package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import dev.nilp0inter.subspace.model.SttStatus
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
class SttControllerTest {
    private fun makeController(
        scope: kotlinx.coroutines.CoroutineScope,
        sco: ScoRoute,
        captureService: CaptureService,
        source: CaptureSource,
        output: PcmOutput,
        transcriber: FakeSttTranscriber,
        dispatcher: CoroutineDispatcher,
    ): SttController = SttController(
        scope = scope,
        sco = sco,
        captureService = captureService,
        source = source,
        output = output,
        transcriptionService = TranscriptionService(transcriber, dispatcher),
    )

    @Test
    fun releaseBeforeScoReadyCancelsWithoutRecordingOrTranscription() = runTest {
        val sco = FakeScoRoute(acquireDelayMs = 1_000)
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber()
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        advanceTimeBy(100)
        controller.onPttReleased()
        advanceUntilIdle()

        assertEquals(0, source.openCount)
        assertEquals(0, transcriber.callCount)
        assertEquals(SttStatus.Cancelled, controller.status.value)
    }

    @Test
    fun normalPressReleaseTranscribesReturnedText() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success("hello world"))
        }
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()

        assertEquals(1, output.beepCount)
        assertEquals(1, source.openCount)
        assertEquals(SttStatus.Recording, controller.status.value)

        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttStatus.Transcribed("hello world"), controller.status.value)
        assertEquals(1, transcriber.callCount)
        assertEquals(1, output.beepCount)
        assertEquals(0, output.playbackCount)
    }

    @Test
    fun emptyAudioReportsEmptyAudioStatusWithoutCallingParakeet() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.emptySource()
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber()
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttStatus.EmptyAudio, controller.status.value)
        assertEquals(0, transcriber.callCount)
    }

    @Test
    fun maxDurationStopsRecordingAndWaitsForReleaseBeforeTranscribing() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success("max transcript"))
        }
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(SttStatus.MaxDurationReached, controller.status.value)
        assertFalse(captureService.isCapturing.value)
        assertEquals(0, transcriber.callCount)

        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttStatus.Transcribed("max transcript"), controller.status.value)
        assertEquals(1, transcriber.callCount)
    }

    @Test
    fun transcriberFailureSurfacesErrorStatus() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Failure("inference blew up"))
        }
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttStatus.Error("inference blew up"), controller.status.value)
    }

    @Test
    fun cancellationReleasesScoAndReturnsToIdle() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber()
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.cancelAndRelease()
        runCurrent()

        assertEquals(SttStatus.Idle, controller.status.value)
        assertEquals(1, output.releaseRouteCount)
        assertFalse(captureService.isCapturing.value)
    }

    @Test
    fun normalTranscriptionReleasesRouteViaFinally() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success("hello"))
        }
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertEquals(SttStatus.Transcribed("hello"), controller.status.value)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun transcribeFailureReleasesRouteViaFinally() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Failure("blew up"))
        }
        val controller = makeController(this, sco, captureService, source, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        advanceUntilIdle()

        assertTrue(controller.status.value is SttStatus.Error)
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun newPressCancelsInFlightTranscriptionAndReleasesRouteExactlyOnce() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val sourceA = CaptureServiceFakes.singleShotSource(shortArrayOf(1, 2, 3, 4))
        val sourceB = CaptureServiceFakes.singleShotSource(shortArrayOf(5, 6, 7, 8))
        val output = FakeOutput()
        val transcriber = FakeSttTranscriber().apply {
            setOutcome(TranscriptionOutcome.Success("first"))
        }
        val controller = makeController(this, sco, captureService, sourceA, output, transcriber, coroutineContext[CoroutineDispatcher]!!)
        controller.setEnabled(true)

        controller.onPttPressed()
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        // Transcription is now in flight. Press PTT again before it completes.
        controller.onPttPressed(ResolvedAudioRoute(sco, output, sourceB))
        runCurrent()
        advanceUntilIdle()

        // The first session's transcription was cancelled by the new press;
        // its `finally` block must release the route exactly once. The new
        // session will release again on its own post-capture path.
        assertEquals(
            "old session must release route exactly once via finally on cancellation",
            1,
            output.releaseRouteCount,
        )
        assertEquals(2, sco.acquireCount)
    }

    private class FakeScoRoute(
        private val acquireDelayMs: Long = 0,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        var releaseCount = 0
        var acquireCount = 0; private set

        override fun hasAvailableScoDevice(): Boolean = true

        override suspend fun acquire(): Boolean {
            acquireCount += 1
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
}