package com.limelight.preferences;

/**
 * Turns a panel's reported size into a resolution worth streaming at.
 *
 * Separate from the preference plumbing, and free of Android types, because
 * the rules here are fiddly enough to be worth testing: panels report portrait,
 * encoders reject odd dimensions, and a display can be larger than anything the
 * pair of machines should reasonably attempt.
 */
public final class NativeResolution {

    /** Above this, the client is asking for more than the session can carry. */
    public static final int MAX_WIDTH = 3840;
    public static final int MAX_HEIGHT = 2160;

    /** Below this a value is not a real panel size and should not be trusted. */
    public static final int MIN_DIMENSION = 240;

    /** Used when a display will not report anything usable. */
    public static final int FALLBACK_WIDTH = 1920;
    public static final int FALLBACK_HEIGHT = 1080;

    private NativeResolution() {
    }

    /**
     * @return {@code {width, height}}, landscape, even, and within range
     */
    public static int[] normalize(int reportedWidth, int reportedHeight) {
        if (reportedWidth < MIN_DIMENSION || reportedHeight < MIN_DIMENSION) {
            // A display that reports nothing, or something implausible. Better
            // a known good resolution than a stream that cannot be set up.
            return new int[] {FALLBACK_WIDTH, FALLBACK_HEIGHT};
        }

        // Panels report their physical orientation, which on a handheld is
        // usually portrait even though the stream is always laid out landscape.
        int width = Math.max(reportedWidth, reportedHeight);
        int height = Math.min(reportedWidth, reportedHeight);

        if (width > MAX_WIDTH || height > MAX_HEIGHT) {
            // Scale down on the tighter of the two limits so the panel's shape
            // survives. Fitting each axis independently would stretch it.
            double scale = Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height);
            width = (int) Math.round(width * scale);
            height = (int) Math.round(height * scale);
        }

        // Every hardware encoder in play here wants even dimensions, and a
        // panel with an odd edge would otherwise fail at stream setup with
        // nothing to point at.
        width &= ~1;
        height &= ~1;

        return new int[] {width, height};
    }

    /** The preference value string for a resolved native size. */
    public static String toResolutionString(int[] resolution) {
        return resolution[0] + "x" + resolution[1];
    }
}
