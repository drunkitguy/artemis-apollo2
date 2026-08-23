package com.limelight.preferences;

import java.util.List;

/**
 * Picks which screen "native resolution" should describe.
 *
 * Artemis finds a secondary display by taking the first one whose id is not
 * zero and stopping there, which assumes the only reason a device has a second
 * screen is that a monitor was plugged into it. On a dual screen handheld that
 * assumption picks the small built-in panel, and the stream ends up sized for
 * the auxiliary screen rather than the one being looked at.
 *
 * Choosing by size instead of by enumeration order fixes that: an external
 * monitor is always larger than an auxiliary panel, so "largest" separates them
 * without needing to know which is which.
 */
public final class StreamDisplayChooser {

    public static final int NO_DISPLAY = -1;

    public static final class Candidate {
        public final int displayId;
        public final int width;
        public final int height;
        public final boolean isDefault;
        public final boolean usable;

        public Candidate(int displayId, int width, int height, boolean isDefault, boolean usable) {
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.isDefault = isDefault;
            this.usable = usable;
        }

        long pixels() {
            return (long) width * (long) height;
        }

        @Override
        public String toString() {
            return "Display(" + displayId + ", " + width + "x" + height
                    + (isDefault ? ", default" : "") + (usable ? "" : ", unusable") + ")";
        }
    }

    private StreamDisplayChooser() {
    }

    /**
     * @param displays       every screen the device reports
     * @param useExternal    whether external display mode is on, meaning the
     *                       picture is meant to leave the handheld
     * @return the display id to size the stream for, or {@link #NO_DISPLAY}
     */
    public static int choose(List<Candidate> displays, boolean useExternal) {
        if (displays == null || displays.isEmpty()) {
            return NO_DISPLAY;
        }

        Candidate fallback = null;
        Candidate largest = null;

        for (Candidate candidate : displays) {
            if (candidate == null || !candidate.usable
                    || candidate.width <= 0 || candidate.height <= 0) {
                continue;
            }

            if (candidate.isDefault) {
                fallback = candidate;
            }

            if (largest == null
                    || candidate.pixels() > largest.pixels()
                    || (candidate.pixels() == largest.pixels()
                        && candidate.displayId < largest.displayId)) {
                largest = candidate;
            }
        }

        if (largest == null) {
            return NO_DISPLAY;
        }

        if (useExternal) {
            // The picture is going somewhere off the handheld, and whatever
            // that is will be the biggest thing attached.
            return largest.displayId;
        }

        if (fallback == null) {
            // No display claimed to be the default, which should not happen.
            // The largest is a better guess than giving up.
            return largest.displayId;
        }

        // The stream renders on the main screen. Guard anyway: if the default
        // is somehow not the largest built-in panel, sizing the stream to a
        // smaller auxiliary screen is never what was wanted.
        return fallback.pixels() >= largest.pixels() ? fallback.displayId : largest.displayId;
    }
}
