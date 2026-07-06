package io.sleepwalker.core.protocol

/**
 * ACK/status values for the sleepwalker HID protocol.
 *
 * Mirror of firmware status.h and protocol/src/sleepwalker_protocol/status.py.
 * Status notifications are correlated by sequence identifier.
 */
object Status {
    const val RECEIVED: Int = 0x01
    const val QUEUED: Int = 0x02
    const val SENT_TO_USB: Int = 0x03
    const val MALFORMED: Int = 0x10
    const val BAD_CRC: Int = 0x11
    const val DISARMED: Int = 0x12
    const val QUEUE_FULL: Int = 0x13
    const val USB_NOT_MOUNTED: Int = 0x14
    const val UNSUPPORTED_OPCODE: Int = 0x15
    const val KILLED: Int = 0x16

    val ALL: Set<Int> = setOf(
        RECEIVED, QUEUED, SENT_TO_USB, MALFORMED, BAD_CRC, DISARMED,
        QUEUE_FULL, USB_NOT_MOUNTED, UNSUPPORTED_OPCODE, KILLED,
    )

    fun isKnown(status: Int): Boolean = status in ALL

    fun name(status: Int): String = when (status) {
        RECEIVED -> "received"
        QUEUED -> "queued"
        SENT_TO_USB -> "sent_to_usb"
        MALFORMED -> "malformed"
        BAD_CRC -> "bad_crc"
        DISARMED -> "disarmed"
        QUEUE_FULL -> "queue_full"
        USB_NOT_MOUNTED -> "usb_not_mounted"
        UNSUPPORTED_OPCODE -> "unsupported_opcode"
        KILLED -> "killed"
        else -> "unknown"
    }
}