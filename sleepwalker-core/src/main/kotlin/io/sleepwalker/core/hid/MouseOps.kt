package io.sleepwalker.core.hid

import io.sleepwalker.core.protocol.MouseRel

/**
 * Low-level relative mouse report construction with movement chunking.
 *
 * Per-axis deltas outside the signed 8-bit report range are split into
 * multiple raw relative mouse reports whose per-axis deltas fit the
 * protocol payload. This is the library-owned chunking described in the
 * design: firmware stays simple and only validates/emits one raw
 * report at a time.
 */
object MouseChunker {
    /** Maximum per-axis delta in a single raw report (signed 8-bit). */
    const val MAX_DELTA: Int = 127
    const val MIN_DELTA: Int = -128

    /**
     * Split a single relative movement into a list of per-axis delta
     * pairs, each fitting the signed 8-bit report range. The last chunk
     * may be smaller than MAX_DELTA.
     */
    fun chunkDelta(delta: Int): List<Int> {
        if (delta == 0) return emptyList()
        val out = ArrayList<Int>()
        var remaining = delta
        while (remaining > MAX_DELTA) {
            out.add(MAX_DELTA)
            remaining -= MAX_DELTA
        }
        while (remaining < MIN_DELTA) {
            out.add(MIN_DELTA)
            remaining -= MIN_DELTA
        }
        if (remaining != 0) {
            out.add(remaining)
        }
        return out
    }

    /**
     * Plan a relative move as a sequence of (dx, dy) chunks. The two
     * axes are chunked independently and emitted in lockstep; the
     * shorter axis emits zero-delta chunks after it is exhausted.
     */
    fun chunkMove(dx: Int, dy: Int): List<Pair<Int, Int>> {
        val xChunks = chunkDelta(dx)
        val yChunks = chunkDelta(dy)
        val n = maxOf(xChunks.size, yChunks.size)
        if (n == 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>(n)
        for (i in 0 until n) {
            val x = xChunks.getOrElse(i) { 0 }
            val y = yChunks.getOrElse(i) { 0 }
            out.add(x to y)
        }
        return out
    }
}

/**
 * Low-level mouse convenience operations.
 *
 * Builds raw relative mouse reports for button down/up, click, relative
 * move, vertical scroll, horizontal pan, and release-buttons using the
 * [LowLevelHid] primitive API. All operations produce inspectable
 * [LowLevelOp] instances; the caller owns BLE transmission.
 *
 * @property hid  the low-level HID primitive API.
 */
class MouseOps(private val hid: LowLevelHid) {
    /** Press a mouse button down (no automatic release). */
    fun buttonDown(button: Int, seqId: Int = hid.nextSeqId()): LowLevelOp =
        hid.mouseRelReport(buttons = button, dx = 0, dy = 0, seqId = seqId)

    /**
     * Release a mouse button (or all buttons if [button] is 0). The raw
     * report model carries a full button mask; the current slice always
     * clears the full mask, which is safe for click (down/up) and
     * release scenarios.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buttonUp(button: Int = 0, seqId: Int = hid.nextSeqId()): LowLevelOp =
        hid.mouseRelReport(buttons = 0, dx = 0, dy = 0, seqId = seqId)

    /**
     * Click a mouse button: button-down followed by button-up.
     * Returns exactly two operations.
     */
    fun click(button: Int, seqIdDown: Int = hid.nextSeqId(),
              seqIdUp: Int = hid.nextSeqId()): List<LowLevelOp> = listOf(
        buttonDown(button, seqIdDown),
        buttonUp(button, seqIdUp),
    )

    /**
     * Left click: button-down (LEFT) followed by button-up.
     * Returns exactly two operations.
     */
    fun leftClick(seqIdDown: Int = hid.nextSeqId(),
                  seqIdUp: Int = hid.nextSeqId()): List<LowLevelOp> =
        click(MouseRel.BUTTON_LEFT, seqIdDown, seqIdUp)

    /**
     * Relative move. Large movements are chunked into multiple raw
     * reports whose per-axis deltas fit the signed 8-bit payload.
     */
    fun move(dx: Int, dy: Int,
             baseSeqId: Int = hid.nextSeqId()): List<LowLevelOp> {
        val chunks = MouseChunker.chunkMove(dx, dy)
        if (chunks.isEmpty()) {
            return listOf(hid.mouseRelReport(
                buttons = 0, dx = 0, dy = 0, seqId = baseSeqId))
        }
        var seq = baseSeqId
        return chunks.map { (x, y) ->
            val op = hid.mouseRelReport(buttons = 0, dx = x, dy = y, seqId = seq)
            seq = hid.nextSeqId()
            op
        }
    }

    /** Vertical scroll (wheel). Chunked if out of i8 range. */
    fun scroll(amount: Int,
               baseSeqId: Int = hid.nextSeqId()): List<LowLevelOp> {
        val chunks = MouseChunker.chunkDelta(amount)
        if (chunks.isEmpty()) {
            return listOf(hid.mouseRelReport(
                buttons = 0, dx = 0, dy = 0, wheel = 0, seqId = baseSeqId))
        }
        var seq = baseSeqId
        return chunks.map { w ->
            val op = hid.mouseRelReport(
                buttons = 0, dx = 0, dy = 0, wheel = w, seqId = seq)
            seq = hid.nextSeqId()
            op
        }
    }

    /** Horizontal pan. Chunked if out of i8 range. */
    fun pan(amount: Int,
            baseSeqId: Int = hid.nextSeqId()): List<LowLevelOp> {
        val chunks = MouseChunker.chunkDelta(amount)
        if (chunks.isEmpty()) {
            return listOf(hid.mouseRelReport(
                buttons = 0, dx = 0, dy = 0, pan = 0, seqId = baseSeqId))
        }
        var seq = baseSeqId
        return chunks.map { p ->
            val op = hid.mouseRelReport(
                buttons = 0, dx = 0, dy = 0, pan = p, seqId = seq)
            seq = hid.nextSeqId()
            op
        }
    }

    /** Release all mouse buttons. */
    fun releaseButtons(seqId: Int = hid.nextSeqId()): LowLevelOp =
        buttonUp(0, seqId)
}
