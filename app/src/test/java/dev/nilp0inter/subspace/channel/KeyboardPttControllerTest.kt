package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.*
import dev.nilp0inter.subspace.bluetooth.FakeSleepwalkerBleConnection
import dev.nilp0inter.subspace.bluetooth.SleepwalkerBleConnection
import dev.nilp0inter.subspace.model.KeyboardConnectionState
import dev.nilp0inter.subspace.model.KeyboardStatus
import dev.nilp0inter.subspace.model.ScoState
import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.hid.toFrameBytes
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.text.TapScriptCompiler
import io.sleepwalker.core.text.TextPlanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardPttControllerTest {

    private fun makeController(
        scope: kotlinx.coroutines.CoroutineScope,
        sco: ScoRoute,
        captureService: CaptureService,
        source: CaptureSource,
        output: PcmOutput,
        transcriptionService: PcmTranscriber,
        connection: SleepwalkerBleConnection,
        hid: LowLevelHid,
    ): KeyboardPttController = KeyboardPttController(
        scope = scope,
        sco = sco,
        captureService = captureService,
        source = source,
        output = output,
        transcriptionService = transcriptionService,
        connection = connection,
        hid = hid,
        keymapDatabase = SeedKeymapDatabase,
        hostProfileProvider = { HostProfile.LINUX_US }
    )

    @Test
    fun successfulCycle() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val output = FakeOutput()
        val transcriber = FakePcmTranscriber().apply {
            outcome = Result.success("hello world")
        }
        val connection = FakeSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Connected)
        }
        val hid = LowLevelHidImpl()
        val controller = makeController(
            scope = this,
            sco = sco,
            captureService = captureService,
            source = source,
            output = output,
            transcriptionService = transcriber,
            connection = connection,
            hid = hid
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        assertEquals(KeyboardStatus.Recording, controller.status.value)

        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(KeyboardStatus.Done("hello world"), controller.status.value)
        assertEquals(1, transcriber.callCount)
        assertEquals(1, output.releaseRouteCount)

        // Verify HID ops were sent in order: arm, typed ops, disarm
        val sent = connection.sentOps
        assertTrue("Expected at least arm and disarm ops", sent.size >= 2)
        assertEquals(io.sleepwalker.core.protocol.Opcodes.ARM, sent.first().opcode)
        assertEquals(io.sleepwalker.core.protocol.Opcodes.DISARM, sent.last().opcode)
        val expectedPlan = TextPlanner(hid = hid).plan("hello world ", HostProfile.LINUX_US).plan!!
        val expectedCompiled = TapScriptCompiler.compile(expectedPlan, hid)
        val typedOps = sent.subList(1, sent.size - 1)
        assertEquals(expectedCompiled.size, typedOps.size)
        expectedCompiled.forEachIndexed { index, expectedOp ->
            assertEquals(expectedOp.opcode, typedOps[index].opcode)
            assertTrue(expectedOp.payload.contentEquals(typedOps[index].payload))
        }
    }

    @Test
    fun emptyAudioReportsEmptyAudioStatus() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.emptySource()
        val output = FakeOutput()
        val transcriber = FakePcmTranscriber()
        val connection = FakeSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Connected)
        }
        val hid = LowLevelHidImpl()
        val controller = makeController(
            scope = this,
            sco = sco,
            captureService = captureService,
            source = source,
            output = output,
            transcriptionService = transcriber,
            connection = connection,
            hid = hid
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(KeyboardStatus.EmptyAudio, controller.status.value)
        assertEquals(0, transcriber.callCount)
        assertEquals(0, connection.sentOps.size)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun modelNotReadyReportsError() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val output = FakeOutput()
        val transcriber = FakePcmTranscriber().apply {
            outcome = Result.failure(TranscriptionException.ModelNotReady)
        }
        val connection = FakeSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Connected)
        }
        val hid = LowLevelHidImpl()
        val controller = makeController(
            scope = this,
            sco = sco,
            captureService = captureService,
            source = source,
            output = output,
            transcriptionService = transcriber,
            connection = connection,
            hid = hid
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(KeyboardStatus.Error("STT model not ready"), controller.status.value)
        assertEquals(0, connection.sentOps.size)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun transcriptionFailureReportsError() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val output = FakeOutput()
        val transcriber = FakePcmTranscriber().apply {
            outcome = Result.failure(TranscriptionException.Failed("inference failed"))
        }
        val connection = FakeSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Connected)
        }
        val hid = LowLevelHidImpl()
        val controller = makeController(
            scope = this,
            sco = sco,
            captureService = captureService,
            source = source,
            output = output,
            transcriptionService = transcriber,
            connection = connection,
            hid = hid
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(KeyboardStatus.Error("inference failed"), controller.status.value)
        assertEquals(0, connection.sentOps.size)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun unrepresentableGlyphReportsError() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val output = FakeOutput()
        val transcriber = FakePcmTranscriber().apply {
            outcome = Result.success("hello £")
        }
        val connection = FakeSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Connected)
        }
        val hid = LowLevelHidImpl()
        val controller = makeController(
            scope = this,
            sco = sco,
            captureService = captureService,
            source = source,
            output = output,
            transcriptionService = transcriber,
            connection = connection,
            hid = hid
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            KeyboardStatus.Error("Unrepresentable character '£' for profile linux:us"),
            controller.status.value
        )
        assertEquals(0, connection.sentOps.size)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun errorDuringTypingSendsKill() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val output = FakeOutput()
        val transcriber = FakePcmTranscriber().apply {
            outcome = Result.success("hello")
        }
        val connection = ThrowingSleepwalkerBleConnection().apply {
            setConnectionState(KeyboardConnectionState.Connected)
            throwAfterOpsCount = 1 // 1st op (ARM) succeeds, 2nd op (first typed op) throws
        }
        val hid = LowLevelHidImpl()
        val controller = makeController(
            scope = this,
            sco = sco,
            captureService = captureService,
            source = source,
            output = output,
            transcriptionService = transcriber,
            connection = connection,
            hid = hid
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        // Assert status is Error
        assertTrue(controller.status.value is KeyboardStatus.Error)
        assertEquals("Fake connection send failed", (controller.status.value as KeyboardStatus.Error).reason)

        // Verify sent ops contains ARM and KILL
        val sent = connection.sentOps
        assertTrue(sent.size >= 2)
        assertEquals(io.sleepwalker.core.protocol.Opcodes.ARM, sent.first().opcode)
        assertEquals(io.sleepwalker.core.protocol.Opcodes.KILL, sent.last().opcode)

        // Verify route released
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun cancelAndReleaseCancelsInFlightTypingAndSendsKill() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3))
        val output = FakeOutput()
        val transcriber = FakePcmTranscriber().apply {
            outcome = Result.success("hello")
        }
        val connection = DelayingSleepwalkerBleConnection(delayMs = 1000).apply {
            setConnectionState(KeyboardConnectionState.Connected)
        }
        val hid = LowLevelHidImpl()
        val controller = makeController(
            scope = this,
            sco = sco,
            captureService = captureService,
            source = source,
            output = output,
            transcriptionService = transcriber,
            connection = connection,
            hid = hid
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        
        // Advance time to start typing job and get into the delayed sendOp
        advanceTimeBy(100) // This should trigger finishSession, transcription, and startTyping
        
        assertEquals(KeyboardStatus.Typing, controller.status.value)
        
        // Now cancel and release
        controller.cancelAndRelease()
        runCurrent()
        advanceUntilIdle()

        assertEquals(KeyboardStatus.Idle, controller.status.value)

        // Verify KILL was sent after ARM
        val sent = connection.sentOps
        assertTrue(sent.size >= 2)
        assertEquals(io.sleepwalker.core.protocol.Opcodes.ARM, sent[0].opcode)
        assertEquals(io.sleepwalker.core.protocol.Opcodes.KILL, sent[1].opcode)

        // Verify route released
        assertTrue(output.releaseRouteCount >= 1)
    }

    private class FakeScoRoute : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        var releaseCount = 0
        var acquireCount = 0; private set

        override fun hasAvailableScoDevice(): Boolean = true

        override suspend fun acquire(): Boolean {
            acquireCount += 1
            _state.value = ScoState.Active
            return true
        }

        override fun isActive(): Boolean = _state.value == ScoState.Active

        override fun release() {
            releaseCount += 1
            _state.value = ScoState.Inactive
        }
    }

    private class FakeOutput : PcmOutput {
        var beepCount = 0
        var playbackCount = 0
        var releaseRouteCount = 0

        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun playReadyBeep(coldStart: Boolean) {
            beepCount += 1
        }

        override suspend fun play(recording: RecordedPcm) {
            playbackCount += 1
        }

        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }

    private class FakePcmTranscriber : PcmTranscriber {
        var callCount = 0
        var outcome: Result<String> = Result.success("hello world")

        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
            callCount++
            return outcome.getOrThrow()
        }
    }

    private class ThrowingSleepwalkerBleConnection : SleepwalkerBleConnection() {
        val sentOps = mutableListOf<LowLevelOp>()
        var throwAfterOpsCount = -1

        fun setConnectionState(state: KeyboardConnectionState) {
            _connectionState.value = state
        }

        override fun connect(adapter: android.bluetooth.BluetoothAdapter?, context: android.content.Context) {}
        override fun disconnect() {
            _connectionState.value = KeyboardConnectionState.Disconnected
        }

        override suspend fun sendOp(op: LowLevelOp) {
            if (op.opcode == io.sleepwalker.core.protocol.Opcodes.KILL) {
                if (_connectionState.value == KeyboardConnectionState.Connected) {
                    sentOps.add(op)
                }
                return
            }
            if (throwAfterOpsCount == 0) {
                throw Exception("Fake connection send failed")
            }
            if (throwAfterOpsCount > 0) {
                throwAfterOpsCount--
            }
            if (_connectionState.value == KeyboardConnectionState.Connected) {
                sentOps.add(op)
            }
        }
    }

    private class DelayingSleepwalkerBleConnection(
        private val delayMs: Long = 1000
    ) : SleepwalkerBleConnection() {
        val sentOps = mutableListOf<LowLevelOp>()

        fun setConnectionState(state: KeyboardConnectionState) {
            _connectionState.value = state
        }

        override fun connect(adapter: android.bluetooth.BluetoothAdapter?, context: android.content.Context) {}
        override fun disconnect() {
            _connectionState.value = KeyboardConnectionState.Disconnected
        }

        override suspend fun sendOp(op: LowLevelOp) {
            if (_connectionState.value == KeyboardConnectionState.Connected) {
                sentOps.add(op)
            }
            if (op.opcode != io.sleepwalker.core.protocol.Opcodes.KILL) {
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }
}
