package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.AudioEncoder
import dev.nilp0inter.subspace.audio.PcmTranscriber
import dev.nilp0inter.subspace.model.CaptainsLogChannel
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptainsLogControllerTest {
    @Test
    fun bothOutputsEncodeTranscribeAndWriteMarkdownLink() = runTest {
        val base = createTempDirectory(prefix = "captains-log-").toFile()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber("hello log")
        val controller = controller(encoder, transcriber)

        controller.processCapture(CaptainsLogChannel(saveVoice = true, saveText = true), base, shortArrayOf(1, 2), 16_000)

        assertEquals(1, encoder.callCount)
        assertEquals(1, transcriber.callCount)
        val log = File(base, "2026/2026-06/2026-06-24/log-2026-06-24.md")
        assertTrue(File(base, "2026/2026-06/2026-06-24/recordings/log-2026-06-24_14-30-00.ogg").isFile)
        assertEquals(
            "# Log 2026-06-24\n\n" +
                "## Entry 14-30-00\n\n" +
                "hello log\n\n" +
                "[Source recording](recordings/log-2026-06-24_14-30-00.ogg)\n\n",
            log.readText(),
        )
    }

    @Test
    fun textOnlyDoesNotEncodeOrWriteRecordingLink() = runTest {
        val base = createTempDirectory(prefix = "captains-log-").toFile()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber("text only")

        controller(encoder, transcriber).processCapture(
            CaptainsLogChannel(saveVoice = false, saveText = true),
            base,
            shortArrayOf(1, 2),
            16_000,
        )

        assertEquals(0, encoder.callCount)
        assertEquals(1, transcriber.callCount)
        val log = File(base, "2026/2026-06/2026-06-24/log-2026-06-24.md")
        assertFalse(File(base, "2026/2026-06/2026-06-24/recordings/log-2026-06-24_14-30-00.ogg").exists())
        assertFalse(log.readText().contains("Source recording"))
    }

    @Test
    fun voiceOnlyDoesNotTranscribeOrWriteMarkdown() = runTest {
        val base = createTempDirectory(prefix = "captains-log-").toFile()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber("ignored")

        controller(encoder, transcriber).processCapture(
            CaptainsLogChannel(saveVoice = true, saveText = false),
            base,
            shortArrayOf(1, 2),
            16_000,
        )

        assertEquals(1, encoder.callCount)
        assertEquals(0, transcriber.callCount)
        assertTrue(File(base, "2026/2026-06/2026-06-24/recordings/log-2026-06-24_14-30-00.ogg").isFile)
        assertFalse(File(base, "2026/2026-06/2026-06-24/log-2026-06-24.md").exists())
    }

    @Test
    fun transcriptionFailureWritesPlaceholderAndKeepsAudio() = runTest {
        val base = createTempDirectory(prefix = "captains-log-").toFile()
        val encoder = FakeEncoder()
        val transcriber = FakeTranscriber(error = IllegalStateException("stt failed"))

        controller(encoder, transcriber).processCapture(
            CaptainsLogChannel(saveVoice = true, saveText = true),
            base,
            shortArrayOf(1, 2),
            16_000,
        )

        assertTrue(File(base, "2026/2026-06/2026-06-24/recordings/log-2026-06-24_14-30-00.ogg").isFile)
        val log = File(base, "2026/2026-06/2026-06-24/log-2026-06-24.md").readText()
        assertTrue(log.contains("[Transcription failed: stt failed]"))
        assertTrue(log.contains("[Source recording](recordings/log-2026-06-24_14-30-00.ogg)"))
    }

    @Test
    fun encodingFailureStillWritesMarkdownNote() = runTest {
        val base = createTempDirectory(prefix = "captains-log-").toFile()
        val encoder = FakeEncoder(error = IllegalStateException("codec failed"))
        val transcriber = FakeTranscriber("hello")

        controller(encoder, transcriber).processCapture(
            CaptainsLogChannel(saveVoice = true, saveText = true),
            base,
            shortArrayOf(1, 2),
            16_000,
        )

        val log = File(base, "2026/2026-06/2026-06-24/log-2026-06-24.md").readText()
        assertTrue(log.contains("hello"))
        assertTrue(log.contains("Recording failed."))
    }

    private fun controller(encoder: AudioEncoder, transcriber: PcmTranscriber): CaptainsLogController =
        CaptainsLogController(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            encoder = encoder,
            transcriber = transcriber,
            clock = Clock.fixed(Instant.parse("2026-06-24T14:30:00Z"), ZoneOffset.UTC),
        )

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
