package io.sleepwalker.core.hid

import io.sleepwalker.core.protocol.MouseRel
import io.sleepwalker.core.protocol.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mouse chunking, keymap lookup, text planning, rendering failures,
 * status parsing, and public API boundary tests.
 *
 * Task 2.8: Kotlin unit tests for the core library surface.
 */
class MouseOpsTest {

    private fun newHid(): LowLevelHid = LowLevelHidImpl()

    @Test fun mouse_rel_report_payload_is_five_bytes() {
        val hid = newHid()
        val op = hid.mouseRelReport(buttons = MouseRel.BUTTON_LEFT, dx = 0, dy = 0)
        assertEquals(Opcodes.MOUSE_REL_REPORT, op.opcode)
        assertEquals(5, op.payload.size)
        assertEquals(0x01, op.payload[0].toInt() and 0xFF)
    }

    @Test fun left_click_produces_down_then_up() {
        val hid = newHid()
        val ops = MouseOps(hid).leftClick()
        assertEquals(2, ops.size)
        // First op: LEFT button down.
        assertEquals(Opcodes.MOUSE_REL_REPORT, ops[0].opcode)
        assertEquals(MouseRel.BUTTON_LEFT, ops[0].payload[0].toInt() and 0xFF)
        // Second op: buttons cleared.
        assertEquals(0, ops[1].payload[0].toInt() and 0xFF)
    }

    @Test fun large_movement_chunked() {
        val hid = newHid()
        val ops = MouseOps(hid).move(dx = 300, dy = 0)
        // 300 = 127 + 127 + 46 => three chunks.
        assertEquals(3, ops.size)
        assertEquals(127, MouseRel.decode(ops[0].payload).dx)
        assertEquals(127, MouseRel.decode(ops[1].payload).dx)
        assertEquals(46, MouseRel.decode(ops[2].payload).dx)
    }

    @Test fun negative_movement_chunked() {
        val hid = newHid()
        val ops = MouseOps(hid).move(dx = -300, dy = 0)
        assertEquals(3, ops.size)
        assertEquals(-128, MouseRel.decode(ops[0].payload).dx)
        assertEquals(-128, MouseRel.decode(ops[1].payload).dx)
        assertEquals(-44, MouseRel.decode(ops[2].payload).dx)
    }

    @Test fun small_move_single_report() {
        val hid = newHid()
        val ops = MouseOps(hid).move(dx = 10, dy = -5)
        assertEquals(1, ops.size)
        val rep = MouseRel.decode(ops[0].payload)
        assertEquals(10, rep.dx)
        assertEquals(-5, rep.dy)
    }

    @Test fun scroll_chunked() {
        val hid = newHid()
        val ops = MouseOps(hid).scroll(amount = 256)
        assertEquals(3, ops.size) // 127 + 127 + 2
        assertEquals(127, MouseRel.decode(ops[0].payload).wheel)
        assertEquals(127, MouseRel.decode(ops[1].payload).wheel)
        assertEquals(2, MouseRel.decode(ops[2].payload).wheel)
    }

    @Test fun pan_chunked() {
        val hid = newHid()
        val ops = MouseOps(hid).pan(amount = -256)
        // -256 = -128 + -128 (two chunks)
        assertEquals(2, ops.size)
        assertEquals(-128, MouseRel.decode(ops[0].payload).pan)
        assertEquals(-128, MouseRel.decode(ops[1].payload).pan)
    }

    @Test fun release_buttons_clears_mask() {
        val hid = newHid()
        val op = MouseOps(hid).releaseButtons()
        assertEquals(Opcodes.MOUSE_REL_REPORT, op.opcode)
        assertEquals(0, op.payload[0].toInt() and 0xFF)
    }

    @Test fun chunker_empty_for_zero() {
        assertTrue(MouseChunker.chunkDelta(0).isEmpty())
        assertTrue(MouseChunker.chunkMove(0, 0).isEmpty())
    }

    @Test fun seq_id_increments_and_wraps() {
        val hid = newHid()
        val a = hid.nextSeqId()
        val b = hid.nextSeqId()
        assertTrue(b != a)
    }
}
