package com.limelight.sweep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the list of runs a sweep will perform.
 *
 * The shape of the plan is the whole design decision. A full cross product of
 * every client setting is both long and useless: most of the cells differ by
 * less than the run to run noise of a wireless link on a handheld that is
 * warming up while the sweep proceeds. So the plan varies only the settings
 * whose answer is genuinely device specific and unknown in advance, holds
 * bitrate fixed at a value the bitrate test already established as safe, and
 * repeats each cell so the report can say whether a difference is real.
 */
public final class SweepPlan {

    /** How much of the space to cover. */
    public enum Depth {
        /** Codecs only. Fastest, and usually where the largest difference is. */
        QUICK,
        /** Codecs against core pinning. */
        STANDARD,
        /** Adds frame pacing. */
        THOROUGH,
        /**
         * Everything the client can vary, bitrate included.
         *
         * Bitrate is its own axis here rather than a constant because it
         * interacts with the codec: the point at which a codec starts losing
         * packets is not the same for H.264 and AV1, so a single fixed bitrate
         * can rank a codec well only because it happened to suit that number.
         */
        EXHAUSTIVE
    }

    /** Repeats per cell. Below three, a median is not meaningfully a median. */
    public static final int REPEATS = 3;

    private SweepPlan() {
    }

    /**
     * @param codecs      candidate codecs the decoder actually supports
     * @param bitrateKbps held constant across every run
     * @param pacingModes candidate frame pacing modes, or empty to leave it alone
     * @param corePinningAvailable whether this device has distinct fast cores
     * @return the runs, already expanded by {@link #REPEATS} and interleaved
     */
    public static List<SweepVariant> build(List<Codec> codecs,
                                           List<Integer> bitratesKbps,
                                           List<Pacing> pacingModes,
                                           boolean corePinningAvailable,
                                           Depth depth) {
        List<SweepVariant> cells = new ArrayList<>();

        boolean[] pinning = (depth != Depth.QUICK && corePinningAvailable)
                ? new boolean[] {false, true}
                : new boolean[] {false};

        List<Pacing> pacings = ((depth == Depth.THOROUGH || depth == Depth.EXHAUSTIVE)
                && pacingModes != null && !pacingModes.isEmpty())
                ? pacingModes
                : Collections.singletonList(new Pacing(-1, ""));

        List<Integer> bitrates;
        if (bitratesKbps == null || bitratesKbps.isEmpty()) {
            bitrates = Collections.singletonList(0);
        } else if (depth == Depth.EXHAUSTIVE) {
            bitrates = bitratesKbps;
        } else {
            // Every other depth holds bitrate at the first value it was given,
            // which the bitrate test already established as safe.
            bitrates = Collections.singletonList(bitratesKbps.get(0));
        }

        for (Codec codec : codecs) {
            for (boolean pin : pinning) {
                for (Pacing pacing : pacings) {
                    for (Integer bitrate : bitrates) {
                        cells.add(new SweepVariant(codec.formatMask, codec.name, bitrate,
                                pin, pacing.mode, pacing.name));
                    }
                }
            }
        }

        return interleave(cells, REPEATS);
    }

    /** Convenience for the depths that hold bitrate constant. */
    public static List<SweepVariant> build(List<Codec> codecs,
                                           int bitrateKbps,
                                           List<Pacing> pacingModes,
                                           boolean corePinningAvailable,
                                           Depth depth) {
        return build(codecs, Collections.singletonList(bitrateKbps), pacingModes,
                corePinningAvailable, depth);
    }

    /**
     * Repeats every cell, ordered so that the repeats of one configuration are
     * spread across the sweep rather than run back to back.
     *
     * This matters more than it looks. A handheld gets hotter as the sweep goes
     * on, and Wi-Fi contention drifts. Running a configuration's three repeats
     * consecutively measures one moment three times; spreading them means each
     * configuration sees the early, middle and late conditions, so a thermal
     * trend shows up as spread within a configuration instead of masquerading
     * as one configuration being better than another.
     */
    static List<SweepVariant> interleave(List<SweepVariant> cells, int repeats) {
        List<SweepVariant> out = new ArrayList<>(cells.size() * repeats);
        int n = cells.size();
        for (int round = 0; round < repeats; round++) {
            // Rotate rather than reverse. Reversing alternate rounds looks like
            // it spreads the repeats, but it puts the same cell on both sides
            // of every round boundary, which is exactly the back to back
            // measurement this is meant to avoid. Rotating gives no adjacent
            // repeats at all for more than two cells, and also means the cell
            // measured first is a different one each round.
            for (int i = 0; i < n; i++) {
                out.add(cells.get((i + round) % n));
            }
        }
        return out;
    }

    /** How long the plan will take, roughly, for the confirmation prompt. */
    public static long estimatedMillis(int runs, long perRunMs) {
        return (long) runs * perRunMs;
    }

    /** A codec the decoder reported it can handle. */
    public static final class Codec {
        public final int formatMask;
        public final String name;

        public Codec(int formatMask, String name) {
            this.formatMask = formatMask;
            this.name = name;
        }
    }

    /** A frame pacing mode to try. */
    public static final class Pacing {
        public final int mode;
        public final String name;

        public Pacing(int mode, String name) {
            this.mode = mode;
            this.name = name;
        }
    }
}
