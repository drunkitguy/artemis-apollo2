package com.voidlink.android.ui.stream

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.input.HostInputFeedback
import com.voidlink.android.protocol.input.InputConstants

/**
 * Plays the host's rumble commands (`docs/01-PROTOCOL.md` §9.6, §12.4; UI spec §5.6).
 *
 * UI spec §5.6: *"When it is on and the host sends a rumble message, it plays on the physical
 * controller's motor if it has one, otherwise on the device vibrator."* That order is the whole
 * design — a phone buzzing in your hand while you hold a gamepad is worse than no rumble at all.
 *
 * ### Turning two motors into one effect
 *
 * The protocol sends two 16-bit motor values: a low-frequency (heavy) and a high-frequency (light)
 * motor, as on an Xbox pad. Android's `Vibrator` is a single actuator with one amplitude, so the two
 * are combined into the stronger of the pair, weighted towards the low-frequency motor, which is
 * what a player feels as "the rumble". A device without amplitude control gets a plain on/off pulse
 * instead of a silently ignored amplitude.
 *
 * Each command replaces the last rather than queueing: rumble arrives continuously while a car is
 * on a rough road, and a queue would run seconds behind the game.
 *
 * @param context any context.
 * @param settings the live settings; `rumbleEnabled` is read per command so turning it off in the
 *   in-stream drawer stops the next buzz rather than the next session.
 */
class RumblePlayer(
    context: Context,
    private val settings: () -> StreamSettings,
) {

    private val appContext = context.applicationContext

    private val deviceVibrator: Vibrator? = systemVibrator(appContext)

    /** Whether the device's own actuator can vary its strength. */
    private val hasAmplitudeControl: Boolean = deviceVibrator?.hasAmplitudeControl() == true

    /** Pad slot to input-device id, so a rumble can find the pad that owns the slot. */
    private val padDevices = LinkedHashMap<Int, Int>()

    /** Records which input device is playing controller [controllerNumber]. */
    fun bindController(controllerNumber: Int, deviceId: Int) {
        padDevices[controllerNumber] = deviceId
    }

    /** Forgets a pad that has gone away. */
    fun unbindController(controllerNumber: Int) {
        padDevices.remove(controllerNumber)
    }

    /**
     * Plays one feedback message.
     *
     * Ignores everything that is not rumble: trigger rumble has no Android equivalent on a phone,
     * and pretending otherwise by buzzing the whole device on a trigger pull would be worse than
     * doing nothing.
     */
    fun play(feedback: HostInputFeedback) {
        if (feedback !is HostInputFeedback.Rumble) return
        if (!settings().rumbleEnabled) return

        val amplitude = amplitudeFor(feedback.lowFrequencyMotor, feedback.highFrequencyMotor)
        val target = padVibrator(feedback.controllerNumber) ?: deviceVibrator ?: return

        if (amplitude <= 0) {
            runCatching { target.cancel() }
            return
        }
        runCatching { target.vibrate(effectFor(amplitude, target)) }
            .onFailure { ProtocolLog.w(InputConstants.TAG, "rumble failed: ${it.message}") }
    }

    /** Stops any effect in progress. Called on teardown and on focus loss. */
    fun stop() {
        runCatching { deviceVibrator?.cancel() }
        for (controllerNumber in padDevices.keys) {
            runCatching { padVibrator(controllerNumber)?.cancel() }
        }
    }

    /**
     * The vibrator belonging to the pad in [controllerNumber]'s slot, or `null` when it has none.
     *
     * `InputDevice.getVibrator()` is deprecated on API 31+ in favour of `getVibratorManager()`, but
     * it remains the only call available on API 26–30 and still returns a working vibrator above
     * that, so it is used unconditionally.
     */
    @Suppress("DEPRECATION")
    private fun padVibrator(controllerNumber: Int): Vibrator? {
        val deviceId = padDevices[controllerNumber] ?: return null
        val device = InputDevice.getDevice(deviceId) ?: return null
        val vibrator = device.vibrator ?: return null
        return if (vibrator.hasVibrator()) vibrator else null
    }

    /**
     * One amplitude from two motors.
     *
     * The low-frequency motor dominates because it is the one a player registers as force; the
     * high-frequency motor contributes at half weight so a purely high-frequency effect — a gun's
     * report, typically — is still felt.
     */
    private fun amplitudeFor(lowFrequency: Int, highFrequency: Int): Int {
        val low = lowFrequency.coerceIn(0, MOTOR_MAX)
        val high = highFrequency.coerceIn(0, MOTOR_MAX)
        val combined = maxOf(low, high / 2)
        if (combined == 0) return 0
        return (combined * MAX_AMPLITUDE / MOTOR_MAX).coerceIn(1, MAX_AMPLITUDE)
    }

    private fun effectFor(amplitude: Int, target: Vibrator): VibrationEffect {
        val strength = if (target === deviceVibrator && !hasAmplitudeControl) {
            VibrationEffect.DEFAULT_AMPLITUDE
        } else {
            amplitude
        }
        // A short pulse, replaced by the next command. The host sends rumble continuously while an
        // effect lasts, so the duration only has to outlive the gap between commands.
        return VibrationEffect.createOneShot(PULSE_MS, strength)
    }

    private companion object {
        /** The protocol's motor values are `uint16` (spec §9.6). */
        const val MOTOR_MAX: Int = 0xFFFF

        /** `VibrationEffect`'s amplitude range is 1..255. */
        const val MAX_AMPLITUDE: Int = 255

        /** Long enough to bridge the gap between two rumble commands, short enough not to linger. */
        const val PULSE_MS: Long = 120L

        /**
         * The device's own vibrator.
         *
         * `VibratorManager` is API 31+; below that `getSystemService(Vibrator::class.java)` is the
         * documented path. Both are wrapped because a device with no actuator returns null on one
         * and a vibrator answering `hasVibrator() == false` on the other.
         */
        fun systemVibrator(context: Context): Vibrator? {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                context.getSystemService(Vibrator::class.java)
            }
            return if (vibrator?.hasVibrator() == true) vibrator else null
        }
    }
}
