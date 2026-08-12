package com.voidlink.android.protocol.enet

import com.voidlink.android.protocol.ProtocolLog

/**
 * Values `docs/01-PROTOCOL.md` §9.1 states outright for the GameStream control channel.
 *
 * These belong in `protocol/ProtocolConstants.kt` alongside every other protocol constant, and
 * should be folded into it. They live here only because this package was written while that file
 * was owned by another change; nothing outside `protocol/enet/` reads them yet, so the move is a
 * cut-and-paste with no call-site churn.
 */
object EnetControlConstants {

    /** Logcat tag for the ENet subsystem, matching `02-ARCHITECTURE.md` §9 (`VL.Enet`). */
    const val TAG: String = "VL.Enet"

    /** Default control port (spec §0.4); the real one comes from `SETUP streamid=control`. */
    const val DEFAULT_CONTROL_PORT: Int = 47999

    /** "Channel count: 3" (spec §9.1). Two are used; the third is offered because the spec says so. */
    const val CHANNEL_COUNT: Int = 3

    /** `CONTROL_STREAM_TIMEOUT_SEC` — 10 s to complete the ENet handshake (spec §9.1). */
    const val CONNECT_TIMEOUT_MS: Int = 10_000

    /** `CONTROL_STREAM_LINGER_TIMEOUT_SEC` — pump for the disconnect ACK for 2 s (spec §9.7). */
    const val LINGER_TIMEOUT_MS: Int = 2_000
}

/**
 * ENet decisions the spec explicitly marks **UNVERIFIED**, plus the deliberate divergences from
 * stock ENet that this subset makes.
 *
 * Collected in one object for the same reason as `UnverifiedProtocolConstants`: the guessed surface
 * has to stay countable, and a debugging session against a real host needs one file to experiment
 * in. Every entry names the spec section that flags it and what goes wrong if the guess is wrong.
 *
 * Each is announced through [ProtocolLog.unverified] the first time the code depends on it; see
 * [announce].
 */
object EnetUnverifiedConstants {

    /**
     * Channel carrying periodic pings and FEC status (spec §9.1: `CTRL_CHANNEL_GENERIC`).
     *
     * UNVERIFIED(spec 01 §9.1, consolidated item 4): "the exact numeric channel ids. They are small
     * integers (0 and 1). Implement as `GENERIC = 0`, `URGENT = 1` and log; if the host ignores our
     * messages, swapping them is the first thing to try."
     * Risk if wrong: messages ignored or mis-prioritised — a silent failure, not a crash.
     */
    const val CHANNEL_GENERIC: Int = 0

    /**
     * Channel carrying input, IDR requests and termination (spec §9.1: `CTRL_CHANNEL_URGENT`).
     *
     * @see CHANNEL_GENERIC for the UNVERIFIED note that covers both ids.
     */
    const val CHANNEL_URGENT: Int = 1

    /**
     * Whether every datagram we send carries the 16-bit send time.
     *
     * Stock ENet sets the flag only when the datagram contains a reliable command; a receiver that
     * follows ENet refuses to acknowledge a reliable command that arrived without one. Sending it
     * unconditionally costs two bytes per datagram, cannot make an acknowledgement impossible, and
     * removes an entire class of "the host never ACKs us" failure from the table.
     *
     * Not UNVERIFIED so much as a deliberate divergence, recorded here because it is exactly the
     * kind of thing that is invisible until someone diffs our traffic against a real client's.
     */
    const val ALWAYS_SEND_SENT_TIME: Boolean = true

    /**
     * Whether to drop datagrams whose session id does not match the negotiated one.
     *
     * ENet uses the two session bits to reject packets from a previous connection that reused the
     * same address and port. Dropping on mismatch is what stock ENet does; we default to accepting,
     * because the failure mode of a mismatch we computed wrongly is a control channel that silently
     * receives nothing, and the failure mode of not checking is accepting a stale packet on a port
     * we only ever use for one session.
     */
    const val VALIDATE_SESSION_ID: Boolean = false

    /**
     * Logs, once per process, that the ENet layer is running on assumed values.
     *
     * Called from [EnetHost.connect] because every one of these takes effect the moment a
     * connection is attempted.
     */
    fun announce() {
        ProtocolLog.unverified(
            EnetControlConstants.TAG,
            "enet-channel-ids",
            "control channels GENERIC=$CHANNEL_GENERIC URGENT=$CHANNEL_URGENT of " +
                "${EnetControlConstants.CHANNEL_COUNT} (spec 01 §9.1); swap them first if the " +
                "host ignores our messages",
        )
        ProtocolLog.unverified(
            EnetControlConstants.TAG,
            "enet-always-sent-time",
            "every datagram carries the ENet sent-time field (stock ENet sends it only with " +
                "reliable commands); costs 2 bytes, guarantees our reliable commands are ACKable",
        )
        if (!VALIDATE_SESSION_ID) {
            ProtocolLog.unverified(
                EnetControlConstants.TAG,
                "enet-session-id-unchecked",
                "incoming datagrams are accepted regardless of ENet session id; stock ENet drops " +
                    "on mismatch",
            )
        }
    }
}

