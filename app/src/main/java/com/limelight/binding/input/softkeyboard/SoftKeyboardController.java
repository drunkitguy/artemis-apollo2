package com.limelight.binding.input.softkeyboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A gamepad first on screen keyboard.
 *
 * The point of it is that you never have to take a thumb off the sticks: the
 * pad drives the focus ring and the face buttons do the work. Touch still
 * works, and mixing the two is fine because a tap moves the ring to the key it
 * hit before pressing it.
 *
 * The overlay deliberately does not take Android focus. The stream keeps
 * rendering and the session keeps running underneath; input is intercepted by
 * {@link Game} calling into {@link #handleKeyDown}, {@link #handleKeyUp} and
 * {@link #handleMotionEvent} before it routes anything to the host.
 */
public class SoftKeyboardController {

    /** How long a held stick waits before it starts repeating. */
    private static final long STICK_REPEAT_DELAY_MS = 400;
    /** How fast a held stick repeats once it has started. */
    private static final long STICK_REPEAT_INTERVAL_MS = 90;
    /** Past this the stick counts as pushed. Loose enough for worn sticks. */
    private static final float STICK_DEADZONE = 0.5f;
    /** Below this the stick has recentred and may fire again immediately. */
    private static final float STICK_RECENTRE = 0.3f;
    /** Triggers report 0..1; treat over half pull as a press. */
    private static final float TRIGGER_THRESHOLD = 0.5f;

    private final Game game;
    private final FrameLayout root;
    private final Context context;

    private SoftKeyboardModel model;
    private SoftKeyboardView view;
    private SoftKeyboardPresentation presentation;
    private DisplayManager.DisplayListener displayListener;
    private int presentationDisplayId = KeyboardDisplayChooser.NO_DISPLAY;
    private boolean shown;
    /** What the last attempt to show the keyboard actually did, for the report. */
    private String lastOutcome;

    /** Local mirror of what has been typed, purely so the user can see it. */
    private final StringBuilder echo = new StringBuilder();

    private SoftKeyboardModel.Direction heldDirection;
    private long nextRepeatAt;
    private boolean leftTriggerDown;
    private boolean rightTriggerDown;

    public SoftKeyboardController(Game game, FrameLayout root) {
        this.game = game;
        this.root = root;
        this.context = root.getContext();
    }

    // ------------------------------------------------------------- visibility

    public boolean isShown() {
        return shown;
    }

    public SoftKeyboardLayouts.Page getPage() {
        return model == null ? SoftKeyboardLayouts.Page.LETTERS : model.getPage();
    }

    /** Shows the letter keyboard, or hides it if that page is already up. */
    public void toggleKeyboard() {
        toggle(SoftKeyboardLayouts.Page.LETTERS);
    }

    /** Shows the numeric keypad, or hides it if it is already up. */
    public void toggleKeypad() {
        toggle(SoftKeyboardLayouts.Page.PIN);
    }

    private void toggle(SoftKeyboardLayouts.Page page) {
        if (shown && samePresentation(page)) {
            hide();
        } else {
            show(page);
        }
    }

    /**
     * The letter and symbol pages are the same overlay, so asking for letters
     * while the symbol page is up should close it rather than rebuild it.
     */
    private boolean samePresentation(SoftKeyboardLayouts.Page page) {
        boolean wantKeypad = page == SoftKeyboardLayouts.Page.PIN;
        boolean haveKeypad = getPage() == SoftKeyboardLayouts.Page.PIN;
        return wantKeypad == haveKeypad;
    }

    public void show(SoftKeyboardLayouts.Page page) {
        hide();

        model = new SoftKeyboardModel(page);
        view = new SoftKeyboardView(context, model);
        view.setOnKeyPressListener(new SoftKeyboardView.OnKeyPressListener() {
            @Override
            public void onKeyPress(int row, int column) {
                model.setFocus(row, column);
                pressFocusedKey();
            }
        });

        echo.setLength(0);
        applyHint();
        view.setEcho("");

        attach(view, page);
        watchDisplays();
        shown = true;
        heldDirection = null;
        leftTriggerDown = false;
        rightTriggerDown = false;
    }

    public void hide() {
        unwatchDisplays();
        detach();
        view = null;
        shown = false;
        heldDirection = null;
    }

    // ------------------------------------------------------ where it is shown

    /**
     * Puts the keyboard on a second screen when there is one, and falls back
     * to an overlay on the streaming screen when there is not.
     *
     * The fallback is not an error path. Most devices have one screen, and a
     * docked overlay is the right answer there; the second screen is a bonus
     * for handhelds that have one.
     */
    private void attach(SoftKeyboardView keyboard, SoftKeyboardLayouts.Page page) {
        Display target = chooseDisplay();
        if (target != null) {
            try {
                SoftKeyboardPresentation shownOn = new SoftKeyboardPresentation(
                        game, target, keyboard, page == SoftKeyboardLayouts.Page.PIN);
                shownOn.show();
                presentation = shownOn;
                presentationDisplayId = target.getDisplayId();
                lastOutcome = "shown on screen " + target.getDisplayId();
                LimeLog.info("Soft keyboard " + lastOutcome);
                return;
            } catch (WindowManager.InvalidDisplayException e) {
                // The screen went away between choosing it and showing on it.
                lastOutcome = "screen " + target.getDisplayId() + " refused the window ("
                        + e.getClass().getSimpleName() + "), fell back to an overlay";
                LimeLog.warning("Soft keyboard " + lastOutcome);
            } catch (RuntimeException e) {
                // A vendor screen that will not host a presentation is not a
                // reason to leave the user without a keyboard.
                lastOutcome = "screen " + target.getDisplayId() + " failed with "
                        + e.getClass().getSimpleName() + ", fell back to an overlay";
                LimeLog.warning("Soft keyboard " + lastOutcome + ": " + e.getMessage());
            }
            presentation = null;
            presentationDisplayId = KeyboardDisplayChooser.NO_DISPLAY;
        } else {
            lastOutcome = "docked over the stream (no separate second screen)";
            LimeLog.info("Soft keyboard " + lastOutcome);
        }

        root.addView(keyboard, layoutParamsFor(page));
    }

    /** A plain language account of what the keyboard did, for the screen report. */
    public String describe() {
        return SoftKeyboardDiagnostics.report(
                context, game.getStreamDisplayId(), prefersSecondScreen(), lastOutcome);
    }

    private void detach() {
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (RuntimeException ignored) {
                // Dismissing a presentation whose screen is already gone throws.
            }
            presentation = null;
            presentationDisplayId = KeyboardDisplayChooser.NO_DISPLAY;
        }
        if (view != null && view.getParent() == root) {
            root.removeView(view);
        }
    }

    /** @return the screen to use, or null to fall back to an overlay */
    private Display chooseDisplay() {
        if (!prefersSecondScreen()) {
            return null;
        }

        DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displays == null) {
            return null;
        }

        Display[] all = displays.getDisplays();
        if (all == null || all.length < 2) {
            return null;
        }

        List<KeyboardDisplayChooser.Candidate> candidates = new ArrayList<>(all.length);
        for (Display display : all) {
            Point size = sizeOf(display);
            candidates.add(new KeyboardDisplayChooser.Candidate(
                    display.getDisplayId(), size.x, size.y,
                    display.getState() != Display.STATE_OFF));
        }

        int chosen = KeyboardDisplayChooser.choose(candidates, game.getStreamDisplayId());
        return chosen == KeyboardDisplayChooser.NO_DISPLAY ? null : displays.getDisplay(chosen);
    }

    @SuppressWarnings("deprecation")
    private static Point sizeOf(Display display) {
        Point size = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = display.getMode();
            if (mode != null && mode.getPhysicalWidth() > 0) {
                size.set(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                return size;
            }
        }
        display.getRealSize(size);
        return size;
    }

    private boolean prefersSecondScreen() {
        return PreferenceConfiguration.readPreferences(context).softKeyboardOnSecondScreen;
    }

    /**
     * A second screen can be unplugged or folded away with the keyboard on it.
     * When that happens the keys are rebuilt as an overlay rather than
     * vanishing, because the user is mid sentence.
     */
    private void watchDisplays() {
        if (displayListener != null) {
            return;
        }
        final DisplayManager displays =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displays == null) {
            return;
        }

        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (shown && displayId == presentationDisplayId) {
                    rebuildForPage();
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {
            }
        };
        displays.registerDisplayListener(displayListener, null);
    }

    private void unwatchDisplays() {
        if (displayListener == null) {
            return;
        }
        DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displays != null) {
            displays.unregisterDisplayListener(displayListener);
        }
        displayListener = null;
    }

    /**
     * Letters and symbols dock along the bottom edge, out of the way of what
     * is being typed into. The keypad is its own screen: centred, boxed to a
     * thumb friendly width, with keys big enough to hit without looking.
     */
    private FrameLayout.LayoutParams layoutParamsFor(SoftKeyboardLayouts.Page page) {
        boolean keypad = page == SoftKeyboardLayouts.Page.PIN;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);

        if (keypad) {
            params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
            // Stretched to a handheld's full width the keys become absurdly
            // wide, so the pad is boxed rather than filled.
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            params.width = Math.min(Math.max(screenWidth * 3 / 5, dp(260f)), dp(420f));
        } else {
            params.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        }

        int margin = dp(10f);
        params.leftMargin = margin;
        params.rightMargin = margin;
        params.bottomMargin = keypad ? 0 : margin;
        return params;
    }

    private void applyHint() {
        view.setHint(context.getString(getPage() == SoftKeyboardLayouts.Page.PIN
                ? R.string.soft_keyboard_hint_keypad
                : R.string.soft_keyboard_hint_letters));
    }

    // ------------------------------------------------------------ gamepad in

    /**
     * @return true when the overlay consumed the event, in which case the host
     *         must not see it
     */
    public boolean handleKeyDown(KeyEvent event) {
        if (!shown) {
            return false;
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return moveAndRepaint(SoftKeyboardModel.Direction.UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return moveAndRepaint(SoftKeyboardModel.Direction.DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveAndRepaint(SoftKeyboardModel.Direction.LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveAndRepaint(SoftKeyboardModel.Direction.RIGHT);

            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                pressFocusedKey();
                return true;

            case KeyEvent.KEYCODE_BUTTON_B:
                sendKeyCode(KeyEvent.KEYCODE_DEL, false);
                trimEcho();
                return true;

            case KeyEvent.KEYCODE_BUTTON_X:
                sendKeyCode(KeyEvent.KEYCODE_SPACE, false);
                appendEcho(" ");
                return true;

            case KeyEvent.KEYCODE_BUTTON_Y:
                pressKey(findKeyWithAction(SoftKey.Action.SHIFT));
                return true;

            case KeyEvent.KEYCODE_BUTTON_L2:
            case KeyEvent.KEYCODE_BUTTON_R2:
                swapPage();
                return true;

            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R1:
                // The host never tells the client that the focused field only
                // takes digits, so reaching the keypad has to be something the
                // user does. A shoulder button is the cheapest way to do it
                // without going back out to the menu.
                toggleKeypadPage();
                return true;

            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BACK:
                hide();
                return true;

            default:
                // Everything else from a pad is swallowed while the keyboard is
                // up. Letting a stray bumper through would fire in the game
                // behind the overlay, which is exactly what the user is not
                // looking at.
                return isFromGamepad(event.getDevice());
        }
    }

    /** Key ups are swallowed to match whatever {@link #handleKeyDown} consumed. */
    public boolean handleKeyUp(KeyEvent event) {
        if (!shown) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return isFromGamepad(event.getDevice());
    }

    /** Sticks navigate, with a hold to repeat. Triggers swap the page. */
    public boolean handleMotionEvent(MotionEvent event) {
        if (!shown || !isFromGamepad(event.getDevice())) {
            return false;
        }

        float x = axis(event, MotionEvent.AXIS_X, MotionEvent.AXIS_HAT_X);
        float y = axis(event, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_Y);

        SoftKeyboardModel.Direction direction = null;
        if (Math.abs(x) > Math.abs(y)) {
            if (x > STICK_DEADZONE) {
                direction = SoftKeyboardModel.Direction.RIGHT;
            } else if (x < -STICK_DEADZONE) {
                direction = SoftKeyboardModel.Direction.LEFT;
            }
        } else {
            if (y > STICK_DEADZONE) {
                direction = SoftKeyboardModel.Direction.DOWN;
            } else if (y < -STICK_DEADZONE) {
                direction = SoftKeyboardModel.Direction.UP;
            }
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (direction == null) {
            if (Math.abs(x) < STICK_RECENTRE && Math.abs(y) < STICK_RECENTRE) {
                heldDirection = null;
            }
        } else if (direction != heldDirection) {
            heldDirection = direction;
            nextRepeatAt = now + STICK_REPEAT_DELAY_MS;
            moveAndRepaint(direction);
        } else if (now >= nextRepeatAt) {
            nextRepeatAt = now + STICK_REPEAT_INTERVAL_MS;
            moveAndRepaint(direction);
        }

        // Triggers arrive as axes on most pads and as buttons on a few, so both
        // paths are handled and edge detected to avoid a page swap per frame.
        boolean left = event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > TRIGGER_THRESHOLD
                || event.getAxisValue(MotionEvent.AXIS_BRAKE) > TRIGGER_THRESHOLD;
        boolean right = event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > TRIGGER_THRESHOLD
                || event.getAxisValue(MotionEvent.AXIS_GAS) > TRIGGER_THRESHOLD;
        if ((left && !leftTriggerDown) || (right && !rightTriggerDown)) {
            swapPage();
        }
        leftTriggerDown = left;
        rightTriggerDown = right;

        return true;
    }

    private static float axis(MotionEvent event, int primary, int fallback) {
        float value = event.getAxisValue(primary);
        if (Math.abs(value) < STICK_DEADZONE) {
            float hat = event.getAxisValue(fallback);
            if (Math.abs(hat) >= Math.abs(value)) {
                return hat;
            }
        }
        return value;
    }

    private static boolean isFromGamepad(InputDevice device) {
        if (device == null) {
            return false;
        }
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    // ------------------------------------------------------------ key presses

    private boolean moveAndRepaint(SoftKeyboardModel.Direction direction) {
        if (model.move(direction)) {
            view.refresh();
        }
        return true;
    }

    private void pressFocusedKey() {
        pressKey(model.getFocusedKey());
    }

    private void pressKey(SoftKey key) {
        if (key == null) {
            return;
        }

        SoftKeyboardModel.Press press = model.press(key);

        switch (press.key.action) {
            case CLOSE:
                hide();
                return;

            case CLIPBOARD:
                pasteClipboard();
                view.refresh();
                return;

            case PAGE:
                rebuildForPage();
                return;

            case SHIFT:
                view.refresh();
                return;

            case CHAR:
            default:
                break;
        }

        if (press.sends()) {
            sendKeyCode(press.keyCode, press.shift);
            recordEcho(press);
        }
        view.refresh();
    }

    /** Y and the trigger shortcuts need the key object, not a screen position. */
    private SoftKey findKeyWithAction(SoftKey.Action action) {
        for (java.util.List<SoftKey> row : model.getRows()) {
            for (SoftKey key : row) {
                if (key.action == action) {
                    return key;
                }
            }
        }
        return null;
    }

    /** Flips between the keypad and the letter keyboard, in place. */
    private void toggleKeypadPage() {
        model.setPage(model.getPage() == SoftKeyboardLayouts.Page.PIN
                ? SoftKeyboardLayouts.Page.LETTERS
                : SoftKeyboardLayouts.Page.PIN);
        rebuildForPage();
    }

    private void swapPage() {
        if (model.getPage() == SoftKeyboardLayouts.Page.PIN) {
            // The keypad is its own mode; a trigger should not drop the user
            // into a letter grid they did not ask for.
            return;
        }
        model.setPage(model.getPage() == SoftKeyboardLayouts.Page.LETTERS
                ? SoftKeyboardLayouts.Page.SYMBOLS
                : SoftKeyboardLayouts.Page.LETTERS);
        rebuildForPage();
    }

    /**
     * The pages have different key counts, so the view is rebuilt rather than
     * repainted. The echo and the scroll position survive; focus does not,
     * which {@link SoftKeyboardModel#setPage} already decided.
     */
    private void rebuildForPage() {
        detach();

        SoftKeyboardView rebuilt = new SoftKeyboardView(context, model);
        rebuilt.setOnKeyPressListener(new SoftKeyboardView.OnKeyPressListener() {
            @Override
            public void onKeyPress(int row, int column) {
                model.setFocus(row, column);
                pressFocusedKey();
            }
        });

        view = rebuilt;
        attach(view, model.getPage());
        applyHint();
        view.setEcho(echo.toString());
        view.refresh();
    }

    private void sendKeyCode(int androidKeyCode, boolean shift) {
        game.sendSoftKeyboardKey(androidKeyCode, shift);
    }

    private void pasteClipboard() {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(context);
        if (text == null || text.length() == 0) {
            return;
        }
        game.sendSoftKeyboardText(text.toString());
        appendEcho(text.toString());
    }

    // ------------------------------------------------------------------ echo

    private void recordEcho(SoftKeyboardModel.Press press) {
        switch (press.keyCode) {
            case KeyEvent.KEYCODE_DEL:
                trimEcho();
                return;
            case KeyEvent.KEYCODE_ENTER:
                echo.setLength(0);
                view.setEcho("");
                return;
            case KeyEvent.KEYCODE_SPACE:
                appendEcho(" ");
                return;
            default:
                break;
        }

        String face = press.key.face(press.shift);
        // Only single character faces are literal text. "Tab", "Esc" and the
        // arrows are actions, and echoing their names would be a lie.
        if (face.length() == 1) {
            appendEcho(face);
        }
    }

    private void appendEcho(String text) {
        echo.append(text);
        // The echo is one line; older text scrolling off is better than a
        // keyboard that grows and covers the game.
        if (echo.length() > 96) {
            echo.delete(0, echo.length() - 96);
        }
        if (view != null) {
            view.setEcho(echo.toString());
        }
    }

    private void trimEcho() {
        if (echo.length() > 0) {
            echo.setLength(echo.length() - 1);
        }
        if (view != null) {
            view.setEcho(echo.toString());
        }
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
