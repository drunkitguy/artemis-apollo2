package com.voidlink.android.protocol.input

/**
 * The mouse buttons the protocol names (spec §10.3).
 *
 * @property code the `uint8` written into a mouse-button packet.
 */
enum class MouseButton(val code: Int) {
    LEFT(InputConstants.MOUSE_BUTTON_LEFT),
    MIDDLE(InputConstants.MOUSE_BUTTON_MIDDLE),
    RIGHT(InputConstants.MOUSE_BUTTON_RIGHT),

    /**
     * The "back" side button.
     *
     * UNVERIFIED(spec 01 §10.3): "the button numbering for X1/X2. Left/middle/right as 1/2/3 is
     * solid." Nothing else depends on this being right, so it is sent as documented and left alone.
     */
    X1(InputConstants.MOUSE_BUTTON_X1),

    /** The "forward" side button. UNVERIFIED for the same reason as [X1]. */
    X2(InputConstants.MOUSE_BUTTON_X2),
}

/**
 * Touch event types, shared by the native-touch and pen packets (spec §10.3).
 *
 * @property code the `uint8` written into the packet.
 * @property batchable whether spec §10.4 allows coalescing: moves and hovers may be dropped in
 *   favour of a newer one, state changes may never be.
 */
enum class TouchEventType(val code: Int, val batchable: Boolean) {
    HOVER(0x00, true),
    DOWN(0x01, false),
    UP(0x02, false),
    MOVE(0x03, true),

    /** Cancels one pointer. Only `pointerId` is meaningful (spec §10.3). */
    CANCEL(0x04, false),
    BUTTON_ONLY(0x05, false),
    HOVER_LEAVE(0x06, false),

    /** Cancels every active touch. UI spec §5.4 requires this on focus loss. */
    CANCEL_ALL(0x07, false),
}

/**
 * Motion sensor types (spec §10.3).
 *
 * The units are load-bearing and are converted before they reach a packet: accelerometer in m/s²
 * including gravity, gyroscope in **degrees** per second where Android reports radians.
 *
 * @property code the `uint8` written into a controller-motion packet.
 */
enum class MotionType(val code: Int) {
    ACCELEROMETER(0x01),
    GYROSCOPE(0x02),
}

/**
 * The pad types a Sunshine host can emulate (spec §10.3).
 *
 * @property code the `uint8` written into a controller-arrival packet.
 */
enum class ControllerType(val code: Int) {
    UNKNOWN(0x00),
    XBOX(0x01),

    /** PlayStation. The settings enum calls this `DUALSHOCK_4` and the UI labels it "DS4". */
    PLAYSTATION(0x02),
    NINTENDO(0x03),
}

/** Battery states for the Sunshine controller-battery packet (spec §10.3). */
enum class BatteryState(val code: Int) {
    UNKNOWN(0x00),
    NOT_PRESENT(0x01),
    DISCHARGING(0x02),
    CHARGING(0x03),
    CONNECTED_NOT_CHARGING(0x04),
    FULL(0x05),
}

/**
 * One controller's complete state, as a single immutable value.
 *
 * Passed whole rather than as nine parameters because spec §10.4 requires controller packets to be
 * sent "only on change": comparing two of these is how "on change" is decided, and a value class
 * with structural equality makes that comparison correct by construction rather than by remembering
 * to include every field.
 *
 * @property controllerNumber which pad, 0-based.
 * @property buttonFlags the full 32-bit button mask; the low 16 bits go in `buttonFlags`, the high
 *   16 in `buttonFlags2` (Sunshine only).
 * @property leftTrigger 0..255.
 * @property rightTrigger 0..255.
 * @property leftStickX -32768..32767, positive right.
 * @property leftStickY -32768..32767, **positive up** — the opposite of Android's axis sign.
 * @property rightStickX as [leftStickX].
 * @property rightStickY as [leftStickY].
 */
data class ControllerState(
    val controllerNumber: Int = 0,
    val buttonFlags: Int = 0,
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    val leftStickX: Int = 0,
    val leftStickY: Int = 0,
    val rightStickX: Int = 0,
    val rightStickY: Int = 0,
) {
    /** True when nothing is pressed and both sticks are centred — the "arrival" state. */
    val isNeutral: Boolean
        get() = buttonFlags == 0 && leftTrigger == 0 && rightTrigger == 0 &&
            leftStickX == 0 && leftStickY == 0 && rightStickX == 0 && rightStickY == 0
}

/**
 * What the host is, as far as input is concerned (spec §10.1, §10.3).
 *
 * Three things branch on this and nothing else does: which magic a relative mouse move and a
 * multi-controller packet use (Gen 5 changed both), whether the Sunshine extension packets may be
 * sent at all, and whether input is encrypted with GCM or CBC.
 *
 * @property generation `AppVersionQuad[0]` (spec §0.3).
 * @property isSunshine whether the host is Sunshine-family, which is what gates every `SS_*` packet
 *   and the high 16 button bits.
 * @property controlStreamEncrypted whether the *control stream* encrypts our messages for us. On
 *   GFE 3.22 and newer the reference client stops encrypting input entirely and sends the plaintext
 *   packet, because the control stream will encrypt it. v1 never negotiates that (spec §6.5), so
 *   this is false and input carries its own AES envelope.
 */
data class InputProfile(
    val generation: Int,
    val isSunshine: Boolean,
    val controlStreamEncrypted: Boolean = false,
) {
    /** Gen 5 renumbered the relative-mouse and multi-controller magics (spec §10.3). */
    val isGen5OrLater: Boolean get() = generation >= GEN5

    /** AES-GCM starts at Gen 7; older hosts use AES-CBC (spec §10.1). */
    val usesGcm: Boolean get() = generation >= InputConstants.GCM_MIN_GENERATION

    /** How many pads the host will look at (spec §10.3). */
    val maxGamepads: Int
        get() = if (isSunshine) InputConstants.MAX_GAMEPADS_SUNSHINE else InputConstants.MAX_GAMEPADS_GFE

    private companion object {
        const val GEN5: Int = 5
    }
}
