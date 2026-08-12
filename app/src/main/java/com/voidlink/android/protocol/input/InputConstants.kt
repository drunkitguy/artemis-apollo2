package com.voidlink.android.protocol.input

import com.voidlink.android.protocol.ProtocolLog

/**
 * Every constant the GameStream input protocol needs, transcribed from `docs/01-PROTOCOL.md` §10
 * and cross-referenced by section.
 *
 * Mirrors the role [com.voidlink.android.protocol.control.ControlConstants] plays for the control
 * stream: one constants file per subsystem while the packages are being written. Nothing else under
 * `protocol/input/` may define a protocol constant.
 *
 * Values the spec marks **UNVERIFIED**, *and the places where spec §10 and the reference client
 * disagree*, live in [UnverifiedInputConstants] so the guessed surface of the input path stays
 * countable at a glance. There are more of the second kind than the first here, and they are the
 * single most likely reason for "input does nothing" — see that object's header.
 */
object InputConstants {

    /** Subsystem tag for the input path, matching architecture §9's tag table. */
    const val TAG: String = "VL.Input"

    // ---- Common header (spec §10.2) ------------------------------------------------------------

    /**
     * `{ uint32 size; uint32 magic }`.
     *
     * `size` is **big-endian** and counts everything after itself (so `4 + body`); `magic` is
     * **little-endian**. Two different orders four bytes apart, in the first eight bytes of every
     * packet — spec §0.1 calls endianness the number-one bug source and this is where it starts.
     */
    const val HEADER_SIZE: Int = 8

    /** Width of the `size` field, which is excluded from its own value (spec §10.2). */
    const val SIZE_FIELD_BYTES: Int = 4

    // ---- Magics (spec §10.3) -------------------------------------------------------------------

    const val MAGIC_KEY_DOWN: Int = 0x00000003
    const val MAGIC_KEY_UP: Int = 0x00000004
    const val MAGIC_UTF8_TEXT: Int = 0x00000017
    const val MAGIC_MOUSE_MOVE_REL: Int = 0x00000006
    const val MAGIC_MOUSE_MOVE_REL_GEN5: Int = 0x00000007
    const val MAGIC_MOUSE_MOVE_ABS: Int = 0x00000005
    const val MAGIC_MOUSE_BUTTON_DOWN_GEN5: Int = 0x00000008
    const val MAGIC_MOUSE_BUTTON_UP_GEN5: Int = 0x00000009

    /**
     * Mouse button magics before Gen 5, which are the Gen 5 values minus one.
     *
     * The reference client writes this as `magic = action; if (gen >= 5) magic++`, where the
     * actions are `BUTTON_ACTION_PRESS = 0x07` and `BUTTON_ACTION_RELEASE = 0x08`.
     */
    const val MAGIC_MOUSE_BUTTON_DOWN: Int = 0x00000007
    const val MAGIC_MOUSE_BUTTON_UP: Int = 0x00000008

    const val MAGIC_SCROLL: Int = 0x00000009
    const val MAGIC_SCROLL_GEN5: Int = 0x0000000A
    const val MAGIC_HSCROLL: Int = 0x55000001

    /** The single-controller packet of Gen 3. Collides numerically with [MAGIC_SCROLL_GEN5]. */
    const val MAGIC_CONTROLLER: Int = 0x0000000A
    const val MAGIC_MULTI_CONTROLLER: Int = 0x0000000D
    const val MAGIC_MULTI_CONTROLLER_GEN5: Int = 0x0000000C

    /** Haptics-enable. Collides numerically with [MAGIC_MULTI_CONTROLLER] on Gen < 5. */
    const val MAGIC_ENABLE_HAPTICS: Int = 0x0000000D

