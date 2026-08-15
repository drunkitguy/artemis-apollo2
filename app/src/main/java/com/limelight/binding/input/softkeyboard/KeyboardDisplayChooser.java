package com.limelight.binding.input.softkeyboard;

import java.util.List;

/**
 * Decides which screen the soft keyboard belongs on.
 *
 * Dual screen handhelds pair a large main panel with a smaller secondary one,
 * and the keyboard wants the small one: it leaves the game unobstructed and
 * puts the keys under the thumbs. The rule is deliberately expressed over
 * plain numbers rather than {@code android.view.Display} so it can be tested.
 */
public final class KeyboardDisplayChooser {

    /** Nothing suitable was found; the caller should fall back to an overlay. */
    public static final int NO_DISPLAY = -1;

    /** One screen the device is reporting. */
    public static final class Candidate {
        public final int displayId;
        public final int width;
        public final int height;
        /** False for a screen that is off, mirroring, or otherwise not usable. */
        public final boolean usable;

        public Candidate(int displayId, int width, int height, boolean usable) {
            this.displayId = displayId;
            this.width = width;
            this.height = height;
            this.usable = usable;
        }

        long pixels() {
            return (long) width * (long) height;
        }

        @Override
        public String toString() {
            return "Display(" + displayId + ", " + width + "x" + height + (usable ? "" : ", unusable") + ")";
        }
    }

    private KeyboardDisplayChooser() {
    }

    /**
     * Picks the screen to put the keyboard on.
     *
     * Any screen the stream is already on is out, whichever one that is: on a
     * Thor the game sits on the 1080p panel and the keyboard wants the smaller
     * one, but with Artemis's external display mode the game is on the
     * external screen and the same rule sends the keyboard back to the
     * handheld. Of what is left the smallest wins, because on a dual screen
     * device the secondary panel is the lower resolution one, and ties go to
     * the lowest id so the choice does not move around between sessions.
     *
     * @param displays      every screen the device reports
     * @param gameDisplayId the screen the stream is being rendered on
     * @return a display id, or {@link #NO_DISPLAY} when there is no second screen
     */
    public static int choose(List<Candidate> displays, int gameDisplayId) {
        if (displays == null) {
            return NO_DISPLAY;
        }

        Candidate best = null;
        for (Candidate candidate : displays) {
            if (candidate == null || !candidate.usable) {
                continue;
            }
            if (candidate.displayId == gameDisplayId) {
                continue;
            }
            if (candidate.width <= 0 || candidate.height <= 0) {
                // A screen that will not report a size cannot be laid out on.
                continue;
            }
            if (best == null
                    || candidate.pixels() < best.pixels()
                    || (candidate.pixels() == best.pixels() && candidate.displayId < best.displayId)) {
                best = candidate;
            }
        }

        return best == null ? NO_DISPLAY : best.displayId;
    }
}
