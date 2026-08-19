package com.limelight.binding.input.trackpad;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * A blank surface on the second screen that drives the host's pointer.
 *
 * All the decisions about what a touch means live in {@link TrackpadGesture};
 * this only translates Android's event stream into calls on it and hands the
 * results to a listener. Kept black so the panel still reads as off next to
 * the game.
 */
public class SoftTrackpadView extends FrameLayout {

    public interface Listener {
        void onPointerMove(int dx, int dy);

        void onScroll(int clicks);

        void onLeftClick();

        void onRightClick();
    }

    private final TrackpadGesture gesture;
    private Listener listener;
    private final TextView hint;

    public SoftTrackpadView(Context context, float sensitivity) {
        super(context);
        this.gesture = new TrackpadGesture(sensitivity);

        setBackgroundColor(Color.BLACK);

        hint = new TextView(context);
        hint.setText(R.string.soft_trackpad_hint);
        hint.setTextColor(ContextCompat.getColor(context, R.color.vl_secondary_label));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setGravity(Gravity.CENTER);
        LayoutParams hintParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        hintParams.gravity = Gravity.CENTER;
        addView(hint, hintParams);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Hides the hint once the surface has been used, so it stops being noise. */
    private void dismissHint() {
        if (hint.getVisibility() == View.VISIBLE) {
            hint.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        long time = event.getEventTime();
        float x = event.getX();
        float y = event.getY();
        int count = event.getPointerCount();

        TrackpadGesture.Action action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dismissHint();
                action = gesture.onTouchDown(x, y, time, count);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                action = gesture.onPointerAdded(x, y, time, count);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                // count still includes the finger that is leaving.
                action = gesture.onPointerRemoved(x, y, time, Math.max(1, count - 1));
                break;
            case MotionEvent.ACTION_MOVE:
                action = gesture.onTouchMove(x, y, time, count);
                break;
            case MotionEvent.ACTION_UP:
                action = gesture.onTouchUp(x, y, time);
                break;
            case MotionEvent.ACTION_CANCEL:
                gesture.reset();
                return true;
            default:
                return true;
        }

        dispatch(action);
        return true;
    }

    private void dispatch(TrackpadGesture.Action action) {
        if (listener == null || action == null) {
            return;
        }
        switch (action.kind) {
            case MOVE:
                listener.onPointerMove(action.dx, action.dy);
                break;
            case SCROLL:
                listener.onScroll(action.dy);
                break;
            case LEFT_CLICK:
                listener.onLeftClick();
                break;
            case RIGHT_CLICK:
                listener.onRightClick();
                break;
            case NONE:
            default:
                break;
        }
    }

    /** Drops any touch in progress, for when the panel changes underneath it. */
    public void cancelTouches() {
        gesture.reset();
    }
}