    const val MAGIC_SS_TOUCH: Int = 0x55000002
    const val MAGIC_SS_PEN: Int = 0x55000003
    const val MAGIC_SS_CONTROLLER_ARRIVAL: Int = 0x55000004
    const val MAGIC_SS_CONTROLLER_TOUCH: Int = 0x55000005
    const val MAGIC_SS_CONTROLLER_MOTION: Int = 0x55000006
    const val MAGIC_SS_CONTROLLER_BATTERY: Int = 0x55000007

    // ---- Packet body sizes (spec §10.3) --------------------------------------------------------
    //
    // Counted field by field rather than assumed: spec §0.2 makes every struct `#pragma pack(1)`,
    // so a body that is two bytes too long is two zero bytes the host reads as the start of the
    // next field.

    /** `int8 flags, int16 keyCode, int8 modifiers, int16 zero2`. */
    const val BODY_KEYBOARD: Int = 1 + 2 + 1 + 2

    /** `int16 deltaX, int16 deltaY`. */
    const val BODY_MOUSE_MOVE_REL: Int = 2 + 2

    /** `int16 x, y, unused, width, height`. */
    const val BODY_MOUSE_MOVE_ABS: Int = 5 * 2

    /** `uint8 button`. */
    const val BODY_MOUSE_BUTTON: Int = 1

    /** `int16 scrollAmt1, int16 scrollAmt2, int16 zero3`. */
    const val BODY_SCROLL: Int = 3 * 2

    /** `int16 scrollAmount`. */
    const val BODY_HSCROLL: Int = 2

    /** `uint16 enable`. */
    const val BODY_ENABLE_HAPTICS: Int = 2

    /** headerB, buttonFlags, 2 triggers, 4 sticks, `int32` tailA, tailB. */
    const val BODY_CONTROLLER: Int = 2 + 2 + 1 + 1 + 4 * 2 + 4 + 2

    /** headerB, number, mask, midB, flags, 2 triggers, 4 sticks, tailA, flags2, tailB. */
    const val BODY_MULTI_CONTROLLER: Int = 4 * 2 + 2 + 1 + 1 + 4 * 2 + 2 + 2 + 2

    /** `uint8 number, uint8 type, uint16 capabilities, uint32 supportedButtonFlags`. */
    const val BODY_SS_CONTROLLER_ARRIVAL: Int = 1 + 1 + 2 + 4

    /** `uint8 number, uint8 motionType, uint8[2] zero, netfloat x, y, z`. */
    const val BODY_SS_CONTROLLER_MOTION: Int = 4 + 3 * 4

    /** `uint8 number, eventType, zero, touchpadIndex, uint32 pointerId, netfloat x, y, pressure`. */
    const val BODY_SS_CONTROLLER_TOUCH: Int = 4 + 4 + 3 * 4

    /** `uint8 number, batteryState, batteryPercentage, zero`. */
    const val BODY_SS_CONTROLLER_BATTERY: Int = 4

    /** eventType, zero, rotation, pointerId, and five netfloats. */
    const val BODY_SS_TOUCH: Int = 1 + 1 + 2 + 4 + 5 * 4

    /** eventType, toolType, penButtons, zero, 3 netfloats, rotation, tilt, zero2, 2 netfloats. */
    const val BODY_SS_PEN: Int = 4 + 3 * 4 + 2 + 1 + 1 + 2 * 4

    /** `UTF8_TEXT_EVENT_MAX_COUNT` — the longest text body the host accepts (spec §10.3). */
    const val UTF8_TEXT_MAX_BYTES: Int = 32

    // ---- Keyboard (spec §10.3) -----------------------------------------------------------------

    const val MODIFIER_SHIFT: Int = 0x01
    const val MODIFIER_CTRL: Int = 0x02
    const val MODIFIER_ALT: Int = 0x04
    const val MODIFIER_META: Int = 0x08

    /** `SS_KBE_FLAG_NON_NORMALIZED` — Sunshine only; zero for GFE (spec §10.3). */
    const val KEYBOARD_FLAG_NON_NORMALIZED: Int = 0x01

