package com.voidlink.android.protocol.enet

/**
 * Delivery guarantees a caller can ask for (`docs/01-PROTOCOL.md` §9.1, §9.5).
 *
 * The control stream needs all three: the periodic ping is reliable because the RTT estimate comes
 * from its acknowledgement, input and IDR requests are reliable because losing one is visible to
 * the user, and Sunshine's per-frame FEC status is unsequenced because a late report is worse than
 * a missing one.
 */
enum class EnetDelivery {
    /**
     * Reliable and ordered within its channel. Retransmitted until acknowledged, fragmented if it
     * exceeds the MTU, and delivered strictly in send order.
     */
    RELIABLE,

    /**
     * Sent once, delivered in order but with gaps. A packet that arrives behind a newer one on the
     * same channel is dropped rather than delivered late.
     */
    UNRELIABLE,

    /**
     * Sent once, delivered on arrival in whatever order. No sequencing state at either end.
     */
    UNSEQUENCED,
}

/**
 * The peer state machine (`docs/01-PROTOCOL.md` §9.1, §9.7).
 *
 * A reduction of ENet's ten states to the six this subset distinguishes. The states ENet keeps for
 * deferred event dispatch (`CONNECTION_PENDING`, `CONNECTION_SUCCEEDED`, `DISCONNECT_LATER`) have
 * no analogue here because [EnetHost] dispatches events as it produces them, and `ZOMBIE` collapses
 * into [DISCONNECTED] because we have exactly one peer and nothing to garbage-collect.
 */
enum class EnetPeerState {
    /** No connection. The initial state, and the state after a completed or failed teardown. */
    DISCONNECTED,

    /** A CONNECT has been queued or sent; waiting for VERIFY_CONNECT. */
    CONNECTING,

    /** Server side: a CONNECT arrived and VERIFY_CONNECT was sent; waiting for its acknowledgement. */
    ACKNOWLEDGING_CONNECT,

    /** The handshake completed. Data may flow in both directions. */
    CONNECTED,

    /** We sent a DISCONNECT and are waiting for its acknowledgement (spec §9.7 step 3). */
    DISCONNECTING,

    /** The peer sent us a DISCONNECT; we owe it an acknowledgement before we let go. */
    ACKNOWLEDGING_DISCONNECT,
}

/**
 * Something the ENet layer wants the caller to know about.
 *
 * Produced by [EnetPeer] as it processes commands and timers, and surfaced by [EnetHost] through
 * its state flow and inbound channel. Modelled as a sealed class rather than callbacks so the
 * service loop stays single-threaded: it appends events to a list and drains them at a point of
 * its choosing.
 */
sealed class EnetEvent {

    /** The handshake completed and the peer is usable. */
    class Connected(val peer: EnetPeer) : EnetEvent()

    /**
     * The connection ended.
     *
     * @property data the 32-bit word from the DISCONNECT command, or 0 when the peer simply
     *   stopped answering.
     * @property timedOut true when we gave up on an unacknowledged command rather than being told
     *   to go away. Spec §9.7 notes the host times an abandoned session out on its own, and this is
     *   the mirror image of that.
     */
    class Disconnected(val peer: EnetPeer, val data: Int, val timedOut: Boolean) : EnetEvent()

    /** The handshake did not complete within [EnetConfig.connectTimeoutMs], or was refused. */
    class ConnectFailed(val reason: String) : EnetEvent()

    /**
     * A payload arrived for the application.
     *
     * @property channelId the channel it arrived on — [EnetUnverifiedConstants.CHANNEL_GENERIC] or
     *   [EnetUnverifiedConstants.CHANNEL_URGENT] for GameStream.
     * @property payload the reassembled application bytes: a GameStream control message with the
     *   little-endian `{ uint16 type; uint16 payloadLength; }` header of spec §9.2, which this
     *   layer neither reads nor writes.
     */
    class Received(val channelId: Int, val payload: ByteArray) : EnetEvent()
}

/**
 * A payload handed to the application by [EnetHost.inbound].
 *
 * @property channelId the ENet channel it arrived on.
 * @property payload the reassembled bytes, owned by the receiver.
 */
class EnetInboundPacket(
    val channelId: Int,
    val payload: ByteArray,
)
