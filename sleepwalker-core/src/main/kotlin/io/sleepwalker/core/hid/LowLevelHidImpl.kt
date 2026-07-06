package io.sleepwalker.core.hid

import io.sleepwalker.core.protocol.Frame
import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.MouseRel

/**
 * Default [LowLevelHid] implementation.
 *
 * Encodes symbolic HID commands into protocol frames with a monotonic
 * sequence id. Pure protocol encoding — no BLE I/O. The app/service
 * layer owns the BLE connection and writes the encoded frame bytes.
 *
 * This is the reusable library surface; the reference app delegates here
 * rather than constructing protocol frames itself.
 */
class LowLevelHidImpl : LowLevelHid {
    @Volatile private var nextSeq: Int = 1

    override fun nextSeqId(): Int = synchronized(this) {
        val s = nextSeq
        nextSeq = (nextSeq + 1) and 0xFFFF
        if (nextSeq == 0) nextSeq = 1
        s
    }

    private fun op(seqId: Int, opcode: Int, payload: ByteArray = ByteArray(0)): LowLevelOp {
        // Validate by encoding; this is the single source of truth for
        // the binary frame layout. We keep the LowLevelOp payload as the
        // raw protocol payload (not the full frame) so callers can
        // inspect the operation before it is framed for BLE.
        Frame.encode(seqId, opcode, payload) // validates ranges / payload size
        return LowLevelOp(opcode = opcode, payload = payload, seqId = seqId)
    }

    override fun arm(seqId: Int): LowLevelOp = op(seqId, io.sleepwalker.core.protocol.Opcodes.ARM)
    override fun disarm(seqId: Int): LowLevelOp = op(seqId, io.sleepwalker.core.protocol.Opcodes.DISARM)
    override fun kill(seqId: Int): LowLevelOp = op(seqId, io.sleepwalker.core.protocol.Opcodes.KILL)
    override fun releaseAll(seqId: Int): LowLevelOp = op(seqId, io.sleepwalker.core.protocol.Opcodes.RELEASE_ALL)

    override fun keyTap(usage: HidUsage, seqId: Int): LowLevelOp =
        op(seqId, io.sleepwalker.core.protocol.Opcodes.KEY_TAP,
            byteArrayOf(usage.usbUsage.toByte()))

    override fun keyDown(usage: HidUsage, seqId: Int): LowLevelOp =
        op(seqId, io.sleepwalker.core.protocol.Opcodes.KEY_DOWN,
            byteArrayOf(usage.usbUsage.toByte()))

    override fun keyUp(usage: HidUsage, seqId: Int): LowLevelOp =
        op(seqId, io.sleepwalker.core.protocol.Opcodes.KEY_UP,
            byteArrayOf(usage.usbUsage.toByte()))

    override fun keyboardTapScript(taps: List<Pair<Byte, Byte>>, seqId: Int): LowLevelOp =
        op(seqId, io.sleepwalker.core.protocol.Opcodes.KEYBOARD_TAP_SCRIPT,
            ByteArray(1 + taps.size * 2).apply {
                this[0] = taps.size.toByte()
                taps.forEachIndexed { i, tap ->
                    this[1 + i * 2] = tap.first
                    this[2 + i * 2] = tap.second
                }
            })

    override fun mouseRelReport(
        buttons: Int, dx: Int, dy: Int,
        wheel: Int, pan: Int, seqId: Int,
    ): LowLevelOp = op(
        seqId, io.sleepwalker.core.protocol.Opcodes.MOUSE_REL_REPORT,
        MouseRel.encode(buttons, dx, dy, wheel, pan),
    )
}

/**
 * Encode a [LowLevelOp] into the wire frame bytes for BLE transmission.
 *
 * Convenience extension so the app/service layer can frame a planned
 * operation without importing the protocol layer directly.
 */
fun LowLevelOp.toFrameBytes(): ByteArray =
    Frame.encode(seqId, opcode, payload)
