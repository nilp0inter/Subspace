package dev.nilp0inter.subspace.service

import dev.nilp0inter.subspace.audio.ChannelInputAcceptance

/** Routes audio input session preparation to the selected channel controller. */
interface ChannelRouter {
    fun prepareInput(channelId: String): ChannelInputAcceptance
}
