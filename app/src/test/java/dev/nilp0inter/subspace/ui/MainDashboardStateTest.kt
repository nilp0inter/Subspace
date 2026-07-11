package dev.nilp0inter.subspace.ui

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelKind
import dev.nilp0inter.subspace.service.ChannelExecutionStatus
import dev.nilp0inter.subspace.service.ChannelRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainDashboardStateTest {

    @Test
    fun dynamicCardOrderMatchesAppStateSnapshots() {
        val s1 = ChannelRuntimeSnapshot("c1", "J1", ChannelKind.JOURNAL, true, true, ChannelExecutionStatus.IDLE)
        val s2 = ChannelRuntimeSnapshot("c2", "D1", ChannelKind.DEBUG, true, true, ChannelExecutionStatus.IDLE)
        val s3 = ChannelRuntimeSnapshot("c3", "K1", ChannelKind.KEYBOARD, true, true, ChannelExecutionStatus.IDLE)
        
        // Scenario A: J -> D -> K
        val stateA = AppState(channels = listOf(s1, s2, s3))
        assertEquals(3, stateA.channels.size)
        assertEquals("c1", stateA.channels[0].id)
        assertEquals("c2", stateA.channels[1].id)
        assertEquals("c3", stateA.channels[2].id)

        // Scenario B: Reordered: K -> J -> D
        val stateB = AppState(channels = listOf(s3, s1, s2))
        assertEquals(3, stateB.channels.size)
        assertEquals("c3", stateB.channels[0].id)
        assertEquals("c1", stateB.channels[1].id)
        assertEquals("c2", stateB.channels[2].id)
    }

    @Test
    fun multipleSameKindCardsAreSupportedIndependently() {
        val d1 = ChannelRuntimeSnapshot("c1", "Debug Main", ChannelKind.DEBUG, true, true, ChannelExecutionStatus.IDLE)
        val d2 = ChannelRuntimeSnapshot("c2", "Debug Alternate", ChannelKind.DEBUG, true, true, ChannelExecutionStatus.IDLE)

        val state = AppState(
            channels = listOf(d1, d2),
            activeChannelId = "c2"
        )

        assertEquals(2, state.channels.size)
        
        // Assert they have different IDs and names despite same kind
        assertEquals("c1", state.channels[0].id)
        assertEquals("Debug Main", state.channels[0].name)
        assertEquals(ChannelKind.DEBUG, state.channels[0].kind)

        assertEquals("c2", state.channels[1].id)
        assertEquals("Debug Alternate", state.channels[1].name)
        assertEquals(ChannelKind.DEBUG, state.channels[1].kind)

        // Verify active selection is bound to c2, not debug kind generically
        assertEquals("c2", state.activeChannelId)
    }

    @Test
    fun phonePttTargetingAndReadiness() {
        val s1 = ChannelRuntimeSnapshot("c1", "J1", ChannelKind.JOURNAL, true, true, ChannelExecutionStatus.IDLE)
        val s2 = ChannelRuntimeSnapshot("c2", "J2", ChannelKind.JOURNAL, true, false, ChannelExecutionStatus.IDLE)

        val state = AppState(
            channels = listOf(s1, s2),
            activeChannelId = "c1"
        )

        // c1 is active and ready
        assertEquals("c1", state.activeChannelId)
        assertTrue(state.channels.first { it.id == "c1" }.isReady)

        // c2 is inactive and not ready
        assertFalse(state.channels.first { it.id == "c2" }.isReady)
    }
}
