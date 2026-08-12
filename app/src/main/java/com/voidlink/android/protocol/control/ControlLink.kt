package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.enet.EnetDelivery
import com.voidlink.android.protocol.enet.EnetHost
import com.voidlink.android.protocol.enet.EnetInboundPacket
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The transport [ControlStream] writes GameStream messages onto — architecture §5.3's
 * `ControlTransport`.
 *
 * Exactly the four operations the control stream needs from ENet, and nothing else. It exists so
 * that the whole of `protocol/control/` — framing, the start sequence, the ping cadence, the IDR
 * rate limiter, host-feedback dispatch — is testable without a socket, while
 * [EnetControlLink] keeps the production path a fifteen-line adapter over the real
 * [EnetHost]. `protocol/enet` is deliberately unaware of this interface: it moves opaque payloads
 * on numbered channels and knows nothing about what they mean.
 */
interface ControlLink {

    /**
     * Payloads from the host, in per-channel delivery order.
     *
     * Closed when the transport stops, which is what ends [ControlStream]'s inbound pump.
     */
    val inbound: ReceiveChannel<EnetInboundPacket>

    /**
     * How many channels the peer negotiated, or `null` when that is not known yet.
     *
     * Spec §9.1 asks for three; a peer that granted fewer is why
     * [ControlStream] falls back to channel 0 rather than sending into a channel that does not
     * exist.
     */
    val negotiatedChannelCount: Int?

    /** Queues one framed control message. Returns whether it was accepted for sending. */
    fun send(channelId: Int, payload: ByteArray, delivery: EnetDelivery): Boolean

    /**
     * Sends an ENet DISCONNECT and pumps for its acknowledgement (spec §9.7 step 3).
     *
     * @return true when the remote acknowledged; false means the host will time the session out on
     *   its own.
     */
    suspend fun disconnect(lingerMs: Long): Boolean
}

/**
 * [ControlLink] over the real ENet host.
 *
 * Owns nothing: the session opened the [EnetHost] and the session closes it, because the video and
 * control teardown order of spec §9.7 is the session's to enforce.
 */
class EnetControlLink(private val host: EnetHost) : ControlLink {

    override val inbound: ReceiveChannel<EnetInboundPacket>
        get() = host.inbound

    override val negotiatedChannelCount: Int?
        get() = host.peer?.channelCount

    override fun send(channelId: Int, payload: ByteArray, delivery: EnetDelivery): Boolean =
        host.send(channelId, payload, delivery)

    override suspend fun disconnect(lingerMs: Long): Boolean = host.disconnect(lingerMs = lingerMs)
}
