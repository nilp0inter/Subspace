package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.DebugMode
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.UnknownChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class PttDispatchDecisionTest {
    @Test
    fun readyActiveTargetDispatchesToSelectedInstance() {
        val debug = DebugChannel(id = "debug-two", mode = DebugMode.STT)
        val state = AppState(channels = listOf(DebugChannel(), debug), activeChannelId = debug.id)

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.Dispatch(debug), decision)
    }

    @Test
    fun notReadyActiveTargetPlaysErrorBeepInsteadOfDispatching() {
        val journal = JournalChannel(baseDirectory = null)
        val state = AppState(channels = listOf(journal, DebugChannel()), activeChannelId = journal.id)

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.ErrorBeep(journal), decision)
    }

    @Test
    fun unknownActiveTypePlaysErrorBeep() {
        val unknown = UnknownChannel(id = "future", typeId = "future-type", name = "Future", position = 0)
        val state = AppState(channels = listOf(unknown), activeChannelId = unknown.id)

        val decision = decidePttDispatch(state)

        assertEquals(PttDispatchDecision.ErrorBeep(unknown), decision)
    }

    @Test
    fun missingActiveTargetReturnsNull() {
        val state = AppState(channels = listOf(DebugChannel()), activeChannelId = "missing")

        val decision = decidePttDispatch(state)

        assertEquals(null, decision)
    }
}
