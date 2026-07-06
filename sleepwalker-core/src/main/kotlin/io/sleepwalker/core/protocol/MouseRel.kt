package io.sleepwalker.core.protocol

/**
 * Raw relative mouse report payload helpers.
 *
 * Mirror of protocol/src/sleepwalker_protocol/mouse.py and firmware
 * sleepwalker_protocol.h. The payload is exactly five bytes:
 *   buttons:u8, dx:i8, dy:i8, wheel:i8, pan:i8
 *
 * The library owns button-mask state, movement chunking, clicks, drags,
 * and scrolling abstractions. Firmware validates the payload length and
 * emits the corresponding TinyUSB relative mouse report.
 */
object MouseRel {
    const val PAYLOAD_LEN: Int = 5

    const val BUTTON_LEFT: Int = 0x01
    const val BUTTON_RIGHT: Int = 0x02
    const val BUTTON_MIDDLE: Int = 0x04

    val BUTTON_ALL: Set<Int> = setOf(BUTTON_LEFT, BUTTON_RIGHT, BUTTON_MIDDLE)

    /** Encode a raw relative mouse report payload (five bytes). */
    fun encode(
        buttons: Int,
        dx: Int,
        dy: Int,
        wheel: Int = 0,
        pan: Int = 0,
    ): ByteArray {
        val b = buttons and 0xFF
        val x = clampI8(dx)
        val y = clampI8(dy)
        val w = clampI8(wheel)
        val p = clampI8(pan)
        return byteArrayOf(
            b.toByte(),
            x.toByte(),
            y.toByte(),
            w.toByte(),
            p.toByte(),
        )
    }

    /** Decode a five-byte payload into a [Report]. Throws on wrong length. */
    fun decode(data: ByteArray): Report {
        require(data.size == PAYLOAD_LEN) {
            "mouse rel payload must be $PAYLOAD_LEN bytes, got ${data.size}"
        }
        return Report(
            buttons = data[0].toInt() and 0xFF,
            dx = data[1].toInt(),   // Kotlin Byte is already signed
            dy = data[2].toInt(),
            wheel = data[3].toInt(),
            pan = data[4].toInt(),
        )
    }

    fun isValidPayload(data: ByteArray): Boolean = data.size == PAYLOAD_LEN

    private fun clampI8(v: Int): Int = v.coerceIn(-128, 127)

    /** Decoded raw relative mouse report. */
    data class Report(
        val buttons: Int,
        val dx: Int,
        val dy: Int,
        val wheel: Int,
        val pan: Int,
    ) {
        fun toBytes(): ByteArray = encode(buttons, dx, dy, wheel, pan)
    }
}
