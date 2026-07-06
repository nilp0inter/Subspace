package io.sleepwalker.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task 1.3 / 1.5: Kotlin mouse payload parity with Python golden fixtures.
 *
 * The canonical byte sequences here MUST match the Python fixtures in
 * protocol/src/sleepwalker_protocol/tests/test_mouse.py.
 */
class MouseRelTest {

    @Test fun payload_len_is_five() {
        assertEquals(5, MouseRel.PAYLOAD_LEN)
    }

    @Test fun button_mask_bits() {
        assertEquals(0x01, MouseRel.BUTTON_LEFT)
        assertEquals(0x02, MouseRel.BUTTON_RIGHT)
        assertEquals(0x04, MouseRel.BUTTON_MIDDLE)
    }

    @Test fun encode_left_button_down() {
        val b = MouseRel.encode(MouseRel.BUTTON_LEFT, 0, 0)
        assertEquals("0100000000", b.toHex())
    }

    @Test fun encode_move_dx10_dy_neg5() {
        val b = MouseRel.encode(0, 10, -5)
        assertEquals("000afb0000", b.toHex())
    }

    @Test fun encode_signed_extrema() {
        val b = MouseRel.encode(0, -128, 127, -128, 127)
        assertEquals("00807f807f", b.toHex())
    }

    @Test fun decode_round_trip() {
        val b = byteArrayOf(0x03, 0x0A, 0xFB.toByte(), 0x01, 0xFF.toByte())
        val rep = MouseRel.decode(b)
        assertEquals(0x03, rep.buttons)
        assertEquals(10, rep.dx)
        assertEquals(-5, rep.dy)
        assertEquals(1, rep.wheel)
        assertEquals(-1, rep.pan)
    }

    @Test fun decode_wrong_length_throws() {
        try {
            MouseRel.decode(byteArrayOf(0x00, 0x01))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    @Test fun is_valid_payload() {
        assertTrue(MouseRel.isValidPayload(ByteArray(5)))
        assertFalse(MouseRel.isValidPayload(ByteArray(4)))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
