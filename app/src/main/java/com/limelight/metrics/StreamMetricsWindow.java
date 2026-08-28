package com.limelight.metrics;

/**
 * Turns cumulative stream counters into the rates a live readout needs.
 *
 * The decoder reports totals since the session began, so a banner built
 * directly on them would show a session average that barely moves after the
 * first minute and hides exactly the moments worth noticing. This keeps the
 * previous reading and reports the difference, so what is on screen describes
 * the last second rather than the last hour.
 *
 * Pure Java: the arithmetic has enough edges worth pinning down that it should
 * not need a device to exercise.
 */
public final class StreamMetricsWindow {

    /** Nothing has been measured yet. */
    public static final int UNKNOWN = -1;

    private boolean primed;
    private long lastTimestampMs;
    private long lastFramesRendered;
    private long lastDecoderTimeMs;

    private int fps = UNKNOWN;
    private int decodeMsTenths = UNKNOWN;

    /** Frames per second over the last window, or {@link #UNKNOWN}. */
    public int getFps() {
        return fps;
    }

    /** Average decode time in tenths of a millisecond, or {@link #UNKNOWN}. */
    public int getDecodeTimeTenthsMs() {
        return decodeMsTenths;
    }

    public boolean hasReading() {
        return fps != UNKNOWN;
    }

    public void reset() {
        primed = false;
        fps = UNKNOWN;
        decodeMsTenths = UNKNOWN;
    }

    /**
     * Feeds one reading of the cumulative counters.
     *
     * @param timestampMs      monotonic clock, not wall time
     * @param framesRendered   total frames rendered since the session began
     * @param decoderTimeMs    total milliseconds spent decoding since then
     */
    public void update(long timestampMs, long framesRendered, long decoderTimeMs) {
        if (!primed) {
            primed = true;
            lastTimestampMs = timestampMs;
            lastFramesRendered = framesRendered;
            lastDecoderTimeMs = decoderTimeMs;
            return;
        }

        long elapsedMs = timestampMs - lastTimestampMs;
        long frames = framesRendered - lastFramesRendered;
        long decodeMs = decoderTimeMs - lastDecoderTimeMs;

        if (elapsedMs <= 0 || frames < 0 || decodeMs < 0) {
            // A counter went backwards, which happens when the session is torn
            // down and started again underneath us. Re-anchor rather than
            // reporting a wild number for one tick.
            lastTimestampMs = timestampMs;
            lastFramesRendered = framesRendered;
            lastDecoderTimeMs = decoderTimeMs;
            return;
        }

        lastTimestampMs = timestampMs;
        lastFramesRendered = framesRendered;
        lastDecoderTimeMs = decoderTimeMs;

        fps = (int) Math.round(frames * 1000.0 / elapsedMs);

        if (frames == 0) {
            // No frames means no decode time to average. Keeping the previous
            // figure is a lie; zero is a different lie. Say nothing.
            decodeMsTenths = UNKNOWN;
        } else {
            decodeMsTenths = (int) Math.round(decodeMs * 10.0 / frames);
        }
    }

    /** "8.4" for 84 tenths, or an em dash when there is no reading. */
    public static String formatTenths(int tenths) {
        if (tenths == UNKNOWN) {
            return "—";
        }
        return (tenths / 10) + "." + (tenths % 10);
    }
}
