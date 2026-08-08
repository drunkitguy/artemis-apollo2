package com.limelight.preferences;

import android.content.Context;
import android.view.MotionEvent;

/**
 * Recognises a drag inward from a screen edge, used to open the settings panel
 * without leaving the stream.
 *
 * <h3>Why this is conservative</h3>
 * These gestures live on top of a game. A false positive does not merely open a
 * panel, it eats part of a swipe the user meant for the host, mid-play. So the
 * detector requires all of:
 *
 * <ul>
 *   <li>the gesture to <em>start</em> within a narrow strip at the chosen edge,</li>
 *   <li>a drag inward of at least the user's configured distance,</li>
 *   <li>and horizontal movement clearly dominant over vertical, so a vertical
 *       swipe near the edge is not stolen.</li>
 * </ul>
 *
 * The distance is a preference rather than a constant precisely because how
 * much accidental triggering is tolerable depends on the game and the person.
 *
 * <p>This only ever <em>observes</em>. It never consumes the event, so if it is
 * wrong about a gesture the underlying stream still receives it. Deciding to
 * swallow input on a streaming client is not a decision to make on a heuristic.
 *
 * <p>Thread ownership: touch dispatch only, so main thread by construction. No
 * shared state.
 */
public class EdgeSwipeDetector {

    /** How wide the touch start zone is at the edge, in dp. */
    private static final int EDGE_ZONE_DP = 24;

    /** Horizontal travel must exceed vertical by this factor. */
    private static final float HORIZONTAL_DOMINANCE = 1.5f;

    public interface Listener {
        void onEdgeSwipe();
    }

    private final float edgeZonePx;
    private final float slideDistancePx;
    private final boolean fromLeftEdge;
    private final Listener listener;

    private boolean tracking;
    private boolean fired;
    private float startX;
    private float startY;

    public EdgeSwipeDetector(Context context, boolean fromLeftEdge,
                             int slideDistanceDp, Listener listener) {
        float density = context.getResources().getDisplayMetrics().density;
        this.edgeZonePx = EDGE_ZONE_DP * density;
        this.slideDistancePx = Math.max(10, slideDistanceDp) * density;
        this.fromLeftEdge = fromLeftEdge;
        this.listener = listener;
    }

    /**
     * Feeds a touch event to the detector. Always returns without consuming;
     * the caller must continue to dispatch the event as normal.
     */
    public void onTouchEvent(MotionEvent event, int viewWidth) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX();
                startY = event.getY();
                fired = false;
                tracking = fromLeftEdge
                        ? startX <= edgeZonePx
                        : startX >= viewWidth - edgeZonePx;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!tracking || fired) {
                    break;
                }

                float dx = event.getX() - startX;
                float dy = Math.abs(event.getY() - startY);
                float inward = fromLeftEdge ? dx : -dx;

                if (inward >= slideDistancePx && inward > dy * HORIZONTAL_DOMINANCE) {
                    fired = true;
                    tracking = false;
                    if (listener != null) {
                        listener.onEdgeSwipe();
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                fired = false;
                break;

            default:
                break;
        }
    }
}
