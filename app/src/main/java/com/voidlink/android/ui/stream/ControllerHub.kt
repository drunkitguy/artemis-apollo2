package com.voidlink.android.ui.stream

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.voidlink.android.data.EmulatedControllerType
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.input.AxisMath
import com.voidlink.android.protocol.input.ControllerState
import com.voidlink.android.protocol.input.ControllerType
import com.voidlink.android.protocol.input.GamepadButtons
import com.voidlink.android.protocol.input.InputConstants
import com.voidlink.android.protocol.input.InputSink

/**
 * Physical controllers: enumeration, hot-plug, stable slot assignment, and axis reading
 * (`docs/01-PROTOCOL.md` §10.3, §12.3).
 *
 * The Android side of the controller path, kept as thin as it can be. Everything that can be
 * decided without a device — dead zones, axis normalisation, button flags, face-button swapping —
 * lives in `protocol/input`'s pure helpers and is unit-tested there; this class does the three
 * things that genuinely need the platform:
 *
 * 1. **Finding pads.** `InputDevice` is enumerated at start and watched with an
 *    [InputManager.InputDeviceListener], so a pad connected mid-session works without reconnecting
 *    the stream (spec §12.3).
 * 2. **Assigning slots.** The host addresses pads by number, and those numbers have to be *stable*:
 *    if pad 2 disconnects, pad 1 must not become pad 2 and take over its character. Slots are
 *    therefore held by device id and freed only when that device goes away.
 * 3. **Reading axes.** Which axis a trigger reports on differs by pad — `AXIS_LTRIGGER`/`BRAKE`,
 *    `AXIS_RTRIGGER`/`GAS`, and `AXIS_Z`/`AXIS_RZ` for the right stick — so each is read with a
 *    fallback chain, and the per-axis dead zone comes from the device's own
 *    `MotionRange.getFlat()` where it reports one.
 *
 * **Threading:** every method runs on the main thread — Android input callbacks and the device
 * listener are both delivered there.
 *
 * @param context any context; the application context is used for the system service.
 * @param sink where controller state goes.
 * @param settings the live settings, re-read per event so a change to "Swap A/B X/Y" applies to the
 *   next button press rather than the next session.
 * @param onPadAttached told which input device took which slot, so rumble can be routed back to the
 *   pad the host is addressing rather than to the phone in the user's other hand.
 * @param onPadDetached told when a slot is freed.
 */
