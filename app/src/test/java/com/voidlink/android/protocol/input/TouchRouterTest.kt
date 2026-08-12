package com.voidlink.android.protocol.input

import com.voidlink.android.data.TouchMode
import com.voidlink.android.media.Letterbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Records what the router asked for, as readable strings. */
private class ScriptSink(override val supportsSunshineExtensions: Boolean = true) : InputSink {

    val calls = mutableListOf<String>()

    override fun mouseMoveRelative(deltaX: Int, deltaY: Int) {
        calls += "rel($deltaX,$deltaY)"
    }

    override fun mouseMoveAbsolute(x: Int, y: Int, referenceWidth: Int, referenceHeight: Int) {
        calls += "abs($x,$y,$referenceWidth,$referenceHeight)"
    }

    override fun mouseButton(button: MouseButton, pressed: Boolean) {
        calls += "${button.name.lowercase()}${if (pressed) "Down" else "Up"}"
    }

    override fun scroll(clicks: Float) {
        calls += "scroll(${if (clicks > 0) "+" else "-"})"
    }

    override fun horizontalScroll(clicks: Float) {
        calls += "hscroll"
    }

    override fun key(virtualKeyCode: Int, pressed: Boolean, modifiers: Int) {
        calls += "key"
    }

    override fun text(text: String) {
        calls += "text"
    }

    override fun controllerArrived(
        controllerNumber: Int,
        type: ControllerType,
        capabilities: Int,
        supportedButtonFlags: Int,
    ) = Unit

    override fun controllerRemoved(controllerNumber: Int) = Unit
    override fun controllerState(state: ControllerState) = Unit
    override fun controllerMotion(
        controllerNumber: Int,
        type: MotionType,
        x: Float,
        y: Float,
        z: Float,
    ) = Unit

    override fun touch(
        eventType: TouchEventType,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Int,
    ) {
        calls += "touch(${eventType.name},$pointerId,${round(x)},${round(y)})"
    }

    override fun releaseAll() {
        calls += "releaseAll"
    }

    override fun flush() = Unit

    // Locale.US explicitly: a device or CI runner set to a comma-decimal locale would
    // otherwise format "0,50" and fail every assertion in this file for no real reason.
    private fun round(value: Float): String = String.format(Locale.US, "%.2f", value)
}

/**
 * The three touch modes of UI spec §5.4 and the letterbox rule of §5.7.
 *
 * The rule worth the most attention is the black-band drop. UI spec §5.7: *"touches in the black
 * bands are outside the stream and are **dropped** in Native/Absolute modes"*. Clamping them instead
 * — which is what a `coerceIn` on the coordinates would silently do — turns a thumb resting on the
 * bezel into a permanent press on the edge of the game world.
 */
class TouchRouterTest {

    /** A 1920×1080 stream inside a 2000×1200 view: 60 px black bands top and bottom. */
    private val videoRect = Letterbox.fit(1920, 1080, 2000, 1200)

    private fun router(
        sink: ScriptSink,
        mode: TouchMode,
        gestures: MutableList<TouchRouter.TouchGesture> = mutableListOf(),
    ): TouchRouter = TouchRouter({ sink }, { gestures += it }).apply {
        updateSurface(
            TouchSurface(
                mode = mode,
                videoRect = videoRect,
                streamWidth = 1920,
                streamHeight = 1080,
                pointerVelocityPercent = 100,
            ),
        )
    }

    private fun TouchRouter.down(id: Int, x: Float, y: Float, time: Long) =
        onPointer(TouchAction.DOWN, id, x, y, 1f, time)

    private fun TouchRouter.move(id: Int, x: Float, y: Float, time: Long) =
        onPointer(TouchAction.MOVE, id, x, y, 1f, time)

    private fun TouchRouter.up(id: Int, x: Float, y: Float, time: Long) =
        onPointer(TouchAction.UP, id, x, y, 1f, time)

    // ---- Touchpad mode ---------------------------------------------------------------------------

