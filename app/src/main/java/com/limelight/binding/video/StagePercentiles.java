package com.limelight.binding.video;

import java.util.Arrays;

/**
 * Rolling percentiles for one pipeline stage, cheap enough to sit on the hot
 * path.
 *
 * <h3>Why percentiles and not an average</h3>
 * Every diagnosis in this project came from a tail. The present stage measured
 * 4.81 ms p50 against 20.05 ms p99, and the distribution turned out to be
 * bimodal with a second population one frame period out — an average of about
 * 6 ms would have shown a single healthy-looking number and hidden the entire
 * finding. The overlay exists to make that visible during a session rather than
 * after it, so it reports p50 and p99 and never a mean.
 *
 * <h3>Cost</h3>
 * {@link #add} is a bounds-check, an array store and an increment: no
 * allocation, no lock, no sorting. All the work happens in {@link #snapshot},
 * which the overlay calls about once per second — copying and sorting a few
 * hundred ints at 1 Hz is not measurable against a 60 Hz pipeline.
 *
 * <p>An overlay that perturbs the pipeline it measures is the failure mode to
 * avoid, so the ordering is deliberate: constant work per frame, all the
 * variable work at display time.
 *
 * <h3>Thread ownership</h3>
 * {@code add()} is called from whichever thread owns that stage — the video
 * receive thread for arrival and submit, the renderer thread for decode and
 * present. {@code snapshot()} is called from the overlay thread. There is no
 * lock: the ring is a plain int array and the writer only ever advances.
 *
 * <p>A concurrent snapshot can therefore read a partially updated ring and
 * compute a percentile from a mix of old and new samples. That is accepted
 * rather than overlooked: the values are wall-clock durations in microseconds
 * that move slowly relative to a one-second display cadence, so the worst case
 * is a percentile that is one sample stale. Taking a lock on the decode path to
 * avoid that would cost more than the inaccuracy it prevents, and would make the
 * instrument affect the thing it measures.
 */
public final class StagePercentiles {

    /**
     * Samples retained. 600 is ten seconds at 60 Hz and five at 120 Hz — long
     * enough that a p99 means something (six samples above the line at 600),
     * short enough that the overlay reflects the last few seconds rather than
     * the whole session.
     */
    private static final int CAPACITY = 600;

    private final int[] samplesUs = new int[CAPACITY];
    private volatile int count;
    private int writePos;

    /** Scratch for snapshot(), so the overlay thread does not allocate either. */
    private final int[] scratch = new int[CAPACITY];

    /** Immutable result of a snapshot. */
    public static final class Snapshot {
        public final int p50Us;
        public final int p99Us;
        public final int samples;

        Snapshot(int p50Us, int p99Us, int samples) {
            this.p50Us = p50Us;
            this.p99Us = p99Us;
            this.samples = samples;
        }

        public boolean isEmpty() {
            return samples == 0;
        }

        public float p50Ms() {
            return p50Us / 1000f;
        }

        public float p99Ms() {
            return p99Us / 1000f;
        }
    }

    private static final Snapshot EMPTY = new Snapshot(0, 0, 0);

    /**
     * Records one sample. Negative durations are dropped rather than clamped:
     * a negative stage duration means the two timestamps came from different
     * clocks or arrived out of order, and folding it in as zero would quietly
     * pull the percentile down.
     */
    public void add(long micros) {
        if (micros < 0 || micros > Integer.MAX_VALUE) {
            return;
        }

        int pos = writePos;
        samplesUs[pos] = (int) micros;
        writePos = (pos + 1) % CAPACITY;

        int c = count;
        if (c < CAPACITY) {
            count = c + 1;
        }
    }

    /** Clears the window, e.g. when the stream mode changes underneath it. */
    public void reset() {
        count = 0;
        writePos = 0;
    }

    /**
     * Computes p50 and p99 over the retained window. Overlay thread only.
     */
    public Snapshot snapshot() {
        int n = count;
        if (n <= 0) {
            return EMPTY;
        }
        if (n > CAPACITY) {
            n = CAPACITY;
        }

        System.arraycopy(samplesUs, 0, scratch, 0, n);
        Arrays.sort(scratch, 0, n);

        return new Snapshot(scratch[percentileIndex(n, 50)],
                            scratch[percentileIndex(n, 99)],
                            n);
    }

    private static int percentileIndex(int n, int percentile) {
        int idx = (int) ((long) n * percentile / 100L);
        if (idx >= n) {
            idx = n - 1;
        }
        if (idx < 0) {
            idx = 0;
        }
        return idx;
    }
}
