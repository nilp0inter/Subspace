package dev.nilp0inter.subspace.channel

import dev.nilp0inter.subspace.audio.AudioEncoder
import dev.nilp0inter.subspace.audio.PcmTranscriber
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalRecoveryTest {
    @Test
    fun staleRecordingMarkedAbandoned() = runTest {
        val base = createTempDir()
        val pathGen = JournalEntryPaths(java.time.ZoneOffset.UTC)
        val startedAt = java.time.ZonedDateTime.of(2026, 6, 24, 14, 30, 0, 0, java.time.ZoneOffset.UTC)
        val paths = pathGen.preparePaths(base, startedAt)
        val store = JournalMetadataStore()

        store.write(
            JournalEntryMetadata(
                entryId = paths.stem,
                startedAt = startedAt.toString(),
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = CaptureTaskState.recording),
            ),
            paths.metadataFile,
        )

        val controller = JournalController(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            encoder = NoopEncoder(),
            transcriber = NoopTranscriber(),
            dispatcher = Dispatchers.Unconfined,
        )
        val job = controller.runRecovery(base)
        runBlocking { job.join() }

        val recovered = store.read(paths.metadataFile)!!
        assertEquals(CaptureTaskState.abandoned, recovered.capture.state)
    }

    @Test
    fun staleRunningEncodingConvertedToPending() = runTest {
        val base = createTempDir()
        val pathGen = JournalEntryPaths(java.time.ZoneOffset.UTC)
        val startedAt = java.time.ZonedDateTime.of(2026, 6, 24, 14, 30, 0, 0, java.time.ZoneOffset.UTC)
        val paths = pathGen.preparePaths(base, startedAt)
        val store = JournalMetadataStore()

        store.write(
            JournalEntryMetadata(
                entryId = paths.stem,
                startedAt = startedAt.toString(),
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = DerivedTaskStatus.running),
                transcription = DerivedTaskState(state = DerivedTaskStatus.running),
            ),
            paths.metadataFile,
        )

        val controller = JournalController(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            encoder = NoopEncoder(),
            transcriber = NoopTranscriber(),
            dispatcher = Dispatchers.Unconfined,
        )
        val job = controller.runRecovery(base)
        runBlocking { job.join() }

        val recovered = store.read(paths.metadataFile)!!
        assertEquals(DerivedTaskStatus.pending, recovered.encoding?.state)
        assertEquals(DerivedTaskStatus.pending, recovered.transcription?.state)
    }

    private fun createTempDir(): File = createTempDirectory(prefix = "journal-rec-").toFile()

    private class NoopEncoder : AudioEncoder {
        override suspend fun encode(pcm: ShortArray, outputFile: File, sampleRate: Int): Result<File> =
            Result.failure(IllegalStateException("not expected"))
    }

    private class NoopTranscriber : PcmTranscriber {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String =
            throw IllegalStateException("not expected")
    }
}
