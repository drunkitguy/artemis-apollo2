package com.voidlink.android.data

import kotlin.math.abs
import kotlin.math.ceil

/**
 * One timed round trip to a host.
 *
 * @property roundTripMs how long the request took, or `null` when it failed or timed out.
 * @property failure a short description of why it failed, when it did.
 */
data class LinkSample(
    val roundTripMs: Long?,
    val failure: String? = null,
) {
    /** True when the host answered this sample. */
    val succeeded: Boolean get() = roundTripMs != null
}

/**
 * How good a link looks, as a coarse verdict the UI can colour.
 *
 * Deliberately four steps rather than a score out of a hundred: the user's decision is "leave it
 * alone", "maybe lower the bitrate", or "fix the network", and a number to two decimal places does
 * not help them make it.
 */
enum class LinkGrade(val label: String) {
    /** Fast and steady; nothing here will limit the stream. */
    EXCELLENT("Excellent"),

    /** Good enough that the codec, not the link, decides the picture. */
    GOOD("Good"),

    /** Usable, but jitter or loss is high enough to be worth backing the bitrate off. */
    FAIR("Fair"),

    /** Something is wrong with the network; a lower bitrate will help but will not fix it. */
    POOR("Poor"),
}

/**
 * The statistics derived from a burst of [LinkSample]s.
 *
 * **Jitter, not bandwidth, is what makes a stream stutter.** A link with 200 Mbps of throughput and
 * 40 ms of jitter feels far worse than one with 60 Mbps and 2 ms, because the decoder has a fixed
 * queue and a late frame is a dropped frame. That is why this type reports jitter and loss as
 * first-class figures and why the recommendation engine degrades its answer on them.
 *
 * Every field is derived arithmetic over the samples, so the whole type is JVM-unit-testable and
 * carries no Android dependency.
 *
 * @property requested how many samples were attempted.
 * @property succeeded how many of them came back.
 * @property minMs the fastest round trip — the link's floor when nothing is in the way.
 * @property medianMs the typical round trip.
 * @property p95Ms the slow tail: one request in twenty is at least this slow.
 * @property jitterMs the mean absolute difference between consecutive successful samples.
 * @property lossPercent share of samples that timed out or errored.
 * @property driftMs the second half's median minus the first half's; positive means the link got
 *   worse while we watched it, which is the signature of a saturated uplink or a roaming Wi-Fi
 *   client.
 */
