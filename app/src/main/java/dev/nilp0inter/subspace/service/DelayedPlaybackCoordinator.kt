package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.DelayedPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.DeferredAudioPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioOperation
import dev.nilp0inter.subspace.channel.capability.OpaqueSynthesizedAudio
import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason
import dev.nilp0inter.subspace.model.DelayedPlaybackOperationId
import dev.nilp0inter.subspace.model.DelayedPlaybackOutcome
import dev.nilp0inter.subspace.model.DelayedPlaybackRequest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Host-owned delivery of durable inbound agent messages.
 *
 * The durable message text is authoritative. Synthesized audio is intentionally held only in
 * memory and can be discarded and regenerated after interruption or process death. The audio
 * port owns route resolution and process-wide audio admission at the instant playback begins;
 * this coordinator never sees or retains an originating PTT route.
 */
class DelayedPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val store: DurableAgentRunStore,
    private val selectedChannel: suspend () -> String?,
    private val operationIsCurrent: suspend (AgentOperationContext) -> Boolean,
    private val synthesis: DelayedPlaybackSynthesisPort,
    private val audio: DelayedPlaybackAudioPort,
    private val onStateChanged: suspend () -> Unit = {},
) : DelayedPlaybackCapability {
    private val mutex = Mutex()
    private val operationIds = mutableMapOf<AgentMessageId, DelayedPlaybackOperationId>()
    private val synthesizedAudio = mutableMapOf<AgentMessageId, OpaqueSynthesizedAudio>()
    private var pumpJob: Job? = null
    @Volatile private var pumpRequested = false
    private var activeMessageId: AgentMessageId? = null

    /**
     * The caller must first commit the final inbound response through [DurableAgentRunStore].
     * Scheduling is deliberately non-blocking: synthesis and playback are host work that may
     * outlive the remote run callback.
     */
    override suspend fun schedule(
        context: AgentOperationContext,
        request: DelayedPlaybackRequest,
    ): DelayedPlaybackOutcome {
        if (!operationIsCurrent(context)) return DelayedPlaybackOutcome.Stale
        val message = inboundMessage(request.responseMessageId, context) ?: return DelayedPlaybackOutcome.Stale
        if (message.text != request.text) return DelayedPlaybackOutcome.Stale
        if (message.lifecycle == DurableMessageLifecycle.HEARD) {
            return DelayedPlaybackOutcome.Heard(operationIdFor(message.id))
        }
        if (message.lifecycle != DurableMessageLifecycle.PENDING && message.lifecycle != DurableMessageLifecycle.PLAYING) {
            return DelayedPlaybackOutcome.Failed(DelayedPlaybackFailureReason.HOST_FAILURE)
        }
        requestPump()
        return DelayedPlaybackOutcome.Pending(operationIdFor(message.id))
    }

    /**
     * Called only from the shared, deliberate channel-selection action. A passive projection
     * refresh must use [onAudioAvailable] instead so it cannot reverse an SOS queue pause.
     */
    fun onChannelSelected(channelInstanceId: String) {
        when (store.resumePlaybackDrain(channelInstanceId)) {
            is DurableAgentStoreResult.Success -> requestPump()
            is DurableAgentStoreResult.Failure -> Unit
        }
    }

    /** Call after any host audio owner releases admission. */
    fun onAudioAvailable() {
        requestPump()
    }

    /**
     * Restores interrupted messages to their durable pending state. No audio artifact is restored:
     * the next selected-channel admission synthesizes from the persisted text again.
     */
    suspend fun reconcileAfterRestart(): DurableAgentStoreResult<DurableRecoveryPlan> {
        synchronized(synthesizedAudio) { synthesizedAudio.clear() }
        return store.reconcileAfterRestart().also { requestPump() }
    }

    fun close() {
        synchronized(synthesizedAudio) { synthesizedAudio.clear() }
        pumpJob?.cancel()
    }

    private fun requestPump() {
        pumpRequested = true
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (true) {
                pumpRequested = false
                val candidate = nextSelectedPendingMessage()
                val activeCandidate = mutex.withLock {
                    if (activeMessageId != null) null else candidate?.also { activeMessageId = it.id }
                } ?: return@launch
                val completed = try {
                    deliver(activeCandidate)
                } finally {
                    mutex.withLock { activeMessageId = null }
                    onStateChanged()
                }
                if (!completed && !pumpRequested) return@launch
            }
        }
    }

    private suspend fun nextSelectedPendingMessage(): DurableAgentMessage? {
        val selected = selectedChannel() ?: return null
        if (store.playbackDrainState(selected) == PlaybackDrainState.PAUSED_BY_USER) return null
        return store.snapshot().messages
            .asSequence()
            .filter {
                it.direction == DurableMessageDirection.INBOUND &&
                    it.channelInstanceId == selected &&
                    it.lifecycle == DurableMessageLifecycle.PENDING
            }
            .sortedWith(compareBy<DurableAgentMessage> { it.createdAtMillis }.thenBy { it.id.value })
            .firstOrNull()
    }

    private suspend fun deliver(message: DurableAgentMessage): Boolean {
        // Selection is re-evaluated at each effect boundary. A selection change during active
        // playback intentionally does not interrupt it; before admission it leaves text pending.
        if (selectedChannel() != message.channelInstanceId) return false
        val artifact = synchronized(synthesizedAudio) { synthesizedAudio[message.id] } ?: when (val result = synthesis.synthesize(message.text)) {
            is DelayedPlaybackSynthesisResult.Success -> result.audio.also {
                synchronized(synthesizedAudio) { synthesizedAudio[message.id] = it }
            }
            DelayedPlaybackSynthesisResult.Cancelled -> return false
            is DelayedPlaybackSynthesisResult.Failed -> return false
        }
        if (selectedChannel() != message.channelInstanceId) return false

        when (store.beginPlayback(message.id)) {
            is DurableAgentStoreResult.Failure -> return false
            is DurableAgentStoreResult.Success -> Unit
        }
        if (selectedChannel() != message.channelInstanceId) {
            store.returnPlaybackToPending(message.id)
            return false
        }

        // The port atomically checks global audio admission, resolves the route at playback time,
        // and plays. It is the sole place route/focus state can enter this lifecycle.
        val result = try {
            audio.playIfAdmitted(message.channelInstanceId, artifact)
        } catch (error: CancellationException) {
            store.returnPlaybackToPending(message.id)
            throw error
        } catch (_: Exception) {
            store.returnPlaybackToPending(message.id)
            return false
        }
        return when (result) {
            DelayedPlaybackAudioResult.Completed -> when (store.markHeard(message.id)) {
                is DurableAgentStoreResult.Success -> {
                    synchronized(synthesizedAudio) { synthesizedAudio.remove(message.id) }
                    true
                }
                is DurableAgentStoreResult.Failure -> {
                    store.returnPlaybackToPending(message.id)
                    false
                }
            }
            DelayedPlaybackAudioResult.ExplicitlySkipped -> when (
                store.skipPlaybackAndPause(message.channelInstanceId, message.id)
            ) {
                is DurableAgentStoreResult.Success -> {
                    synchronized(synthesizedAudio) { synthesizedAudio.remove(message.id) }
                    true
                }
                is DurableAgentStoreResult.Failure -> false
            }
            DelayedPlaybackAudioResult.Busy,
            DelayedPlaybackAudioResult.Interrupted,
            DelayedPlaybackAudioResult.Cancelled,
            is DelayedPlaybackAudioResult.Failed,
            -> {
                store.returnPlaybackToPending(message.id)
                false
            }
        }
    }

    private fun inboundMessage(messageId: AgentMessageId, context: AgentOperationContext): DurableAgentMessage? =
        store.snapshot().messages.firstOrNull {
            it.id == messageId &&
                it.direction == DurableMessageDirection.INBOUND &&
                it.channelInstanceId == context.scope.channelInstanceId &&
                it.runId == context.runId
        }

    private fun operationIdFor(messageId: AgentMessageId): DelayedPlaybackOperationId =
        synchronized(operationIds) {
            operationIds.getOrPut(messageId) { DelayedPlaybackOperationId(UUID.randomUUID().toString()) }
        }
}


