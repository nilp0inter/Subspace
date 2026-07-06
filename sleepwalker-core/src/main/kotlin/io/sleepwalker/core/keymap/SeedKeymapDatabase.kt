package io.sleepwalker.core.keymap

/**
 * Seed/conformance keymap database.
 *
 * Contains a small US QWERTY dataset sufficient for unit tests and the
 * high-level text planning conformance scenario. This is NOT a complete
 * global keymap corpus — the [KeymapDatabase] contract allows a future
 * data-ingestion change to ship the full corpus without changing the
 * text rendering API.
 *
 * USB HID keyboard usage ids (usage page 0x07):
 *   a..z = 0x04..0x1d, 1..9 = 0x1e..0x26, 0 = 0x27
 *   space = 0x2c, enter = 0x28, escape = 0x29
 *
 * Modifier mask bits (USB HID keyboard report modifier byte):
 *   bit 0 = left ctrl, bit 1 = left shift, bit 2 = left alt,
 *   bit 3 = left gui, bit 4 = right ctrl, bit 5 = right shift,
 *   bit 6 = right alt, bit 7 = right gui
 */
object SeedKeymapDatabase : KeymapDatabase {
    private const val SHIFT = 0x02

    // USB HID keyboard usage ids.
    private const val A = 0x04
    private const val B = 0x05
    private const val C = 0x06
    private const val D = 0x07
    private const val E = 0x08
    private const val F = 0x09
    private const val G = 0x0A
    private const val H = 0x0B
    private const val I = 0x0C
    private const val J = 0x0D
    private const val K = 0x0E
    private const val L = 0x0F
    private const val M = 0x10
    private const val N = 0x11
    private const val O = 0x12
    private const val P = 0x13
    private const val Q = 0x14
    private const val R = 0x15
    private const val S = 0x16
    private const val T = 0x17
    private const val U = 0x18
    private const val V = 0x19
    private const val W = 0x1A
    private const val X = 0x1B
    private const val Y = 0x1C
    private const val Z = 0x1D
    private const val NUM_1 = 0x1E
    private const val NUM_2 = 0x1F
    private const val NUM_3 = 0x20
    private const val NUM_4 = 0x21
    private const val NUM_5 = 0x22
    private const val NUM_6 = 0x23
    private const val NUM_7 = 0x24
    private const val NUM_8 = 0x25
    private const val NUM_9 = 0x26
    private const val NUM_0 = 0x27
    private const val ENTER = 0x28
    private const val ESCAPE = 0x29
    private const val SPACE = 0x2C
    private const val MINUS = 0x2D
    private const val EQUAL = 0x2E
    private const val LEFTBRACE = 0x2F
    private const val RIGHTBRACE = 0x30
    private const val BACKSLASH = 0x31
    private const val SEMICOLON = 0x33
    private const val APOSTROPHE = 0x34
    private const val GRAVE = 0x35
    private const val COMMA = 0x36
    private const val DOT = 0x37
    private const val SLASH = 0x38

