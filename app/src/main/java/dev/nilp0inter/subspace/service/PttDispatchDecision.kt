package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.Channel

internal sealed interface PttDispatchDecision {
    val channel: Channel
    val channelId: String get() = channel.id

    data class Dispatch(override val channel: Channel) : PttDispatchDecision
    data class ErrorBeep(override val channel: Channel) : PttDispatchDecision
}

internal fun decidePttDispatch(appState: AppState): PttDispatchDecision? {
    val channel = appState.activeChannel() ?: return null
    return if (channel.isReady) {
        PttDispatchDecision.Dispatch(channel)
    } else {
        PttDispatchDecision.ErrorBeep(channel)
    }
}
