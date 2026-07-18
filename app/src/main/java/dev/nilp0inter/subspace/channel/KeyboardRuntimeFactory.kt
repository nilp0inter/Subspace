package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.service.SubspaceLogger as Log

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisitionPolicy
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextKeyRequest
import dev.nilp0inter.subspace.channel.capability.TextOutputKey
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.channel.capability.opaqueAudioRecording
import dev.nilp0inter.subspace.model.BuiltInChannelDescriptors
import dev.nilp0inter.subspace.model.ChannelConfigurationField
import dev.nilp0inter.subspace.model.ChannelImplementationDescriptor
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ProviderRevisionFingerprint
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.KeyboardProviderConfiguration
import dev.nilp0inter.subspace.model.KeyboardProviderConfigurationCodec
import io.sleepwalker.core.keymap.HostProfile
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import dev.nilp0inter.subspace.service.ChannelRuntime
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Provider-backed Keyboard runtime construction with instance-scoped semantic capability leases. */
class KeyboardBuiltInProvider(
    initialProfiles: Collection<HostProfile> = listOf(HostProfile.LINUX_US),
) : ChannelImplementationProvider {
    override val fingerprint = ProviderRevisionFingerprint.BUILTIN
    @Volatile
    private var currentDescriptor = keyboardDescriptor(initialProfiles)

    override val descriptor
        get() = currentDescriptor

    internal fun updateHostProfiles(profiles: Collection<HostProfile>) {
        if (profiles.isNotEmpty()) currentDescriptor = keyboardDescriptor(profiles)
    }
    override suspend fun constructRuntime(
        request: ChannelRuntimeConstructionRequest,
    ): ChannelRuntimeConstructionResult {
        val configuration = KeyboardProviderConfigurationCodec.decode(request.configuration.payload)
            .getOrElse { error ->
                return ChannelRuntimeConstructionResult.Failure(
                    ChannelProviderError.RuntimeConstructionFailed(
                        descriptor.implementationId,
                        error.message ?: "Invalid Keyboard configuration",
                    ),
                )
            }
        val profile = runCatching { TextOutputProfile(configuration.hostProfileKey) }.getOrElse { error ->
            return ChannelRuntimeConstructionResult.Failure(
                ChannelProviderError.RuntimeConstructionFailed(
                    descriptor.implementationId,
                    error.message ?: "Invalid Keyboard profile",
                ),
            )
        }
        return ChannelRuntimeConstructionResult.Success(
            KeyboardRuntime(
                definition = request.definition,
                configuration = configuration,
                profile = profile,
                capabilities = request.capabilities,
                initialPreparation = request.capabilities.preparationFor(CapabilityKey.TextOutput, recoverable = true),
            ),
        )
    }
}

private fun keyboardDescriptor(profiles: Collection<HostProfile>): ChannelImplementationDescriptor {
    val choices = profiles
        .distinctBy { it.key }
        .sortedBy { it.key }
        .map { profile ->
            ChannelConfigurationField.ChoiceField.Choice(
                id = profile.key,
                label = buildString {
                    append(profile.layout)
                    profile.variant?.let { append(" ($it)") }
                    append(" [${profile.hostOs}]")
                },
            )
        }
    return BuiltInChannelDescriptors.keyboard.copy(
        configurationFields = listOf(
            ChannelConfigurationField.ChoiceField(
                id = "hostProfile",
                label = "Host profile",
                choices = choices,
            ),
        ),
    )
}

/**
 * Keyboard PTT is capture -> opaque transcription -> semantic text delivery. The runtime never
 * sees Sleepwalker connection state or transport objects and never closes shared text output.
 */
