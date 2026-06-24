package dev.nilp0inter.subspace.channel

import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalEntryPathsTest {
    @Test
    fun pathsForTimestampUseExpectedLayout() {
        val paths = JournalEntryPaths(ZoneOffset.UTC).pathsFor(
            baseDirectory = File("/logs"),
            startedAt = ZonedDateTime.of(2026, 6, 24, 14, 30, 0, 123_000_000, ZoneOffset.UTC),
        )
        assertEquals("/logs/2026/2026-06/2026-06-24", paths.dayDirectory.absolutePath)
        assertEquals(
            "/logs/2026/2026-06/2026-06-24/entries/journal-entry-2026-06-24_14-30-00-123-+0000",
            paths.entryDirectory.absolutePath,
        )
        assertEquals("journal-entry-2026-06-24_14-30-00-123-+0000", paths.stem)
        assertEquals(
            "journal-entry-2026-06-24_14-30-00-123-+0000.metadata.json",
            paths.metadataFile.name,
        )
        assertEquals(
            "journal-entry-2026-06-24_14-30-00-123-+0000.capture.wav",
            paths.captureFile.name,
        )
        assertEquals(
            "journal-entry-2026-06-24_14-30-00-123-+0000.recording.ogg",
            paths.recordingFile.name,
        )
        assertEquals("journal-day-2026-06-24.md", paths.markdownFile.name)
    }

    @Test
    fun preparePathsCreatesEntryDirectory() {
        val base = kotlin.io.path.createTempDirectory(prefix = "journal-").toFile()
        val startedAt = ZonedDateTime.of(2026, 6, 24, 14, 30, 0, 0, ZoneOffset.UTC)
        val paths = JournalEntryPaths(ZoneOffset.UTC).preparePaths(base, startedAt)
        assertTrue(paths.entryDirectory.isDirectory)
    }
}
