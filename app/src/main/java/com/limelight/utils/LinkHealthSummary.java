package com.limelight.utils;

import android.content.Context;

import com.limelight.R;

/**
 * Turns the frame loss the client already measures into something a user can
 * read and act on.
 *
 * <h3>Why this exists</h3>
 * On the first real hardware capture, <b>50.5% of the video frames the host
 * produced never arrived</b> — 16,176 produced, 8,003 delivered — and in the
 * worst stretches it was 87%. The client knew: {@code VideoStats.framesLost}
 * had been counting the whole time. It went into a performance overlay that was
 * off, and into a crash report string that is only built when the decoder
 * crashes. The user's entire signal was that it "felt laggy".
 *
 * <p>The existing {@code CONN_STATUS_POOR} overlay does fire, but it says
 * "poor connection" with no number attached, so 6% loss and 87% loss look
 * identical, and it disappears with the stream. Nothing survives the session
 * to tell you what happened or what to change.
 *
 * <h3>What it claims, and what it does not</h3>
 * The loss percentage is measured. The suggested bitrate is <em>arithmetic on a
 * measurement</em>, not a measurement: if only 50% of frames survived, the path
 * evidently could not carry the offered rate, so the suggestion scales the
 * request by the delivered fraction and takes a margin off. It is deliberately
 * described to the user as somewhere to start, not as a correct value.
 *
 * <p>It does not claim to know <em>why</em> frames were lost. Loss of this
 * shape is consistent with a saturated link, but it is also consistent with a
 * host that cannot keep up. Distinguishing those needs the host's own numbers.
 *
 * <p>Immutable value object; safe to hand between threads once built.
 */
public final class LinkHealthSummary {

    /**
     * Below this, loss is normal and not worth a word. Moonlight's own
     * connection-status logic already treats 5% as okay, so this sits just
     * above it rather than inventing a second, conflicting idea of "fine".
     */
    private static final double NOTABLE_LOSS_PERCENT = 8.0;

    /** Above this, the stream was not really working, whatever it looked like. */
    private static final double SEVERE_LOSS_PERCENT = 25.0;

    /**
     * Sessions shorter than this are dominated by startup: the first frames
     * arrive while the encoder is still settling, so a short session can show
     * alarming loss that means nothing.
     */
    private static final long MIN_MEANINGFUL_SESSION_MS = 20_000L;

    /**
     * Headroom left below the delivered rate when suggesting a bitrate. A path
     * that just barely carried the traffic is a path that will fail on the next
     * busy scene, and the whole failure here was running with no margin.
     */
    private static final double SUGGESTION_HEADROOM = 0.8;

    private final int framesReceived;
    private final int framesLost;
    private final int requestedBitrateKbps;
    private final long sessionDurationMs;

    public LinkHealthSummary(int framesReceived, int framesLost,
                             int requestedBitrateKbps, long sessionDurationMs) {
        this.framesReceived = Math.max(0, framesReceived);
        this.framesLost = Math.max(0, framesLost);
        this.requestedBitrateKbps = requestedBitrateKbps;
        this.sessionDurationMs = sessionDurationMs;
    }

    /** Frames the host produced, as far as the client can tell from frame ids. */
    public int getFramesProduced() {
        return framesReceived + framesLost;
    }

    public double getLossPercent() {
        int produced = getFramesProduced();
        if (produced <= 0) {
            return 0;
        }
        return (framesLost * 100.0) / produced;
    }

    /**
     * Bitrate to suggest, in kbps, or 0 when there is nothing useful to say.
     *
     * <p>Scales the requested rate by the fraction that actually arrived and
     * then takes a margin off. Rounded to 5 Mbps because presenting this to
     * one kbps would imply a precision the estimate does not have.
     */
    public int getSuggestedBitrateKbps() {
        if (!isWorthReporting() || requestedBitrateKbps <= 0) {
            return 0;
        }

        double deliveredFraction = 1.0 - (getLossPercent() / 100.0);
        int suggestion = (int) (requestedBitrateKbps * deliveredFraction * SUGGESTION_HEADROOM);

        // Round down to a 5 Mbps step.
        suggestion = (suggestion / 5000) * 5000;

        // Never suggest something unusable, and never suggest going up.
        if (suggestion < 5000) {
            suggestion = 5000;
        }
        if (suggestion >= requestedBitrateKbps) {
            return 0;
        }
        return suggestion;
    }

    public boolean isSevere() {
        return getLossPercent() >= SEVERE_LOSS_PERCENT;
    }

    /**
     * True when this is worth interrupting the user for. Requires both a
     * meaningful session length and real loss, so a quick connect-and-quit
     * never produces a scary message.
     */
    public boolean isWorthReporting() {
        return sessionDurationMs >= MIN_MEANINGFUL_SESSION_MS
                && getFramesProduced() > 0
                && getLossPercent() >= NOTABLE_LOSS_PERCENT;
    }

    /**
     * User-facing message, or null when there is nothing worth saying.
     *
     * <p>Leads with the number. "Poor connection" is what the stream already
     * said and it was not enough to act on.
     */
    public String buildMessage(Context context) {
        if (!isWorthReporting()) {
            return null;
        }

        String headline = context.getString(
                isSevere() ? R.string.link_health_severe : R.string.link_health_notable,
                Math.round(getLossPercent()));

        int suggestion = getSuggestedBitrateKbps();
        if (suggestion <= 0) {
            return headline;
        }

        return headline + "\n" + context.getString(R.string.link_health_suggestion,
                requestedBitrateKbps / 1000, suggestion / 1000);
    }

    /** Compact form for the diagnostics string and the trace metadata. */
    public String toDiagnosticString() {
        return "frames_produced=" + getFramesProduced()
                + " frames_received=" + framesReceived
                + " frames_lost=" + framesLost
                + " loss_pct=" + String.format(java.util.Locale.US, "%.1f", getLossPercent())
                + " requested_kbps=" + requestedBitrateKbps
                + " suggested_kbps=" + getSuggestedBitrateKbps();
    }
}
