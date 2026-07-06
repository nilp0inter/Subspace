package io.sleepwalker.core.text

import io.sleepwalker.core.hid.LowLevelHidImpl
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.KeymapEntry
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2.8: keymap lookup, text planning, rendering failures.
 */
class TextPlannerTest {

    @Test fun linux_us_profile_present_in_seed() {
        assertTrue(SeedKeymapDatabase.contains(HostProfile.LINUX_US))
    }

    @Test fun missing_layout_returns_structured_failure() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        val unknown = HostProfile("plan9", "dvorak")
        val result = planner.plan("a", unknown)
        assertFalse(result.ok)
        assertNull(result.plan)
        assertTrue(result.failure is TextRenderingFailure.MissingLayout)
        val f = result.failure as TextRenderingFailure.MissingLayout
        assertEquals(unknown, f.profile)
    }

    @Test fun representable_text_planned_as_key_taps() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        val result = planner.plan("hi", HostProfile.LINUX_US)
        assertTrue(result.ok)
        assertNotNull(result.plan)
        val ops = result.plan!!
        assertEquals(2, ops.size)
        assertEquals(Opcodes.KEY_TAP, ops[0].opcode)
        assertEquals(Opcodes.KEY_TAP, ops[1].opcode)
        // 'h' = USB usage 0x0B, 'i' = 0x0C
        assertEquals(0x0B, ops[0].payload[0].toInt() and 0xFF)
        assertEquals(0x0C, ops[1].payload[0].toInt() and 0xFF)
    }

    @Test fun unmodified_letters_and_digits_and_shifted_punctuation() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        
        // Direct lowercase
        val planA = planner.plan("a", HostProfile.LINUX_US).plan!!
        assertEquals(1, planA.size)
        assertEquals(Opcodes.KEY_TAP, planA[0].opcode)
        assertEquals(0x04, planA[0].payload[0].toInt() and 0xFF) // 'a'

        // Shifted uppercase
        val planShiftA = planner.plan("A", HostProfile.LINUX_US).plan!!
        assertEquals(3, planShiftA.size)
        assertEquals(Opcodes.KEY_DOWN, planShiftA[0].opcode)
        assertEquals(0xE1, planShiftA[0].payload[0].toInt() and 0xFF) // LEFT_SHIFT
        assertEquals(Opcodes.KEY_TAP, planShiftA[1].opcode)
        assertEquals(0x04, planShiftA[1].payload[0].toInt() and 0xFF) // 'a'/'A'
        assertEquals(Opcodes.KEY_UP, planShiftA[2].opcode)
        assertEquals(0xE1, planShiftA[2].payload[0].toInt() and 0xFF) // LEFT_SHIFT

        // Digits
        val plan1 = planner.plan("1", HostProfile.LINUX_US).plan!!
        assertEquals(1, plan1.size)
        assertEquals(Opcodes.KEY_TAP, plan1[0].opcode)
        assertEquals(0x1E, plan1[0].payload[0].toInt() and 0xFF) // '1'

        // Shifted punctuation
        val planExcl = planner.plan("!", HostProfile.LINUX_US).plan!!
        assertEquals(3, planExcl.size)
        assertEquals(Opcodes.KEY_DOWN, planExcl[0].opcode)
        assertEquals(0xE1, planExcl[0].payload[0].toInt() and 0xFF) // LEFT_SHIFT
        assertEquals(Opcodes.KEY_TAP, planExcl[1].opcode)
        assertEquals(0x1E, planExcl[1].payload[0].toInt() and 0xFF) // '1'/'!'
        assertEquals(Opcodes.KEY_UP, planExcl[2].opcode)
        assertEquals(0xE1, planExcl[2].payload[0].toInt() and 0xFF) // LEFT_SHIFT

        // Spaces
        val planSpace = planner.plan(" ", HostProfile.LINUX_US).plan!!
        assertEquals(1, planSpace.size)
        assertEquals(Opcodes.KEY_TAP, planSpace[0].opcode)
        assertEquals(0x2C, planSpace[0].payload[0].toInt() and 0xFF) // space
    }

    @Test fun planning_uses_low_level_hid_interface_methods() {
        val calls = mutableListOf<String>()
        val spyHid = object : io.sleepwalker.core.hid.LowLevelHid {
            override fun nextSeqId(): Int = 42
            override fun arm(seqId: Int) = io.sleepwalker.core.hid.LowLevelOp(Opcodes.ARM, byteArrayOf(), seqId)
            override fun disarm(seqId: Int) = io.sleepwalker.core.hid.LowLevelOp(Opcodes.DISARM, byteArrayOf(), seqId)
            override fun kill(seqId: Int) = io.sleepwalker.core.hid.LowLevelOp(Opcodes.KILL, byteArrayOf(), seqId)
            override fun releaseAll(seqId: Int) = io.sleepwalker.core.hid.LowLevelOp(Opcodes.RELEASE_ALL, byteArrayOf(), seqId)
            override fun keyTap(usage: io.sleepwalker.core.protocol.HidUsage, seqId: Int): io.sleepwalker.core.hid.LowLevelOp {
                calls.add("keyTap:${usage.name}")
                return io.sleepwalker.core.hid.LowLevelOp(Opcodes.KEY_TAP, byteArrayOf(usage.usbUsage.toByte()), seqId)
            }
            override fun keyDown(usage: io.sleepwalker.core.protocol.HidUsage, seqId: Int): io.sleepwalker.core.hid.LowLevelOp {
                calls.add("keyDown:${usage.name}")
                return io.sleepwalker.core.hid.LowLevelOp(Opcodes.KEY_DOWN, byteArrayOf(usage.usbUsage.toByte()), seqId)
            }
            override fun keyUp(usage: io.sleepwalker.core.protocol.HidUsage, seqId: Int): io.sleepwalker.core.hid.LowLevelOp {
                calls.add("keyUp:${usage.name}")
                return io.sleepwalker.core.hid.LowLevelOp(Opcodes.KEY_UP, byteArrayOf(usage.usbUsage.toByte()), seqId)
            }
            override fun keyboardTapScript(taps: List<Pair<Byte, Byte>>, seqId: Int): io.sleepwalker.core.hid.LowLevelOp {
                calls.add("keyboardTapScript:${taps.size}")
                return io.sleepwalker.core.hid.LowLevelOp(Opcodes.KEYBOARD_TAP_SCRIPT, byteArrayOf(), seqId)
            }

            override fun mouseRelReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int, seqId: Int) =
                io.sleepwalker.core.hid.LowLevelOp(Opcodes.MOUSE_REL_REPORT, byteArrayOf(), seqId)
        }
        
        val planner = TextPlanner(hid = spyHid)
        val plan = planner.plan("aA", HostProfile.LINUX_US).plan!!
        
        assertEquals(4, plan.size)
        assertEquals(4, calls.size)
        assertEquals("keyTap:USB_KEY_A", calls[0])
        assertEquals("keyDown:USB_KEY_LEFTSHIFT", calls[1])
        assertEquals("keyTap:USB_KEY_A", calls[2])
        assertEquals("keyUp:USB_KEY_LEFTSHIFT", calls[3])
    }

    @Test fun unrepresentable_glyph_rejected() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        // '€' is not in the seed US keymap.
        val result = planner.plan("€", HostProfile.LINUX_US)
        assertFalse(result.ok)
        assertNull(result.plan)
        assertTrue(result.failure is TextRenderingFailure.UnrepresentableGlyph)
        val f = result.failure as TextRenderingFailure.UnrepresentableGlyph
        assertEquals('€', f.ch)
    }

    @Test fun mixed_representable_and_unrepresentable_emits_no_ops() {
        val planner = TextPlanner(hid = LowLevelHidImpl())
        // 'a' is representable, '€' is not; whole request must fail.
        val result = planner.plan("a€", HostProfile.LINUX_US)
        assertFalse(result.ok)
        assertNull(result.plan)
    }

    @Test fun seed_keymap_resolves_lowercase_letters() {
        val entry = SeedKeymapDatabase.entryFor('a')
        assertNotNull(entry)
        assertEquals(0x04, entry!!.usage)
        assertEquals(0, entry.modifiers)
    }

    @Test fun seed_keymap_resolves_uppercase_with_shift() {
        val entry = SeedKeymapDatabase.entryFor('A')
        assertNotNull(entry)
        assertEquals(0x04, entry!!.usage)
        // Shift modifier bit 1 = 0x02
        assertEquals(0x02, entry.modifiers)
    }
}
