package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.channel.capability.AsynchronousConversationCapability
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityLeaseTermination
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.OpenAiModelDiscoveryCapability
import dev.nilp0inter.subspace.channel.capability.RevocableChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.Transcription
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.model.AcceptedAgentRun
import dev.nilp0inter.subspace.model.AgentConversationEnqueueRequest
import dev.nilp0inter.subspace.model.AgentMessageId
import dev.nilp0inter.subspace.model.AgentOperationId
import dev.nilp0inter.subspace.model.AgentRunId
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfiguration
import dev.nilp0inter.subspace.model.OpenAiAgentProviderConfigurationCodec
import dev.nilp0inter.subspace.model.OpenAiAvailabilityReason
import dev.nilp0inter.subspace.model.OpenAiConnectionProfileId
import dev.nilp0inter.subspace.model.OpenAiModelChoice
import dev.nilp0inter.subspace.model.OpenAiModelDiscoveryOutcome
import dev.nilp0inter.subspace.model.OpenAiModelId
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiAgentRuntimeTest {
    @Test
    fun `readiness preserves profile and model failures as typed preparation reasons`() = runTest {
        val profile = OpenAiConnectionProfileId("selected-profile")
        data class Case(
            val name: String,
            val discovery: OpenAiModelDiscoveryOutcome,
            val expected: ChannelPreparationReason,
        )
        val cases = listOf(
            Case(
                name = "deleted profile",
                discovery = OpenAiModelDiscoveryOutcome.Unavailable(profile, OpenAiAvailabilityReason.PROFILE_MISSING),
                expected = ChannelPreparationReason.ConnectionProfileUnavailable(OpenAiAvailabilityReason.PROFILE_MISSING),
            ),
            Case(
                name = "stale model list",
                discovery = OpenAiModelDiscoveryOutcome.Available(
                    profile,
                    listOf(OpenAiModelChoice(OpenAiModelId("selected-model"), "Selected model")),
                    isStale = true,
                ),
                expected = ChannelPreparationReason.ModelUnavailable,
            ),
            Case(
                name = "model no longer listed",
                discovery = OpenAiModelDiscoveryOutcome.Available(
                    profile,
                    listOf(OpenAiModelChoice(OpenAiModelId("replacement-model"), "Replacement model")),
                ),
                expected = ChannelPreparationReason.ModelUnavailable,
            ),
        )

        cases.forEach { case ->
            val runtime = fixture(host = AgentCapabilityHost(discovery = case.discovery)).runtime

            assertEquals(
                "${case.name}: input must be refused until its referenced resource is usable",
                ChannelInputAcceptance.Refused(case.expected.message),
                runtime.prepareInput(),
            )
            assertEquals(
                ChannelPreparationAvailability.Unavailable(case.expected),
                runtime.snapshot.value.preparation,
            )
        }
    }

    @Test
    fun `keyboard capability is required only for configurations that enable keyboard tools`() = runTest {
        val unavailableTextOutput = CapabilityAvailability.Unavailable(CapabilityUnavailableReason.NOT_CONFIGURED)
        val normal = fixture(
            host = AgentCapabilityHost(textOutputAvailability = unavailableTextOutput),
            configuration = configuration(keyboardEnabled = false, keyboardProfileId = null),
        ).runtime
        val keyboardEnabled = fixture(
            host = AgentCapabilityHost(textOutputAvailability = unavailableTextOutput),
            configuration = configuration(keyboardEnabled = true, keyboardProfileId = "keyboard-profile"),
        ).runtime

        assertTrue(normal.prepareInput() is ChannelInputAcceptance.Accepted)
        assertEquals(
            ChannelInputAcceptance.Refused("Required agent capability is unavailable"),
            keyboardEnabled.prepareInput(),
        )
    }

    @Test
    fun `empty recordings and blank transcripts never enter the durable agent queue`() = runTest {
        val emptyRecordingHost = AgentCapabilityHost(transcript = "spoken text")
        val emptyRecording = fixture(host = emptyRecordingHost).runtime
        val emptyTarget = acceptedTarget(emptyRecording)

        assertEquals(ChannelInputResult.None, emptyTarget.onInputReleased(RecordedPcm(shortArrayOf(), 16_000)))
        assertEquals(0, emptyRecordingHost.transcriptionCount)
        assertTrue(emptyRecordingHost.enqueued.isEmpty())
        assertEquals(ChannelExecutionStatus.FAILED, emptyRecording.snapshot.value.executionStatus)

        val blankTranscriptHost = AgentCapabilityHost(transcript = "  \n\t ")
        val blankTranscript = fixture(host = blankTranscriptHost).runtime
        val blankTarget = acceptedTarget(blankTranscript)

        assertEquals(ChannelInputResult.None, blankTarget.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)))
        assertEquals(1, blankTranscriptHost.transcriptionCount)
        assertTrue(blankTranscriptHost.enqueued.isEmpty())
        assertEquals(ChannelExecutionStatus.FAILED, blankTranscript.snapshot.value.executionStatus)
    }

    @Test
    fun `accepted transcript is trimmed enqueued once and returns control to the host queue`() = runTest {
        val host = AgentCapabilityHost(transcript = "  send this to the agent  ")
        val runtime = fixture(host = host).runtime

        val result = acceptedTarget(runtime).onInputReleased(RecordedPcm(shortArrayOf(1, -1), 16_000))

        assertEquals(ChannelInputResult.None, result)
        assertEquals(
            listOf(
                AgentConversationEnqueueRequest(
                    profileId = OpenAiConnectionProfileId("selected-profile"),
                    modelId = OpenAiModelId("selected-model"),
                    userText = "send this to the agent",
                ),
            ),
            host.enqueued,
        )
        assertEquals(ChannelExecutionStatus.SUCCESS, runtime.snapshot.value.executionStatus)
    }

    @Test
    fun `queue admission failure remains terminal locally and does not manufacture playback or completion`() = runTest {
        val host = AgentCapabilityHost(
            transcript = "request",
            admission = CapabilityOperationResult.Unavailable(CapabilityUnavailableReason.RESOURCE_BUSY),
        )
        val runtime = fixture(host = host).runtime

        assertEquals(ChannelInputResult.None, acceptedTarget(runtime).onInputReleased(RecordedPcm(shortArrayOf(1), 16_000)))
        assertEquals(1, host.enqueued.size)
        assertEquals(ChannelExecutionStatus.FAILED, runtime.snapshot.value.executionStatus)
    }

    @Test
    fun `SOS resets only this runtime conversation and close prevents a late reset`() = runTest {
        val host = AgentCapabilityHost()
        val fixture = fixture(host = host)

        fixture.runtime.handleSos()
        assertEquals(listOf(fixture.scope.identity), host.resetScopes)
        assertEquals(ChannelExecutionStatus.IDLE, fixture.runtime.snapshot.value.executionStatus)

        fixture.runtime.close()
        fixture.runtime.handleSos()
        assertEquals(listOf(fixture.scope.identity), host.resetScopes)
        assertEquals(
            ChannelPreparationAvailability.Unavailable(ChannelPreparationReason.RuntimeClosed),
            fixture.runtime.snapshot.value.preparation,
        )
    }

    private suspend fun acceptedTarget(runtime: OpenAiAgentRuntime) =
        (runtime.prepareInput() as? ChannelInputAcceptance.Accepted)?.target
            ?: throw AssertionError("Expected ready OpenAI Agent runtime")

    private fun fixture(
        host: AgentCapabilityHost,
        configuration: OpenAiAgentProviderConfiguration = configuration(),
    ): RuntimeFixture {
        val definition = ChannelDefinition(
            id = "agent-instance",
            name = "Agent instance",
            implementationId = BuiltInChannelImplementationIds.OPENAI_AGENT,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = OpenAiAgentProviderConfigurationCodec.encode(configuration),
        )
        val scope = RevocableChannelCapabilityScope(
            identity = CapabilityScopeIdentity(definition.id, RuntimeGeneration(7)),
            declaredCapabilities = setOf(
                ChannelCapability.Transcription,
                ChannelCapability.Synthesis,
                ChannelCapability.OpenAiModelDiscovery,
                ChannelCapability.OpenAiCompletion,
                ChannelCapability.AsynchronousConversation,
                ChannelCapability.DelayedPlayback,
                ChannelCapability.TextOutput,
            ),
            host = host,
        )
        return RuntimeFixture(OpenAiAgentRuntime(definition, configuration, scope), scope)
    }

    private fun configuration(
        keyboardEnabled: Boolean = false,
        keyboardProfileId: String? = null,
    ) = OpenAiAgentProviderConfiguration(
        connectionProfileId = "selected-profile",
        modelId = "selected-model",
        systemPrompt = "You are concise.",
        keyboardEnabled = keyboardEnabled,
        keyboardProfileId = keyboardProfileId,
    )

    private data class RuntimeFixture(
        val runtime: OpenAiAgentRuntime,
        val scope: RevocableChannelCapabilityScope,
    )

    private class AgentCapabilityHost(
        private val transcript: String = "spoken text",
        private val discovery: OpenAiModelDiscoveryOutcome = OpenAiModelDiscoveryOutcome.Available(
            OpenAiConnectionProfileId("selected-profile"),
            listOf(OpenAiModelChoice(OpenAiModelId("selected-model"), "Selected model")),
        ),
        private val textOutputAvailability: CapabilityAvailability = CapabilityAvailability.Available,
        private val admission: CapabilityOperationResult<AcceptedAgentRun> = CapabilityOperationResult.Success(
            AcceptedAgentRun(AgentRunId("run"), AgentMessageId("message"), AgentOperationId("operation")),
        ),
    ) : ChannelCapabilityHost {
        var transcriptionCount = 0
            private set
        val enqueued = mutableListOf<AgentConversationEnqueueRequest>()
        val resetScopes = mutableListOf<CapabilityScopeIdentity>()

        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = if (key == CapabilityKey.TextOutput) textOutputAvailability else CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = portFor(key)

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = portFor(key)

        @Suppress("UNCHECKED_CAST")
        private fun <T : ChannelCapabilityPort> portFor(key: CapabilityKey<T>): HostedCapabilityAcquisition<T> = when (key) {
            CapabilityKey.Transcription -> HostedCapabilityAcquisition.Available(
                object : TranscriptionCapability {
                    override suspend fun transcribe(
                        recording: dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording,
                    ): CapabilityOperationResult<Transcription> {
                        transcriptionCount += 1
                        return CapabilityOperationResult.Success(Transcription(transcript))
                    }
                },
                cleanup = { _: CapabilityLeaseTermination -> },
            ) as HostedCapabilityAcquisition<T>

            CapabilityKey.OpenAiModelDiscovery -> HostedCapabilityAcquisition.Available(
                object : OpenAiModelDiscoveryCapability {
                    override suspend fun discover(profileId: OpenAiConnectionProfileId): OpenAiModelDiscoveryOutcome = discovery
                },
                cleanup = { _: CapabilityLeaseTermination -> },
            ) as HostedCapabilityAcquisition<T>

            CapabilityKey.AsynchronousConversation -> HostedCapabilityAcquisition.Available(
                object : AsynchronousConversationCapability {
                    override suspend fun enqueue(
                        scope: CapabilityScopeIdentity,
                        request: AgentConversationEnqueueRequest,
                    ): CapabilityOperationResult<AcceptedAgentRun> {
                        enqueued += request
                        return admission
                    }

                    override suspend fun resetConversation(scope: CapabilityScopeIdentity) {
                        resetScopes += scope
                    }
                },
                cleanup = { _: CapabilityLeaseTermination -> },
            ) as HostedCapabilityAcquisition<T>

            else -> HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
        }
    }
}