class KeyboardRuntime(
    val definition: dev.nilp0inter.subspace.model.ChannelDefinition,
    private val configuration: KeyboardProviderConfiguration,
    private val profile: TextOutputProfile,
    private val capabilities: dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope,
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
        if (closed.get()) return ChannelInputAcceptance.Unavailable("Keyboard runtime is closed")
        if (!definition.enabled) return ChannelInputAcceptance.Refused("Keyboard channel is disabled")

        return when (
            val acquisition = capabilities.acquire(
                CapabilityKey.TextOutput,
                CapabilityAcquisitionPolicy.PrepareRecoverable(PREPARATION_TIMEOUT_MILLIS),
            )
        ) {
            is CapabilityAcquisition.Available -> {
                acquisition.lease.release()
                updatePreparation(ChannelPreparationAvailability.Available)
                ChannelInputAcceptance.Accepted(KeyboardInputTarget())
            }
            else -> {
                val preparation = acquisition.preparation(recoverable = true)
                updatePreparation(preparation)
                ChannelInputAcceptance.Refused(preparation.reasonText())
            }
        }
    }

    override suspend fun refreshReadiness() {
        if (!closed.get()) {
            updatePreparation(capabilities.preparationFor(CapabilityKey.TextOutput, recoverable = true))
        }
    }

    override suspend fun handleSos() {
        if (closed.get() || !definition.enabled) return
        val result = capabilities.useCapability(CapabilityKey.TextOutput) { output ->
            CapabilityOperationResult.Success(
                output.sendKey(TextKeyRequest(TextOutputKey.ENTER, profile)),
            )
        }
        if (result !is CapabilityOperationResult.Success || result.value !is TextDeliveryOutcome.Delivered) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
            }
        }
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

    private inner class KeyboardInputTarget : ChannelInputTarget {
        override fun onInputStarted(session: ChannelAudioInputSession) {
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
            }
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            if (closed.get()) return ChannelInputResult.None
            if (recording.isEmpty) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
            val transcription = capabilities.useCapability(CapabilityKey.Transcription) { capability ->
                capability.transcribe(opaqueAudioRecording(recording))
            }
            val text = (transcription as? CapabilityOperationResult.Success)?.value?.text
            if (text == null) {
                if (!closed.get()) {
                    _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
                }
                return ChannelInputResult.None
            }
            if (text.isEmpty()) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val textToType = if (text.last() == ' ') {
                text
            } else {
                "$text "
            }
            Log.i(
                CHANNEL_EFFECT_TAG,
                "KEYBOARD_TRANSCRIPTION_RESULT instance=$id outcome=${transcription.diagnosticName()} text_length=${text.length}"
            )
            val delivery = capabilities.useCapability(CapabilityKey.TextOutput) { output ->
                CapabilityOperationResult.Success(output.sendText(TextOutputRequest(textToType, profile)))
            }
            Log.i(
                CHANNEL_EFFECT_TAG,
                "KEYBOARD_DELIVERY_RESULT instance=$id outcome=${delivery.diagnosticName()}",
            )
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(
                    executionStatus = if (
                        delivery is CapabilityOperationResult.Success &&
                        delivery.value is TextDeliveryOutcome.Delivered
                    ) {
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

    private companion object {
        const val PREPARATION_TIMEOUT_MILLIS = 15_000L
    }
}

private fun CapabilityOperationResult<*>.diagnosticName(): String = when (this) {
    is CapabilityOperationResult.Success -> when (val outcome = value) {
        is TextDeliveryOutcome.Delivered -> "delivered"
        is TextDeliveryOutcome.Rejected -> "rejected:${outcome.reason}"
        is TextDeliveryOutcome.Failed -> "failed:${outcome.reason}"
        is TextDeliveryOutcome.Indeterminate -> "indeterminate:${outcome.reason}"
        else -> "success"
    }
    is CapabilityOperationResult.Unavailable -> "unavailable:$reason"
    is CapabilityOperationResult.Denied -> "denied:$reason"
    CapabilityOperationResult.Closed -> "closed"
    CapabilityOperationResult.Cancelled -> "cancelled"
    is CapabilityOperationResult.Failed -> "failed:$reason"
}

private const val CHANNEL_EFFECT_TAG = "SubspaceChannel"
