package com.voidlink.android.protocol.input

import com.voidlink.android.data.TouchMode
import com.voidlink.android.media.Letterbox
import com.voidlink.android.media.VideoRect
import kotlin.math.abs
import kotlin.math.roundToInt

/** What happened to one pointer. The Android view decomposes a `MotionEvent` into these. */
enum class TouchAction { DOWN, MOVE, UP, CANCEL }

/**
 * Everything the router needs to know about the surface it is mapping onto.
 *
 * Rebuilt whenever the view is measured or a setting changes, and read whole so a rotation cannot
 * leave the mode and the rectangle disagreeing.
 *
 * @property mode which of UI spec §5.4's three behaviours applies.
 * @property videoRect where the video sits inside the view, from [Letterbox.fit]. Touches outside it
 *   are in the black bands.
 * @property streamWidth the stream's width in pixels — the reference frame for Absolute Touch.
 * @property streamHeight the stream's height in pixels.
 * @property pointerVelocityPercent the `touchPointerVelocityPercent` setting (25..300), which scales
 *   Touchpad-mode movement only.
 * @property tapToClick whether a Touchpad-mode tap is a left click.
 * @property twoFingerTapRightClick whether a two-finger tap is a right click.
 * @property threeFingerTapMiddleClick whether a three-finger tap is a middle click.
 * @property edgeSwipeEnabled whether the edge-swipe recognizer runs at all. When it is off nothing
 *   is buffered, which UI spec §5.4 calls out as the point of Native Touch.
 * @property edgeSwipeStartPx how far from the start edge a contact may begin and still be an
 *   edge-swipe candidate — UI spec §5.4's 20 dp, converted by the view.
 * @property edgeSwipeDistancePx how far it must travel inward to count, from the
 *   `exitSwipeDistanceDp` setting.
 */
data class TouchSurface(
    val mode: TouchMode = TouchMode.TOUCHPAD,
    val videoRect: VideoRect = VideoRect.EMPTY,
    val streamWidth: Int = 0,
    val streamHeight: Int = 0,
    val pointerVelocityPercent: Int = 100,
    val tapToClick: Boolean = true,
    val twoFingerTapRightClick: Boolean = true,
    val threeFingerTapMiddleClick: Boolean = false,
    val edgeSwipeEnabled: Boolean = false,
    val edgeSwipeStartPx: Float = 0f,
    val edgeSwipeDistancePx: Float = 0f,
)

/**
 * Turns touches into host input, per UI spec §5.4 and §5.7.
 *
 * | Mode | Behaviour |
 * |---|---|
 * | **Touchpad** | Drag ⇒ relative mouse move scaled by the velocity setting. Tap ⇒ left click. Two-finger drag ⇒ scroll. Two-finger tap ⇒ right click. Tap-and-a-half ⇒ left button held for the drag. |
 * | **Native Touch** | Every pointer forwarded with its own id and normalized coordinates, up to [InputConstants.MAX_TOUCH_POINTERS]. Sunshine only. |
 * | **Absolute Touch** | Finger position maps 1:1 to the host cursor. Down ⇒ move then left-down; up ⇒ left-up. |
 *
 * ### The letterbox rule, which is not cosmetic
 *
 * UI spec §5.7: *"Touch coordinate mapping uses the video rectangle, not the view … touches in the
 * black bands are outside the stream and are **dropped** in Native/Absolute modes, and treated as
 * ordinary touchpad surface in Touchpad mode."* [Letterbox.normalize] returns `null` for exactly
 * those points, and this class drops them rather than clamping — a clamped touch is a phantom press
 * on the edge of the screen, which in a game is a shot fired at nothing.
 *
 * A pointer that goes *out* of the video mid-drag is treated as a pointer-up in Native and Absolute
 * modes, so the host does not keep a finger pinned at the last in-bounds position.
 *
 * ### Multi-finger gestures
 *
 * Three or more simultaneous pointers are never forwarded: UI spec §5.4 binds a three-finger tap to
 * a [com.voidlink.android.data.GestureAction], and the recognizer must win *before* pointers reach
 * the host. When a third finger lands, anything already sent for the current gesture is retracted —
 * held buttons released, touches cancelled — and [gestureListener] is told. What the gesture *does*
 * is the stream screen's decision, not this class's.
 *
 * Pure: no Android types, no clock of its own, no I/O. The view supplies event timestamps, which is
 * both what `MotionEvent` already carries and what makes tap timing testable.
 *
 * **Not thread-safe** — every call comes from the Android input thread.
 *
 * @param sink where input goes. A supplier rather than a value because the session attaches after
 *   the surface is already mounted (see [InputPipeline]).
 * @param gestureListener told when a multi-finger gesture is recognised.
 */
