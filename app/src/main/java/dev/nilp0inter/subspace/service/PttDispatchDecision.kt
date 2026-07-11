package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.ChannelKind

internal sealed interface PttDispatchDecision {
    val channelId: String

    data class Dispatch(override val channelId: String) : PttDispatchDecision
    data class ErrorBeep(override val channelId: String) : PttDispatchDecision
}

internal fun decidePttDispatch(appState: AppState): PttDispatchDecision? {
    val channel = appState.channels.find { it.id == appState.activeChannelId } ?: return null
    val canRecoverKeyboard =
        channel.kind == ChannelKind.KEYBOARD && channel.enabled
    return if (channel.isReady || canRecoverKeyboard) {
        PttDispatchDecision.Dispatch(channel.id)
    } else {
        PttDispatchDecision.ErrorBeep(channel.id)
    }
}
