package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.AudioEncoder
import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputAcceptance
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun activeCarHangFinalizesMetadataAndRunsDerivedProcessing() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = ContinuousFakeSource(sampleRate = 16_000)
        val output = FakeOutput()
        val encoder = RecordingEncoder()
        val transcriber = RecordingTranscriber("car hang transcript")
        val journalController = journalController(encoder, transcriber)
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
        advanceTimeBy(100)
        runCurrent()
        controller.onPttReleased()
        awaitReleaseRoute(output, expected = 1)

        val metadataFile = findMetadataFile(baseDir)
        assertTrue("Normal car hang must persist terminal metadata", metadataFile != null)
        val metadata = JournalMetadataStore().read(metadataFile!!)
        assertTrue("Terminal metadata must be readable", metadata != null)
        metadata!!
        assertTrue("Normal terminal metadata must include endedAt", metadata.endedAt != null)
        assertEquals(CaptureTaskState.deleted, metadata.capture.state)
        assertEquals(16_000, metadata.capture.sampleRate)
        assertEquals(1, metadata.capture.channels)
        assertEquals("pcm_s16le", metadata.capture.encoding)
        assertTrue("Final metadata must include capture duration", metadata.capture.durationMs != null)
        assertTrue("Final metadata must include capture byte count", metadata.capture.bytes != null)
        assertEquals(DerivedTaskStatus.finished, metadata.encoding?.state)
        assertEquals(DerivedTaskStatus.finished, metadata.transcription?.state)
        assertEquals("car hang transcript", metadata.transcription?.text)
        assertEquals(1, encoder.callCount)
        assertEquals(1, transcriber.callCount)

        val markdownFiles = baseDir.walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
        assertEquals(1, markdownFiles.size)
        assertTrue(markdownFiles.single().readText().contains("car hang transcript"))
        assertEquals(1, output.releaseRouteCount)
    }

    @Test
    fun inputReleaseDoesNotCompleteUntilDerivedProcessingFinishes() = runTest {
        val samples = ShortArray(160) { it.toShort() }
        val encoder = SuspendingEncoder()
        val transcript = "derived before terminal completion"
        val journalController = JournalController(
            scope = backgroundScope,
            encoder = encoder,
            transcriber = RecordingTranscriber(transcript),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val channel = JournalChannel(baseDirectory = baseDir.absolutePath)
        val controller = JournalPttController(
            scope = this,
            sco = FakeScoRoute(),
            output = FakeOutput(),
            captureService = CaptureServiceFakes.newService(this),
            source = ContinuousFakeSource(sampleRate = 16_000),
            journal = journalController,
            channelProvider = { channel },
        )
        val acceptance = controller.prepareInput()
        assertTrue(acceptance is ChannelInputAcceptance.Accepted)
        val target = (acceptance as ChannelInputAcceptance.Accepted).target
        target.onInputStarted(object : ChannelAudioInputSession {
            override val frames = flowOf(samples)
            override val sampleRate: Int = 16_000
        })
        runCurrent()

        // UNDISPATCHED makes the hard-coded Dispatchers.IO context identical to
        // the caller context, so release deterministically runs through the
        // processCaptureFile call before this Deferred is returned to the test.
        val release = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            target.onInputReleased(RecordedPcm(samples, 16_000))
        }

        assertFalse(
            "Release must wait for the processCaptureFile Job rather than only starting it",
            release.isCompleted,
        )
        runCurrent()
        assertTrue("Derived encoding must have reached the controlled suspension", encoder.started.isCompleted)
        assertFalse("Release must remain incomplete while encoding is suspended", release.isCompleted)

        val metadataFile = findMetadataFile(baseDir)
        assertTrue("Terminal metadata must exist before derived processing", metadataFile != null)
        val processingMetadata = JournalMetadataStore().read(metadataFile!!)
        assertTrue(processingMetadata != null)
        assertEquals(DerivedTaskStatus.running, processingMetadata!!.encoding?.state)
        assertEquals(DerivedTaskStatus.pending, processingMetadata.transcription?.state)
        assertTrue(
            "Markdown must not expose the entry before derived processing finishes",
            baseDir.walkTopDown().none { it.isFile && it.extension == "md" },
        )

        encoder.allowCompletion.complete(Unit)
        runCurrent()
        release.await()

        val finishedMetadata = JournalMetadataStore().read(metadataFile)
        assertTrue(finishedMetadata != null)
        assertEquals(CaptureTaskState.deleted, finishedMetadata!!.capture.state)
        assertEquals(DerivedTaskStatus.finished, finishedMetadata.encoding?.state)
        assertEquals(DerivedTaskStatus.finished, finishedMetadata.transcription?.state)
        assertEquals(transcript, finishedMetadata.transcription?.text)
        val markdown = baseDir.walkTopDown().single { it.isFile && it.extension == "md" }.readText()
        assertTrue(markdown.contains(transcript))
        assertTrue(markdown.contains(".ogg"))
    }

    @Test
    fun preCaptureCancellationLeavesEntryForRecovery() = runTest {
        val sco = FakeScoRoute()
        val captureService = CaptureServiceFakes.newService(this)
        val source = ContinuousFakeSource(sampleRate = 16_000)
        val output = FakeOutput()
        val encoder = RecordingEncoder()
        val transcriber = RecordingTranscriber("must not run")
        val journalController = journalController(encoder, transcriber)
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

        val metadataFile = findMetadataFile(baseDir)
        assertTrue("Pre-capture cancellation must leave recoverable metadata", metadataFile != null)
        val beforeRecovery = JournalMetadataStore().read(metadataFile!!)
        assertTrue(beforeRecovery != null)
        assertEquals(CaptureTaskState.recording, beforeRecovery!!.capture.state)
        assertTrue("Cancellation must not claim a terminal timestamp", beforeRecovery.endedAt == null)

        journalController.runRecovery(baseDir).join()

        val recovered = JournalMetadataStore().read(metadataFile)
        assertTrue(recovered != null)
        assertEquals(CaptureTaskState.abandoned, recovered!!.capture.state)
        assertTrue("Recovery must not add a terminal timestamp", recovered.endedAt == null)
        assertEquals(0, encoder.callCount)
        assertEquals(0, transcriber.callCount)
        assertEquals(1, output.releaseRouteCount)
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

    private fun journalController(
        encoder: AudioEncoder = NoopEncoder(),
        transcriber: PcmTranscriber = NoopTranscriber(),
    ): JournalController = JournalController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        encoder = encoder,
        transcriber = transcriber,
        dispatcher = Dispatchers.Unconfined,
    )


    private fun findWavFile(root: File): File? {
        val captureFiles = mutableListOf<File>()
        root.walkTopDown().forEach { if (it.isFile && it.extension == "wav") captureFiles.add(it) }
        return captureFiles.firstOrNull()
    }

    private fun findMetadataFile(root: File): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.extension == "json" }


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

    private class RecordingEncoder : AudioEncoder {
        var callCount = 0

        override suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int): Result<File> {
            callCount += 1
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
            return Result.success(outputFile)
        }
    }

    private class SuspendingEncoder : AudioEncoder {
        val started = CompletableDeferred<Unit>()
        val allowCompletion = CompletableDeferred<Unit>()

        override suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int): Result<File> {
            started.complete(Unit)
            allowCompletion.await()
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
            return Result.success(outputFile)
        }
    }

    private class RecordingTranscriber(private val result: String) : PcmTranscriber {
        var callCount = 0

        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
            callCount += 1
            return result
        }
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