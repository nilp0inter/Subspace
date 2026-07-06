package io.sleepwalker.core.keymap

/**
 * Host profile for text rendering.
 *
 * Models the host OS, keymap/layout identifier, and optional variant
 * metadata. The text planner uses the matching bundled keymap data for
 * rendering decisions.
 *
 * @property hostOs    host operating system (e.g. "linux", "windows", "macos").
 * @property layout    keymap/layout identifier (e.g. "us", "us-intl", "de").
 * @property variant   optional variant metadata (e.g. "nodeadkeys").
 */
data class HostProfile(
    val hostOs: String,
    val layout: String,
    val variant: String? = null,
) {
    val name: String
        get() = when (this) {
            LINUX_US -> "LINUX_US"
            else -> "${hostOs.uppercase()}_${layout.uppercase()}"
        }
    /** Stable lookup key used by [KeymapDatabase]. */
    val key: String get() = buildString {
        append(hostOs.lowercase())
        append(':')
        append(layout.lowercase())
        if (variant != null) {
            append(':')
            append(variant.lowercase())
        }
    }

    companion object {
        /** Convenience: Linux host with US keymap. */
        val LINUX_US: HostProfile = HostProfile("linux", "us")

        fun values(): List<HostProfile> = listOf(LINUX_US)

        fun valueOf(name: String): HostProfile = when (name) {
            "LINUX_US" -> LINUX_US
            else -> LINUX_US
        }
    }
}

/**
 * A single keymap entry mapping a character to the USB HID usage and
 * modifier state required to type it on a given host profile.
 *
 * @property ch        the character this entry produces.
 * @property usage     the USB HID keyboard usage id to press.
 * @property modifiers modifier mask bits (bit 0 = left ctrl, 1 = left shift,
 *                     2 = left alt, 3 = left gui, 4 = right ctrl,
 *                     5 = right shift, 6 = right alt, 7 = right gui).
 */
data class KeymapEntry(
    val ch: Char,
    val usage: Int,
    val modifiers: Int,
)

/**
 * Bundled keymap database abstraction.
 *
 * The public API allows the complete keymap corpus to be shipped as
 * library data without changing text rendering APIs. The seed/conformance
 * dataset lives in [SeedKeymapDatabase]; a future data-ingestion change
 * can replace it with a larger corpus.
 */
interface KeymapDatabase {
    /** Resolve a host profile to its keymap entries, or null if absent. */
    fun lookup(profile: HostProfile): List<KeymapEntry>?

    /** True if the host profile is present in the bundled database. */
    fun contains(profile: HostProfile): Boolean = lookup(profile) != null

    /** All host profiles known to this database (for diagnostics). */
    val profiles: Collection<HostProfile>
}