    // ---- Mouse (spec §10.3) --------------------------------------------------------------------

    const val MOUSE_BUTTON_LEFT: Int = 1
    const val MOUSE_BUTTON_MIDDLE: Int = 2
    const val MOUSE_BUTTON_RIGHT: Int = 3
    const val MOUSE_BUTTON_X1: Int = 4
    const val MOUSE_BUTTON_X2: Int = 5

    /** The Windows `WHEEL_DELTA`: one wheel click (spec §10.3). */
    const val WHEEL_DELTA: Int = 120

    // ---- Controller structure constants (spec §10.3) -------------------------------------------

    const val C_HEADER_B: Int = 0x1400
    const val C_TAIL_A: Int = 0x0000009C
    const val C_TAIL_B: Int = 0x0055

    const val MC_HEADER_B: Int = 0x001A
    const val MC_MID_B: Int = 0x0014
    const val MC_TAIL_A: Int = 0x009C
    const val MC_TAIL_B: Int = 0x0055

    /** GFE exposes four pads; `activeGamepadMask` is masked to four bits for it (spec §10.3). */
    const val MAX_GAMEPADS_GFE: Int = 4

    /** Sunshine exposes sixteen — the width of `activeGamepadMask` (spec §10.3). */
    const val MAX_GAMEPADS_SUNSHINE: Int = 16

    // ---- Button flags (spec §10.3) -------------------------------------------------------------

    const val BUTTON_UP: Int = 0x0001
    const val BUTTON_DOWN: Int = 0x0002
    const val BUTTON_LEFT: Int = 0x0004
    const val BUTTON_RIGHT: Int = 0x0008
    const val BUTTON_PLAY: Int = 0x0010
    const val BUTTON_BACK: Int = 0x0020
    const val BUTTON_LS_CLK: Int = 0x0040
    const val BUTTON_RS_CLK: Int = 0x0080
    const val BUTTON_LB: Int = 0x0100
    const val BUTTON_RB: Int = 0x0200
    const val BUTTON_SPECIAL: Int = 0x0400
    const val BUTTON_A: Int = 0x1000
    const val BUTTON_B: Int = 0x2000
    const val BUTTON_X: Int = 0x4000
    const val BUTTON_Y: Int = 0x8000

    /** Sunshine extensions; they occupy the high 16 bits and travel in `buttonFlags2`. */
    const val BUTTON_PADDLE1: Int = 0x010000
    const val BUTTON_PADDLE2: Int = 0x020000
    const val BUTTON_PADDLE3: Int = 0x040000
    const val BUTTON_PADDLE4: Int = 0x080000
    const val BUTTON_TOUCHPAD: Int = 0x100000
    const val BUTTON_MISC: Int = 0x200000

    /** Every button an Xbox-style pad can produce, for `supportedButtonFlags` (spec §10.3). */
    const val BUTTONS_STANDARD: Int = BUTTON_UP or BUTTON_DOWN or BUTTON_LEFT or BUTTON_RIGHT or
        BUTTON_PLAY or BUTTON_BACK or BUTTON_LS_CLK or BUTTON_RS_CLK or BUTTON_LB or BUTTON_RB or
        BUTTON_SPECIAL or BUTTON_A or BUTTON_B or BUTTON_X or BUTTON_Y

    // ---- Controller capabilities (spec §10.3) --------------------------------------------------

    const val CCAP_ANALOG_TRIGGERS: Int = 0x001
    const val CCAP_RUMBLE: Int = 0x002
    const val CCAP_TRIGGER_RUMBLE: Int = 0x004
    const val CCAP_TOUCHPAD: Int = 0x008
    const val CCAP_ACCEL: Int = 0x010
    const val CCAP_GYRO: Int = 0x020
    const val CCAP_BATTERY_STATE: Int = 0x040
    const val CCAP_RGB_LED: Int = 0x080
    const val CCAP_DUAL_TOUCHPAD: Int = 0x100

