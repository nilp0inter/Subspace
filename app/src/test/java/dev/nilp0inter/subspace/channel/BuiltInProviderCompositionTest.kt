package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.CapabilityAcquisitionPolicy
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailabilityResult
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityLeaseTermination
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.DelayedPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.JournalEntryHandle
import dev.nilp0inter.subspace.channel.capability.JournalEntryRequest
import dev.nilp0inter.subspace.channel.capability.JournalStorageCapability
import dev.nilp0inter.subspace.channel.capability.JournalStoredCapture
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording
import dev.nilp0inter.subspace.channel.capability.OpaqueSynthesizedAudio
import dev.nilp0inter.subspace.channel.capability.RevocableChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.journalDerivation
import dev.nilp0inter.subspace.channel.capability.journalEntryHandle
import dev.nilp0inter.subspace.channel.capability.journalStoredCapture
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.SpeechSynthesisRequest
import dev.nilp0inter.subspace.channel.capability.SynthesisCapability
import dev.nilp0inter.subspace.channel.capability.SynthesizedAudioArtifact
import dev.nilp0inter.subspace.channel.capability.Transcription
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.channel.capability.opaqueAudioRecording
import dev.nilp0inter.subspace.lua.LuaNativeKernel
import dev.nilp0inter.subspace.lua.actor.ActorRuntimeFactory
import dev.nilp0inter.subspace.model.AcceptedAgentRun
import dev.nilp0inter.subspace.model.AgentConversationEnqueueRequest
import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelImplementationId
import dev.nilp0inter.subspace.model.ChannelImplementationProvider
import dev.nilp0inter.subspace.model.ChannelImplementationProviderRegistry
import dev.nilp0inter.subspace.model.ChannelProviderRegistrationResult
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionRequest
import dev.nilp0inter.subspace.model.ChannelRuntimeConstructionResult
import dev.nilp0inter.subspace.model.DelayedPlaybackOutcome
import dev.nilp0inter.subspace.model.DelayedPlaybackOperationId
import dev.nilp0inter.subspace.model.GenerationExecutionContextImpl
import dev.nilp0inter.subspace.model.JournalProviderConfiguration
import dev.nilp0inter.subspace.model.JournalProviderConfigurationCodec
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfiguration
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfigurationCodec
import dev.nilp0inter.subspace.model.OpenAiChatOutcome
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiModelChoice
import dev.nilp0inter.subspace.model.OpenAiModelDiscoveryOutcome
import dev.nilp0inter.subspace.model.OpenAiModelId
import dev.nilp0inter.subspace.model.OpaqueJsonObject
import dev.nilp0inter.subspace.model.ValidatedChannelConfiguration
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.RuntimeInvocationBoundary
import dev.nilp0inter.subspace.service.RuntimeWorkerDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInProviderCompositionTest {
    @Test
    fun everyBuiltInProviderOperatesThroughCapabilityPortsWithoutLuaStateConstruction() = runTest {
        LuaNativeKernel.resetForTest()
        ActorRuntimeFactory.resetForTest()
        try {
            val host = CompositionCapabilityHost()
            val providers = listOf<ChannelImplementationProvider>(
                JournalBuiltInProvider(),
                KeyboardBuiltInProvider(),
                OpenAiAgentBuiltInProvider(),
            )
            val registry = ChannelImplementationProviderRegistry()
            providers.forEach { provider ->
                assertEquals(ChannelProviderRegistrationResult.Registered, registry.register(provider))
            }
            assertEquals(
                listOf("builtin:journal", "builtin:keyboard", "builtin:openai-agent"),
                registry.descriptors().map { it.implementationId.value },
            )

            for (provider in providers) {
                val generation = RuntimeGeneration.next()
                val definition = ChannelDefinition(
                    id = "composition-${provider.descriptor.implementationId.value.removePrefix("builtin:")}",
                    name = provider.descriptor.presentation.label,
                    implementationId = provider.descriptor.implementationId,
                    enabled = true,
                    configSchemaVersion = 1,
                    configPayload = providerPayload(provider.descriptor.implementationId),
                )
                val payload = definition.configPayload
                val request = ChannelRuntimeConstructionRequest(
                    definition = definition,
                    configuration = ValidatedChannelConfiguration(
                        implementationId = provider.descriptor.implementationId,
                        schemaVersion = 1,
                        payload = payload,
                    ),
                    capabilities = RevocableChannelCapabilityScope(
                        identity = CapabilityScopeIdentity(definition.id, generation),
                        declaredCapabilities = provider.descriptor.requiredCapabilities,
                        host = host,
                    ),
                    generationContext = generationContext(definition.id, generation),
                )
                val result = provider.constructRuntime(request)
                val runtime = (result as? ChannelRuntimeConstructionResult.Success)?.runtime
                    ?: error("${provider.descriptor.implementationId.value} construction failed: $result")
                val target = runtime.prepareInput()
                assertTrue("${provider.descriptor.implementationId.value} did not admit input: $target", target is ChannelInputAcceptance.Accepted)
                val inputResult = (target as ChannelInputAcceptance.Accepted).target.onInputReleased(
                    RecordedPcm(shortArrayOf(1, -1), 16_000),
                )
                assertEquals(ChannelInputResult.None, inputResult)
                assertTrue(
                    provider.descriptor.implementationId.value,
                    runtime.snapshot.value.executionStatus == ChannelExecutionStatus.SUCCESS,
                )
                runtime.close()
            }
            assertFalse(ActorRuntimeFactory.isCreateAttempted)
            assertFalse(LuaNativeKernel.isLoadAttempted)
            assertEquals(
                listOf("journal", "agent"),
                host.operations,
            )
        } finally {
            ActorRuntimeFactory.resetForTest()
            LuaNativeKernel.resetForTest()
        }
    }

    private fun providerPayload(id: ChannelImplementationId): OpaqueJsonObject = when (id.value) {
        "builtin:journal" -> JournalProviderConfigurationCodec.encode(
            JournalProviderConfiguration("/records", saveVoice = true, saveText = true),
        )
        "builtin:keyboard" -> OpaqueJsonObject.parse("{\"hostProfile\":\"linux:us\"}").getOrThrow()
        "builtin:openai-agent" -> OpenAiAgentProviderConfigurationCodec.encode(
            OpenAiAgentProviderConfiguration(
                connectionProfileId = "profile",
                modelId = "model",
                systemPrompt = "system",
                keyboardEnabled = false,
                keyboardProfileId = null,
            ),
        )
        else -> error("unexpected built-in provider $id")
    }

    private fun generationContext(id: String, generation: RuntimeGeneration): GenerationExecutionContextImpl {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val boundary = RuntimeInvocationBoundary(RuntimeWorkerDispatcher.fromDispatcher(Dispatchers.Unconfined))
        val gate = boundary.openGeneration(id, generation, scope)
        return GenerationExecutionContextImpl(id, gate, scope)
    }

    private class CompositionCapabilityHost : ChannelCapabilityHost {
        val operations = mutableListOf<String>()

        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = available(key)

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = available(key)

        @Suppress("UNCHECKED_CAST")
        private fun <T : ChannelCapabilityPort> available(key: CapabilityKey<T>): HostedCapabilityAcquisition<T> = when (key) {
            CapabilityKey.TextOutput -> HostedCapabilityAcquisition.Available(object : dev.nilp0inter.subspace.channel.capability.TextOutputCapability {
                override suspend fun sendText(request: dev.nilp0inter.subspace.channel.capability.TextOutputRequest): dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome =
                    dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome.Delivered("text")

                override suspend fun sendKey(request: dev.nilp0inter.subspace.channel.capability.TextKeyRequest): dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome =
                    dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome.Delivered("key")
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            CapabilityKey.Journal -> HostedCapabilityAcquisition.Available(object : JournalStorageCapability {
                override suspend fun createEntry(request: JournalEntryRequest): CapabilityOperationResult<JournalEntryHandle> {
                    operations += "journal"
                    return CapabilityOperationResult.Success(journalEntryHandle("entry"))
                }

                override suspend fun storeCapture(
                    entry: JournalEntryHandle,
                    recording: OpaqueAudioRecording,
                ): CapabilityOperationResult<JournalStoredCapture> = CapabilityOperationResult.Success(journalStoredCapture("capture"))

                override suspend fun derive(entry: JournalEntryHandle): CapabilityOperationResult<dev.nilp0inter.subspace.channel.capability.JournalDerivation> =
                    CapabilityOperationResult.Success(journalDerivation("derivation"))
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            CapabilityKey.Transcription -> HostedCapabilityAcquisition.Available(object : TranscriptionCapability {
                override suspend fun transcribe(recording: OpaqueAudioRecording): CapabilityOperationResult<Transcription> =
                    CapabilityOperationResult.Success(Transcription("transcribed"))
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            CapabilityKey.Synthesis -> HostedCapabilityAcquisition.Available(object : SynthesisCapability {
                override suspend fun synthesize(request: SpeechSynthesisRequest): CapabilityOperationResult<OpaqueSynthesizedAudio> =
                    CapabilityOperationResult.Success(SynthesizedAudioArtifact(floatArrayOf(0.1f), generation = RuntimeGeneration(0)))
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            CapabilityKey.OpenAiModelDiscovery -> HostedCapabilityAcquisition.Available(object : dev.nilp0inter.subspace.channel.capability.OpenAiModelDiscoveryCapability {
                override suspend fun discover(profileId: OpenAiConnectionProfileId): OpenAiModelDiscoveryOutcome =
                    OpenAiModelDiscoveryOutcome.Available(
                        profileId,
                        listOf(OpenAiModelChoice(OpenAiModelId("model"), "Model")),
                    )
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            CapabilityKey.OpenAiCompletion -> HostedCapabilityAcquisition.Available(object : dev.nilp0inter.subspace.channel.capability.OpenAiCompletionCapability {
                override suspend fun complete(
                    context: AgentOperationContext,
                    request: dev.nilp0inter.subspace.model.OpenAiChatRequest,
                ): OpenAiChatOutcome = OpenAiChatOutcome.FinalAssistantMessage("done")
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            CapabilityKey.AsynchronousConversation -> HostedCapabilityAcquisition.Available(object : dev.nilp0inter.subspace.channel.capability.AsynchronousConversationCapability {
                override suspend fun enqueue(
                    scope: CapabilityScopeIdentity,
                    request: AgentConversationEnqueueRequest,
                ): CapabilityOperationResult<AcceptedAgentRun> {
                    operations += "agent"
                    return CapabilityOperationResult.Success(
                        AcceptedAgentRun(AgentRunId("run"), AgentMessageId("message"), AgentOperationId("operation")),
                    )
                }
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            CapabilityKey.DelayedPlayback -> HostedCapabilityAcquisition.Available(object : DelayedPlaybackCapability {
                override suspend fun schedule(
                    context: AgentOperationContext,
                    request: dev.nilp0inter.subspace.model.DelayedPlaybackRequest,
                ): DelayedPlaybackOutcome = DelayedPlaybackOutcome.Pending(DelayedPlaybackOperationId("delayed"))
            }) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            else -> HostedCapabilityAcquisition.Unavailable(dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason.UNSUPPORTED)
        }
    }
}

private fun OpaqueJsonObject.toJsonObject(): org.json.JSONObject = org.json.JSONObject(toJsonString())
