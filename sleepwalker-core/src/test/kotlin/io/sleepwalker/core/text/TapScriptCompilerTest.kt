package io.sleepwalker.core.text

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.protocol.Frame
import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapScriptCompilerTest {

    @Test
    fun testKeyboardTapScriptSerialization() {
        val seqId = 123
        val taps = listOf(
            0x02.toByte() to 0x04.toByte(), // modifier: 0x02 (Shift), usage: 0x04 (A)
            0x00.toByte() to 0x05.toByte()  // modifier: 0x00 (None), usage: 0x05 (B)
        )
        val serialized = Frame.keyboardTapScript(seqId, taps)

        // Decode using Frame.decode to verify correctness
        val decoded = Frame.decode(serialized)
        assertEquals(Frame.PROTOCOL_VERSION, decoded.version)
        assertEquals(seqId, decoded.seqId)
        assertEquals(Opcodes.KEYBOARD_TAP_SCRIPT, decoded.opcode)

        // Assert payload length and content: count byte followed by pairs
        assertEquals(5, decoded.payload.size)
        assertEquals(2.toByte(), decoded.payload[0]) // count
        assertEquals(0x02.toByte(), decoded.payload[1]) // modifier 1
        assertEquals(0x04.toByte(), decoded.payload[2]) // usage 1
        assertEquals(0x00.toByte(), decoded.payload[3]) // modifier 2
        assertEquals(0x05.toByte(), decoded.payload[4]) // usage 2
    }

    @Test
    fun testCompileMapping() {
        val spyHid = object : LowLevelHid {
            var capturedTaps: List<Pair<Byte, Byte>>? = null

            override fun nextSeqId(): Int = 42
            override fun arm(seqId: Int) = LowLevelOp(Opcodes.ARM, byteArrayOf(), seqId)
            override fun disarm(seqId: Int) = LowLevelOp(Opcodes.DISARM, byteArrayOf(), seqId)
            override fun kill(seqId: Int) = LowLevelOp(Opcodes.KILL, byteArrayOf(), seqId)
            override fun releaseAll(seqId: Int) = LowLevelOp(Opcodes.RELEASE_ALL, byteArrayOf(), seqId)
            override fun keyTap(usage: HidUsage, seqId: Int) = LowLevelOp(Opcodes.KEY_TAP, byteArrayOf(usage.usbUsage.toByte()), seqId)
            override fun keyDown(usage: HidUsage, seqId: Int) = LowLevelOp(Opcodes.KEY_DOWN, byteArrayOf(usage.usbUsage.toByte()), seqId)
            override fun keyUp(usage: HidUsage, seqId: Int) = LowLevelOp(Opcodes.KEY_UP, byteArrayOf(usage.usbUsage.toByte()), seqId)

            override fun keyboardTapScript(taps: List<Pair<Byte, Byte>>, seqId: Int): LowLevelOp {
                capturedTaps = taps
                val payload = ByteArray(1 + taps.size * 2)
                payload[0] = taps.size.toByte()
                taps.forEachIndexed { i, tap ->
                    payload[1 + i * 2] = tap.first
                    payload[2 + i * 2] = tap.second
                }
                return LowLevelOp(Opcodes.KEYBOARD_TAP_SCRIPT, payload, seqId)
            }

            override fun mouseRelReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int, seqId: Int) =
                LowLevelOp(Opcodes.MOUSE_REL_REPORT, byteArrayOf(), seqId)
        }

        // Plan a short text "aA"
        val planner = TextPlanner(hid = spyHid)
        val result = planner.plan("aA", HostProfile.LINUX_US)
        assertTrue(result.ok)
        val plannedOps = result.plan!!

        // Compile
        val compiledOps = TapScriptCompiler.compile(plannedOps, spyHid)

        // Verify compiled operation details
        assertEquals(1, compiledOps.size)
        val op = compiledOps[0]
        assertEquals(Opcodes.KEYBOARD_TAP_SCRIPT, op.opcode)

        assertNotNull(spyHid.capturedTaps)
        val taps = spyHid.capturedTaps!!
        assertEquals(2, taps.size)
        // First tap: 'a' -> modifier 0x00, usage 0x04
        assertEquals(0x00.toByte(), taps[0].first)
        assertEquals(0x04.toByte(), taps[0].second)
        // Second tap: 'A' -> modifier 0x02 (Left Shift), usage 0x04
        assertEquals(0x02.toByte(), taps[1].first)
        assertEquals(0x04.toByte(), taps[1].second)
    }

    @Test
    fun testCompileChunking() {
        val capturedTapsList = mutableListOf<List<Pair<Byte, Byte>>>()
        val spyHid = object : LowLevelHid {
            override fun nextSeqId(): Int = 1
            override fun arm(seqId: Int) = LowLevelOp(Opcodes.ARM, byteArrayOf(), seqId)
            override fun disarm(seqId: Int) = LowLevelOp(Opcodes.DISARM, byteArrayOf(), seqId)
            override fun kill(seqId: Int) = LowLevelOp(Opcodes.KILL, byteArrayOf(), seqId)
            override fun releaseAll(seqId: Int) = LowLevelOp(Opcodes.RELEASE_ALL, byteArrayOf(), seqId)
            override fun keyTap(usage: HidUsage, seqId: Int) = LowLevelOp(Opcodes.KEY_TAP, byteArrayOf(usage.usbUsage.toByte()), seqId)
            override fun keyDown(usage: HidUsage, seqId: Int) = LowLevelOp(Opcodes.KEY_DOWN, byteArrayOf(usage.usbUsage.toByte()), seqId)
            override fun keyUp(usage: HidUsage, seqId: Int) = LowLevelOp(Opcodes.KEY_UP, byteArrayOf(usage.usbUsage.toByte()), seqId)

            override fun keyboardTapScript(taps: List<Pair<Byte, Byte>>, seqId: Int): LowLevelOp {
                capturedTapsList.add(taps)
                val payload = ByteArray(1 + taps.size * 2)
                payload[0] = taps.size.toByte()
                taps.forEachIndexed { i, tap ->
                    payload[1 + i * 2] = tap.first
                    payload[2 + i * 2] = tap.second
                }
                return LowLevelOp(Opcodes.KEYBOARD_TAP_SCRIPT, payload, seqId)
            }

            override fun mouseRelReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int, seqId: Int) =
                LowLevelOp(Opcodes.MOUSE_REL_REPORT, byteArrayOf(), seqId)
        }

        // Construct 100 keyboard tap operations
        val ops = List(100) { index ->
            LowLevelOp(
                opcode = Opcodes.KEY_TAP,
                payload = byteArrayOf(0x04.toByte()),
                seqId = index + 1
            )
        }

        // Compile with maxBatchSize = 32
        val compiled = TapScriptCompiler.compile(ops, spyHid, maxBatchSize = 32)

        // Verify compilation results into exactly 4 KEYBOARD_TAP_SCRIPT operations
        assertEquals(4, compiled.size)
        compiled.forEach { op ->
            assertEquals(Opcodes.KEYBOARD_TAP_SCRIPT, op.opcode)
        }

        // Verify chunk sizes passed to spyHid
        assertEquals(4, capturedTapsList.size)
        assertEquals(32, capturedTapsList[0].size)
        assertEquals(32, capturedTapsList[1].size)
        assertEquals(32, capturedTapsList[2].size)
        assertEquals(4, capturedTapsList[3].size)

        // Assert payload sizes on the returned LowLevelOps
        assertEquals(1 + 32 * 2, compiled[0].payload.size)
        assertEquals(1 + 32 * 2, compiled[1].payload.size)
        assertEquals(1 + 32 * 2, compiled[2].payload.size)
        assertEquals(1 + 4 * 2, compiled[3].payload.size)
    }
}
