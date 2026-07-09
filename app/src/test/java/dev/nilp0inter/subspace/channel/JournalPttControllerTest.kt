package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.AudioEncoder
import dev.nilp0inter.subspace.audio.CaptureService
import dev.nilp0inter.subspace.audio.CaptureServiceFakes
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.audio.PcmOutput
import dev.nilp0inter.subspace.audio.RecordedPcm
import dev.nilp0inter.subspace.audio.ResolvedAudioRoute
import dev.nilp0inter.subspace.audio.ScoRoute
import dev.nilp0inter.subspace.audio.WavPcmReader
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.ScoState
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JournalPttControllerTest {

    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = createTempDirectory(prefix = "journal-ptt-").toFile()
    }

    @After
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun pressReleaseWritesWavWithSessionSampleRateAndReleasesRoute() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = ContinuousFakeSource(sampleRate = 16_000)
        val output = FakeOutput()
        val journalController = journalController()
        val channel = JournalChannel(baseDirectory = baseDir.absolutePath)
        val controller = JournalPttController(
            scope = this,
            sco = sco,
            output = output,
            captureService = captureService,
            source = source,
            journal = journalController,
            channelProvider = { channel },
        )

        controller.onPttPressed()
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        awaitReleaseRoute(output, expected = 1)

        assertEquals(
            "JournalPttController must release the route via output.releaseRoute() after finalize",
            1,
            output.releaseRouteCount,
        )
    }

    @Test
    fun eightKhzSourceProducesWavHeaderWith8000SampleRate() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = ContinuousFakeSource(sampleRate = 8_000)
        val output = FakeOutput()
        val journalController = journalController()
        val channel = JournalChannel(baseDirectory = baseDir.absolutePath, saveVoice = false, saveText = true)
        val controller = JournalPttController(
            scope = this,
            sco = sco,
            output = output,
            captureService = captureService,
            source = source,
            journal = journalController,
            channelProvider = { channel },
        )

        controller.onPttPressed()
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        awaitReleaseRoute(output, expected = 1)

        val wavFile = findWavFile(baseDir)
        assertTrue("A WAV capture file must be produced", wavFile != null)
        val wav = WavPcmReader.read(wavFile!!)
        assertTrue("WAV must be readable", wav != null)
        wav!!
        assertEquals(
            "WAV header sample rate must match the 8 kHz negotiated session rate",
            8_000,
            wav.sampleRate,
        )
    }

    @Test
    fun cancelAndReleaseReleasesRouteViaOutput() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = ContinuousFakeSource(sampleRate = 16_000)
        val output = FakeOutput()
        val journalController = journalController()
        val channel = JournalChannel(baseDirectory = baseDir.absolutePath)
        val controller = JournalPttController(
            scope = this,
            sco = sco,
            output = output,
            captureService = captureService,
            source = source,
            journal = journalController,
            channelProvider = { channel },
        )

        controller.onPttPressed()
        runCurrent()
        controller.cancelAndRelease()
        runCurrent()
        awaitReleaseRoute(output, expected = 1)

        assertTrue(
            "cancelAndRelease must release the route via output.releaseRoute()",
            output.releaseRouteCount >= 1,
        )
    }

    @Test
    fun sequentialSessionsProduceTwoRecordingFiles() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = ContinuousFakeSource(sampleRate = 16_000)
        val output = FakeOutput()
        val journalController = journalController()
        val channel = JournalChannel(baseDirectory = baseDir.absolutePath)
        val controller = JournalPttController(
            scope = this,
            sco = sco,
            output = output,
            captureService = captureService,
            source = source,
            journal = journalController,
            channelProvider = { channel },
        )

        // 1. First session start and release
        controller.onPttPressed()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        controller.onPttReleased()
        runCurrent()
        awaitReleaseRoute(output, expected = 1)

        // 2. New session starts on a new route
        val newRoute = ResolvedAudioRoute(
            sco = FakeScoRoute(),
            output = FakeOutput(),
            source = source,
        )
        controller.onPttPressed(newRoute)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        controller.onPttReleased(newRoute)
        runCurrent()
        awaitReleaseRoute(newRoute.output as FakeOutput, expected = 1)

        // 3. Verify that both OGG/JSON files were produced
        val oggFiles = baseDir.walkTopDown().filter { it.isFile && it.extension == "ogg" }.toList()
        assertEquals("Two OGG recording files must be produced", 2, oggFiles.size)
        val jsonFiles = baseDir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertEquals("Two metadata JSON files must be produced", 2, jsonFiles.size)
    }

    private suspend fun TestScope.awaitReleaseRoute(output: FakeOutput, expected: Int) {
        // finishSession uses withContext(Dispatchers.IO) which runs on a
        // real thread pool outside the test scheduler. advanceUntilIdle
        // drives the test-dispatcher part; then poll with real Thread.sleep
        // for the IO continuation to be queued and processed.
        advanceUntilIdle()
        val deadline = System.currentTimeMillis() + 5_000L
        while (output.releaseRouteCount < expected && System.currentTimeMillis() < deadline) {
            runCurrent()
            if (output.releaseRouteCount >= expected) break
            Thread.sleep(10)
        }
    }

    private fun journalController(): JournalController = JournalController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        encoder = NoopEncoder(),
        transcriber = NoopTranscriber(),
        dispatcher = Dispatchers.Unconfined,
    )

    private fun findWavFile(root: File): File? {
        val captureFiles = mutableListOf<File>()
        root.walkTopDown().forEach { if (it.isFile && it.extension == "wav") captureFiles.add(it) }
        return captureFiles.firstOrNull()
    }

    private class FakeScoRoute : ScoRoute {
        private val _state = MutableStateFlow<ScoState>(ScoState.Inactive)
        override val state: StateFlow<ScoState> = _state
        override val coldStart: Boolean = false
        var releaseCount = 0; private set

        override fun hasAvailableScoDevice(): Boolean = true
        override suspend fun acquire(): Boolean {
            _state.value = ScoState.Active
            return true
        }
        override fun isActive(): Boolean = _state.value == ScoState.Active
        override fun release() { releaseCount += 1 }
    }

    private class FakeOutput : PcmOutput {
        var releaseRouteCount = 0; private set
        var playCount = 0; private set
        override suspend fun playReadyBeep(coldStart: Boolean) {}
        override suspend fun playErrorBeep(coldStart: Boolean) {}
        override suspend fun play(recording: RecordedPcm) { playCount++ }
        override suspend fun releaseRoute() { releaseRouteCount += 1 }
    }

    private class NoopEncoder : AudioEncoder {
        override suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int): Result<File> {
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
            return Result.success(outputFile)
        }
    }

    private class NoopTranscriber : PcmTranscriber {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String = ""
    }

    private class ContinuousFakeSource(
        private val sampleRate: Int = 16_000,
        override val sourceId: dev.nilp0inter.subspace.audio.CaptureSourceId =
            dev.nilp0inter.subspace.audio.CaptureSourceId.VoiceCommunication,
    ) : dev.nilp0inter.subspace.audio.CaptureSource {
        override suspend fun open(): dev.nilp0inter.subspace.audio.OpenedCaptureSource? =
            ContinuousOpenedSource(sampleRate)
    }

    private class ContinuousOpenedSource(
        override val sampleRate: Int,
    ) : dev.nilp0inter.subspace.audio.OpenedCaptureSource {
        override val bufferSizeShorts: Int = 64
        private var closed = false

        override fun read(buffer: ShortArray): Int {
            if (closed) return -1
            val n = minOf(buffer.size, 64)
            for (i in 0 until n) buffer[i] = (i * 100).toShort()
            return n
        }

        override fun close() { closed = true }
    }
}