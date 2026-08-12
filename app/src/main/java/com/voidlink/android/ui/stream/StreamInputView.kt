package com.voidlink.android.ui.stream

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.media.Letterbox
import com.voidlink.android.media.VideoRect
import com.voidlink.android.media.VideoStreamFormat
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.input.InputConstants
import com.voidlink.android.protocol.input.InputSink
import com.voidlink.android.protocol.input.MouseButton
import com.voidlink.android.protocol.input.TouchAction
import com.voidlink.android.protocol.input.TouchRouter
import com.voidlink.android.protocol.input.TouchSurface
import com.voidlink.android.protocol.input.WindowsKeyCodes

/**
 * The invisible full-bleed input surface of UI spec §5.1's layer z1.
 *
 * A plain `View` rather than Compose pointer input, for three reasons Compose cannot currently
 * serve: **physical key events** (`onKeyDown`/`onKeyUp` with the device id and repeat count intact),
 * **joystick axes** (`onGenericMotionEvent`), and **pointer capture** (`onCapturedPointerEvent`,
 * API 26), which is the only way to get raw relative mouse movement on Android.
 *
 * Everything it does is decomposition: a `MotionEvent` into per-pointer calls on [TouchRouter], a
 * `KeyEvent` into either a pad button or a Windows virtual-key code, an axis packet into a
 * [com.voidlink.android.protocol.input.ControllerState]. The decisions all live behind that
 * boundary, in plain JVM classes that are unit-tested; this file is the part that cannot be.
 *
 * @param context the Activity context.
 * @param sink where input goes; read per event, because the session attaches after the surface is
 *   mounted.
 * @param settings the live settings.
 * @param onGesture told about the multi-finger gestures UI spec §5.4 binds to actions.
 * @param onPadAttached forwarded from [ControllerHub], so rumble can be routed to the pad that owns
 *   the slot the host is addressing.
 * @param onPadDetached forwarded from [ControllerHub].
 */