class TouchRouter(
    private val sink: () -> InputSink,
    private val gestureListener: (TouchGesture) -> Unit = {},
) {

    /** A recognised multi-finger gesture, for the stream screen to bind an action to. */
    enum class TouchGesture {
        /** Three fingers went down and came back up without travelling. */
        THREE_FINGER_TAP,

        /** A drag that started within the start-edge threshold and travelled inward. */
        EDGE_SWIPE,
    }

    private class Pointer(
        val id: Int,
        val downX: Float,
        val downY: Float,
        val downTimeMs: Long,
        var lastX: Float,
        var lastY: Float,
        var lastTimeMs: Long = downTimeMs,
        var travelled: Boolean = false,
        var forwarded: Boolean = false,
    )

    /** Live pointers, in the order they went down. */
    private val pointers = LinkedHashMap<Int, Pointer>()

    private var surface = TouchSurface()

    /** Highest simultaneous pointer count of the current gesture, so a 2→1 lift is not a tap. */
    private var peakPointerCount: Int = 0

    /** When the previous single-finger tap lifted, for the tap-and-a-half recognizer. */
    private var lastTapUpTimeMs: Long = Long.MIN_VALUE

    /** True while the left button is held for a tap-and-a-half drag. */
    private var dragHoldActive: Boolean = false

    /** True once a gesture has been claimed by the multi-finger recognizer. */
    private var gestureClaimed: Boolean = false

    /** Accumulated two-finger scroll distance not yet worth a wheel click. */
    private var scrollAccumulator: Float = 0f

    /**
     * The pointer whose start was buffered because it began at the start edge, if any.
     *
     * UI spec §5.4: an edge-originating contact is held back until the recognizer decides, and
     * replayed in order if it does not resolve into the gesture.
     */
    private var pendingEdgePointerId: Int? = null

    /** Replaces the surface description. Safe to call between events; not during one. */
    fun updateSurface(surface: TouchSurface) {
        this.surface = surface
    }

    /** The surface currently in force. */
    fun surface(): TouchSurface = surface

    /**
     * Feeds one pointer event.
     *
     * @param x view-space x in pixels.
     * @param y view-space y in pixels.
     * @param pressure 0..1 as Android reports it.
     * @param eventTimeMs the event's own timestamp — `MotionEvent.getEventTime()`.
     */
    fun onPointer(
        action: TouchAction,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
        eventTimeMs: Long,
    ) {
        when (action) {
            TouchAction.DOWN -> onDown(pointerId, x, y, pressure, eventTimeMs)
            TouchAction.MOVE -> onMove(pointerId, x, y, pressure, eventTimeMs)
            TouchAction.UP -> onUp(pointerId, x, y, pressure, eventTimeMs)
            TouchAction.CANCEL -> onCancel()
        }
    }

    /**
     * Drops every pointer and releases everything held.
     *
     * Called on focus loss as well as on `ACTION_CANCEL`; UI spec §5.4 requires both to reach
     * `LI_TOUCH_EVENT_CANCEL_ALL` and to release held buttons.
     */
    fun onCancel() {
        val target = sink()
        if (pointers.values.any { it.forwarded }) target.touch(
            eventType = TouchEventType.CANCEL_ALL,
            pointerId = 0,
            x = 0f,
            y = 0f,
            pressureOrDistance = 0f,
        )
        if (dragHoldActive) {
            target.mouseButton(MouseButton.LEFT, pressed = false)
            dragHoldActive = false
        }
        pointers.clear()
        peakPointerCount = 0
        gestureClaimed = false
        scrollAccumulator = 0f
        pendingEdgePointerId = null
    }

    // ---- Pointer lifecycle -----------------------------------------------------------------------

    private fun onDown(pointerId: Int, x: Float, y: Float, pressure: Float, timeMs: Long) {
        pointers[pointerId] = Pointer(pointerId, x, y, timeMs, x, y)
        peakPointerCount = maxOf(peakPointerCount, pointers.size)

        if (pointers.size >= GESTURE_POINTER_COUNT) {
            // A third finger means the gesture recognizer owns this contact. Retract anything
            // already sent so the host does not keep a click or a touch from the first two.
            claimGesture()
            return
        }
        if (gestureClaimed) return

        // UI spec §5.4: an edge-originating contact is buffered while the recognizer decides, and
        // replayed if it turns out not to be the gesture. A recognizer whose toggle is off does no
        // buffering at all, "which is the point of Native Touch".
        if (pointers.size == 1 && isEdgeCandidate(x)) {
            pendingEdgePointerId = pointerId
            return
        }

        beginPointer(pointerId, x, y, pressure, timeMs)
    }

    /** The mode-specific half of a pointer going down, separated so a buffered one can be replayed. */
    private fun beginPointer(pointerId: Int, x: Float, y: Float, pressure: Float, timeMs: Long) {
        when (surface.mode) {
            TouchMode.TOUCHPAD -> {
                if (pointers.size == 1 && isTapAndAHalf(timeMs)) {
                    dragHoldActive = true
                    sink().mouseButton(MouseButton.LEFT, pressed = true)
                }
                if (pointers.size == 2) scrollAccumulator = 0f
            }

            TouchMode.NATIVE_TOUCH -> forwardNative(pointerId, TouchEventType.DOWN, x, y, pressure)

            TouchMode.ABSOLUTE_TOUCH -> {
                // Only the first finger drives the cursor; a second finger in this mode is a
                // right-click candidate and is handled on the way up.
                if (pointers.size == 1) {
                    if (moveAbsolute(x, y)) {
                        sink().mouseButton(MouseButton.LEFT, pressed = true)
                        pointers[pointerId]?.forwarded = true
                    }
                }
            }
        }
    }

    private fun onMove(pointerId: Int, x: Float, y: Float, pressure: Float, timeMs: Long) {
        pointers[pointerId]?.lastTimeMs = timeMs
        if (pointerId == pendingEdgePointerId) {
            resolveEdgeCandidate(pointerId, x, y, pressure)
            if (gestureClaimed || pointerId == pendingEdgePointerId) return
        }
        val pointer = pointers[pointerId] ?: return
        val dx = x - pointer.lastX
        val dy = y - pointer.lastY
        pointer.lastX = x
        pointer.lastY = y
        if (abs(x - pointer.downX) > TAP_SLOP_PX || abs(y - pointer.downY) > TAP_SLOP_PX) {
            pointer.travelled = true
        }
        if (gestureClaimed || pointers.size >= GESTURE_POINTER_COUNT) return

        when (surface.mode) {
            TouchMode.TOUCHPAD -> when (pointers.size) {
                1 -> {
                    val velocity = surface.pointerVelocityPercent / PERCENT
                    val moveX = (dx * velocity).roundToInt()
                    val moveY = (dy * velocity).roundToInt()
                    if (moveX != 0 || moveY != 0) sink().mouseMoveRelative(moveX, moveY)
                }
                // Two fingers scroll. Only the first pointer's delta is used rather than the
                // average: averaging makes a pinch — where the two fingers move in opposite
                // directions — cancel out to a jittery nothing instead of simply not scrolling.
                2 -> if (pointerId == pointers.keys.first()) accumulateScroll(dy)
                else -> Unit
            }

            TouchMode.NATIVE_TOUCH -> forwardNative(pointerId, TouchEventType.MOVE, x, y, pressure)

            TouchMode.ABSOLUTE_TOUCH -> if (pointerId == pointers.keys.first()) moveAbsolute(x, y)
        }
    }

    private fun onUp(pointerId: Int, x: Float, y: Float, pressure: Float, timeMs: Long) {
        if (pointerId == pendingEdgePointerId) {
            // The finger lifted before the swipe completed: it was an ordinary contact after all,
            // so replay its start before handling the lift.
            val buffered = pointers[pointerId]
            pendingEdgePointerId = null
            if (buffered != null) {
                beginPointer(pointerId, buffered.downX, buffered.downY, pressure, buffered.downTimeMs)
            }
        }
        val pointer = pointers.remove(pointerId) ?: return
        val wasTap = !pointer.travelled && timeMs - pointer.downTimeMs <= TAP_MAX_DURATION_MS

        if (gestureClaimed) {
            if (pointers.isEmpty()) finishGesture(wasTap)
            return
        }

        when (surface.mode) {
            TouchMode.TOUCHPAD -> onTouchpadUp(wasTap, timeMs)
            TouchMode.NATIVE_TOUCH ->
                if (pointer.forwarded) forwardNative(pointerId, TouchEventType.UP, x, y, pressure)
            TouchMode.ABSOLUTE_TOUCH -> if (pointer.forwarded) {
                sink().mouseButton(MouseButton.LEFT, pressed = false)
            }
        }

        if (pointers.isEmpty()) {
            peakPointerCount = 0
            scrollAccumulator = 0f
        }
    }

    private fun onTouchpadUp(wasTap: Boolean, timeMs: Long) {
        val target = sink()
        if (dragHoldActive && pointers.isEmpty()) {
            target.mouseButton(MouseButton.LEFT, pressed = false)
            dragHoldActive = false
            return
        }
        if (!wasTap || !pointers.isEmpty()) return

        when (peakPointerCount) {
            1 -> if (surface.tapToClick) {
                click(MouseButton.LEFT)
                // Remember the lift so an immediate second contact becomes a drag-with-button-held
                // rather than a second click (UI spec §5.4's "tap-and-a-half").
                lastTapUpTimeMs = timeMs
            }
            2 -> if (surface.twoFingerTapRightClick) click(MouseButton.RIGHT)
            else -> Unit
        }
    }

    // ---- Modes ------------------------------------------------------------------------------------

    /**
     * Forwards one native-touch pointer, dropping anything in a letterbox band.
     *
     * A pointer that leaves the video mid-drag is reported as an UP: it has left the host's surface,
     * and leaving it pinned at the boundary is worse than ending the touch.
     */
    private fun forwardNative(
        pointerId: Int,
        eventType: TouchEventType,
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        val pointer = pointers[pointerId]
        if (pointerId >= InputConstants.MAX_TOUCH_POINTERS) return

        val point = Letterbox.normalize(surface.videoRect, x, y)
        if (point == null) {
            // Outside the video. If this pointer was already down on the host, end it there.
            if (pointer != null && pointer.forwarded) {
                pointer.forwarded = false
                sink().touch(TouchEventType.UP, pointerId, 0f, 0f, 0f)
            }
            return
        }

        // A pointer whose DOWN was dropped (it started in a band) must not send a bare MOVE: the
        // host would have no matching contact. Promote it to a DOWN instead.
        val type = if (eventType == TouchEventType.MOVE && pointer?.forwarded != true) {
            TouchEventType.DOWN
        } else {
            eventType
        }
        if (pointer != null) pointer.forwarded = type != TouchEventType.UP
        sink().touch(
            eventType = type,
            pointerId = pointerId,
            x = point.x,
            y = point.y,
            pressureOrDistance = pressure,
        )
    }

    /**
     * Sends an absolute cursor position for a view-space point.
     *
     * @return true when the point was inside the video and a packet was sent.
     */
    private fun moveAbsolute(x: Float, y: Float): Boolean {
        val point = Letterbox.normalize(surface.videoRect, x, y) ?: return false
        if (surface.streamWidth <= 0 || surface.streamHeight <= 0) return false
        val pixels = Letterbox.toStreamPixels(point, surface.streamWidth, surface.streamHeight)
        sink().mouseMoveAbsolute(
            x = pixels.first,
            y = pixels.second,
            referenceWidth = surface.streamWidth,
            referenceHeight = surface.streamHeight,
        )
        return true
    }

    private fun accumulateScroll(deltaY: Float) {
        scrollAccumulator += deltaY
        val clicks = (scrollAccumulator / SCROLL_PIXELS_PER_CLICK)
        if (abs(clicks) < MIN_SCROLL_CLICKS) return
        scrollAccumulator -= clicks * SCROLL_PIXELS_PER_CLICK
        // Dragging two fingers *down* scrolls the content *up*, which is what every touch surface
        // does and the opposite of what the raw delta says.
        sink().scroll(-clicks)
    }

    private fun click(button: MouseButton) {
        val target = sink()
        target.mouseButton(button, pressed = true)
        target.mouseButton(button, pressed = false)
    }

    /** Whether a contact at [x] started inside the start-edge strip and the recognizer is enabled. */
    private fun isEdgeCandidate(x: Float): Boolean =
        surface.edgeSwipeEnabled && surface.edgeSwipeStartPx > 0f && x <= surface.edgeSwipeStartPx

    /**
     * Decides what a buffered edge contact turned out to be.
     *
     * Three outcomes: the swipe completed (claim the gesture), the contact went somewhere else or
     * ran out of time (replay it and carry on), or it is still ambiguous (keep buffering). The time
     * limit is what stops a finger resting on the edge from swallowing input forever.
     */
    private fun resolveEdgeCandidate(pointerId: Int, x: Float, y: Float, pressure: Float) {
        val pointer = pointers[pointerId] ?: return
        val travelledInward = x - pointer.downX
        val travelledVertically = abs(y - pointer.downY)

        if (surface.edgeSwipeDistancePx > 0f && travelledInward >= surface.edgeSwipeDistancePx) {
            pendingEdgePointerId = null
            claimGesture()
            gestureListener(TouchGesture.EDGE_SWIPE)
            return
        }
        val expired = pointer.lastTimeMs - pointer.downTimeMs > EDGE_BUFFER_MS
        val wrongWay = travelledInward < -TAP_SLOP_PX || travelledVertically > surface.edgeSwipeDistancePx
        if (expired || wrongWay) {
            pendingEdgePointerId = null
            beginPointer(pointerId, pointer.downX, pointer.downY, pressure, pointer.downTimeMs)
        }
    }

    private fun isTapAndAHalf(timeMs: Long): Boolean =
        surface.mode == TouchMode.TOUCHPAD &&
            surface.tapToClick &&
            lastTapUpTimeMs != Long.MIN_VALUE &&
            timeMs - lastTapUpTimeMs <= TAP_AND_A_HALF_WINDOW_MS

    // ---- Gestures ---------------------------------------------------------------------------------

    private fun claimGesture() {
        if (gestureClaimed) return
        gestureClaimed = true
        val target = sink()
        if (dragHoldActive) {
            target.mouseButton(MouseButton.LEFT, pressed = false)
            dragHoldActive = false
        }
        if (pointers.values.any { it.forwarded }) {
            target.touch(TouchEventType.CANCEL_ALL, 0, 0f, 0f, 0f)
            pointers.values.forEach { it.forwarded = false }
        }
    }

    private fun finishGesture(lastPointerWasTap: Boolean) {
        val recognised = lastPointerWasTap && peakPointerCount == GESTURE_POINTER_COUNT
        gestureClaimed = false
        peakPointerCount = 0
        if (recognised) {
            if (surface.threeFingerTapMiddleClick) click(MouseButton.MIDDLE)
            gestureListener(TouchGesture.THREE_FINGER_TAP)
        }
    }

    private companion object {
        const val PERCENT: Float = 100f

        /** Movement above this makes a contact a drag rather than a tap. */
        const val TAP_SLOP_PX: Float = 16f

        /** A contact longer than this is a press-and-hold, not a tap. */
        const val TAP_MAX_DURATION_MS: Long = 250L

        /** How soon after a tap a new contact becomes a button-held drag (UI spec §5.4). */
        const val TAP_AND_A_HALF_WINDOW_MS: Long = 300L

        /** Finger travel worth one wheel click in two-finger scrolling. */
        const val SCROLL_PIXELS_PER_CLICK: Float = 60f

        /** Below this the accumulator keeps waiting, so a slow scroll is smooth rather than steppy. */
        const val MIN_SCROLL_CLICKS: Float = 0.05f

        /** UI spec §5.4 binds a **three**-finger tap; a fourth finger is part of the same gesture. */
        const val GESTURE_POINTER_COUNT: Int = 3

        /** UI spec §5.4's "~80 ms" buffering window for an edge-originating contact. */
        const val EDGE_BUFFER_MS: Long = 80L
    }
}
