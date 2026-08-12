package com.voidlink.android.protocol.input

/**
 * Android key codes to Windows virtual-key codes (`docs/01-PROTOCOL.md` §10.3).
 *
 * Spec §10.3: *"`keyCode` is a **Windows VK code**, not an Android keycode. We need a translation
 * table (`KeyEvent.KEYCODE_A` → `0x41`, etc.). Build it explicitly; there is no shortcut."*
 *
 * Deliberately free of `android.view.KeyEvent`: the Android constants are numeric literals declared
 * below, so this whole file is a pure `Int` → `Int` function that runs and is tested on a plain JVM.
 * That matters more here than anywhere else in the input path, because a wrong entry is not a crash
 * — it is one key on a keyboard doing something else, which nobody notices until they use that key.
 *
 * ### What is deliberately absent
 *
 * * **Character semantics.** `KEYCODE_A` maps to `VK_A` whatever layout the device uses, because the
 *   host applies its *own* layout to the virtual-key code. A user with an AZERTY device streaming to
 *   a QWERTY host gets QWERTY, which is what the reference client does too. Typed text that must
 *   survive a layout difference goes through [InputSink.text] instead (spec §10.3's UTF-8 event).
 * * **Gamepad key codes.** `KEYCODE_BUTTON_*` are handled by the controller path as button flags,
 *   never as keystrokes; mapping them here would make a pad press letters.
 * * **Device keys** — Home, Back, Volume, Power. The Activity needs those, and forwarding them would
 *   make the phone unusable while streaming.
 */
object WindowsKeyCodes {

    /**
     * The Windows virtual-key code for [androidKeyCode], or `null` when there is no sensible one.
     *
     * A `null` means "do not send this key", not "send zero": a key-down for VK 0 is a key the host
     * may hold forever.
     */
    fun forAndroidKeyCode(androidKeyCode: Int): Int? = TABLE[androidKeyCode]

    /**
     * The protocol modifier mask for an Android `KeyEvent` meta state (spec §10.3).
     *
     * The four `META_*_ON` values below are Android's; they are literals for the same reason the key
     * table is.
     */
    fun modifiersFromMetaState(metaState: Int): Int {
        var modifiers = 0
        if (metaState and META_SHIFT_ON != 0) modifiers = modifiers or InputConstants.MODIFIER_SHIFT
        if (metaState and META_CTRL_ON != 0) modifiers = modifiers or InputConstants.MODIFIER_CTRL
        if (metaState and META_ALT_ON != 0) modifiers = modifiers or InputConstants.MODIFIER_ALT
        if (metaState and META_META_ON != 0) modifiers = modifiers or InputConstants.MODIFIER_META
        return modifiers
    }

    /** `KeyEvent.META_SHIFT_ON`. */
    const val META_SHIFT_ON: Int = 0x00000001

    /** `KeyEvent.META_ALT_ON`. */
    const val META_ALT_ON: Int = 0x00000002

    /** `KeyEvent.META_CTRL_ON`. */
    const val META_CTRL_ON: Int = 0x00001000

    /** `KeyEvent.META_META_ON`. */
    const val META_META_ON: Int = 0x00010000

    // ---- Android key codes (android.view.KeyEvent), as literals --------------------------------

