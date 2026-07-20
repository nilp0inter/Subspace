package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.DelayedPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.DeferredAudioPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioOperation
import dev.nilp0inter.subspace.channel.capability.OpaqueSynthesizedAudio
import dev.nilp0inter.subspace.channel.capability.dispose
import dev.nilp0inter.subspace.channel.capability.generationOf
import dev.nilp0inter.subspace.channel.capability.retainedBytesOf
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
        val discarded = synchronized(synthesizedAudio) { synthesizedAudio.values.toList().also { synthesizedAudio.clear() } }
        discarded.forEach { it.dispose() }
        return store.reconcileAfterRestart().also { requestPump() }
    }

    fun close() {
        val discarded = synchronized(synthesizedAudio) { synthesizedAudio.values.toList().also { synthesizedAudio.clear() } }
        discarded.forEach { it.dispose() }
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
                    synchronized(synthesizedAudio) { synthesizedAudio.remove(message.id) }?.dispose()
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
                    synchronized(synthesizedAudio) { synthesizedAudio.remove(message.id) }?.dispose()
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
    private val limits: DeferredAudioPlaybackCoordinator.Limits = DeferredAudioPlaybackCoordinator.Limits.DEFAULT,
    private val processQuota: DeferredAudioPlaybackCoordinator.ProcessQuota =
        DeferredAudioPlaybackCoordinator.ProcessQuota.DEFAULT,
) : DeferredAudioPlaybackCapability, GenerationCapabilityResource {
    private val mutex = Mutex()
    private val pending = mutableListOf<DeferredAudioEntry>()
    private val wakeJobs = mutableMapOf<DelayedPlaybackOperationId, Job>()
    private var pumpJob: Job? = null
    @Volatile private var pumpRequested = false
    @Volatile private var closed = false
    private var activeEntry: DeferredAudioEntry? = null
    private val revokedGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, dev.nilp0inter.subspace.channel.capability.RuntimeGeneration>>()

    // Per-instance quota counters (this coordinator only).
    private var instanceEntries: Int = 0
    private var instanceBytes: Long = 0L
    // Per-channel-instance quota counters, keyed by stable channel ID.
    private val channelEntries = mutableMapOf<String, Int>()
    private val channelBytes = mutableMapOf<String, Long>()
    // Per-runtime-generation quota counters; RuntimeGeneration is process-unique.
    private val generationEntries = mutableMapOf<dev.nilp0inter.subspace.channel.capability.RuntimeGeneration, Int>()
    private val generationBytes = mutableMapOf<dev.nilp0inter.subspace.channel.capability.RuntimeGeneration, Long>()

    private fun isRevoked(channelInstanceId: String, generation: dev.nilp0inter.subspace.channel.capability.RuntimeGeneration): Boolean =
        revokedGenerations.contains(channelInstanceId to generation)

    private fun isAuthorized(entry: DeferredAudioEntry): Boolean =
        !closed && !isRevoked(entry.channelInstanceId, entry.quotaGeneration)
    /** Finite positive limits for this deferred playback coordinator instance. */
    data class Limits(
        val maxEntriesPerInstance: Int,
        val maxBytesPerInstance: Long,
        val maxEntriesPerGeneration: Int,
        val maxBytesPerGeneration: Long,
    ) {
        init {
            require(maxEntriesPerInstance > 0) { "maxEntriesPerInstance must be positive: $maxEntriesPerInstance" }
            require(maxBytesPerInstance > 0L) { "maxBytesPerInstance must be positive: $maxBytesPerInstance" }
            require(maxEntriesPerGeneration > 0) { "maxEntriesPerGeneration must be positive: $maxEntriesPerGeneration" }
            require(maxBytesPerGeneration > 0L) { "maxBytesPerGeneration must be positive: $maxBytesPerGeneration" }
        }

        companion object {
            val DEFAULT = Limits(
                maxEntriesPerInstance = 32,
                maxBytesPerInstance = 8L * 1024 * 1024,
                maxEntriesPerGeneration = 32,
                maxBytesPerGeneration = 8L * 1024 * 1024,
            )
        }
    }

    /** Shared process-wide deferred playback quota accountant. */
    class ProcessQuota(
        maxEntries: Int,
        maxBytes: Long,
    ) {
        init {
            require(maxEntries > 0) { "maxEntries must be positive: $maxEntries" }
            require(maxBytes > 0L) { "maxBytes must be positive: $maxBytes" }
        }

        private val maxEntries = maxEntries
        private val maxBytes = maxBytes
        private var entriesUsed: Int = 0
        private var bytesUsed: Long = 0L

        @Synchronized
        internal fun tryReserve(entries: Int, bytes: Long): Boolean {
            if (entries < 0 || bytes < 0L) return false
            val nextEntries = entriesUsed + entries
            val nextBytes = bytesUsed + bytes
            if (nextEntries < entriesUsed || nextBytes < bytesUsed) return false
            if (nextEntries > maxEntries) return false
            if (nextBytes > maxBytes) return false
            entriesUsed = nextEntries
            bytesUsed = nextBytes
            return true
        }

        @Synchronized
        internal fun forceReserve(entries: Int, bytes: Long) {
            entriesUsed += entries
            bytesUsed += bytes
        }

        @Synchronized
        internal fun release(entries: Int, bytes: Long) {
            entriesUsed -= entries
            bytesUsed -= bytes
        }

        @Synchronized
        fun liveEntries(): Int = entriesUsed

        @Synchronized
        fun retainedBytes(): Long = bytesUsed

        companion object {
            val DEFAULT = ProcessQuota(maxEntries = 256, maxBytes = 64L * 1024 * 1024)
        }
    }

    data class ChannelAccounting(val entries: Int, val bytes: Long)

    internal data class Accounting(
        val liveEntries: Int,
        val retainedBytes: Long,
        val perChannel: Map<String, ChannelAccounting>,
    )

    /** Snapshot of this coordinator's current quota accounting. */
    internal fun accounting(): Accounting = synchronized(pending) {
        Accounting(
            liveEntries = instanceEntries,
            retainedBytes = instanceBytes,
            perChannel = channelEntries.mapValues { (channel, entries) ->
                ChannelAccounting(entries = entries, bytes = channelBytes[channel] ?: 0L)
            },
        )
    }

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
        if (!operationIsCurrent(context)) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (isRevoked(context.scope.channelInstanceId, context.scope.runtimeGeneration)) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        val operationGeneration = generationOf(audio)
        if (operationGeneration != null && isRevoked(context.scope.channelInstanceId, operationGeneration)) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (operationGeneration != null && operationGeneration != context.scope.runtimeGeneration) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (eligibilityDelayMillis < 0L) {
            return DelayedPlaybackOutcome.Failed(DelayedPlaybackFailureReason.HOST_FAILURE)
        }

        val channel = context.scope.channelInstanceId
        val generation = context.scope.runtimeGeneration
        val generationKey = generation
        val retainedBytes = retainedBytesOf(audio)
        if (retainedBytes < 0L) {
            return DelayedPlaybackOutcome.Busy
        }

        val now = nowMillis()
        val entry = DeferredAudioEntry(
            operationId = DelayedPlaybackOperationId(UUID.randomUUID().toString()),
            channelInstanceId = channel,
            audio = audio,
            generation = operationGeneration,
            quotaGeneration = generation,
            eligibleAtMillis = saturatingAdd(now, eligibilityDelayMillis),
            retainedBytes = retainedBytes,
        )

        // Admission preflights every scope and reserves atomically before the
        // entry is visible to the queue. No sibling can be evicted and no
        // caller-owned artifact is consumed until this succeeds.
        val admitted: Boolean? = synchronized(pending) {
            if (closed || isRevoked(channel, generation)) {
                null
            } else {
                val nextInstanceEntries = instanceEntries + 1
                val nextInstanceBytes = instanceBytes + retainedBytes
                val nextChannelEntries = (channelEntries[channel] ?: 0) + 1
                val nextChannelBytes = (channelBytes[channel] ?: 0L) + retainedBytes
                val nextGenerationEntries = (generationEntries[generationKey] ?: 0) + 1
                val nextGenerationBytes = (generationBytes[generationKey] ?: 0L) + retainedBytes

                val localWithinBounds =
                    nextInstanceEntries > instanceEntries &&
                        nextInstanceBytes >= instanceBytes &&
                        nextChannelEntries > (channelEntries[channel] ?: 0) &&
                        nextChannelEntries <= limits.maxEntriesPerInstance &&
                        nextChannelBytes >= (channelBytes[channel] ?: 0L) &&
                        nextChannelBytes <= limits.maxBytesPerInstance &&
                        nextGenerationEntries > (generationEntries[generationKey] ?: 0) &&
                        nextGenerationEntries <= limits.maxEntriesPerGeneration &&
                        nextGenerationBytes >= (generationBytes[generationKey] ?: 0L) &&
                        nextGenerationBytes <= limits.maxBytesPerGeneration
                if (!localWithinBounds || !processQuota.tryReserve(1, retainedBytes)) {
                    false
                } else {
                    instanceEntries = nextInstanceEntries
                    instanceBytes = nextInstanceBytes
                    channelEntries[channel] = nextChannelEntries
                    channelBytes[channel] = nextChannelBytes
                    generationEntries[generationKey] = nextGenerationEntries
                    generationBytes[generationKey] = nextGenerationBytes
                    pending.add(entry)
                    true
                }
            }
        }
        if (admitted == null) {
            dispose(audio)
            return DelayedPlaybackOutcome.Stale
        }
        if (!admitted) {
            return DelayedPlaybackOutcome.Busy
        }
        if (eligibilityDelayMillis > 0L) {
            scheduleEligibilityWake(entry)
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
    override suspend fun onGenerationTermination(
        identity: dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity,
        termination: dev.nilp0inter.subspace.channel.capability.CapabilityLeaseTermination,
    ) {
        if (termination != dev.nilp0inter.subspace.channel.capability.CapabilityLeaseTermination.REVOKED) return
        synchronized(revokedGenerations) {
            revokedGenerations += identity.channelInstanceId to identity.runtimeGeneration
        }
        val removed = synchronized(pending) {
            pending.filter {
                it.channelInstanceId == identity.channelInstanceId &&
                    it.quotaGeneration == identity.runtimeGeneration
            }.also { entries ->
                entries.forEach { pending.remove(it) }
                entries.forEach { entry ->
                    val channel = entry.channelInstanceId
                    val generationKey = entry.quotaGeneration
                    instanceEntries -= 1
                    instanceBytes -= entry.retainedBytes
                    val channelEntryCount = (channelEntries[channel] ?: 1) - 1
                    val channelByteCount = (channelBytes[channel] ?: entry.retainedBytes) - entry.retainedBytes
                    if (channelEntryCount <= 0) channelEntries.remove(channel) else channelEntries[channel] = channelEntryCount
                    if (channelByteCount <= 0L) channelBytes.remove(channel) else channelBytes[channel] = channelByteCount
                    val generationEntryCount = (generationEntries[generationKey] ?: 1) - 1
                    val generationByteCount = (generationBytes[generationKey] ?: entry.retainedBytes) - entry.retainedBytes
                    if (generationEntryCount <= 0) generationEntries.remove(generationKey) else generationEntries[generationKey] = generationEntryCount
                    if (generationByteCount <= 0L) generationBytes.remove(generationKey) else generationBytes[generationKey] = generationByteCount
                    processQuota.release(1, entry.retainedBytes)
                }
            }
        }
        removed.forEach { dispose(it.audio) }
        publishPendingCounts()
        // An in-flight predecessor may have been the pump's active entry. Wake the pump so
        // unaffected sibling/successor generations are still considered after it observes stale.
        if (removed.isNotEmpty()) requestPump()
    }

    fun close() {
        val removed = synchronized(pending) {
            closed = true
            pending.toList().also {
                pending.clear()
                wakeJobs.values.toList().also { jobs -> wakeJobs.clear(); jobs.forEach { it.cancel() } }
                it.forEach { entry -> processQuota.release(1, entry.retainedBytes) }
                clearAccounting()
            }
        }
        removed.forEach { dispose(it.audio) }
        pumpJob?.cancel()
        publishPendingCounts()
    }
    /** Remove one queued entry and release every scope exactly once. */
    private fun removeAndRelease(entry: DeferredAudioEntry): Boolean = synchronized(pending) {
        if (!isAuthorized(entry)) return@synchronized false
        if (!pending.remove(entry)) return@synchronized false
        val channel = entry.channelInstanceId
        val generationKey = entry.quotaGeneration
        instanceEntries -= 1
        instanceBytes -= entry.retainedBytes
        val channelEntryCount = (channelEntries[channel] ?: 1) - 1
        val channelByteCount = (channelBytes[channel] ?: entry.retainedBytes) - entry.retainedBytes
        if (channelEntryCount <= 0) channelEntries.remove(channel) else channelEntries[channel] = channelEntryCount
        if (channelByteCount <= 0L) channelBytes.remove(channel) else channelBytes[channel] = channelByteCount
        val generationEntryCount = (generationEntries[generationKey] ?: 1) - 1
        val generationByteCount = (generationBytes[generationKey] ?: entry.retainedBytes) - entry.retainedBytes
        if (generationEntryCount <= 0) generationEntries.remove(generationKey) else generationEntries[generationKey] = generationEntryCount
        if (generationByteCount <= 0L) generationBytes.remove(generationKey) else generationBytes[generationKey] = generationByteCount
        processQuota.release(1, entry.retainedBytes)
        true
    }

    /** Release all accounting, used by close after pending entries are detached. */
    private fun clearAccounting() {
        instanceEntries = 0
        instanceBytes = 0L
        channelEntries.clear()
        channelBytes.clear()
        generationEntries.clear()
        generationBytes.clear()
    }


    /**
     * Wake at the entry's absolute host eligibility time. The injected clock is authoritative for
     * eligibility, while coroutine delay only provides a wakeup. Rechecking the absolute deadline
     * prevents an early wake (clock adjustment) from losing the only retry and avoids real-time or
     * Lua-side sleeps.
     */
    private fun scheduleEligibilityWake(entry: DeferredAudioEntry) {
        val job = scope.launch {
            while (true) {
                val remaining = synchronized(pending) {
                    if (!pending.contains(entry)) return@launch
                    millisUntil(entry.eligibleAtMillis, nowMillis())
                }
                if (remaining <= 0L) break
                kotlinx.coroutines.delay(remaining)
            }
            synchronized(pending) { wakeJobs.remove(entry.operationId) }
            publishPendingCounts()
            requestPump()
        }
        synchronized(pending) { wakeJobs[entry.operationId] = job }
    }

    private fun saturatingAdd(base: Long, delta: Long): Long = try {
        Math.addExact(base, delta)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun millisUntil(deadline: Long, now: Long): Long = try {
        if (now >= deadline) 0L else Math.subtractExact(deadline, now)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
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
        if (!isAuthorized(entry)) {
            if (removeAndRelease(entry)) dispose(entry.audio)
            publishPendingCounts()
            return false
        }
        // Selection is checked immediately before admission. If it changed after the candidate
        // snapshot, retain the entry at its channel FIFO head; never route it through the newly
        // selected channel and do not consume/dispose the still-authorized artifact.
        if (selectedChannel() != entry.channelInstanceId) {
            return false
        }
        if (!isAuthorized(entry)) {
            if (removeAndRelease(entry)) dispose(entry.audio)
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
        // A backend may complete after revocation/close. Do not interpret that completion as a
        // successful playback or release accounting a successor now owns.
        if (!isAuthorized(entry)) return false
        return when (result) {
            DelayedPlaybackAudioResult.Completed,
            DelayedPlaybackAudioResult.ExplicitlySkipped,
            -> {
                if (!removeAndRelease(entry)) return false
                dispose(entry.audio)
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
    val generation: dev.nilp0inter.subspace.channel.capability.RuntimeGeneration?,
    val quotaGeneration: dev.nilp0inter.subspace.channel.capability.RuntimeGeneration,
    val eligibleAtMillis: Long = 0L,
    val retainedBytes: Long = 0L,
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
