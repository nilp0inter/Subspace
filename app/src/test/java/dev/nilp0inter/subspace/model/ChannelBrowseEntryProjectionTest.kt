package dev.nilp0inter.subspace.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBrowseEntryProjectionTest {
    @Test
    fun projectsActiveChannelAsActiveKind() {
        val journal = JournalChannel(baseDirectory = "/storage/emulated/0/Subspace")
        val state = AppState(channels = listOf(journal, DebugChannel()), activeChannelId = journal.id)

        val entries = projectChannelBrowseEntries(state)

        assertEquals(2, entries.size)
        assertEquals(ChannelStatusKind.Active, entries.first { it.id == journal.id }.statusKind)
        assertEquals(ChannelStatusKind.Ready, entries.first { it.id == DebugChannel.ID }.statusKind)
    }

    @Test
    fun projectsReadyChannelAsReadyWhenNotActive() {
        val journal = JournalChannel(baseDirectory = "/storage/emulated/0/Subspace")
        val debug = DebugChannel()
        val state = AppState(channels = listOf(journal, debug), activeChannelId = debug.id)

        val entries = projectChannelBrowseEntries(state)

        assertEquals(ChannelStatusKind.Ready, entries.first { it.id == journal.id }.statusKind)
        assertEquals(ChannelStatusKind.Active, entries.first { it.id == debug.id }.statusKind)
    }

    @Test
    fun projectsUnconfiguredChannelAsStandby() {
        val journal = JournalChannel(baseDirectory = null)
        val debug = DebugChannel()
        val state = AppState(channels = listOf(journal, debug), activeChannelId = debug.id)

        val entries = projectChannelBrowseEntries(state)

        assertEquals(ChannelStatusKind.Standby, entries.first { it.id == journal.id }.statusKind)
    }

    @Test
    fun activeUnconfiguredChannelStillProjectsActive() {
        val journal = JournalChannel(baseDirectory = null)
        val debug = DebugChannel()
        val state = AppState(channels = listOf(journal, debug), activeChannelId = journal.id)

        val entries = projectChannelBrowseEntries(state)

        assertEquals(ChannelStatusKind.Active, entries.first { it.id == journal.id }.statusKind)
        assertEquals(ChannelStatusKind.Ready, entries.first { it.id == debug.id }.statusKind)
    }

    @Test
    fun projectionUsesConfiguredChannelOrder() {
        val channels = normalizeChannelPositions(
            listOf(
                DebugChannel(id = "debug-a", name = "Debug A", position = 4),
                JournalChannel(position = 2),
                DebugChannel(id = "debug-b", name = "Debug B", position = 3),
            ),
        )
        val state = AppState(channels = channels, activeChannelId = channels.first().id)

        val entries = projectChannelBrowseEntries(state)

        assertEquals(listOf("captains-log", "debug-b", "debug-a"), entries.map { it.id })
    }

    @Test
    fun pendingCountPulledFromMapWhenSupplied() {
        val journal = JournalChannel(baseDirectory = "/subspace")
        val debug = DebugChannel(id = "debug-two", name = "Debug Two")
        val state = AppState(channels = listOf(journal, debug), activeChannelId = debug.id)

        val entries = projectChannelBrowseEntries(
            state,
            pendingCounts = mapOf(journal.id to 5, debug.id to 2),
        )

        assertEquals(5, entries.first { it.id == journal.id }.pendingCount)
        assertEquals(2, entries.first { it.id == debug.id }.pendingCount)
    }

    @Test
    fun orderedChannelIdsSharesProjectionOrder() {
        val state = AppState()

        val ids = orderedChannelIds(state)

        assertEquals(projectChannelBrowseEntries(state).map { it.id }, ids)
        assertEquals(listOf(JournalChannel.ID, DebugChannel.ID), ids)
    }

    @Test
    fun selectChannelByOffsetSaturatesWithoutWraparound() {
        val ordered = listOf("journal", "debug-a", "debug-b")

        assertEquals("journal", selectChannelByOffset(ordered, "journal", -10))
        assertEquals("debug-b", selectChannelByOffset(ordered, "debug-b", +10))
        assertEquals("debug-a", selectChannelByOffset(ordered, "journal", +1))
        assertEquals("debug-a", selectChannelByOffset(ordered, "debug-b", -1))
    }

    @Test
    fun selectChannelByOffsetReturnsNullForEmptyChannelList() {
        val empty = emptyList<String>()
        assertEquals(null, selectChannelByOffset(empty, "anything", +1))
        assertEquals(null, selectChannelByOffset(empty, "anything", -1))
    }

    @Test
    fun selectChannelByOffsetSaturatesAtBoundsWhenCurrentIdUnknown() {
        val ordered = listOf(JournalChannel.ID, DebugChannel.ID)

        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, "missing", 0))
        assertEquals(DebugChannel.ID, selectChannelByOffset(ordered, "missing", +1))
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, "missing", -10))
    }

    @Test
    fun projectionNameUsesChannelDisplayName() {
        val state = AppState(
            channels = listOf(
                JournalChannel(name = "Field Notes"),
                DebugChannel(id = "debug-two", name = "Loopback"),
            ),
            activeChannelId = "debug-two",
        )

        val entries = projectChannelBrowseEntries(state)

        assertTrue(entries.any { it.name == "Field Notes" })
        assertTrue(entries.any { it.name == "Loopback" })
    }

    @Test
    fun unknownChannelProjectsAsStandby() {
        val unknown = UnknownChannel(id = "x", typeId = "future", name = "Future", position = 0)
        val state = AppState(channels = listOf(unknown), activeChannelId = "missing")

        val entries = projectChannelBrowseEntries(state)

        assertEquals(ChannelStatusKind.Standby, entries.single().statusKind)
    }
}
