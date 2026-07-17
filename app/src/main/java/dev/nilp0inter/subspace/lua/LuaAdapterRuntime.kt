package dev.nilp0inter.subspace.lua

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.lua.actor.ActorRuntime
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ActorRuntimeHostContext
import dev.nilp0inter.subspace.model.Disposable
import dev.nilp0inter.subspace.model.GenerationAdmission
import dev.nilp0inter.subspace.model.GenerationExecutionContext
import dev.nilp0inter.subspace.service.ChannelActivationResult
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import dev.nilp0inter.subspace.service.ChannelRuntime
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import dev.nilp0inter.subspace.lua.actor.ActorGateResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * A [ChannelRuntime] backed by one Lua actor instance.
 *
 * Owns the actor, bridge, state handle, and lifecycle callback handles from
 * a successfully loaded program image. Manages snapshot projection, readiness
 * caching, input lifecycle, SOS fire-and-forget dispatch, and idempotent close.
 *
 * Construction does not enter Lua; the program image is loaded and callbacks
 * extracted during construction. [activate] invokes startup and lifecycle-ready
 * callbacks. [prepareInput] never enters Lua — it returns from cached readiness.
 * [close] is idempotent and suppresses all late effects.
 */
internal class LuaAdapterRuntime(
    private val definition: ChannelDefinition,
    private val actor: ActorRuntime,
    private val generationContext: GenerationExecutionContext,
    private val stateHandle: LuaStateHandle,
    private val bridge: LuaKernelBridge,
    private val callbacks: Map<String, LuaCallbackHandle>,
) : ChannelRuntime {

    private val closed = AtomicBoolean(false)
    private val activated = AtomicBoolean(false)
    private val activationClaimed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val logLock = Any()
    private val logs = ArrayDeque<LuaAdapterLogRecord>(MAX_LOG_RECORDS)
    private val localDiagnostics = ArrayDeque<String>(MAX_LOCAL_DIAGNOSTICS)
    private val sleepLock = Any()
    private val activeSleepWaits = mutableSetOf<ActiveSleepWait>()

    internal val logSnapshot: List<LuaAdapterLogRecord>
        get() = synchronized(logLock) { logs.toList() }

    // Cached readiness, initially false per spec neutral default.
    // Updated by refreshReadiness -> handle_readiness; read by prepareInput.
    private val cachedReadiness = AtomicBoolean(false)


    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            implementationId = definition.implementationId,
            enabled = definition.enabled,
            preparation = if (definition.enabled)
                ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
            else
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled),
            executionStatus = ChannelExecutionStatus.IDLE,
        ),
    )

    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    override val id: String get() = definition.id

    override val readinessRefreshIntervalMillis: Long = READINESS_REFRESH_INTERVAL_MILLIS

    private companion object {
        const val MAX_LOG_RECORDS = 128
        const val MAX_LOCAL_DIAGNOSTICS = 32
        const val MAX_SLEEP_SECONDS = 86_400.0
        const val READINESS_REFRESH_INTERVAL_MILLIS = 5_000L
    }

    // ── Activate ──────────────────────────────────────────────────────────

    /**
     * Runs the synchronous activation sequence:
     * 1. Invoke startup() — non-yielding, non-spawning (except via context).
     * 2. Invoke handle_lifecycle({event = "ready"}) if present.
     * 3. Atomically claim Ready state.
     *
     * On any failure, invokes [close] to discard every staged task and
     * transitions to the failed snapshot state.
     */
    override suspend fun activate(): ChannelActivationResult {
        if (!activationClaimed.compareAndSet(false, true) || closed.get() || !generationContext.isActive() ||
            !actor.stageProgramImage() || !actor.claimProgramImageStartup()
        ) {
            return ChannelActivationResult.Failed("Lua runtime is closed or not staged")
        }
        val startupOutcome = invokeStartupCallback().asGeneralCallbackOutcome()
        if (startupOutcome !is CallbackInvocationResult.Success) {
            discardStagedAndClose()
            return ChannelActivationResult.Failed("startup failed: ${startupOutcome.diagnostic()}")
        }

        callbacks["handle_lifecycle"]?.let { lifecycleHandle ->
            val lifecycleOutcome = invokeCallbackHandle(
                lifecycleHandle,
                LuaValue.Map(mapOf("event" to LuaValue.StringValue("ready"))),
            ).asGeneralCallbackOutcome()
            if (lifecycleOutcome !is CallbackInvocationResult.Success) {
                discardStagedAndClose()
                return ChannelActivationResult.Failed("handle_lifecycle failed: ${lifecycleOutcome.diagnostic()}")
            }
        }

        // Publish the actor before returning Ready. The registry separately
        // authorizes context-staged tasks only after this activation result.
        if (closed.get() || !generationContext.isActive() || !activated.compareAndSet(false, true) ||
            !actor.publishProgramImageLive()
        ) {
            discardStagedAndClose()
            return ChannelActivationResult.Failed("Lua runtime closed during activation")
        }

        updateSnapshot(
            preparation = ChannelPreparationAvailability.Available,
            executionStatus = ChannelExecutionStatus.IDLE,
        )

        return ChannelActivationResult.Ready
    }

    // ── prepareInput ──────────────────────────────────────────────────────

    /**
     * Returns input acceptance based entirely on cached readiness + presence
     * of handle_input. NEVER enters Lua.
     *
     * Per spec: readiness is cached from [refreshReadiness] calls.
     * handle_input presence is determined at construction.
     */
    override suspend fun prepareInput(): ChannelInputAcceptance {
        if (closed.get()) return ChannelInputAcceptance.Unavailable("Lua runtime is closed")
        if (!definition.enabled) return ChannelInputAcceptance.Refused("Channel is disabled")
        if (!activated.get()) return ChannelInputAcceptance.Unavailable("Lua runtime not yet activated")

        val hasHandleInput = callbacks.containsKey("handle_input")
        if (!hasHandleInput) {
            return ChannelInputAcceptance.Refused("Input not supported (no handle_input callback)")
        }
        if (!cachedReadiness.get()) {
            return ChannelInputAcceptance.Refused("Channel is not ready")
        }

        return ChannelInputAcceptance.Accepted(LuaInputTarget())
    }

    // ── refreshReadiness ──────────────────────────────────────────────────

    /**
     * Invokes handle_readiness() and caches the result. On any failure
     * (throw, error table, non-table, invalid ready field, yield) caches
     * not-ready and logs a diagnostic locally without failing the actor.
     *
     * Neutral default (no callback): cached as not-ready.
     */
    override suspend fun refreshReadiness() {
        if (closed.get()) return

        val readinessHandle = callbacks["handle_readiness"]
        if (readinessHandle == null) {
            cachedReadiness.set(false)
            return
        }

        val outcome = invokeCallbackHandle(readinessHandle, LuaValue.Map(emptyMap()))
        cachedReadiness.set(when (outcome) {
            is CallbackInvocationResult.Success -> extractReadinessFromReturn(outcome.value)
            is CallbackInvocationResult.Failure,
            is CallbackInvocationResult.YieldViolation,
            is CallbackInvocationResult.InvalidOutcome -> false
        })
    }

    // ── handleSos ─────────────────────────────────────────────────────────

    /**
     * Fire-and-forget SOS dispatch. Invokes handle_sos({event = "sos"}) if
     * present. All failures are local-contained: logged, no snapshot mutation,
     * no generation failure.
     */
    override suspend fun handleSos() {
        if (closed.get()) return
        val sosHandle = callbacks["handle_sos"]
        if (sosHandle == null) return

        val sosArg = LuaValue.Map(mapOf("event" to LuaValue.StringValue("sos")))
        when (invokeCallbackHandle(sosHandle, sosArg).asGeneralCallbackOutcome()) {
            is CallbackInvocationResult.Success -> Unit
            is CallbackInvocationResult.Failure,
            is CallbackInvocationResult.YieldViolation,
            is CallbackInvocationResult.InvalidOutcome -> Unit // local-contained
        }
    }

    // ── close ─────────────────────────────────────────────────────────────

    /**
     * Idempotent close: stop admission, dispose timers via context, cancel
     * and join background tasks, close actor Lua state once, suppress all
     * late effects.
     *
     * After close the snapshot is terminal-unavailable and no further Lua
     * entry occurs.
     */
    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        cachedReadiness.set(false)
        synchronized(logLock) {
            logs.clear()
            localDiagnostics.clear()
        }
        val sleepWaits = synchronized(sleepLock) {
            activeSleepWaits.toList().also { activeSleepWaits.clear() }
        }
        sleepWaits.forEach { sleep ->
            sleep.timer.dispose()
            sleep.deadline.dispose()
            sleep.terminal.complete(false)
        }
        discardStagedTasks()
        actor.close()
        synchronized(lifecycleLock) {
            _snapshot.value = _snapshot.value.copy(
                preparation = ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
                executionStatus = ChannelExecutionStatus.IDLE,
            )
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Invoke a named callback via the bridge, parsing the outcome into our
     * internal result type.
     */
    private suspend fun invokeCallback(
        name: String,
        arguments: LuaValue,
    ): CallbackInvocationResult {
        val handle = callbacks[name]
            ?: return CallbackInvocationResult.Failure("callback '$name' not found")
        return invokeCallbackHandle(handle, arguments)
    }

    private suspend fun invokeStartupCallback(): CallbackInvocationResult {
        val handle = callbacks["startup"] ?: return CallbackInvocationResult.Failure("callback 'startup' not found")
        return invokeCallbackHandle(handle, LuaValue.Nil)
    }

    /**
     * Invoke a callback handle via the bridge. The bridge enforces
     * non-yielding, protected execution, and contract validation.
     */
    private suspend fun invokeCallbackHandle(
        handle: LuaCallbackHandle,
        arguments: LuaValue,
    ): CallbackInvocationResult {
        if (closed.get() || !generationContext.isActive()) {
            return CallbackInvocationResult.Failure("runtime is closed")
        }
        val admission = ScopedSpawnAdmission()
        val gateOutcome = try {
            if (handle.name == "startup") {
                actor.invokeProgramImageStartup(handle, admission)
            } else {
                actor.invokeProgramImageCallback(handle, arguments, admission)
            }
        } catch (error: Throwable) {
            admission.release(false)
            return failProgramImage("callback bridge failure: ${error.message ?: error::class.simpleName}")
        }
        when (val release = admission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> return failProgramImage(release.diagnostic)
        }
        val outcome = when (gateOutcome) {
            is ActorGateResult.Success -> gateOutcome.value
            else -> return CallbackInvocationResult.Failure("callback gate is unavailable")
        }
        return when (outcome) {
            is LuaKernelOutcome.Completed -> consumeCompletedOutcome(outcome)
            is LuaKernelOutcome.RuntimeFailure -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.MemoryFailure -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Interrupted -> CallbackInvocationResult.Failure(outcome.diagnostic ?: "interrupted")
            is LuaKernelOutcome.Cancelled -> CallbackInvocationResult.Failure("callback cancelled")
            is LuaKernelOutcome.ValidationFailure -> CallbackInvocationResult.InvalidOutcome(outcome.diagnostic)
            is LuaKernelOutcome.InvalidOwnership -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Stale -> CallbackInvocationResult.YieldViolation("stale generation")
            is LuaKernelOutcome.Closed -> CallbackInvocationResult.Failure("state is closed")
            is LuaKernelOutcome.Yielded -> CallbackInvocationResult.YieldViolation(
                "callback yielded operation ${outcome.operationId}",
            )
            is LuaKernelOutcome.Created,
            is LuaKernelOutcome.SyntaxFailure,
            is LuaKernelOutcome.Snapshot -> CallbackInvocationResult.Failure(
                "unexpected bridge outcome: ${outcome::class.simpleName}",
            )
        }
    }

    /** Drains only non-admission side effects; admission occurred inside JNI. */
    private fun consumeCompletedOutcome(outcome: LuaKernelOutcome.Completed): CallbackInvocationResult {
        consumeNativeLogs(outcome.logs)
        return luaValueFromOutcome(outcome.value)
    }

    /**
     * JNI invokes [admitTask] while Lua is still in the originating slice.
     * Accepted context tasks begin only after a completed or yielded native
     * slice returns the exact ordered IDs admitted synchronously.
     */
    private sealed class SpawnAdmissionRelease {
        data object Matched : SpawnAdmissionRelease()
        data class Mismatch(val diagnostic: String) : SpawnAdmissionRelease()
    }

    private inner class ScopedSpawnAdmission : LuaSpawnAdmission {
        private val pending = mutableListOf<Pair<Long, CompletableDeferred<Boolean>>>()

        override fun admitTask(coroutineId: Long): Int {
            val decision = CompletableDeferred<Boolean>()
            return when (generationContext.admitTask {
                if (decision.await()) runBackgroundCoroutine(LuaCoroutineId(coroutineId))
            }) {
                is GenerationAdmission.Accepted -> {
                    pending += coroutineId to decision
                    0
                }
                is GenerationAdmission.Rejected -> if (generationContext.isActive()) 2 else 1
            }
        }

        fun releaseFor(outcome: LuaKernelOutcome): SpawnAdmissionRelease {
            val accepted = pending.map { it.first }
            val matched = when (outcome) {
                is LuaKernelOutcome.Completed -> outcome.spawnedCoroutines.orEmpty() == accepted
                is LuaKernelOutcome.Yielded -> outcome.spawnedCoroutines.orEmpty() == accepted
                else -> accepted.isEmpty()
            }
            val release = matched && (outcome is LuaKernelOutcome.Completed || outcome is LuaKernelOutcome.Yielded)
            pending.forEach { it.second.complete(release) }
            return if (matched) SpawnAdmissionRelease.Matched else {
                SpawnAdmissionRelease.Mismatch(
                    "native spawn admission mismatch: accepted=$accepted outcome=${outcome::class.simpleName}",
                )
            }
        }

        fun releaseFor(result: ActorGateResult<LuaKernelOutcome>): SpawnAdmissionRelease = when (result) {
            is ActorGateResult.Success -> releaseFor(result.value)
            else -> {
                val accepted = pending.map { it.first }
                val matched = accepted.isEmpty()
                pending.forEach { it.second.complete(false) }
                if (matched) SpawnAdmissionRelease.Matched else {
                    SpawnAdmissionRelease.Mismatch(
                        "native spawn admission mismatch: accepted=$accepted gate=${result::class.simpleName}",
                    )
                }
            }
        }

        fun release(run: Boolean) {
            pending.forEach { it.second.complete(run) }
        }
    }

    private class ActiveSleepWait(
        val terminal: CompletableDeferred<Boolean>,
        val timer: Disposable,
        val deadline: Disposable,
    )
    /** Runs one native coroutine without recursion, retaining task capacity while asleep. */
    private suspend fun runBackgroundCoroutine(initialCoroutineId: LuaCoroutineId) {
        suspend fun resumeSlice(operation: LuaOperationHandle, success: Boolean, value: String): ActorGateResult<LuaKernelOutcome>? {
            val admission = ScopedSpawnAdmission()
            val result = actor.resumeProgramImageCoroutine(operation, success, value, admission)
            return when (val release = admission.releaseFor(result)) {
                SpawnAdmissionRelease.Matched -> result
                is SpawnAdmissionRelease.Mismatch -> {
                    failProgramImage(release.diagnostic)
                    null
                }
            }
        }

        val startAdmission = ScopedSpawnAdmission()
        var gateOutcome = actor.startProgramImageCoroutine(initialCoroutineId, startAdmission)
        when (val release = startAdmission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> {
                failProgramImage(release.diagnostic)
                return
            }
        }
        while (!closed.get() && generationContext.isActive()) {
            val outcome = when (gateOutcome) {
                is ActorGateResult.Success -> gateOutcome.value
                else -> return
            }
            when (outcome) {
                is LuaKernelOutcome.Completed -> {
                    val completion = consumeCompletedOutcome(outcome)
                    if (completion !is CallbackInvocationResult.Success) {
                        failProgramImage("malformed background completion: ${completion.diagnostic()}")
                    }
                    return
                }
                is LuaKernelOutcome.Yielded -> {
                    consumeNativeLogs(outcome.logs)
                    val operation = try {
                        LuaOperationHandle(stateHandle, LuaCoroutineId(outcome.coroutineId), LuaOperationId(outcome.operationId))
                    } catch (_: IllegalArgumentException) {
                        failProgramImage("invalid background operation handle")
                        return
                    }
                    val seconds = parseSleepSeconds(outcome.value)
                    if (seconds == null) {
                        gateOutcome = resumeSlice(operation, false, "E_INVALID_YIELD") ?: return
                        continue
                    }
                    val deadlineMillis = actor.calculateSleepDeadline((seconds * 1_000.0).toLong())
                    if (deadlineMillis == null) {
                        gateOutcome = resumeSlice(operation, false, "E_INVALID_ARGUMENT") ?: return
                        continue
                    }
                    val terminal = CompletableDeferred<Boolean>()
                    val terminalWon = AtomicBoolean(false)
                    val timerAdmission = generationContext.scheduleTimer(seconds) {
                        if (terminalWon.compareAndSet(false, true)) terminal.complete(true)
                    }
                    if (timerAdmission is GenerationAdmission.Rejected) {
                        gateOutcome = resumeSlice(operation, false, "E_BUSY") ?: return
                        continue
                    }
                    val timer = (timerAdmission as GenerationAdmission.Accepted<Disposable>).value
                    val deadlineAdmission = generationContext.scheduleTimer(deadlineMillis / 1_000.0) {
                        if (terminalWon.compareAndSet(false, true)) terminal.complete(false)
                    }
                    if (deadlineAdmission is GenerationAdmission.Rejected) {
                        timer.dispose()
                        gateOutcome = resumeSlice(operation, false, "E_BUSY") ?: return
                        continue
                    }
                    val deadline = (deadlineAdmission as GenerationAdmission.Accepted<Disposable>).value
                    val sleep = ActiveSleepWait(terminal, timer, deadline)
                    val admittedSleep = synchronized(sleepLock) {
                        if (closed.get()) false else activeSleepWaits.add(sleep)
                    }
                    if (!admittedSleep) {
                        timer.dispose()
                        deadline.dispose()
                        return
                    }
                    val timerFirst = try {
                        terminal.await()
                    } finally {
                        synchronized(sleepLock) { activeSleepWaits.remove(sleep) }
                        timer.dispose()
                        deadline.dispose()
                    }
                    if (closed.get() || !generationContext.isActive()) return
                    gateOutcome = resumeSlice(operation, timerFirst, if (timerFirst) "" else "E_TIMEOUT") ?: return
                }
                else -> {
                    failProgramImage("background coroutine terminated: ${outcome::class.simpleName}")
                    return
                }
            }
        }
    }

    private fun parseSleepSeconds(label: String?): Double? {
        val encoded = label?.removePrefix("sleep:") ?: return null
        if (encoded === label) return null
        return encoded.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_SLEEP_SECONDS }
    }

    /** Accepts only native-normalized level/payload records; host owns metadata. */
    private fun consumeNativeLogs(rawRecords: List<String>?) {
        rawRecords.orEmpty().forEach { raw ->
            val record = when (val parsed = LuaValue.fromJsonString(raw)) {
                is JsonParsingResult.Success -> parsed.value as? LuaValue.Map
                is JsonParsingResult.Failure -> null
            }
            val level = (record?.pairs?.get("level") as? LuaValue.StringValue)?.value
            val payload = record?.pairs?.get("payload") as? LuaValue.Map
            if (record == null || record.pairs.keys != setOf("level", "payload") ||
                level !in setOf("debug", "info", "warn", "error") || payload == null
            ) {
                recordDiagnostic("invalid native log record")
                return@forEach
            }
            synchronized(logLock) {
                if (logs.size == MAX_LOG_RECORDS) logs.removeFirst()
                logs.addLast(
                    LuaAdapterLogRecord(
                        instanceId = generationContext.instanceId,
                        generation = stateHandle.generation.value,
                        timestampMillis = System.currentTimeMillis(),
                        level = requireNotNull(level),
                        payload = requireNotNull(payload),
                    ),
                )
            }
        }
    }

    private fun recordDiagnostic(message: String) = synchronized(logLock) {
        if (localDiagnostics.size == MAX_LOCAL_DIAGNOSTICS) localDiagnostics.removeFirst()
        localDiagnostics.addLast(message)
    }

    /** Parses normalized native callback output without throwing. */
    private fun luaValueFromOutcome(value: String?): CallbackInvocationResult {
        if (value == null || value.isEmpty() || value == "null") {
            return CallbackInvocationResult.Success(LuaValue.Nil)
        }
        return when (val parsed = LuaValue.fromJsonString(value)) {
            is JsonParsingResult.Success -> CallbackInvocationResult.Success(parsed.value)
            is JsonParsingResult.Failure -> CallbackInvocationResult.InvalidOutcome(parsed.diagnostic)
        }
    }

    /**
     * Extract a boolean readiness from a callback return table.
     *
     * Per spec: only `{ready = true}` is ready; all malformed or absent values are not ready.
     */
    private fun extractReadinessFromReturn(value: LuaValue): Boolean =
        (value as? LuaValue.Map)?.pairs?.get("ready")?.let { (it as? LuaValue.Bool)?.value } ?: false

    /** Fails the actor and atomically projects its terminal unavailability to the registry. */
    private fun failProgramImage(diagnostic: String): CallbackInvocationResult.Failure {
        actor.latchProgramImageFailure()
        recordDiagnostic(diagnostic)
        updateSnapshot(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed(diagnostic),
            ),
            executionStatus = ChannelExecutionStatus.FAILED,
        )
        return CallbackInvocationResult.Failure(diagnostic)
    }

    private fun discardStagedTasks() {
        (generationContext as? ActorRuntimeHostContext)?.discardActorStagedTasks()
    }

    /**
     * Close and discard everything on activation failure.
     */
    private suspend fun discardStagedAndClose() {
        actor.latchProgramImageFailure()
        discardStagedTasks()
        actor.close()
        updateSnapshot(
            preparation = ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("activation failed"),
            ),
            executionStatus = ChannelExecutionStatus.FAILED,
        )
    }

    private fun updateSnapshot(
        preparation: ChannelPreparationAvailability? = null,
        executionStatus: ChannelExecutionStatus? = null,
    ) = synchronized(lifecycleLock) {
        if (closed.get()) return@synchronized
        _snapshot.value = _snapshot.value.copy(
            preparation = preparation ?: _snapshot.value.preparation,
            executionStatus = executionStatus ?: _snapshot.value.executionStatus,
        )
    }

    // ── Input target ─────────────────────────────────────────────────────

    private inner class LuaInputTarget : ChannelInputTarget {
        private val sessionId = UUID.randomUUID().toString()
        private var sampleRate: Int = 0

        override fun onInputStarted(session: ChannelAudioInputSession) {
            if (!closed.get()) {
                sampleRate = session.sampleRate
                updateSnapshot(executionStatus = ChannelExecutionStatus.RECORDING)
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            if (closed.get()) return ChannelInputResult.None
            updateSnapshot(executionStatus = ChannelExecutionStatus.PROCESSING)
            val callback = callbacks["handle_input"]
            if (callback == null) {
                updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val effectiveSampleRate = if (sampleRate > 0) sampleRate else recording.sampleRate
            val durationMillis = if (effectiveSampleRate > 0) {
                recording.samples.size.toDouble() * 1_000.0 / effectiveSampleRate.toDouble()
            } else {
                0.0
            }
            val event = LuaValue.Map(
                mapOf(
                    "event" to LuaValue.StringValue("capture"),
                    "session" to LuaValue.StringValue(sessionId),
                    "metadata" to LuaValue.Map(
                        mapOf(
                            "duration_ms" to LuaValue.Number(durationMillis),
                            "sample_rate" to LuaValue.Number(effectiveSampleRate.toDouble()),
                            "channels" to LuaValue.Number(1.0),
                        ),
                    ),
                ),
            )
            when (val outcome = invokeCallbackHandle(callback, event)) {
                is CallbackInvocationResult.Success -> updateSnapshot(
                    executionStatus = outcome.value.strictInputStatus() ?: ChannelExecutionStatus.FAILED,
                )
                is CallbackInvocationResult.Failure,
                is CallbackInvocationResult.YieldViolation,
                is CallbackInvocationResult.InvalidOutcome -> {
                    updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                }
            }
            return ChannelInputResult.None
        }

        override fun onInputPlaybackCompleted() = Unit

        override fun onInputCancelled(reason: String) {
            if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.IDLE)
        }

        override fun onInputFailed(reason: String) {
            if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
        }
    }
}