    @Test
    fun `a drag becomes relative movement scaled by the velocity setting`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD)
        router.updateSurface(router.surface().copy(pointerVelocityPercent = 200))

        router.down(0, 100f, 100f, 0)
        router.move(0, 110f, 105f, 10)

        assertEquals(listOf("rel(20,10)"), sink.calls)
    }

    @Test
    fun `a tap is a left click`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD)
        router.down(0, 100f, 100f, 0)
        router.up(0, 100f, 100f, 50)
        assertEquals(listOf("leftDown", "leftUp"), sink.calls)
    }

    @Test
    fun `a long press is not a tap`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD)
        router.down(0, 100f, 100f, 0)
        router.up(0, 100f, 100f, 5_000)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `a drag that ends is not a tap either`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD)
        router.down(0, 100f, 100f, 0)
        router.move(0, 300f, 100f, 20)
        router.up(0, 300f, 100f, 40)
        assertTrue(sink.calls.none { it == "leftDown" })
    }

    @Test
    fun `tap-and-a-half holds the left button down for the drag that follows`() {
        // UI spec §5.4's "drag-after-tap": tap, then put the finger straight back down and move.
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD)
        router.down(0, 100f, 100f, 0)
        router.up(0, 100f, 100f, 40)
        sink.calls.clear()

        router.down(0, 100f, 100f, 100)
        router.move(0, 150f, 100f, 120)
        router.up(0, 150f, 100f, 200)

        assertEquals(listOf("leftDown", "rel(50,0)", "leftUp"), sink.calls)
    }

    @Test
    fun `a two-finger tap is a right click`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD)
        router.down(0, 100f, 100f, 0)
        router.down(1, 200f, 100f, 10)
        router.up(0, 100f, 100f, 40)
        router.up(1, 200f, 100f, 50)
        assertEquals(listOf("rightDown", "rightUp"), sink.calls)
    }

    @Test
    fun `a two-finger drag scrolls, and dragging down scrolls the content up`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD)
        router.down(0, 100f, 100f, 0)
        router.down(1, 200f, 100f, 5)
        router.move(0, 100f, 200f, 20)

        assertEquals(listOf("scroll(-)"), sink.calls)
    }

    @Test
    fun `a third finger retracts what the first two started and reports a gesture`() {
        val gestures = mutableListOf<TouchRouter.TouchGesture>()
        val sink = ScriptSink()
        val router = router(sink, TouchMode.TOUCHPAD, gestures)

        router.down(0, 100f, 100f, 0)
        router.down(1, 200f, 100f, 5)
        router.down(2, 300f, 100f, 10)
        router.up(0, 100f, 100f, 40)
        router.up(1, 200f, 100f, 45)
        router.up(2, 300f, 100f, 50)

        assertEquals(listOf(TouchRouter.TouchGesture.THREE_FINGER_TAP), gestures)
        // Nothing reached the host: the recognizer wins before pointers are forwarded.
        assertTrue(sink.calls.isEmpty())
    }

    // ---- Native touch mode -----------------------------------------------------------------------

    @Test
    fun `native touch forwards normalized coordinates against the video rectangle`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.NATIVE_TOUCH)
        // The video occupies y = 60..1140 in a 1200-tall view, so the view's centre is the video's
        // centre and (0, 60) is the video's top-left corner.
        router.down(0, 1000f, 600f, 0)
        assertEquals(listOf("touch(DOWN,0,0.50,0.50)"), sink.calls)
    }

    @Test
    fun `a touch in a letterbox band is dropped, not clamped`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.NATIVE_TOUCH)
        router.down(0, 1000f, 10f, 0) // in the top black band
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `a pointer that leaves the video mid-drag is ended rather than pinned to the edge`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.NATIVE_TOUCH)
        router.down(0, 1000f, 600f, 0)
        router.move(0, 1000f, 10f, 20)
        assertEquals(listOf("touch(DOWN,0,0.50,0.50)", "touch(UP,0,0.00,0.00)"), sink.calls)
    }

    @Test
    fun `a pointer that starts in a band and moves into the video arrives as a DOWN`() {
        // A bare MOVE would reference a contact the host never opened.
        val sink = ScriptSink()
        val router = router(sink, TouchMode.NATIVE_TOUCH)
        router.down(0, 1000f, 10f, 0)
        router.move(0, 1000f, 600f, 20)
        assertEquals(listOf("touch(DOWN,0,0.50,0.50)"), sink.calls)
    }

    @Test
    fun `every pointer keeps its own id`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.NATIVE_TOUCH)
        router.down(0, 500f, 600f, 0)
        router.down(1, 1500f, 600f, 5)
        assertEquals(
            listOf("touch(DOWN,0,0.25,0.50)", "touch(DOWN,1,0.75,0.50)"),
            sink.calls,
        )
    }

    @Test
    fun `an eleventh pointer is ignored rather than sent past the protocol's limit`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.NATIVE_TOUCH)
        router.down(InputConstants.MAX_TOUCH_POINTERS, 1000f, 600f, 0)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `cancelling releases every forwarded pointer at once`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.NATIVE_TOUCH)
        router.down(0, 500f, 600f, 0)
        router.down(1, 1500f, 600f, 5)
        sink.calls.clear()

        router.onCancel()
        assertEquals(listOf("touch(CANCEL_ALL,0,0.00,0.00)"), sink.calls)
    }

    // ---- Absolute touch mode ---------------------------------------------------------------------

    @Test
    fun `absolute touch maps the finger onto stream pixels and clicks`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.ABSOLUTE_TOUCH)
        router.down(0, 1000f, 600f, 0)
        router.move(0, 1500f, 600f, 20)
        router.up(0, 1500f, 600f, 40)

        assertEquals(
            listOf(
                "abs(960,540,1920,1080)",
                "leftDown",
                "abs(1440,540,1920,1080)",
                "leftUp",
            ),
            sink.calls,
        )
    }

    @Test
    fun `absolute touch drops a press in the black bands`() {
        val sink = ScriptSink()
        val router = router(sink, TouchMode.ABSOLUTE_TOUCH)
        router.down(0, 1000f, 5f, 0)
        router.up(0, 1000f, 5f, 40)
        assertTrue(sink.calls.isEmpty())
    }

    // ---- The edge swipe (UI spec §5.4) -----------------------------------------------------------

    private fun edgeRouter(
        sink: ScriptSink,
        mode: TouchMode,
        gestures: MutableList<TouchRouter.TouchGesture>,
        enabled: Boolean = true,
    ): TouchRouter = TouchRouter({ sink }, { gestures += it }).apply {
        updateSurface(
            TouchSurface(
                mode = mode,
                videoRect = videoRect,
                streamWidth = 1920,
                streamHeight = 1080,
                edgeSwipeEnabled = enabled,
                edgeSwipeStartPx = 40f,
                edgeSwipeDistancePx = 200f,
            ),
        )
    }

    @Test
    fun `a swipe in from the start edge is a gesture, and nothing reaches the host`() {
        val gestures = mutableListOf<TouchRouter.TouchGesture>()
        val sink = ScriptSink()
        val router = edgeRouter(sink, TouchMode.NATIVE_TOUCH, gestures)

        router.down(0, 10f, 600f, 0)
        router.move(0, 300f, 600f, 30)
        router.up(0, 300f, 600f, 60)

        assertEquals(listOf(TouchRouter.TouchGesture.EDGE_SWIPE), gestures)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun `an edge contact that is not a swipe is replayed to the host in order`() {
        // UI spec §5.4: "if it does not resolve into the gesture, replay the buffered pointers to
        // the host in order". Without the replay, every touch near the start edge would vanish.
        val gestures = mutableListOf<TouchRouter.TouchGesture>()
        val sink = ScriptSink()
        val router = edgeRouter(sink, TouchMode.NATIVE_TOUCH, gestures)

        router.down(0, 10f, 600f, 0)
        router.move(0, 20f, 600f, 200) // past the 80 ms window, nowhere near the distance

        assertTrue(gestures.isEmpty())
        // The DOWN is replayed at its original position, then the MOVE follows.
        assertEquals(
            listOf("touch(DOWN,0,0.00,0.50)", "touch(MOVE,0,0.01,0.50)"),
            sink.calls,
        )
    }

    @Test
    fun `a tap at the start edge still clicks once the buffer resolves`() {
        val gestures = mutableListOf<TouchRouter.TouchGesture>()
        val sink = ScriptSink()
        val router = edgeRouter(sink, TouchMode.TOUCHPAD, gestures)

        router.down(0, 10f, 600f, 0)
        router.up(0, 10f, 600f, 40)

        assertEquals(listOf("leftDown", "leftUp"), sink.calls)
    }

    @Test
    fun `a disabled recognizer buffers nothing, which is the point of Native Touch`() {
        val gestures = mutableListOf<TouchRouter.TouchGesture>()
        val sink = ScriptSink()
        val router = edgeRouter(sink, TouchMode.NATIVE_TOUCH, gestures, enabled = false)

        router.down(0, 10f, 600f, 0)
        assertEquals(listOf("touch(DOWN,0,0.00,0.50)"), sink.calls)
    }

    @Test
    fun `an unmeasured surface sends nothing at all`() {
        // Before the first layout the video rectangle is empty; every touch is outside it.
        val sink = ScriptSink()
        val router = TouchRouter({ sink }).apply {
            updateSurface(TouchSurface(mode = TouchMode.NATIVE_TOUCH))
        }
        router.down(0, 100f, 100f, 0)
        assertTrue(sink.calls.isEmpty())
    }
}