@Suppress("ViewConstructor")
class StreamInputView(
    context: Context,
    private val sink: () -> InputSink,
    private val settings: () -> StreamSettings,
    private val onGesture: (TouchRouter.TouchGesture) -> Unit = {},
    onPadAttached: (slot: Int, deviceId: Int) -> Unit = { _, _ -> },
    onPadDetached: (slot: Int) -> Unit = {},
) : View(context) {

    private val router = TouchRouter(sink) { gesture -> onGesture(gesture) }

    private val controllers = ControllerHub(
        context = context,
        sink = sink,
        settings = settings,
        onPadAttached = onPadAttached,
        onPadDetached = onPadDetached,
    )

    /** The stream's dimensions, for the letterbox maths. Null until a decoder has been chosen. */
    private var streamFormat: VideoStreamFormat? = null

    /** Which mouse buttons the host currently believes are down. */
    private var mouseButtonState: Int = 0

    /** Whether pointer capture has been asked for, so it is not requested on every focus change. */
    private var captureRequested: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The stream surface is beneath this view and must stay visible: this one exists only to
        // receive events.
        setWillNotDraw(true)
    }

    /** Starts watching for controllers. Call from the composable's `DisposableEffect`. */
    fun start() {
        controllers.start()
        requestFocus()
    }

    /** Stops watching and releases everything the host thinks is held. */
    fun stop() {
        controllers.stop()
        releaseEverything()
        releaseCapture()
    }

    /** Publishes the stream's dimensions and the current settings into the touch mapping. */
    fun updateStream(format: VideoStreamFormat?) {
        streamFormat = format
        refreshSurface()
    }

    /** Re-reads the settings into the touch mapping. Call when the settings flow emits. */
    fun refreshSurface() {
        val format = streamFormat
        val current = settings()
        router.updateSurface(
            TouchSurface(
                mode = current.touchMode,
                videoRect = if (format == null) {
                    VideoRect.EMPTY
                } else {
                    Letterbox.fit(format.width, format.height, width, height)
                },
                streamWidth = format?.width ?: 0,
                streamHeight = format?.height ?: 0,
                pointerVelocityPercent = current.touchPointerVelocityPercent,
                tapToClick = current.tapToClick,
                twoFingerTapRightClick = current.twoFingerTapRightClick,
                threeFingerTapMiddleClick = current.threeFingerTapMiddleClick,
                edgeSwipeEnabled = current.edgeSwipeEnabled,
                edgeSwipeStartPx = EDGE_SWIPE_START_DP * resources.displayMetrics.density,
                edgeSwipeDistancePx = current.exitSwipeDistanceDp * resources.displayMetrics.density,
            ),
        )
    }

    /**
     * Releases every held input.
     *
     * UI spec §5.4 requires this on focus loss, and the stream screen also calls it when a dialog or
     * the settings drawer opens.
     */
    fun releaseEverything() {
        router.onCancel()
        controllers.releaseAll()
        sink().releaseAll()
        mouseButtonState = 0
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // The video rectangle depends on the view's size, which changes on rotation. UI spec §5.7:
        // rotation must not restart the session, so the mapping is recomputed instead.
        refreshSurface()
    }

    // ---- Touch and mouse ---------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouseEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                dispatchPointer(TouchAction.DOWN, event, index)
            }

            MotionEvent.ACTION_MOVE ->
                // A move event batches every pointer, so all of them are dispatched.
                for (index in 0 until event.pointerCount) {
                    dispatchPointer(TouchAction.MOVE, event, index)
                }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                dispatchPointer(TouchAction.UP, event, event.actionIndex)

            MotionEvent.ACTION_CANCEL -> router.onCancel()

            else -> return false
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK)) {
            return controllers.onMotionEvent(event)
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouseEvent(event)
        return super.onGenericMotionEvent(event)
    }

    /**
     * Relative mouse movement from a captured pointer (API 26+).
     *
     * `onCapturedPointerEvent` is the only source of *raw* mouse deltas on Android: without capture,
     * a mouse moves the system cursor and reports absolute positions that stop at the screen edge,
     * which makes an FPS unplayable. minSdk is 26, so no version guard is needed.
     */
    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.getAxisValue(MotionEvent.AXIS_X).toInt()
                val dy = event.getAxisValue(MotionEvent.AXIS_Y).toInt()
                if (dx != 0 || dy != 0) sink().mouseMoveRelative(dx, dy)
            }

            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE ->
                return onMouseEvent(event)

            MotionEvent.ACTION_SCROLL -> return onMouseEvent(event)

            else -> return false
        }
        return true
    }

    /**
     * Mouse buttons, wheel and (uncaptured) motion.
     *
     * Buttons are tracked as a bitmask difference rather than read from `getActionButton()` alone,
     * because a mouse can press two buttons in one event and because a released mouse that never
     * sent its up event leaves the host dragging.
     */
    private fun onMouseEvent(event: MotionEvent): Boolean {
        val target = sink()
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vertical != 0f) target.scroll(vertical)
                if (horizontal != 0f) target.horizontalScroll(horizontal)
                return true
            }

            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                // Without pointer capture the mouse reports absolute positions; treating them as an
                // absolute pointer keeps the host's cursor under the user's cursor.
                val format = streamFormat
                val position = if (format == null) null else mapAbsolute(event.x, event.y)
                if (format != null && position != null) {
                    target.mouseMoveAbsolute(
                        x = position.first,
                        y = position.second,
                        referenceWidth = format.width,
                        referenceHeight = format.height,
                    )
                }
            }
        }

        val buttons = event.buttonState
        val changed = buttons xor mouseButtonState
        if (changed != 0) {
            mouseButtonState = buttons
            for ((mask, button) in MOUSE_BUTTONS) {
                if (changed and mask == 0) continue
                target.mouseButton(button, pressed = buttons and mask != 0)
            }
        }
        return true
    }

    private fun dispatchPointer(action: TouchAction, event: MotionEvent, index: Int) {
        router.onPointer(
            action = action,
            pointerId = event.getPointerId(index),
            x = event.getX(index),
            y = event.getY(index),
            pressure = event.getPressure(index),
            eventTimeMs = event.eventTime,
        )
    }

    private fun mapAbsolute(x: Float, y: Float): Pair<Int, Int>? {
        val format = streamFormat ?: return null
        val rect = Letterbox.fit(format.width, format.height, width, height)
        val point = Letterbox.normalize(rect, x, y) ?: return null
        return Letterbox.toStreamPixels(point, format.width, format.height)
    }

    // ---- Keyboard and pads --------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(keyCode, event, pressed = true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(keyCode, event, pressed = false) || super.onKeyUp(keyCode, event)

    private fun handleKey(keyCode: Int, event: KeyEvent, pressed: Boolean): Boolean {
        // Pads first: several of their buttons share key codes with the keyboard's (BACK, MENU,
        // the D-pad), and a pad press must not also type.
        if (controllers.onKeyEvent(event, pressed)) return true

        if (!settings().forwardKeyboard) return false
        // The Back key stays local: it is how the user leaves the stream, and forwarding it would
        // trap them (UI spec §5.2's disconnect flow owns it).
        if (keyCode == KeyEvent.KEYCODE_BACK) return false

        val virtualKey = WindowsKeyCodes.forAndroidKeyCode(keyCode) ?: return false
        if (pressed && event.repeatCount > 0) {
            // Windows generates its own key repeat from a held key; forwarding Android's as well
            // types twice as fast as the user is holding.
            return true
        }
        sink().key(
            virtualKeyCode = virtualKey,
            pressed = pressed,
            modifiers = WindowsKeyCodes.modifiersFromMetaState(event.metaState),
        )
        return true
    }

    // ---- Focus and capture ---------------------------------------------------------------------------

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            requestFocus()
            maybeRequestCapture()
        } else {
            // UI spec §5.4: focus loss releases everything. A key held when the app is backgrounded
            // would otherwise stay down on the host until the session ends.
            releaseEverything()
            releaseCapture()
        }
    }

    override fun onDetachedFromWindow() {
        releaseEverything()
        releaseCapture()
        super.onDetachedFromWindow()
    }

    /**
     * Asks for pointer capture, if the user wants it and a mouse is attached.
     *
     * Capture is refused by the platform unless the view is focused and its window has focus, and it
     * is silently ignored on a device with no pointing device, so the result is not treated as an
     * error — the uncaptured absolute-position path in [onMouseEvent] keeps working either way.
     */
    private fun maybeRequestCapture() {
        if (captureRequested || !settings().captureMouse) return
        if (!hasPointingDevice()) return
        captureRequested = true
        runCatching { requestPointerCapture() }.onFailure {
            captureRequested = false
            ProtocolLog.w(InputConstants.TAG, "pointer capture was refused: ${it.message}")
        }
    }

    private fun releaseCapture() {
        if (!captureRequested) return
        captureRequested = false
        runCatching { releasePointerCapture() }
    }

    private fun hasPointingDevice(): Boolean = InputDevice.getDeviceIds().any { deviceId ->
        val device = InputDevice.getDevice(deviceId) ?: return@any false
        device.supportsSource(InputDevice.SOURCE_MOUSE)
    }

    private companion object {

        /** UI spec §5.4: an edge swipe "starts within 20 dp of the start edge". */
        const val EDGE_SWIPE_START_DP: Float = 20f

        /**
         * Android's button masks to the protocol's button numbers (spec §10.3).
         *
         * X1/X2 are `BUTTON_BACK`/`BUTTON_FORWARD` on Android; spec §10.3 marks their protocol
         * numbering UNVERIFIED, and nothing else depends on it being right.
         */
        val MOUSE_BUTTONS: List<Pair<Int, MouseButton>> = listOf(
            MotionEvent.BUTTON_PRIMARY to MouseButton.LEFT,
            MotionEvent.BUTTON_TERTIARY to MouseButton.MIDDLE,
            MotionEvent.BUTTON_SECONDARY to MouseButton.RIGHT,
            MotionEvent.BUTTON_BACK to MouseButton.X1,
            MotionEvent.BUTTON_FORWARD to MouseButton.X2,
        )
    }
}
