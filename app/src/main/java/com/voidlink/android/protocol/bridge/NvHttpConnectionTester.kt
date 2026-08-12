package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.ConnectionTester
import com.voidlink.android.data.KnownHost
import com.voidlink.android.data.LinkQuality
import com.voidlink.android.data.LinkSample
import com.voidlink.android.data.LinkTestProgress
import com.voidlink.android.data.ThroughputEvidence
import com.voidlink.android.data.ThroughputFailure
import com.voidlink.android.data.ThroughputMode
import com.voidlink.android.data.ThroughputProgress
import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.netperf.Iperf3Client
import com.voidlink.android.protocol.netperf.Iperf3Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The real [ConnectionTester], replacing the stub the UI shipped against.
 *
 * Two tiers, because the two questions need different amounts of the user's cooperation:
 *
 * * **Tier 1 — link quality.** Works against any host with no setup, by timing a paced burst of
 *   ordinary `/serverinfo` requests. It measures latency, jitter and failure rate, which between
 *   them predict stutter better than a bandwidth figure does.
 * * **Tier 2 — throughput.** Needs `iperf3 -s` running on the PC, and in exchange gives a real
 *   measurement of the path video would take.
 *
 * ### What this must never do
 *
 * **Tier 1 uses the plaintext port only.** A Sunshine/Apollo host leaks a socket on every HTTPS
 * connection — they pile up in `CLOSE_WAIT` until its process restarts — and serves the secure
 * port from a single thread, so a measurement loop pointed at 47984 would progressively break the
 * host it was measuring. Bulk-fetching box art to estimate bandwidth is the same mistake wearing a
 * different hat and is equally forbidden. Twenty small plaintext requests spaced 100 ms apart is
 * the whole budget, and it is paced rather than concurrent so it can never look like a flood.
 *
 * @param client the NVHTTP transport; its per-host serialisation is reused rather than bypassed.
 * @param resolver picks the address that currently answers.
 * @param iperf the iperf3 client used by tier 2.
 */
