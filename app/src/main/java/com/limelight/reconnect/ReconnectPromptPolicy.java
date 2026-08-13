package com.limelight.reconnect;

/**
 * Decides whether -- and at what bitrate -- to offer the user a reconnect when the
 * connection has been struggling.
 *
 * <p>Pure Java on purpose: no Android types are referenced here, so every rule below can
 * be unit tested on the JVM. {@code Game} owns the timers, the view and the relaunch; this
 * class owns nothing but the decision.
 *
 * <p><b>What this deliberately is not.</b> There is no client-to-host bitrate message in
 * this protocol and no RTSP verb to renegotiate one, and {@code ANNOUNCE} sets
 * {@code minimumBitrateKbps == maximumBitrateKbps} precisely so the host does not adapt on
 * its own. The only actuator available is tearing the session down and launching it again.
 * Automatic bitrate scaling existed in this protocol family once and was removed because it
 * oscillated. So this class never decides to change anything: it decides whether to *ask*,
 * exactly once, and the answer to that question is a human's.
 *
 * <p>Every rule here exists to stop the prompt from becoming noise:
 * <ul>
 *   <li>nothing is offered until the session has been up for {@link #STARTUP_GRACE_MS},
 *       because the first seconds of a stream are noisy by nature;</li>
 *   <li>the connection has to stay poor for {@link #SUSTAINED_POOR_MS} -- a single blip
 *       recovers on its own and must not produce a prompt;</li>
 *   <li>once the offer has been made it is never made again for the rest of the session,
 *       whether the user accepted, dismissed it or ignored it;</li>
 *   <li>the reduction has to be big enough to be worth a teardown, and the current bitrate
 *       has to be high enough that cutting it could plausibly help.</li>
 * </ul>
 */
public final class ReconnectPromptPolicy {

    /** Nothing is offered until the session has been running this long. */
    public static final long STARTUP_GRACE_MS = 15000;

    /** The connection has to stay poor this long before the offer appears. */
    public static final long SUSTAINED_POOR_MS = 5000;

    /** An ignored prompt takes itself away after this long. */
    public static final long AUTO_DISMISS_MS = 10000;

    /** The offered bitrate, as a percentage of the one the session is running at. */
    public static final int REDUCTION_PERCENT = 60;

    /** The bitrate seek bar moves in 500 kbps steps, so the offer lands on one. */
    public static final int ROUNDING_KBPS = 500;

    /** Matches the minimum of the bitrate seek bar. */
    public static final int MIN_BITRATE_KBPS = 500;

    /**
     * Below this there is nothing useful left to give away: a stream already down at a
     * few Mbps is not struggling because of its bitrate.
     */
    public static final int MIN_OFFERABLE_BITRATE_KBPS = 3000;

    /** A reconnect costs the user their stream for a few seconds; it has to buy more than this. */
    public static final int MIN_SAVING_KBPS = 1000;

    /**
     * Frame loss over the sustained-poor window at or above this is treated as the
     * renderer's counters agreeing with the host's verdict. The same 0.5% figure the
     * performance overlay would show as packet loss.
     */
    public static final double CORROBORATING_LOSS_PERCENT = 0.5;

    /**
     * Fewer frames than this over the whole window means the stream was not really
     * delivering, which is degradation whatever the loss ratio works out to.
     */
    public static final long MIN_FRAMES_FOR_LOSS_VERDICT = 30;

    // ---------------------------------------------------------------- pure decisions

    /**
     * The bitrate to offer, in kbps: {@link #REDUCTION_PERCENT} of the current one, rounded
     * down onto a seek bar step and never below the seek bar's own minimum.
     */
    public static int reducedBitrateKbps(int currentKbps) {
        if (currentKbps <= MIN_BITRATE_KBPS) {
            return MIN_BITRATE_KBPS;
        }
        long reduced = (long) currentKbps * REDUCTION_PERCENT / 100;
        reduced = (reduced / ROUNDING_KBPS) * ROUNDING_KBPS;
        if (reduced < MIN_BITRATE_KBPS) {
            return MIN_BITRATE_KBPS;
        }
        if (reduced > currentKbps) {
            return currentKbps;
        }
        return (int) reduced;
    }

