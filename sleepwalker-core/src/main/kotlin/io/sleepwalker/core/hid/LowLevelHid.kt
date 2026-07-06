package io.sleepwalker.core.hid

import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.Opcodes

/**
 * A single low-level keyboard or mouse operation encoded as a protocol
 * frame payload + opcode. Produced by [LowLevelHid] and [TextPlanner];
 * inspected by callers before execution.
 */
data class LowLevelOp(
    val opcode: Int,
    val payload: ByteArray,
    val seqId: Int,
) {
    val name: String get() = when (opcode) {
        Opcodes.ARM -> "arm"
        Opcodes.DISARM -> "disarm"
        Opcodes.KILL -> "kill"
        Opcodes.RELEASE_ALL -> "release_all"
        Opcodes.KEY_TAP -> "key_tap"
        Opcodes.KEY_DOWN -> "key_down"
        Opcodes.KEY_UP -> "key_up"
        Opcodes.MOUSE_REL_REPORT -> "mouse_rel_report"
        else -> "unknown"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LowLevelOp) return false
        return opcode == other.opcode &&
            seqId == other.seqId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = opcode
        result = 31 * result + seqId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Low-level HID primitive API.
 *
 * Encodes symbolic HID commands into protocol frames with a sequence id.
 * The app service layer allocates sequence ids and owns the BLE
 * connection; this interface produces the bytes to send.
 *
 * Low-level keyboard operations use symbolic USB HID usages (see
 * [io.sleepwalker.core.protocol.Usages]). Low-level relative mouse
 * operations use a raw relative mouse report model with button mask,
 * relative X/Y movement, vertical wheel, and horizontal pan fields.
 */
interface LowLevelHid {
    /** Allocate the next sequence identifier (1..0xFFFF, wrapping). */
    fun nextSeqId(): Int

    /** Arm the firmware safety state. */
    fun arm(seqId: Int = nextSeqId()): LowLevelOp

    /** Disarm the firmware safety state. Releases all keys/buttons. */
    fun disarm(seqId: Int = nextSeqId()): LowLevelOp

    /** Kill the firmware safety state. Forces release-all + DISARMED. */
    fun kill(seqId: Int = nextSeqId()): LowLevelOp

    /** Release all currently held keys/buttons. */
    fun releaseAll(seqId: Int = nextSeqId()): LowLevelOp

    /** Tap a keyboard key (key-down followed by key-up). */
    fun keyTap(usage: HidUsage, seqId: Int = nextSeqId()): LowLevelOp

    /** Press a keyboard key down (no automatic release). */
    fun keyDown(usage: HidUsage, seqId: Int = nextSeqId()): LowLevelOp

    /** Release a keyboard key (or all keys if usage is NONE). */
    fun keyUp(usage: HidUsage, seqId: Int = nextSeqId()): LowLevelOp

    /** Compile and send a batched keyboard tap script. */
    fun keyboardTapScript(
        taps: List<Pair<Byte, Byte>>,
        seqId: Int = nextSeqId(),
    ): LowLevelOp

    /**
     * Emit a raw relative mouse report. Per-axis deltas must fit the
     * signed 8-bit report range; use [MouseOps] for movement that may
     * exceed the range.
     */
    fun mouseRelReport(
        buttons: Int, dx: Int, dy: Int,
        wheel: Int = 0, pan: Int = 0,
        seqId: Int = nextSeqId(),
    ): LowLevelOp
}

/**
 * Parsed status notification exposed at the public session boundary.
 *
 * Correlates a library-issued command sequence identifier with the
 * firmware-emitted status name. The opaque context bytes are preserved
 * so future capability responses and device-class metadata can be added
 * without changing the frame format.
 */
data class SessionStatus(
    val seqId: Int,
    val status: Int,
    val statusName: String,
    val context: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionStatus) return false
        return seqId == other.seqId &&
            status == other.status &&
            context.contentEquals(other.context)
    }

    override fun hashCode(): Int {
        var result = seqId
        result = 31 * result + status
        result = 31 * result + context.contentHashCode()
        return result
    }
}