/** Semantic synthesis boundary; no samples, paths, engines, or routes cross into the coordinator. */
interface DelayedPlaybackSynthesisPort {
    suspend fun synthesize(text: String): DelayedPlaybackSynthesisResult
}

sealed interface DelayedPlaybackSynthesisResult {
    data class Success(val audio: OpaqueSynthesizedAudio) : DelayedPlaybackSynthesisResult
    data object Cancelled : DelayedPlaybackSynthesisResult
    data class Failed(val reason: DelayedPlaybackFailureReason) : DelayedPlaybackSynthesisResult
}

/**
 * Host audio boundary. [playIfAdmitted] MUST serialize with PTT, announcements, and all other
 * host audio work. It MUST resolve the current route only after admission and before playback.
 */
interface DelayedPlaybackAudioPort {
    suspend fun playIfAdmitted(
        channelInstanceId: String,
        audio: OpaqueSynthesizedAudio,
    ): DelayedPlaybackAudioResult
}

sealed interface DelayedPlaybackAudioResult {
    data object Busy : DelayedPlaybackAudioResult
    data object Completed : DelayedPlaybackAudioResult
    data object ExplicitlySkipped : DelayedPlaybackAudioResult
    data object Interrupted : DelayedPlaybackAudioResult
    data object Cancelled : DelayedPlaybackAudioResult
    data class Failed(val reason: DelayedPlaybackFailureReason) : DelayedPlaybackAudioResult
}

