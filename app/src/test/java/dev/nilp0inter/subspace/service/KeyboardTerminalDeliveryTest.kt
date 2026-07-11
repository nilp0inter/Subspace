package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.AudioRouteEndpoint
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.NoopScoRoute
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.channel.KeyboardRuntime
import dev.nilp0inter.subspace.channel.SleepwalkerTextOutputService
import dev.nilp0inter.subspace.channel.capability.CapabilityAvailability
import dev.nilp0inter.subspace.channel.capability.CapabilityKey
import dev.nilp0inter.subspace.channel.capability.CapabilityLeaseTermination
import dev.nilp0inter.subspace.channel.capability.CapabilityOperationResult
import dev.nilp0inter.subspace.channel.capability.CapabilityScopeIdentity
import dev.nilp0inter.subspace.channel.capability.ChannelCapability
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityHost
import dev.nilp0inter.subspace.channel.capability.ChannelCapabilityPort
import dev.nilp0inter.subspace.channel.capability.HostedCapabilityAcquisition
import dev.nilp0inter.subspace.channel.capability.RevocableChannelCapabilityScope
import dev.nilp0inter.subspace.channel.capability.RuntimeGeneration
import dev.nilp0inter.subspace.channel.capability.TextOutputCapability
import dev.nilp0inter.subspace.channel.capability.Transcription
import dev.nilp0inter.subspace.channel.capability.TranscriptionCapability
import dev.nilp0inter.subspace.model.BuiltInChannelImplementationIds
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.InputMode
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import dev.nilp0inter.subspace.model.KeyboardProviderConfiguration
import dev.nilp0inter.subspace.model.KeyboardProviderConfigurationCodec
import dev.nilp0inter.subspace.model.PttSource
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.Opcodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTerminalDeliveryTest {
    @Test
    fun slowKeyboardTranscriptionAndAcknowledgedHidDeliveryFinishBeforeTerminalCleanup() = runTest {
        val events = mutableListOf<String>()
        val connection = AcknowledgingConnection(events).apply { setConnected() }
        val textOutput = SleepwalkerTextOutputService(
            scope = backgroundScope,
            connection = connection,
            hid = LowLevelHidImpl(),
            keymapDatabase = SeedKeymapDatabase,
            connect = { error("connected delivery must not reconnect") },
            preparationTimeoutMs = 9_000,
            deliveryTimeoutMs = 9_000,
        )
        val capabilities = RevocableChannelCapabilityScope(
            identity = CapabilityScopeIdentity("keyboard", RuntimeGeneration(0)),
            declaredCapabilities = setOf(ChannelCapability.Transcription, ChannelCapability.TextOutput),
            host = KeyboardCapabilityHost(
                transcriber = DelayedTranscriber(events),
                textOutput = textOutput.capabilityFor("keyboard"),
                events = events,
            ),
        )
        val keyboard = KeyboardRuntime(
            definition = keyboardDefinition(),
            configuration = KeyboardProviderConfiguration("linux:us"),
            profile = dev.nilp0inter.subspace.channel.capability.TextOutputProfile("linux:us"),
            capabilities = capabilities,
            initialPreparation = ChannelPreparationAvailability.Available,
        )
        val output = TerminalOutput(events)
        val manager = PttAudioSessionManager(
            scope = this,
            captureService = CaptureServiceFakes.newService(this),
            channelRouter = object : ChannelRouter {
                override suspend fun prepareInput(channelId: String): ChannelInputAcceptance = keyboard.prepareInput()
            },
            resolvePttAudioRoute = {
                ResolvedAudioRoute(
                    sco = NoopScoRoute(),
                    output = output,
                    source = CaptureServiceFakes.singleShotSource(shortArrayOf(1)),
                    endpoint = AudioRouteEndpoint.Local,
                )
            },
            onTerminalCompleted = { events += "completion" },
            targetReleaseTimeoutMillis = 9_000,
        )

        assertTrue(manager.start(PttSource.Phone, "keyboard", InputMode.OnAPinch))
        runCurrent()
        val releaseTime = testScheduler.currentTime
        manager.release(PttSource.Phone)
        runCurrent()

        assertEquals(listOf("transcription-started"), events)
        advanceTimeBy(releaseTime + 5_001 - testScheduler.currentTime)
        runCurrent()
        assertEquals(listOf("transcription-started"), events)
        assertEquals(0, output.routeReleaseCount)
        assertTrue(manager.activeSession != null)

        advanceTimeBy(releaseTime + 7_000 - testScheduler.currentTime)
        runCurrent()
        assertTrue(connection.acknowledgementRequested.isCompleted)
        assertEquals(
            listOf(
                "transcription-started",
                "transcription-returned",
                "arm",
                "typed-text",
                "acknowledgement-requested",
            ),
            events,
        )
        assertEquals(0, output.routeReleaseCount)
        advanceTimeBy(releaseTime + 8_999 - testScheduler.currentTime)
        runCurrent()
        assertEquals(0, output.routeReleaseCount)
        assertTrue(manager.activeSession != null)

        connection.acknowledgement.complete(true)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "transcription-started",
                "transcription-returned",
                "arm",
                "typed-text",
                "acknowledgement-requested",
                "disarm",
                "delivery-lease-released",
                "route-release",
                "completion",
            ),
            events,
        )
        assertEquals(ChannelExecutionStatus.SUCCESS, keyboard.snapshot.value.executionStatus)
        assertTrue(connection.sent.any { it.opcode != Opcodes.ARM && it.opcode != Opcodes.DISARM })
        assertFalse(connection.sent.any { it.opcode == Opcodes.KILL })
        assertEquals(1, output.routeReleaseCount)
        assertEquals(null, manager.activeSession)
    }

    private fun keyboardDefinition(): ChannelDefinition = ChannelDefinition(
        id = "keyboard",
        name = "Keyboard",
        implementationId = BuiltInChannelImplementationIds.KEYBOARD,
        enabled = true,
        configSchemaVersion = 1,
        configPayload = KeyboardProviderConfigurationCodec.encode(KeyboardProviderConfiguration("linux:us")),
    )

    private class DelayedTranscriber(
        private val events: MutableList<String>,
    ) : TranscriptionCapability {
        override suspend fun transcribe(
            recording: dev.nilp0inter.subspace.channel.capability.OpaqueAudioRecording,
        ): CapabilityOperationResult<Transcription> {
            events += "transcription-started"
            delay(6_000)
            events += "transcription-returned"
            return CapabilityOperationResult.Success(Transcription("a"))
        }
    }
    private class KeyboardCapabilityHost(
        private val transcriber: TranscriptionCapability,
        private val textOutput: TextOutputCapability,
        private val events: MutableList<String>,
    ) : ChannelCapabilityHost {
        private var textOutputAcquisitions = 0
        override suspend fun availability(
            identity: CapabilityScopeIdentity,
            key: CapabilityKey<*>,
        ): CapabilityAvailability = CapabilityAvailability.Available

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
        private fun <T : ChannelCapabilityPort> portFor(
            key: CapabilityKey<T>,
        ): HostedCapabilityAcquisition<T> = when (key) {
            CapabilityKey.Transcription -> HostedCapabilityAcquisition.Available(transcriber) {
                _: CapabilityLeaseTermination ->
            } as HostedCapabilityAcquisition<T>
            CapabilityKey.TextOutput -> {
                textOutputAcquisitions += 1
                HostedCapabilityAcquisition.Available(textOutput) { _: CapabilityLeaseTermination ->
                    if (textOutputAcquisitions > 1) events += "delivery-lease-released"
                } as HostedCapabilityAcquisition<T>
            }
            else -> error("Unexpected capability $key")
        }
    }

    private class TerminalOutput(
        private val events: MutableList<String>,
    ) : PcmOutput {
        var routeReleaseCount = 0
            private set

        override suspend fun playReadyBeep(coldStart: Boolean) = Unit

        override suspend fun playErrorBeep(coldStart: Boolean) = Unit

        override suspend fun play(recording: RecordedPcm) = Unit

        override suspend fun releaseRoute() {
            routeReleaseCount += 1
            events += "route-release"
        }
    }

    private class AcknowledgingConnection(
        private val events: MutableList<String>,
    ) : SleepwalkerBleConnection() {
        val acknowledgementRequested = CompletableDeferred<Unit>()
        val acknowledgement = CompletableDeferred<Boolean>()
        val sent = mutableListOf<LowLevelOp>()
        private var typedTextRecorded = false

        fun setConnected() {
            _connectionState.value = KeyboardConnectionState.Connected
        }

        override suspend fun sendOp(op: LowLevelOp) {
            sent += op
            when (op.opcode) {
                Opcodes.ARM -> events += "arm"
                Opcodes.DISARM -> events += "disarm"
                else -> if (!typedTextRecorded) {
                    typedTextRecorded = true
                    events += "typed-text"
                }
            }
        }

        override suspend fun awaitAck(seqId: Int, timeoutMs: Long): Boolean {
            events += "acknowledgement-requested"
            acknowledgementRequested.complete(Unit)
            return acknowledgement.await()
        }
    }
}
