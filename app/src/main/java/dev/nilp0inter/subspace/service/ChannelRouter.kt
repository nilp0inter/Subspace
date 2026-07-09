package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelAudioInputSession
import dev.nilp0inter.subspace.audio.ChannelInputResult
import dev.nilp0inter.subspace.audio.RecordedPcm

/** Routes audio input session events to the selected channel controller. */
interface ChannelRouter {
    fun onInputStarted(channelId: String, session: ChannelAudioInputSession)
    suspend fun onInputReleased(channelId: String, recording: RecordedPcm): ChannelInputResult
    fun onInputPlaybackCompleted(channelId: String) {}
    fun onInputCancelled(channelId: String, reason: String)
    fun onInputFailed(channelId: String, reason: String)
}
