package com.voidlink.android.protocol.input

/**
 * One motion sample in the host's units and frame.
 *
 * @property x first axis, m/s² for the accelerometer and deg/s for the gyroscope.
 */
data class MotionSample(val x: Float, val y: Float, val z: Float)

/**
 * Sensor conversion for the controller-motion packet (`docs/01-PROTOCOL.md` §10.3, §12.5).
 *
 * Spec §10.3 makes the point that matters: *"Units are load-bearing"* — accelerometer in **m/s²
 * including gravity**, gyroscope in **degrees per second**, where Android's `TYPE_GYROSCOPE` reports
 * radians per second. A client that forwards the raw Android value sends a gyro reading 57 times too
 * small, which reads to the user as "gyro aiming does not work" rather than as a units bug.
 *
 * Pure and free of `android.hardware`, so the conversion, the display-rotation transform and the
 * sensitivity curve are all testable without a device.
 */
object MotionMath {

    /**
     * Android's gyroscope, in rad/s, to the host's deg/s, with sensitivity applied.
     *
     * @param sensitivityPercent the `gyroSensitivityPercent` setting (25..300). Applied as a plain
     *   multiplier: the host receives a faster-turning gyro rather than a scaled one, which is what
     *   makes the setting feel linear.
     */
    fun gyroscope(
        x: Float,
        y: Float,
        z: Float,
        sensitivityPercent: Int,
        rotationDegrees: Int = 0,
    ): MotionSample {
        val scale = InputConstants.RADIANS_TO_DEGREES * (sensitivityPercent / PERCENT)
        return transform(x * scale, y * scale, z * scale, rotationDegrees)
    }

    /**
     * Android's accelerometer, already in m/s² including gravity, in the host's frame.
     *
     * Sensitivity deliberately does not apply: the accelerometer reports an orientation-bearing
     * physical quantity that a game uses to know which way is down, and scaling it would tell the
     * game gravity had changed.
     */
    fun accelerometer(x: Float, y: Float, z: Float, rotationDegrees: Int = 0): MotionSample =
        transform(x, y, z, rotationDegrees)

    /**
     * Rotates a device-frame vector into the frame the user is holding, then applies the debug
     * inversions.
     *
     * Android's sensor axes are fixed to the device's **natural** orientation, which on a phone is
     * portrait and on a tablet is often landscape. A stream is usually watched in landscape, so a
     * gyro forwarded unrotated has pitch and yaw swapped — the player tilts to aim up and the view
     * pans sideways. Rotating by the display rotation is the same transform
     * `SensorManager.remapCoordinateSystem` performs, written out for the four right angles that are
     * the only ones a display reports.
     *
     * @param rotationDegrees the display's rotation: 0, 90, 180 or 270, from `Display.getRotation()`.
     */
    fun transform(x: Float, y: Float, z: Float, rotationDegrees: Int): MotionSample {
        val rotated = when (((rotationDegrees % FULL_TURN) + FULL_TURN) % FULL_TURN) {
            QUARTER -> floatArrayOf(-y, x, z)
            HALF -> floatArrayOf(-x, -y, z)
            THREE_QUARTER -> floatArrayOf(y, -x, z)
            else -> floatArrayOf(x, y, z)
        }
        return MotionSample(
            x = if (UnverifiedInputConstants.invertMotionX) -rotated[0] else rotated[0],
            y = if (UnverifiedInputConstants.invertMotionY) -rotated[1] else rotated[1],
            z = if (UnverifiedInputConstants.invertMotionZ) -rotated[2] else rotated[2],
        )
    }

    private const val PERCENT: Float = 100f
    private const val QUARTER: Int = 90
    private const val HALF: Int = 180
    private const val THREE_QUARTER: Int = 270
    private const val FULL_TURN: Int = 360
}

/**
 * Reads the host→client messages the input layer acts on (`docs/01-PROTOCOL.md` §9.6, §10.3).
 *
 * The control stream parses its own rumble message; these parsers exist for the Sunshine feedback
 * extensions (spec §9.3 indices 9–12), whose *payloads* are known while their wire type ids are
 * marked UNVERIFIED. When the session starts recognising those types, the payloads are already
 * pinned by tests here.
 *
 * Every field is **little-endian**, like everything else the control stream carries inbound.
 */
object HostFeedbackParser {

    /**
     * The rumble payload (spec §9.6).
     *
     * Spec §9.6 marks the layout UNVERIFIED and prescribes the branch: *"if `payloadLength >= 4 + 6`,
     * read the three uint16s from offset 4 (little-endian); otherwise read them from offset 0."*
     * The reference client always skips four bytes, which says the long form is the real one and the
     * short form is the safety net.
     */
    fun rumble(payload: ByteArray): HostInputFeedback.Rumble? {
        val offset = if (payload.size >= LEADING + RUMBLE_FIELDS) LEADING else 0
        if (payload.size < offset + RUMBLE_FIELDS) return null
        return HostInputFeedback.Rumble(
            controllerNumber = le16(payload, offset),
            lowFrequencyMotor = le16(payload, offset + 2),
            highFrequencyMotor = le16(payload, offset + 4),
        )
    }

    /**
     * The rumble-triggers payload (spec §9.3 index 9).
     *
     * Note the difference from [rumble]: the reference client reads this one from offset **0**, with
     * no leading four bytes. Two neighbouring messages with different framings is exactly the kind
     * of asymmetry that gets "cleaned up" into a bug.
     */
    fun rumbleTriggers(payload: ByteArray): HostInputFeedback.RumbleTriggers? {
        if (payload.size < RUMBLE_FIELDS) return null
        return HostInputFeedback.RumbleTriggers(
            controllerNumber = le16(payload, 0),
            leftTriggerMotor = le16(payload, 2),
            rightTriggerMotor = le16(payload, 4),
        )
    }

    /**
     * The set-motion-event payload (spec §9.3 index 10, §10.3).
     *
     * **The field order is not the order spec §10.3 lists them in.** Spec §10.3 describes the
     * message as specifying "a controller number, a motion type, and a **report rate in Hz**", which
     * reads as `(number, type, rate)`; the reference client parses
     * `uint16 controllerNumber, uint16 reportRateHz, uint8 motionType`. Reading it in the spec's
     * order would set the rate from the motion type — 1 Hz for the accelerometer, 2 Hz for the gyro
     * — which is not obviously wrong in a log and is unusable in a game.
     *
     * @return `null` for a payload too short or a motion type outside the two the protocol defines.
     */
    fun setMotionEventState(payload: ByteArray): HostInputFeedback.SetMotionEventState? {
        if (payload.size < MOTION_STATE_BYTES) return null
        val type = when (payload[MOTION_TYPE_OFFSET].toInt() and BYTE_MASK) {
            MotionType.ACCELEROMETER.code -> MotionType.ACCELEROMETER
            MotionType.GYROSCOPE.code -> MotionType.GYROSCOPE
            else -> return null
        }
        return HostInputFeedback.SetMotionEventState(
            controllerNumber = le16(payload, 0),
            motionType = type,
            reportRateHz = le16(payload, 2),
        )
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK) or ((bytes[offset + 1].toInt() and BYTE_MASK) shl 8)

    private const val BYTE_MASK: Int = 0xFF
    private const val LEADING: Int = 4
    private const val RUMBLE_FIELDS: Int = 6
    private const val MOTION_TYPE_OFFSET: Int = 4
    private const val MOTION_STATE_BYTES: Int = 5
}
