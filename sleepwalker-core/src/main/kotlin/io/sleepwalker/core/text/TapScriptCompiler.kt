package io.sleepwalker.core.text

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.protocol.Opcodes

object TapScriptCompiler {
    /**
     * Compiles a planned sequence of keyboard operations into one or more
     * compact keyboard tap scripts, chunked by maxBatchSize.
     */
    fun compile(
        ops: List<LowLevelOp>,
        hid: LowLevelHid,
        maxBatchSize: Int = 32
    ): List<LowLevelOp> {
        val taps = mutableListOf<Pair<Byte, Byte>>()
        var currentModifiers = 0

        for (op in ops) {
            when (op.opcode) {
                Opcodes.KEY_DOWN -> {
                    val usage = if (op.payload.isNotEmpty()) op.payload[0].toInt() and 0xFF else 0
                    if (usage in 0xE0..0xE7) {
                        val bit = usage - 0xE0
                        currentModifiers = currentModifiers or (1 shl bit)
                    }
                }
                Opcodes.KEY_UP -> {
                    val usage = if (op.payload.isNotEmpty()) op.payload[0].toInt() and 0xFF else 0
                    if (usage in 0xE0..0xE7) {
                        val bit = usage - 0xE0
                        currentModifiers = currentModifiers and (1 shl bit).inv()
                    } else if (usage == 0) {
                        currentModifiers = 0
                    }
                }
                Opcodes.KEY_TAP -> {
                    val usage = if (op.payload.isNotEmpty()) op.payload[0] else 0.toByte()
                    taps.add(currentModifiers.toByte() to usage)
                }
            }
        }

        if (taps.isEmpty()) {
            return emptyList()
        }

        return taps.chunked(maxBatchSize).map { chunk ->
            hid.keyboardTapScript(chunk)
        }
    }
}
