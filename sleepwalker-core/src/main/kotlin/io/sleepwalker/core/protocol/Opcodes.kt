package io.sleepwalker.core.protocol

/**
 * Command opcodes for the sleepwalker HID protocol.
 *
 * Mirror of firmware/components/sleepwalker_protocol/include/sleepwalker_protocol.h
 * and protocol/src/sleepwalker_protocol/opcodes.py. Must agree byte-for-byte.
 *
 * Device-class opcode namespaces:
 *   0x0000          - reserved / invalid
 *   0x0001..0x000F  - safety/control
 *   0x0010..0x00FF  - keyboard HID
 *   0x0100..0x01FF  - relative mouse HID
 *   0x0200..0x02FF  - future absolute pointer HID (reserved)
 *   0x0300..0x03FF  - future virtual serial / USB CDC (reserved)
 *   0x0400..0x04FF  - future capabilities/configuration (reserved)
 *   0xF000..0xFFFF  - private/test/invalid fixtures
 *
 * Reserved future namespaces decode as valid frames but are rejected by
 * current dispatch with STATUS_UNSUPPORTED_OPCODE.
 */
object Opcodes {
    const val RESERVED: Int = 0x0000

    // ---- Safety/control: 0x0001..0x000F ----
    const val ARM: Int = 0x0001
    const val DISARM: Int = 0x0002
    const val KILL: Int = 0x0003
    const val RELEASE_ALL: Int = 0x0004

    // ---- Keyboard HID: 0x0010..0x00FF ----
    const val KEY_TAP: Int = 0x0011
    const val KEY_DOWN: Int = 0x0012
    const val KEY_UP: Int = 0x0013
    const val KEYBOARD_TAP_SCRIPT: Int = 0x0014
    // ---- Relative mouse HID: 0x0100..0x01FF ----
    /** Raw relative mouse report. Payload: buttons,u8 dx,i8 dy,i8 wheel,i8 pan,i8. */
    const val MOUSE_REL_REPORT: Int = 0x0100

    // ---- Reserved future namespace bases ----
    const val ABS_POINTER_BASE: Int = 0x0200
    const val SERIAL_BASE: Int = 0x0300
    const val CAPABILITY_BASE: Int = 0x0400

    // ---- Fixture-only opcode outside the known set ----
    const val UNSUPPORTED_FIXTURE: Int = 0xFFFE

    /** All currently-implemented opcodes accepted by firmware dispatch. */
    val ALL: Set<Int> = setOf(
        ARM, DISARM, KILL, RELEASE_ALL,
        KEY_TAP, KEY_DOWN, KEY_UP, KEYBOARD_TAP_SCRIPT,
        MOUSE_REL_REPORT,
    )

    /** Namespace range table (low, high, name). */
    val NAMESPACE_RANGES: List<Triple<Int, Int, String>> = listOf(
        Triple(0x0001, 0x000F, "safety"),
        Triple(0x0010, 0x00FF, "keyboard"),
        Triple(0x0100, 0x01FF, "relative_mouse"),
        Triple(0x0200, 0x02FF, "absolute_pointer_reserved"),
        Triple(0x0300, 0x03FF, "serial_reserved"),
        Triple(0x0400, 0x04FF, "capability_reserved"),
        Triple(0xF000, 0xFFFF, "private_fixture"),
    )

    fun isKnown(opcode: Int): Boolean = opcode in ALL

    /** Classify an opcode into its device-class namespace, or null. */
    fun namespaceFor(opcode: Int): String? =
        NAMESPACE_RANGES.firstOrNull { opcode in it.first..it.second }?.third

    /** True if the opcode is in a reserved-but-unimplemented namespace. */
    fun isReservedFuture(opcode: Int): Boolean {
        val ns = namespaceFor(opcode)
        return ns == "absolute_pointer_reserved" ||
            ns == "serial_reserved" ||
            ns == "capability_reserved"
    }
}
