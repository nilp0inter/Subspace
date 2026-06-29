package dev.nilp0inter.subspace.audio

import dev.nilp0inter.subspace.model.ScoState
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureServiceTest {

    @Test
    fun secondStartWhileSessionActiveIsRejected() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        val first = service.startSession(source, FakeScoRoute(), FakeOutput()) { true }
        assertTrue(first is CaptureStartResult.Started)

        val second = service.startSession(FakeSource(), FakeScoRoute(), FakeOutput()) { true }
        assertEquals(CaptureStartResult.SessionActive, second)

        (first as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun stopClearsActiveSoNextStartIsAccepted() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        val first = service.startSession(source, FakeScoRoute(), FakeOutput()) { true }
        assertTrue(first is CaptureStartResult.Started)
        (first as CaptureStartResult.Started).session.stop()

        // The active reference must be cleared synchronously inside stop() so
        // a rapid PTT re-press — with no coroutine pump between release and
        // the next press — is accepted instead of rejected as SessionActive.
        val second = service.startSession(FakeSource(continuous = true), FakeScoRoute(), FakeOutput()) { true }
        assertTrue(
            "expected second start to be accepted immediately after stop, got $second",
            second is CaptureStartResult.Started,
        )
        (second as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun startWithNoActiveSessionAcceptsAndOpensSource() = runTest {
        val service = captureService()
        val source = FakeSource(
            continuous = true,
            sourceId = CaptureSourceId.VoiceCommunication,
        )

        val result = service.startSession(source, FakeScoRoute(), FakeOutput()) { true }

        assertTrue(result is CaptureStartResult.Started)
        assertEquals(1, source.openCount)
        assertEquals(CaptureSourceId.VoiceCommunication, source.openedSourceId)
        assertTrue(service.isCapturing.value)

        (result as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun framesEmitsChunksReadFromTheSource() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(
                ShortArray(4) { 1 },
                ShortArray(4) { 2 },
                ShortArray(4) { 3 },
            ),
            continuous = false,
        )
        val session = startedSession(service, source)

        val collected = mutableListOf<ShortArray>()
        val collector = launch { session.frames.toList(collected) }

        advanceTimeBy(LOOP_TICK_MS * source.scriptedChunkCount)
        runCurrent()
        collector.cancel()

        assertTrue("expected at least one frame emitted, got ${collected.size}", collected.isNotEmpty())
        session.stop()
    }

    @Test
    fun stopReturnsFullCaptureWithAllSamples() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(
                ShortArray(4) { 1 },
                ShortArray(4) { 2 },
                ShortArray(4) { 3 },
            ),
            continuous = false,
        )
        val session = startedSession(service, source)

        advanceTimeBy(LOOP_TICK_MS * source.scriptedChunkCount)
        runCurrent()
        val pcm = session.stop()

        assertEquals(12, pcm.samples.size)
        assertEquals(1.toShort(), pcm.samples[0])
        assertEquals(2.toShort(), pcm.samples[4])
        assertEquals(3.toShort(), pcm.samples[8])
    }

    @Test
    fun stopWithNoChunksReturnsEmptyPcm() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = false)
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()

        val pcm = session.stop()
        assertEquals(0, pcm.samples.size)
        assertEquals(DEFAULT_RATE, pcm.sampleRate)
    }

    @Test
    fun sessionEndsAtMaxDurationCapWithCapturedPcm() = runTest {
        val service = captureService(maxDurationMs = MAX_DURATION_TEST_MS)
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(2) { 5 }),
            continuous = true,
        )
        val session = startedSession(service, source)

        advanceTimeBy(MAX_DURATION_TEST_MS + SMALL_TICK_MS)
        runCurrent()

        val completion = session.completion.await()
        assertTrue("expected MaxDuration, got $completion", completion is CaptureCompletion.MaxDuration)
        val pcm = completion.recordedPcm
        assertTrue(
            "expected at least the scripted chunk captured, got ${pcm.samples.size}",
            pcm.samples.size >= 2,
        )
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun levelReflectsMicrophoneInputWhileCapturing() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(16) { Short.MAX_VALUE }),
            continuous = false,
        )
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()

        assertTrue(
            "expected positive level for loud input, got ${service.level.value}",
            service.level.value > 0f,
        )

        session.stop()
    }

    @Test
    fun levelIsZeroWhenNoSessionActive() = runTest {
        val service = captureService()
        assertEquals(0f, service.level.value)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun isCapturingAndLevelReflectFullSessionLifecycle() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(16) { Short.MAX_VALUE }),
            continuous = false,
        )

        assertFalse(service.isCapturing.value)
        assertEquals(0f, service.level.value)

        val session = startedSession(service, source)
        assertTrue(service.isCapturing.value)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()
        assertTrue(
            "level should reflect input while capturing, got ${service.level.value}",
            service.level.value > 0f,
        )

        session.stop()
        assertFalse(service.isCapturing.value)
        assertEquals(0f, service.level.value)
    }

    @Test
    fun silentInputYieldsNearZeroLevel() = runTest {
        val service = captureService()
        val source = FakeSource(
            scriptedChunks = listOf(ShortArray(16) { 0 }),
            continuous = false,
        )
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()

        assertEquals(0f, service.level.value, 0.001f)
        session.stop()
    }

    @Test
    fun isCapturingTransitionsThroughSessionLifecycle() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        assertFalse(service.isCapturing.value)

        val session = startedSession(service, source)
        assertTrue(service.isCapturing.value)

        session.stop()
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun cancelledSessionReportsCancelledCompletion() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = false)
        val session = startedSession(service, source)

        advanceTimeBy(SMALL_TICK_MS)
        runCurrent()
        val pcm = service.cancelSession(session)

        assertEquals(0, pcm.samples.size)
        val completion = session.completion.await()
        assertTrue("expected Cancelled, got $completion", completion is CaptureCompletion.Cancelled)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun scoUnavailableIsTypedFailureAndDoesNotOpenSource() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val sco = FakeScoRoute(scoAvailable = false)

        val result = service.startSession(source, sco, FakeOutput()) { true }

        assertEquals(CaptureStartResult.ScoUnavailable, result)
        assertEquals(0, source.openCount)
        assertFalse(service.isCapturing.value)
    }

    @Test
    fun shouldProceedFalseAfterScoAcquireCancelsBeforeBeep() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val sco = FakeScoRoute()
        val output = FakeOutput()

        val result = service.startSession(source, sco, output) { false }

        assertEquals(CaptureStartResult.Cancelled, result)
        assertEquals(0, output.readyBeepCount)
        assertEquals(0, source.openCount)
        assertEquals(1, sco.acquireCount)
        assertEquals(
            "service must release SCO on Cancelled after SCO acquire (PTT released during acquire)",
            1,
            sco.releaseCount,
        )
    }

    @Test
    fun shouldProceedFalseAfterBeepCancelsBeforeRecording() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)
        val sco = FakeScoRoute()
        val output = FakeOutput()

        // First predicate call (after SCO acquire) returns true → beep plays.
        // Second predicate call (after beep) returns false → cancelled.
        var predicateCalls = 0
        val result = service.startSession(source, sco, output) {
            predicateCalls += 1
            predicateCalls == 1
        }

        assertEquals(CaptureStartResult.Cancelled, result)
        assertEquals(1, output.readyBeepCount)
        assertEquals(0, source.openCount)
        assertEquals(
            "service must release SCO on Cancelled after beep (PTT released during beep)",
            1,
            sco.releaseCount,
        )
    }

    @Test
    fun sourceOpenFailureReportsRecordingFailed() = runTest {
        val service = captureService()
        val source = FakeSource(openShouldFail = true)
        val sco = FakeScoRoute()
        val output = FakeOutput()

        val result = service.startSession(source, sco, output) { true }

        assertEquals(CaptureStartResult.RecordingFailed, result)
        assertEquals(1, output.readyBeepCount)
        assertFalse(service.isCapturing.value)
        assertEquals(
            "service must release SCO on RecordingFailed (source open returned null after beep)",
            1,
            sco.releaseCount,
        )
    }

    @Test
    fun rapidRePressAfterCancelSessionIsAccepted() = runTest {
        val service = captureService()
        val source = FakeSource(continuous = true)

        val first = service.startSession(source, FakeScoRoute(), FakeOutput()) { true }
        assertTrue(first is CaptureStartResult.Started)
        val firstSession = (first as CaptureStartResult.Started).session

        service.cancelSession(firstSession)

        // No advanceTimeBy / runCurrent between cancel and re-press — the
        // service must have cleared `active` synchronously inside
        // `cancelSession()` → `ActiveSession.cancel()` → `finalize()`.
        val second = service.startSession(
            FakeSource(continuous = true),
            FakeScoRoute(),
            FakeOutput(),
        ) { true }
        assertTrue(
            "expected rapid re-press after cancelSession to be accepted, got $second",
            second is CaptureStartResult.Started,
        )
        (second as CaptureStartResult.Started).session.stop()
    }

    @Test
    fun bufferCapPreventsAccumulationPastConfiguredLimit() = runTest {
        val tinySampleRate = 4
        val capFactorSeconds = 1
        val capSamples = tinySampleRate * capFactorSeconds
        val scriptedChunkCount = 4
        val scriptedChunkSize = 4

        val service = captureService(maxBufferSamplesFactor = capFactorSeconds)
        val source = FakeSource(
            sampleRate = tinySampleRate,
            scriptedChunks = List(scriptedChunkCount) { ShortArray(scriptedChunkSize) { 1 } },
            continuous = false,
        )
        val session = startedSession(service, source)

        advanceTimeBy(LOOP_TICK_MS * scriptedChunkCount + SMALL_TICK_MS)
        runCurrent()
        val pcm = session.stop()

        assertEquals(capSamples, pcm.samples.size)
    }

    @Test
    fun sessionExposesNegotiated16kHzSampleRate() = runTest {
        val service = captureService()
        val source = FakeSource(
            sampleRate = 16_000,
            continuous = true,
        )

        val session = startedSession(service, source)

        assertEquals(16_000, session.sampleRate)
        session.stop()
    }

    @Test
    fun sessionExposesNegotiated8kHzSampleRate() = runTest {
        val service = captureService()
        val source = FakeSource(
            sampleRate = 8_000,
            continuous = true,
        )

        val session = startedSession(service, source)

        assertEquals(8_000, session.sampleRate)
        session.stop()
    }

    // -- helpers -------------------------------------------------------------

    private suspend fun startedSession(
        service: CaptureService,
        source: FakeSource,
    ): CaptureSession {
        val result = service.startSession(source, FakeScoRoute(), FakeOutput()) { true }
        assertTrue("startSession failed: $result", result is CaptureStartResult.Started)
        return (result as CaptureStartResult.Started).session
    }

    private fun testDispatcher(scope: TestScope): CoroutineDispatcher =
        scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher

    private fun TestScope.captureService(
        maxDurationMs: Long = CaptureService.DEFAULT_MAX_DURATION_MS,
        maxBufferSamplesFactor: Int = CaptureService.DEFAULT_BUFFER_FACTOR,
        clock: () -> Long = { testScheduler.currentTime },
    ): CaptureService = CaptureService(
        scope = this,
        readDispatcher = testDispatcher(this),
        maxDurationMs = maxDurationMs,
        maxBufferSamplesFactor = maxBufferSamplesFactor,
        clock = clock,
    )

    private class FakeSource(
        override val sourceId: CaptureSourceId = CaptureSourceId.VoiceCommunication,
        private val sampleRate: Int = DEFAULT_RATE,
        private val scriptedChunks: List<ShortArray> = emptyList(),
        private val continuous: Boolean = false,
        private val openShouldFail: Boolean = false,
    ) : CaptureSource {
        var openCount: Int = 0; private set
        var openedSourceId: CaptureSourceId? = null; private set
        val scriptedChunkCount: Int get() = scriptedChunks.size

        override suspend fun open(): OpenedCaptureSource? {
            openCount += 1
            if (openShouldFail) return null
            openedSourceId = sourceId
            return Opened(
                sampleRate = sampleRate,
                bufferSizeShorts = scriptedChunks.firstOrNull()?.size ?: DEFAULT_BUFFER_SHORTS,
                scripted = scriptedChunks,
                continuousAfter = continuous,
            )
        }
    }

    private class Opened(
        override val sampleRate: Int,
        override val bufferSizeShorts: Int,
        scripted: List<ShortArray>,
        private val continuousAfter: Boolean,
    ) : OpenedCaptureSource {
        private val queue = ArrayDeque<ShortArray>().apply { scripted.forEach { addLast(it) } }
        @Volatile private var closed: Boolean = false

        override fun read(buffer: ShortArray): Int {
            if (closed) return -1
            val next = queue.removeFirstOrNull()
                ?: if (continuousAfter) ShortArray(buffer.size) { 0 }
                else return 0
            val n = minOf(next.size, buffer.size)
            next.copyInto(buffer, 0, 0, n)
            return n
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeScoRoute(
        private val scoAvailable: Boolean = true,
    ) : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state.asStateFlow()
        override val coldStart: Boolean = false
        var acquireCount: Int = 0; private set
        var releaseCount: Int = 0; private set

        override fun hasAvailableScoDevice(): Boolean = scoAvailable

        override suspend fun acquire(): Boolean {
            acquireCount += 1
            if (!scoAvailable) return false
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
        var readyBeepCount: Int = 0; private set
        var errorBeepCount: Int = 0; private set
        var playCount: Int = 0; private set

        override suspend fun playReadyBeep(coldStart: Boolean) {
            readyBeepCount += 1
        }

        override suspend fun playErrorBeep(coldStart: Boolean) {
            errorBeepCount += 1
        }

        override suspend fun play(recording: RecordedPcm) {
            playCount += 1
        }
    }

    private companion object {
        const val DEFAULT_RATE = CaptureService.DEFAULT_SAMPLE_RATE
        const val DEFAULT_BUFFER_SHORTS = 4
        const val SMALL_TICK_MS = 5L
        const val LOOP_TICK_MS = 2L
        const val MAX_DURATION_TEST_MS = 50L
    }
}
