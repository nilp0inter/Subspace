package dev.nilp0inter.subspace.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBrowseEntryProjectionTest {
    @Test
    fun projectsActiveChannelAsActiveKind() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = "/storage/emulated/0/Subspace"),
            activeChannelId = JournalChannel.ID,
        )

        val entries = projectChannelBrowseEntries(state)

        assertEquals(3, entries.size)
        val journal = entries.first { it.id == JournalChannel.ID }
        val debug = entries.first { it.id == DebugChannel.ID }
        val keyboard = entries.first { it.id == KeyboardChannel.ID }
        assertEquals(ChannelStatusKind.Active, journal.statusKind)
        assertEquals(ChannelStatusKind.Ready, debug.statusKind)
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun projectsReadyChannelAsReadyWhenNotActive() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = "/storage/emulated/0/Subspace"),
            activeChannelId = DebugChannel.ID,
        )

        val entries = projectChannelBrowseEntries(state)

        val journal = entries.first { it.id == JournalChannel.ID }
        val debug = entries.first { it.id == DebugChannel.ID }
        val keyboard = entries.first { it.id == KeyboardChannel.ID }
        assertEquals(ChannelStatusKind.Ready, journal.statusKind)
        assertEquals(ChannelStatusKind.Active, debug.statusKind)
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun projectsUnconfiguredChannelAsStandby() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = null),
            activeChannelId = DebugChannel.ID,
        )

        val entries = projectChannelBrowseEntries(state)

        val journal = entries.first { it.id == JournalChannel.ID }
        val keyboard = entries.first { it.id == KeyboardChannel.ID }
        assertEquals(ChannelStatusKind.Standby, journal.statusKind)
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun projectsActiveChannelStandbyWhenUnconfigured() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = null),
            activeChannelId = JournalChannel.ID,
        )

        val entries = projectChannelBrowseEntries(state)

        val journal = entries.first { it.id == JournalChannel.ID }
        assertEquals(ChannelStatusKind.Active, journal.statusKind)
        val debug = entries.first { it.id == DebugChannel.ID }
        assertEquals(ChannelStatusKind.Ready, debug.statusKind)
        val keyboard = entries.first { it.id == KeyboardChannel.ID }
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun orderingIsJournalThenDebugByOrderIndex() {
        val state = AppState()

        val entries = projectChannelBrowseEntries(state)

        assertEquals(listOf(JournalChannel.ID, DebugChannel.ID, KeyboardChannel.ID), entries.map { it.id })
    }

    @Test
    fun pendingCountDefaultsToZeroWhenMapMissingChannel() {
        val state = AppState()

        val entries = projectChannelBrowseEntries(state, pendingCounts = emptyMap())

        entries.forEach { assertEquals(0, it.pendingCount) }
    }

    @Test
    fun pendingCountPulledFromMapWhenSupplied() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = "/subspace"),
            activeChannelId = DebugChannel.ID,
        )

        val entries = projectChannelBrowseEntries(
            state,
            pendingCounts = mapOf(JournalChannel.ID to 5, DebugChannel.ID to 2),
        )

        assertEquals(5, entries.first { it.id == JournalChannel.ID }.pendingCount)
        assertEquals(2, entries.first { it.id == DebugChannel.ID }.pendingCount)
    }

    @Test
    fun orderedChannelIdsSharesProjectionOrder() {
        val state = AppState()

        val ids = orderedChannelIds(state)

        assertEquals(projectChannelBrowseEntries(state).map { it.id }, ids)
        assertEquals(listOf(JournalChannel.ID, DebugChannel.ID, KeyboardChannel.ID), ids)
    }

    @Test
    fun channelOrderIndexesAreStable() {
        val state = AppState()
        val channels = listOf(state.journal, state.debugChannel, state.keyboard)

        assertEquals(0, channels.first { it is JournalChannel }.orderIndex)
        assertEquals(1, channels.first { it is DebugChannel }.orderIndex)
        assertEquals(2, channels.first { it is KeyboardChannel }.orderIndex)
        // Re-running projection gives the same order — stable across calls.
        val first = projectChannelBrowseEntries(state).map { it.id }
        val second = projectChannelBrowseEntries(state).map { it.id }
        assertEquals(first, second)
    }

    @Test
    fun selectChannelByOffsetSaturatesWithoutWraparound() {
        val ordered = listOf(JournalChannel.ID, DebugChannel.ID, KeyboardChannel.ID)

        // Negative offset past first index saturates at first — no wraparound.
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, JournalChannel.ID, -1))
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, JournalChannel.ID, -10))
        // Positive offset past last index saturates at last — no wraparound.
        assertEquals(KeyboardChannel.ID, selectChannelByOffset(ordered, KeyboardChannel.ID, +1))
        assertEquals(KeyboardChannel.ID, selectChannelByOffset(ordered, KeyboardChannel.ID, +10))
        // Standard advance.
        assertEquals(DebugChannel.ID, selectChannelByOffset(ordered, JournalChannel.ID, +1))
        assertEquals(KeyboardChannel.ID, selectChannelByOffset(ordered, DebugChannel.ID, +1))
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, DebugChannel.ID, -1))
    }

    @Test
    fun selectChannelByOffsetReturnsNullForEmptyChannelList() {
        val empty = emptyList<String>()
        assertEquals(null, selectChannelByOffset(empty, "anything", +1))
        assertEquals(null, selectChannelByOffset(empty, "anything", -1))
    }

    @Test
    fun selectChannelByOffsetSaturatesAtBoundsWhenCurrentIdUnknown() {
        val ordered = listOf(JournalChannel.ID, DebugChannel.ID, KeyboardChannel.ID)

        // Unknown id defaults to the zeroth channel then offsets from there.
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, "missing", 0))
        assertEquals(DebugChannel.ID, selectChannelByOffset(ordered, "missing", +1))
        assertEquals(KeyboardChannel.ID, selectChannelByOffset(ordered, "missing", +2))
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, "missing", -10))
    }

    @Test
    fun projectionNameUsesChannelDisplayName() {
        val state = AppState()

        val entries = projectChannelBrowseEntries(state)

        assertTrue(entries.any { it.name == JournalChannel.NAME })
        assertTrue(entries.any { it.name == DebugChannel.NAME })
        assertTrue(entries.any { it.name == KeyboardChannel.NAME })
    }
}