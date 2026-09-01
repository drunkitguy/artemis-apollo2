package com.limelight.focus;

/**
 * Decides which reported focus changes are worth handing to the keyboard.
 *
 * The reports are a hint, not a command. The host cannot see every kind of
 * field, so a stream of them is full of gaps: a browser text box usually
 * arrives as unknown, and losing focus for a moment while clicking between
 * controls should not slam the keyboard shut and reopen it.
 *
 * Pure Java, because these rules are entirely about timing and hysteresis and
 * are far easier to be sure of against a test.
 *
 * <p>What this class deliberately does NOT do is decide what the panel shows.
 * It used to, with its own SHOW_TEXT / SHOW_DIGITS / REST vocabulary, and that
 * was a second opinion about a question
 * {@code SoftKeyboardController.applyHostFieldFocus} and
 * {@code HostFieldFocus.pageFor} already answer - and answer better, since
 * they know about password masking, read-only fields, whether the user picked
 * the page by hand and whether the panel is the host's to take back. So this
 * is now a filter in front of that: it returns the report to deliver, or null
 * to stay quiet, and everything downstream of the delivery is somebody else's
 * decision.
 */
public final class FocusHintPolicy {

    /**
     * How long focus must be gone before the keyboard closes.
     *
     * Clicking from one field to another passes through a moment of nothing
     * focused. Without this the panel would flicker shut and open again on
     * every such click.
     */
    public static final long CLOSE_DELAY_MS = 900;

    /** What the host last said, before any of the hysteresis below. */
    private FocusHint.State current = FocusHint.State.NONE;
    private long noneSinceMs;
    /**
     * The last report actually handed on.
     *
     * Keyed on the report, never on what the panel is doing. The panel's own
     * state is the controller's business, and a copy of it here would drift the
     * moment the controller declined to act on something - which it does, on
     * purpose, whenever the user is mid-word or picked the page by hand.
     */
    private FocusHint.Report delivered;

    /**
     * @param report what the host just reported
     * @param nowMs  monotonic clock
     * @return the report to hand to the keyboard, or null to do nothing
     */
    public FocusHint.Report onHint(FocusHint.Report report, long nowMs) {
        if (report == null) {
            return null;
        }

        // Unknown means the host looked and could not tell, which is common in
        // browsers. Treating it as "no field" would close the keyboard while
        // the user is mid-sentence, so it changes nothing at all.
        if (report.state == FocusHint.State.UNKNOWN) {
            return null;
        }

        if (report.state != FocusHint.State.NONE) {
            noneSinceMs = 0;
            current = report.state;
            return deliver(report);
        }

        current = FocusHint.State.NONE;

        if (delivered == null || delivered.state == FocusHint.State.NONE) {
            // Nothing was ever raised on this host's account, so there is
            // nothing to take back and no reason to start a clock.
            return null;
        }
        if (noneSinceMs == 0) {
            // Start the clock rather than closing straight away.
            noneSinceMs = nowMs;
            return null;
        }
        if (nowMs - noneSinceMs < CLOSE_DELAY_MS) {
            return null;
        }
        noneSinceMs = 0;
        return deliver(report);
    }

    /**
     * Called on a timer so a sustained absence of focus eventually closes the
     * keyboard even if the host stops reporting.
     */
    public FocusHint.Report onTick(long nowMs) {
        if (current != FocusHint.State.NONE || noneSinceMs == 0) {
            return null;
        }
        if (delivered == null || delivered.state == FocusHint.State.NONE) {
            return null;
        }
        if (nowMs - noneSinceMs < CLOSE_DELAY_MS) {
            return null;
        }
        noneSinceMs = 0;
        return deliver(new FocusHint.Report(FocusHint.State.NONE, 0));
    }

    /**
     * Suppresses a repeat of what was already handed on.
     *
     * The host reports on focus changes and again on a slow keepalive, so the
     * same field arrives over and over. Delivering each one would be harmless -
     * {@code applyHostFieldFocus} is idempotent on the resolved page and mask -
     * but it would put a preference read on the path for nothing.
     */
    private FocusHint.Report deliver(FocusHint.Report report) {
        if (report.equals(delivered)) {
            return null;
        }
        delivered = report;
        return report;
    }

    public void reset() {
        current = FocusHint.State.NONE;
        noneSinceMs = 0;
        delivered = null;
    }
}
