package com.voidlink.android.ui.stream

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import com.voidlink.android.data.GyroMode
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.input.InputConstants
import com.voidlink.android.protocol.input.InputSink
import com.voidlink.android.protocol.input.MotionMath
import com.voidlink.android.protocol.input.MotionType

/**
 * The device's gyroscope and accelerometer, forwarded as controller motion
 * (`docs/01-PROTOCOL.md` §10.3, §12.5).
 *
 * Thin on purpose: it registers listeners, converts with [MotionMath] and hands the result to the
 * sink, which throttles to the rate the host asked for. Everything numerical — the radians-to-degrees
 * conversion, the sensitivity multiplier, the display-rotation transform — is in [MotionMath] and is
 * unit-tested.
 *
 * ### The settings this honours
 *
 * * **[GyroMode]** decides whether anything is registered at all. `OFF` registers nothing;
 *   `BUILT_IN` and `AUTO` use the device's own sensors; `CONTROLLER` means the *pad's* sensors,
 *   which Android exposes on a handful of devices and which this build does not read — so it
 *   registers nothing and says so once, rather than silently behaving like `BUILT_IN`.
 * * **`gyroSensitivityPercent`** scales the gyroscope only. The accelerometer reports gravity, and
 *   scaling gravity would tell the game the world had tilted.
 *
 * The sampling rate is requested in microseconds. `HIGH_SAMPLING_RATE_SENSORS` is already in the
 * manifest, which is what allows anything above 200 Hz on API 31+; without it the platform silently
 * clamps, which is a rate difference rather than a failure.
 *
 * @param context the Activity context — it is also the display context for the rotation.
 * @param sink where samples go.
 * @param settings the live settings, read at [start].
 */
class MotionSensorPump(
    private val context: Context,
    private val sink: () -> InputSink,
    private val settings: () -> StreamSettings,
) {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var registered: Boolean = false

    /** Which pad the samples are attributed to. Slot 0: the phone is player one. */
    private var controllerNumber: Int = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            if (values.size < AXES) return
            val rotation = displayRotationDegrees()
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val sample = MotionMath.gyroscope(
                        x = values[0],
                        y = values[1],
                        z = values[2],
                        sensitivityPercent = settings().gyroSensitivityPercent,
                        rotationDegrees = rotation,
                    )
                    sink().controllerMotion(
                        controllerNumber,
                        MotionType.GYROSCOPE,
                        sample.x,
                        sample.y,
                        sample.z,
                    )
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    val sample = MotionMath.accelerometer(values[0], values[1], values[2], rotation)
                    sink().controllerMotion(
                        controllerNumber,
                        MotionType.ACCELEROMETER,
                        sample.x,
                        sample.y,
                        sample.z,
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Registers the sensors the current settings call for.
     *
     * @param controllerNumber the pad slot to attribute samples to.
     * @return true when at least one sensor was registered.
     */
    fun start(controllerNumber: Int = 0): Boolean {
        if (registered) return true
        this.controllerNumber = controllerNumber
        val manager = sensorManager ?: return false
        val mode = settings().gyroMode
        if (mode == GyroMode.OFF) return false
        if (mode == GyroMode.CONTROLLER) {
            ProtocolLog.i(
                InputConstants.TAG,
                "gyro mode is CONTROLLER; this build does not read a pad's own sensors, so no " +
                    "motion is forwarded",
            )
            return false
        }

        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (gyro == null && accelerometer == null) {
            ProtocolLog.i(InputConstants.TAG, "this device has neither a gyroscope nor an accelerometer")
            return false
        }
        if (gyro != null) manager.registerListener(listener, gyro, SAMPLING_PERIOD_US)
        if (accelerometer != null) {
            manager.registerListener(listener, accelerometer, SAMPLING_PERIOD_US)
        }
        registered = true
        ProtocolLog.i(
            InputConstants.TAG,
            "motion forwarding started at ${REPORT_RATE_HZ}Hz (gyro=${gyro != null}, " +
                "accelerometer=${accelerometer != null})",
        )
        return true
    }

    /** Unregisters everything. Idempotent. */
    fun stop() {
        if (!registered) return
        registered = false
        sensorManager?.unregisterListener(listener)
    }

    /**
     * The display's rotation in degrees, for [MotionMath.transform].
     *
     * `Context.getDisplay()` is API 30+ and needs a visual context, which an Activity is;
     * `WindowManager.getDefaultDisplay()` is the deprecated path that still works below that.
     */
    @Suppress("DEPRECATION")
    private fun displayRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display?.rotation }.getOrNull()
        } else {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.defaultDisplay?.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private companion object {
        const val AXES: Int = 3

        /**
         * The rate sensors are sampled at.
         *
         * Spec §10.3 says to honour the rate the host requests by throttling, which
         * [com.voidlink.android.protocol.input.InputSender] does; sampling faster than the host
         * wants is wasted work, and sampling slower cannot be fixed downstream. 200 Hz is the
         * ceiling a DualSense reports at and is what `HIGH_SAMPLING_RATE_SENSORS` in the manifest
         * exists to allow on API 31+.
         */
        const val REPORT_RATE_HZ: Int = 200
        const val SAMPLING_PERIOD_US: Int = 1_000_000 / REPORT_RATE_HZ
    }
}
