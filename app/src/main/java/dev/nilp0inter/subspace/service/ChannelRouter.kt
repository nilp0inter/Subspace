package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance

/** Routes audio input session preparation to the selected channel controller. */
interface ChannelRouter {
    suspend fun prepareInput(channelId: String): ChannelInputAcceptance
}