/**
 * In-memory, selection-aware deferred playback of opaque pre-produced audio (e.g. Debug ECHO).
 * The artifact is never persisted: raw captured audio is not durable-text-regenerable. The host
 * retries after terminal cleanup using the same half-duplex admission and current-mode routing as
 * text-based delayed playback. Selection is re-evaluated at each admission boundary; if the channel
 * is no longer selected the artifact is discarded (not retried on a different channel).
 */
class DeferredAudioPlaybackCoordinator(
    private val scope: CoroutineScope,
    private val selectedChannel: suspend () -> String?,
    private val operationIsCurrent: suspend (AgentOperationContext) -> Boolean,
    private val audio: DeferredAudioPlaybackAudioPort,
    private val onStateChanged: suspend () -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : DeferredAudioPlaybackCapability {
    private val mutex = Mutex()
    private val pending = mutableListOf<DeferredAudioEntry>()
    private val wakeJobs = mutableMapOf<DelayedPlaybackOperationId, Job>()
    private var pumpJob: Job? = null
    @Volatile private var pumpRequested = false
    private var activeEntry: DeferredAudioEntry? = null

    private val _pendingCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    /**
     * Read-only per-channel deferred opaque-audio pending counts, keyed by stable channel ID.
     * Zero entries are omitted. Updates are published atomically after queue add/remove/discard/
     * close. Busy/interrupted/cancelled/failed entries remain counted; only completion, explicit
     * skip, selection-discard, or close decrement/clear. Collecting this flow never schedules a
     * pump, so projecting counts cannot wake the same queue.
     */
    val pendingCounts: StateFlow<Map<String, Int>> = _pendingCounts.asStateFlow()

    private fun publishPendingCounts() {
        val now = nowMillis()
        _pendingCounts.value = synchronized(pending) {
            pending.asSequence()
                .filter { it.eligibleAtMillis <= now }
                .groupingBy { it.channelInstanceId }
                .eachCount()
        }
    }

    override suspend fun scheduleAudio(
        context: AgentOperationContext,
        audio: OpaqueAudioOperation,
        eligibilityDelayMillis: Long,
    ): DelayedPlaybackOutcome {
        if (!operationIsCurrent(context)) return DelayedPlaybackOutcome.Stale
        val now = nowMillis()
        val entry = DeferredAudioEntry(
            operationId = DelayedPlaybackOperationId(UUID.randomUUID().toString()),
            channelInstanceId = context.scope.channelInstanceId,
            audio = audio,
            eligibleAtMillis = now + eligibilityDelayMillis,
        )
        synchronized(pending) {
            pending.add(entry)
        }
        if (eligibilityDelayMillis > 0L) {
            scheduleEligibilityWake(entry, eligibilityDelayMillis)
        } else {
            publishPendingCounts()
            requestPump()
        }
        return DelayedPlaybackOutcome.Pending(entry.operationId)
    }

    /** Call after any host audio owner releases admission. */
    fun onAudioAvailable() {
        requestPump()
    }

    /** Call from the shared, deliberate channel-selection action. */
    fun onChannelSelected(channelInstanceId: String) {
        requestPump()
    }

    fun close() {
        synchronized(pending) {
            pending.clear()
            wakeJobs.values.toList().also { wakeJobs.clear() }
        }.forEach { it.cancel() }
        pumpJob?.cancel()
        publishPendingCounts()
    }

    private fun scheduleEligibilityWake(entry: DeferredAudioEntry, delayMillis: Long) {
        val job = scope.launch {
            kotlinx.coroutines.delay(delayMillis)
            synchronized(pending) { wakeJobs.remove(entry.operationId) }
            publishPendingCounts()
            requestPump()
        }
        synchronized(pending) { wakeJobs[entry.operationId] = job }
    }

    private fun requestPump() {
        pumpRequested = true
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (true) {
                pumpRequested = false
                val candidate = nextSelectedPendingEntry()
                val active = mutex.withLock {
                    if (activeEntry != null) null else candidate?.also { activeEntry = it }
                } ?: return@launch
                val completed = try {
                    deliver(active)
                } finally {
                    mutex.withLock { activeEntry = null }
                    onStateChanged()
                }
                if (!completed && !pumpRequested) return@launch
            }
        }
    }

    /**
     * Returns the FIFO head of the selected channel's pending entries iff it is eligible
     * (its host-side delay has elapsed). A not-yet-eligible head returns null so later
     * same-channel entries cannot overtake it; determinism is preserved by strict FIFO.
     */
    private suspend fun nextSelectedPendingEntry(): DeferredAudioEntry? {
        val selected = selectedChannel() ?: return null
        val now = nowMillis()
        return synchronized(pending) {
            val head = pending.firstOrNull { it.channelInstanceId == selected } ?: return@synchronized null
            if (now >= head.eligibleAtMillis) head else null
        }
    }

    private suspend fun deliver(entry: DeferredAudioEntry): Boolean {
        if (selectedChannel() != entry.channelInstanceId) {
            synchronized(pending) { pending.remove(entry) }
            publishPendingCounts()
            return false
        }
        val result = try {
            audio.playOperationIfAdmitted(entry.channelInstanceId, entry.audio)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        return when (result) {
            DelayedPlaybackAudioResult.Completed,
            DelayedPlaybackAudioResult.ExplicitlySkipped,
            -> {
                synchronized(pending) { pending.remove(entry) }
                publishPendingCounts()
                true
            }
            DelayedPlaybackAudioResult.Busy,
            DelayedPlaybackAudioResult.Interrupted,
            DelayedPlaybackAudioResult.Cancelled,
            is DelayedPlaybackAudioResult.Failed,
            -> false
        }
    }
}

private data class DeferredAudioEntry(
    val operationId: DelayedPlaybackOperationId,
    val channelInstanceId: String,
    val audio: OpaqueAudioOperation,
    val eligibleAtMillis: Long = 0L,
)

/**
 * Host audio boundary for deferred opaque-audio playback. [playOperationIfAdmitted] MUST
 * serialize with PTT, announcements, and all other host audio work. It MUST resolve the
 * current route only after admission and before playback.
 */
interface DeferredAudioPlaybackAudioPort {
    suspend fun playOperationIfAdmitted(
        channelInstanceId: String,
        audio: OpaqueAudioOperation,
    ): DelayedPlaybackAudioResult
}
