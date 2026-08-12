package com.voidlink.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Which kind of throughput test to run against an `iperf3` server on the host PC.
 *
 * Both run **server → client**, because that is the direction video travels and therefore the only
 * direction whose capacity tells us anything about a stream.
 */
enum class ThroughputMode(val label: String) {
    /**
     * UDP at a fixed target rate, reporting the loss and jitter the link produces *at that rate*.
     *
     * This is the default, and it is the more useful of the two. A TCP test answers "what is the
     * peak average bandwidth", which is not the question — remote play does not break because the
     * average was too low, it breaks because packets arrived late or not at all at the rate we were
     * actually sending. Driving UDP at the rate we intend to stream at measures precisely that
     * failure mode, and video itself is UDP.
     */
    PACED_UDP("At my settings"),

    /**
     * TCP, as fast as the link will carry it, reporting sustained throughput.
     *
     * Kept because it answers a different and still useful question — how much room is there at all
     * — which is what you want when the paced test passes easily and you are wondering whether to
     * raise the settings.
     */
    SUSTAINED_TCP("Find the ceiling"),
}

/**
 * A throughput measurement, in whichever of the two shapes the test produced.
 *
 * They are not interchangeable and the recommendation engine treats them differently, so they are
 * separate types rather than one type with half its fields null.
 */
sealed interface ThroughputEvidence {

    /** Throughput in megabits per second, as the number to show the user. */
    val headlineMbps: Double

    /**
     * A TCP run: the link carried this much on average, flat out.
     *
     * Says nothing about behaviour at a specific rate, so the recommendation engine keeps a wide
     * safety margin under it.
     *
     * @property megabitsPerSecond sustained receive rate.
     * @property bytes total bytes received during the measured window.
     * @property seconds length of the measured window.
     */
    data class Sustained(
        val megabitsPerSecond: Double,
        val bytes: Long,
        val seconds: Double,
    ) : ThroughputEvidence {
        override val headlineMbps: Double get() = megabitsPerSecond
    }

    /**
     * A UDP run at a fixed rate: the link was asked for [targetMbps] and delivered this.
     *
     * The strongest evidence we can gather without actually streaming, because it is the same
     * traffic shape as the stream itself.
     *
     * @property targetMbps the rate the server was told to send at.
     * @property receivedMbps the rate that actually arrived.
     * @property lossPercent share of the server's datagrams that never turned up.
     * @property jitterMs RFC 1889 interarrival jitter, as computed by the standard iperf3 estimator.
     * @property packets how many datagrams arrived.
     */
    data class Loaded(
        val targetMbps: Double,
        val receivedMbps: Double,
        val lossPercent: Double,
        val jitterMs: Double,
        val packets: Long,
    ) : ThroughputEvidence {
        override val headlineMbps: Double get() = receivedMbps

        /** True when the link carried the requested rate with essentially nothing dropped. */
        val isClean: Boolean get() = lossPercent <= CLEAN_LOSS_PERCENT

        companion object {
            /**
             * Loss at or below this is indistinguishable from measurement noise.
             *
             * A stream's forward error correction absorbs a fraction of a percent without a visible
             * artefact; above that the decoder starts asking for keyframes, which is the stutter
             * the user came here to fix.
             */
            const val CLEAN_LOSS_PERCENT: Double = 0.5
        }
    }
}

/**
 * Why a throughput test could not produce a number.
 *
 * Each of these needs a different thing from the user, so they are separate values rather than one
 * "test failed" — the overwhelmingly common case is simply that `iperf3 -s` is not running, and the
 * UI has to say exactly that rather than blame the network.
 */
enum class ThroughputFailure {
    /** Nothing is listening on the port: `iperf3 -s` has not been started on the PC. */
    SERVER_NOT_RUNNING,

    /** An iperf3 server answered but is already busy with another test. */
    SERVER_BUSY,

    /** The address could not be reached at all — wrong network, or the PC is off. */
    UNREACHABLE,

    /** Something answered, but it did not speak the iperf3 control protocol. */
    PROTOCOL_MISMATCH,

    /** The iperf3 server reported an error of its own. */
    SERVER_ERROR,

    /** The exchange stalled and the deadline passed. */
    TIMED_OUT,

    /** The user cancelled, or the screen went away. */
    CANCELLED,
}

/** Progress of a Tier 1 link-quality measurement. */
sealed interface LinkTestProgress {

    /** One more sample is in, [completed] of [total] done. */
    data class Sampled(
        val completed: Int,
        val total: Int,
        val lastRoundTripMs: Long?,
    ) : LinkTestProgress

