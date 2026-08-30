package com.limelight.ui;

import android.content.Context;
import android.os.Build;
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
 *
 * The requested input type held by the target view is the single source of truth. It is
 * what makes the view a text editor (see ExternalControllerView.onCreateInputConnection),
 * and it is what Game.handleCommitText tests before forwarding a character, so the two
 * must never disagree: a live forwarding InputConnection with a closed guard silently
 * eats everything the user types. Every place that clears the input type therefore calls
 * restartInput() in the same breath, so the connection dies with the guard.
 */
public class SoftKeyboardController {

    public enum Mode {
        TEXT,
        NUMBER,
        PASSWORD,
        /** A masked field the host has positive numeric evidence for: a PIN, a CVV, an OTP. */
        NUMBER_PASSWORD
    }

    /**
     * Implemented by the view the IME attaches to: the stream surface on a single screen,
     * the touch surface on the secondary display.
     */
    public interface ImeTarget {
        /** 0 means "no keyboard requested", otherwise an android.text.InputType class. */
        void setImeInputType(int inputType);

        /** The input type currently requested, or 0 when we are not holding the IME. */
        int getImeInputType();

        View asView();
    }

    private final Context context;
    private final ImeTarget target;

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
        if (mode == Mode.NUMBER_PASSWORD) {
            // A masked field that is numeric by construction. TYPE_NUMBER_VARIATION_PASSWORD
            // gets the numeric layout without the IME learning or suggesting the digits, which
            // is exactly what a PIN or a CVV wants. No SIGNED/DECIMAL flags: a PIN has neither
            // a sign nor a decimal point, and offering them only adds keys that do nothing.
            return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        if (mode == Mode.PASSWORD) {
            // A password field on the host still needs a normal alphabetic layout: hiding the
            // keyboard entirely would mean the user physically cannot log in. What matters is
            // that the IME does not learn the text, hence VISIBLE_PASSWORD (which suppresses
            // predictive text and clipboard learning) together with NO_SUGGESTIONS. The
            // characters are echoed locally only; nothing typed is ever read back from the
            // host, and the host itself masks the field.
            return InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    }

    public static int imeOptions() {
        return EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
    }

    /**
     * True exactly while the target is offering a forwarding InputConnection on our
     * behalf. Derived from the target rather than kept in a flag of our own so that it
     * cannot drift away from what the view is actually telling the IME.
     */
    public boolean isShown() {
        return target != null && target.getImeInputType() != 0;
    }

    public void show(Mode mode) {
        View view = target.asView();
        if (view == null) {
            return;
        }

        lastMode = mode;
        target.setImeInputType(typeFor(mode));

        // Left set afterwards: the stream surface is focusable for its own reasons and the
        // touch surface is focusedByDefault, so clearing it again on hide() would take
        // focus away from views that want it.
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
    }

    public void hide() {
        View view = target.asView();
        InputMethodManager inputManager = getInputMethodManager();

        if (view != null && inputManager != null) {
            inputManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        clearImeTarget();
    }

    /**
     * The manual keyboard action, and - since the on-screen ABC/123 chip was removed - the
     * user's only way to recover from a host verdict that asked for the wrong layout.
     *
     * So it is not a plain show/hide. When a keyboard is already up in a DIFFERENT layout,
     * this switches to the requested one in place (show() calls restartInput(), which makes
     * the IME re-read the input type without a hide/show cycle) instead of dismissing it.
     * Recovering from a wrongly-numeric field is then one gesture, not two: the numeric
     * layouts Android draws have no letters key at all, so "hide, then raise again" leaves
     * the user staring at a keyboard-less screen in between and costs them a second action
     * to get anywhere.
     *
     * It stays a toggle for the case that matters: asking for the layout that is already up
     * hides it.
     */
    public void toggle(Mode mode) {
        if (!isImeUp()) {
            show(mode);
            return;
        }
        if (lastMode != mode) {
            show(mode);
            return;
        }
        hide();
    }

    /**
     * Whether the IME is on screen right now.
     *
     * WindowInsetsCompat only carries real per-type visibility on API 30+; below that the
     * androidx fallback reports every type as visible, which would turn every toggle into
     * a hide. So the insets are only consulted on R+, and on older releases we fall back
     * to what the target is currently advertising. The secondary display is R+ by
     * construction (ExternalDisplayControlActivity needs setLaunchDisplayId), so the
     * fallback only ever applies to the single-screen path.
     */
    private boolean isImeUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            View view = target.asView();
            if (view != null) {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
                if (insets != null) {
                    onImeVisibilityChanged(insets.isVisible(WindowInsetsCompat.Type.ime()));
                }
            }
        }
        return isShown();
    }

    /**
     * Driven by the window insets listeners on both the stream window and the secondary
     * display, and by the on-demand read above.
     *
     * This has to be symmetric. Insets are re-dispatched for all sorts of reasons that
     * have nothing to do with the keyboard going away - the secondary display hands focus
     * back to the game activity on every analog stick sample, for one - so a visible IME
     * must re-arm the target rather than leave it cleared, or the IME keeps hold of a
     * forwarding connection whose guard has already closed and the user types into
     * nothing.
     */
    public void onImeVisibilityChanged(boolean visible) {
        View view = target.asView();

        if (!visible) {
            clearImeTarget();
            return;
        }

        // Only re-arm when the IME is serving us: if the focus is somewhere else, the
        // keyboard belongs to that view and its text is none of our business.
        if (view != null && view.hasFocus() && target.getImeInputType() == 0) {
            target.setImeInputType(typeFor(lastMode));
            InputMethodManager inputManager = getInputMethodManager();
            if (inputManager != null) {
                inputManager.restartInput(view);
            }
        }
    }

    /**
     * Stops advertising an editor and tells the IME immediately, so the forwarding
     * InputConnection never outlives the guard that feeds it.
     */
    private void clearImeTarget() {
        if (target.getImeInputType() == 0) {
            return;
        }

        target.setImeInputType(0);

        View view = target.asView();
        InputMethodManager inputManager = getInputMethodManager();
        if (view != null && inputManager != null) {
            inputManager.restartInput(view);
        }
    }

    private InputMethodManager getInputMethodManager() {
        return (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
    }
}