    // ---- Motion (spec §10.3) -------------------------------------------------------------------

    /** Android reports gyroscope rates in rad/s; the host wants deg/s (spec §10.3). */
    const val RADIANS_TO_DEGREES: Float = 57.29578f

    /** `LI_ROT_UNKNOWN` — the rotation value meaning "we do not know" (spec §10.3). */
    const val ROTATION_UNKNOWN: Int = 0xFFFF

    /** `LI_BATTERY_PERCENTAGE_UNKNOWN` (spec §10.3). */
    const val BATTERY_PERCENTAGE_UNKNOWN: Int = 0xFF

    // ---- Encryption (spec §10.1) ---------------------------------------------------------------

    /** The remote-input key from `/launch?rikey=` is 16 bytes (spec §5). */
    const val KEY_BYTES: Int = 16

    /** The protocol's IV is 16 bytes; GCM on the JVM takes 12 of them (spec §10.1). */
    const val IV_BYTES: Int = 16

    /** `GCMParameterSpec` accepts only a 12-byte nonce on the JVM (spec §10.1). */
    const val GCM_IV_BYTES: Int = 12

    /** The AES-GCM tag, which the wire format puts **before** the ciphertext (spec §10.1). */
    const val GCM_TAG_BYTES: Int = 16

    const val GCM_TAG_BITS: Int = GCM_TAG_BYTES * 8
    const val GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"
    const val CBC_TRANSFORMATION: String = "AES/CBC/PKCS5Padding"
    const val KEY_ALGORITHM: String = "AES"

    /** AES-GCM arrived in Gen 7; Gen 5/6 use AES-CBC (spec §10.1). */
    const val GCM_MIN_GENERATION: Int = 7

    /** Width of the big-endian length prefix that precedes the ciphertext (spec §10). */
    const val ENCRYPTED_LENGTH_PREFIX_BYTES: Int = 4

    // ---- Batching (spec §10.4) -----------------------------------------------------------------

    /**
     * Spec §10.4: relative mouse moves are coalesced and flushed "at most once per ~4 ms".
     *
     * The reference client uses 1 ms and explains why the wait *reduces* latency: it stops
     * per-event packets from queueing inside ENet. 4 ms is the spec's number and is used because
     * it is also roughly a frame at 240 Hz, below anything a user can perceive.
     */
    const val MOUSE_BATCH_INTERVAL_MS: Long = 4L

    /** Largest delta a single relative-move packet can carry; bigger moves are split. */
    const val MOUSE_DELTA_MAX: Int = Short.MAX_VALUE.toInt()

    /** Smallest delta a single relative-move packet can carry. */
    const val MOUSE_DELTA_MIN: Int = Short.MIN_VALUE.toInt()

    /** Stick axes are `int16` (spec §10.3). */
    const val STICK_MAX: Int = Short.MAX_VALUE.toInt()
    const val STICK_MIN: Int = Short.MIN_VALUE.toInt()

    /** Triggers are `uint8` (spec §10.3). */
    const val TRIGGER_MAX: Int = 255

    /** UI spec §5.4: Native Touch forwards up to ten simultaneous pointers. */
    const val MAX_TOUCH_POINTERS: Int = 10
}

/**
 * Input-path decisions that are **guesses**, and the places where `docs/01-PROTOCOL.md` §10 and the
 * reference client (`moonlight-common-c`'s `InputStream.c` / `Input.h`) disagree.
 *
 * Collected in one object for the same reason as
 * [com.voidlink.android.protocol.control.UnverifiedControlConstants]: a debugging session against a
 * real host needs exactly one file to experiment in.
 *
 * **Read this before debugging "the host ignores my input".** Two classes of entry live here:
 *
 * 1. Things spec §10 itself flags UNVERIFIED — chiefly the [InputIvMode] chaining rule, which is
 *    the highest-risk item in the whole input path because a wrong IV means every packet fails its
 *    GCM tag check on the host and is dropped **silently**.
 * 2. Fields where spec §10.3's blanket rule ("multi-byte body fields are big-endian unless
 *    netfloat") contradicts the reference client. The reference is what today's hosts parse, so it
 *    wins here, but every divergence is named below and most are switchable.
 */
