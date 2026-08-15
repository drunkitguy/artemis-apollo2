package com.limelight.binding.input.softkeyboard;

import android.app.Presentation;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

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

    private View content;
    private boolean keypad;
    private FrameLayout container;

    public SoftKeyboardPresentation(Context outerContext, Display display,
                                    View content, boolean keypad) {
        super(outerContext, display);
        this.content = content;
        this.keypad = keypad;
    }

    /**
     * Replaces what the screen is showing without tearing the window down.
     *
     * Swapping between the resting screen and a keyboard happens often enough
     * that rebuilding the presentation each time would flash the panel.
     */
    public void swapContent(View newContent, boolean keypadLayout) {
        this.content = newContent;
        this.keypad = keypadLayout;
        if (container != null) {
            container.removeAllViews();
            fill();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getWindow() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }

        container = new FrameLayout(getContext());
        // Black rather than the app background: when nothing is being typed
        // this panel should read as off, not as a lit blank page next to the
        // game.
        container.setBackgroundColor(android.graphics.Color.BLACK);
        fill();
        setContentView(container);
    }

    private void fill() {
        boolean resting = content instanceof SoftKeyboardLauncherView;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                resting ? FrameLayout.LayoutParams.MATCH_PARENT
                        : FrameLayout.LayoutParams.WRAP_CONTENT);

        // The resting screen fills the panel so a tap anywhere is meaningful.
        // A keyboard gets the whole width but not the height: letters sit low,
        // where a phone keyboard lives and the thumbs already are, and the
        // keypad centres because it is the only thing on the screen.
        params.gravity = resting ? Gravity.CENTER
                : (keypad ? Gravity.CENTER : (Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));

        if (!resting) {
            int margin = Math.round(8f * getContext().getResources().getDisplayMetrics().density);
            params.leftMargin = margin;
            params.rightMargin = margin;
            params.topMargin = margin;
            params.bottomMargin = margin;
        }

        detachFromParent(content);
        container.addView(content, params);
    }

    /** A view can only live in one hierarchy, and it may have been an overlay first. */
    private static void detachFromParent(View view) {
        if (view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).removeView(view);
        }
    }
}
