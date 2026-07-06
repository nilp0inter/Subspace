package io.sleepwalker.core.protocol

/**
 * Symbolic USB HID usages for the sleepwalker HID protocol.
 *
 * Mirror of firmware usages.h and protocol/src/sleepwalker_protocol/usages.py.
 * Symbolic names are used at external command boundaries instead of
 * platform-specific numeric keycodes.
 */
data class HidUsage(
    val name: String,
    val usbUsage: Int,
    val evdevCode: Int,
)

object Usages {
    val USB_KEY_NONE = HidUsage("USB_KEY_NONE", 0x00, 0)
    val USB_KEY_A = HidUsage("USB_KEY_A", 0x04, 30)
    val USB_KEY_B = HidUsage("USB_KEY_B", 0x05, 48)
    val USB_KEY_C = HidUsage("USB_KEY_C", 0x06, 46)
    val USB_KEY_D = HidUsage("USB_KEY_D", 0x07, 32)
    val USB_KEY_E = HidUsage("USB_KEY_E", 0x08, 18)
    val USB_KEY_F = HidUsage("USB_KEY_F", 0x09, 33)
    val USB_KEY_G = HidUsage("USB_KEY_G", 0x0A, 34)
    val USB_KEY_H = HidUsage("USB_KEY_H", 0x0B, 35)
    val USB_KEY_I = HidUsage("USB_KEY_I", 0x0C, 23)
    val USB_KEY_J = HidUsage("USB_KEY_J", 0x0D, 36)
    val USB_KEY_K = HidUsage("USB_KEY_K", 0x0E, 37)
    val USB_KEY_L = HidUsage("USB_KEY_L", 0x0F, 38)
    val USB_KEY_M = HidUsage("USB_KEY_M", 0x10, 50)
    val USB_KEY_N = HidUsage("USB_KEY_N", 0x11, 49)
    val USB_KEY_O = HidUsage("USB_KEY_O", 0x12, 24)
    val USB_KEY_P = HidUsage("USB_KEY_P", 0x13, 25)
    val USB_KEY_Q = HidUsage("USB_KEY_Q", 0x14, 16)
    val USB_KEY_R = HidUsage("USB_KEY_R", 0x15, 19)
    val USB_KEY_S = HidUsage("USB_KEY_S", 0x16, 31)
    val USB_KEY_T = HidUsage("USB_KEY_T", 0x17, 20)
    val USB_KEY_U = HidUsage("USB_KEY_U", 0x18, 22)
    val USB_KEY_V = HidUsage("USB_KEY_V", 0x19, 47)
    val USB_KEY_W = HidUsage("USB_KEY_W", 0x1A, 17)
    val USB_KEY_X = HidUsage("USB_KEY_X", 0x1B, 45)
    val USB_KEY_Y = HidUsage("USB_KEY_Y", 0x1C, 21)
    val USB_KEY_Z = HidUsage("USB_KEY_Z", 0x1D, 44)
    val USB_KEY_1 = HidUsage("USB_KEY_1", 0x1E, 2)
    val USB_KEY_2 = HidUsage("USB_KEY_2", 0x1F, 3)
    val USB_KEY_3 = HidUsage("USB_KEY_3", 0x20, 4)
    val USB_KEY_4 = HidUsage("USB_KEY_4", 0x21, 5)
    val USB_KEY_5 = HidUsage("USB_KEY_5", 0x22, 6)
    val USB_KEY_6 = HidUsage("USB_KEY_6", 0x23, 7)
    val USB_KEY_7 = HidUsage("USB_KEY_7", 0x24, 8)
    val USB_KEY_8 = HidUsage("USB_KEY_8", 0x25, 9)
    val USB_KEY_9 = HidUsage("USB_KEY_9", 0x26, 10)
    val USB_KEY_0 = HidUsage("USB_KEY_0", 0x27, 11)
    val USB_KEY_ENTER = HidUsage("USB_KEY_ENTER", 0x28, 28)
    val USB_KEY_ESCAPE = HidUsage("USB_KEY_ESCAPE", 0x29, 1)
    val USB_KEY_SPACE = HidUsage("USB_KEY_SPACE", 0x2C, 57)
    val USB_KEY_MINUS = HidUsage("USB_KEY_MINUS", 0x2D, 12)
    val USB_KEY_EQUAL = HidUsage("USB_KEY_EQUAL", 0x2E, 13)
    val USB_KEY_LEFTBRACE = HidUsage("USB_KEY_LEFTBRACE", 0x2F, 26)
    val USB_KEY_RIGHTBRACE = HidUsage("USB_KEY_RIGHTBRACE", 0x30, 27)
    val USB_KEY_BACKSLASH = HidUsage("USB_KEY_BACKSLASH", 0x31, 43)
    val USB_KEY_SEMICOLON = HidUsage("USB_KEY_SEMICOLON", 0x33, 39)
    val USB_KEY_APOSTROPHE = HidUsage("USB_KEY_APOSTROPHE", 0x34, 40)
    val USB_KEY_GRAVE = HidUsage("USB_KEY_GRAVE", 0x35, 41)
    val USB_KEY_COMMA = HidUsage("USB_KEY_COMMA", 0x36, 51)
    val USB_KEY_DOT = HidUsage("USB_KEY_DOT", 0x37, 52)
    val USB_KEY_SLASH = HidUsage("USB_KEY_SLASH", 0x38, 53)
    val USB_KEY_LEFTSHIFT = HidUsage("USB_KEY_LEFTSHIFT", 0xE1, 42)

    val REGISTRY: Map<String, HidUsage> = listOf(
        USB_KEY_NONE, USB_KEY_A, USB_KEY_B, USB_KEY_C, USB_KEY_D, USB_KEY_E, USB_KEY_F, USB_KEY_G,
        USB_KEY_H, USB_KEY_I, USB_KEY_J, USB_KEY_K, USB_KEY_L, USB_KEY_M, USB_KEY_N, USB_KEY_O,
        USB_KEY_P, USB_KEY_Q, USB_KEY_R, USB_KEY_S, USB_KEY_T, USB_KEY_U, USB_KEY_V, USB_KEY_W,
        USB_KEY_X, USB_KEY_Y, USB_KEY_Z, USB_KEY_1, USB_KEY_2, USB_KEY_3, USB_KEY_4, USB_KEY_5,
        USB_KEY_6, USB_KEY_7, USB_KEY_8, USB_KEY_9, USB_KEY_0, USB_KEY_ENTER, USB_KEY_ESCAPE,
        USB_KEY_SPACE, USB_KEY_MINUS, USB_KEY_EQUAL, USB_KEY_LEFTBRACE, USB_KEY_RIGHTBRACE,
        USB_KEY_BACKSLASH, USB_KEY_SEMICOLON, USB_KEY_APOSTROPHE, USB_KEY_GRAVE, USB_KEY_COMMA,
        USB_KEY_DOT, USB_KEY_SLASH, USB_KEY_LEFTSHIFT
    ).associateBy { it.name }
    val BY_USB: Map<Int, HidUsage> =
        REGISTRY.values.filter { it.usbUsage != 0 }.associateBy { it.usbUsage }

    val BY_EVDEV: Map<Int, HidUsage> =
        REGISTRY.values.filter { it.evdevCode != 0 }.associateBy { it.evdevCode }

    fun byName(name: String): HidUsage =
        REGISTRY[name] ?: throw NoSuchElementException("unknown usage: $name")

    fun byUsb(usbUsage: Int): HidUsage? = BY_USB[usbUsage]
    fun byEvdev(evdevCode: Int): HidUsage? = BY_EVDEV[evdevCode]
}