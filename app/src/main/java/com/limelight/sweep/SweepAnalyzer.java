package com.limelight.sweep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a pile of sweep runs into something worth acting on.
 *
 * Two rules shape this. Repeats are reduced with a median rather than a mean,
 * because one run ruined by a burst of Wi-Fi contention should not move the
 * answer. And nothing is declared a winner unless it beats the alternative by
 * more than the spread of its own repeats, because a ranking whose gaps are
 * smaller than its noise is a ranking of noise.
 */
public final class SweepAnalyzer {

    /**
     * A difference smaller than this fraction of the spread is not reported as
     * a difference. One is deliberately strict: the winner has to be clear of
     * the runner up by at least as much as its own repeats disagree.
     */
    private static final double SIGNIFICANCE_FACTOR = 1.0;

    private SweepAnalyzer() {
    }

    /** One configuration, reduced across its repeats. */
    public static final class Summary {
        public final SweepVariant variant;
        public final int runs;
        public final double medianDecodeMs;
        /** Half the range of the repeats, as a plain uncertainty figure. */
        public final double decodeSpreadMs;
        public final double medianLossPercent;
        public final double medianHostLatencyMs;
        public final boolean anyFailed;

        Summary(SweepVariant variant, int runs, double medianDecodeMs, double decodeSpreadMs,
                double medianLossPercent, double medianHostLatencyMs, boolean anyFailed) {
            this.variant = variant;
            this.runs = runs;
            this.medianDecodeMs = medianDecodeMs;
            this.decodeSpreadMs = decodeSpreadMs;
            this.medianLossPercent = medianLossPercent;
            this.medianHostLatencyMs = medianHostLatencyMs;
            this.anyFailed = anyFailed;
        }

        /** Unusable regardless of how fast it decoded. */
        public boolean isDisqualified() {
            return anyFailed || medianLossPercent > 0.5;
        }
    }

    /** One measured run, as fed in by the caller. */
    public static final class Run {
        public final SweepVariant variant;
        public final double decodeMs;
        public final double lossPercent;
        public final double hostLatencyMs;
        public final boolean failed;

        public Run(SweepVariant variant, double decodeMs, double lossPercent,
                   double hostLatencyMs, boolean failed) {
            this.variant = variant;
            this.decodeMs = decodeMs;
            this.lossPercent = lossPercent;
            this.hostLatencyMs = hostLatencyMs;
            this.failed = failed;
        }
    }

    /** Groups runs by configuration and reduces each group. */
    public static List<Summary> summarize(List<Run> runs) {
        Map<String, List<Run>> grouped = new LinkedHashMap<>();
        for (Run run : runs) {
            String key = run.variant.key();
            List<Run> bucket = grouped.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                grouped.put(key, bucket);
            }
            bucket.add(run);
        }

        List<Summary> out = new ArrayList<>(grouped.size());
        for (List<Run> bucket : grouped.values()) {
            List<Double> decode = new ArrayList<>();
            List<Double> loss = new ArrayList<>();
            List<Double> host = new ArrayList<>();
            boolean failed = false;

            for (Run run : bucket) {
                if (run.failed) {
                    failed = true;
                    continue;
                }
                decode.add(run.decodeMs);
                loss.add(run.lossPercent);
                host.add(run.hostLatencyMs);
            }

            out.add(new Summary(
                    bucket.get(0).variant,
                    bucket.size(),
                    median(decode),
                    spread(decode),
                    median(loss),
                    median(host),
                    failed));
        }
        return out;
    }

    /**
     * Best configuration by decode time, or null when nothing qualified.
     *
     * Disqualified configurations are dropped first: a codec that decodes
     * quickly while losing packets is not a better answer, it is a broken one.
     */
    public static Summary best(List<Summary> summaries) {
        Summary best = null;
        for (Summary candidate : summaries) {
            if (candidate.isDisqualified()) {
                continue;
            }
            if (best == null || candidate.medianDecodeMs < best.medianDecodeMs) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * True when the winner is far enough ahead of the runner up to be worth
     * acting on, given how much its own repeats disagreed.
     */
    public static boolean isConclusive(List<Summary> summaries) {
        Summary best = best(summaries);
        if (best == null) {
            return false;
        }

        Summary runnerUp = null;
        for (Summary candidate : summaries) {
            if (candidate == best || candidate.isDisqualified()) {
                continue;
            }
            if (runnerUp == null || candidate.medianDecodeMs < runnerUp.medianDecodeMs) {
                runnerUp = candidate;
            }
        }

        if (runnerUp == null) {
            // Only one configuration survived, so there is nothing to compare
            // it against and nothing to be confident about.
            return false;
        }

        double gap = runnerUp.medianDecodeMs - best.medianDecodeMs;
        double noise = Math.max(best.decodeSpreadMs, runnerUp.decodeSpreadMs);
        return gap > noise * SIGNIFICANCE_FACTOR && gap > 0;
    }

    static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2d;
    }

    /** Half the range. Crude, but honest and readable as a plus or minus. */
    static double spread(List<Double> values) {
        if (values == null || values.size() < 2) {
            return 0d;
        }
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (double value : values) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        return (high - low) / 2d;
    }
}
