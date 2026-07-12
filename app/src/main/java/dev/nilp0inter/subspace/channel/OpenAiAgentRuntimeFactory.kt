package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailabilityResult
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.opaqueAudioRecording
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ChannelProviderError
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfiguration
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfigurationCodec
import dev.nilp0inter.subspace.model.OpenAiModelDiscoveryOutcome
import dev.nilp0inter.subspace.model.OpenAiModelId
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import dev.nilp0inter.subspace.service.ChannelRuntime
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Native provider whose runtime only admits local speech to the host-owned durable agent queue.
 * Completion, tool effects, synthesis, playback, and all remote/client lifecycle remain host work.
 */
class OpenAiAgentBuiltInProvider : ChannelImplementationProvider {
    override val descriptor = dev.nilp0inter.subspace.model.BuiltInChannelDescriptors.openAiAgent

    override suspend fun constructRuntime(request: ChannelRuntimeConstructionRequest): ChannelRuntimeConstructionResult {
        val configuration = OpenAiAgentProviderConfigurationCodec.decode(request.configuration.payload)
            .getOrElse { error ->
                return ChannelRuntimeConstructionResult.Failure(
                    ChannelProviderError.RuntimeConstructionFailed(
                        descriptor.implementationId,
                        error.message ?: "Invalid OpenAI Agent configuration",
                    ),
                )
            }
        return ChannelRuntimeConstructionResult.Success(
            OpenAiAgentRuntime(request.definition, configuration, request.capabilities),
        )
    }
}

