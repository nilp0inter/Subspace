package io.sleepwalker.core.hid

import io.sleepwalker.core.ble.StatusNotification
import io.sleepwalker.core.protocol.Status

/**
 * Session-level status correlation.
 *
 * Exposes parsed status notifications and sequence identifiers through
 * the public library/session boundary so callers can correlate library
 * operations with firmware acknowledgements.
 *
 * The reference app consumes this surface rather than parsing raw BLE
 * notification bytes itself.
 */
object SessionStatusParser {
    /**
     * Parse a raw BLE TX characteristic notification into a
     * [SessionStatus], or null if the payload is malformed.
     *
     * The opaque context bytes are preserved so future capability
     * responses and device-class metadata can be added without changing
     * the frame format.
     */
    fun parse(data: ByteArray): SessionStatus? {
        val note = StatusNotification.parse(data) ?: return null
        return SessionStatus(
            seqId = note.seqId,
            status = note.status,
            statusName = note.statusName,
            context = note.context,
        )
    }

    /** True if the status value is recognized by the current protocol. */
    fun isKnownStatus(status: Int): Boolean = Status.isKnown(status)

    /** Human-readable name for a status value, or "unknown". */
    fun nameFor(status: Int): String = Status.name(status)
}
