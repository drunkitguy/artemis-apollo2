package com.limelight.focus;

/**
 * Decides what the second screen should do about a reported focus change.
 *
 * The reports are a hint, not a command. The host cannot see every kind of
 * field, so a stream of them is full of gaps: a browser text box usually
 * arrives as unknown, and losing focus for a moment while clicking between
 * controls should not slam the keyboard shut and reopen it.
 *
 * Pure Java, because these rules are entirely about timing and hysteresis and
 * are far easier to be sure of against a test.
 */
public final class FocusHintPolicy {

    /** What the panel should be showing. */
    public enum Action {
        /** Leave it as it is. */
        NOTHING,
        /** Open, or switch to, the letter keyboard. */
        SHOW_TEXT,
        /** Open, or switch to, the number pad. */
        SHOW_DIGITS,
        /** Put the panel back to the trackpad. */
        REST
    }

    /**
     * How long focus must be gone before the keyboard closes.
     *
     * Clicking from one field to another passes through a moment of nothing
     * focused. Without this the panel would flicker shut and open again on
     * every such click.
     */
    public static final long CLOSE_DELAY_MS = 900;

    private FocusHint.State current = FocusHint.State.NONE;
    private long noneSinceMs;
    private boolean keyboardOpen;
    private boolean digitsShowing;

    /** Tells the policy what the panel is doing, for when the user acts directly. */
    public void syncOpenState(boolean open, boolean digits) {
        this.keyboardOpen = open;
        this.digitsShowing = digits;
    }

    /**
     * @param state    what the host just reported
     * @param nowMs    monotonic clock
     * @return what the panel should do
     */
    public Action onHint(FocusHint.State state, long nowMs) {
        if (state == null) {
            return Action.NOTHING;
        }

        // Unknown means the host looked and could not tell, which is common in
        // browsers. Treating it as "no field" would close the keyboard while
        // the user is mid-sentence, so it changes nothing at all.
        if (state == FocusHint.State.UNKNOWN) {
            return Action.NOTHING;
        }

        if (state != FocusHint.State.NONE) {
            noneSinceMs = 0;
        }

        current = state;

        switch (state) {
            case DIGITS:
                if (keyboardOpen && digitsShowing) {
                    return Action.NOTHING;
                }
                keyboardOpen = true;
                digitsShowing = true;
                return Action.SHOW_DIGITS;

            case TEXT:
                if (keyboardOpen && !digitsShowing) {
                    return Action.NOTHING;
                }
                keyboardOpen = true;
                digitsShowing = false;
                return Action.SHOW_TEXT;

            case NONE:
            default:
                if (!keyboardOpen) {
                    return Action.NOTHING;
                }
                if (noneSinceMs == 0) {
                    // Start the clock rather than closing straight away.
                    noneSinceMs = nowMs;
                    return Action.NOTHING;
                }
                if (nowMs - noneSinceMs < CLOSE_DELAY_MS) {
                    return Action.NOTHING;
                }
                noneSinceMs = 0;
                keyboardOpen = false;
                digitsShowing = false;
                return Action.REST;
        }
    }

    /**
     * Called on a timer so a sustained absence of focus eventually closes the
     * keyboard even if the host stops reporting.
     */
    public Action onTick(long nowMs) {
        if (current != FocusHint.State.NONE || !keyboardOpen || noneSinceMs == 0) {
            return Action.NOTHING;
        }
        if (nowMs - noneSinceMs < CLOSE_DELAY_MS) {
            return Action.NOTHING;
        }
        noneSinceMs = 0;
        keyboardOpen = false;
        digitsShowing = false;
        return Action.REST;
    }

    public void reset() {
        current = FocusHint.State.NONE;
        noneSinceMs = 0;
        keyboardOpen = false;
        digitsShowing = false;
    }
}
