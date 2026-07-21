package dev.nilp0inter.subspace.lua
import dev.nilp0inter.subspace.channel.capability.*
import dev.nilp0inter.subspace.dependency.PackageCapability

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
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
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
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import dev.nilp0inter.subspace.lua.actor.ActorOperationIdentity
import dev.nilp0inter.subspace.lua.actor.ActorOperationToken
import dev.nilp0inter.subspace.lua.actor.ActorTaskIdentity
import dev.nilp0inter.subspace.lua.actor.ActorTaskId
import dev.nilp0inter.subspace.lua.actor.ActorOperationId
import dev.nilp0inter.subspace.lua.actor.ActorTerminal
import java.util.UUID

private fun normalizeSemanticAudioError(value: String): String = when (value) {
    "E_INVALID_ARGUMENT", "E_INVALID_VALUE", "E_INVALID_CONTEXT",
    "E_CAPABILITY_UNDECLARED", "E_UNAVAILABLE", "E_BUSY", "E_TIMEOUT",
    "E_CANCELLED", "E_CLOSED", "E_STALE", "E_HOST_FAILURE" -> value
    else -> "E_HOST_FAILURE"
}
private fun ChannelCapabilityScope.isPublicCapabilityDeclared(id: String): Boolean = when (id) {
    PackageCapability.AUDIO_TRANSCRIPTION -> ChannelCapability.Transcription in declaredCapabilities
    PackageCapability.AUDIO_SYNTHESIS -> ChannelCapability.Synthesis in declaredCapabilities
    PackageCapability.AUDIO_PLAYBACK -> ChannelCapability.AudioOperation in declaredCapabilities &&
        ChannelCapability.DeferredAudioPlayback in declaredCapabilities
    else -> false
}

private fun CapabilityAcquisition<*>.normalizedSemanticError(): String = when (this) {
    is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable -> "E_UNAVAILABLE"
    is CapabilityAcquisition.Denied -> "E_CAPABILITY_UNDECLARED"
    CapabilityAcquisition.Closed -> "E_CLOSED"
    CapabilityAcquisition.Cancelled -> "E_CANCELLED"
    is CapabilityAcquisition.Failed -> "E_HOST_FAILURE"
    is CapabilityAcquisition.Available -> "E_HOST_FAILURE"
}

