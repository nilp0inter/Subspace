package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.JournalEntryRequest
import dev.nilp0inter.subspace.channel.capability.JournalStorageCapability
import dev.nilp0inter.subspace.channel.capability.opaqueAudioRecording
import dev.nilp0inter.subspace.model.BuiltInChannelDescriptors
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.JournalProviderConfiguration
import dev.nilp0inter.subspace.model.JournalProviderConfigurationCodec
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import dev.nilp0inter.subspace.service.ChannelRuntime
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provider-backed Journal runtime construction with no filesystem or controller authority. */
class JournalBuiltInProvider : ChannelImplementationProvider {
    override val descriptor = BuiltInChannelDescriptors.journal

    override suspend fun constructRuntime(
        request: ChannelRuntimeConstructionRequest,
    ): ChannelRuntimeConstructionResult {
        val configuration = JournalProviderConfigurationCodec.decode(request.configuration.payload)
            .getOrElse { error ->
                return ChannelRuntimeConstructionResult.Failure(
                    ChannelProviderError.RuntimeConstructionFailed(
                        descriptor.implementationId,
                        error.message ?: "Invalid Journal configuration",
                    ),
                )
            }
        return ChannelRuntimeConstructionResult.Success(
            JournalRuntime(
                definition = request.definition,
                configuration = configuration,
                capabilities = request.capabilities,
                initialPreparation = initialPreparation(configuration, request.capabilities),
            ),
        )
    }

    private suspend fun initialPreparation(
        configuration: JournalProviderConfiguration,
        capabilities: ChannelCapabilityScope,
    ): ChannelPreparationAvailability = when {
        configuration.baseDirectory.isNullOrBlank() -> ChannelPreparationAvailability.Unavailable(
            ChannelPreparationReason.RuntimeFailed("Journal storage directory is not configured"),
        )
        !configuration.saveVoice && !configuration.saveText -> ChannelPreparationAvailability.Unavailable(
            ChannelPreparationReason.RuntimeFailed("No Journal output is enabled"),
        )
        else -> capabilities.preparationFor(CapabilityKey.Journal, recoverable = false)
    }
}

/**
 * Instance-local Journal input target. Storage, recovery, and durable derivation remain behind
 * the Journal capability; this runtime never owns paths, files, controller jobs, or a host scope.
 */
class JournalRuntime(
    val definition: dev.nilp0inter.subspace.model.ChannelDefinition,
    private val configuration: JournalProviderConfiguration,
    private val capabilities: ChannelCapabilityScope,
    initialPreparation: ChannelPreparationAvailability,
) : ChannelRuntime {
    private val closed = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            implementationId = definition.implementationId,
            enabled = definition.enabled,
            preparation = if (definition.enabled) initialPreparation else disabledPreparation(),
            executionStatus = ChannelExecutionStatus.IDLE,
        ),
    )
    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()

    override val id: String
        get() = definition.id

    override suspend fun prepareInput(): ChannelInputAcceptance {
        if (closed.get()) return ChannelInputAcceptance.Unavailable("Journal runtime is closed")
        if (!definition.enabled) return ChannelInputAcceptance.Refused("Journal channel is disabled")
        if (configuration.baseDirectory.isNullOrBlank()) {
            updatePreparation(ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("Journal storage directory is not configured"),
            ))
            return ChannelInputAcceptance.Refused("Journal storage directory is not configured")
        }
        if (!configuration.saveVoice && !configuration.saveText) {
            return ChannelInputAcceptance.Unavailable("No Journal output is enabled")
        }

        return when (val acquisition = capabilities.acquire(CapabilityKey.Journal)) {
            is CapabilityAcquisition.Available -> {
                acquisition.lease.release()
                updatePreparation(ChannelPreparationAvailability.Available)
                ChannelInputAcceptance.Accepted(JournalInputTarget())
            }
            else -> {
                val preparation = acquisition.preparation(recoverable = false)
                updatePreparation(preparation)
                ChannelInputAcceptance.Refused(preparation.reasonText())
            }
        }
    }

    override suspend fun refreshReadiness() {
        if (closed.get()) return
        val preparation = when {
            !definition.enabled -> disabledPreparation()
            configuration.baseDirectory.isNullOrBlank() -> ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("Journal storage directory is not configured"),
            )
            !configuration.saveVoice && !configuration.saveText -> ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("No Journal output is enabled"),
            )
            else -> capabilities.preparationFor(CapabilityKey.Journal, recoverable = false)
        }
        updatePreparation(preparation)
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            updatePreparation(ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed))
        }
    }

    private fun updatePreparation(preparation: ChannelPreparationAvailability) {
        if (!closed.get() || preparation is ChannelPreparationAvailability.Unavailable) {
            _snapshot.value = _snapshot.value.copy(preparation = preparation)
        }
    }

    private inner class JournalInputTarget : ChannelInputTarget {
        override fun onInputStarted(session: ChannelAudioInputSession) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            if (closed.get()) return ChannelInputResult.None
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
            val result = capabilities.useCapability(CapabilityKey.Journal) { journal ->
                when (
                    val entry = journal.createEntry(
                        JournalEntryRequest(
                            capturedAtEpochMillis = System.currentTimeMillis(),
                            saveVoice = configuration.saveVoice,
                            saveText = configuration.saveText,
                        ),
                    )
                ) {
                    is CapabilityOperationResult.Success -> journal.storeCapture(
                        entry.value,
                        opaqueAudioRecording(recording),
                    ).mapToUnit()
                    else -> entry.mapToUnit()
                }
            }
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(
                    executionStatus = if (result is CapabilityOperationResult.Success) {
                        ChannelExecutionStatus.SUCCESS
                    } else {
                        ChannelExecutionStatus.FAILED
                    },
                )
            }
            return ChannelInputResult.None
        }

        override fun onInputCancelled(reason: String) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
            }
        }

        override fun onInputFailed(reason: String) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
            }
        }
    }
}

