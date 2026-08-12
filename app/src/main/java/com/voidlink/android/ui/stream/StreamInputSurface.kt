package com.voidlink.android.ui.stream

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.voidlink.android.data.GestureAction
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.media.VideoStreamFormat
import com.voidlink.android.protocol.input.HostInputFeedback
import com.voidlink.android.protocol.input.InputConstants
import com.voidlink.android.protocol.input.InputPipeline
import com.voidlink.android.protocol.input.TouchRouter
import kotlinx.coroutines.delay

/**
 * Layer z1 of UI spec §5.1: the invisible input surface over the video.
 *
 * Mounts [StreamInputView] and owns the three things around it that have a lifecycle — the
 * controller hub, the motion sensors and the rumble player — binding them to the composition rather
 * than to the Activity, so backgrounding the stream releases the sensors and stops the buzzing
 * without any of them needing to know about the session.
 *
 * ### Where the input actually goes
 *
 * Nowhere, until the session layer publishes a connection to
 * [com.voidlink.android.protocol.input.InputPipeline]. Until then the sink is
 * [com.voidlink.android.protocol.input.NoOpInputSink] and a touch during the connecting phase is a
 * no-op rather than a crash. The sink is read *per event*, so input starts working the instant the
 * control stream comes up, with no recomposition and no coordination between the two layers.
 *
 * @param format the stream's dimensions, for the letterbox mapping. Null before a decoder is chosen.
 * @param settings the merged global + per-host settings. Re-read on every event, so the in-stream
 *   drawer's live rows (touch mode, pointer velocity, gyro, rumble, swap A/B X/Y) take effect
 *   immediately, exactly as UI spec §5.3 requires.
 * @param onGestureAction invoked with the [GestureAction] bound to a recognised gesture, for the
 *   stream screen to act on.
 */
@Composable
fun StreamInputSurface(
    format: VideoStreamFormat?,
    settings: StreamSettings,
    onGestureAction: (GestureAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentSettings = rememberUpdatedState(settings)
    val currentGestureAction = rememberUpdatedState(onGestureAction)

    val rumble = remember { RumblePlayer(context) { currentSettings.value } }

    val view = remember {
        StreamInputView(
            context = context,
            sink = { InputPipeline.sink },
            settings = { currentSettings.value },
            onGesture = { gesture ->
                val action = when (gesture) {
                    TouchRouter.TouchGesture.THREE_FINGER_TAP ->
                        currentSettings.value.threeFingerTapAction
                            .takeIf { currentSettings.value.threeFingerTapEnabled }

                    TouchRouter.TouchGesture.EDGE_SWIPE ->
                        currentSettings.value.edgeSwipeAction
                            .takeIf { currentSettings.value.edgeSwipeEnabled }
                }
                if (action != null && action != GestureAction.NONE) {
                    currentGestureAction.value(action)
                }
            },
            onPadAttached = { slot, deviceId -> rumble.bindController(slot, deviceId) },
            onPadDetached = { slot -> rumble.unbindController(slot) },
        )
    }

    val motion = remember { MotionSensorPump(context, { InputPipeline.sink }, { currentSettings.value }) }

    AndroidView(
        factory = { view },
        modifier = modifier.fillMaxSize(),
        update = { it.updateStream(format) },
    )

    DisposableEffect(view) {
        val feedback: (HostInputFeedback) -> Unit = { message -> rumble.play(message) }
        InputPipeline.addFeedbackListener(feedback)
        view.start()
        motion.start()
        onDispose {
            InputPipeline.removeFeedbackListener(feedback)
            motion.stop()
            rumble.stop()
            view.stop()
        }
    }

    // Settings that change the touch mapping (mode, velocity, tap behaviours) are re-read here
    // rather than on every event, because the mapping is a value the router holds.
    LaunchedEffect(settings) {
        view.refreshSurface()
    }

    // Gyro registration depends on a setting that can change live; restarting the pump is cheap and
    // is the only way to pick up a switch from OFF.
    LaunchedEffect(settings.gyroMode) {
        motion.stop()
        motion.start()
    }

    /*
     * The flush tick of spec §10.4.
     *
     * Relative mouse deltas accumulate and are written at most once per
     * MOUSE_BATCH_INTERVAL_MS. Without a tick, a movement that stops exactly inside a batching
     * window would sit in the accumulator until the next touch — the pointer would lag the finger
     * by a few pixels at the end of every gesture.
     */
    LaunchedEffect(Unit) {
        while (true) {
            delay(InputConstants.MOUSE_BATCH_INTERVAL_MS)
            InputPipeline.sink.flush()
        }
    }
}
