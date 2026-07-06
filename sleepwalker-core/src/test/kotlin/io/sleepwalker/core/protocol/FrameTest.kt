package io.sleepwalker.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Frame encode/decode round-trip and CRC tests.
 *
 * Verifies byte-for-byte compatibility with the Python golden fixtures
 * (protocol/src/sleepwalker_protocol/fixtures). The canonical fixture hex
 * values are embedded here so a mismatch between Android and the Python
 * package is caught without hardware.
 */
class FrameTest {

    @Test fun roundTrip_keySpace() {
        val payload = byteArrayOf(0x2c.toByte())
        val encoded = Frame.encode(seqId = 1, opcode = Opcodes.KEY_TAP, payload = payload)
        // Expected to match the Python fixture valid_usb_key_space.bin.
        val expectedHex = "010100110001002ca56c6561"
        assertEquals(expectedHex, encoded.toHex())

        val decoded = Frame.decode(encoded)
        assertEquals(1, decoded.seqId)
        assertEquals(Opcodes.KEY_TAP, decoded.opcode)
        assertEquals(1, decoded.payloadLen)
        assertEquals(0x2c.toByte(), decoded.payload[0])
    }

    @Test fun roundTrip_arm() {
        val encoded = Frame.encode(seqId = 2, opcode = Opcodes.ARM)
        // Expected to match the Python fixture arm.bin.
        assertEquals("01020001000000a4126fce", encoded.toHex())
        val decoded = Frame.decode(encoded)
        assertEquals(Opcodes.ARM, decoded.opcode)
        assertEquals(0, decoded.payloadLen)
    }

    @Test fun roundTrip_disarm() {
        val encoded = Frame.encode(seqId = 3, opcode = Opcodes.DISARM)
        assertEquals("01030002000000ef6e8617", encoded.toHex())
    }

    @Test fun roundTrip_kill() {
        val encoded = Frame.encode(seqId = 4, opcode = Opcodes.KILL)
        assertEquals("0104000300000032393fb2", encoded.toHex())
    }

    @Test fun roundTrip_releaseAll() {
        val encoded = Frame.encode(seqId = 5, opcode = Opcodes.RELEASE_ALL)
        assertEquals("010500040000002ed2b4e4", encoded.toHex())
    }

    @Test fun badCrc_rejected() {
        // Corrupt the last byte of the CRC trailer.
        val good = Frame.encode(seqId = 6, opcode = Opcodes.KEY_TAP, byteArrayOf(0x2c.toByte()))
        val bad = good.copyOf()
        bad[bad.lastIndex] = (bad[bad.lastIndex].toInt() xor 0xFF).toByte()
        // The Python fixture bad_crc.bin flips the last byte; verify we reject.
        try {
            Frame.decode(bad)
            fail("expected BadCrc")
        } catch (_: Frame.DecodeError.BadCrc) {
            // ok
        }
    }

    @Test fun unsupportedOpcode_decodesButUnknown() {
        val encoded = Frame.encode(seqId = 7, opcode = 0xFFFE)
        assertEquals("010700feff00000bb13ffe", encoded.toHex())
        val decoded = Frame.decode(encoded)
        assertEquals(0xFFFE, decoded.opcode)
        assertTrue(!Opcodes.isKnown(decoded.opcode))
    }

    @Test fun malformed_tooShort() {
        try {
            Frame.decode(byteArrayOf(0x01, 0x00))
            fail("expected Malformed")
        } catch (_: Frame.DecodeError.Malformed) {
            // ok
        }
    }

    @Test fun unsupportedVersion_rejected() {
        // Build a frame with version 2.
        val payload = ByteArray(0)
        val crc = Frame.computeCrc(2, 1, Opcodes.ARM, payload)
        val out = java.nio.ByteBuffer.allocate(Frame.HEADER_SIZE + Frame.CRC_SIZE)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put(2.toByte())
            .putShort(1.toShort())
            .putShort(Opcodes.ARM.toShort())
            .putShort(0.toShort())
            .putInt(crc.toInt())
            .array()
        try {
            Frame.decode(out)
            fail("expected UnsupportedVersion")
        } catch (_: Frame.DecodeError.UnsupportedVersion) {
            // ok
        }
    }