private fun CapabilityOperationResult<*>.normalizedSemanticError(): String = when (this) {
    is CapabilityOperationResult.Unavailable -> "E_UNAVAILABLE"
    is CapabilityOperationResult.Denied -> "E_CAPABILITY_UNDECLARED"
    CapabilityOperationResult.Closed -> "E_CLOSED"
    CapabilityOperationResult.Cancelled -> "E_CANCELLED"
    is CapabilityOperationResult.Failed -> "E_HOST_FAILURE"
    is CapabilityOperationResult.Success -> "E_HOST_FAILURE"
}

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
    private val logSink: PluginLogSink = NoOpPluginLogSink,
    private val configuration: ValidatedChannelConfiguration,
    private val capabilities: ChannelCapabilityScope,
    private val initialSummary: String? = null,
) : ChannelRuntime {
    @Volatile
    private var audioRegistry: LuaOpaqueAudioRegistry? = null

    private val closed = AtomicBoolean(false)
    private val hostGeneration = requireNotNull(generationContext as? ActorRuntimeHostContext) {
        "Lua adapter requires a host-owned actor runtime context"
    }.actorIdentity.runtimeGeneration.value
    private val activated = AtomicBoolean(false)
    private val activationClaimed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val logLock = Any()
    private val logs = ArrayDeque<LuaAdapterLogRecord>(MAX_LOG_RECORDS)
    private val localDiagnostics = ArrayDeque<String>(MAX_LOCAL_DIAGNOSTICS)
    private val sleepLock = Any()
    private val activeSleepWaits = mutableSetOf<ActiveSleepWait>()

    private val inputLock = Any()
    private var pendingInputExecution: PendingInputExecution? = null
    private var retiredInputOwnerToken: String? = null
    // Set by host cancellation even if the callback has not yielded yet.
    private val preserveCapturedOnTimeout = AtomicBoolean(false)
    private var inputCancellationRequested = false

    internal val logSnapshot: List<LuaAdapterLogRecord>
        get() = synchronized(logLock) { logs.toList() }

    internal val localDiagnosticSnapshot: List<String>
        get() = synchronized(logLock) { localDiagnostics.toList() }

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
            summary = initialSummary,
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
        const val MAX_READINESS_STATUS_BYTES = 256
        const val READINESS_MALFORMED_DIAGNOSTIC = "invalid readiness result"
        // Matches the actor's operation-specific yielded-operation deadline.
        const val SEMANTIC_AUDIO_DEADLINE_MILLIS = 30_000L
    }

    // ── Activate ──────────────────────────────────────────────────────────

    /**
     * Runs the synchronous activation sequence:
     * 1. Invoke startup() — non-yielding, non-spawning (except via context).
     * 2. Invoke handle_lifecycle({event = "ready"}) if present.
     * 3. Atomically claim Ready state.
     *
     * On any failure, closes the actor and discards every staged task before
     * transitioning to the failed snapshot state.
     */
    override suspend fun activate(): ChannelActivationResult {
        // Claim activation before touching the actor so concurrent callers can never
        // enter startup twice. The caller that wins the claim owns cleanup for every
        // failure observed before publication; a later call cannot discard work from
        // an already-live generation.
        if (!activationClaimed.compareAndSet(false, true)) {
            return ChannelActivationResult.Failed("Lua runtime activation was already claimed")
        }
        if (closed.get() || !generationContext.isActive() ||
            !actor.stageProgramImage() || !actor.claimProgramImageStartup()
        ) {
            discardStagedAndClose()
            return ChannelActivationResult.Failed("Lua runtime is closed or not staged")
        }

        val config = validatedConfigurationToLua(configuration)
        val startupOutcome = invokeStartupCallback(config).asGeneralCallbackOutcome()
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
        audioRegistry = LuaOpaqueAudioRegistry(stateHandle)

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

    /** Current suspended input owner, or null when no semantic operation is pending. */
    internal fun pendingInput(): LuaInputPending? = synchronized(inputLock) {
        pendingInputExecution?.let { pending ->
            LuaInputPending(
                ownerToken = pending.ownerToken,
                operationId = pending.operation.operationId.value,
                label = pending.label,
            )
        }
    }

    /**
     * Complete one pending semantic input operation. Ownership is checked before
     * actor entry; stale or foreign callers cannot consume or resume the token.
     * A yielded continuation keeps the same owner token and publishes its new
     * operation id for the next authorized completion.
     */
    internal suspend fun resumeInput(
        ownerToken: String,
        operationId: Long,
        success: Boolean,
        value: String,
    ): LuaInputResumeResult {
        if (closed.get() || !generationContext.isActive()) {
            synchronized(inputLock) { pendingInputExecution?.takeIf { it.ownerToken == ownerToken } }
                ?.let { finishPendingInput(it, CallbackInvocationResult.Failure("runtime is closed")) }
            return LuaInputResumeResult.Closed
        }
        val pending = synchronized(inputLock) {
            pendingInputExecution?.takeIf {
                it.ownerToken == ownerToken && it.operation.operationId.value == operationId
            }
        } ?: synchronized(inputLock) {
            when {
                pendingInputExecution?.ownerToken == ownerToken -> LuaInputResumeResult.Stale
                retiredInputOwnerToken == ownerToken -> LuaInputResumeResult.Stale
                else -> LuaInputResumeResult.ForeignOwner
            }
        }.let { return it }

        val deliveredValue = if (!success && (pending.label.startsWith("transcribe:") || pending.label.startsWith("synthesize:") || pending.label.startsWith("playback:"))) {
            normalizeSemanticAudioError(value)
        } else {
            value
        }

        val admission = ScopedSpawnAdmission(pending.audioOwner)
        val gateOutcome = try {
            actor.resumeProgramImageCoroutine(pending.operation, success, deliveredValue, admission)
        } catch (error: Throwable) {
            admission.release(false)
            finishPendingInput(pending, CallbackInvocationResult.Failure("input resume failed"))
            return LuaInputResumeResult.Closed
        }
        when (val release = admission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> {
                finishPendingInput(pending, CallbackInvocationResult.Failure(release.diagnostic))
                return LuaInputResumeResult.Stale
            }
        }
        val outcome = (gateOutcome as? ActorGateResult.Success)?.value
            ?: run {
                finishPendingInput(pending, CallbackInvocationResult.Failure("input owner is no longer live"))
                return LuaInputResumeResult.Closed
            }
        when (outcome) {
            is LuaKernelOutcome.Yielded -> {
                val next = LuaOperationHandle(
                    stateHandle,
                    LuaCoroutineId(outcome.coroutineId),
                    LuaOperationId(outcome.operationId),
                )
                // A chained yield (e.g. synthesize -> playback.schedule in TTS) is a
                // distinct semantic operation: mint a fresh terminal gate so the next
                // executor can claim it independently of the completed predecessor.
                val nextSemantic = SemanticAudioOperation.create(
                    next,
                    (generationContext as ActorRuntimeHostContext).actorIdentity,
                )
                synchronized(inputLock) {
                    if (pendingInputExecution?.ownerToken != ownerToken ||
                        pendingInputExecution?.operation?.operationId?.value != operationId
                    ) return LuaInputResumeResult.Stale
                    pendingInputExecution = pending.copy(
                        operation = next,
                        label = outcome.value ?: "",
                        semantic = nextSemantic,
                    )
                }
                return LuaInputResumeResult.Accepted
            }
            is LuaKernelOutcome.Completed -> {
                val completion = consumeCompletedOutcome(outcome)
                val terminal = if (completion is CallbackInvocationResult.Success) {
                    completion.value.strictInputStatus()?.let { CallbackInvocationResult.Success(completion.value) }
                        ?: CallbackInvocationResult.InvalidOutcome("malformed handle_input terminal result")
                } else completion
                finishPendingInput(pending, terminal)
                return LuaInputResumeResult.Accepted
            }
            else -> {
                finishPendingInput(pending, CallbackInvocationResult.Failure("input operation failed"))
                return LuaInputResumeResult.Accepted
            }
        }
    }
    private fun finishPendingInput(
        pending: PendingInputExecution,
        result: CallbackInvocationResult,
    ) {
        pending.semantic.terminalize(result)
        synchronized(inputLock) {
            if (pendingInputExecution?.ownerToken == pending.ownerToken) {
                retiredInputOwnerToken = pending.ownerToken
                pendingInputExecution = null
            }
        }
        pending.terminal.complete(result)
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

        val context = readinessContext()
        val outcome = invokeCallbackHandle(
            readinessHandle,
            LuaValue.Map(mapOf("capabilities" to LuaValue.Map(context))),
        )
        when (val projection = outcome.readinessProjection()) {
            is ReadinessProjection.Valid -> {
                cachedReadiness.set(projection.ready)
                if (projection.statusPresent) {
                    updateSnapshot(summary = projection.status)
                }
            }
            ReadinessProjection.Malformed -> {
                cachedReadiness.set(false)
                recordDiagnostic(READINESS_MALFORMED_DIAGNOSTIC)
            }
        }
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
            is CallbackInvocationResult.Pending -> Unit // synchronous SOS rejects yield
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
        audioRegistry?.close()
        audioRegistry = null
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
        val closingInput = synchronized(inputLock) {
            pendingInputExecution?.also { pendingInputExecution = null }
        }
        closingInput?.let {
            it.semantic.terminalize(CallbackInvocationResult.Failure("runtime is closed"))
            it.terminal.complete(CallbackInvocationResult.Failure("runtime is closed"))
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

    /**
     * Converts the validated host configuration into a fresh normalized Lua table.
     * Parses the JSON payload string into an independent LuaValue tree so Lua
     * mutations cannot affect the host, a sibling instance, or a later generation.
     */
    private fun validatedConfigurationToLua(config: ValidatedChannelConfiguration): LuaValue.Map {
        val parsed = LuaValue.fromJsonString(config.payload.toJsonString())
        val valuesMap = when (parsed) {
            is JsonParsingResult.Success -> parsed.value
            is JsonParsingResult.Failure -> LuaValue.Map(emptyMap())
        }
        return LuaValue.Map(mapOf(
            "schema_version" to LuaValue.Number(config.schemaVersion.toDouble()),
            "values" to (valuesMap as? LuaValue.Map ?: LuaValue.Map(emptyMap())),
        ))
    }

    private suspend fun invokeStartupCallback(config: LuaValue): CallbackInvocationResult {
        val handle = callbacks["startup"] ?: return CallbackInvocationResult.Failure("callback 'startup' not found")
        return invokeCallbackHandle(handle, config)
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
                actor.invokeProgramImageStartup(handle, arguments, admission)
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

    private suspend fun invokeInputCallbackHandle(
        handle: LuaCallbackHandle,
        arguments: LuaValue,
        capturedAudioToken: String,
        audioOwner: LuaOpaqueAudioRegistry.Owner,
    ): CallbackInvocationResult {
        if (closed.get() || !generationContext.isActive()) {
            return CallbackInvocationResult.Failure("runtime is closed")
        }
        val admission = ScopedSpawnAdmission(audioOwner)
        val gateOutcome = try {
            actor.invokeProgramImageInputCallback(handle, arguments, capturedAudioToken, admission)
        } catch (_: Throwable) {
            admission.release(false)
            return CallbackInvocationResult.Failure("callback bridge failure")
        }
        when (val release = admission.releaseFor(gateOutcome)) {
            SpawnAdmissionRelease.Matched -> Unit
            is SpawnAdmissionRelease.Mismatch -> return failProgramImage(release.diagnostic)
        }
        val outcome = (gateOutcome as? ActorGateResult.Success)?.value
            ?: return CallbackInvocationResult.Failure("callback gate is unavailable")
        return when (outcome) {
            is LuaKernelOutcome.Completed -> consumeCompletedOutcome(outcome)
            is LuaKernelOutcome.Yielded -> {
                consumeNativeLogs(outcome.logs)
                val operation = try {
                    LuaOperationHandle(
                        stateHandle,
                        LuaCoroutineId(outcome.coroutineId),
                        LuaOperationId(outcome.operationId),
                    )
                } catch (_: IllegalArgumentException) {
                    return CallbackInvocationResult.Failure("invalid input operation handle")
                }
                CallbackInvocationResult.Pending(operation, outcome.value ?: "")
            }
            is LuaKernelOutcome.RuntimeFailure -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.MemoryFailure -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Interrupted -> CallbackInvocationResult.Failure(outcome.diagnostic ?: "interrupted")
            is LuaKernelOutcome.Cancelled -> CallbackInvocationResult.Failure("callback cancelled")
            is LuaKernelOutcome.ValidationFailure -> CallbackInvocationResult.InvalidOutcome(outcome.diagnostic)
            is LuaKernelOutcome.InvalidOwnership -> CallbackInvocationResult.Failure(outcome.diagnostic)
            is LuaKernelOutcome.Stale -> CallbackInvocationResult.Failure("stale input operation")
            is LuaKernelOutcome.Closed -> CallbackInvocationResult.Failure("state is closed")
            else -> CallbackInvocationResult.Failure("unexpected input bridge outcome")
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

    private inner class ScopedSpawnAdmission(
        private val audioOwner: LuaOpaqueAudioRegistry.Owner? = null,
    ) : LuaSpawnAdmission {
        private val pending = mutableListOf<Pair<Long, CompletableDeferred<Boolean>>>()

        override fun admitTask(coroutineId: Long): Int {
            val decision = CompletableDeferred<Boolean>()
            return when (generationContext.admitTask {
                if (decision.await()) runBackgroundCoroutine(LuaCoroutineId(coroutineId))
            }) {
                is GenerationAdmission.Accepted -> { pending += coroutineId to decision; 0 }
                is GenerationAdmission.Rejected -> if (generationContext.isActive()) 2 else 1
            }
        }

        override fun admitTranscription(operationId: Long, token: String): Int {
            if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_TRANSCRIPTION)) return 3
            val owner = audioOwner ?: return 1
            val registry = audioRegistry ?: return 1
            if (operationId == 0L) {
                return when (registry.resolve(LuaOpaqueAudioRegistry.Token(token), owner, LuaOpaqueAudioRegistry.Kind.Captured)) {
                    is LuaOpaqueAudioRegistry.Resolution.Captured -> 0
                    LuaOpaqueAudioRegistry.Resolution.Foreign -> 3
                    LuaOpaqueAudioRegistry.Resolution.WrongKind -> 4
                    LuaOpaqueAudioRegistry.Resolution.Stale -> 5
                    LuaOpaqueAudioRegistry.Resolution.Closed -> 6
                    is LuaOpaqueAudioRegistry.Resolution.Synthesized -> 4
                }
            }
            return if (closed.get() || !generationContext.isActive()) 1 else 0
        }

        override fun admitSynthesis(operationId: Long, paramsJson: String): Int {
            audioOwner ?: return 1
            audioRegistry ?: return 1
            if (operationId == 0L) {
                if (closed.get() || !generationContext.isActive()) return 1
                if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_SYNTHESIS)) return 3
                return 0
            }
            return if (closed.get() || !generationContext.isActive()) 1 else 0
        }
        override fun admitPlayback(operationId: Long, token: String, delaySeconds: Double): Int {
            val owner = audioOwner ?: return 1
            val registry = audioRegistry ?: return 1
            if (operationId != 0L) return if (closed.get() || !generationContext.isActive()) 1 else 0
            if (closed.get() || !generationContext.isActive()) return 1
            if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_PLAYBACK)) return 3
            if (!delaySeconds.isFinite() || delaySeconds < 0.0 || delaySeconds > 86_400.0) return 4
            return when {
                registry.resolve(LuaOpaqueAudioRegistry.Token(token), owner, LuaOpaqueAudioRegistry.Kind.Captured) is LuaOpaqueAudioRegistry.Resolution.Captured -> 0
                registry.resolve(LuaOpaqueAudioRegistry.Token(token), owner, LuaOpaqueAudioRegistry.Kind.Synthesized) is LuaOpaqueAudioRegistry.Resolution.Synthesized -> 0
                registry.resolve(LuaOpaqueAudioRegistry.Token(token), owner, LuaOpaqueAudioRegistry.Kind.Captured) is LuaOpaqueAudioRegistry.Resolution.Foreign -> 3
                registry.resolve(LuaOpaqueAudioRegistry.Token(token), owner, LuaOpaqueAudioRegistry.Kind.Captured) is LuaOpaqueAudioRegistry.Resolution.Stale -> 5
                else -> 4
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
            val timestamp = System.currentTimeMillis()
            synchronized(logLock) {
                if (logs.size == MAX_LOG_RECORDS) logs.removeFirst()
                logs.addLast(
                    LuaAdapterLogRecord(
                        instanceId = generationContext.instanceId,
                        generation = hostGeneration,
                        timestampMillis = timestamp,
                        level = requireNotNull(level),
                        payload = requireNotNull(payload),
                    ),
                )
            }
            if (!closed.get() && generationContext.isActive()) {
                val serializedPayload = try {
                    canonicalSerialize(payload, 262_144)
                } catch (e: Exception) {
                    null
                }
                if (serializedPayload != null) {
                    logSink.tryPublish(
                        instanceId = generationContext.instanceId,
                        generation = hostGeneration,
                        timestampMillis = timestamp,
                        level = requireNotNull(level),
                        payloadJson = serializedPayload
                    )
                }
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
    private suspend fun readinessContext(): Map<String, LuaValue> {
        val context = linkedMapOf<String, LuaValue>()
        PackageCapability.ALL.forEach { capabilityId ->
            if (!capabilities.isPublicCapabilityDeclared(capabilityId)) return@forEach
            val availability = when (capabilityId) {
                PackageCapability.AUDIO_TRANSCRIPTION -> availabilityFor(CapabilityKey.Transcription)
                PackageCapability.AUDIO_SYNTHESIS -> availabilityFor(CapabilityKey.Synthesis)
                PackageCapability.AUDIO_PLAYBACK -> {
                    val audio = availabilityFor(CapabilityKey.AudioOperation)
                    val playback = availabilityFor(CapabilityKey.DeferredAudioPlayback)
                    combineAvailability(audio, playback)
                }
                else -> return@forEach
            }
            context[capabilityId] = LuaValue.StringValue(availability)
        }
        return context
    }

    private suspend fun availabilityFor(key: CapabilityKey<*>): String = try {
        when (val result = capabilities.availability(key)) {
            is CapabilityAvailabilityResult.State -> when (result.availability) {
                CapabilityAvailability.Available -> "available"
                CapabilityAvailability.Recoverable -> "recoverable"
                is CapabilityAvailability.Unavailable -> "unavailable"
            }
            else -> "unavailable"
        }
    } catch (_: Exception) {
        "unavailable"
    }

    private fun combineAvailability(first: String, second: String): String = when {
        first == "unavailable" || second == "unavailable" -> "unavailable"
        first == "recoverable" || second == "recoverable" -> "recoverable"
        else -> "available"
    }

    private sealed interface ReadinessProjection {
        data class Valid(
            val ready: Boolean,
            val statusPresent: Boolean,
            val status: String?,
        ) : ReadinessProjection

        data object Malformed : ReadinessProjection
    }

    private fun CallbackInvocationResult.readinessProjection(): ReadinessProjection {
        val value = (this as? CallbackInvocationResult.Success)?.value as? LuaValue.Map
            ?: return ReadinessProjection.Malformed
        val keys = value.pairs.keys
        if (keys != setOf("ready") && keys != setOf("ready", "status")) {
            return ReadinessProjection.Malformed
        }
        val ready = (value.pairs["ready"] as? LuaValue.Bool)?.value
            ?: return ReadinessProjection.Malformed
        if ("status" !in keys) return ReadinessProjection.Valid(ready, false, null)
        val status = (value.pairs["status"] as? LuaValue.StringValue)?.value
            ?: return ReadinessProjection.Malformed
        if (!isWellFormedUtf16(status) || status.toByteArray(Charsets.UTF_8).size > MAX_READINESS_STATUS_BYTES) {
            return ReadinessProjection.Malformed
        }
        return ReadinessProjection.Valid(ready, true, status)
    }

    private fun isWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                index += 2
            } else if (character.isLowSurrogate()) {
                return false
            } else {
                index++
            }
        }
        return true
    }

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
        audioRegistry?.close()
        audioRegistry = null
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
        summary: String? = null,
    ) = synchronized(lifecycleLock) {
        if (closed.get()) return@synchronized
        _snapshot.value = _snapshot.value.copy(
            preparation = preparation ?: _snapshot.value.preparation,
            executionStatus = executionStatus ?: _snapshot.value.executionStatus,
            summary = summary ?: _snapshot.value.summary,
        )
    }

    // ── Input target ─────────────────────────────────────────────────────

    private inner class LuaInputTarget : ChannelInputTarget {
        private val sessionId = UUID.randomUUID().toString()
        private var sampleRate: Int = 0

        override fun onInputStarted(session: ChannelAudioInputSession) {
            if (!closed.get()) {
                synchronized(inputLock) { inputCancellationRequested = false }
                sampleRate = session.sampleRate
                updateSnapshot(executionStatus = ChannelExecutionStatus.RECORDING)
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            if (closed.get()) return ChannelInputResult.None
            val cancelled = synchronized(inputLock) { inputCancellationRequested }
            if (cancelled || recording.isEmpty) {
                updateSnapshot(executionStatus = if (cancelled) ChannelExecutionStatus.IDLE else ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            updateSnapshot(executionStatus = ChannelExecutionStatus.PROCESSING)
            val callback = callbacks["handle_input"]
            if (callback == null) {
                updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val registry = audioRegistry ?: run {
                if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val owner = LuaOpaqueAudioRegistry.Owner.Input(UUID.randomUUID().toString())
            val opaqueRecording = opaqueAudioRecording(recording, capabilities.identity.runtimeGeneration)
            val token = registry.admitCaptured(owner, opaqueRecording)
            if (token == null) {
                updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val outcome = try {
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
                val invocation = invokeInputCallbackHandle(callback, event, token.value, owner)
                when (invocation) {
                    is CallbackInvocationResult.Pending -> {
                        val pending = PendingInputExecution(
                            ownerToken = UUID.randomUUID().toString(),
                            operation = invocation.operation,
                            label = invocation.label,
                            terminal = CompletableDeferred(),
                            audioOwner = owner,
                            capturedAudioToken = token.value,
                            semantic = SemanticAudioOperation.create(
                                invocation.operation,
                                (generationContext as ActorRuntimeHostContext).actorIdentity,
                            ),
                        )
                        val cancellationRequested = synchronized(inputLock) {
                            when {
                                closed.get() || pendingInputExecution != null -> null
                                 else -> {
                                    pendingInputExecution = pending
                                    inputCancellationRequested
                                }
                            }
                        }
                        if (cancellationRequested != null) {
                            dispatchPendingByLabel(pending)
                            // Chained yields: each resume may produce a new semantic
                            // operation (e.g. synthesize -> playback.schedule in TTS,
                            // or transcribe -> synthesize -> playback.schedule in
                            // STT_TTS). Dispatch each successive yield until the
                            // callback terminates or no further progress is made.
                            var lastDispatchedOperationId = pending.operation.operationId.value
                            while (true) {
                                val current = synchronized(inputLock) { pendingInputExecution } ?: break
                                if (current.ownerToken != pending.ownerToken) break
                                if (current.operation.operationId.value == lastDispatchedOperationId) break
                                lastDispatchedOperationId = current.operation.operationId.value
                                dispatchPendingByLabel(current)
                            }
                        }
                        if (cancellationRequested == null) {
                            CallbackInvocationResult.Failure("input execution is busy")
                        } else {
                            if (cancellationRequested) cancelPendingInput()
                            try {
                                pending.terminal.await()
                            } finally {
                                synchronized(inputLock) {
                                    if (pendingInputExecution?.ownerToken == pending.ownerToken) {
                                        pendingInputExecution = null
                                    }
                                }
                            }
                        }
                    }
                    else -> invocation
                }
            } finally {
                if (!preserveCapturedOnTimeout.getAndSet(false)) registry.invalidateOwner(owner)
            }
            when (outcome) {
                is CallbackInvocationResult.Success -> {
                    if (outcome.value.strictInputStatus() != null) {
                        updateSnapshot(
                            executionStatus = outcome.value.strictInputStatus()!!,
                        )
                    } else {
                        updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                    }
                }
                is CallbackInvocationResult.Failure,
                is CallbackInvocationResult.YieldViolation,
                is CallbackInvocationResult.InvalidOutcome,
                is CallbackInvocationResult.Pending -> {
                    updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
                }
            }
            return ChannelInputResult.None
        }

        override fun onInputPlaybackCompleted() = Unit

        override fun onInputCancelled(reason: String) {
            synchronized(inputLock) { inputCancellationRequested = true }
            cancelPendingInput()
            if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.IDLE)
        }


        override fun onInputFailed(reason: String) {
            if (!closed.get()) updateSnapshot(executionStatus = ChannelExecutionStatus.FAILED)
        }
    }
    private suspend fun executeTranscription(pending: PendingInputExecution) {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_TRANSCRIPTION)) {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CAPABILITY_UNDECLARED")
            return
        }
        val registry = audioRegistry
        val recording = registry?.resolve(LuaOpaqueAudioRegistry.Token(pending.capturedAudioToken), pending.audioOwner, LuaOpaqueAudioRegistry.Kind.Captured) as? LuaOpaqueAudioRegistry.Resolution.Captured
        if (recording == null) { resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_STALE"); return }
        val result = when (val acquisition = capabilities.acquire(CapabilityKey.Transcription)) {
            is CapabilityAcquisition.Available -> {
                val value = awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use { it.transcribe(recording.recording) }
                    },
                )
                if (value == null) {
                    preserveCapturedOnTimeout.set(true)
                    resumeSemantic(pending, false, "E_TIMEOUT")
                    return
                } else {
                    value
                }
            }
            is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable -> CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
        val (success, value) = when (result) {
            is CapabilityOperationResult.Success -> result.value.text.takeIf { it.toByteArray(Charsets.UTF_8).size <= 16 * 1024 }?.let { true to it } ?: (false to "E_HOST_FAILURE")
            is CapabilityOperationResult.Unavailable -> false to "E_UNAVAILABLE"
            is CapabilityOperationResult.Denied -> false to "E_CAPABILITY_UNDECLARED"
            CapabilityOperationResult.Closed -> false to "E_CLOSED"
            CapabilityOperationResult.Cancelled -> false to "E_CANCELLED"
            is CapabilityOperationResult.Failed -> false to "E_HOST_FAILURE"
        }
        resumeSemantic(pending, success, value)
    }

    private suspend fun resumeSemantic(pending: PendingInputExecution, success: Boolean, value: String) {
        if (!pending.semantic.claimTerminal()) return
        pending.semantic.completeClaimed(
            if (success) CallbackInvocationResult.Success(LuaValue.StringValue(value))
            else CallbackInvocationResult.Failure(value),
        )
        resumeInput(pending.ownerToken, pending.operation.operationId.value, success, value)
    }

    /** Dispatches one pending semantic operation by its yield-label prefix. */
    private suspend fun dispatchPendingByLabel(pending: PendingInputExecution) {
        when {
            pending.label.startsWith("transcribe:") -> executeTranscription(pending)
            pending.label.startsWith("synthesize:") -> executeSynthesis(pending)
            pending.label.startsWith("playback:") -> executePlayback(pending)
            else -> Unit
        }
    }

    private suspend fun <T : Any> awaitSemanticBackend(
        operation: suspend () -> T,
        onLateResult: (T) -> Unit = {},
    ): T? {
        val hostContext = generationContext as? ActorRuntimeHostContext
            ?: return withTimeoutOrNull(SEMANTIC_AUDIO_DEADLINE_MILLIS) { operation() }
        val backend = hostContext.actorParentScope.async { operation() }
        val result = withTimeoutOrNull(SEMANTIC_AUDIO_DEADLINE_MILLIS) { backend.await() }
        if (result == null) {
            hostContext.actorParentScope.launch {
                runCatching { backend.await() }.getOrNull()?.let(onLateResult)
            }
        }
        return result
    }
    private suspend fun executeSynthesis(pending: PendingInputExecution) {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_SYNTHESIS)) { resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CAPABILITY_UNDECLARED"); return }
        val params = try { org.json.JSONObject(pending.label.removePrefix("synthesize:")) } catch (_: Exception) { resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_INVALID_ARGUMENT"); return }
        val text = params.optString("text", ""); val language = params.optString("language", ""); val voice = params.optString("voice", ""); val speed = params.optDouble("speed", Double.NaN)
        if (text.isBlank() || language.isBlank() || voice.isBlank() || !speed.isFinite() || speed <= 0.0) { resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_INVALID_ARGUMENT"); return }
        val result = when (val acquisition = capabilities.acquire(CapabilityKey.Synthesis)) {
            is CapabilityAcquisition.Available -> {
                val value = awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use {
                            it.synthesize(
                                SpeechSynthesisRequest(text, language, SpeechVoice(voice), speed.toFloat()),
                            )
                        }
                    },
                    onLateResult = { late ->
                        if (late is CapabilityOperationResult.Success) late.value.dispose()
                    },
                )
                if (value == null) { resumeSemantic(pending, false, "E_TIMEOUT"); return }
                value
            }
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable -> CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(CapabilityFailureReason.HOST_FAILURE)
        }
        when (result) {
            is CapabilityOperationResult.Success -> {
                if (!pending.semantic.claimTerminal()) { result.value.dispose(); return }
                val token = audioRegistry?.admitSynthesized(pending.audioOwner, result.value)
                if (token == null) {
                    result.value.dispose()
                    pending.semantic.completeClaimed(CallbackInvocationResult.Failure("E_BUSY"))
                    resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_BUSY")
                } else {
                    pending.semantic.completeClaimed(CallbackInvocationResult.Success(LuaValue.StringValue("synthesized:${token.value}")))
                    resumeInput(pending.ownerToken, pending.operation.operationId.value, true, "synthesized:${token.value}")
                }
            }
            is CapabilityOperationResult.Unavailable -> resumeSemantic(pending, false, "E_UNAVAILABLE")
            is CapabilityOperationResult.Denied -> resumeSemantic(pending, false, "E_CAPABILITY_UNDECLARED")
            CapabilityOperationResult.Closed -> resumeSemantic(pending, false, "E_CLOSED")
            CapabilityOperationResult.Cancelled -> resumeSemantic(pending, false, "E_CANCELLED")
            is CapabilityOperationResult.Failed -> resumeSemantic(pending, false, "E_HOST_FAILURE")
        }
    }

    private suspend fun executePlayback(pending: PendingInputExecution) {
        if (!capabilities.isPublicCapabilityDeclared(PackageCapability.AUDIO_PLAYBACK)) {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CAPABILITY_UNDECLARED")
            return
        }
        val parts = pending.label.split(":")
        val token = parts.getOrNull(1)
        val delay = parts.getOrNull(2)?.toDoubleOrNull()
        if (parts.size != 3 || token == null || delay == null || !delay.isFinite() || delay < 0.0 || delay > 86_400.0) {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_INVALID_VALUE")
            return
        }
        val registry = audioRegistry ?: run {
            resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_CLOSED")
            return
        }
        val captured = registry.resolve(
            LuaOpaqueAudioRegistry.Token(token),
            pending.audioOwner,
            LuaOpaqueAudioRegistry.Kind.Captured,
        )
        val kind = if (captured is LuaOpaqueAudioRegistry.Resolution.Captured) {
            LuaOpaqueAudioRegistry.Kind.Captured
        } else {
            LuaOpaqueAudioRegistry.Kind.Synthesized
        }
        val audio = if (captured is LuaOpaqueAudioRegistry.Resolution.Captured) {
            captured.recording
        } else {
            (registry.resolve(LuaOpaqueAudioRegistry.Token(token), pending.audioOwner, kind)
                as? LuaOpaqueAudioRegistry.Resolution.Synthesized)?.audio
        }
        if (audio == null) {
            resumeSemantic(pending, false, "E_STALE")
            return
        }
        val operation: CapabilityOperationResult<OpaqueAudioOperation> = when (
            val acquisition = capabilities.acquire(CapabilityKey.AudioOperation)
        ) {
            is CapabilityAcquisition.Available -> acquisition.lease.use { port ->
                if (audio is OpaqueAudioRecording) port.createPlaybackResult(audio)
                else port.createPlaybackResult(audio as OpaqueSynthesizedAudio)
            }
            is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable ->
                CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
            is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
            CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
            CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
            is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
        }
        if (operation !is CapabilityOperationResult.Success) {
            resumeSemantic(pending, false, operation.normalizedSemanticError())
            return
        }
        val scheduledResult: CapabilityOperationResult<dev.nilp0inter.subspace.model.DelayedPlaybackOutcome>? =
            when (val acquisition = capabilities.acquire(CapabilityKey.DeferredAudioPlayback)) {
                is CapabilityAcquisition.Available -> awaitSemanticBackend(
                    operation = {
                        acquisition.lease.use { port ->
                            CapabilityOperationResult.Success(
                                port.scheduleAudio(
                                    AgentOperationContext(
                                        capabilities.identity,
                                        AgentRunId(UUID.randomUUID().toString()),
                                        AgentOperationId(UUID.randomUUID().toString()),
                                    ),
                                    operation.value,
                                    (delay * 1000.0).toLong(),
                                ),
                            )
                        }
                    },
                )
                is CapabilityAcquisition.Recoverable, is CapabilityAcquisition.Unavailable ->
                    CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
                is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
                CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
                CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
                is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
            }
        if (scheduledResult == null) {
            dispose(operation.value)
            resumeSemantic(pending, false, "E_TIMEOUT")
            return
        }
        when (scheduledResult) {
            is CapabilityOperationResult.Success -> {
                val scheduled = scheduledResult.value
                if (scheduled is dev.nilp0inter.subspace.model.DelayedPlaybackOutcome.Pending ||
                    scheduled is dev.nilp0inter.subspace.model.DelayedPlaybackOutcome.Playing ||
                    scheduled is dev.nilp0inter.subspace.model.DelayedPlaybackOutcome.Heard
                ) {
                    if (!pending.semantic.claimTerminal()) {
                        dispose(operation.value)
                        return
                    }
                    val consumed = registry.consume(LuaOpaqueAudioRegistry.Token(token), pending.audioOwner, kind)
                    if (consumed == null) {
                        dispose(operation.value)
                        pending.semantic.completeClaimed(CallbackInvocationResult.Failure("E_STALE"))
                        resumeInput(pending.ownerToken, pending.operation.operationId.value, false, "E_STALE")
                        return
                    }
                    disposeRegistryArtifact(consumed)
                    pending.semantic.completeClaimed(CallbackInvocationResult.Success(LuaValue.StringValue("scheduled")))
                    resumeInput(pending.ownerToken, pending.operation.operationId.value, true, "scheduled")
                } else if (scheduled == dev.nilp0inter.subspace.model.DelayedPlaybackOutcome.Busy) {
                    // Queue quota rejection does not consume or dispose the caller's token;
                    // the Lua caller may retry once capacity is released.
                    resumeSemantic(pending, false, "E_BUSY")
                } else {
                    dispose(operation.value)
                    resumeSemantic(pending, false, "E_HOST_FAILURE")
                }
            }
            else -> {
                dispose(operation.value)
                resumeSemantic(pending, false, scheduledResult.normalizedSemanticError())
            }
        }
    }
    private fun disposeRegistryArtifact(entry: LuaOpaqueAudioRegistry.Entry) {
        when (entry) {
            is LuaOpaqueAudioRegistry.Entry.Captured -> entry.recording.dispose()
            is LuaOpaqueAudioRegistry.Entry.Synthesized -> entry.audio.dispose()
        }
    }

    private fun cancelPendingInput() {
        val pending = synchronized(inputLock) { pendingInputExecution } ?: return
        val hostContext = generationContext as? ActorRuntimeHostContext ?: return
        if (closed.get() || !generationContext.isActive()) return
        // Cancellation is a live terminal delivery, not generation retirement:
        // re-enter exactly the owner operation with E_CANCELLED. The generation
        // parent scope keeps this cleanup bounded to the actor lifetime without
        // consuming managed-task capacity or imposing a generic task deadline.
        hostContext.actorParentScope.launch {
            resumeInput(
                ownerToken = pending.ownerToken,
                operationId = pending.operation.operationId.value,
                success = false,
                value = "E_CANCELLED",
            )
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

internal data class LuaInputPending(
    val ownerToken: String,
    val operationId: Long,
    val label: String,
)

internal sealed interface LuaInputResumeResult {
    data object Accepted : LuaInputResumeResult
    data object Stale : LuaInputResumeResult
    data object ForeignOwner : LuaInputResumeResult
    data object Closed : LuaInputResumeResult
}
private data class PendingInputExecution(
    val ownerToken: String,
    val operation: LuaOperationHandle,
    val label: String,
    val terminal: CompletableDeferred<CallbackInvocationResult>,
    val audioOwner: LuaOpaqueAudioRegistry.Owner,
    val capturedAudioToken: String,
    val semantic: SemanticAudioOperation,
)

private class SemanticAudioOperation private constructor(
    val actorToken: ActorOperationToken,
    val generationIdentity: dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity,
) {
    private val terminalGate = AtomicBoolean(false)
    fun claimTerminal(): Boolean = terminalGate.compareAndSet(false, true)
    fun terminalize(result: CallbackInvocationResult): Boolean {
        if (!claimTerminal()) return false
        completeClaimed(result)
        return true
    }

    fun completeClaimed(result: CallbackInvocationResult) {
        val outcome = when (result) {
            is CallbackInvocationResult.Success -> ActorTerminal.Completed(actorToken.identity, result.value.toString())
            is CallbackInvocationResult.Failure -> ActorTerminal.Failed(actorToken.identity, result.diagnostic)
            is CallbackInvocationResult.YieldViolation -> ActorTerminal.Failed(actorToken.identity, result.diagnostic)
            is CallbackInvocationResult.InvalidOutcome -> ActorTerminal.Failed(actorToken.identity, result.diagnostic)
            is CallbackInvocationResult.Pending -> ActorTerminal.Failed(actorToken.identity, "semantic operation remained pending")
        }
        actorToken.complete(outcome)
    }

    companion object {
        fun create(
            operation: LuaOperationHandle,
            generationIdentity: dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity,
        ): SemanticAudioOperation {
            val identity = ActorOperationIdentity(
                scope = generationIdentity,
                taskId = ActorTaskIdentity(generationIdentity, ActorTaskId.next()),
                operationId = ActorOperationId.next(),
            )
            return SemanticAudioOperation(
                ActorOperationToken(identity, operation),
                generationIdentity,
            )
        }
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
    /** Input callback suspended on a host semantic operation. */
    data class Pending(
        val operation: LuaOperationHandle,
        val label: String,
    ) : CallbackInvocationResult()
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
    is CallbackInvocationResult.Pending -> "callback suspended"
}
