package io.sleepwalker.core.ble

import io.sleepwalker.core.protocol.Frame
import io.sleepwalker.core.protocol.Status
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed status notification from the ESP32-S3 TX characteristic.
 *
 * Notification payload layout (little-endian):
 *   seq_id (u16) + status (u8) + ctx_len (u8) + ctx[ctx_len]
 */
data class StatusNotification(
    val seqId: Int,
    val status: Int,
    val context: ByteArray,
) {
    val statusName: String get() = Status.name(status)
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        fun parse(data: ByteArray): StatusNotification? {
            if (data.size < 4) return null
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val seqId = bb.short.toInt() and 0xFFFF
            val status = bb.get().toInt() and 0xFF
            val ctxLen = bb.get().toInt() and 0xFF
            if (data.size < 4 + ctxLen) return null
            val ctx = ByteArray(ctxLen)
            bb.get(ctx)
            return StatusNotification(seqId, status, ctx)
        }
    }
}

/**
 * MTU-aware frame chunking for BLE writes.
 *
 * The ATT MTU is runtime-negotiated. Effective payload per write is
 * MTU minus ATT header overhead (3 bytes for write-without-response).
 * This helper splits a frame into chunks that fit the negotiated MTU.
 * The firmware reassembles by accumulating writes into a single frame
 * buffer up to MAX_FRAME_SIZE.
 */
object BleWriter {
    const val ATT_HEADER_OVERHEAD: Int = 3

    fun maxWriteSize(mtu: Int): Int = (mtu - ATT_HEADER_OVERHEAD).coerceAtLeast(20)

    fun chunkFrame(frame: ByteArray, mtu: Int): List<ByteArray> {
        val chunkSize = maxWriteSize(mtu)
        if (frame.size <= chunkSize) return listOf(frame)
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off < frame.size) {
            val len = minOf(chunkSize, frame.size - off)
            out.add(frame.copyOfRange(off, off + len))
            off += len
        }
        return out
    }
}