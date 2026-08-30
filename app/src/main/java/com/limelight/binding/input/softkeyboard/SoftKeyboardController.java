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
    /**
     * How long both sticks must be held clicked to open the keyboard.
     *
     * Long enough that it cannot be reached by playing. Clicking both sticks
     * at once already takes a deliberate grip, and holding that still for half
     * a second is not something any game asks for.
     */
    private static final long CHORD_HOLD_MS = 500;
    /**
     * Grace for a keyboard that has just opened but has not been typed on.
     *
     * Shorter than the normal idle so that a keyboard opened by accident gives
     * the pad back almost immediately, rather than costing the player several
     * seconds of a game they were in the middle of.
     */
    private static final long IDLE_UNUSED_MS = 2500;
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

    /** Live readout for the second screen, ticked only while the panel rests. */
    private final com.limelight.metrics.StreamMetricsWindow metrics =
            new com.limelight.metrics.StreamMetricsWindow();
    private com.limelight.metrics.StreamMetricsBanner banner;
    private static final long METRICS_INTERVAL_MS = 1000;
    private final Runnable tickMetrics = new Runnable() {
        @Override
        public void run() {
            updateMetrics();
            if (banner != null) {
                idleHandler.postDelayed(this, METRICS_INTERVAL_MS);
            }
        }
    };

    /**
     * Key codes whose press the keyboard consumed.
     *
     * Without this, releasing the pad mid-press leaks the matching key up to
     * the game: Start hands the pad back on the way down, and the way up would
     * then land in whatever is being streamed and open its menu.
     */
    private final java.util.Set<Integer> consumedKeys = new java.util.HashSet<>();

    private boolean leftStickClicked;
    private boolean rightStickClicked;
    private final Runnable openOnChord = new Runnable() {
        @Override
        public void run() {
            if (!leftStickClicked || !rightStickClicked || !shown || capturing) {
                return;
            }
            if (hostOwnsPanel && view != null) {
                // The PC already put the right keyboard up and it is sitting
                // there resting. Re-opening the same page would rebuild it for
                // nothing; what the chord means in that state is "give me the
                // pad so I can type", which matters most on exactly the panels
                // this chord exists for, the ones that are not touchscreens.
                setCapturing(true);
                return;
            }
            openPage(preferredPage());
        }
    };
    /**
     * Cached because the chord is checked on every gamepad press while the
     * game is being played. Re-reading the whole preference set there would
     * put a file parse on the input path.
     */
    private boolean padShortcut = true;

    // ------------------------------------------------------- what the PC says

    /**
     * The page the PC last asked for, or null when it reports no typable field.
     *
     * Null means "go back to resting", never "hide the panel".
     */
    private SoftKeyboardLayouts.Page hostPage;
    /** True once any field focus report has arrived, i.e. this host speaks it. */
    private boolean hostVerdictSeen;
    /**
     * Set when the page currently on the panel was put there by the PC.
     *
     * The host may only take back what the host raised. A keyboard the user
     * opened by hand is never closed or re-paged out from under them.
     */
    private boolean hostOwnsPanel;
    /** The user overrode the page by hand during this typing session. */
    private boolean userChosePage;
    /**
     * A password field has focus, so the on screen echo shows bullets.
     *
     * This panel normally paints everything typed across the second screen so
     * the user can see what they sent without looking up at the TV. Pointed at
     * a password box that turns the handheld into a password display readable
     * by whoever else is in the room, which is why this flag exists.
     */
    private boolean maskEcho;
    /** Cached alongside {@link #padShortcut}. */
    private boolean autoLayoutFromHost = true;
    /** The last raw report, kept only so the screen report can explain itself. */
    private int hostKind = HostFieldFocus.KIND_NONE;
    private int hostFlags;
    /** What one masked character looks like on the panel. */
    private static final char ECHO_MASK_CHAR = '•';

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
        // Asking for a specific keyboard from the menu is an explicit override.
        // The PC does not get to take this panel back afterwards.
        hostOwnsPanel = false;
        userChosePage = true;

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
            // Nothing typed yet, so hand the pad back quickly if this turns out
            // to have been opened by accident.
            idleHandler.postDelayed(releaseOnIdle, IDLE_UNUSED_MS);
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
        stopMetrics();
        refreshCachedPreferences();
        idleHandler.removeCallbacks(openOnChord);
        leftStickClicked = false;
        rightStickClicked = false;
        idleHandler.removeCallbacks(releaseOnIdle);
        capturing = false;
        heldDirection = null;
        echo.setLength(0);
        // Back to the panel nobody has claimed: whatever the PC or the user
        // decided about this typing session is finished with.
        hostOwnsPanel = false;
        userChosePage = false;
        maskEcho = false;

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

    private void startMetrics(com.limelight.metrics.StreamMetricsBanner target) {
        stopMetrics();
        banner = target;
        metrics.reset();
        banner.setResolution(game.getStreamWidth(), game.getStreamHeight());
        idleHandler.post(tickMetrics);
    }

    private void stopMetrics() {
        idleHandler.removeCallbacks(tickMetrics);
        banner = null;
    }

    private void updateMetrics() {
        if (banner == null) {
            return;
        }

        com.limelight.binding.video.StreamCounters counters = game.getStreamCounters();
        if (counters == null) {
            return;
        }

        metrics.update(android.os.SystemClock.uptimeMillis(),
                counters.framesRendered, counters.decoderTimeMs);
        banner.setResolution(game.getStreamWidth(), game.getStreamHeight());
        banner.setRates(metrics.getFps(), metrics.getDecodeTimeTenthsMs());
    }




    private SoftKeyboardLauncherView newLauncher() {
        // Once the PC is telling us which field has focus, picking a keyboard
        // by hand is work the user should not have to do, so the two buttons
        // come off the panel and the trackpad gets the space. Against a host
        // that says nothing they stay exactly where they were.
        SoftKeyboardLauncherView launcher = new SoftKeyboardLauncherView(
                context, preferredPage(), padShortcutEnabled(), trackpadSensitivity(),
                autoLayoutFromHost && hostVerdictSeen);
        launcher.setOnPickListener(new SoftKeyboardLauncherView.OnPickListener() {
            @Override
            public void onPick(SoftKeyboardLayouts.Page page) {
                openPage(page);
            }
        });

        startMetrics(launcher.getBanner());

        launcher.getTrackpad().setListener(
                new com.limelight.binding.input.trackpad.SoftTrackpadView.Listener() {
            @Override
            public void onPointerMove(int dx, int dy) {
                game.sendSoftTrackpadMove(dx, dy);
            }

            @Override
            public void onScroll(int clicks) {
                game.sendSoftTrackpadScroll(clicks);
            }

            @Override
            public void onLeftClick() {
                game.sendSoftTrackpadClick(false);
            }

            @Override
            public void onRightClick() {
                game.sendSoftTrackpadClick(true);
            }
        });

        return launcher;
    }

    /**
     * Finger travel to pointer travel.
     *
     * The panel is small, so one to one would make crossing a 1080p screen an
     * unreasonable amount of swiping. Scaled by the ratio of the streamed
     * width to the panel width, so a swipe across the pad is roughly a swipe
     * across the picture whatever size the second screen turns out to be.
     */
    private float trackpadSensitivity() {
        int panelWidth = context.getResources().getDisplayMetrics().widthPixels;
        if (presentationDisplayId != KeyboardDisplayChooser.NO_DISPLAY) {
            DisplayManager displays =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displays != null) {
                Display panel = displays.getDisplay(presentationDisplayId);
                if (panel != null) {
                    Point size = sizeOf(panel);
                    if (size.x > 0) {
                        panelWidth = size.x;
                    }
                }
            }
        }

        int streamWidth = PreferenceConfiguration.readPreferences(context).width;
        if (panelWidth <= 0 || streamWidth <= 0) {
            return 1.5f;
        }

        // Clamped so an unusually shaped panel cannot produce a pointer that is
        // either unusable or impossible to aim.
        float ratio = (float) streamWidth / (float) panelWidth;
        return Math.max(0.8f, Math.min(3.0f, ratio));
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
        PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(context);
        padShortcut = prefs.softKeyboardPadShortcut;
        autoLayoutFromHost = prefs.softKeyboardHostLayout;
    }

    /**
     * Which keyboard to open when the user has not said which one they want.
     *
     * The PC knows the answer whenever it is reporting a field, and it is a
     * better answer than "whatever you used last time" because it is about the
     * box the cursor is actually in. Falls straight back to the remembered page
     * against a host that does not report anything.
     */
    private SoftKeyboardLayouts.Page preferredPage() {
        if (autoLayoutFromHost && hostVerdictSeen && hostPage != null) {
            return hostPage;
        }
        return lastUsedPage();
    }

    /** Brings up one of the keyboards on a screen that is currently resting. */
    private void openPage(SoftKeyboardLayouts.Page page) {
        // Every route into here is the user asking by hand, so the PC no longer
        // owns this panel and may not take it away again.
        hostOwnsPanel = false;
        // The keyboard replaces the panel, so there is no banner to feed.
        stopMetrics();
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

    // ---------------------------------------------- the PC picks the keyboard

    /**
     * Puts a keyboard on the panel because the PC says a field has focus.
     *
     * The one difference from {@link #openPage} is the one that matters: this
     * does not take the gamepad. Windows moving focus is not a reason to stop
     * the game receiving input, and a keyboard that appears without stealing
     * the pad costs the player nothing if the PC guessed wrong. Touching any
     * key still hands the pad over through the usual listener, so "tap a key to
     * start typing" works exactly as it did.
     *
     * A page change while the user is already typing keeps the pad, because the
     * keyboard already had it. That is not the host taking the pad; it is the
     * host declining to drop it in the middle of a word.
     */
    private void openPageResting(SoftKeyboardLayouts.Page page) {
        boolean keepPad = capturing;

        stopMetrics();
        rememberPage(page);

        model = new SoftKeyboardModel(page);
        view = buildView();
        presentation.swapContent(view, page == SoftKeyboardLayouts.Page.PIN);

        // Whatever is in the echo belongs to the field that just lost focus.
        echo.setLength(0);
        view.setEcho("");
        capturing = keepPad;
        applyHint();
        view.refresh();
        lastOutcome = (keepPad ? "typing on screen " : "waiting on screen ")
                + presentationDisplayId + " (the PC asked for this keyboard)";
    }

    /**
     * A report from the PC about the field that has focus.
     *
     * Absolute state, not an edge: the most recent call is always the current
     * truth, so there is nothing to reconcile and nothing to miss if one is
     * dropped. Called on the UI thread.
     *
     * @param kind  one of {@code MoonBridge.TEXT_FIELD_*}
     * @param flags a mask of {@code MoonBridge.TEXT_FIELD_FLAG_*}
     */
    public void applyHostFieldFocus(byte kind, byte flags) {
        // Arrives as a signed byte off the wire. Widen before anything looks at
        // it, or a future host setting the top flag bit turns flags negative.
        int kindValue = kind & 0xFF;
        int flagValue = flags & 0xFF;

        refreshCachedPreferences();
        if (!autoLayoutFromHost) {
            // Turned off means off: no auto raise, no page pre-selection, and
            // the ABC/123 buttons stay on the panel. Identical to not having
            // this feature at all.
            return;
        }

        hostVerdictSeen = true;
        hostKind = kindValue;
        hostFlags = flagValue;

        SoftKeyboardLayouts.Page want = HostFieldFocus.pageFor(kindValue, flagValue);
        boolean wantMask = HostFieldFocus.masksEcho(kindValue, flagValue);

        // Keyed on what would actually be done, not on the raw bytes. Tabbing
        // between two text fields that differ only in, say, the multiline flag
        // must not rebuild anything.
        if (want == hostPage && wantMask == maskEcho) {
            return;
        }
        hostPage = want;
        maskEcho = wantMask;
        if (wantMask) {
            // Focus has moved into a masked box. Clear the panel now rather
            // than at whatever point the page happens to get rebuilt, so this
            // holds even on the paths below that deliberately do nothing.
            applyEchoMask();
        }

        if (presentation == null) {
            // Docked over the game, resting means gone, so raising a keyboard
            // here would drop a panel across the picture every time the PC's
            // focus moved. The verdict is still remembered, and the next manual
            // open gets the right page out of it.
            return;
        }

        if (capturing && (userChosePage || !hostOwnsPanel)) {
            // Somebody is typing on a keyboard the PC did not put there, or on
            // one whose page they picked by hand. Leave them alone.
            return;
        }

        if (want == null) {
            // Nothing has focus any more. Take back only what was raised here,
            // and never in the middle of a word.
            if (hostOwnsPanel && !capturing) {
                hostOwnsPanel = false;
                rest();
            }
            return;
        }

        if (shown && hostOwnsPanel && getPage() == want) {
            // Right keyboard already up: only the masking can have changed.
            applyEchoMask();
            return;
        }

        openPageResting(want);
        hostOwnsPanel = true;
    }

    /**
     * Forgets everything the previous session's PC said.
     *
     * Called as a new connection is started, because the next host may be a
     * different machine, or the same one with the setting turned off, and a
     * stale verdict would decide which keyboard opens and whether the ABC/123
     * buttons are on the panel.
     */
    public void resetHostFieldState() {
        hostPage = null;
        hostVerdictSeen = false;
        hostOwnsPanel = false;
        userChosePage = false;
        maskEcho = false;
        hostKind = HostFieldFocus.KIND_NONE;
        hostFlags = 0;
    }

    /**
     * The echo belongs to the field that had focus, so a change of field throws
     * it away. In the direction that matters, plain text typed into an ordinary
     * box must not still be sitting on the panel once a password box has focus.
     */
    private void applyEchoMask() {
        echo.setLength(0);
        if (view != null) {
            view.setEcho("");
        }
    }

    /** One line about the PC's last word on the subject, for the screen report. */
    private String hostVerdict() {
        if (!autoLayoutFromHost) {
            return "ignored, letting the PC pick the keyboard is turned off";
        }
        if (!hostVerdictSeen) {
            return "nothing, this host does not report which field has focus";
        }
        return HostFieldFocus.describe(hostKind, hostFlags);
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
        if (!capturing) {
            return;
        }
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
        stopMetrics();
        idleHandler.removeCallbacks(releaseOnIdle);
        capturing = false;
        unwatchDisplays();
        detach();
        view = null;
        shown = false;
        heldDirection = null;
        hostOwnsPanel = false;
        userChosePage = false;
        maskEcho = false;
        echo.setLength(0);
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
        // Not on the input path, so the cache can be brought up to date first
        // rather than reporting a preference the user has since changed.
        refreshCachedPreferences();
        return SoftKeyboardDiagnostics.report(
                context, game.getStreamDisplayId(), prefersSecondScreen(), lastOutcome,
                hostVerdict());
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
        if (shown && !capturing) {
            // Watched, never consumed. While the panel is resting every button
            // belongs to the game, including the two this chord is made of.
            trackOpenChord(event, true);
            return false;
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
                // The PC now tells us when the focused field only takes digits,
                // but it is guessing from what the application publishes and
                // plenty of applications publish nothing useful. This is the
                // override, and it is the cheapest one to reach: a shoulder
                // button, without going back out to the menu. Once it is used
                // the PC stops being allowed to change the page underneath.
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
     * Opens the keyboard when both sticks are held clicked together.
     *
     * This exists because the second screen on a handheld may not be a
     * touchscreen, in which case the buttons on the resting panel cannot be
     * pressed and there is no way to start typing short of the menu.
     *
     * It must not be reachable by playing. An earlier version used both
     * bumpers pressed within a window, which was wrong: L1 then R1 in quick
     * succession is ordinary in plenty of games, and because opening the
     * keyboard also takes the pad, a false trigger cost several seconds of
     * control in the middle of whatever was being played. Both sticks clicked
     * and held is a grip nothing asks for by accident.
     *
     * Nothing here consumes the event. The game receives both stick clicks
     * whether or not the chord completes.
     */
    private void trackOpenChord(KeyEvent event, boolean down) {
        if (!padShortcutEnabled()) {
            return;
        }

        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                leftStickClicked = down;
                break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                rightStickClicked = down;
                break;
            default:
                return;
        }

        idleHandler.removeCallbacks(openOnChord);
        if (leftStickClicked && rightStickClicked) {
            idleHandler.postDelayed(openOnChord, CHORD_HOLD_MS);
        }
    }

    /** Key ups are swallowed to match whatever {@link #handleKeyDown} consumed. */
    public boolean handleKeyUp(KeyEvent event) {
        // Checked before the capture gate: a key whose press we took must have
        // its release taken too, even if we have since handed the pad back.
        if (consumedKeys.remove(event.getKeyCode())) {
            return true;
        }
        if (shown && !capturing) {
            // Releasing either stick cancels a chord in progress.
            trackOpenChord(event, false);
            return false;
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
        // Something was actually typed, so this is a real session rather than
        // an accidental open: allow the longer pause before giving the pad back.
        touchIdleTimer();
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
        // A page picked by hand outranks the PC for the rest of this session.
        userChosePage = true;
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
        // Same as the keypad flip: a page reached by hand is the user's choice,
        // and the PC does not get to move it back.
        userChosePage = true;
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

    /**
     * Every path that puts a character on the panel goes through
     * {@link #appendEcho}, which is where password masking happens. Deleting
     * and clearing need no special case: a bullet trims like any other
     * character, and Enter wipes the line either way.
     */
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
        if (text == null || text.isEmpty()) {
            return;
        }

        if (maskEcho) {
            // The PC says this is a password box. Something still has to appear
            // or the panel looks like the keys are not reaching the PC at all,
            // but it appears as bullets: a handheld's second screen is at
            // reading distance for everyone else in the room, and the whole
            // point of this echo is that it is easy to read.
            //
            // Spaces and pasted clipboard text are masked the same way. A space
            // shown as a space would give away where the words break.
            for (int i = 0; i < text.length(); i++) {
                echo.append(ECHO_MASK_CHAR);
            }
        } else {
            echo.append(text);
        }
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
