package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.service.LogEntry
import dev.nilp0inter.subspace.service.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogAnalysisFilterTest {

    private fun entry(level: LogLevel, tag: String, message: String) =
        LogEntry(0L, level, tag, message)

    private val sampleEntries = listOf(
        entry(LogLevel.Debug, "SubspaceRoute", "ROUTE_RESOLVE mode=Work"),
        entry(LogLevel.Info, "SubspaceChannel", "TEXT_OUTPUT_DELIVERY_START"),
        entry(LogLevel.Warn, "SubspaceRoute", "SCO_RELEASE_WARM_SKIP"),
        entry(LogLevel.Error, "SubspaceRoute", "SCO_ACQUIRE_FAIL"),
        entry(LogLevel.Debug, "SubspaceChannel", "KEYBOARD_TRANSCRIPTION_RESULT"),
        entry(LogLevel.Info, "SubspacePttService", "CHANNEL_SELECT"),
    )

    @Test
    fun noFiltersReturnsAllEntries() {
        val result = filterLogEntries(sampleEntries, "", emptySet(), null)
        assertEquals(6, result.size)
    }

    @Test
    fun levelFilterShowsOnlySelectedLevels() {
        val result = filterLogEntries(sampleEntries, "", setOf(LogLevel.Warn, LogLevel.Error), null)
        assertEquals(2, result.size)
        assertEquals(LogLevel.Warn, result[0].level)
        assertEquals(LogLevel.Error, result[1].level)
    }

    @Test
    fun tagFilterShowsOnlySelectedTag() {
        val result = filterLogEntries(sampleEntries, "", emptySet(), "SubspaceRoute")
        assertEquals(3, result.size)
        assertTrue(result.all { it.tag == "SubspaceRoute" })
    }

    @Test
    fun searchQueryMatchesMessageContent() {
        val result = filterLogEntries(sampleEntries, "SCO", emptySet(), null)
        assertEquals(2, result.size)
        assertTrue(result.all { it.message.contains("SCO") })
    }

    @Test
    fun searchQueryMatchesTagContent() {
        val result = filterLogEntries(sampleEntries, "channel", emptySet(), null)
        // Matches tag "SubspaceChannel" (2 entries) + message "CHANNEL_SELECT" (1 entry)
        assertEquals(3, result.size)
    }

    @Test
    fun combinedFiltersIntersect() {
        val result = filterLogEntries(
            sampleEntries,
            "ROUTE",
            setOf(LogLevel.Debug),
            "SubspaceRoute",
        )
        assertEquals(1, result.size)
        assertEquals("ROUTE_RESOLVE mode=Work", result[0].message)
    }

    @Test
    fun searchQueryWithNoMatchesReturnsEmpty() {
        val result = filterLogEntries(sampleEntries, "nonexistent", emptySet(), null)
        assertEquals(0, result.size)
    }

    @Test
    fun levelFilterWithEmptySetReturnsAll() {
        val result = filterLogEntries(sampleEntries, "", emptySet(), null)
        assertEquals(sampleEntries.size, result.size)
    }
}