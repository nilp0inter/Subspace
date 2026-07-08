package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ResolvedAudioRoute

/** Routes PTT press/release/release-all to the appropriate channel controller. */
interface ChannelRouter {
    fun onPttPressed(channelId: String, route: ResolvedAudioRoute)
    fun onPttReleased(channelId: String, route: ResolvedAudioRoute)
    fun cancelAndRelease(channelId: String)
}
