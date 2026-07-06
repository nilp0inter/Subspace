package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.KeyboardChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class PttDispatchDecisionTest {
    @Test
    fun readyActivePhoneTargetDispatchesToSelectedChannel() {
        val state = AppState(activeChannelId = DebugChannel.ID)

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.Dispatch(DebugChannel.ID), decision)
    }

    @Test
    fun notReadyActivePhoneTargetPlaysErrorBeepInsteadOfDispatching() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = null),
            activeChannelId = JournalChannel.ID,
        )

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.ErrorBeep(JournalChannel.ID), decision)
    }

    @Test
    fun readyActiveCarTargetDispatchesToSelectedChannel() {
        val state = AppState(activeChannelId = DebugChannel.ID)

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.Dispatch(DebugChannel.ID), decision)
    }

    @Test
    fun notReadyActiveCarTargetPlaysErrorBeepInsteadOfDispatching() {
        val state = AppState(
            journal = JournalChannel(baseDirectory = null),
            activeChannelId = JournalChannel.ID,
        )

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.ErrorBeep(JournalChannel.ID), decision)
    }

    @Test
    fun readyActiveKeyboardChannelDispatchesToKeyboard() {
        val state = AppState(
            keyboard = KeyboardChannel(bridgeConnectedProvider = { true }),
            activeChannelId = KeyboardChannel.ID
        )

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.Dispatch(KeyboardChannel.ID), decision)
    }

    @Test
    fun notReadyActiveKeyboardChannelPlaysErrorBeep() {
        val state = AppState(
            keyboard = KeyboardChannel(bridgeConnectedProvider = { false }),
            activeChannelId = KeyboardChannel.ID
        )

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.ErrorBeep(KeyboardChannel.ID), decision)
    }
}