class ControllerHub(
    context: Context,
    private val sink: () -> InputSink,
    private val settings: () -> StreamSettings,
    private val onPadAttached: (slot: Int, deviceId: Int) -> Unit = { _, _ -> },
    private val onPadDetached: (slot: Int) -> Unit = {},
) {

    /** One connected pad. */
    private class Pad(
        val deviceId: Int,
        val slot: Int,
        val name: String,
        val capabilities: Int,
        val leftDeadZone: Float,
        val rightDeadZone: Float,
        var buttonFlags: Int = 0,
        var hatFlags: Int = 0,
        var state: ControllerState = ControllerState(),
    )

    private val inputManager: InputManager? =
        context.applicationContext.getSystemService(Context.INPUT_SERVICE) as? InputManager

    private val pads = LinkedHashMap<Int, Pad>()

    /** Slot occupancy, indexed by controller number. */
    private val slots = arrayOfNulls<Int>(InputConstants.MAX_GAMEPADS_SUNSHINE)

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            addDevice(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            removeDevice(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            // A pad whose capabilities changed (a DualSense switching modes, for instance) is
            // re-announced so the host re-reads them.
            removeDevice(deviceId)
            addDevice(deviceId)
        }
    }

    /** How many pads are connected, so the on-screen widgets can hide themselves (UI spec §5.5). */
    val connectedCount: Int get() = pads.size

    /** Names of the connected pads, for the "Controller 1 connected" toast. */
    fun connectedNames(): List<String> = pads.values.map { it.name }

    /**
     * Enumerates what is already plugged in and starts watching for changes.
     *
     * Safe to call more than once; the listener registration is idempotent from Android's side and
     * already-known devices are skipped.
     */
    fun start() {
        inputManager?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        for (deviceId in InputDevice.getDeviceIds()) addDevice(deviceId)
    }

    /**
     * Stops watching and tells the host every pad has gone.
     *
     * Announcing the removals matters: a host left believing a pad is attached keeps the virtual
     * device around, and the next session finds the slot taken.
     */
    fun stop() {
        inputManager?.unregisterInputDeviceListener(listener)
        for (pad in pads.values.toList()) removeDevice(pad.deviceId)
    }

    // ---- Events -----------------------------------------------------------------------------------

    /**
     * Handles a gamepad key event.
     *
     * @return true when the event belonged to a pad and was consumed.
     */
    fun onKeyEvent(event: KeyEvent, pressed: Boolean): Boolean {
        val pad = pads[event.deviceId] ?: return false
        val flag = GamepadButtons.flagFor(event.keyCode, settings().swapFaceButtons)
        if (flag == 0) return false
        // A held key repeats; the host already knows it is down and a repeat would be a redundant
        // packet every few tens of milliseconds.
        if (pressed && event.repeatCount > 0) return true

        pad.buttonFlags = if (pressed) pad.buttonFlags or flag else pad.buttonFlags and flag.inv()
        publish(pad)
        return true
    }

    /**
     * Handles a joystick motion event.
     *
     * @return true when the event came from a known pad.
     */
    fun onMotionEvent(event: MotionEvent): Boolean {
        val pad = pads[event.deviceId] ?: return false
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) return false

        // Historical samples exist so a fast flick is not sampled once; only the newest position
        // matters for the state we send, and the batching in InputSender is what protects the wire.
        val left = AxisMath.applyStickDeadZone(
            x = event.getAxisValue(MotionEvent.AXIS_X),
            y = event.getAxisValue(MotionEvent.AXIS_Y),
            deadZone = pad.leftDeadZone,
        )
        val right = AxisMath.applyStickDeadZone(
            x = axisWithFallback(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX),
            y = axisWithFallback(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY),
            deadZone = pad.rightDeadZone,
        )
        pad.hatFlags = AxisMath.hatToButtonFlags(
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )

        val next = ControllerState(
            controllerNumber = pad.slot,
            buttonFlags = pad.buttonFlags or pad.hatFlags,
            leftTrigger = AxisMath.toTrigger(
                axisWithFallback(event, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE),
            ),
            rightTrigger = AxisMath.toTrigger(
                axisWithFallback(event, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS),
            ),
            leftStickX = AxisMath.toStickAxis(left[0]),
            leftStickY = AxisMath.toStickAxis(left[1]),
            rightStickX = AxisMath.toStickAxis(right[0]),
            rightStickY = AxisMath.toStickAxis(right[1]),
        )

        // Spec §10.4: stick jitter must not count as a change. The sink also drops identical
        // states, but comparing here as well keeps a resting pad from waking the sender at the
        // event rate of the device, which on some pads is 250 Hz.
        if (!isMeaningfullyDifferent(pad.state, next)) return true
        pad.state = next
        sink().controllerState(next)
        return true
    }

    /** Releases every button on every pad, without disconnecting them (UI spec §5.4). */
    fun releaseAll() {
        for (pad in pads.values) {
            pad.buttonFlags = 0
            pad.hatFlags = 0
            pad.state = ControllerState(controllerNumber = pad.slot)
            sink().controllerState(pad.state)
        }
    }

    // ---- Device bookkeeping ------------------------------------------------------------------------

    private fun addDevice(deviceId: Int) {
        if (pads.containsKey(deviceId)) return
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!isGamepad(device)) return

        val slot = claimSlot(deviceId) ?: run {
            ProtocolLog.w(
                InputConstants.TAG,
                "no controller slot left for ${device.name}; the host exposes " +
                    "${InputConstants.MAX_GAMEPADS_SUNSHINE} pads",
            )
            return
        }
        val pad = Pad(
            deviceId = deviceId,
            slot = slot,
            name = device.name ?: "Controller",
            capabilities = capabilitiesOf(device),
            leftDeadZone = deadZoneOf(device, MotionEvent.AXIS_X),
            rightDeadZone = deadZoneOf(device, MotionEvent.AXIS_Z),
        )
        pads[deviceId] = pad
        onPadAttached(slot, deviceId)
        sink().controllerArrived(
            controllerNumber = slot,
            type = emulatedType(),
            capabilities = pad.capabilities,
            supportedButtonFlags = InputConstants.BUTTONS_STANDARD,
        )
        ProtocolLog.i(
            InputConstants.TAG,
            "controller ${pad.name} (device $deviceId) took slot $slot with capabilities " +
                "0x${pad.capabilities.toString(16)}",
        )
    }

    private fun removeDevice(deviceId: Int) {
        val pad = pads.remove(deviceId) ?: return
        slots[pad.slot] = null
        onPadDetached(pad.slot)
        sink().controllerRemoved(pad.slot)
    }

    /**
     * Takes the lowest free slot.
     *
     * Lowest-free rather than next-in-sequence so that unplugging pad 0 and plugging it back in
     * returns it to slot 0, which is the slot a single-player game is looking at.
     */
    private fun claimSlot(deviceId: Int): Int? {
        for (index in slots.indices) {
            if (slots[index] == null) {
                slots[index] = deviceId
                return index
            }
        }
        return null
    }

    private fun publish(pad: Pad) {
        val next = pad.state.copy(
            controllerNumber = pad.slot,
            buttonFlags = pad.buttonFlags or pad.hatFlags,
        )
        pad.state = next
        sink().controllerState(next)
    }

    /**
     * Which pad type the host is asked to emulate.
     *
     * `BOTH` means "let the host decide", which in protocol terms is
     * [ControllerType.UNKNOWN] — a Sunshine host that is told nothing specific creates its default
     * pad rather than refusing.
     */
    private fun emulatedType(): ControllerType = when (settings().emulatedControllerType) {
        EmulatedControllerType.XBOX_360 -> ControllerType.XBOX
        EmulatedControllerType.DUALSHOCK_4 -> ControllerType.PLAYSTATION
        EmulatedControllerType.BOTH -> ControllerType.UNKNOWN
    }

    /**
     * What the pad can do, as spec §10.3's capability bits.
     *
     * Only the bits we can establish are set. Rumble is claimed when the device reports a vibrator
     * *or* when the phone itself has one, because the rumble player falls back to the phone
     * (UI spec §5.6) — a host told the pad cannot rumble never sends the events that would drive
     * that fallback.
     */
    private fun capabilitiesOf(device: InputDevice): Int {
        var capabilities = 0
        if (hasAxis(device, MotionEvent.AXIS_LTRIGGER) || hasAxis(device, MotionEvent.AXIS_BRAKE)) {
            capabilities = capabilities or InputConstants.CCAP_ANALOG_TRIGGERS
        }
        if (hasVibrator(device)) {
            capabilities = capabilities or InputConstants.CCAP_RUMBLE
        }
        return capabilities
    }

    /**
     * Whether the pad has motors of its own.
     *
     * `InputDevice.getVibrator()` is deprecated in favour of `getVibratorManager()` on API 31+, but
     * it is still the only call that works on API 26–30 and it still answers correctly above that,
     * so it is used unconditionally rather than behind a version branch that would have to keep
     * both paths alive for one boolean.
     */
    @Suppress("DEPRECATION")
    private fun hasVibrator(device: InputDevice): Boolean = device.vibrator?.hasVibrator() == true

    private fun hasAxis(device: InputDevice, axis: Int): Boolean =
        device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null

    /**
     * The device's own idea of its stick's resting wobble, floored at our default.
     *
     * A pad that reports no flat range gets [AxisMath.DEFAULT_DEAD_ZONE]; a pad that reports a
     * larger one gets its own, because the manufacturer has measured the hardware and we have not.
     */
    private fun deadZoneOf(device: InputDevice, axis: Int): Float {
        val range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
            ?: return AxisMath.DEFAULT_DEAD_ZONE
        return maxOf(range.flat, AxisMath.DEFAULT_DEAD_ZONE)
    }

    private fun axisWithFallback(event: MotionEvent, primary: Int, fallback: Int): Float {
        val value = event.getAxisValue(primary)
        return if (value != 0f) value else event.getAxisValue(fallback)
    }

    private fun isMeaningfullyDifferent(previous: ControllerState, next: ControllerState): Boolean =
        previous.buttonFlags != next.buttonFlags ||
            previous.leftTrigger != next.leftTrigger ||
            previous.rightTrigger != next.rightTrigger ||
            AxisMath.axisChanged(previous.leftStickX, next.leftStickX) ||
            AxisMath.axisChanged(previous.leftStickY, next.leftStickY) ||
            AxisMath.axisChanged(previous.rightStickX, next.rightStickX) ||
            AxisMath.axisChanged(previous.rightStickY, next.rightStickY)

    private companion object {

        /**
         * Whether a device is a pad rather than a keyboard, mouse or the phone's own sensors.
         *
         * The `SOURCE_GAMEPAD or SOURCE_JOYSTICK` test is Android's documented one. Virtual devices
         * are excluded: `isVirtual` covers the synthetic device the platform uses for injected
         * events, which would otherwise appear as a phantom pad on some TV devices.
         */
        fun isGamepad(device: InputDevice): Boolean {
            // `InputDevice.isVirtual()` is API 29+, and minSdk is 26. Below that the check falls
            // back to the device id: the platform's synthetic device is
            // `KeyCharacterMap.VIRTUAL_KEYBOARD`, which is id -1, and every real device has a
            // non-negative id.
            val virtual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                device.isVirtual
            } else {
                device.id < 0
            }
            if (virtual) return false
            val sources = device.sources
            val gamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            val joystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            return gamepad || joystick
        }
    }
}
