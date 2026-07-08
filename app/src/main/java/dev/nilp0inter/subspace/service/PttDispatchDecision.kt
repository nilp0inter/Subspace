package dev.nilp0inter.subspace.service

import android.util.Log
import dev.nilp0inter.subspace.model.AppState
import dev.nilp0inter.subspace.model.DebugChannel
import dev.nilp0inter.subspace.model.JournalChannel
import dev.nilp0inter.subspace.model.KeyboardChannel

private const val TAG = "SubspaceRoute"

internal sealed interface PttDispatchDecision {
    val channelId: String

    data class Dispatch(override val channelId: String) : PttDispatchDecision
    data class ErrorBeep(override val channelId: String) : PttDispatchDecision
}

internal fun decidePttDispatch(appState: AppState): PttDispatchDecision? {
    Log.d(TAG, "decidePttDispatch activeChannelId=${appState.activeChannelId}")
    val channel = when (appState.activeChannelId) {
        JournalChannel.ID -> {
            Log.d(TAG, "decidePttDispatch matched JournalChannel")
            appState.journal
        }
        DebugChannel.ID -> {
            Log.d(TAG, "decidePttDispatch matched DebugChannel")
            appState.debugChannel
        }
        KeyboardChannel.ID -> {
            Log.d(TAG, "decidePttDispatch matched KeyboardChannel, isReady=${appState.keyboard.isReady}")
            appState.keyboard
        }
        else -> {
            Log.d(TAG, "decidePttDispatch null because activeChannelId=${appState.activeChannelId}")
            return null
        }
    }

    Log.d(TAG, "decidePttDispatch channel.isReady=${channel.isReady}")
    return if (channel.isReady) {
        PttDispatchDecision.Dispatch(channel.id)
    } else {
        PttDispatchDecision.ErrorBeep(channel.id)
    }
}
