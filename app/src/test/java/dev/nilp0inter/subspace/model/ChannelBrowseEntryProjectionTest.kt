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
        assertEquals(ChannelStatusKind.Active, journal.statusKind)
        assertEquals(ChannelStatusKind.Ready, debug.statusKind)
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
        assertEquals(ChannelStatusKind.Ready, journal.statusKind)
        assertEquals(ChannelStatusKind.Active, debug.statusKind)
    }

    @Test
    fun projectsUnconfiguredChannelAsStandby() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = null),
            activeChannelId = DebugChannel.ID,
        )

        val entries = projectChannelBrowseEntries(state)

        val journal = entries.first { it.id == JournalChannel.ID }
        assertEquals(ChannelStatusKind.Standby, journal.statusKind)
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
    }

    @Test
    fun orderingIsJournalThenDebugByOrderIndex() {
        val state = AppState()

        val entries = projectChannelBrowseEntries(state)

        assertEquals(listOf(JournalChannel.ID, WebhookChannel.ID, DebugChannel.ID), entries.map { it.id })
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
            pendingCounts = mapOf(JournalChannel.ID to 5, WebhookChannel.ID to 3, DebugChannel.ID to 2),
        )

        assertEquals(5, entries.first { it.id == JournalChannel.ID }.pendingCount)
        assertEquals(3, entries.first { it.id == WebhookChannel.ID }.pendingCount)
        assertEquals(2, entries.first { it.id == DebugChannel.ID }.pendingCount)
    }

    @Test
    fun orderedChannelIdsSharesProjectionOrder() {
        val state = AppState()

        val ids = orderedChannelIds(state)

        assertEquals(projectChannelBrowseEntries(state).map { it.id }, ids)
        assertEquals(listOf(JournalChannel.ID, WebhookChannel.ID, DebugChannel.ID), ids)
    }

    @Test
    fun channelOrderIndexesAreStable() {
        val state = AppState()
        val channels = listOf(state.journal, state.webhookChannel, state.debugChannel)

        assertEquals(0, channels.first { it is JournalChannel }.orderIndex)
        assertEquals(1, channels.first { it is WebhookChannel }.orderIndex)
        assertEquals(2, channels.first { it is DebugChannel }.orderIndex)
        // Re-running projection gives the same order — stable across calls.
        val first = projectChannelBrowseEntries(state).map { it.id }
        val second = projectChannelBrowseEntries(state).map { it.id }
        assertEquals(first, second)
    }

    @Test
    fun selectChannelByOffsetSaturatesWithoutWraparound() {
        val ordered = listOf(JournalChannel.ID, WebhookChannel.ID, DebugChannel.ID)

        // Negative offset past first index saturates at first — no wraparound.
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, JournalChannel.ID, -1))
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, JournalChannel.ID, -10))
        // Positive offset past last index saturates at last — no wraparound.
        assertEquals(DebugChannel.ID, selectChannelByOffset(ordered, DebugChannel.ID, +1))
        assertEquals(DebugChannel.ID, selectChannelByOffset(ordered, DebugChannel.ID, +10))
        // Standard advance.
        assertEquals(WebhookChannel.ID, selectChannelByOffset(ordered, JournalChannel.ID, +1))
        assertEquals(WebhookChannel.ID, selectChannelByOffset(ordered, DebugChannel.ID, -1))
    }

    @Test
    fun selectChannelByOffsetReturnsNullForEmptyChannelList() {
        val empty = emptyList<String>()
        assertEquals(null, selectChannelByOffset(empty, "anything", +1))
        assertEquals(null, selectChannelByOffset(empty, "anything", -1))
    }

    @Test
    fun selectChannelByOffsetSaturatesAtBoundsWhenCurrentIdUnknown() {
        val ordered = listOf(JournalChannel.ID, WebhookChannel.ID, DebugChannel.ID)

        // Unknown id defaults to the zeroth channel then offsets from there.
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, "missing", 0))
        assertEquals(WebhookChannel.ID, selectChannelByOffset(ordered, "missing", +1))
        assertEquals(JournalChannel.ID, selectChannelByOffset(ordered, "missing", -10))
    }

    @Test
    fun projectionNameUsesChannelDisplayName() {
        val state = AppState()

        val entries = projectChannelBrowseEntries(state)

        assertTrue(entries.any { it.name == JournalChannel.NAME })
        assertTrue(entries.any { it.name == WebhookChannel.NAME })
        assertTrue(entries.any { it.name == DebugChannel.NAME })
    }
}
