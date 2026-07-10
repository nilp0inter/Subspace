package dev.nilp0inter.subspace.model

sealed interface KeyboardStatus {
    data object Idle : KeyboardStatus
    data object WaitingForAudio : KeyboardStatus
    data object Recording : KeyboardStatus
    data object MaxDurationReached : KeyboardStatus
    data object Transcribing : KeyboardStatus
    data object Typing : KeyboardStatus
    data class Done(val text: String) : KeyboardStatus
    data object EmptyAudio : KeyboardStatus
    data object Cancelled : KeyboardStatus
    data class Error(val reason: String) : KeyboardStatus
}

fun KeyboardStatus.displayText(): String = when (this) {
    KeyboardStatus.Idle -> "Idle"
    KeyboardStatus.WaitingForAudio -> "Waiting for audio"
    KeyboardStatus.Recording -> "Recording"
    KeyboardStatus.MaxDurationReached -> "Max duration reached"
    KeyboardStatus.Transcribing -> "Transcribing"
    KeyboardStatus.Typing -> "Typing"
    is KeyboardStatus.Done -> text
    KeyboardStatus.EmptyAudio -> "Empty audio"
    KeyboardStatus.Cancelled -> "Cancelled"
    is KeyboardStatus.Error -> "Error: $reason"
}
