package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.EchoStatus
import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
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
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(PCM)
        val sco = FakeScoRoute(acquireDelayMs = 1_000)
        val output = FakeOutput()
        val controller = EchoController(this, sco, captureService, source, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        advanceTimeBy(100)
        controller.onPttReleased()
        runCurrent()

        assertEquals(0, source.openCount)
        assertEquals(0, output.playbackCount)
        assertEquals(EchoStatus.Cancelled, controller.status.value)
        // The controller must not release the route on this branch — the
        // service owns SCO release on Cancelled. The controller's
        // output.releaseRoute() must NOT be called here.
        assertEquals(0, output.releaseRouteCount)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(EchoStatus.Idle, controller.status.value)
    }

    @Test
    fun defaultModeRecordsAfterBeepAndPlaysOnRelease() = runTest {
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(PCM)
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val controller = EchoController(this, sco, captureService, source, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()

        assertEquals(1, output.readyBeepCount)
        assertEquals(1, source.openCount)
        assertEquals(EchoStatus.Recording, controller.status.value)

        controller.onPttReleased()
        runCurrent()

        assertEquals(1, output.playbackCount)
        assertEquals(EchoStatus.Warm, controller.status.value)

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(1, output.releaseRouteCount)
        assertEquals(0, sco.releaseCount)
        assertEquals(EchoStatus.Idle, controller.status.value)
    }

    @Test
    fun recordingFailedDoesNotReleaseScoViaController() = runTest {
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.failingSource()
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val controller = EchoController(this, sco, captureService, source, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()

        // The service releases SCO on RecordingFailed; the controller must
        // not double-release via output.releaseRoute() on this branch.
        assertEquals(0, output.releaseRouteCount)
        assertEquals(1, sco.releaseCount)
        assertEquals(EchoStatus.Error("Recording failed"), controller.status.value)
    }

    @Test
    fun maxDurationStopsRecordingAndWaitsForRelease() = runTest {
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(PCM)
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val controller = EchoController(this, sco, captureService, source, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(EchoStatus.MaxDurationReached, controller.status.value)
        assertFalse(captureService.isCapturing.value)

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

    private class FakeOutput : PcmOutput {
        var readyBeepCount = 0
        var playbackCount = 0
        var releaseRouteCount = 0

        override suspend fun playReadyBeep(coldStart: Boolean) {
            readyBeepCount += 1
        }

        override suspend fun playErrorBeep(coldStart: Boolean) {}

        override suspend fun play(recording: RecordedPcm) {
            playbackCount += 1
        }

        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }

    private companion object {
        val PCM = shortArrayOf(1, 2, 3)
    }
}
