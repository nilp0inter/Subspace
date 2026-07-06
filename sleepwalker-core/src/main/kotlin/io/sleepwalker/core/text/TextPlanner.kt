package io.sleepwalker.core.text

import io.sleepwalker.core.hid.LowLevelHid
import io.sleepwalker.core.hid.LowLevelOp
import io.sleepwalker.core.keymap.HostProfile
import io.sleepwalker.core.keymap.KeymapDatabase
import io.sleepwalker.core.keymap.SeedKeymapDatabase
import io.sleepwalker.core.protocol.HidUsage
import io.sleepwalker.core.protocol.Usages

/**
 * Structured text rendering failure.
 *
 * The library reports structured failures when text cannot be
 * represented by the selected host profile rather than silently emitting
 * approximate or incorrect keystrokes.
 */
sealed class TextRenderingFailure {
    /** The host profile is absent from the bundled keymap database. */
    data class MissingLayout(val profile: HostProfile) : TextRenderingFailure()

    /** A specific glyph cannot be represented by the host profile. */
    data class UnrepresentableGlyph(val ch: Char, val profile: HostProfile) :
        TextRenderingFailure()
}

/**
 * The result of high-level text planning.
 *
 * On success, [plan] holds an inspectable sequence of low-level
 * keyboard operations. On failure, [failure] holds the structured
 * rendering failure and no HID operations are emitted.
 */
data class TextPlan(
    val plan: List<LowLevelOp>?,
    val failure: TextRenderingFailure? = null,
) {
    val ok: Boolean get() = plan != null && failure == null
}

/**
 * High-level text planning API.
 *
 * Translates requested text into an inspectable execution plan of
 * low-level keyboard operations for the selected host profile. The
 * high-level API composes low-level primitives rather than bypassing
 * them: each planned operation is produced by [LowLevelHid].
 *
 * @property database  the bundled keymap database (defaults to the seed).
 * @property hid       the low-level HID primitive API.
 */
class TextPlanner(
    private val database: KeymapDatabase = SeedKeymapDatabase,
    private val hid: LowLevelHid,
) {
    /**
     * Plan text input for the given host profile.
     *
     * Returns a [TextPlan] with an inspectable list of low-level
     * keyboard operations (key down/up/tap). If the text contains a
     * glyph that cannot be represented, returns a structured
     * [TextRenderingFailure.UnrepresentableGlyph] and emits no HID
     * operations for that request.
     */
    fun plan(text: String, profile: HostProfile): TextPlan {
        val entries = database.lookup(profile)
            ?: return TextPlan(plan = null, failure = TextRenderingFailure.MissingLayout(profile))

        val entryMap = entries.associateBy { it.ch }
        val ops = ArrayList<LowLevelOp>(text.length * 3)

        for (ch in text) {
            val entry = entryMap[ch]
                ?: return TextPlan(plan = null, failure = TextRenderingFailure.UnrepresentableGlyph(ch, profile))

            val usage = Usages.byUsb(entry.usage) ?: HidUsage(
                name = "USB_USAGE_0x${"%02x".format(entry.usage)}",
                usbUsage = entry.usage,
                evdevCode = 0,
            )

            val isShifted = (entry.modifiers and 0x02) != 0
            if (isShifted) {
                ops.add(hid.keyDown(Usages.USB_KEY_LEFTSHIFT))
                ops.add(hid.keyTap(usage))
                ops.add(hid.keyUp(Usages.USB_KEY_LEFTSHIFT))
            } else {
                ops.add(hid.keyTap(usage))
            }
        }
        return TextPlan(plan = ops, failure = null)
    }
}
