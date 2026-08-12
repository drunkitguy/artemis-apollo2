package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.enet.EnetDelivery
import com.voidlink.android.protocol.enet.EnetInboundPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [ControlLink] that records what was written and lets a test hand back what the host "sent".
 *
 * The reason [ControlLink] exists: with it, the framing, the channel discipline, the ping cadence,
 * the IDR rate limiter and the host-feedback dispatch are all testable in-process and
 * deterministically. `protocol/enet` already proves the transport on a real loopback socket with
 * fault injection; repeating that here would test ENet a second time and the control stream not at
 * all.
 */
class FakeControlLink(
    override var negotiatedChannelCount: Int? = 3,
    private val acceptSends: Boolean = true,
) : ControlLink {

    /** One recorded write. */
    class Sent(val channelId: Int, val payload: ByteArray, val delivery: EnetDelivery) {
        /** The framed bytes as hex, which is how the fixtures read. */
        fun hex(): String = Hex.encode(payload)
    }

    /**
     * Everything written, in order.
     *
     * Copy-on-write because the session's pumps write from `Dispatchers.IO` while the test thread
     * reads: a plain `ArrayList` here makes the assertions flaky in a way that looks like a bug in
     * the code under test.
     */
    val sent: MutableList<Sent> = CopyOnWriteArrayList()

    /** How many times [disconnect] was called, and with what linger. */
    @Volatile
    var disconnectCount: Int = 0
        private set

    @Volatile
    var lastLingerMs: Long = -1L
        private set

    /** What [disconnect] reports; false is "the host never acknowledged". */
    @Volatile
    var disconnectAcknowledged: Boolean = true

    private val incoming = Channel<EnetInboundPacket>(Channel.UNLIMITED)

    override val inbound: ReceiveChannel<EnetInboundPacket> = incoming

    override fun send(channelId: Int, payload: ByteArray, delivery: EnetDelivery): Boolean {
        if (!acceptSends) return false
        sent += Sent(channelId, payload, delivery)
        return true
    }

    override suspend fun disconnect(lingerMs: Long): Boolean {
        disconnectCount++
        lastLingerMs = lingerMs
        return disconnectAcknowledged
    }

    /** Delivers a framed control message as if the host had sent it. */
    fun deliver(payload: ByteArray, channelId: Int = 0) {
        incoming.trySend(EnetInboundPacket(channelId, payload))
    }

    /** Closes the inbound channel, which is how a real transport signals that ENet stopped. */
    fun closeInbound() {
        incoming.close()
    }
}
