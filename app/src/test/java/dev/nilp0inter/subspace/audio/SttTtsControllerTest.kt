package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.SttTtsStatus
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class SttTtsControllerTest {
    private fun makeController(
        scope: kotlinx.coroutines.CoroutineScope,
        sco: ScoRoute,
        captureService: CaptureService,
        source: CaptureSource,
        output: PcmOutput,
        transcriber: FakeSttTranscriber,
        synthesizer: FakeTtsSynthesizer,
        dispatcher: CoroutineDispatcher,
    ): SttTtsController = SttTtsController(
        scope = scope,
        sco = sco,
        captureService = captureService,
        source = source,
        output = output,
        transcriber = transcriber,
        synthesizer = synthesizer,
        transcriptionDispatcher = dispatcher,
        synthesisDispatcher = dispatcher,
    )

    @Test
    fun roundTripSuccessTranscribesThenSynthesizesThenPlays() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success("hello world"))
        }
        val synth = FakeTtsSynthesizer().apply {
            setOutcome(SynthesisOutcome.Success(FloatArray(100) { 0.5f }))
        }
        val controller = makeController(this, sco, captureService, source, output, transcriber, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        assertEquals(SttTtsStatus.Recording, controller.status.value)

        controller.onPttReleased("/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttTtsStatus.Idle, controller.status.value)
        assertEquals(1, transcriber.callCount)
        assertEquals(1, synth.callCount)
        assertEquals("hello world", synth.lastRequest?.text)
        assertEquals(1, output.playbackCount)
    }

    @Test
    fun earlyReleaseBeforeRecordingCancels() = runTest {
        val sco = FakeScoRoute(acquireDelayMs = 1_000)
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber()
        val synth = FakeTtsSynthesizer()
        val controller = makeController(this, sco, captureService, source, output, transcriber, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        advanceTimeBy(100)
        controller.onPttReleased("/tmp/M1.json", "en", 8, 1.0f, 16_000)
        advanceUntilIdle()

        assertEquals(0, source.openCount)
        assertEquals(0, transcriber.callCount)
        assertEquals(0, synth.callCount)
    }

    @Test
    fun emptyAudioReportsEmptyAudioWithoutCallingParakeet() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.emptySource()
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber()
        val synth = FakeTtsSynthesizer()
        val controller = makeController(this, sco, captureService, source, output, transcriber, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased("/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttTtsStatus.EmptyAudio, controller.status.value)
        assertEquals(0, transcriber.callCount)
        assertEquals(0, synth.callCount)
    }

    @Test
    fun emptyTranscriptReportsEmptyTranscriptWithoutCallingSupertonic() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success(""))
        }
        val synth = FakeTtsSynthesizer()
        val controller = makeController(this, sco, captureService, source, output, transcriber, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased("/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttTtsStatus.EmptyTranscript, controller.status.value)
        assertEquals(1, transcriber.callCount)
        assertEquals(0, synth.callCount)
    }

    @Test
    fun transcriptionFailureSurfacesErrorStatus() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Failure("stt blew up"))
        }
        val synth = FakeTtsSynthesizer()
        val controller = makeController(this, sco, captureService, source, output, transcriber, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased("/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertTrue(controller.status.value is SttTtsStatus.Error)
        assertEquals(0, synth.callCount)
    }

    @Test
    fun synthesisFailureSurfacesErrorStatus() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success("hello"))
        }
        val synth = FakeTtsSynthesizer().apply {
            setOutcome(SynthesisOutcome.Failure("tts blew up"))
        }
        val controller = makeController(this, sco, captureService, source, output, transcriber, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased("/tmp/M1.json", "en", 8, 1.0f, 16_000)
        runCurrent()
        advanceUntilIdle()

        assertTrue(controller.status.value is SttTtsStatus.Error)
        assertEquals(0, output.playbackCount)
    }

    @Test
    fun cancellationReleasesScoAndReturnsToIdle() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber()
        val synth = FakeTtsSynthesizer()
        val controller = makeController(this, sco, captureService, source, output, transcriber, synth, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.cancelAndRelease()
        runCurrent()

        assertEquals(SttTtsStatus.Idle, controller.status.value)
        assertEquals(1, sco.releaseCount)
        assertFalse(captureService.isCapturing.value)
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

    private class FakeOutput : PcmOutput {
        var beepCount = 0
        var playbackCount = 0

        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun playReadyBeep(coldStart: Boolean) {
            beepCount += 1
        }

        override suspend fun play(recording: RecordedPcm) {
            playbackCount += 1
        }
    }
}