class OpenAiAgentRuntime(
    private val definition: dev.nilp0inter.subspace.model.ChannelDefinition,
    private val configuration: OpenAiAgentProviderConfiguration,
    private val capabilities: ChannelCapabilityScope,
) : ChannelRuntime {
    private val closed = AtomicBoolean(false)
    private val _snapshot = MutableStateFlow(
        ChannelRuntimeSnapshot(
            id = definition.id,
            name = definition.name,
            implementationId = definition.implementationId,
            enabled = definition.enabled,
            preparation = if (definition.enabled) {
                ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
            } else {
                ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.Disabled)
            },
            executionStatus = ChannelExecutionStatus.IDLE,
        ),
    )
    override val snapshot: StateFlow<ChannelRuntimeSnapshot> = _snapshot.asStateFlow()
    override val id: String get() = definition.id

    override suspend fun prepareInput(): ChannelInputAcceptance {
        if (closed.get()) return ChannelInputAcceptance.Unavailable("OpenAI Agent runtime is closed")
        if (!definition.enabled) return ChannelInputAcceptance.Refused("OpenAI Agent channel is disabled")
        val readiness = readiness()
        setPreparation(readiness)
        return when (readiness) {
            ChannelPreparationAvailability.Available -> ChannelInputAcceptance.Accepted(AgentInputTarget())
            is ChannelPreparationAvailability.Recoverable -> ChannelInputAcceptance.Refused(readiness.reason.message)
            is ChannelPreparationAvailability.Unavailable -> ChannelInputAcceptance.Refused(readiness.reason.message)
        }
    }

    override suspend fun refreshReadiness() {
        if (!closed.get() && definition.enabled) setPreparation(readiness())
    }

    override suspend fun handleSos() {
        if (closed.get() || !definition.enabled) return
        val reset = capabilities.useCapability(CapabilityKey.AsynchronousConversation) { conversation ->
            conversation.resetConversation(capabilities.identity)
            CapabilityOperationResult.Success(Unit)
        }
        if (reset !is CapabilityOperationResult.Success && !closed.get()) {
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
        } else if (!closed.get()) {
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
        }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            setPreparation(ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed))
        }
    }

    private suspend fun readiness(): ChannelPreparationAvailability {
        val required = listOf(
            CapabilityKey.Transcription,
            CapabilityKey.Synthesis,
            CapabilityKey.OpenAiCompletion,
            CapabilityKey.AsynchronousConversation,
            CapabilityKey.DelayedPlayback,
        ) + if (configuration.keyboardEnabled) listOf(CapabilityKey.TextOutput) else emptyList()
        for (key in required) {
            when (val availability = capabilities.availability(key)) {
                is CapabilityAvailabilityResult.State -> when (availability.availability) {
                    CapabilityAvailability.Available -> Unit
                    CapabilityAvailability.Recoverable -> return ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
                    is CapabilityAvailability.Unavailable -> return ChannelPreparationAvailability.Unavailable(
                        ChannelPreparationReason.RuntimeFailed("Required agent capability is unavailable"),
                    )
                }
                is CapabilityAvailabilityResult.Denied -> return ChannelPreparationAvailability.Unavailable(
                    ChannelPreparationReason.RuntimeFailed("Required agent capability is not authorized"),
                )
                CapabilityAvailabilityResult.Closed -> return ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed)
                CapabilityAvailabilityResult.Cancelled -> return ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.RuntimeCancelled)
                is CapabilityAvailabilityResult.Failed -> return ChannelPreparationAvailability.Unavailable(
                    ChannelPreparationReason.RuntimeFailed("Agent capability readiness failed"),
                )
            }
        }
        return when (val discovery = capabilities.useCapability(CapabilityKey.OpenAiModelDiscovery) { models ->
            CapabilityOperationResult.Success(models.discover(OpenAiConnectionProfileId(configuration.connectionProfileId)))
        }) {
            is CapabilityOperationResult.Success -> when (val outcome = discovery.value) {
                is OpenAiModelDiscoveryOutcome.Available -> if (!outcome.isStale && outcome.models.any { it.id == OpenAiModelId(configuration.modelId) }) {
                    ChannelPreparationAvailability.Available
                } else {
                    ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.ModelUnavailable)
                }
                is OpenAiModelDiscoveryOutcome.Loading -> ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.ProviderInitialising)
                is OpenAiModelDiscoveryOutcome.Unavailable -> ChannelPreparationAvailability.Unavailable(
                    ChannelPreparationReason.ConnectionProfileUnavailable(outcome.reason),
                )
            }
            is CapabilityOperationResult.Unavailable -> ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("Model discovery is unavailable"),
            )
            is CapabilityOperationResult.Denied -> ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("Model discovery is not authorized"),
            )
            CapabilityOperationResult.Closed -> ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed)
            CapabilityOperationResult.Cancelled -> ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.RuntimeCancelled)
            is CapabilityOperationResult.Failed -> ChannelPreparationAvailability.Unavailable(
                ChannelPreparationReason.RuntimeFailed("Model discovery failed"),
            )
        }
    }

    private fun setPreparation(value: ChannelPreparationAvailability) {
        if (!closed.get() || value is ChannelPreparationAvailability.Unavailable) {
            _snapshot.value = _snapshot.value.copy(preparation = value)
        }
    }

    private inner class AgentInputTarget : ChannelInputTarget {
        override fun onInputStarted(session: ChannelAudioInputSession) {
            if (!closed.get()) _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.RECORDING)
        }

        override suspend fun onInputReleased(recording: RecordedPcm): ChannelInputResult {
            if (closed.get()) return ChannelInputResult.None
            if (recording.isEmpty) {
                _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.PROCESSING)
            val transcription = withTimeoutOrNull(MAXIMUM_TRANSCRIPTION_MILLIS) {
                capabilities.useCapability(CapabilityKey.Transcription) { capability ->
                    capability.transcribe(opaqueAudioRecording(recording))
                }
            } ?: CapabilityOperationResult.Cancelled
            val text = (transcription as? CapabilityOperationResult.Success)?.value?.text?.trim()
            if (text.isNullOrEmpty()) {
                if (!closed.get()) _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
                return ChannelInputResult.None
            }
            val admission = capabilities.useCapability(CapabilityKey.AsynchronousConversation) { conversation ->
                conversation.enqueue(
                    capabilities.identity,
                    dev.nilp0inter.subspace.model.AgentConversationEnqueueRequest(
                        OpenAiConnectionProfileId(configuration.connectionProfileId),
                        OpenAiModelId(configuration.modelId),
                        text,
                    ),
                )
            }
            if (!closed.get()) {
                _snapshot.value = _snapshot.value.copy(
                    executionStatus = if (admission is CapabilityOperationResult.Success) {
                        ChannelExecutionStatus.SUCCESS
                    } else {
                        ChannelExecutionStatus.FAILED
                    },
                )
            }
            return ChannelInputResult.None
        }

        override fun onInputCancelled(reason: String) {
            if (!closed.get()) _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.IDLE)
        }

        override fun onInputFailed(reason: String) {
            if (!closed.get()) _snapshot.value = _snapshot.value.copy(executionStatus = ChannelExecutionStatus.FAILED)
        }
    }

    private companion object {
        const val MAXIMUM_TRANSCRIPTION_MILLIS = 30_000L
    }
}
