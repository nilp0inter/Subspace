package dev.nilp0inter.subspace.service

import android.content.Context
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.OpenAiCompletionCapability
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.SynthesizedAudioArtifact
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioOperation
import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextKeyRequest
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.model.AgentConversationEnqueueRequest
import dev.nilp0inter.subspace.model.AgentRunState
import dev.nilp0inter.subspace.model.AgentToolCallId
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelCatalogueSnapshot
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfiguration
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfigurationCodec
import dev.nilp0inter.subspace.model.OpenAiChatOutcome
import dev.nilp0inter.subspace.model.OpenAiChatRequest
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiMessage
import dev.nilp0inter.subspace.model.OpenAiModelId
import dev.nilp0inter.subspace.model.OpenAiToolArgumentValue
import dev.nilp0inter.subspace.model.OpenAiToolCall
import dev.nilp0inter.subspace.openai.adapter.OpenAiSdkModelDiscoveryService
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAgentRuntimeCompositionTest {
    @Test
    fun `catalogue Agent definition durably composes PTT turn keyboard effect selected playback and privacy-safe status`() = runTest {
        withTemporaryDirectory { directory ->
            val catalogue = MutableStateFlow(
                ChannelCatalogueSnapshot(
                    definitions = listOf(agentDefinition(systemPrompt = "system secret must stay private")),
                    activeChannelId = AGENT_ID,
                ),
            )
            val completion = ScriptedCompletion(
                ArrayDeque(
                    listOf(
                        OpenAiChatOutcome.ToolCalls(
                            listOf(
                                OpenAiToolCall(
                                    id = AgentToolCallId("type-once"),
                                    name = AgentToolRegistry.KeyboardTypeText,
                                    arguments = mapOf("text" to OpenAiToolArgumentValue.Text("launch sequence")),
                                ),
                            ),
                        ),
                        OpenAiChatOutcome.FinalAssistantMessage("assistant response must stay private"),
                    ),
                ),
            )
            val textOutput = RecordingTextOutput()
            val played = mutableListOf<Pair<String, RecordedPcm>>()
            val graph = graph(
                directory = directory,
                scope = this,
                catalogue = catalogue,
                completion = completion,
                textOutput = textOutput,
                play = { channelId, recording ->
                    played += channelId to recording
                    DelayedPlaybackAudioResult.Completed
                },
            )

            val admitted = graph.coordinator.enqueue(agentScope(), enqueueRequest("PTT question must stay private"))
            assertTrue(admitted is CapabilityOperationResult.Success)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    OpenAiConnectionProfileId("profile-from-definition") to OpenAiModelId("model-from-definition"),
                    OpenAiConnectionProfileId("profile-from-definition") to OpenAiModelId("model-from-definition"),
                ),
                completion.requests.map { it.profileId to it.modelId },
            )
            assertEquals(
                listOf(OpenAiMessage.System("system secret must stay private"), OpenAiMessage.User("PTT question must stay private")),
                completion.requests.first().messages,
            )
            assertEquals(
                listOf(AgentToolRegistry.KeyboardTypeText, AgentToolRegistry.KeyboardPressEnter),
                completion.requests.first().tools.map { it.name },
            )
            assertEquals(
                listOf(TextOutputRequest("launch sequence", dev.nilp0inter.subspace.channel.capability.TextOutputProfile("keyboard-profile"))),
                textOutput.textRequests,
            )
            assertEquals(listOf(AGENT_ID), played.map { it.first })
            assertEquals(listOf(16_384.toShort()), played.single().second.samples.toList())

            val durable = graph.store.snapshot()
            assertEquals(1, durable.runs.size)
            assertEquals(DurableRunState.COMPLETED, durable.runs.single().state)
            assertEquals(
                listOf(DurableMessageLifecycle.COMPLETED, DurableMessageLifecycle.HEARD),
                durable.messages.map { it.lifecycle },
            )
            val status = graph.coordinator.status.value.getValue(AGENT_ID)
            assertEquals(AgentRunState.COMPLETED, status.state)
            assertEquals(0, status.queuedTurnCount)
            assertEquals(0, status.pendingResponseCount)
            val visibleStatus = graph.coordinator.status.value.toString()
            assertFalse(visibleStatus.contains("system secret must stay private"))
            assertFalse(visibleStatus.contains("PTT question must stay private"))
            assertFalse(visibleStatus.contains("assistant response must stay private"))

            graph.coordinator.shutdown()
            graph.playback.close()
        }
    }

    @Test
    fun `SOS reset and definition replacement revoke blocked Agent generations before they publish effects`() = runTest {
        withTemporaryDirectory { directory ->
            val catalogue = MutableStateFlow(
                ChannelCatalogueSnapshot(listOf(agentDefinition()), AGENT_ID),
            )
            val completion = BlockingCompletion()
            val textOutput = RecordingTextOutput()
            val played = mutableListOf<Pair<String, RecordedPcm>>()
            val graph = graph(directory, this, catalogue, completion, textOutput) { channelId, recording ->
                played += channelId to recording
                DelayedPlaybackAudioResult.Completed
            }

            assertTrue(graph.coordinator.enqueue(agentScope(), enqueueRequest("reset this turn")) is CapabilityOperationResult.Success)
            runCurrent()
            assertEquals(1, completion.started)
            assertTrue(graph.coordinator.enqueue(agentScope(), enqueueRequest("queued turn reset with the active turn")) is CapabilityOperationResult.Success)
            val queued = graph.coordinator.status.value.getValue(AGENT_ID)
            assertEquals(AgentRunState.RUNNING, queued.state)
            assertEquals(1, queued.queuedTurnCount)
            assertEquals(0, queued.pendingResponseCount)
            graph.coordinator.resetConversation(agentScope())
            runCurrent()
            assertEquals(AgentRunState.INDETERMINATE, graph.coordinator.status.value.getValue(AGENT_ID).state)
            assertEquals(
                dev.nilp0inter.subspace.model.AgentRunTerminalOutcome.Indeterminate(
                    dev.nilp0inter.subspace.model.AgentRunIndeterminateReason.REMOTE_EFFECT_UNCONFIRMED,
                ),
                graph.coordinator.status.value.getValue(AGENT_ID).terminalOutcome,
            )

            assertTrue(graph.coordinator.enqueue(agentScope(), enqueueRequest("replace this turn")) is CapabilityOperationResult.Success)
            runCurrent()
            assertEquals(2, completion.started)
            catalogue.value = ChannelCatalogueSnapshot(
                listOf(agentDefinition(modelId = "replacement-model", systemPrompt = "replacement prompt")),
                AGENT_ID,
            )
            graph.coordinator.replace(agentScope())
            runCurrent()

            assertEquals(
                listOf(DurableRunState.INDETERMINATE, DurableRunState.CANCELLED, DurableRunState.INDETERMINATE),
                graph.store.snapshot().runs.map { it.state },
            )
            val terminal = graph.coordinator.status.value.getValue(AGENT_ID)
            assertEquals(AgentRunState.INDETERMINATE, terminal.state)
            assertEquals(
                dev.nilp0inter.subspace.model.AgentRunTerminalOutcome.Indeterminate(
                    dev.nilp0inter.subspace.model.AgentRunIndeterminateReason.REMOTE_EFFECT_UNCONFIRMED,
                ),
                terminal.terminalOutcome,
            )
            assertTrue(textOutput.textRequests.isEmpty())
            assertTrue(played.isEmpty())

            graph.coordinator.shutdown()
            graph.playback.close()
        }
    }

    @Test
    fun `shutdown leaves a submitted run indeterminate on restart instead of replaying the remote completion`() = runTest {
        withTemporaryDirectory { directory ->
            val catalogue = MutableStateFlow(ChannelCatalogueSnapshot(listOf(agentDefinition()), AGENT_ID))
            val submitted = BlockingCompletion()
            val first = graph(directory, this, catalogue, submitted, RecordingTextOutput()) { _, _ -> DelayedPlaybackAudioResult.Completed }

            assertTrue(first.coordinator.enqueue(agentScope(), enqueueRequest("do not replay after shutdown")) is CapabilityOperationResult.Success)
            runCurrent()
            assertEquals(1, submitted.started)
            first.coordinator.shutdown()
            runCurrent()
            first.playback.close()

            val afterRestart = ScriptedCompletion(ArrayDeque(listOf(OpenAiChatOutcome.FinalAssistantMessage("must not run"))))
            val restarted = graph(directory, this, catalogue, afterRestart, RecordingTextOutput()) { _, _ -> DelayedPlaybackAudioResult.Completed }
            restarted.coordinator.start()
            advanceUntilIdle()

            assertEquals(1, submitted.started)
            assertTrue(afterRestart.requests.isEmpty())
            val run = restarted.store.snapshot().runs.single()
            assertEquals(DurableRunState.INDETERMINATE, run.state)
            assertEquals(DurableRunTerminalReason.AMBIGUOUS_REMOTE_SUBMISSION, run.terminalReason)

            restarted.coordinator.shutdown()
            restarted.playback.close()
        }
    }

    @Test
    fun `removed Agent definition rejects new work and shutdown permanently closes the composition graph`() = runTest {
        withTemporaryDirectory { directory ->
            val catalogue = MutableStateFlow(ChannelCatalogueSnapshot(listOf(agentDefinition()), AGENT_ID))
            val completion = ScriptedCompletion(ArrayDeque(listOf(OpenAiChatOutcome.FinalAssistantMessage("unused"))))
            val graph = graph(directory, this, catalogue, completion, RecordingTextOutput()) { _, _ -> DelayedPlaybackAudioResult.Completed }

            catalogue.value = ChannelCatalogueSnapshot(emptyList(), "")
            assertEquals(
                CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED),
                graph.coordinator.enqueue(agentScope(), enqueueRequest("definition was removed")),
            )
            assertTrue(completion.requests.isEmpty())
            assertTrue(graph.store.snapshot().runs.isEmpty())

            graph.coordinator.shutdown()
            assertEquals(CapabilityOperationResult.Closed, graph.coordinator.enqueue(agentScope(), enqueueRequest("after shutdown")))
            graph.playback.close()
        }
    }

    private fun graph(
        directory: File,
        scope: CoroutineScope,
        catalogue: MutableStateFlow<ChannelCatalogueSnapshot>,
        completion: OpenAiCompletionCapability,
        textOutput: TextOutputCapability,
        playOperation: suspend (String, OpaqueAudioOperation) -> DelayedPlaybackAudioResult = { _, _ -> DelayedPlaybackAudioResult.Completed },
        play: suspend (String, RecordedPcm) -> DelayedPlaybackAudioResult,
    ): ServiceAgentRuntimeGraph {
        val context = mockk<Context>()
        every { context.noBackupFilesDir } returns directory
        return ServiceAgentRuntimeComposition.create(
            context = context,
            scope = scope,
            catalogue = { catalogue.value },
            selectedChannel = { catalogue.value.activeChannelId },
            modelDiscovery = mockk<OpenAiSdkModelDiscoveryService>(relaxed = true),
            completion = completion,
            synthesize = { CapabilityOperationResult.Success(SynthesizedAudioArtifact(floatArrayOf(0.5f))) },
            play = play,
            playOperation = playOperation,
            textOutput = { textOutput },
            textOutputAvailable = { true },
        )
    }

    private fun agentDefinition(
        modelId: String = "model-from-definition",
        systemPrompt: String = "configured system prompt",
    ): ChannelDefinition = ChannelDefinition(
        id = AGENT_ID,
        name = "Configured Agent",
        implementationId = BuiltInChannelImplementationIds.OPENAI_AGENT,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = OpenAiAgentProviderConfigurationCodec.encode(
            OpenAiAgentProviderConfiguration(
                connectionProfileId = "profile-from-definition",
                modelId = modelId,
                systemPrompt = systemPrompt,
                keyboardEnabled = true,
                keyboardProfileId = "keyboard-profile",
            ),
        ),
    )

    private fun agentScope() = CapabilityScopeIdentity(AGENT_ID, RuntimeGeneration(7))

    private fun enqueueRequest(text: String) = AgentConversationEnqueueRequest(
        profileId = OpenAiConnectionProfileId("caller-profile-must-not-override-definition"),
        modelId = OpenAiModelId("caller-model-must-not-override-definition"),
        userText = text,
    )

    private suspend fun <T> withTemporaryDirectory(block: suspend (File) -> T): T {
        val directory = createTempDirectory("service-agent-runtime-composition-test-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class ScriptedCompletion(
        private val outcomes: ArrayDeque<OpenAiChatOutcome>,
    ) : OpenAiCompletionCapability {
        val requests = mutableListOf<OpenAiChatRequest>()

        override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
            requests += request
            return outcomes.removeFirstOrNull() ?: throw AssertionError("Unexpected extra completion request")
        }
    }

    private class BlockingCompletion : OpenAiCompletionCapability {
        var started = 0
            private set

        override suspend fun complete(context: AgentOperationContext, request: OpenAiChatRequest): OpenAiChatOutcome {
            started += 1
            awaitCancellation()
        }
    }

    private class RecordingTextOutput : TextOutputCapability {
        val textRequests = mutableListOf<TextOutputRequest>()

        override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
            textRequests += request
            return TextDeliveryOutcome.Delivered("text-${textRequests.size}")
        }

        override suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome =
            throw AssertionError("The composition test only configures keyboard_type_text")
    }

    private companion object {
        const val AGENT_ID = "agent-instance"
    }
}