    /**
     * Whether dropping to {@link #reducedBitrateKbps} is worth interrupting the session
     * for. Both ends matter: a stream that is already tiny has nothing to give up, and a
     * reduction that saves almost nothing is not worth a teardown.
     */
    public static boolean isReductionWorthwhile(int currentKbps) {
        if (currentKbps < MIN_OFFERABLE_BITRATE_KBPS) {
            return false;
        }
        return currentKbps - reducedBitrateKbps(currentKbps) >= MIN_SAVING_KBPS;
    }

    /**
     * Whether the renderer's own counters, differenced across the sustained-poor window,
     * agree that the stream was in trouble.
     *
     * <p>This is corroboration, not detection: the host's {@code CONN_STATUS_POOR} signal
     * is what starts the clock. Reading counters that the renderer already maintains for
     * the performance overlay costs one field read per counter and happens twice per
     * degradation episode, never per frame.
     */
    public static boolean degradationCorroborated(long framesReceivedDelta, long framesLostDelta) {
        long received = Math.max(0, framesReceivedDelta);
        long lost = Math.max(0, framesLostDelta);
        long total = received + lost;
        if (total < MIN_FRAMES_FOR_LOSS_VERDICT) {
            // Next to nothing arrived over several seconds. That is not a healthy stream.
            return true;
        }
        return (100.0 * lost / total) >= CORROBORATING_LOSS_PERCENT;
    }

    // ------------------------------------------------------------------ session state

    private boolean enabled = true;
    private boolean offerSpent;
    private long sessionStartMs = -1;
    private long poorSinceMs = -1;

    /**
     * Turns the whole feature off for this session. Used for the preference, and for a
     * session that is itself the product of a reconnect -- offering again there is how a
     * one-shot prompt would turn into the hunting we are avoiding.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Called once the stream is actually up; starts the startup grace period. */
    public void sessionStarted(long nowMs) {
        sessionStartMs = nowMs;
        poorSinceMs = -1;
    }

    public boolean hasSessionStarted() {
        return sessionStartMs >= 0;
    }

    /**
     * Records that the host reported a poor connection.
     *
     * @return true if this is the leading edge of a new poor episode, meaning the caller
     *         should arm its one-shot check. False means we are already inside an episode
     *         (or there is nothing to arm), and the caller must not re-arm anything.
     */
    public boolean connectionPoor(long nowMs) {
        if (!enabled || offerSpent || !hasSessionStarted()) {
            return false;
        }
        if (poorSinceMs >= 0) {
            return false;
        }
        poorSinceMs = nowMs;
        return true;
    }

    /** Records that the connection recovered, which cancels the pending episode. */
    public void connectionOkay(long nowMs) {
        poorSinceMs = -1;
    }

    public boolean isPoor() {
        return poorSinceMs >= 0;
    }

    /**
     * How long from {@code nowMs} until the earliest moment an offer could be made: the
     * later of "poor for long enough" and "past the startup grace period". Never negative,
     * so it can be handed straight to a delayed post.
     */
    public long evaluationDelayMs(long nowMs) {
        long sustained = poorSinceMs >= 0 ? (poorSinceMs + SUSTAINED_POOR_MS) - nowMs : SUSTAINED_POOR_MS;
        long grace = hasSessionStarted() ? (sessionStartMs + STARTUP_GRACE_MS) - nowMs : STARTUP_GRACE_MS;
        long delay = Math.max(sustained, grace);
        return delay > 0 ? delay : 0;
    }

    /** Whether an offer may be shown right now. */
    public boolean shouldOffer(long nowMs) {
        if (!enabled || offerSpent || !hasSessionStarted()) {
            return false;
        }
        if (poorSinceMs < 0) {
            return false;
        }
        if (nowMs - sessionStartMs < STARTUP_GRACE_MS) {
            return false;
        }
        return nowMs - poorSinceMs >= SUSTAINED_POOR_MS;
    }

    /**
     * Records that the offer has been made. From here on nothing can bring it back for
     * this session -- accepted, dismissed and ignored all land here.
     */
    public void offerMade() {
        offerSpent = true;
        poorSinceMs = -1;
    }

    public boolean isOfferSpent() {
        return offerSpent;
    }
}