/** Host-enriched, bounded diagnostic record; plugin payload cannot supply metadata. */
internal data class LuaAdapterLogRecord(
    val instanceId: String,
    val generation: Long,
    val timestampMillis: Long,
    val level: String,
    val payload: LuaValue.Map,
)

private fun LuaValue.strictInputStatus(): ChannelExecutionStatus? {
    if (this !is LuaValue.Map) return null
    val ok = pairs["ok"]
    if (pairs.keys == setOf("ok") && ok is LuaValue.Bool && ok.value) {
        return ChannelExecutionStatus.SUCCESS
    }
    val error = pairs["error"] as? LuaValue.Map ?: return null
    return if (
        pairs.keys == setOf("error") &&
        error.pairs.keys == setOf("code", "detail") &&
        (error.pairs["code"] as? LuaValue.StringValue)?.value?.isNotEmpty() == true &&
        (error.pairs["detail"] as? LuaValue.StringValue)?.value?.isNotEmpty() == true
    ) {
        ChannelExecutionStatus.FAILED
    } else {
        null
    }
}

// ── Internal callback invocation result type ─────────────────────────────

/**
 * Typed outcome for a synchronous callback invocation.
 */
internal sealed class CallbackInvocationResult {
    /** Callback returned successfully, possibly with a normalized value. */
    data class Success(val value: LuaValue = LuaValue.Nil) : CallbackInvocationResult()

