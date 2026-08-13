package com.limelight.bitratetest;

/**
 * Builds the bitrate ladder walked by the connection test.
 *
 * Pure Java on purpose: no Android types are referenced here so the ladder can be
 * unit tested on the JVM.
 *
 * The rungs are the fixed ladder asked for by the feature (10 / 20 / 30 / 50 / 80 /
 * 120 / 150 Mbps), trimmed to a ceiling derived from the resolution and frame rate the
 * user actually streams at. Testing 150 Mbps at 720p30 tells you nothing useful about
 * the link -- the encoder simply will not produce that much data for that few pixels --
 * so the ladder stops where more bitrate stops being meaningful.
 */
public final class BitrateLadder {

    /** The full ladder, in kbps, before the resolution/fps ceiling is applied. */
    public static final int[] BASE_RUNGS_KBPS = { 10000, 20000, 30000, 50000, 80000, 120000, 150000 };

    /** The ladder never stops below this, even at tiny resolutions. */
    public static final int MIN_CEILING_KBPS = 10000;

    /** The ladder never goes above this, no matter how large the resolution. */
    public static final int MAX_CEILING_KBPS = 150000;

    /**
     * How far above the default bitrate for a mode it is still worth probing. Four times
     * the default is already well past what any encoder will spend on typical content, so
     * a mode that is clean there is not bitrate limited.
     */
    public static final int CEILING_MULTIPLE_OF_DEFAULT = 4;

    /** A rung is only appended for the ceiling itself if it clears the last rung by this much. */
    private static final int MIN_RUNG_GAP_KBPS = 2000;

    private BitrateLadder() {
    }

    /**
     * The default bitrate Artemis would pick for this mode, in kbps.
     *
     * This is a faithful port of PreferenceConfiguration.getDefaultBitrate() (itself taken
     * from Moonlight Qt). It is duplicated rather than called because that class pulls in
     * Android, and this one has to stay unit testable.
     */
    public static int defaultBitrateKbps(int width, int height, double fps) {
        int roundedFps = (int) Math.round(fps);
        if (roundedFps <= 0) {
            roundedFps = 60;
        }

        // Bitrate does not scale linearly with frame rate once we get past 60 fps.
        double frameRateFactor = (roundedFps <= 60 ? roundedFps : (Math.sqrt(roundedFps / 60.0) * 60.0)) / 30.0;

        int[] pixelVals = {
                640 * 360,
                854 * 480,
                1280 * 720,
                1920 * 1080,
                2560 * 1440,
                3840 * 2160,
                -1,
        };
        int[] factorVals = {
                1,
                2,
                5,
                10,
                20,
                40,
                -1,
        };

        double resolutionFactor;
        int pixels = Math.max(1, width) * Math.max(1, height);
        int i = 0;
        while (true) {
            if (pixels == pixelVals[i]) {
                resolutionFactor = factorVals[i];
                break;
            }
            else if (pixelVals[i] != -1 && pixels < pixelVals[i]) {
                if (i == 0) {
                    resolutionFactor = factorVals[i];
                }
                else {
                    resolutionFactor = ((double) (pixels - pixelVals[i - 1]) / (pixelVals[i] - pixelVals[i - 1]))
                            * (factorVals[i] - factorVals[i - 1]) + factorVals[i - 1];
                }
                break;
            }
            else if (pixelVals[i] == -1) {
                resolutionFactor = factorVals[i - 1];
                break;
            }
            i++;
        }

        return (int) Math.round(resolutionFactor * frameRateFactor) * 1000;
    }

    /** The highest bitrate worth probing for this mode, in kbps. */
    public static int ceilingKbps(int width, int height, double fps) {
        long ceiling = (long) defaultBitrateKbps(width, height, fps) * CEILING_MULTIPLE_OF_DEFAULT;
        if (ceiling < MIN_CEILING_KBPS) {
            return MIN_CEILING_KBPS;
        }
        if (ceiling > MAX_CEILING_KBPS) {
            return MAX_CEILING_KBPS;
        }
        return (int) ceiling;
    }

    /**
     * Builds the ascending ladder of bitrates to test, in kbps. Always returns at least
     * one rung.
     */
    public static int[] build(int width, int height, double fps) {
        int ceiling = ceilingKbps(width, height, fps);

        int count = 0;
        for (int rung : BASE_RUNGS_KBPS) {
            if (rung <= ceiling) {
                count++;
            }
        }

        boolean appendCeiling = (count == 0) || (ceiling - BASE_RUNGS_KBPS[count - 1] >= MIN_RUNG_GAP_KBPS);

        int[] ladder = new int[count + (appendCeiling ? 1 : 0)];
        for (int i = 0; i < count; i++) {
            ladder[i] = BASE_RUNGS_KBPS[i];
        }
        if (appendCeiling) {
            ladder[count] = ceiling;
        }
        return ladder;
    }
}