object UnverifiedInputConstants {

    /**
     * Which IV rule the AES-GCM input cipher uses. **Runtime-switchable, on purpose.**
     *
     * Spec §10.1: "the precise IV chaining rule is the least well-documented part of the input
     * path, and getting it wrong means the host silently discards all our input (no error, just an
     * unresponsive game)", followed by an instruction to "put this behind a strategy interface with
     * a second implementation … selectable by a debug setting". This field is that setting; see
     * [InputIvMode] for what each option does and [InputEncryptor] for where it is read.
     *
     * Written from the UI thread and read from the input thread, so `@Volatile`.
     */
    @Volatile
    @JvmField
    var ivMode: InputIvMode = InputIvMode.CHAINED_REFERENCE

    /**
     * Which 12 bytes of the protocol's 16-byte IV become the GCM nonce.
     *
     * UNVERIFIED(spec 01 §10.1): "Java requires a 12-byte IV for `GCMParameterSpec`; the protocol
     * supplies 16. Use the **first 12 bytes**… if input is rejected, trying the last 12 bytes is
     * the second thing to test."
     *
     * There is a third possibility the spec does not name, and it is worth knowing before a
     * debugging session burns an hour: OpenSSL — which is what both hosts use — does **not**
     * truncate a 16-byte GCM IV. For any nonce that is not 96 bits it derives the counter block by
     * GHASHing the nonce, so neither truncation reproduces it exactly. If both truncations fail
     * against a host whose input is otherwise correct, that derivation, not this switch, is the
     * next suspect. It is deliberately not implemented here: it needs a hand-rolled GCM, and the
     * first 16 bytes of our IV are `riKeyId || zeros`, which is exactly the case where a host may
     * well be normalising too.
     */
    @Volatile
    @JvmField
    var useFirstTwelveIvBytes: Boolean = true

    /**
     * Whether the keyboard packet's `keyCode` is written little-endian.
     *
     * **Spec §10.3 and the reference client disagree.** Spec §10.3 says every multi-byte body field
     * is big-endian; the reference writes `holder->packet.keyboard.keyCode = LE16(keyCode)`. We
     * follow the reference (`true`), because that is the byte order hosts actually parse.
     *
     * Risk if wrong: every key arrives as a different key — `A` (0x41) read big-endian from a
     * little-endian field is 0x4100, which is not a virtual-key code at all, so nothing happens.
     * Flip this if the keyboard does nothing while the mouse works.
     */
    @Volatile
    @JvmField
    var keyCodeLittleEndian: Boolean = true

    /**
     * Whether controller packet fields (`buttonFlags`, sticks, the structural constants) are
     * written little-endian.
     *
     * **Spec §10.3 and the reference client disagree**, exactly as for [keyCodeLittleEndian]: the
     * reference writes every field of both controller packets with `LE16`/`LE32`. We follow the
     * reference (`true`).
     *
     * Risk if wrong: the host sees `headerB = 0x1A00` instead of `0x001A` and rejects the packet,
     * or worse, accepts it and reads the sticks byte-swapped — a stick pushed fully right reads as
     * a small negative value. Flip this if the pad is dead or wildly wrong while the mouse works.
     */
    @Volatile
    @JvmField
    var controllerFieldsLittleEndian: Boolean = true

    /**
     * Whether the Sunshine extension packets (`SS_*`) write their integers little-endian.
     *
     * Same disagreement, same resolution: the reference writes `pointerId`, `rotation`,
     * `capabilities` and `supportedButtonFlags` with `LE16`/`LE32`. The `netfloat`s are
     * little-endian in both readings, so touch coordinates would survive a wrong choice here while
     * pointer identity would not.
     */
    @Volatile
    @JvmField
    var sunshineFieldsLittleEndian: Boolean = true