    private const val KEYCODE_0: Int = 7
    private const val KEYCODE_9: Int = 16
    private const val KEYCODE_DPAD_UP: Int = 19
    private const val KEYCODE_DPAD_DOWN: Int = 20
    private const val KEYCODE_DPAD_LEFT: Int = 21
    private const val KEYCODE_DPAD_RIGHT: Int = 22
    private const val KEYCODE_A: Int = 29
    private const val KEYCODE_Z: Int = 54
    private const val KEYCODE_COMMA: Int = 55
    private const val KEYCODE_PERIOD: Int = 56
    private const val KEYCODE_ALT_LEFT: Int = 57
    private const val KEYCODE_ALT_RIGHT: Int = 58
    private const val KEYCODE_SHIFT_LEFT: Int = 59
    private const val KEYCODE_SHIFT_RIGHT: Int = 60
    private const val KEYCODE_TAB: Int = 61
    private const val KEYCODE_SPACE: Int = 62
    private const val KEYCODE_ENTER: Int = 66
    private const val KEYCODE_DEL: Int = 67
    private const val KEYCODE_GRAVE: Int = 68
    private const val KEYCODE_MINUS: Int = 69
    private const val KEYCODE_EQUALS: Int = 70
    private const val KEYCODE_LEFT_BRACKET: Int = 71
    private const val KEYCODE_RIGHT_BRACKET: Int = 72
    private const val KEYCODE_BACKSLASH: Int = 73
    private const val KEYCODE_SEMICOLON: Int = 74
    private const val KEYCODE_APOSTROPHE: Int = 75
    private const val KEYCODE_SLASH: Int = 76
    private const val KEYCODE_PAGE_UP: Int = 92
    private const val KEYCODE_PAGE_DOWN: Int = 93
    private const val KEYCODE_ESCAPE: Int = 111
    private const val KEYCODE_FORWARD_DEL: Int = 112
    private const val KEYCODE_CTRL_LEFT: Int = 113
    private const val KEYCODE_CTRL_RIGHT: Int = 114
    private const val KEYCODE_CAPS_LOCK: Int = 115
    private const val KEYCODE_SCROLL_LOCK: Int = 116
    private const val KEYCODE_META_LEFT: Int = 117
    private const val KEYCODE_META_RIGHT: Int = 118
    private const val KEYCODE_SYSRQ: Int = 120
    private const val KEYCODE_BREAK: Int = 121
    private const val KEYCODE_MOVE_HOME: Int = 122
    private const val KEYCODE_MOVE_END: Int = 123
    private const val KEYCODE_INSERT: Int = 124
    private const val KEYCODE_F1: Int = 131
    private const val KEYCODE_F12: Int = 142
    private const val KEYCODE_NUM_LOCK: Int = 143
    private const val KEYCODE_NUMPAD_0: Int = 144
    private const val KEYCODE_NUMPAD_9: Int = 153
    private const val KEYCODE_NUMPAD_DIVIDE: Int = 154
    private const val KEYCODE_NUMPAD_MULTIPLY: Int = 155
    private const val KEYCODE_NUMPAD_SUBTRACT: Int = 156
    private const val KEYCODE_NUMPAD_ADD: Int = 157
    private const val KEYCODE_NUMPAD_DOT: Int = 158
    private const val KEYCODE_NUMPAD_COMMA: Int = 159
    private const val KEYCODE_NUMPAD_ENTER: Int = 160
    private const val KEYCODE_NUMPAD_EQUALS: Int = 161

    // ---- Windows virtual-key codes -------------------------------------------------------------

    private const val VK_BACK: Int = 0x08
    private const val VK_TAB: Int = 0x09
    private const val VK_RETURN: Int = 0x0D
    private const val VK_PAUSE: Int = 0x13
    private const val VK_CAPITAL: Int = 0x14
    private const val VK_ESCAPE: Int = 0x1B
    private const val VK_SPACE: Int = 0x20
    private const val VK_PRIOR: Int = 0x21
    private const val VK_NEXT: Int = 0x22
    private const val VK_END: Int = 0x23
    private const val VK_HOME: Int = 0x24
    private const val VK_LEFT: Int = 0x25
    private const val VK_UP: Int = 0x26
    private const val VK_RIGHT: Int = 0x27
    private const val VK_DOWN: Int = 0x28
    private const val VK_SNAPSHOT: Int = 0x2C
    private const val VK_INSERT: Int = 0x2D
    private const val VK_DELETE: Int = 0x2E
    private const val VK_0: Int = 0x30
    private const val VK_A: Int = 0x41
    private const val VK_LWIN: Int = 0x5B
    private const val VK_RWIN: Int = 0x5C
    private const val VK_NUMPAD0: Int = 0x60
    private const val VK_MULTIPLY: Int = 0x6A
    private const val VK_ADD: Int = 0x6B
    private const val VK_SEPARATOR: Int = 0x6C
    private const val VK_SUBTRACT: Int = 0x6D
    private const val VK_DECIMAL: Int = 0x6E
    private const val VK_DIVIDE: Int = 0x6F
    private const val VK_F1: Int = 0x70
    private const val VK_NUMLOCK: Int = 0x90
    private const val VK_SCROLL: Int = 0x91
    private const val VK_LSHIFT: Int = 0xA0
    private const val VK_RSHIFT: Int = 0xA1
    private const val VK_LCONTROL: Int = 0xA2
    private const val VK_RCONTROL: Int = 0xA3
    private const val VK_LMENU: Int = 0xA4
    private const val VK_RMENU: Int = 0xA5

    /** `VK_OEM_1` — the `;:` key on a US layout. */
    private const val VK_OEM_1: Int = 0xBA

    /** `VK_OEM_PLUS` — `=+`, and *not* the numpad plus. */
    private const val VK_OEM_PLUS: Int = 0xBB
    private const val VK_OEM_COMMA: Int = 0xBC
    private const val VK_OEM_MINUS: Int = 0xBD
    private const val VK_OEM_PERIOD: Int = 0xBE

    /** `VK_OEM_2` — `/?`. */
    private const val VK_OEM_2: Int = 0xBF

    /** `VK_OEM_3` — the backtick/tilde key. */
    private const val VK_OEM_3: Int = 0xC0

    /** `VK_OEM_4` — `[{`. */
    private const val VK_OEM_4: Int = 0xDB

    /** `VK_OEM_5` — `\|`. */
    private const val VK_OEM_5: Int = 0xDC

    /** `VK_OEM_6` — `]}`. */
    private const val VK_OEM_6: Int = 0xDD

