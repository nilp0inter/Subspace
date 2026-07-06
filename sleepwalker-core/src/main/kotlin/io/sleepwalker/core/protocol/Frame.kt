package io.sleepwalker.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Versioned binary command frame layout for the sleepwalker HID protocol.
 *
 * Mirror of firmware frame.h and protocol/src/sleepwalker_protocol/frame.py.
 *
 * Layout (little-endian):
 *   ver(u8) | seq_id(u16) | opcode(u16) | payload_len(u16) | payload | crc32(u32)
 *
 * CRC-32 is corruption detection only; it is NOT authorization or
 * authentication. The initial authorization boundary is BLE bonding plus
 * explicit firmware safety state.
 */
object Frame {
    const val PROTOCOL_VERSION: Int = 1
    const val HEADER_SIZE: Int = 7
    const val CRC_SIZE: Int = 4
    const val MAX_PAYLOAD_LEN: Int = 240
    const val MAX_FRAME_SIZE: Int = HEADER_SIZE + MAX_PAYLOAD_LEN + CRC_SIZE

    sealed class DecodeError(message: String) : Exception(message) {
        class Malformed(message: String) : DecodeError(message)
        class UnsupportedVersion(val version: Int) :
            DecodeError("unsupported protocol version $version")
        class BadCrc(val expected: Long, val got: Long) :
            DecodeError("CRC mismatch: expected ${expected.toString(16)}, got ${got.toString(16)}")
    }

    data class Decoded(
        val version: Int,
        val seqId: Int,
        val opcode: Int,
        val payload: ByteArray,
        val crc32: Long,
    ) {
        val payloadLen: Int get() = payload.size
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    fun computeCrc(
        version: Int, seqId: Int, opcode: Int, payload: ByteArray,
    ): Long {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(version.toByte())
            .putShort(seqId.toShort())
            .putShort(opcode.toShort())
            .putShort(payload.size.toShort())
            .array()
        val crc = CRC32()
        crc.update(header)
        crc.update(payload)
        return crc.value
    }

    fun encode(
        seqId: Int, opcode: Int, payload: ByteArray = ByteArray(0),
        version: Int = PROTOCOL_VERSION,
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD_LEN) {
            "payload too large: ${payload.size} > $MAX_PAYLOAD_LEN"
        }
        require(seqId in 0..0xFFFF) { "seqId out of uint16 range: $seqId" }
        require(opcode in 0..0xFFFF) { "opcode out of uint16 range: $opcode" }
        require(version in 0..0xFF) { "version out of uint8 range: $version" }

        val crc = computeCrc(version, seqId, opcode, payload)
        val out = ByteBuffer.allocate(HEADER_SIZE + payload.size + CRC_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(version.toByte())
            .putShort(seqId.toShort())
            .putShort(opcode.toShort())
            .putShort(payload.size.toShort())
            .put(payload)
            .putInt(crc.toInt())
            .array()
        return out
    }

    fun keyboardTapScript(
        seqId: Int,
        taps: List<Pair<Byte, Byte>>
    ): ByteArray {
        val payload = ByteArray(1 + taps.size * 2)
        payload[0] = taps.size.toByte()
        taps.forEachIndexed { i, tap ->
            payload[1 + i * 2] = tap.first
            payload[2 + i * 2] = tap.second
        }
        return encode(seqId, Opcodes.KEYBOARD_TAP_SCRIPT, payload)
    }

    fun decode(data: ByteArray): Decoded {
        if (data.size < HEADER_SIZE + CRC_SIZE) {
            throw DecodeError.Malformed("frame too short: ${data.size}")
        }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.get().toInt() and 0xFF
        val seqId = bb.short.toInt() and 0xFFFF
        val opcode = bb.short.toInt() and 0xFFFF
        val payloadLen = bb.short.toInt() and 0xFFFF
        val expectedTotal = HEADER_SIZE + payloadLen + CRC_SIZE
        if (data.size != expectedTotal) {
            throw DecodeError.Malformed(
                "frame length mismatch: got ${data.size}, expected $expectedTotal")
        }
        if (payloadLen > MAX_PAYLOAD_LEN) {
            throw DecodeError.Malformed("payload length too large: $payloadLen")
        }
        if (version != PROTOCOL_VERSION) {
            throw DecodeError.UnsupportedVersion(version)
        }
        val payload = ByteArray(payloadLen)
        bb.get(payload)
        val crcCarried = bb.int.toLong() and 0xFFFFFFFFL
        val crcComputed = computeCrc(version, seqId, opcode, payload)
        if (crcCarried != crcComputed) {
            throw DecodeError.BadCrc(crcComputed, crcCarried)
        }
        return Decoded(version, seqId, opcode, payload, crcCarried)
    }
}