package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalCoroutinesApi::class)
class EchoControllerTest {
    @Test
    fun releaseBeforeScoReadyCancelsWithoutRecordingOrPlayback() = runTest {
        val sco = FakeScoRoute(acquireDelayMs = 1_000)
        val recorder = FakeRecorder()
        val output = FakeOutput()
        val controller = EchoController(this, sco, recorder, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        advanceTimeBy(100)
        controller.onPttReleased()
        runCurrent()

        assertFalse(recorder.started)
        assertEquals(0, output.playbackCount)
        assertEquals(EchoStatus.Cancelled, controller.status.value)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(EchoStatus.Idle, controller.status.value)
    }

    @Test
    fun defaultModeRecordsAfterBeepAndPlaysOnRelease() = runTest {
        val sco = FakeScoRoute()
        val recorder = FakeRecorder()
        val output = FakeOutput()
        val controller = EchoController(this, sco, recorder, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()

        assertEquals(1, output.beepCount)
        assertTrue(recorder.started)
        assertEquals(EchoStatus.Recording, controller.status.value)

        controller.onPttReleased()
        runCurrent()

        assertEquals(1, output.playbackCount)
        assertEquals(EchoStatus.Warm, controller.status.value)

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(1, sco.releaseCount)
        assertEquals(EchoStatus.Idle, controller.status.value)
    }

    @Test
    fun maxDurationStopsRecordingAndWaitsForRelease() = runTest {
        val sco = FakeScoRoute()
        val recorder = FakeRecorder()
        val output = FakeOutput()
        val controller = EchoController(this, sco, recorder, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(EchoStatus.MaxDurationReached, controller.status.value)
        assertFalse(recorder.isActive)

        controller.onPttReleased()
        runCurrent()

        assertEquals(1, output.playbackCount)
    }

    private class FakeScoRoute(
        private val acquireDelayMs: Long = 0,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        override val coldStart: Boolean = false
        var releaseCount = 0

        override fun hasAvailableScoDevice(): Boolean = true

        override suspend fun acquire(): Boolean {
            delay(acquireDelayMs)
            _state.value = ScoState.Active
            return true
        }

        override fun isActive(): Boolean = _state.value == ScoState.Active


        override fun release() {
            releaseCount += 1
            _state.value = ScoState.Inactive
        }
    }

    private class FakeRecorder(
        private val onStart: () -> Unit = {},
    ) : AudioRecorder {
        var started = false
        override var isActive: Boolean = false
            private set

        override suspend fun start(): Boolean {
            onStart()
            started = true
            isActive = true
            return true
        }

        override fun stopIfActiveOrEmpty(): RecordedPcm {
            if (!isActive) return RecordedPcm(shortArrayOf(), 16_000)
            isActive = false
            return RecordedPcm(shortArrayOf(1, 2, 3), 16_000)
        }
    }

    private class FakeOutput(
        private val onBeep: () -> Unit = {},
    ) : PcmOutput {
        var beepCount = 0
        var playbackCount = 0

        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun playReadyBeep(coldStart: Boolean) {
            onBeep()
            beepCount += 1
        }

        override suspend fun play(recording: RecordedPcm) {
            playbackCount += 1
        }
    }
}