class NvHttpConnectionTester(
    private val client: NvHttpClient,
    private val resolver: HostEndpointResolver,
    private val iperf: Iperf3Client = Iperf3Client(),
) : ConnectionTester {

    override fun measureLink(host: KnownHost, samples: Int): Flow<LinkTestProgress> = flow {
        val resolved = resolver.resolve(host, ConnectionTester.LINK_SAMPLE_TIMEOUT_MS)
        if (resolved == null) {
            emit(
                LinkTestProgress.Unreachable(
                    "${host.name} did not answer on any address this device knows about.",
                ),
            )
            return@flow
        }

        val address = resolved.address
        val collected = ArrayList<LinkSample>(samples)
        for (index in 0 until samples) {
            // Paced, not concurrent. The host answers plaintext requests one at a time, so firing
            // these together would measure our own queue rather than the network — and would be
            // exactly the kind of burst this app has spent a lot of effort learning not to send.
            if (index > 0) delay(ConnectionTester.LINK_SAMPLE_SPACING_MS)
            val startedAt = System.nanoTime()
            val result = client.serverInfoPlain(address, ConnectionTester.LINK_SAMPLE_TIMEOUT_MS)
            val elapsedMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
            val sample = if (result.isSuccess) {
                LinkSample(roundTripMs = elapsedMs)
            } else {
                LinkSample(roundTripMs = null, failure = result.errorDescription())
            }
            collected.add(sample)
            emit(LinkTestProgress.Sampled(collected.size, samples, sample.roundTripMs))
        }
        emit(LinkTestProgress.Finished(LinkQuality.from(collected)))
    }

    override fun measureThroughput(
        host: KnownHost,
        port: Int,
        mode: ThroughputMode,
        seconds: Int,
        targetMbps: Double,
    ): Flow<ThroughputProgress> = flow {
        emit(ThroughputProgress.Connecting)

        val address = addressFor(host)
        if (address == null) {
            emit(
                ThroughputProgress.Failed(
                    reason = ThroughputFailure.UNREACHABLE,
                    detail = "No usable address is stored for ${host.name}.",
                ),
            )
            return@flow
        }

        val udp = mode == ThroughputMode.PACED_UDP
        val targetBits = (targetMbps * BITS_PER_MEGABIT)
            .toLong()
            .coerceIn(MIN_TARGET_BITS_PER_SECOND, MAX_TARGET_BITS_PER_SECOND)

        ProtocolLog.i(
            ProtocolLog.TAG_NETPERF,
            "iperf3 ${if (udp) "UDP" else "TCP"} reverse test to $address:$port for ${seconds}s" +
                (if (udp) " at $targetBits bit/s" else ""),
        )

        val outcome = iperf.run(
            hostAddress = address,
            port = port,
            udp = udp,
            seconds = seconds,
            targetBitsPerSecond = targetBits,
        ) { elapsedSeconds, megabitsPerSecond ->
            emit(ThroughputProgress.Running(elapsedSeconds, seconds, megabitsPerSecond))
        }

        emit(toProgress(outcome, mode, targetMbps))
    }.flowOn(Dispatchers.IO)

    /**
     * The bare host to point iperf3 at.
     *
     * The stored primary address is tried first because it is the one that most recently worked and
     * costs nothing to use; resolving is only worth a round trip when there is no stored address at
     * all. Any `:port` in the stored string belongs to the NVHTTP listener, not to iperf3, so only
     * the host part is taken.
     */
    private suspend fun addressFor(host: KnownHost): String? {
        HostAddress.parse(host.primaryAddress)?.let { return it.host }
        return resolver.resolve(host, ConnectionTester.LINK_SAMPLE_TIMEOUT_MS)?.address?.host
    }

    private fun toProgress(
        outcome: Iperf3Outcome,
        mode: ThroughputMode,
        targetMbps: Double,
    ): ThroughputProgress = when (outcome) {
        is Iperf3Outcome.Success -> when {
            // A clean handshake that carried nothing is not a measurement of zero bandwidth — it
            // means the data path never came up, which on a paced UDP test usually means something
            // between here and the PC drops UDP.
            outcome.bytes <= 0L || outcome.seconds <= 0.0 -> ThroughputProgress.Failed(
                reason = ThroughputFailure.PROTOCOL_MISMATCH,
                detail = "The iperf3 test finished but no data arrived. If this was the UDP test, " +
                    "something on the network is dropping UDP; try the TCP test.",
            )

            mode == ThroughputMode.PACED_UDP -> ThroughputProgress.Finished(
                ThroughputEvidence.Loaded(
                    targetMbps = targetMbps,
                    receivedMbps = outcome.megabitsPerSecond,
                    lossPercent = outcome.lossPercent,
                    jitterMs = outcome.jitterMs,
                    packets = outcome.packets,
                ),
            )

            else -> ThroughputProgress.Finished(
                ThroughputEvidence.Sustained(
                    megabitsPerSecond = outcome.megabitsPerSecond,
                    bytes = outcome.bytes,
                    seconds = outcome.seconds,
                ),
            )
        }

        is Iperf3Outcome.NotRunning ->
            ThroughputProgress.Failed(ThroughputFailure.SERVER_NOT_RUNNING, outcome.detail)

        is Iperf3Outcome.Busy ->
            ThroughputProgress.Failed(ThroughputFailure.SERVER_BUSY, outcome.detail)

        is Iperf3Outcome.Unreachable ->
            ThroughputProgress.Failed(ThroughputFailure.UNREACHABLE, outcome.detail)

        is Iperf3Outcome.Mismatch ->
            ThroughputProgress.Failed(ThroughputFailure.PROTOCOL_MISMATCH, outcome.detail)

        is Iperf3Outcome.ServerFailed ->
            ThroughputProgress.Failed(ThroughputFailure.SERVER_ERROR, outcome.detail)

        is Iperf3Outcome.TimedOut ->
            ThroughputProgress.Failed(ThroughputFailure.TIMED_OUT, outcome.detail)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val BITS_PER_MEGABIT = 1_000_000.0

        /** Below this the paced test measures rounding rather than the link. */
        const val MIN_TARGET_BITS_PER_SECOND = 1_000_000L

        /** Above this a phone's radio, not the network, is what would be measured. */
        const val MAX_TARGET_BITS_PER_SECOND = 1_000_000_000L
    }
}
