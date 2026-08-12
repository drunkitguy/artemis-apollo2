package com.voidlink.android.protocol.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Android gamepad key codes to GameStream button flags (spec §10.3).
 *
 * Pure, and free of `android.view.KeyEvent` for the same reason as [WindowsKeyCodes]: the mapping is
 * the part that can be wrong, and it should be provable without a device.
 *
 * The flag layout is worth staring at once. Spec §10.3 notes it: *"the D-pad occupies the low
 * nibble, face buttons the high nibble"* — `A = 0x1000`, `B = 0x2000`, `X = 0x4000`, `Y = 0x8000`.
 * `Y` therefore sets the sign bit of a 16-bit mask, which is exactly the bug the reference client
 * guards against when a caller passes a sign-extended `short` as a 32-bit mask; [ControllerState]
 * carries a proper `Int` and [ControllerFixups] masks it, so it cannot happen here.
 */
object GamepadButtons {

    // ---- Android key codes (android.view.KeyEvent), as literals --------------------------------

    const val KEYCODE_BACK: Int = 4
    const val KEYCODE_DPAD_UP: Int = 19
    const val KEYCODE_DPAD_DOWN: Int = 20
    const val KEYCODE_DPAD_LEFT: Int = 21
    const val KEYCODE_DPAD_RIGHT: Int = 22
    const val KEYCODE_DPAD_CENTER: Int = 23
    const val KEYCODE_MENU: Int = 82
    const val KEYCODE_BUTTON_A: Int = 96
    const val KEYCODE_BUTTON_B: Int = 97
    const val KEYCODE_BUTTON_C: Int = 98
    const val KEYCODE_BUTTON_X: Int = 99
    const val KEYCODE_BUTTON_Y: Int = 100
    const val KEYCODE_BUTTON_Z: Int = 101
    const val KEYCODE_BUTTON_L1: Int = 102
    const val KEYCODE_BUTTON_R1: Int = 103
    const val KEYCODE_BUTTON_L2: Int = 104
    const val KEYCODE_BUTTON_R2: Int = 105
    const val KEYCODE_BUTTON_THUMBL: Int = 106
    const val KEYCODE_BUTTON_THUMBR: Int = 107
    const val KEYCODE_BUTTON_START: Int = 108
    const val KEYCODE_BUTTON_SELECT: Int = 109
    const val KEYCODE_BUTTON_MODE: Int = 110

    /**
     * The button flag for [androidKeyCode], or `0` when the pad key means nothing to the host.
     *
     * @param swapFaceButtons the `swapFaceButtons` setting: swaps A↔B and X↔Y so a Nintendo-style
     *   pad, whose physical labels sit where an Xbox pad's opposite ones do, produces what the
     *   button *says* rather than where it *is*.
     */
    fun flagFor(androidKeyCode: Int, swapFaceButtons: Boolean): Int = when (androidKeyCode) {
        KEYCODE_BUTTON_A -> if (swapFaceButtons) InputConstants.BUTTON_B else InputConstants.BUTTON_A
        KEYCODE_BUTTON_B -> if (swapFaceButtons) InputConstants.BUTTON_A else InputConstants.BUTTON_B
        KEYCODE_BUTTON_X -> if (swapFaceButtons) InputConstants.BUTTON_Y else InputConstants.BUTTON_X
        KEYCODE_BUTTON_Y -> if (swapFaceButtons) InputConstants.BUTTON_X else InputConstants.BUTTON_Y

        KEYCODE_DPAD_UP -> InputConstants.BUTTON_UP
        KEYCODE_DPAD_DOWN -> InputConstants.BUTTON_DOWN
        KEYCODE_DPAD_LEFT -> InputConstants.BUTTON_LEFT
        KEYCODE_DPAD_RIGHT -> InputConstants.BUTTON_RIGHT
        // Several pads report the stick click as DPAD_CENTER rather than THUMBL; treating it as A
        // is what Android's own game-controller sample does and what a user expects from a
        // "confirm" press on a TV remote-shaped pad.
        KEYCODE_DPAD_CENTER -> InputConstants.BUTTON_A

        KEYCODE_BUTTON_L1 -> InputConstants.BUTTON_LB
        KEYCODE_BUTTON_R1 -> InputConstants.BUTTON_RB
        KEYCODE_BUTTON_THUMBL -> InputConstants.BUTTON_LS_CLK
        KEYCODE_BUTTON_THUMBR -> InputConstants.BUTTON_RS_CLK
        KEYCODE_BUTTON_START, KEYCODE_MENU -> InputConstants.BUTTON_PLAY
        KEYCODE_BUTTON_SELECT, KEYCODE_BACK -> InputConstants.BUTTON_BACK
        KEYCODE_BUTTON_MODE -> InputConstants.BUTTON_SPECIAL

        // L2/R2 as *keys* come from pads with digital shoulder triggers. Their analogue form
        // arrives as an axis instead and is handled there; a pad that reports both would otherwise
        // send the trigger twice.
        KEYCODE_BUTTON_C, KEYCODE_BUTTON_Z -> 0
        else -> 0
    }

    /**
     * Whether [androidKeyCode] is a gamepad key this client consumes.
     *
     * Used to decide whether a key event belongs to the controller path or the keyboard path. `BACK`
     * is deliberately included only when it arrives from a device with gamepad sources — the
     * caller's job — because on a phone it is the navigation gesture.
     */
    fun isGamepadKey(androidKeyCode: Int): Boolean =
        flagFor(androidKeyCode, swapFaceButtons = false) != 0
}