    private val US_ENTRIES: List<KeymapEntry> = listOf(
        // Lowercase letters.
        KeymapEntry('a', A, 0), KeymapEntry('b', B, 0),
        KeymapEntry('c', C, 0), KeymapEntry('d', D, 0),
        KeymapEntry('e', E, 0), KeymapEntry('f', F, 0),
        KeymapEntry('g', G, 0), KeymapEntry('h', H, 0),
        KeymapEntry('i', I, 0), KeymapEntry('j', J, 0),
        KeymapEntry('k', K, 0), KeymapEntry('l', L, 0),
        KeymapEntry('m', M, 0), KeymapEntry('n', N, 0),
        KeymapEntry('o', O, 0), KeymapEntry('p', P, 0),
        KeymapEntry('q', Q, 0), KeymapEntry('r', R, 0),
        KeymapEntry('s', S, 0), KeymapEntry('t', T, 0),
        KeymapEntry('u', U, 0), KeymapEntry('v', V, 0),
        KeymapEntry('w', W, 0), KeymapEntry('x', X, 0),
        KeymapEntry('y', Y, 0), KeymapEntry('z', Z, 0),
        // Uppercase letters (shift).
        KeymapEntry('A', A, SHIFT), KeymapEntry('B', B, SHIFT),
        KeymapEntry('C', C, SHIFT), KeymapEntry('D', D, SHIFT),
        KeymapEntry('E', E, SHIFT), KeymapEntry('F', F, SHIFT),
        KeymapEntry('G', G, SHIFT), KeymapEntry('H', H, SHIFT),
        KeymapEntry('I', I, SHIFT), KeymapEntry('J', J, SHIFT),
        KeymapEntry('K', K, SHIFT), KeymapEntry('L', L, SHIFT),
        KeymapEntry('M', M, SHIFT), KeymapEntry('N', N, SHIFT),
        KeymapEntry('O', O, SHIFT), KeymapEntry('P', P, SHIFT),
        KeymapEntry('Q', Q, SHIFT), KeymapEntry('R', R, SHIFT),
        KeymapEntry('S', S, SHIFT), KeymapEntry('T', T, SHIFT),
        KeymapEntry('U', U, SHIFT), KeymapEntry('V', V, SHIFT),
        KeymapEntry('W', W, SHIFT), KeymapEntry('X', X, SHIFT),
        KeymapEntry('Y', Y, SHIFT), KeymapEntry('Z', Z, SHIFT),
        // Digits.
        KeymapEntry('0', NUM_0, 0), KeymapEntry('1', NUM_1, 0),
        KeymapEntry('2', NUM_2, 0), KeymapEntry('3', NUM_3, 0),
        KeymapEntry('4', NUM_4, 0), KeymapEntry('5', NUM_5, 0),
        KeymapEntry('6', NUM_6, 0), KeymapEntry('7', NUM_7, 0),
        KeymapEntry('8', NUM_8, 0), KeymapEntry('9', NUM_9, 0),
        // Shifted digits / symbols.
        KeymapEntry('!', NUM_1, SHIFT), KeymapEntry('@', NUM_2, SHIFT),
        KeymapEntry('#', NUM_3, SHIFT), KeymapEntry('$', NUM_4, SHIFT),
        KeymapEntry('%', NUM_5, SHIFT), KeymapEntry('^', NUM_6, SHIFT),
        KeymapEntry('&', NUM_7, SHIFT), KeymapEntry('*', NUM_8, SHIFT),
        KeymapEntry('(', NUM_9, SHIFT), KeymapEntry(')', NUM_0, SHIFT),
        // Punctuation keys.
        KeymapEntry('-', MINUS, 0), KeymapEntry('_', MINUS, SHIFT),
        KeymapEntry('=', EQUAL, 0), KeymapEntry('+', EQUAL, SHIFT),
        KeymapEntry('[', LEFTBRACE, 0), KeymapEntry('{', LEFTBRACE, SHIFT),
        KeymapEntry(']', RIGHTBRACE, 0), KeymapEntry('}', RIGHTBRACE, SHIFT),
        KeymapEntry('\\', BACKSLASH, 0), KeymapEntry('|', BACKSLASH, SHIFT),
        KeymapEntry(';', SEMICOLON, 0), KeymapEntry(':', SEMICOLON, SHIFT),
        KeymapEntry('\'', APOSTROPHE, 0), KeymapEntry('"', APOSTROPHE, SHIFT),
        KeymapEntry('`', GRAVE, 0), KeymapEntry('~', GRAVE, SHIFT),
        KeymapEntry(',', COMMA, 0), KeymapEntry('<', COMMA, SHIFT),
        KeymapEntry('.', DOT, 0), KeymapEntry('>', DOT, SHIFT),
        KeymapEntry('/', SLASH, 0), KeymapEntry('?', SLASH, SHIFT),
        // Control keys.
        KeymapEntry(' ', SPACE, 0),
        KeymapEntry('\n', ENTER, 0),
        KeymapEntry('\u001b', ESCAPE, 0),
    )

    private val BY_KEY: Map<String, List<KeymapEntry>> = mapOf(
        HostProfile.LINUX_US.key to US_ENTRIES,
    )

    private val BY_CH: Map<Char, KeymapEntry> =
        US_ENTRIES.associateBy { it.ch }

    override fun lookup(profile: HostProfile): List<KeymapEntry>? =
        BY_KEY[profile.key]

    override val profiles: Collection<HostProfile> = listOf(HostProfile.LINUX_US)

    /** Resolve a single character to its keymap entry, or null. */
    fun entryFor(ch: Char): KeymapEntry? = BY_CH[ch]
}