    /** `VK_OEM_7` — `'"`. */
    private const val VK_OEM_7: Int = 0xDE

    /**
     * The table, built once.
     *
     * Ranges are generated rather than written out — `KEYCODE_A`..`KEYCODE_Z` and `VK_A`..`VK_Z` are
     * both contiguous and in the same order, as are the digits, the numpad digits and the function
     * keys — because twenty-six hand-written lines are twenty-six chances to transpose two of them.
     * Everything that is *not* contiguous is written out one entry per line.
     */
    private val TABLE: Map<Int, Int> = buildMap {
        for (offset in 0..(KEYCODE_Z - KEYCODE_A)) put(KEYCODE_A + offset, VK_A + offset)
        for (offset in 0..(KEYCODE_9 - KEYCODE_0)) put(KEYCODE_0 + offset, VK_0 + offset)
        for (offset in 0..(KEYCODE_NUMPAD_9 - KEYCODE_NUMPAD_0)) {
            put(KEYCODE_NUMPAD_0 + offset, VK_NUMPAD0 + offset)
        }
        for (offset in 0..(KEYCODE_F12 - KEYCODE_F1)) put(KEYCODE_F1 + offset, VK_F1 + offset)

        put(KEYCODE_DPAD_UP, VK_UP)
        put(KEYCODE_DPAD_DOWN, VK_DOWN)
        put(KEYCODE_DPAD_LEFT, VK_LEFT)
        put(KEYCODE_DPAD_RIGHT, VK_RIGHT)

        put(KEYCODE_COMMA, VK_OEM_COMMA)
        put(KEYCODE_PERIOD, VK_OEM_PERIOD)
        put(KEYCODE_ALT_LEFT, VK_LMENU)
        put(KEYCODE_ALT_RIGHT, VK_RMENU)
        put(KEYCODE_SHIFT_LEFT, VK_LSHIFT)
        put(KEYCODE_SHIFT_RIGHT, VK_RSHIFT)
        put(KEYCODE_TAB, VK_TAB)
        put(KEYCODE_SPACE, VK_SPACE)
        put(KEYCODE_ENTER, VK_RETURN)
        // Android's DEL is Backspace; its FORWARD_DEL is the Delete key. Getting these two the
        // wrong way round deletes the character on the wrong side of the caret, every time.
        put(KEYCODE_DEL, VK_BACK)
        put(KEYCODE_FORWARD_DEL, VK_DELETE)
        put(KEYCODE_GRAVE, VK_OEM_3)
        put(KEYCODE_MINUS, VK_OEM_MINUS)
        put(KEYCODE_EQUALS, VK_OEM_PLUS)
        put(KEYCODE_LEFT_BRACKET, VK_OEM_4)
        put(KEYCODE_RIGHT_BRACKET, VK_OEM_6)
        put(KEYCODE_BACKSLASH, VK_OEM_5)
        put(KEYCODE_SEMICOLON, VK_OEM_1)
        put(KEYCODE_APOSTROPHE, VK_OEM_7)
        put(KEYCODE_SLASH, VK_OEM_2)
        put(KEYCODE_PAGE_UP, VK_PRIOR)
        put(KEYCODE_PAGE_DOWN, VK_NEXT)
        put(KEYCODE_ESCAPE, VK_ESCAPE)
        put(KEYCODE_CTRL_LEFT, VK_LCONTROL)
        put(KEYCODE_CTRL_RIGHT, VK_RCONTROL)
        put(KEYCODE_CAPS_LOCK, VK_CAPITAL)
        put(KEYCODE_SCROLL_LOCK, VK_SCROLL)
        put(KEYCODE_META_LEFT, VK_LWIN)
        put(KEYCODE_META_RIGHT, VK_RWIN)
        put(KEYCODE_SYSRQ, VK_SNAPSHOT)
        put(KEYCODE_BREAK, VK_PAUSE)
        put(KEYCODE_MOVE_HOME, VK_HOME)
        put(KEYCODE_MOVE_END, VK_END)
        put(KEYCODE_INSERT, VK_INSERT)
        put(KEYCODE_NUM_LOCK, VK_NUMLOCK)
        put(KEYCODE_NUMPAD_DIVIDE, VK_DIVIDE)
        put(KEYCODE_NUMPAD_MULTIPLY, VK_MULTIPLY)
        put(KEYCODE_NUMPAD_SUBTRACT, VK_SUBTRACT)
        put(KEYCODE_NUMPAD_ADD, VK_ADD)
        put(KEYCODE_NUMPAD_DOT, VK_DECIMAL)
        put(KEYCODE_NUMPAD_COMMA, VK_SEPARATOR)
        // Windows has no distinct numpad Enter or numpad Equals virtual key: both report as the
        // main-keyboard key, with the extended-key bit distinguishing them at a level we never see.
        put(KEYCODE_NUMPAD_ENTER, VK_RETURN)
        put(KEYCODE_NUMPAD_EQUALS, VK_OEM_PLUS)
    }
}
