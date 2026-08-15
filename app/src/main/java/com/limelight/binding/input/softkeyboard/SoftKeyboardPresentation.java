package com.limelight.binding.input.softkeyboard;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * Hosts the keyboard on a second screen.
 *
 * On a dual screen handheld this is the whole point: the game keeps the main
 * panel and the keys get the small one, so nothing is covered and the keys are
 * where the thumbs already are.
 *
 * The window is explicitly not focusable. That is what keeps the gamepad
 * working: key events stay with the streaming activity on the main display,
 * where the controller already intercepts them, while touches on this screen
 * still land on the keys. A focusable presentation would pull key input off
 * the activity and the pad would stop driving the focus ring.
 */
public class SoftKeyboardPresentation extends Presentation {

    private final SoftKeyboardView keyboard;
    private final boolean keypad;

    public SoftKeyboardPresentation(Context outerContext, Display display,
                                    SoftKeyboardView keyboard, boolean keypad) {
        super(outerContext, display);
        this.keyboard = keyboard;
        this.keypad = keypad;
    }

    public SoftKeyboardView getKeyboard() {
        return keyboard;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }

        FrameLayout container = new FrameLayout(getContext());
        container.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.vl_background));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        // A second screen is the keyboard's own screen, so it gets the whole
        // width rather than being docked the way an overlay is. Letters still
        // sit low, where a phone keyboard lives and the thumbs already are;
        // the keypad centres, because it is the only thing on the screen.
        params.gravity = keypad ? Gravity.CENTER : (Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);

        int margin = Math.round(8f * getContext().getResources().getDisplayMetrics().density);
        params.leftMargin = margin;
        params.rightMargin = margin;
        params.topMargin = margin;
        params.bottomMargin = margin;

        detachFromParent(keyboard);
        container.addView(keyboard, params);
        setContentView(container);
    }

    /** A view can only live in one hierarchy, and it may have been an overlay first. */
    private static void detachFromParent(View view) {
        if (view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).removeView(view);
        }
    }
}