/**
 * A millisecond clock for the ENet layer.
 *
 * ENet's timers are 32-bit millisecond counters that are allowed to wrap; every comparison in
 * [EnetPeer] is written to survive that, so the only requirement on an implementation is that it
 * be monotonic. Injectable because retransmission is the part of this protocol most worth testing
 * and least worth testing with real sleeps.
 */
fun interface EnetClock {
    /** The current time in milliseconds. Wrapping is expected and handled by callers. */
    fun nowMs(): Int
}

/**
 * The default clock: `System.nanoTime()` in milliseconds.
 *
 * Monotonic, unaffected by wall-clock adjustments, and wraps roughly every 49 days — which the
 * wrapping comparisons in [EnetPeer] absorb.
 */
object SystemEnetClock : EnetClock {
    override fun nowMs(): Int = (System.nanoTime() / 1_000_000L).toInt()
}

/**
 * Tunables for [EnetHost] and [EnetPeer] (`docs/01-PROTOCOL.md` §9.1).
 *
 * Defaults reproduce ENet's own, with the GameStream-specific values the spec pins: three channels,
 * one peer, no bandwidth throttling, a 10 s connect timeout.
 *
 * @property channelCount channels to negotiate. Spec §9.1: three.
 * @property mtu the MTU we offer in CONNECT; the negotiated value is the smaller of the two.
 * @property windowSize the window we offer. We do not implement throttling, so we ask for the
 *   maximum and let the host cap it.
 * @property serviceIntervalMs how long the service loop blocks on the socket before running timers.
 *   Bounds both retransmission granularity and how quickly [EnetHost.run] notices cancellation.
 * @property pingIntervalMs ENet-level keep-alive interval (spec §9.1 "Ping/timeout handling").
 * @property initialRoundTripTimeMs the RTT assumed before an acknowledgement measures one; the
 *   first retransmission timeout is derived from it.
 * @property connectTimeoutMs how long the handshake may take. Spec §9.1: `CONTROL_STREAM_TIMEOUT_SEC`.
 * @property timeoutLimit multiplier on the initial RTO giving the ceiling past which a peer that
 *   has stopped acknowledging is declared dead.
 * @property timeoutMinimumMs never declare a peer dead sooner than this.
 * @property timeoutMaximumMs always declare a peer dead by this point.
 * @property acceptIncomingConnections whether an inbound CONNECT creates a peer. False for the
 *   client; true for the minimal server peer the loopback tests run against.
 * @property maximumPacketSize largest reassembled payload accepted, a bound on what a hostile or
 *   confused sender can make us allocate.
 * @property maximumQueuedSendBytes cap on payload bytes queued but not yet handed to the socket.
 *   Without a throttle something has to say no; this does.
 */
data class EnetConfig(
    val channelCount: Int = EnetControlConstants.CHANNEL_COUNT,
    val mtu: Int = EnetProtocol.DEFAULT_MTU,
    val windowSize: Int = EnetProtocol.MAXIMUM_WINDOW_SIZE,
    val serviceIntervalMs: Int = 10,
    val pingIntervalMs: Int = EnetProtocol.PING_INTERVAL_MS,
    val initialRoundTripTimeMs: Int = EnetProtocol.DEFAULT_ROUND_TRIP_TIME_MS,
    val connectTimeoutMs: Int = EnetControlConstants.CONNECT_TIMEOUT_MS,
    val timeoutLimit: Int = EnetProtocol.TIMEOUT_LIMIT,
    val timeoutMinimumMs: Int = EnetProtocol.TIMEOUT_MINIMUM_MS,
    val timeoutMaximumMs: Int = EnetProtocol.TIMEOUT_MAXIMUM_MS,
    val acceptIncomingConnections: Boolean = false,
    val maximumPacketSize: Int = 1 shl 20,
    val maximumQueuedSendBytes: Int = 1 shl 20,
) {
    init {
        require(channelCount in EnetProtocol.MINIMUM_CHANNEL_COUNT..EnetProtocol.MAXIMUM_CHANNEL_COUNT) {
            "channelCount $channelCount outside ENet's 1..255"
        }
        require(mtu in EnetProtocol.MINIMUM_MTU..EnetProtocol.MAXIMUM_MTU) {
            "mtu $mtu outside ENet's ${EnetProtocol.MINIMUM_MTU}..${EnetProtocol.MAXIMUM_MTU}"
        }
        require(serviceIntervalMs > 0) { "serviceIntervalMs must be positive" }
        require(initialRoundTripTimeMs > 0) { "initialRoundTripTimeMs must be positive" }
    }

    /**
     * Largest payload a single [EnetCommand.SendFragment] may carry.
     *
     * ENet's own definition: the MTU less a protocol header and the fragment command struct. Every
     * fragment but the last is exactly this size, so both ends must compute it identically.
     */
    val fragmentLength: Int
        get() = mtu - EnetProtocol.PROTOCOL_HEADER_SIZE_WITH_SENT_TIME - EnetProtocol.SEND_FRAGMENT_SIZE
}
