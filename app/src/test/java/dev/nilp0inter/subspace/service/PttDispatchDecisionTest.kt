package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PttDispatchDecisionTest {

    private fun mockState(
        activeChannelId: String,
        isReady: Boolean,
        kind: ChannelKind,
        enabled: Boolean,
    ): AppState {
        val snapshot = ChannelRuntimeSnapshot(
            id = activeChannelId,
            name = "Test Channel",
            kind = kind,
            enabled = enabled,
            isReady = isReady,
            executionStatus = ChannelExecutionStatus.IDLE,
        )
        return AppState(
            channels = listOf(snapshot),
            activeChannelId = activeChannelId,
        )
    }

    @Test
    fun readyActiveChannelDispatchesToSelectedChannel() {
        val state = mockState("debug-channel", isReady = true, kind = ChannelKind.DEBUG, enabled = true)

        assertEquals(PttDispatchDecision.Dispatch("debug-channel"), decidePttDispatch(state))
    }

    @Test
    fun disconnectedEnabledKeyboardDispatchesForRecovery() {
        val state = mockState("keyboard-channel", isReady = false, kind = ChannelKind.KEYBOARD, enabled = true)

        assertEquals(PttDispatchDecision.Dispatch("keyboard-channel"), decidePttDispatch(state))
    }

    @Test
    fun disconnectedDisabledKeyboardUsesImmediateProblemFeedback() {
        val state = mockState("keyboard-channel", isReady = false, kind = ChannelKind.KEYBOARD, enabled = false)

        assertEquals(PttDispatchDecision.ErrorBeep("keyboard-channel"), decidePttDispatch(state))
    }

    @Test
    fun notReadyNonKeyboardUsesImmediateProblemFeedback() {
        val state = mockState("journal-channel", isReady = false, kind = ChannelKind.JOURNAL, enabled = true)

        assertEquals(PttDispatchDecision.ErrorBeep("journal-channel"), decidePttDispatch(state))
    }
}
