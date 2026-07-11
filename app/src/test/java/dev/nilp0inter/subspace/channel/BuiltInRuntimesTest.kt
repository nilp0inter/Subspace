package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.ChannelInputTarget
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelDefinition
import dev.nilp0inter.subspace.model.ChannelKind
import dev.nilp0inter.subspace.model.JournalConfig
import dev.nilp0inter.subspace.model.DebugConfig
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.KeyboardConfig
import io.sleepwalker.core.keymap.HostProfile
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import dev.nilp0inter.subspace.service.DebugRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltInRuntimesTest {

    @Test
    fun journalRuntimeChecksReadinessAndPreparesInput() = runTest {
        val tempDir = File.createTempFile("journal_test_dir", "").apply { delete(); mkdirs(); deleteOnExit() }
        val config = JournalConfig(tempDir.absolutePath, true, true)
        val def = ChannelDefinition("c1", "My Journal", ChannelKind.JOURNAL, true, 1, config)
        
        // Mock dependencies
        val fTranscriber = object : dev.nilp0inter.subspace.audio.PcmTranscriber {
            override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String = "decoded text"
        }
        val jc = JournalController(
            scope = this,
            encoder = object : dev.nilp0inter.subspace.audio.AudioEncoder {
                override suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int): Result<File> = Result.success(outputFile)
            },
            transcriber = fTranscriber
        )
        val runtime = JournalRuntime(
            parentScope = this,
            definition = def,
            journalControllerProvider = { jc },
            pathGenerator = JournalEntryPaths(),
            metadataStore = JournalMetadataStore()
        )

        assertTrue(runtime.snapshot.value.isReady)

        val acceptance = runtime.prepareInput()
        assertTrue(acceptance is ChannelInputAcceptance.Accepted)
        val target = (acceptance as ChannelInputAcceptance.Accepted).target

        val mockSession = object : ChannelAudioInputSession {
            override val frames = flowOf(shortArrayOf(1, 2, 3))
            override val sampleRate: Int = 16_000
        }

        // Start input
        target.onInputStarted(mockSession)
        assertEquals(ChannelExecutionStatus.RECORDING, runtime.snapshot.value.executionStatus)

        // Release input
        val recording = RecordedPcm(shortArrayOf(1, 2, 3), 16_000)
        val result = target.onInputReleased(recording)
        assertEquals(ChannelInputResult.None, result)
        assertEquals(ChannelExecutionStatus.IDLE, runtime.snapshot.value.executionStatus)

        runtime.close()
    }

    @Test
    fun debugRuntimeEchoCheck() = runTest {
        val config = DebugConfig(DebugMode.ECHO)
        val def = ChannelDefinition("c2", "Debug", ChannelKind.DEBUG, true, 1, config)

        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val echo = dev.nilp0inter.subspace.audio.EchoController(this, FakeSco(), captureService, source, FakeOutput())
        echo.setEnabled(true)

        val runtime = DebugRuntime(
            definition = def,
            echoController = echo,
            sttControllerProvider = { null },
            ttsControllerProvider = { null },
            sttTtsControllerProvider = { null },
            modelDirProvider = { null },
            monitorStateProvider = { dev.nilp0inter.subspace.model.MonitorState() }
        )

        assertTrue(runtime.snapshot.value.isReady)

        val acceptance = runtime.prepareInput()
        assertTrue(acceptance is ChannelInputAcceptance.Accepted)
        val target = (acceptance as ChannelInputAcceptance.Accepted).target

        val mockSession = object : ChannelAudioInputSession {
            override val frames = flowOf(shortArrayOf(1, 2, 3))
            override val sampleRate: Int = 16_000
        }
        target.onInputStarted(mockSession)
        assertEquals(ChannelExecutionStatus.RECORDING, runtime.snapshot.value.executionStatus)

        target.onInputCancelled("test")
        assertEquals(ChannelExecutionStatus.IDLE, runtime.snapshot.value.executionStatus)
    }

    @Test
    fun debugRuntimeEchoReadyWhenControllerInactive() = runTest {
        val config = DebugConfig(DebugMode.ECHO)
        val def = ChannelDefinition("c2", "Debug", ChannelKind.DEBUG, true, 1, config)

        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val echo = dev.nilp0inter.subspace.audio.EchoController(this, FakeSco(), captureService, source, FakeOutput())
        // Controller remains inactive (enabled = false): an available-but-inactive ECHO runtime
        // must still report readiness from dependency availability, not activation state.

        val runtime = DebugRuntime(
            definition = def,
            echoController = echo,
            sttControllerProvider = { null },
            ttsControllerProvider = { null },
            sttTtsControllerProvider = { null },
            modelDirProvider = { null },
            monitorStateProvider = { dev.nilp0inter.subspace.model.MonitorState() }
        )

        assertFalse(echo.enabled)
        assertTrue(runtime.snapshot.value.isReady)
    }

    @Test
    fun keyboardRuntimeChecksReadinessRefusal() = runTest {
        val config = KeyboardConfig(HostProfile.LINUX_US)
        val def = ChannelDefinition("c3", "Keyboard", ChannelKind.KEYBOARD, true, 1, config)

        val bridgeConnected = false
        val runtime = KeyboardRuntime(
            parentScope = this,
            definition = def,
            controllerProvider = { null },
            bridgeConnectedFlow = MutableStateFlow(bridgeConnected)
        )

        // Initial connection state disconnected -> not ready
        assertFalse(runtime.snapshot.value.isReady)

        val acceptance = runtime.prepareInput()
        assertTrue(acceptance is ChannelInputAcceptance.Refused)

        runtime.close()
    }

    private class FakeSco : ScoRoute {
        override val state: StateFlow<ScoState> = MutableStateFlow(ScoState.Inactive)
        override fun hasAvailableScoDevice(): Boolean = true
        override suspend fun acquire(): Boolean = true
        override fun isActive(): Boolean = false
        override fun release() {}
    }

    private class FakeOutput : PcmOutput {
        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun playReadyBeep(coldStart: Boolean) {}
        override suspend fun play(recording: RecordedPcm) {}
    }
}
