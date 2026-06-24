package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.AudioEncoder
import dev.nilp0inter.subspace.audio.PcmTranscriber
import java.io.File
import java.io.RandomAccessFile
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalControllerTest {
    @Test
    fun bothOutputsEncodeTranscribeAndWriteMarkdownLink() = runTest {
        val base = createTempDir()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber("hello log")
        val paths = createCapture(base, encoder, transcriber,
            saveVoice = true, saveText = true)

        val log = File(paths.dayDirectory, "journal-day-${paths.dateLabel}.md")
        assertTrue("OGG file should exist", paths.recordingFile.isFile)
        assertTrue("Markdown file should exist", log.isFile)
        val text = log.readText()
        assertTrue("Body should contain transcript", text.contains("hello log"))
        assertTrue("Body should contain recording link", text.contains(paths.recordingFile.name))
    }

    @Test
    fun textOnlyDoesNotEncodeOrWriteRecordingLink() = runTest {
        val base = createTempDir()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber("text only")
        val paths = createCapture(base, encoder, transcriber,
            saveVoice = false, saveText = true)

        assertEquals(0, encoder.callCount)
        assertFalse("OGG file should not exist", paths.recordingFile.exists())
        val log = File(paths.dayDirectory, "journal-day-${paths.dateLabel}.md")
        assertTrue("Markdown should exist", log.isFile)
        assertFalse("No .ogg link", log.readText().contains(".ogg"))
    }

    @Test
    fun voiceOnlyDoesNotTranscribeOrWriteMarkdown() = runTest {
        val base = createTempDir()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber("ignored")
        val paths = createCapture(base, encoder, transcriber,
            saveVoice = true, saveText = false)

        assertEquals(0, transcriber.callCount)
        assertTrue("OGG file should exist", paths.recordingFile.isFile)
        val log = File(paths.dayDirectory, "journal-day-${paths.dateLabel}.md")
        assertTrue("Markdown should exist with recording link", log.isFile)
        assertFalse(log.readText().contains("ignored"))
    }

    @Test
    fun transcriptionFailureWritesPlaceholderAndKeepsAudio() = runTest {
        val base = createTempDir()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber(error = IllegalStateException("stt failed"))
        val paths = createCapture(base, encoder, transcriber,
            saveVoice = true, saveText = true)

        assertTrue("OGG file should exist", paths.recordingFile.isFile)
        val log = File(paths.dayDirectory, "journal-day-${paths.dateLabel}.md")
        assertTrue("Markdown should exist", log.isFile)
        assertTrue("Should contain failure placeholder", log.readText().contains("[Transcription failed: stt failed]"))
    }

    @Test
    fun encodingFailureStillWritesMarkdown() = runTest {
        val base = createTempDir()
        val encoder = FakeEncoder(error = IllegalStateException("codec failed"))
        val transcriber = FakeTranscriber("hello")
        val paths = createCapture(base, encoder, transcriber,
            saveVoice = true, saveText = true)

        val log = File(paths.dayDirectory, "journal-day-${paths.dateLabel}.md")
        assertTrue("Markdown should exist", log.isFile)
        assertTrue("Should contain transcript", log.readText().contains("hello"))
    }

    private suspend fun createCapture(
        base: File,
        encoder: AudioEncoder,
        transcriber: PcmTranscriber,
        saveVoice: Boolean,
        saveText: Boolean,
    ): JournalEntryPaths.EntryPaths {
        val pathGen = JournalEntryPaths(ZoneOffset.UTC)
        val startedAt = ZonedDateTime.of(2026, 6, 24, 14, 30, 0, 0, ZoneOffset.UTC)
        val paths = pathGen.preparePaths(base, startedAt)
        createWav(paths.captureFile, 16_000)
        val metadata = JournalEntryMetadata(
            entryId = paths.stem,
            startedAt = startedAt.toString(),
            timezoneOffset = "+0000",
            channel = MetadataChannelSnapshot(
                id = "captains-log",
                saveVoice = saveVoice,
                saveText = saveText,
            ),
            capture = CaptureState(
                state = CaptureTaskState.finished,
                path = paths.captureFile.name,
                sampleRate = 16_000,
                channels = 1,
                encoding = "pcm_s16le",
                durationMs = 100,
                bytes = 3200,
            ),
            encoding = if (saveVoice) DerivedTaskState(state = DerivedTaskStatus.pending) else DerivedTaskState(state = DerivedTaskStatus.skipped),
            transcription = if (saveText) DerivedTaskState(state = DerivedTaskStatus.pending) else DerivedTaskState(state = DerivedTaskStatus.skipped),
        )
        JournalMetadataStore().write(metadata, paths.metadataFile)
        val controller = JournalController(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            encoder = encoder,
            transcriber = transcriber,
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        controller.processCaptureFile(paths).join()
        return paths
    }

    private fun createWav(file: File, sampleRate: Int) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.writeBytes("RIFF")
            writeInt32Le(raf, 36 + 3200)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeInt32Le(raf, 16)
            writeInt16Le(raf, 1)
            writeInt16Le(raf, 1)
            writeInt32Le(raf, sampleRate)
            writeInt32Le(raf, sampleRate * 2)
            writeInt16Le(raf, 2)
            writeInt16Le(raf, 16)
            raf.writeBytes("data")
            writeInt32Le(raf, 3200)
            val pcm = ShortArray(1600) { (it * 100).toShort() }
            val buf = ByteArray(3200)
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                buf[i * 2] = (v and 0xFF).toByte()
                buf[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            raf.write(buf)
        }
    }

    private fun writeInt16Le(raf: RandomAccessFile, value: Int) {
        raf.writeByte(value and 0xFF)
        raf.writeByte((value shr 8) and 0xFF)
    }

    private fun writeInt32Le(raf: RandomAccessFile, value: Int) {
        raf.writeByte(value and 0xFF)
        raf.writeByte((value shr 8) and 0xFF)
        raf.writeByte((value shr 16) and 0xFF)
        raf.writeByte((value shr 24) and 0xFF)
    }

    private fun createTempDir(): File = createTempDirectory(prefix = "journal-").toFile()

    private class FakeEncoder(private val error: Throwable? = null) : AudioEncoder {
        var callCount = 0
        override suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int): Result<File> {
            callCount += 1
            if (error != null) return Result.failure(error)
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()))
            return Result.success(outputFile)
        }
    }

    private class FakeTranscriber(
        private val text: String = "",
        private val error: Throwable? = null,
    ) : PcmTranscriber {
        var callCount = 0
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
            callCount += 1
            if (error != null) throw error
            return text
        }
    }
}