    /**
     * How many bytes are subtracted from the absolute-mouse reference dimensions.
     *
     * Not in spec §10.3, which says plainly "send our stream's video dimensions as `width`/
     * `height`". The reference client subtracts one from each and explains why:
     *
     * > There appears to be a rounding error in GFE's scaling calculation which prevents the cursor
     * > from reaching the far edge of the screen when streaming at smaller resolutions with a
     * > higher desktop resolution.
     *
     * Applied by [InputSender], never by [InputPackets], so the builder stays a faithful
     * transcription of the spec and the workaround stays visible and removable.
     */
    @Volatile
    @JvmField
    var absoluteMouseReferenceAdjustment: Int = 1

    /**
     * Per-axis sign flips applied to motion samples after the display-rotation transform.
     *
     * UNVERIFIED(spec 01 §10.3): "Axis orientation must be mapped from Android's device frame to
     * the controller frame; expect to need per-axis sign flips, and expose 'invert X/Y' as a debug
     * setting." These are that setting.
     *
     * The defaults are all `false`, i.e. the display-rotated Android frame is sent as-is, because
     * that is the one choice with a stated derivation ([MotionMath.transform]) rather than a guess.
     * A game whose gyro aim goes the wrong way vertically wants [invertMotionY]; horizontally,
     * [invertMotionX]. Z is roll and is rarely bound to anything.
     */
    @Volatile
    @JvmField
    var invertMotionX: Boolean = false

    /** See [invertMotionX]. */
    @Volatile
    @JvmField
    var invertMotionY: Boolean = false

    /** See [invertMotionX]. */
    @Volatile
    @JvmField
    var invertMotionZ: Boolean = false

    /**
     * Whether the plain Windows virtual-key code is sent, or `vk or 0x8000`.
     *
     * UNVERIFIED(spec 01 §10.3): "whether GFE expects the VK code to be offset or transformed (some
     * clients send `keyCode | 0x8000`). **v1: send the plain VK code**, and if keyboard input does
     * not register, test the `0x8000` variant."
     */
    @Volatile
    @JvmField
    var keyCodeHighBit: Boolean = false

    /**
     * Whether input packets are sent reliably.
     *
     * Spec §10.4: "**UNVERIFIED:** whether hosts tolerate unreliable input packets given the
     * AES-GCM IV chaining (a dropped packet would desynchronize the IV). **v1: send ALL input
     * reliably.** This is the safe choice and the IV chaining strongly suggests it is the required
     * one." The reference client agrees — every input packet it sends is
     * `ENET_PACKET_FLAG_RELIABLE`, including relative mouse moves, with a TODO wishing otherwise.
     *
     * This constant documents the decision; delivery is chosen by the session's transport, which
     * this package does not reach into.
     */
    const val SEND_INPUT_RELIABLY: Boolean = true

    /**
     * How many outgoing input packets are logged as hex at WARN.
     *
     * Spec §10.1's mitigation for the IV rule ends with an acceptance test — "move the mouse and
     * see the host's cursor move" — that tells you *whether* input works but nothing about *why*
     * not. These log lines are the difference: they carry the IV mode, the plaintext and the
     * ciphertext of the first few packets, so a user with a real host can compare them against a
     * packet capture and settle the rule in one session without a rebuild.
     */
    const val HEX_LOG_PACKET_COUNT: Int = 8

