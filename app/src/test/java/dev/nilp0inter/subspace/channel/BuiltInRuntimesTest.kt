package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.bluetooth.SleepwalkerConnectionResult
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.AgentOperationContext
import dev.nilp0inter.subspace.channel.capability.AudioOperationArtifact
import dev.nilp0inter.subspace.channel.capability.AudioOperationCapability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityLeaseTermination
import dev.nilp0inter.subspace.channel.capability.DeferredAudioPlaybackCapability
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioOperation
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityUnavailableReason
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.RevocableChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.TextDeliveryOutcome
import dev.nilp0inter.subspace.channel.capability.TextKeyRequest
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.TextOutputKey
import dev.nilp0inter.subspace.channel.capability.TextOutputProfile
import dev.nilp0inter.subspace.channel.capability.TextOutputRequest
import dev.nilp0inter.subspace.channel.capability.Transcription
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.channel.capability.SpeechSynthesisRequest
import dev.nilp0inter.subspace.channel.capability.SynthesisCapability
import dev.nilp0inter.subspace.channel.capability.SynthesizedAudioArtifact
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import dev.nilp0inter.subspace.model.KeyboardProviderConfiguration
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.DebugProviderConfiguration
import dev.nilp0inter.subspace.model.DebugProviderConfigurationCodec
import dev.nilp0inter.subspace.model.DelayedPlaybackOperationId
import dev.nilp0inter.subspace.model.DelayedPlaybackOutcome
import dev.nilp0inter.subspace.model.KeyboardProviderConfigurationCodec
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelPreparationAvailability
import dev.nilp0inter.subspace.service.ChannelPreparationReason
import dev.nilp0inter.subspace.service.DebugRuntime
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import dev.nilp0inter.subspace.channel.KeyboardRuntime
import dev.nilp0inter.subspace.channel.SleepwalkerTextOutputService
import dev.nilp0inter.subspace.channel.TextOutputAvailability
import dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording
import dev.nilp0inter.subspace.channel.capability.OpaqueSynthesizedAudio
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltInRuntimesTest {
    @Test
    fun keyboardInstancesKeepStatusAndConfiguredProfilesIndependent() = runTest {
        val host = RuntimeCapabilityHost(transcript = "captured text")
        val alpha = keyboardRuntime("alpha", "linux:us", host)
        val bravo = keyboardRuntime("bravo", "windows:us", host)

        val alphaTarget = (alpha.runtime.prepareInput() as ChannelInputAcceptance.Accepted).target
        alphaTarget.onInputStarted(EmptySession)

        assertEquals(ChannelExecutionStatus.RECORDING, alpha.runtime.snapshot.value.executionStatus)
        assertEquals(ChannelExecutionStatus.IDLE, bravo.runtime.snapshot.value.executionStatus)

        alphaTarget.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000))

        assertEquals(ChannelExecutionStatus.SUCCESS, alpha.runtime.snapshot.value.executionStatus)
        assertEquals(ChannelExecutionStatus.IDLE, bravo.runtime.snapshot.value.executionStatus)
        assertEquals(
            TextOutputRequest("captured text ", TextOutputProfile("linux:us")),
            host.textRequests.single(),
        )
    }

    @Test
    fun keyboardRuntimeAppendsTrailingSpaceAfterTranscriptionWithoutTrailingSpace() = runTest {
        val host = RuntimeCapabilityHost(transcript = "captured text")
        val runtime = keyboardRuntime("append-space", "linux:us", host)
        val target = (runtime.runtime.prepareInput() as ChannelInputAcceptance.Accepted).target

        target.onInputStarted(EmptySession)
        target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000))

        assertEquals(
            TextOutputRequest("captured text ", TextOutputProfile("linux:us")),
            host.textRequests.single(),
        )
    }

    @Test
    fun keyboardRuntimePreservesTrailingSpaceAfterTranscriptionWithTrailingSpace() = runTest {
        val host = RuntimeCapabilityHost(transcript = "captured text ")
        val runtime = keyboardRuntime("append-space-existing", "linux:us", host)
        val target = (runtime.runtime.prepareInput() as ChannelInputAcceptance.Accepted).target

        target.onInputStarted(EmptySession)
        target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000))

        assertEquals(
            TextOutputRequest("captured text ", TextOutputProfile("linux:us")),
            host.textRequests.single(),
        )
    }

    @Test
    fun keyboardReadinessUsesSemanticRecoverablePreparationAndNeverRequiresTransportState() = runTest {
        val host = RuntimeCapabilityHost(transcript = "unused", textInitiallyRecoverable = true)
        val runtime = keyboardRuntime(
            id = "recoverable",
            profile = "linux:us",
            host = host,
            initialPreparation = ChannelPreparationAvailability.Recoverable(ChannelPreparationReason.RuntimeBusy),
        )

        assertTrue(runtime.runtime.snapshot.value.preparation is ChannelPreparationAvailability.Recoverable)
        assertTrue(runtime.runtime.prepareInput() is ChannelInputAcceptance.Accepted)
        assertEquals(ChannelPreparationAvailability.Available, runtime.runtime.snapshot.value.preparation)
        assertEquals(1, host.preparedTextAcquisitions)
    }

    @Test
    fun runtimeCloseRejectsLateInputWithoutClosingTheSharedTextOutputService() = runTest {
        val connection = CloseCountingConnection().apply { setState(KeyboardConnectionState.Connected) }
        val service = SleepwalkerTextOutputService(
            scope = backgroundScope,
            connection = connection,
            hid = LowLevelHidImpl(),
            keymapDatabase = SeedKeymapDatabase,
            connect = { SleepwalkerConnectionResult.Connected },
        )
        runCurrent()
        val host = RuntimeCapabilityHost(
            transcript = "should not be sent",
            textOutput = service.capabilityFor("retired"),
        )
        val runtime = keyboardRuntime("retired", "linux:us", host)
        val target = (runtime.runtime.prepareInput() as ChannelInputAcceptance.Accepted).target

        runtime.runtime.close()
        runtime.scope.revoke()
        target.onInputReleased(RecordedPcm(shortArrayOf(1), 16_000))

        assertTrue(runtime.runtime.prepareInput() is ChannelInputAcceptance.Unavailable)
        assertTrue(host.textRequests.isEmpty())
        assertEquals(TextOutputAvailability.Available, service.availability.value)
        assertEquals(0, connection.disconnectCount)
    }

    @Test
    fun sosUsesTheReceivingRuntimeProfileAndDoesNotAddressSiblingInstances() = runTest {
        val host = RuntimeCapabilityHost(transcript = "unused")
        val alpha = keyboardRuntime("alpha", "linux:us", host)
        val bravo = keyboardRuntime("bravo", "windows:us", host)

        bravo.runtime.handleSos()

        assertTrue(alpha.runtime.snapshot.value.executionStatus != ChannelExecutionStatus.FAILED)
        assertEquals(
            listOf(TextKeyRequest(TextOutputKey.ENTER, TextOutputProfile("windows:us"))),
            host.keyRequests,
        )
    }

    @Test
    fun debugEchoPreservesRawAudioOperationAndSchedulesDeferredPlayback() = runTest {
        val operation = AudioOperationArtifact(
            recording = RecordedPcm(shortArrayOf(27, -27), 16_000),
            operationId = "echo-operation",
        )
        val deferred = RecordingDeferredAudioPlayback()
        val host = RuntimeCapabilityHost(
            transcript = "unused",
            audioOperation = RecordingAudioOperation(operation),
            deferredAudioPlayback = deferred,
        )
        val runtime = debugRuntime(DebugMode.ECHO, host)
        val target = (runtime.prepareInput() as ChannelInputAcceptance.Accepted).target

        val result = target.onInputReleased(RecordedPcm(shortArrayOf(1, 2), 16_000))

        assertEquals(ChannelInputResult.None, result)
        assertEquals(1, deferred.requests.size)
        val scheduled = deferred.requests.single()
        assertSame(operation, scheduled.audio)
        assertEquals("debug-ECHO", scheduled.context.scope.channelInstanceId)
    }

    @Test
    fun debugDelayedEchoPreservesRawAudioOperationAndForwardsEligibilityDelay() = runTest {
        val operation = AudioOperationArtifact(
            recording = RecordedPcm(shortArrayOf(27, -27), 16_000),
            operationId = "delayed-echo-operation",
        )
        val deferred = RecordingDeferredAudioPlayback()
        val host = RuntimeCapabilityHost(
            transcript = "unused",
            audioOperation = RecordingAudioOperation(operation),
            deferredAudioPlayback = deferred,
        )
        val runtime = debugRuntime(DebugMode.DELAYED_ECHO, host)
        val target = (runtime.prepareInput() as ChannelInputAcceptance.Accepted).target

        val result = target.onInputReleased(RecordedPcm(shortArrayOf(1, 2), 16_000))

        assertEquals(ChannelInputResult.None, result)
        assertEquals(1, deferred.requests.size)
        val scheduled = deferred.requests.single()
        assertSame(operation, scheduled.audio)
        assertEquals(5_000L, scheduled.eligibilityDelayMillis)
        assertEquals("debug-DELAYED_ECHO", scheduled.context.scope.channelInstanceId)
    }

    @Test
    fun debugTtsUsesSupertonicLanguageAndSchedulesDeferredPlayback() = runTest {
        assertDebugModeSchedulesDeferredPlayback(DebugMode.TTS)
    }

    @Test
    fun debugSttTtsUsesSupertonicLanguageAndSchedulesDeferredPlayback() = runTest {
        assertDebugModeSchedulesDeferredPlayback(DebugMode.STT_TTS)
    }

    private suspend fun assertDebugModeSchedulesDeferredPlayback(mode: DebugMode) {
        val operation = AudioOperationArtifact(
            recording = RecordedPcm(shortArrayOf(27, -27), 16_000),
            operationId = "deferred-${mode.name}",
        )
        val synthesis = RecordingSynthesis()
        val deferred = RecordingDeferredAudioPlayback()
        val host = RuntimeCapabilityHost(
            transcript = "captured transcript",
            synthesis = synthesis,
            audioOperation = RecordingAudioOperation(operation),
            deferredAudioPlayback = deferred,
        )
        val runtime = debugRuntime(mode, host)
        val target = (runtime.prepareInput() as ChannelInputAcceptance.Accepted).target

        val result = target.onInputReleased(RecordedPcm(shortArrayOf(1, 2), 16_000))

        assertEquals(ChannelInputResult.None, result)
        assertEquals(1, deferred.requests.size)
        assertSame(operation, deferred.requests.single().audio)
        assertEquals(listOf("en"), synthesis.requests.map(SpeechSynthesisRequest::languageTag))
    }

    private fun debugRuntime(mode: DebugMode, host: RuntimeCapabilityHost): DebugRuntime {
        val definition = ChannelDefinition(
            id = "debug-${mode.name}",
            name = "Debug ${mode.name}",
            implementationId = BuiltInChannelImplementationIds.DEBUG,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = DebugProviderConfigurationCodec.encode(DebugProviderConfiguration(mode)),
        )
        return DebugRuntime(
            definition = definition,
            configuration = DebugProviderConfiguration(mode),
            capabilities = RevocableChannelCapabilityScope(
                identity = CapabilityScopeIdentity(definition.id, RuntimeGeneration(0)),
                declaredCapabilities = setOf(
                    ChannelCapability.Transcription,
                    ChannelCapability.Synthesis,
                    ChannelCapability.AudioOperation,
                    ChannelCapability.DeferredAudioPlayback,
                ),
                host = host,
            ),
            initialPreparation = ChannelPreparationAvailability.Available,
        )
    }

    private fun keyboardRuntime(
        id: String,
        profile: String,
        host: RuntimeCapabilityHost,
        initialPreparation: ChannelPreparationAvailability = ChannelPreparationAvailability.Available,
    ): RuntimeFixture {
        val definition = ChannelDefinition(
            id = id,
            name = "Keyboard $id",
            implementationId = BuiltInChannelImplementationIds.KEYBOARD,
            enabled = true,
            configSchemaVersion = 1,
            configPayload = KeyboardProviderConfigurationCodec.encode(KeyboardProviderConfiguration(profile)),
        )
        val scope = RevocableChannelCapabilityScope(
            identity = CapabilityScopeIdentity(definition.id, RuntimeGeneration(0)),
            declaredCapabilities = setOf(
                ChannelCapability.Transcription,
                ChannelCapability.TextOutput,
            ),
            host = host,
        )
        return RuntimeFixture(
            runtime = KeyboardRuntime(
                definition = definition,
                configuration = KeyboardProviderConfiguration(profile),
                profile = TextOutputProfile(profile),
                capabilities = scope,
                initialPreparation = initialPreparation,
            ),
            scope = scope,
        )
    }

    private data class RuntimeFixture(
        val runtime: KeyboardRuntime,
        val scope: RevocableChannelCapabilityScope,
    )

    private class RuntimeCapabilityHost(
        private val transcript: String,
        private val textOutput: TextOutputCapability = RecordingTextOutput(),
        private val synthesis: SynthesisCapability? = null,
        private val audioOperation: AudioOperationCapability? = null,
        private val deferredAudioPlayback: DeferredAudioPlaybackCapability? = null,
        private val textInitiallyRecoverable: Boolean = false,
    ) : ChannelCapabilityHost {
        val textRequests: List<TextOutputRequest>
            get() = (textOutput as? RecordingTextOutput)?.textRequests ?: emptyList()
        val keyRequests: List<TextKeyRequest>
            get() = (textOutput as? RecordingTextOutput)?.keyRequests ?: emptyList()
        var preparedTextAcquisitions = 0
            private set

        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

        override suspend fun <T : ChannelCapabilityPort> acquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = if (key == CapabilityKey.TextOutput && textInitiallyRecoverable) {
            HostedCapabilityAcquisition.Recoverable(CapabilityUnavailableReason.HOST_NOT_READY)
        } else {
            availableFor(key, prepared = false)
        }

        override suspend fun <T : ChannelCapabilityPort> prepareAndAcquire(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<T>,
            timeoutMillis: Long,
        ): HostedCapabilityAcquisition<T> = availableFor(key, prepared = true)

        @Suppress("UNCHECKED_CAST")
        private fun <T : ChannelCapabilityPort> availableFor(
            key: CapabilityKey<T>,
            prepared: Boolean,
        ): HostedCapabilityAcquisition<T> = when (key) {
            CapabilityKey.TextOutput -> {
                if (prepared) preparedTextAcquisitions += 1
                HostedCapabilityAcquisition.Available(textOutput) { _: CapabilityLeaseTermination -> } as HostedCapabilityAcquisition<T>
            }
            CapabilityKey.Transcription ->
                HostedCapabilityAcquisition.Available(RecordingTranscription(transcript)) { _: CapabilityLeaseTermination -> }
                    as HostedCapabilityAcquisition<T>
            CapabilityKey.Synthesis -> synthesis?.let {
                HostedCapabilityAcquisition.Available(it) { _: CapabilityLeaseTermination -> }
                    as HostedCapabilityAcquisition<T>
            } ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
            CapabilityKey.AudioOperation -> audioOperation?.let {
                HostedCapabilityAcquisition.Available(it) { _: CapabilityLeaseTermination -> }
                    as HostedCapabilityAcquisition<T>
            } ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
            CapabilityKey.DeferredAudioPlayback -> deferredAudioPlayback?.let {
                HostedCapabilityAcquisition.Available(it) { _: CapabilityLeaseTermination -> }
                    as HostedCapabilityAcquisition<T>
            } ?: HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
            else -> HostedCapabilityAcquisition.Unavailable(CapabilityUnavailableReason.UNSUPPORTED)
        }
    }

    private class RecordingTextOutput : TextOutputCapability {
        val textRequests = mutableListOf<TextOutputRequest>()
        val keyRequests = mutableListOf<TextKeyRequest>()

        override suspend fun sendText(request: TextOutputRequest): TextDeliveryOutcome {
            textRequests += request
            return TextDeliveryOutcome.Delivered("text-${textRequests.size}")
        }

        override suspend fun sendKey(request: TextKeyRequest): TextDeliveryOutcome {
            keyRequests += request
            return TextDeliveryOutcome.Delivered("key-${keyRequests.size}")
        }
    }

    private class RecordingTranscription(private val transcript: String) : TranscriptionCapability {
        override suspend fun transcribe(
            recording: OpaqueAudioRecording,
        ): CapabilityOperationResult<Transcription> = CapabilityOperationResult.Success(Transcription(transcript))
    }

    private class RecordingSynthesis : SynthesisCapability {
        val requests = mutableListOf<SpeechSynthesisRequest>()

        override suspend fun synthesize(
            request: SpeechSynthesisRequest,
        ): CapabilityOperationResult<dev.nilp0inter.subspace.channel.capability.OpaqueSynthesizedAudio> {
            requests += request
            return CapabilityOperationResult.Success(SynthesizedAudioArtifact(floatArrayOf(0.5f, -0.5f)))
        }
    }

    private class RecordingAudioOperation(
        private val operation: AudioOperationArtifact,
    ) : AudioOperationCapability {
        override suspend fun createPlaybackResult(
            audio: OpaqueSynthesizedAudio,
        ): CapabilityOperationResult<OpaqueAudioOperation> =
            CapabilityOperationResult.Success(operation)

        override suspend fun createPlaybackResult(
            recording: OpaqueAudioRecording,
        ): CapabilityOperationResult<OpaqueAudioOperation> =
            CapabilityOperationResult.Success(operation)
    }

    private class RecordingDeferredAudioPlayback : DeferredAudioPlaybackCapability {
        data class ScheduledRequest(
            val context: AgentOperationContext,
            val audio: OpaqueAudioOperation,
            val eligibilityDelayMillis: Long,
        )

        val requests = mutableListOf<ScheduledRequest>()

        override suspend fun scheduleAudio(
            context: AgentOperationContext,
            audio: OpaqueAudioOperation,
            eligibilityDelayMillis: Long,
        ): DelayedPlaybackOutcome {
            requests += ScheduledRequest(context, audio, eligibilityDelayMillis)
            return DelayedPlaybackOutcome.Pending(DelayedPlaybackOperationId("deferred-${requests.size}"))
        }
    }

    private object EmptySession : ChannelAudioInputSession {
        override val frames: Flow<ShortArray> = emptyFlow()
        override val sampleRate: Int = 16_000
    }

    private class CloseCountingConnection : SleepwalkerBleConnection() {
        var disconnectCount = 0

        fun setState(state: KeyboardConnectionState) {
            _connectionState.value = state
        }

        override fun disconnect() {
            disconnectCount += 1
            _connectionState.value = KeyboardConnectionState.Disconnected
        }

        override suspend fun sendOp(op: LowLevelOp) = error("closed runtime must not emit transport operations")
    }
}
