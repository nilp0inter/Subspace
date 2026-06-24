package dev.nilp0inter.subspace.channel

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownJournalRendererTest {
    @Test
    fun renderSingleEntryWithTextAndRecording() {
        val dir = createDayDir("2026-06-24")
        val renderer = MarkdownJournalRenderer()

        val entries = listOf(
            JournalEntryMetadata(
                entryId = "journal-entry-2026-06-24_14-30-00-000-0000",
                startedAt = "2026-06-24T14:30:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(
                    state = DerivedTaskStatus.finished,
                    path = "journal-entry-2026-06-24_14-30-00-000-0000.recording.ogg",
                ),
                transcription = DerivedTaskState(
                    state = DerivedTaskStatus.finished,
                    text = "Hello world.",
                ),
            ),
        )

        renderer.render(dir, entries)
        val md = File(dir, "journal-day-2026-06-24.md")
        assertTrue(md.isFile)
        val text = md.readText()
        assertTrue(text.contains("# Journal 2026-06-24"))
        assertTrue(text.contains("## Entry 14-30-00"))
        assertTrue(text.contains("Hello world."))
        assertTrue(text.contains("journal-entry-2026-06-24_14-30-00-000-0000.recording.ogg"))
    }

    @Test
    fun renderMultipleEntriesSortedByStartedAt() {
        val dir = createDayDir("2026-06-24")
        val renderer = MarkdownJournalRenderer()

        val entries = listOf(
            JournalEntryMetadata(
                entryId = "e1",
                startedAt = "2026-06-24T14:00:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, false),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = DerivedTaskStatus.finished, path = "e1.ogg"),
                transcription = null,
            ),
            JournalEntryMetadata(
                entryId = "e2",
                startedAt = "2026-06-24T15:00:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, false),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = DerivedTaskStatus.finished, path = "e2.ogg"),
                transcription = null,
            ),
        )

        renderer.render(dir, entries)
        val md = File(dir, "journal-day-2026-06-24.md")
        val text = md.readText()
        val e1pos = text.indexOf("e1.ogg")
        val e2pos = text.indexOf("e2.ogg")
        assertTrue("e1 should appear before e2", e1pos < e2pos)
    }

    @Test
    fun skippedDerivedTaskOmitsLink() {
        val dir = createDayDir("2026-06-24")
        val renderer = MarkdownJournalRenderer()

        val entries = listOf(
            JournalEntryMetadata(
                entryId = "e1",
                startedAt = "2026-06-24T14:00:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", saveVoice = false, saveText = false),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = DerivedTaskStatus.skipped),
                transcription = null,
            ),
        )

        renderer.render(dir, entries)
        val md = File(dir, "journal-day-2026-06-24.md")
        assertTrue(md.isFile)
        val text = md.readText()
        assertFalse(text.contains(".ogg"))
        assertFalse(text.contains("[Source recording]"))
    }

    @Test
    fun failedTranscriptionShowsPlaceholder() {
        val dir = createDayDir("2026-06-24")
        val renderer = MarkdownJournalRenderer()

        val entries = listOf(
            JournalEntryMetadata(
                entryId = "e1",
                startedAt = "2026-06-24T14:00:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = DerivedTaskStatus.skipped),
                transcription = DerivedTaskState(
                    state = DerivedTaskStatus.failed,
                    error = "stt model crashed",
                ),
            ),
        )

        renderer.render(dir, entries)
        val md = File(dir, "journal-day-2026-06-24.md")
        assertTrue(md.isFile)
        assertTrue(md.readText().contains("[Transcription failed: stt model crashed]"))
    }

    @Test
    fun deletedEntryExcludedFromRender() {
        val dir = createDayDir("2026-06-24")
        val renderer = MarkdownJournalRenderer()

        val entries = listOf(
            JournalEntryMetadata(
                entryId = "e1",
                startedAt = "2026-06-24T14:00:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = DerivedTaskStatus.finished, path = "e1.ogg"),
                transcription = DerivedTaskState(state = DerivedTaskStatus.finished, text = "kept"),
            ),
            JournalEntryMetadata(
                entryId = "e2",
                startedAt = "2026-06-24T15:00:00Z",
                timezoneOffset = "+0000",
                channel = MetadataChannelSnapshot("captains-log", true, true),
                capture = CaptureState(state = CaptureTaskState.finished),
                encoding = DerivedTaskState(state = DerivedTaskStatus.finished, path = "e2.ogg"),
                transcription = DerivedTaskState(state = DerivedTaskStatus.finished, text = "deleted"),
                deletedAt = "2026-06-25T10:00:00Z",
            ),
        )
        val nonDeleted = entries.filter { it.deletedAt == null }

        renderer.render(dir, nonDeleted)
        val md = File(dir, "journal-day-2026-06-24.md")
        val text = md.readText()
        assertTrue(text.contains("kept"))
        assertFalse(text.contains("deleted"))
    }

    private fun createDayDir(dateLabel: String): File {
        val base = createTempDirectory(prefix = "journal-md-").toFile()
        val dir = File(base, dateLabel)
        dir.mkdirs()
        return dir
    }
}