    /**
     * Logs, once per process, that the input path is running on assumed values.
     *
     * Called from [InputSender]'s constructor, because the first packet it builds already depends
     * on every one of these.
     */
    fun announce() {
        ProtocolLog.unverified(
            InputConstants.TAG,
            "input-iv-chaining",
            "AES-GCM input IV mode is ${ivMode.name} (${ivMode.description}); spec 01 §10.1 marks " +
                "the chaining rule UNVERIFIED and it is the highest-risk guess in the input path " +
                "— if the host ignores all input, change UnverifiedInputConstants.ivMode first",
        )
        ProtocolLog.unverified(
            InputConstants.TAG,
            "input-gcm-iv-truncation",
            "using the ${if (useFirstTwelveIvBytes) "first" else "last"} 12 bytes of the 16-byte " +
                "protocol IV as the GCM nonce (spec 01 §10.1 marks this UNVERIFIED; note that " +
                "OpenSSL GHASH-derives a non-96-bit nonce rather than truncating it)",
        )
        ProtocolLog.unverified(
            InputConstants.TAG,
            "input-body-endianness",
            "keyboard, controller and Sunshine-extension body fields are written LITTLE-endian " +
                "per moonlight-common-c, contradicting spec 01 §10.3's blanket \"body fields are " +
                "big-endian\" rule; only the mouse move and scroll deltas are big-endian",
        )
        ProtocolLog.unverified(
            InputConstants.TAG,
            "input-absolute-mouse-reference",
            "absolute mouse reference dimensions are sent minus " +
                "$absoluteMouseReferenceAdjustment (the reference client's workaround for a GFE " +
                "scaling rounding error; not mentioned by spec 01 §10.3)",
        )
    }
}

/**
 * The IV strategies for AES-GCM input encryption (spec §10.1).
 *
 * @property description one line for the log that announces which one is in use.
 */
enum class InputIvMode(val description: String) {

    /**
     * The reference client's rule, and the default.
     *
     * IV starts as `riKeyId` big-endian in bytes 0..3 with the rest zero. After each packet, if the
     * *encrypted blob* (tag + ciphertext) is at least 32 bytes, its final 16 bytes become the next
     * IV. Because the tag is 16 bytes and comes first, that is the last 16 bytes of the ciphertext
     * proper, and the threshold works out to "the ciphertext is at least 16 bytes".
     *
     * The reference client's own comment on this is worth keeping: *"For reasons that I can't
     * understand, NVIDIA decides to use the last 16 bytes of ciphertext in the most recent game
     * controller packet as the IV for future encryption. I think it may be a buffer overrun on
     * their end but we'll have to mimic it to work correctly."*
     */
    CHAINED_REFERENCE(
        "chain from the last 16 bytes of the encrypted blob once it reaches 32 bytes — " +
            "moonlight-common-c's rule",
    ),

    /**
     * Spec §10.1's literal wording: chain "if `ciphertextLen >= 32`".
     *
     * Differs from [CHAINED_REFERENCE] only in the threshold, and only for packets whose ciphertext
     * is between 16 and 31 bytes — which is *most* of them: a mouse button packet is 9 bytes, a
     * relative move 12, a keyboard event 14, an absolute move 18. So the two modes diverge on the
     * second packet of a real session and never resynchronise. Kept because the spec says it and
     * because being able to A/B them is the entire point of this enum.
     */
    CHAINED_SPEC(
        "chain only when the ciphertext itself reaches 32 bytes — spec 01 §10.1's literal wording",
    ),

    /**
     * Never chain: every packet uses the initial `riKeyId` IV.
     *
     * Cryptographically the worst option — GCM catastrophically fails on nonce reuse — but it is
     * what a host that ignores the chaining would expect, and it is the cleanest signal in a
     * bisect: if input starts working under this mode, the chaining rule is the problem and not the
     * packet layout.
     */
    STATIC("never chain; reuse the riKeyId IV for every packet"),

    /**
     * Spec §10.1 step 3's alternative: "a simple incrementing counter IV".
     *
     * Bytes 0..3 stay `riKeyId` big-endian; bytes 12..15 hold a big-endian message counter.
     */
    COUNTER("riKeyId in bytes 0..3, a big-endian message counter in bytes 12..15"),
}
