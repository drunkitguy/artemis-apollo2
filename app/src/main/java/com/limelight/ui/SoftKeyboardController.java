package com.limelight.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Drives the Android system IME (the built-in full on-screen keyboard) on behalf of a
 * streaming surface. Nothing is drawn here: we only ask the platform for a text or a
 * numeric layout and let the existing InputConnection / onKeyPreIme plumbing forward
 * whatever the user types to the host.
 */
public class SoftKeyboardController {

    public enum Mode {
        TEXT,
        NUMBER
    }

    /**
     * Implemented by the view the IME attaches to: the stream surface on a single screen,
     * the touch surface on the secondary display.
     */
    public interface ImeTarget {
        /** 0 means "no keyboard requested", otherwise an android.text.InputType class. */
        void setImeInputType(int inputType);

        View asView();
    }

    private final Context context;
    private final ImeTarget target;

    private boolean shown = false;
    private Mode lastMode = Mode.TEXT;

    public SoftKeyboardController(Context context, ImeTarget target) {
        this.context = context;
        this.target = target;
    }

    /**
     * The IME decides what it draws for a given input type. TYPE_CLASS_NUMBER asks for a
     * numeric layout, which is the closest thing to a numpad the platform offers.
     */
    public static int typeFor(Mode mode) {
        if (mode == Mode.NUMBER) {
            return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    public static int imeOptions() {
        return EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
    }

    public boolean isShown() {
        return shown;
    }

    public Mode getLastMode() {
        return lastMode;
    }

    public void show(Mode mode) {
        View view = target.asView();
        if (view == null) {
            return;
        }

        lastMode = mode;
        target.setImeInputType(typeFor(mode));

        view.setFocusableInTouchMode(true);
        view.requestFocus();

        InputMethodManager inputManager = getInputMethodManager();
        if (inputManager == null) {
            return;
        }

        // restartInput() makes the IME pick up the new input type even when it is already
        // up, so switching between TEXT and NUMBER swaps the layout without a hide/show.
        inputManager.restartInput(view);
        inputManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        shown = true;
    }

    public void hide() {
        View view = target.asView();

        shown = false;
        target.setImeInputType(0);

        if (view == null) {
            return;
        }

        InputMethodManager inputManager = getInputMethodManager();
        if (inputManager != null) {
            inputManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
            inputManager.restartInput(view);
        }
    }

    public void toggle(Mode mode) {
        if (isImeVisible()) {
            hide();
        } else {
            show(mode);
        }
    }

    /**
     * The IME can go away without telling us, and only the secondary display has an insets
     * listener to notice. Reading the root insets on demand keeps the toggle in phase with
     * what is actually on screen everywhere else, without installing a second listener on
     * the streaming surface.
     */
    private boolean isImeVisible() {
        View view = target.asView();
        if (view != null) {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
            if (insets != null) {
                onImeVisibilityChanged(insets.isVisible(WindowInsetsCompat.Type.ime()));
            }
        }
        return shown;
    }

    /**
     * Driven by the window insets listener. The IME can go away without us asking (back
     * press, the IME's own hide key), and we must drop back to the trackpad state then.
     */
    public void onImeVisibilityChanged(boolean visible) {
        if (!visible && shown) {
            shown = false;
            target.setImeInputType(0);
        }
    }

    private InputMethodManager getInputMethodManager() {
        return (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
    }
}
