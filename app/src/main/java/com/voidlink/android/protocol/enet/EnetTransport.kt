package com.voidlink.android.protocol.enet

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * One received datagram.
 *
 * @property data the bytes, owned by the receiver — the transport does not reuse this array.
 * @property length how many of them are meaningful.
 * @property source who sent it. Checked against the peer address before anything is believed.
 */
class EnetDatagram(
    val data: ByteArray,
    val length: Int,
    val source: InetSocketAddress,
)

/**
 * The datagram socket [EnetHost] talks through.
 *
 * An interface for one reason: the loopback tests need to drop, duplicate and reorder datagrams to
 * prove that reliable delivery converges anyway, and doing that inside a decorator is the only way
 * to make loss deterministic. `04-ROADMAP.md` Phase 8 makes that test the gate for the whole
 * control stream, so the seam it needs is part of the design rather than a testing afterthought.
 *
 * Implementations are used from the single service-loop thread only.
 */
interface EnetTransport {

    /** The local port, once bound. Used by tests to aim one host at another. */
    val localPort: Int

    /** Sends the first [length] bytes of [data] to [destination]. Send failures propagate. */
    fun send(data: ByteArray, length: Int, destination: InetSocketAddress)

    /**
     * Blocks for at most [timeoutMs] waiting for a datagram.
     *
     * @return the datagram, or `null` on timeout. A timeout is the normal case: it is what gives
     *   the service loop its tick.
     */
    fun receive(timeoutMs: Int): EnetDatagram?

    /** Closes the socket, unblocking a concurrent [receive]. */
    fun close()
}

/**
 * [EnetTransport] over a plain [DatagramSocket] (`docs/01-PROTOCOL.md` §9.1: ENet over UDP 47999).
 *
 * `java.net` only, as required — nothing here needs an Android API, which is what lets the loopback
 * tests run on the JVM in CI where `02-ARCHITECTURE.md` §10 says the only executable tests live.
 *
 * @param socket the socket to own. Closing this transport closes it.
 * @param maximumDatagramSize receive buffer size; ENet never negotiates an MTU above
 *   [EnetProtocol.MAXIMUM_MTU], so anything larger is not ours.
 */
class DatagramEnetTransport(
    private val socket: DatagramSocket,
    private val maximumDatagramSize: Int = EnetProtocol.MAXIMUM_MTU,
) : EnetTransport {

    private val buffer = ByteArray(maximumDatagramSize)
    private var appliedTimeoutMs: Int = -1

    override val localPort: Int
        get() = socket.localPort

    override fun send(data: ByteArray, length: Int, destination: InetSocketAddress) {
        socket.send(DatagramPacket(data, 0, length, destination))
    }

    override fun receive(timeoutMs: Int): EnetDatagram? {
        if (appliedTimeoutMs != timeoutMs) {
            socket.soTimeout = timeoutMs
            appliedTimeoutMs = timeoutMs
        }
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            val source = packet.socketAddress as? InetSocketAddress ?: return null
            EnetDatagram(buffer.copyOf(packet.length), packet.length, source)
        } catch (timeout: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        socket.close()
    }
}
