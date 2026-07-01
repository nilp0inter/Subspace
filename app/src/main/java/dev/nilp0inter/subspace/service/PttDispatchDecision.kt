package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.WebhookChannel

internal sealed interface PttDispatchDecision {
    val channelId: String

    data class Dispatch(override val channelId: String) : PttDispatchDecision
    data class ErrorBeep(override val channelId: String) : PttDispatchDecision
}

internal fun decidePttDispatch(appState: AppState): PttDispatchDecision? {
    val channel = when (appState.activeChannelId) {
        JournalChannel.ID -> appState.journal
        WebhookChannel.ID -> appState.webhookChannel
        DebugChannel.ID -> appState.debugChannel
        else -> return null
    }

    return if (channel.isReady) {
        PttDispatchDecision.Dispatch(channel.id)
    } else {
        PttDispatchDecision.ErrorBeep(channel.id)
    }
}