data class LinkQuality(
    val requested: Int,
    val succeeded: Int,
    val minMs: Double,
    val medianMs: Double,
    val p95Ms: Double,
    val jitterMs: Double,
    val lossPercent: Double,
    val driftMs: Double,
) {
    /**
     * True when enough samples came back for the figures to mean anything.
     *
     * Two successful round trips out of twenty produce a median and a jitter, and both are noise.
     * Reporting them as measurements would be worse than reporting nothing.
     */
    val isUsable: Boolean get() = succeeded >= MIN_USABLE_SAMPLES

    /** True when latency climbed measurably across the sample window. */
    val isDegrading: Boolean get() = isUsable && driftMs >= DRIFT_WARNING_MS

    /** The coarse verdict. */
    val grade: LinkGrade
        get() = when {
            !isUsable -> LinkGrade.POOR
            lossPercent > POOR_LOSS_PERCENT -> LinkGrade.POOR
            jitterMs > POOR_JITTER_MS -> LinkGrade.POOR
            p95Ms > POOR_P95_MS -> LinkGrade.POOR
            lossPercent > FAIR_LOSS_PERCENT -> LinkGrade.FAIR
            jitterMs > FAIR_JITTER_MS -> LinkGrade.FAIR
            p95Ms > FAIR_P95_MS -> LinkGrade.FAIR
            jitterMs > GOOD_JITTER_MS -> LinkGrade.GOOD
            p95Ms > GOOD_P95_MS -> LinkGrade.GOOD
            else -> LinkGrade.EXCELLENT
        }

    /** One line describing whether the link held steady over the window. */
    val stabilityLabel: String
        get() = when {
            !isUsable -> "Not enough answers"
            driftMs >= DRIFT_WARNING_MS -> "Getting slower"
            driftMs <= -DRIFT_WARNING_MS -> "Settling down"
            else -> "Steady"
        }

    companion object {
        /** Below this many answered samples the statistics are noise, not measurement. */
        const val MIN_USABLE_SAMPLES: Int = 4

        /** How much median latency has to climb across the window before we call it degrading. */
        const val DRIFT_WARNING_MS: Double = 15.0

        private const val POOR_LOSS_PERCENT = 5.0
        private const val POOR_JITTER_MS = 30.0
        private const val POOR_P95_MS = 200.0
        private const val FAIR_LOSS_PERCENT = 1.0
        private const val FAIR_JITTER_MS = 12.0
        private const val FAIR_P95_MS = 80.0
        private const val GOOD_JITTER_MS = 5.0
        private const val GOOD_P95_MS = 35.0

        /**
         * Reduces a burst of samples to its statistics.
         *
         * Failed samples are excluded from every latency figure and counted only in
         * [lossPercent] — averaging a timeout in as though it were a very slow reply would make a
         * dead link look merely sluggish.
         */
        fun from(samples: List<LinkSample>): LinkQuality {
            val timings = ArrayList<Double>(samples.size)
            for (sample in samples) {
                val value = sample.roundTripMs
                if (value != null) timings.add(value.toDouble())
            }
            val lossPercent = if (samples.isEmpty()) {
                0.0
            } else {
                (samples.size - timings.size) * 100.0 / samples.size
            }
            if (timings.isEmpty()) {
                return LinkQuality(
                    requested = samples.size,
                    succeeded = 0,
                    minMs = 0.0,
                    medianMs = 0.0,
                    p95Ms = 0.0,
                    jitterMs = 0.0,
                    lossPercent = lossPercent,
                    driftMs = 0.0,
                )
            }
            val sorted = timings.sorted()
            return LinkQuality(
                requested = samples.size,
                succeeded = timings.size,
                minMs = sorted.first(),
                medianMs = median(sorted),
                p95Ms = percentile(sorted, P95_FRACTION),
                jitterMs = meanConsecutiveDelta(timings),
                lossPercent = lossPercent,
                driftMs = drift(timings),
            )
        }

        private const val P95_FRACTION = 0.95

        /** The middle value, averaging the two middles when [sorted] has an even length. */
        fun median(sorted: List<Double>): Double {
            if (sorted.isEmpty()) return 0.0
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            }
        }

        /**
         * The nearest-rank percentile of an already-sorted list.
         *
         * Nearest-rank rather than interpolated: with twenty samples an interpolated p95 invents a
         * value that was never measured, and the point of the tail figure is to name a round trip
         * that actually happened.
         */
        fun percentile(sorted: List<Double>, fraction: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val rank = ceil(fraction * sorted.size).toInt()
            val index = (rank - 1).coerceIn(0, sorted.size - 1)
            return sorted[index]
        }

        /**
         * Mean absolute difference between neighbouring samples, **in arrival order**.
         *
         * This is the figure that predicts stutter: a stream is fed by a queue two frames deep, so
         * what matters is not how long a packet takes but how much that varies from the last one.
         */
        fun meanConsecutiveDelta(inOrder: List<Double>): Double {
            if (inOrder.size < 2) return 0.0
            var total = 0.0
            for (index in 1 until inOrder.size) {
                total += abs(inOrder[index] - inOrder[index - 1])
            }
            return total / (inOrder.size - 1)
        }

        /**
         * How much the median moved between the first and second halves of the window.
         *
         * A link that is fine for two seconds and then falls apart averages out to "acceptable",
         * which is exactly the reading that sends a user off to buy a router they did not need.
         */
        private fun drift(inOrder: List<Double>): Double {
            if (inOrder.size < MIN_USABLE_SAMPLES) return 0.0
            val split = inOrder.size / 2
            val first = inOrder.subList(0, split).sorted()
            val second = inOrder.subList(split, inOrder.size).sorted()
            if (first.isEmpty() || second.isEmpty()) return 0.0
            return median(second) - median(first)
        }
    }
}
