package dev.nilp0inter.subspace.model

import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBrowseEntryProjectionTest {

    private fun createMockState(
        journalReady: Boolean = true,
        debugReady: Boolean = true,
        keyboardReady: Boolean = false,
        activeChannelId: String = "captains-log"
    ): AppState {
        val journal = ChannelRuntimeSnapshot(
            id = "captains-log",
            name = "Journal",
            kind = ChannelKind.JOURNAL,
            enabled = true,
            isReady = journalReady,
            executionStatus = ChannelExecutionStatus.IDLE
        )
        val debug = ChannelRuntimeSnapshot(
            id = "debug-channel",
            name = "Debug Channel",
            kind = ChannelKind.DEBUG,
            enabled = true,
            isReady = debugReady,
            executionStatus = ChannelExecutionStatus.IDLE
        )
        val keyboard = ChannelRuntimeSnapshot(
            id = "keyboard-channel",
            name = "Keyboard Channel",
            kind = ChannelKind.KEYBOARD,
            enabled = true,
            isReady = keyboardReady,
            executionStatus = ChannelExecutionStatus.IDLE
        )
        return AppState(
            channels = listOf(journal, debug, keyboard),
            activeChannelId = activeChannelId
        )
    }

    @Test
    fun projectsActiveChannelAsActiveKind() {
        val state = createMockState(
            journalReady = true,
            activeChannelId = "captains-log"
        )

        val entries = projectChannelBrowseEntries(state)

        assertEquals(3, entries.size)
        val journal = entries.first { it.id == "captains-log" }
        val debug = entries.first { it.id == "debug-channel" }
        val keyboard = entries.first { it.id == "keyboard-channel" }
        
        assertEquals(ChannelStatusKind.Active, journal.statusKind)
        assertEquals(ChannelStatusKind.Ready, debug.statusKind)
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun projectsReadyChannelAsReadyWhenNotActive() {
        val state = createMockState(
            journalReady = true,
            activeChannelId = "debug-channel"
        )

        val entries = projectChannelBrowseEntries(state)

        val journal = entries.first { it.id == "captains-log" }
        val debug = entries.first { it.id == "debug-channel" }
        val keyboard = entries.first { it.id == "keyboard-channel" }
        
        assertEquals(ChannelStatusKind.Ready, journal.statusKind)
        assertEquals(ChannelStatusKind.Active, debug.statusKind)
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun projectsUnconfiguredChannelAsStandby() {
        val state = createMockState(
            journalReady = false,
            activeChannelId = "debug-channel"
        )

        val entries = projectChannelBrowseEntries(state)

        val journal = entries.first { it.id == "captains-log" }
        val keyboard = entries.first { it.id == "keyboard-channel" }
        assertEquals(ChannelStatusKind.Standby, journal.statusKind)
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun projectsActiveChannelStandbyWhenUnconfigured() {
        // Wait, under active mutual exclusivity: "Active channel id matches activeChannelId"
        // Active is ALWAYS Active status, even if unready. Let's verify that.
        val state = createMockState(
            journalReady = false,
            activeChannelId = "captains-log"
        )

        val entries = projectChannelBrowseEntries(state)

        val journal = entries.first { it.id == "captains-log" }
        assertEquals(ChannelStatusKind.Active, journal.statusKind)
        val debug = entries.first { it.id == "debug-channel" }
        assertEquals(ChannelStatusKind.Ready, debug.statusKind)
        val keyboard = entries.first { it.id == "keyboard-channel" }
        assertEquals(ChannelStatusKind.Standby, keyboard.statusKind)
    }

    @Test
    fun orderingMatchesCatalogueSnapshotOrder() {
        val state = createMockState()

        val entries = projectChannelBrowseEntries(state)

        assertEquals(listOf("captains-log", "debug-channel", "keyboard-channel"), entries.map { it.id })
    }

    @Test
    fun pendingCountDefaultsToZeroWhenMapMissingChannel() {
        val state = createMockState()

        val entries = projectChannelBrowseEntries(state, pendingCounts = emptyMap())

        entries.forEach { assertEquals(0, it.pendingCount) }
    }

    @Test
    fun pendingCountPulledFromMapWhenSupplied() {
        val state = createMockState(activeChannelId = "debug-channel")

        val entries = projectChannelBrowseEntries(
            state,
            pendingCounts = mapOf("captains-log" to 5, "debug-channel" to 2),
        )

        assertEquals(5, entries.first { it.id == "captains-log" }.pendingCount)
        assertEquals(2, entries.first { it.id == "debug-channel" }.pendingCount)
    }

    @Test
    fun orderedChannelIdsSharesProjectionOrder() {
        val state = createMockState()

        val ids = orderedChannelIds(state)

        assertEquals(projectChannelBrowseEntries(state).map { it.id }, ids)
        assertEquals(listOf("captains-log", "debug-channel", "keyboard-channel"), ids)
    }

    @Test
    fun selectChannelByOffsetSaturatesWithoutWraparound() {
        val ordered = listOf("captains-log", "debug-channel", "keyboard-channel")

        assertEquals("captains-log", selectChannelByOffset(ordered, "captains-log", -1))
        assertEquals("captains-log", selectChannelByOffset(ordered, "captains-log", -10))
        assertEquals("keyboard-channel", selectChannelByOffset(ordered, "keyboard-channel", +1))
        assertEquals("keyboard-channel", selectChannelByOffset(ordered, "keyboard-channel", +10))
        assertEquals("debug-channel", selectChannelByOffset(ordered, "captains-log", +1))
        assertEquals("keyboard-channel", selectChannelByOffset(ordered, "debug-channel", +1))
        assertEquals("captains-log", selectChannelByOffset(ordered, "debug-channel", -1))
    }

    @Test
    fun selectChannelByOffsetReturnsNullForEmptyChannelList() {
        val empty = emptyList<String>()
        assertEquals(null, selectChannelByOffset(empty, "anything", +1))
        assertEquals(null, selectChannelByOffset(empty, "anything", -1))
    }

    @Test
    fun selectChannelByOffsetSaturatesAtBoundsWhenCurrentIdUnknown() {
        val ordered = listOf("captains-log", "debug-channel", "keyboard-channel")

        assertEquals("captains-log", selectChannelByOffset(ordered, "missing", 0))
        assertEquals("debug-channel", selectChannelByOffset(ordered, "missing", +1))
        assertEquals("keyboard-channel", selectChannelByOffset(ordered, "missing", +2))
        assertEquals("captains-log", selectChannelByOffset(ordered, "missing", -10))
    }

    @Test
    fun projectionNameUsesChannelDisplayName() {
        val state = createMockState()

        val entries = projectChannelBrowseEntries(state)

        assertTrue(entries.any { it.name == "Journal" })
        assertTrue(entries.any { it.name == "Debug Channel" })
        assertTrue(entries.any { it.name == "Keyboard Channel" })
    }
}
