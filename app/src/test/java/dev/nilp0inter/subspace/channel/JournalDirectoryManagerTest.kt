package dev.nilp0inter.subspace.channel

import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class JournalDirectoryManagerTest {
    @Test
    fun pathsForTimestampUseDateHierarchyAndSafeFileNames() {
        val paths = JournalDirectoryManager().pathsFor(
            baseDirectory = File("/logs"),
            timestamp = LocalDateTime.of(2026, 6, 24, 14, 30, 0),
        )

        assertEquals(File("/logs/2026/2026-06/2026-06-24"), paths.dayDirectory)
        assertEquals(File("/logs/2026/2026-06/2026-06-24/recordings"), paths.recordingsDirectory)
        assertEquals(File("/logs/2026/2026-06/2026-06-24/recordings/journal-2026-06-24_14-30-00.ogg"), paths.recordingFile)
        assertEquals(File("/logs/2026/2026-06/2026-06-24/journal-2026-06-24.md"), paths.markdownFile)
        assertEquals("recordings/journal-2026-06-24_14-30-00.ogg", paths.relativeRecordingLink)
    }
}
