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
    /** Quiet for this long and the panel goes back to black on its own. */
    private static final long IDLE_RELEASE_MS = 6000;
    /** Both bumpers within this window counts as the open-the-keyboard chord. */
    private static final long CHORD_WINDOW_MS = 400;
    /** Where the last used page is remembered between sessions. */
    private static final String STATE_PREFS = "soft_keyboard_state";
    private static final String LAST_PAGE_KEY = "last_page_is_keypad";

    private final Game game;
    private final FrameLayout root;
    private final Context context;

    private SoftKeyboardModel model;
    private SoftKeyboardView view;
    private SoftKeyboardPresentation presentation;
    private DisplayManager.DisplayListener displayListener;
    private int presentationDisplayId = KeyboardDisplayChooser.NO_DISPLAY;
    private boolean shown;
    /**
     * True only while the keyboard owns the gamepad.
     *
     * This is separate from {@link #shown} on purpose. A keyboard that is
     * permanently visible on a second screen must not permanently swallow the
     * pad, or the game gets no input at all. Visible but not capturing is the
     * resting state: touch still types, and the pad still plays the game.
     */
    private boolean capturing;
    /** What the last attempt to show the keyboard actually did, for the report. */
    private String lastOutcome;
    private final android.os.Handler idleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable releaseOnIdle = new Runnable() {
        @Override
        public void run() {
            setCapturing(false);
        }
    };

    /** Local mirror of what has been typed, purely so the user can see it. */
    private final StringBuilder echo = new StringBuilder();

    /**
     * Key codes whose press the keyboard consumed.
     *
     * Without this, releasing the pad mid-press leaks the matching key up to
     * the game: Start hands the pad back on the way down, and the way up would
     * then land in whatever is being streamed and open its menu.
     */
    private final java.util.Set<Integer> consumedKeys = new java.util.HashSet<>();

    private long leftBumperAt;
    private long rightBumperAt;
    /**
     * Cached because the chord is checked on every gamepad press while the
     * game is being played. Re-reading the whole preference set there would
     * put a file parse on the input path.
     */
    private boolean padShortcut = true;

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
        if (presentation != null) {
            // The second screen is already claimed. Asking for the page that is
            // already being typed on means "I am done"; anything else means
            // "switch to this one".
            if (capturing && samePresentation(page)) {
                setCapturing(false);
            } else {
                openPage(page);
            }
            return;
        }

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

    /**
     * Takes or releases the gamepad.
     *
     * Entered by touching a key, or by opening the keyboard from the menu.
     * Left by Start, the on-screen close key, or going quiet for a while, so
     * the pad returns to the game without the user having to think about it.
     */
    public void setCapturing(boolean capture) {
        idleHandler.removeCallbacks(releaseOnIdle);

        if (capturing == capture) {
            if (capture) {
                idleHandler.postDelayed(releaseOnIdle, IDLE_RELEASE_MS);
            }
            return;
        }

        capturing = capture;
        heldDirection = null;
        // Any still-held key keeps its entry so the release is swallowed.

        if (capture) {
            if (view != null) {
                view.setHint(context.getString(hintFor(getPage(), true)));
            }
            idleHandler.postDelayed(releaseOnIdle, IDLE_RELEASE_MS);
        } else {
            // Done typing means the keys go away, not that they sit there
            // faded. On a second screen that leaves a dark panel; over the
            // stream it leaves nothing at all.
            rest();
        }
    }

    /**
     * Puts the second screen back to black with the two choices on it, or
     * takes the overlay away entirely when there is no second screen.
     */
    private void rest() {
        refreshCachedPreferences();
        idleHandler.removeCallbacks(releaseOnIdle);
        capturing = false;
        heldDirection = null;
        echo.setLength(0);

        if (presentation == null) {
            // Docked over the game: resting has to mean gone, because a black
            // panel across the bottom of the stream is worse than no keyboard.
            hide();
            return;
        }

        view = null;
        presentation.swapContent(newLauncher(), false);
        lastOutcome = "resting on screen " + presentationDisplayId;
    }

    private SoftKeyboardLauncherView newLauncher() {
        SoftKeyboardLauncherView launcher =
                new SoftKeyboardLauncherView(context, lastUsedPage(), padShortcutEnabled());
        launcher.setOnPickListener(new SoftKeyboardLauncherView.OnPickListener() {
            @Override
            public void onPick(SoftKeyboardLayouts.Page page) {
                openPage(page);
            }
        });
        return launcher;
    }

    /**
     * Which keyboard to reopen when the user just wants the one from last time.
     *
     * Kept across sessions because the answer is a property of what the person
     * does with their PC, not of this particular stream.
     */
    private SoftKeyboardLayouts.Page lastUsedPage() {
        boolean keypad = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(LAST_PAGE_KEY, false);
        return keypad ? SoftKeyboardLayouts.Page.PIN : SoftKeyboardLayouts.Page.LETTERS;
    }

    private void rememberPage(SoftKeyboardLayouts.Page page) {
        if (page == SoftKeyboardLayouts.Page.SYMBOLS) {
            // The symbol page is a detour off the letters page, not a choice
            // anyone makes from cold.
            return;
        }
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(LAST_PAGE_KEY, page == SoftKeyboardLayouts.Page.PIN)
                .apply();
    }

    private boolean padShortcutEnabled() {
        return padShortcut;
    }

    /** Re-read the preferences that are consulted on the input path. */
    private void refreshCachedPreferences() {
        padShortcut = PreferenceConfiguration.readPreferences(context).softKeyboardPadShortcut;
    }

    /** Brings up one of the keyboards on a screen that is currently resting. */
    private void openPage(SoftKeyboardLayouts.Page page) {
        rememberPage(page);
        if (presentation == null) {
            show(page);
            return;
        }

        model = new SoftKeyboardModel(page);
        view = buildView();
        presentation.swapContent(view, page == SoftKeyboardLayouts.Page.PIN);

        echo.setLength(0);
        view.setEcho("");
        capturing = false;
        setCapturing(true);
        applyHint();
        view.refresh();
        lastOutcome = "typing on screen " + presentationDisplayId;
    }

    private SoftKeyboardView buildView() {
        SoftKeyboardView built = new SoftKeyboardView(context, model);
        built.setOnKeyPressListener(new SoftKeyboardView.OnKeyPressListener() {
            @Override
            public void onKeyPress(int row, int column) {
                setCapturing(true);
                model.setFocus(row, column);
                pressFocusedKey();
            }
        });
        return built;
    }

    public boolean isCapturing() {
        return capturing;
    }

    /** Any activity postpones handing the pad back. */
    private void touchIdleTimer() {
        idleHandler.removeCallbacks(releaseOnIdle);
        idleHandler.postDelayed(releaseOnIdle, IDLE_RELEASE_MS);
    }

    /**
     * Opens the keyboard on stream start when the user has asked for it and
     * there is a screen to put it on that is not the one being streamed to.
     *
     * It comes up resting: visible, dimmed, not holding the pad. Nothing about
     * the game changes until a key is actually touched.
     */
    public void showAutomaticallyIfConfigured() {
        if (shown || !prefersSecondScreen() || !autoShowEnabled()) {
            return;
        }
        Display target = chooseDisplay();
        if (target == null) {
            // With no second screen this would dock over the game uninvited.
            return;
        }

        refreshCachedPreferences();

        // Claim the screen but put nothing on it. The panel stays black until
        // the user says they want to type, and says which kind.
        try {
            SoftKeyboardPresentation shownOn =
                    new SoftKeyboardPresentation(game, target, newLauncher(), false);
            shownOn.show();
            presentation = shownOn;
            presentationDisplayId = target.getDisplayId();
            shown = true;
            capturing = false;
            watchDisplays();
            lastOutcome = "resting on screen " + target.getDisplayId();
            LimeLog.info("Soft keyboard " + lastOutcome);
        } catch (RuntimeException e) {
            presentation = null;
            presentationDisplayId = KeyboardDisplayChooser.NO_DISPLAY;
            lastOutcome = "could not claim a second screen: " + e.getClass().getSimpleName();
            LimeLog.warning("Soft keyboard " + lastOutcome);
        }
    }

    public void show(SoftKeyboardLayouts.Page page) {
        hide();
        refreshCachedPreferences();

        model = new SoftKeyboardModel(page);
        view = new SoftKeyboardView(context, model);
        view.setOnKeyPressListener(new SoftKeyboardView.OnKeyPressListener() {
            @Override
            public void onKeyPress(int row, int column) {
                // A finger on a key means the user is typing, so the pad comes
                // over too rather than making them ask for it separately.
                setCapturing(true);
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
        capturing = false;
        setCapturing(true);
        heldDirection = null;
        leftTriggerDown = false;
        rightTriggerDown = false;
    }

    public void hide() {
        idleHandler.removeCallbacks(releaseOnIdle);
        capturing = false;
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
        view.setHint(context.getString(hintFor(getPage(), capturing)));
    }

    private static int hintFor(SoftKeyboardLayouts.Page page, boolean capturing) {
        if (!capturing) {
            return R.string.soft_keyboard_hint_resting;
        }
        return page == SoftKeyboardLayouts.Page.PIN
                ? R.string.soft_keyboard_hint_keypad
                : R.string.soft_keyboard_hint_letters;
    }

    private boolean autoShowEnabled() {
        return PreferenceConfiguration.readPreferences(context).softKeyboardAutoShow;
    }

    // ------------------------------------------------------------ gamepad in

    /**
     * @return true when the overlay consumed the event, in which case the host
     *         must not see it
     */
    public boolean handleKeyDown(KeyEvent event) {
        if (shown && !capturing && noticeBumperChord(event)) {
            // Deliberately falls through rather than returning: the game still
            // gets both bumpers. Opening the keyboard costs the user nothing
            // they did not already spend, and a panel that lit up by accident
            // goes back to black on its own a few seconds later.
            openPage(lastUsedPage());
        }

        if (!shown || !capturing) {
            return false;
        }
        touchIdleTimer();
        consumedKeys.add(event.getKeyCode());

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
                // Done typing. The keyboard stays on its own screen; only the
                // pad goes back to the game, which is what "done" means when
                // the keys are not covering anything.
                setCapturing(false);
                return true;

            default:
                // Everything else from a pad is swallowed while the keyboard is
                // up. Letting a stray bumper through would fire in the game
                // behind the overlay, which is exactly what the user is not
                // looking at.
                return isFromGamepad(event.getDevice());
        }
    }

    /**
     * Both bumpers within a short window, while the panel is resting.
     *
     * This exists because the second screen on a handheld may not be a
     * touchscreen at all, in which case the buttons on the resting panel are
     * unpressable and there would be no way to start typing without going back
     * out to the menu. Both bumpers together is rare as a deliberate game
     * action, and because the presses are still forwarded, a false positive
     * costs a panel that lights up rather than an input the game never saw.
     *
     * @return true when this press completed the chord
     */
    private boolean noticeBumperChord(KeyEvent event) {
        if (!padShortcutEnabled()) {
            return false;
        }

        long now = android.os.SystemClock.uptimeMillis();
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_L1:
                leftBumperAt = now;
                break;
            case KeyEvent.KEYCODE_BUTTON_R1:
                rightBumperAt = now;
                break;
            default:
                return false;
        }

        if (leftBumperAt == 0 || rightBumperAt == 0) {
            return false;
        }
        if (Math.abs(leftBumperAt - rightBumperAt) > CHORD_WINDOW_MS) {
            return false;
        }

        // Spend the chord so holding both does not reopen it every repeat.
        leftBumperAt = 0;
        rightBumperAt = 0;
        return true;
    }

    /** Key ups are swallowed to match whatever {@link #handleKeyDown} consumed. */
    public boolean handleKeyUp(KeyEvent event) {
        // Checked before the capture gate: a key whose press we took must have
        // its release taken too, even if we have since handed the pad back.
        if (consumedKeys.remove(event.getKeyCode())) {
            return true;
        }
        if (!shown || !capturing) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return isFromGamepad(event.getDevice());
    }

    /** Sticks navigate, with a hold to repeat. Triggers swap the page. */
    public boolean handleMotionEvent(MotionEvent event) {
        if (!shown || !capturing || !isFromGamepad(event.getDevice())) {
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
                if (presentation != null) {
                    // On its own screen there is nothing to get out of the way
                    // of, so closing just means giving the pad back.
                    setCapturing(false);
                } else {
                    hide();
                }
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
        SoftKeyboardView rebuilt = buildView();

        if (presentation != null) {
            // Swap inside the existing window. Tearing the presentation down to
            // change page would blank the second screen on every L2 press.
            view = rebuilt;
            presentation.swapContent(view, model.getPage() == SoftKeyboardLayouts.Page.PIN);
        } else {
            detach();
            view = rebuilt;
            attach(view, model.getPage());
        }

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