    /** Callback returned an application error, threw, or a bridge error occurred. */
    data class Failure(val diagnostic: String) : CallbackInvocationResult()

    /** Callback violated the non-yielding contract by yielding or spawning. */
    data class YieldViolation(val diagnostic: String) : CallbackInvocationResult()

    /** Callback returned a value whose shape does not match the contract for that callback. */
    data class InvalidOutcome(val diagnostic: String) : CallbackInvocationResult()
}

private fun CallbackInvocationResult.asGeneralCallbackOutcome(): CallbackInvocationResult = when (this) {
    is CallbackInvocationResult.Success -> when (val value = value) {
        is LuaValue.Map -> {
            if ("error" !in value.pairs) this
            else if (value.strictInputStatus() == ChannelExecutionStatus.FAILED) {
                CallbackInvocationResult.Failure("Lua callback returned an application error")
            } else {
                CallbackInvocationResult.InvalidOutcome("malformed application error table")
            }
        }
        else -> this
    }
    else -> this
}

private fun CallbackInvocationResult.diagnostic(): String = when (this) {
    is CallbackInvocationResult.Success -> "unexpected successful outcome"
    is CallbackInvocationResult.Failure -> diagnostic
    is CallbackInvocationResult.YieldViolation -> diagnostic
    is CallbackInvocationResult.InvalidOutcome -> diagnostic
}
