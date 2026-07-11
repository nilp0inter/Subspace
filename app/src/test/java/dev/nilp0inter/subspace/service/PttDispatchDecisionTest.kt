package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PttDispatchDecisionTest {

    private fun mockState(activeChannelId: String, isReady: Boolean = true): AppState {
        val snapshot = ChannelRuntimeSnapshot(
            id = activeChannelId,
            name = "Test Channel",
            kind = ChannelKind.DEBUG,
            enabled = true,
            isReady = isReady,
            executionStatus = ChannelExecutionStatus.IDLE
        )
        return AppState(
            channels = listOf(snapshot),
            activeChannelId = activeChannelId
        )
    }

    @Test
    fun readyActivePhoneTargetDispatchesToSelectedChannel() {
        val state = mockState("debug-channel", isReady = true)
        val decision = decidePttDispatch(state)
        assertEquals(PttDispatchDecision.Dispatch("debug-channel"), decision)
    }

    @Test
    fun notReadyActivePhoneTargetPlaysErrorBeepInsteadOfDispatching() {
        val state = mockState("captains-log", isReady = false)
        val decision = decidePttDispatch(state)
        assertEquals(PttDispatchDecision.ErrorBeep("captains-log"), decision)
    }

    @Test
    fun readyActiveCarTargetDispatchesToSelectedChannel() {
        val state = mockState("debug-channel", isReady = true)
        val decision = decidePttDispatch(state)
        assertEquals(PttDispatchDecision.Dispatch("debug-channel"), decision)
    }

    @Test
    fun notReadyActiveCarTargetPlaysErrorBeepInsteadOfDispatching() {
        val state = mockState("captains-log", isReady = false)
        val decision = decidePttDispatch(state)
        assertEquals(PttDispatchDecision.ErrorBeep("captains-log"), decision)
    }

    @Test
    fun readyActiveKeyboardChannelDispatchesToKeyboard() {
        val state = mockState("keyboard-channel", isReady = true)
        val decision = decidePttDispatch(state)
        assertEquals(PttDispatchDecision.Dispatch("keyboard-channel"), decision)
    }

    @Test
    fun notReadyActiveKeyboardChannelPlaysErrorBeep() {
        val state = mockState("keyboard-channel", isReady = false)
        val decision = decidePttDispatch(state)
        assertEquals(PttDispatchDecision.ErrorBeep("keyboard-channel"), decision)
    }
}
