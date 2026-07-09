package dev.nilp0inter.subspace.audio

import kotlinx.coroutines.flow.Flow

interface ChannelAudioInputSession {
    val frames: Flow<ShortArray>
    val sampleRate: Int
}

sealed interface ChannelInputEvent {
    data class Started(val session: ChannelAudioInputSession) : ChannelInputEvent
    data class Released(val recording: RecordedPcm) : ChannelInputEvent
    data class Cancelled(val reason: String) : ChannelInputEvent
    data class Failed(val reason: String) : ChannelInputEvent
}

sealed interface ChannelInputResult {
    data object None : ChannelInputResult
    data class Playback(val recording: RecordedPcm) : ChannelInputResult
}

internal class CaptureChannelAudioInputSession(
    private val delegate: CaptureSession,
) : ChannelAudioInputSession {
    override val frames: Flow<ShortArray> = delegate.frames
    override val sampleRate: Int = delegate.sampleRate
}