internal suspend fun <T : ChannelCapabilityPort, R> ChannelCapabilityScope.useCapability(
    key: CapabilityKey<T>,
    operation: suspend (T) -> CapabilityOperationResult<R>,
): CapabilityOperationResult<R> = when (val acquisition = acquire(key)) {
    is CapabilityAcquisition.Available -> try {
        acquisition.lease.use(operation)
    } finally {
        acquisition.lease.release()
    }
    is CapabilityAcquisition.Recoverable -> CapabilityOperationResult.Unavailable(acquisition.reason)
    is CapabilityAcquisition.Unavailable -> CapabilityOperationResult.Unavailable(acquisition.reason)
    is CapabilityAcquisition.Denied -> CapabilityOperationResult.Denied(acquisition.reason)
    CapabilityAcquisition.Closed -> CapabilityOperationResult.Closed
    CapabilityAcquisition.Cancelled -> CapabilityOperationResult.Cancelled
    is CapabilityAcquisition.Failed -> CapabilityOperationResult.Failed(acquisition.reason)
}

private fun <T> CapabilityOperationResult<T>.mapToUnit(): CapabilityOperationResult<Unit> = when (this) {
    is CapabilityOperationResult.Success -> CapabilityOperationResult.Success(Unit)
    is CapabilityOperationResult.Unavailable -> CapabilityOperationResult.Unavailable(reason)
    is CapabilityOperationResult.Denied -> CapabilityOperationResult.Denied(reason)
    CapabilityOperationResult.Closed -> CapabilityOperationResult.Closed
    CapabilityOperationResult.Cancelled -> CapabilityOperationResult.Cancelled
    is CapabilityOperationResult.Failed -> CapabilityOperationResult.Failed(reason)
}

internal suspend fun ChannelCapabilityScope.preparationFor(
    key: CapabilityKey<*>,
    recoverable: Boolean,
): ChannelPreparationAvailability = when (val result = availability(key)) {
    is dev.nilp0inter.subspace.channel.capability.CapabilityAvailabilityResult.State -> when (result.availability) {
        dev.nilp0inter.subspace.channel.capability.CapabilityAvailability.Available -> ChannelPreparationAvailability.Available
        dev.nilp0inter.subspace.channel.capability.CapabilityAvailability.Recoverable -> if (recoverable) {
            ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
        } else {
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.ProviderInitialising)
        }
        is dev.nilp0inter.subspace.channel.capability.CapabilityAvailability.Unavailable ->
            ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed(result.availability.reason.name),
            )
    }
    else -> ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed("Journal capability unavailable"))
}

internal fun CapabilityAcquisition<*>.preparation(
    recoverable: Boolean,
): ChannelPreparationAvailability = when (this) {
    is CapabilityAcquisition.Recoverable -> if (recoverable) {
        ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
    } else {
        ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeFailed(reason.name))
    }
    is CapabilityAcquisition.Unavailable -> ChannelPreparationAvailability.Unavailable(
        ChannelPreparationReason.RuntimeFailed(reason.name),
    )
    is CapabilityAcquisition.Denied -> ChannelPreparationAvailability.Unavailable(
        ChannelPreparationReason.RuntimeFailed(reason.name),
    )
    CapabilityAcquisition.Closed -> ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed)
    CapabilityAcquisition.Cancelled -> ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeCancelled)
    is CapabilityAcquisition.Failed -> ChannelPreparationAvailability.Unavailable(
        ChannelPreparationReason.RuntimeFailed(reason.name),
    )
    is CapabilityAcquisition.Available -> ChannelPreparationAvailability.Available
}

internal fun ChannelPreparationAvailability.reasonText(): String = when (this) {
    ChannelPreparationAvailability.Available -> "Channel is ready"
    is ChannelPreparationAvailability.Recoverable -> "Channel preparation is pending"
    is ChannelPreparationAvailability.Unavailable -> when (reason) {
        is ChannelPreparationReason.RuntimeFailed -> reason.message
        else -> "Channel is unavailable"
    }
}

internal fun disabledPreparation(): ChannelPreparationAvailability.Unavailable =
    ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled)