/**
 * Stick, trigger and D-pad-as-axis arithmetic (spec §10.3).
 *
 * All of it pure and all of it unit-tested, because every function here is a place where a plausible
 * implementation feels fine in a menu and is wrong in a game: a square dead zone that lets a
 * diagonal through at half deflection, a normalisation that never reaches full scale, a Y axis that
 * is upside down in one mode and not the other.
 */
object AxisMath {

    /**
     * The dead zone applied to a stick, as a fraction of full deflection.
     *
     * Android exposes `InputDevice.MotionRange.getFlat()` per axis, which is the manufacturer's own
     * idea of the stick's rest wobble, and the capture layer prefers it. This is the floor for a
     * device that reports zero — a worn stick that reports 0.05 at rest would otherwise make the
     * character walk on its own, which reads as the game being broken rather than the pad.
     */
    const val DEFAULT_DEAD_ZONE: Float = 0.10f

    /** Below this a trigger counts as released, matching [DEFAULT_DEAD_ZONE]'s intent. */
    const val TRIGGER_DEAD_ZONE: Float = 0.05f

    /**
     * Applies a **radial** dead zone to a stick and rescales what is left to the full range.
     *
     * Radial, not per-axis, and the difference is the whole point: with a per-axis dead zone a stick
     * pushed diagonally to 0.09 on each axis reports nothing, while the same stick pushed to 0.11 on
     * each axis jumps straight to a noticeable value — the classic "the stick has a square hole in
     * the middle" feel. Rescaling then restores the full range, so a stick with a 10% dead zone
     * still reaches 1.0 rather than stopping at 0.9.
     *
     * @param x -1..1, positive right.
     * @param y -1..1, in **Android's** sign convention: positive is *down*.
     * @param deadZone 0..1.
     * @return `[x, y]` in the protocol's convention: positive right, positive **up**, magnitude
     *   rescaled to 0..1.
     */
    fun applyStickDeadZone(x: Float, y: Float, deadZone: Float): FloatArray {
        val magnitude = hypot(x, y)
        if (magnitude <= deadZone || magnitude == 0f) return FloatArray(2)
        val zone = deadZone.coerceIn(0f, MAX_DEAD_ZONE)
        // Rescale the magnitude that survives the dead zone back onto 0..1, then clamp: a stick
        // reporting slightly more than 1.0 at the corners is common and must not overflow int16.
        val scaled = ((magnitude - zone) / (1f - zone)).coerceIn(0f, 1f)
        val unit = scaled / magnitude
        // The Y flip lives here, once, rather than at each of the four call sites that would
        // otherwise have to remember it.
        return floatArrayOf(x * unit, -y * unit)
    }

    /**
     * A normalised axis value to the protocol's `int16` stick range.
     *
     * Clamps rather than wraps: a pad reporting 1.02 at full deflection would otherwise produce
     * -32700, which is full deflection in the *opposite* direction.
     */
    fun toStickAxis(value: Float): Int =
        (value.coerceIn(-1f, 1f) * InputConstants.STICK_MAX)
            .toInt()
            .coerceIn(InputConstants.STICK_MIN, InputConstants.STICK_MAX)

    /**
     * A normalised trigger value to the protocol's `uint8` range.
     *
     * @param value 0..1. Values below [TRIGGER_DEAD_ZONE] become 0, so a resting trigger that
     *   reports 0.01 does not hold the accelerator down for the whole session.
     */
    fun toTrigger(value: Float): Int {
        if (value < TRIGGER_DEAD_ZONE) return 0
        return (value.coerceIn(0f, 1f) * InputConstants.TRIGGER_MAX).toInt()
            .coerceIn(0, InputConstants.TRIGGER_MAX)
    }

    /**
     * The D-pad button flags for a hat-switch position.
     *
     * Most pads report their D-pad as `AXIS_HAT_X`/`AXIS_HAT_Y` rather than as key events, and a
     * client that only listens for `KEYCODE_DPAD_*` finds the D-pad completely dead on those.
     *
     * @param hatX -1 (left), 0, or 1 (right).
     * @param hatY -1 (**up**), 0, or 1 (down) — Android's hat Y is negative upward, like its stick
     *   Y and unlike the protocol's sticks.
     */
    fun hatToButtonFlags(hatX: Float, hatY: Float): Int {
        var flags = 0
        if (hatX < -HAT_THRESHOLD) flags = flags or InputConstants.BUTTON_LEFT
        if (hatX > HAT_THRESHOLD) flags = flags or InputConstants.BUTTON_RIGHT
        if (hatY < -HAT_THRESHOLD) flags = flags or InputConstants.BUTTON_UP
        if (hatY > HAT_THRESHOLD) flags = flags or InputConstants.BUTTON_DOWN
        return flags
    }

    /**
     * Whether two stick positions differ enough to be worth a packet (spec §10.4).
     *
     * Spec §10.4: *"Analog stick jitter must be dead-zoned before it counts as a change."* The dead
     * zone above handles the resting position; this handles a stick held slightly off-centre, where
     * the last bit flickers and would otherwise produce a packet every few milliseconds forever.
     */
    fun axisChanged(previous: Int, current: Int): Boolean =
        abs(previous - current) > AXIS_CHANGE_EPSILON

    /** One part in ~512 of the `int16` range: below a stick's own noise, above its jitter. */
    const val AXIS_CHANGE_EPSILON: Int = 64

    private const val HAT_THRESHOLD: Float = 0.5f

    /** A dead zone at or above this would divide by zero when rescaling. */
    private const val MAX_DEAD_ZONE: Float = 0.9f
}