    @Test fun statusKnown() {
        for (s in Status.ALL) {
            assertTrue(Status.isKnown(s))
        }
        assertNotEquals("received", Status.name(Status.MALFORMED))
    }

    @Test fun usageRegistry() {
        val expected = mapOf(
            "USB_KEY_NONE" to Pair(0x00, 0),
            "USB_KEY_A" to Pair(0x04, 30),
            "USB_KEY_B" to Pair(0x05, 48),
            "USB_KEY_C" to Pair(0x06, 46),
            "USB_KEY_D" to Pair(0x07, 32),
            "USB_KEY_E" to Pair(0x08, 18),
            "USB_KEY_F" to Pair(0x09, 33),
            "USB_KEY_G" to Pair(0x0A, 34),
            "USB_KEY_H" to Pair(0x0B, 35),
            "USB_KEY_I" to Pair(0x0C, 23),
            "USB_KEY_J" to Pair(0x0D, 36),
            "USB_KEY_K" to Pair(0x0E, 37),
            "USB_KEY_L" to Pair(0x0F, 38),
            "USB_KEY_M" to Pair(0x10, 50),
            "USB_KEY_N" to Pair(0x11, 49),
            "USB_KEY_O" to Pair(0x12, 24),
            "USB_KEY_P" to Pair(0x13, 25),
            "USB_KEY_Q" to Pair(0x14, 16),
            "USB_KEY_R" to Pair(0x15, 19),
            "USB_KEY_S" to Pair(0x16, 31),
            "USB_KEY_T" to Pair(0x17, 20),
            "USB_KEY_U" to Pair(0x18, 22),
            "USB_KEY_V" to Pair(0x19, 47),
            "USB_KEY_W" to Pair(0x1A, 17),
            "USB_KEY_X" to Pair(0x1B, 45),
            "USB_KEY_Y" to Pair(0x1C, 21),
            "USB_KEY_Z" to Pair(0x1D, 44),
            "USB_KEY_1" to Pair(0x1E, 2),
            "USB_KEY_2" to Pair(0x1F, 3),
            "USB_KEY_3" to Pair(0x20, 4),
            "USB_KEY_4" to Pair(0x21, 5),
            "USB_KEY_5" to Pair(0x22, 6),
            "USB_KEY_6" to Pair(0x23, 7),
            "USB_KEY_7" to Pair(0x24, 8),
            "USB_KEY_8" to Pair(0x25, 9),
            "USB_KEY_9" to Pair(0x26, 10),
            "USB_KEY_0" to Pair(0x27, 11),
            "USB_KEY_ENTER" to Pair(0x28, 28),
            "USB_KEY_ESCAPE" to Pair(0x29, 1),
            "USB_KEY_SPACE" to Pair(0x2C, 57),
            "USB_KEY_MINUS" to Pair(0x2D, 12),
            "USB_KEY_EQUAL" to Pair(0x2E, 13),
            "USB_KEY_LEFTBRACE" to Pair(0x2F, 26),
            "USB_KEY_RIGHTBRACE" to Pair(0x30, 27),
            "USB_KEY_BACKSLASH" to Pair(0x31, 43),
            "USB_KEY_SEMICOLON" to Pair(0x33, 39),
            "USB_KEY_APOSTROPHE" to Pair(0x34, 40),
            "USB_KEY_GRAVE" to Pair(0x35, 41),
            "USB_KEY_COMMA" to Pair(0x36, 51),
            "USB_KEY_DOT" to Pair(0x37, 52),
            "USB_KEY_SLASH" to Pair(0x38, 53),
            "USB_KEY_LEFTSHIFT" to Pair(0xE1, 42),
        )
        assertEquals(expected.size, Usages.REGISTRY.size)
        for ((name, pair) in expected) {
            val u = Usages.byName(name)
            assertEquals(name, u.name)
            assertEquals(pair.first, u.usbUsage)
            assertEquals(pair.second, u.evdevCode)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}