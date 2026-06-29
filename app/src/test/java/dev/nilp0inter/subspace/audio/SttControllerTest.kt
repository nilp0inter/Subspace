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