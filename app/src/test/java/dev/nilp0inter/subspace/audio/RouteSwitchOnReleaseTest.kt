package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class RouteSwitchOnReleaseTest {
    @Test
    fun sttReleaseCallsReleaseRouteOnOutput() = runTest {
        val sco = FakeScoRoute()
        val recorder = FakeRecorder(hasAudio = true)
        val output = RouteTrackingOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success("hello"))
        }
        val dispatcher = coroutineContext[CoroutineDispatcher]!!
        val controller = SttController(
            this, sco, recorder, output,
            TranscriptionService(transcriber, dispatcher),
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        assertTrue(recorder.started)

        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertTrue("releaseRoute should be called on Stt release", output.releaseRouteCount > 0)
    }

    @Test
    fun sttCancelCallsReleaseRouteOnOutput() = runTest {
        val sco = FakeScoRoute()
        val recorder = FakeRecorder(hasAudio = false)
        val output = RouteTrackingOutput()
        val transcriber = FakeSttTranscriber()
        val dispatcher = coroutineContext[CoroutineDispatcher]!!
        val controller = SttController(
            this, sco, recorder, output,
            TranscriptionService(transcriber, dispatcher),
        )
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertTrue("releaseRoute should be called on empty audio cancel", output.releaseRouteCount > 0)
    }

    @Test
    fun echoReleaseCallsPlayWhichDoesRouteSwitch() = runTest {
        val sco = FakeScoRoute()
        val recorder = FakeRecorder(hasAudio = true)
        val output = RouteTrackingOutput()
        val controller = EchoController(this, sco, recorder, output)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()

        assertTrue("play should be called for echo route switch", output.playCount > 0)
    }

    @Test
    fun defaultPcmOutputReleaseRouteIsNoOp() = runTest {
        val output = LocalPcmOutput()
        output.releaseRoute()
    }

    private class FakeScoRoute : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        override val coldStart: Boolean = false
        var releaseCount = 0

        override fun hasAvailableScoDevice(): Boolean = true
        override suspend fun acquire(): Boolean {
            _state.value = ScoState.Active
            return true
        }
        override fun isActive(): Boolean = _state.value == ScoState.Active
        override fun release() { releaseCount += 1 }
    }

    private class FakeRecorder(private val hasAudio: Boolean) : AudioRecorder {
        var started = false
        override var isActive: Boolean = false
            private set

        override suspend fun start(): Boolean {
            started = true
            isActive = true
            return true
        }

        override fun stopIfActiveOrEmpty(): RecordedPcm {
            if (!isActive) return RecordedPcm(shortArrayOf(), 16_000)
            isActive = false
            return if (hasAudio) RecordedPcm(shortArrayOf(1, 2, 3), 16_000)
                   else RecordedPcm(shortArrayOf(), 16_000)
        }
    }

    private class RouteTrackingOutput : PcmOutput {
        var beepCount = 0
        var playCount = 0
        var releaseRouteCount = 0

        override suspend fun playReadyBeep(coldStart: Boolean) { beepCount++ }
        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun play(recording: RecordedPcm) { playCount++ }
        override suspend fun releaseRoute() { releaseRouteCount++ }
    }
}