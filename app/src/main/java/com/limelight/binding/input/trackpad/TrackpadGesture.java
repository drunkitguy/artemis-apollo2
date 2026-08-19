package com.limelight.binding.input.trackpad;

/**
 * Turns touches on a blank surface into mouse actions.
 *
 * Pure Java on purpose. Tap thresholds, two finger handling and the
 * accumulation of fractional movement are the parts of a trackpad that feel
 * wrong when they are slightly off, and they are much easier to get right
 * against a test than against a device.
 *
 * The caller feeds raw touch events and applies whatever comes back. The
 * gesture keeps no Android state, so one instance per surface is enough.
 */
public final class TrackpadGesture {

    public enum Kind {
        /** Nothing to do for this event. */
        NONE,
        /** Move the pointer by dx, dy. */
        MOVE,
        /** Scroll by dy (positive is content moving up, as a wheel click would). */
        SCROLL,
        /** A complete click: the caller sends button down then up. */
        LEFT_CLICK,
        RIGHT_CLICK
    }

    public static final class Action {
        public static final Action NOTHING = new Action(Kind.NONE, 0, 0);

        public final Kind kind;
        public final int dx;
        public final int dy;

        Action(Kind kind, int dx, int dy) {
            this.kind = kind;
            this.dx = dx;
            this.dy = dy;
        }

        @Override
        public String toString() {
            return "Action(" + kind + ", " + dx + ", " + dy + ")";
        }
    }

    /** Longer than this and a touch is a drag, not a tap, however little it moved. */
    public static final long TAP_TIME_MS = 220;
    /** Further than this in pixels and it is a drag, however brief it was. */
    public static final float TAP_SLOP_PX = 18f;
    /** Pixels of two finger movement per wheel click. */
    public static final float SCROLL_STEP_PX = 42f;

    private final float sensitivity;

    private boolean tracking;
    private int activePointers;
    /** Highest pointer count seen during this touch, so a two finger tap stays one. */
    private int peakPointers;
    private long downTimeMs;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private boolean movedBeyondSlop;

    // Fractional movement carried between events. Without this a slow drag
    // loses every sub-pixel remainder and the pointer feels sticky.
    private float carryX;
    private float carryY;
    private float scrollCarry;

    /**
     * @param sensitivity multiplier on raw finger movement; 1 means one screen
     *                    pixel of finger travel is one unit of pointer movement
     */
    public TrackpadGesture(float sensitivity) {
        this.sensitivity = sensitivity > 0 ? sensitivity : 1f;
    }

    /** Forgets any touch in progress, for when the surface goes away. */
    public void reset() {
        tracking = false;
        activePointers = 0;
        peakPointers = 0;
        movedBeyondSlop = false;
        carryX = 0;
        carryY = 0;
        scrollCarry = 0;
    }

    public Action onTouchDown(float x, float y, long timeMs, int pointerCount) {
        tracking = true;
        activePointers = pointerCount;
        peakPointers = Math.max(1, pointerCount);
        downTimeMs = timeMs;
        downX = x;
        downY = y;
        lastX = x;
        lastY = y;
        movedBeyondSlop = false;
        carryX = 0;
        carryY = 0;
        scrollCarry = 0;
        return Action.NOTHING;
    }

    /** A second or third finger arriving mid touch. */
    public Action onPointerAdded(float x, float y, long timeMs, int pointerCount) {
        activePointers = pointerCount;
        peakPointers = Math.max(peakPointers, pointerCount);
        // Re-anchor so the jump to the new finger's position is not sent as a
        // movement. Without this, adding a finger flicks the pointer.
        lastX = x;
        lastY = y;
        return Action.NOTHING;
    }

    /** A finger leaving while others remain. */
    public Action onPointerRemoved(float x, float y, long timeMs, int pointerCount) {
        activePointers = pointerCount;
        lastX = x;
        lastY = y;
        return Action.NOTHING;
    }

    public Action onTouchMove(float x, float y, long timeMs, int pointerCount) {
        if (!tracking) {
            return Action.NOTHING;
        }

        activePointers = pointerCount;
        peakPointers = Math.max(peakPointers, pointerCount);

        float rawDx = x - lastX;
        float rawDy = y - lastY;
        lastX = x;
        lastY = y;

        if (Math.abs(x - downX) > TAP_SLOP_PX || Math.abs(y - downY) > TAP_SLOP_PX) {
            movedBeyondSlop = true;
        }

        if (pointerCount >= 2) {
            return scroll(rawDy);
        }

        return move(rawDx, rawDy);
    }

    public Action onTouchUp(float x, float y, long timeMs) {
        if (!tracking) {
            return Action.NOTHING;
        }
        tracking = false;

        boolean quick = timeMs - downTimeMs <= TAP_TIME_MS;
        boolean still = !movedBeyondSlop;
        int fingers = peakPointers;

        activePointers = 0;
        peakPointers = 0;

        if (!quick || !still) {
            return Action.NOTHING;
        }

        // A tap with two fingers is the standard way to ask for a right click
        // on a surface with no buttons of its own.
        if (fingers >= 2) {
            return new Action(Kind.RIGHT_CLICK, 0, 0);
        }
        return new Action(Kind.LEFT_CLICK, 0, 0);
    }

    private Action move(float rawDx, float rawDy) {
        float scaledX = rawDx * sensitivity + carryX;
        float scaledY = rawDy * sensitivity + carryY;

        int dx = (int) scaledX;
        int dy = (int) scaledY;

        // Keep what did not fit into a whole unit for next time.
        carryX = scaledX - dx;
        carryY = scaledY - dy;

        if (dx == 0 && dy == 0) {
            return Action.NOTHING;
        }
        return new Action(Kind.MOVE, dx, dy);
    }

    private Action scroll(float rawDy) {
        scrollCarry += rawDy;

        int clicks = (int) (scrollCarry / SCROLL_STEP_PX);
        if (clicks == 0) {
            return Action.NOTHING;
        }
        scrollCarry -= clicks * SCROLL_STEP_PX;

        // Dragging two fingers down should send the content down, which is a
        // negative wheel direction.
        return new Action(Kind.SCROLL, 0, clicks);
    }
}