    /** The window finished; here are the statistics. */
    data class Finished(val quality: LinkQuality) : LinkTestProgress

    /** No address for this host answered at all, so there was nothing to sample. */
    data class Unreachable(val detail: String) : LinkTestProgress
}

/** Progress of a Tier 2 throughput measurement. */
sealed interface ThroughputProgress {

    /** Opening the control connection to the iperf3 server. */
    data object Connecting : ThroughputProgress

    /** The transfer is running. */
    data class Running(
        val elapsedSeconds: Double,
        val totalSeconds: Int,
        val megabitsPerSecond: Double,
    ) : ThroughputProgress

    /** The transfer completed and the result is in. */
    data class Finished(val evidence: ThroughputEvidence) : ThroughputProgress

    /**
     * The test did not produce a number.
     *
     * @property reason which of the actionable failures this was.
     * @property detail the underlying message, for the small print.
     */
    data class Failed(val reason: ThroughputFailure, val detail: String) : ThroughputProgress
}

/**
 * Measures the network path to a host.
 *
 * Declared here, in `data`, and implemented in the protocol layer — the same stub-then-swap seam
 * every other network capability in this app uses, so the UI never imports a socket.
 *
 * **Neither method may touch the host's HTTPS port.** A Sunshine-family host leaks a socket on
 * every TLS connection and serves them from a single thread, so a measurement loop pointed at
 * port 47984 would degrade the very thing it was measuring. Tier 1 therefore paces small plaintext
 * requests, and Tier 2 talks to a completely separate program the user starts themselves.
 */
interface ConnectionTester {

    /**
     * Times a bounded burst of small requests to the host's plaintext control port.
     *
     * @param host the host to measure.
     * @param samples how many round trips to time.
     * @return a cold flow: collecting starts the burst, cancelling stops it.
     */
    fun measureLink(host: KnownHost, samples: Int = DEFAULT_LINK_SAMPLES): Flow<LinkTestProgress>

    /**
     * Runs a throughput test against an `iperf3` server the user has started on the host PC.
     *
     * @param host the host to measure.
     * @param port the iperf3 control port.
     * @param mode which kind of test to run.
     * @param seconds how long the transfer should last.
     * @param targetMbps the rate to drive [ThroughputMode.PACED_UDP] at; ignored by the TCP mode.
     * @return a cold flow: collecting starts the test, cancelling tears the sockets down.
     */
    fun measureThroughput(
        host: KnownHost,
        port: Int,
        mode: ThroughputMode,
        seconds: Int,
        targetMbps: Double,
    ): Flow<ThroughputProgress>

    companion object {
        /**
         * How many round trips a link measurement times.
         *
         * Twenty is enough for a median and a p95 that are not dominated by one unlucky sample, and
         * few enough that at [LINK_SAMPLE_SPACING_MS] apart the whole thing is over in about two
         * seconds and costs the host twenty small plaintext requests.
         */
        const val DEFAULT_LINK_SAMPLES: Int = 20

        /** Gap between samples. Paced, so the burst can never look like a flood to the host. */
        const val LINK_SAMPLE_SPACING_MS: Long = 100L

        /** Per-sample timeout. Beyond this the sample is loss, not latency. */
        const val LINK_SAMPLE_TIMEOUT_MS: Int = 2_000

        /** The port `iperf3 -s` listens on unless told otherwise. */
        const val DEFAULT_IPERF_PORT: Int = 5201

        /** Default transfer length: long enough to get past TCP slow start and Wi-Fi rate ramping. */
        const val DEFAULT_TEST_SECONDS: Int = 10
    }
}

/**
 * The [ConnectionTester] in place before the protocol layer is wired in.
 *
 * Reports honestly that it measured nothing, so previews and unit tests exercise the real empty
 * and failed states rather than fabricated good news.
 */
object StubConnectionTester : ConnectionTester {

    override fun measureLink(host: KnownHost, samples: Int): Flow<LinkTestProgress> =
        flowOf(LinkTestProgress.Unreachable("Networking is not available in this build."))

    override fun measureThroughput(
        host: KnownHost,
        port: Int,
        mode: ThroughputMode,
        seconds: Int,
        targetMbps: Double,
    ): Flow<ThroughputProgress> = flowOf(
        ThroughputProgress.Failed(
            reason = ThroughputFailure.UNREACHABLE,
            detail = "Networking is not available in this build.",
        ),
    )
}
