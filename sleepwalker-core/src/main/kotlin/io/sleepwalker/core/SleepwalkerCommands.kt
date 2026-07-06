package io.sleepwalker.core

import io.sleepwalker.core.protocol.Frame
import io.sleepwalker.core.protocol.Opcodes
import io.sleepwalker.core.protocol.Usages
import io.sleepwalker.core.protocol.HidUsage

/**
 * High-level command builder for the sleepwalker core library.
 *
 * Encodes symbolic HID commands into protocol frames with a sequence id.
 * The app service layer allocates sequence ids and owns the BLE connection.
 */
class SleepwalkerCommands {
    @Volatile private var nextSeq: Int = 1

    fun nextSeqId(): Int = synchronized(this) {
        val s = nextSeq
        nextSeq = (nextSeq + 1) and 0xFFFF
        if (nextSeq == 0) nextSeq = 1
        s
    }

    fun arm(seqId: Int = nextSeqId()): ByteArray =
        Frame.encode(seqId, Opcodes.ARM)

    fun disarm(seqId: Int = nextSeqId()): ByteArray =
        Frame.encode(seqId, Opcodes.DISARM)

    fun kill(seqId: Int = nextSeqId()): ByteArray =
        Frame.encode(seqId, Opcodes.KILL)

    fun releaseAll(seqId: Int = nextSeqId()): ByteArray =
        Frame.encode(seqId, Opcodes.RELEASE_ALL)

    fun keyTap(usage: HidUsage, seqId: Int = nextSeqId()): ByteArray =
        Frame.encode(seqId, Opcodes.KEY_TAP, byteArrayOf(usage.usbUsage.toByte()))

    fun keyDown(usage: HidUsage, seqId: Int = nextSeqId()): ByteArray =
        Frame.encode(seqId, Opcodes.KEY_DOWN, byteArrayOf(usage.usbUsage.toByte()))

    fun keyUp(usage: HidUsage, seqId: Int = nextSeqId()): ByteArray =
        Frame.encode(seqId, Opcodes.KEY_UP, byteArrayOf(usage.usbUsage.toByte()))
}