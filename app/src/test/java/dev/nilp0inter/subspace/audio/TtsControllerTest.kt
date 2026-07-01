package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.TtsStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class TtsControllerTest {
    private fun makeController(
        scope: kotlinx.coroutines.CoroutineScope,
        sco: ScoRoute,
        output: PcmOutput,
        synthesizer: FakeTtsSynthesizer,
        dispatcher: CoroutineDispatcher,
    ): TtsController = TtsController(
        scope = scope,
        sco = sco,
        output = output,
        synthesizer = synthesizer,
        synthesisDispatcher = dispatcher,
    )

    @Test
    fun synthesisSuccessPlaysAudioAndReleasesScoAfterWarmup() = runTest {
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val synth = FakeTtsSynthesizer().apply {
            setOutcome(SynthesisOutcome.Success(FloatArray(100) { 0.5f }))
        }
        val controller = makeController(this, sco, output, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.synthesize("hello", "/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, synth.callCount)
        assertEquals(1, output.playbackCount)
        assertEquals("hello", synth.lastRequest?.text)
        assertEquals("en", synth.lastRequest?.lang)
        assertEquals(8, synth.lastRequest?.totalSteps)
        assertEquals(1.0f, synth.lastRequest!!.speed, 0.001f)

        assertEquals(1, output.releaseRouteCount)
        assertEquals(0, sco.releaseCount)
    }

    @Test
    fun emptyTextReportsEmptyTextStatusWithoutCallingSynthesizer() = runTest {
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val synth = FakeTtsSynthesizer()
        val controller = makeController(this, sco, output, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.synthesize("", "/tmp/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(TtsStatus.EmptyText, controller.status.value)
        assertEquals(0, synth.callCount)
        assertEquals(0, output.playbackCount)
    }

    @Test
    fun modelNotReadyReportsErrorStatus() = runTest {
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val synth = FakeTtsSynthesizer(modelStatus = dev.nilp0inter.subspace.model.TtsModelStatus.Loading)
        val controller = makeController(this, sco, output, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.synthesize("hello", "/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertTrue(controller.status.value is TtsStatus.Error)
        assertEquals(0, output.playbackCount)
    }

    @Test
    fun synthesisFailureSurfacesErrorStatus() = runTest {
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val synth = FakeTtsSynthesizer().apply {
            setOutcome(SynthesisOutcome.Failure("inference blew up"))
        }
        val controller = makeController(this, sco, output, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.synthesize("hello", "/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertEquals(TtsStatus.Error("inference blew up"), controller.status.value)
        assertEquals(0, output.playbackCount)
    }

    @Test
    fun playbackCompletionReturnsToIdle() = runTest {
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val synth = FakeTtsSynthesizer().apply {
            setOutcome(SynthesisOutcome.Success(FloatArray(50) { 0.3f }))
        }
        val controller = makeController(this, sco, output, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.synthesize("hello", "/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, output.playbackCount)
    }

    @Test
    fun cancellationReleasesScoAndReturnsToIdle() = runTest {
        val sco = FakeScoRoute()
        val output = FakeOutput()
        val synth = FakeTtsSynthesizer()
        val controller = makeController(this, sco, output, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.synthesize("hello", "/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        controller.cancelAndRelease()
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, output.releaseRouteCount)
        assertEquals(0, sco.releaseCount)
    }

    @Test
    fun cancellationDuringPlaybackReleasesRouteWithoutErrorStatus() = runTest {
        val sco = FakeScoRoute()
        val output = FakeOutput(playDelayMs = 60_000)
        val synth = FakeTtsSynthesizer().apply {
            setOutcome(SynthesisOutcome.Success(FloatArray(100) { 0.5f }))
        }
        val controller = makeController(this, sco, output, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.synthesize("hello", "/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()

        assertEquals(TtsStatus.Playing, controller.status.value)

        controller.cancelAndRelease()
        advanceUntilIdle()

        assertEquals(TtsStatus.Idle, controller.status.value)
        assertEquals(1, output.playbackCount)
        assertEquals(1, output.releaseRouteCount)
        assertEquals(0, sco.releaseCount)
    }

    private class FakeScoRoute(
        private val acquireDelayMs: Long = 0,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
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

    private class FakeOutput(
        private val playDelayMs: Long = 0,
    ) : PcmOutput {
        var beepCount = 0
        var playbackCount = 0
        var releaseRouteCount = 0

        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun playReadyBeep(coldStart: Boolean) {
            beepCount += 1
        }

        override suspend fun play(recording: RecordedPcm) {
            playbackCount += 1
            if (playDelayMs > 0) delay(playDelayMs)
        }

        override suspend fun releaseRoute() {
            releaseRouteCount += 1
        }
    }
}
