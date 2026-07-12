package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.AudioOperationArtifact
import dev.nilp0inter.subspace.channel.capability.AudioOperationCapabilityAdapter
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.DelayedPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioOperation
import dev.nilp0inter.subspace.channel.capability.OpenAiCompletionCapability
import dev.nilp0inter.subspace.channel.capability.OpenAiModelDiscoveryCapability
import dev.nilp0inter.subspace.channel.capability.OpaqueSynthesizedAudio
import dev.nilp0inter.subspace.channel.capability.SpeechSynthesisRequest
import dev.nilp0inter.subspace.channel.capability.SpeechVoice
import dev.nilp0inter.subspace.channel.capability.PlaybackResultFactory
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.recordedPcmOf
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfigurationCodec
import dev.nilp0inter.subspace.model.OpenAiToolResult
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkCompletionService
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkModelDiscoveryService
import kotlinx.coroutines.CoroutineScope

/** Service-lifetime owner of the durable agent execution graph and its capability projections. */
internal object ServiceAgentRuntimeComposition {
        fun create(
            context: android.content.Context,
            scope: CoroutineScope,
            catalogue: () -> ChannelCatalogueSnapshot,
            selectedChannel: suspend () -> String?,
            modelDiscovery: OpenAiSdkModelDiscoveryService,
            completion: OpenAiCompletionCapability,
            synthesize: suspend (String) -> CapabilityOperationResult<OpaqueSynthesizedAudio>,
            play: suspend (String, RecordedPcm) -> DelayedPlaybackAudioResult,
            playOperation: suspend (String, OpaqueAudioOperation) -> DelayedPlaybackAudioResult,
            textOutput: (String) -> dev.nilp0inter.subspace.channel.capability.TextOutputCapability,
            textOutputAvailable: () -> Boolean,
        ): ServiceAgentRuntimeGraph {
            val store = DurableAgentRunStore(java.io.File(context.noBackupFilesDir, "agent-runs.json"))
            check(store.load() is DurableAgentStoreResult.Success)
            lateinit var coordinator: AgentRunCoordinator
            val playback = DelayedPlaybackCoordinator(
                scope = scope,
                store = store,
                selectedChannel = selectedChannel,
                operationIsCurrent = { operation ->
                    catalogue().definitions.any { it.id == operation.scope.channelInstanceId }
                },
                synthesis = object : DelayedPlaybackSynthesisPort {
                    override suspend fun synthesize(text: String): DelayedPlaybackSynthesisResult = when (val result = synthesize(text)) {
                        is CapabilityOperationResult.Success -> DelayedPlaybackSynthesisResult.Success(result.value)
                        CapabilityOperationResult.Cancelled -> DelayedPlaybackSynthesisResult.Cancelled
                        else -> DelayedPlaybackSynthesisResult.Failed(dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason.SYNTHESIS_FAILED)
                    }
                },
                audio = object : DelayedPlaybackAudioPort {
                    override suspend fun playIfAdmitted(channelInstanceId: String, audio: OpaqueSynthesizedAudio): DelayedPlaybackAudioResult {
                        val operation = AudioOperationCapabilityAdapter(
                            PlaybackResultFactory { samples ->
                                AudioOperationArtifact(dev.nilp0inter.subspace.audio.TtsAudio.toScoPlayback(samples, 16_000))
                            },
                        ).createPlaybackResult(audio)
                        val recording = (operation as? CapabilityOperationResult.Success)?.value?.let(::recordedPcmOf)
                            ?: return DelayedPlaybackAudioResult.Failed(dev.nilp0inter.subspace.model.DelayedPlaybackFailureReason.SYNTHESIS_FAILED)
                        return play(channelInstanceId, recording)
                    }
                },
                onStateChanged = { coordinator.refreshProjection() }
            )
            coordinator = AgentRunCoordinator(
                scope = scope,
                store = store,
                configurationResolver = AgentRunConfigurationResolver { identity ->
                    val configuration = catalogue().definitions
                        .firstOrNull { it.id == identity.channelInstanceId && it.implementationId == BuiltInChannelImplementationIds.OPENAI_AGENT }
                        ?.let { OpenAiAgentProviderConfigurationCodec.decode(it.configPayload).getOrNull() }
                        ?: return@AgentRunConfigurationResolver null
                    val binding = ToolBrokerBinding(
                        channelInstanceId = identity.channelInstanceId,
                        keyboardEnabled = configuration.keyboardEnabled,
                        textOutputProfile = configuration.keyboardProfileId?.let(::TextOutputProfile),
                        textOutputAvailable = textOutputAvailable(),
                        isCurrent = { catalogue().definitions.any { it.id == identity.channelInstanceId } },
                    )
                    AgentRunCoordinatorConfiguration(
                        DurableAgentConfiguration(configuration.connectionProfileId, configuration.modelId, configuration.systemPrompt, configuration.toString().hashCode().toString()),
                        AgentToolRegistry(4_096).advertisedTools(binding),
                    )
                },
                completion = completion,
                tools = AgentToolExecutionPort { operation, call ->
                    val configuration = catalogue().definitions.firstOrNull { it.id == operation.scope.channelInstanceId }
                        ?.let { OpenAiAgentProviderConfigurationCodec.decode(it.configPayload).getOrNull() }
                        ?: return@AgentToolExecutionPort OpenAiToolResult(call.id, dev.nilp0inter.subspace.model.OpenAiToolOutcome.Rejected(dev.nilp0inter.subspace.model.OpenAiToolRejectionReason.STALE_OPERATION))
                    AgentToolBroker(AgentToolRegistry(4_096), store, textOutput(operation.scope.channelInstanceId), { operation.operationId }, 16)
                        .executeSequentially(operation.runId, ToolBrokerBinding(operation.scope.channelInstanceId, configuration.keyboardEnabled, configuration.keyboardProfileId?.let(::TextOutputProfile), textOutputAvailable()) { catalogue().definitions.any { it.id == operation.scope.channelInstanceId } }, listOf(call))
                        .single()
                },
                playback = playback,
            )
            val deferredAudioPlayback = DeferredAudioPlaybackCoordinator(
                scope = scope,
                selectedChannel = selectedChannel,
                operationIsCurrent = { operation ->
                    catalogue().definitions.any { it.id == operation.scope.channelInstanceId }
                },
                audio = object : DeferredAudioPlaybackAudioPort {
                    override suspend fun playOperationIfAdmitted(
                        channelInstanceId: String,
                        audio: OpaqueAudioOperation,
                    ): DelayedPlaybackAudioResult = playOperation(channelInstanceId, audio)
                },
            )
            return ServiceAgentRuntimeGraph(store, modelDiscovery, completion, coordinator, playback, deferredAudioPlayback)
        }
}

internal data class ServiceAgentRuntimeGraph(
    val store: DurableAgentRunStore,
    val modelDiscovery: OpenAiModelDiscoveryCapability,
    val completion: OpenAiCompletionCapability,
    val coordinator: AgentRunCoordinator,
    val playback: DelayedPlaybackCoordinator,
    val deferredAudioPlayback: DeferredAudioPlaybackCoordinator,
)
