package com.limelight.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/**
 * A two-chip overlay ("ABC" / "123") briefly offered on the secondary display after a
 * trackpad left click.
 *
 * The stream protocol carries no host -> client field focus or input type event (see
 * MoonBridge's callback set), so the client cannot know what kind of field was clicked.
 * The click is the only signal we have; the user picks the layout once and
 * SoftKeyboardController remembers it for the rest of the session.
 */
public class SoftKeyboardPrompt extends LinearLayout {

    /** Invoked when the user picks a layout. */
    public interface Callback {
        void onKeyboardModeChosen(SoftKeyboardController.Mode mode);
    }

    private static final int AUTO_HIDE_DELAY_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideRunnable = this::hide;

    public SoftKeyboardPrompt(Context context, Callback callback) {
        super(context);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setFocusable(false);
        setAlpha(0.75f);
        setVisibility(GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dpToPx(24);
        setLayoutParams(params);

        addView(createChip(R.string.soft_keyboard_prompt_text, SoftKeyboardController.Mode.TEXT, callback));
        addView(createChip(R.string.soft_keyboard_prompt_number, SoftKeyboardController.Mode.NUMBER, callback));
    }

    private TextView createChip(int labelResId, SoftKeyboardController.Mode mode, Callback callback) {
        TextView chip = new TextView(getContext());
        chip.setText(labelResId);
        chip.setTextColor(Color.WHITE);
        chip.setBackgroundColor(Color.argb(200, 0, 0, 0));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));

        // The chip must never take focus or the IME would attach to it instead of the
        // touch surface, which is the view that forwards text to the host.
        chip.setFocusable(false);
        chip.setFocusableInTouchMode(false);

        chip.setOnClickListener(v -> {
            hide();
            if (callback != null) {
                callback.onKeyboardModeChosen(mode);
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dpToPx(8), 0, dpToPx(8), 0);
        chip.setLayoutParams(params);

        return chip;
    }

    public void show() {
        setVisibility(VISIBLE);
        handler.removeCallbacks(autoHideRunnable);
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
    }

    public void hide() {
        handler.removeCallbacks(autoHideRunnable);
        setVisibility(GONE);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
