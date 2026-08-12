package com.voidlink.android.protocol.enet

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The peer state machine and its retransmission timers (`docs/01-PROTOCOL.md` §9.1).
 *
 * Driven with [MutableEnetClock], so every timing assertion is exact and no test sleeps. The RTO
 * ladder in particular is worth pinning: `04-ROADMAP.md` Risk 3 warns that "a subtly wrong
 * retransmit timer produces input lag that only shows up under loss", which is precisely the kind
 * of defect that no amount of running against a real host on a good network will reveal.
 */
class EnetPeerTest {

    private val clock = MutableEnetClock()
    private val events = ArrayList<EnetEvent>()
    private val address = InetSocketAddress(
        InetAddress.getLoopbackAddress(),
        EnetControlConstants.DEFAULT_CONTROL_PORT,
    )

    private fun peer(config: EnetConfig = EnetPeerLink.testConfig()): EnetPeer =
        EnetPeer(address, config, incomingPeerId = 0, connectId = 0x1A2B3C4D)

    // ---- The CONNECT datagram ------------------------------------------------------------------

    @Test
    fun `the connect datagram matches its hex representation byte for byte`() {
        val peer = peer()
        peer.startConnect(clock.timeMs, connectData = 0x0BADF00D)

        val datagrams = peer.service(clock.timeMs, events)
        assertEquals(1, datagrams.size)
        assertEquals(
            // Protocol header: peer id 0x0FFF (unassigned), session 0, sent-time flag, t = 0.
            "8fff" + "0000" +
                // CONNECT | ACKNOWLEDGE, system channel, peer-level reliable sequence 1.
                "82" + "ff" + "0001" +
                "0000" + // outgoingPeerId: stamp datagrams to us with peer id 0
                "ffff" + // both session ids unset, as ENet's first CONNECT sends them
                "00000578" + // mtu 1400
                "00010000" + // windowSize 65536
                "00000003" + // channelCount 3 (spec §9.1)
                "00000000" + "00000000" + // no bandwidth limits (spec §9.1)
                "00001388" + "00000002" + "00000002" +
                "1a2b3c4d" + // connectId
                "0badf00d", // X-SS-Connect-Data (spec §6.3)
            Hex.encode(datagrams[0]),
        )
        assertEquals(EnetPeerState.CONNECTING, peer.state)
    }

    @Test
    fun `the connect datagram never sets the compression flag`() {
        // 0x0FFF with ENet's initial 0xFF session id would encode as 0xFFFF, which any receiver
        // reads as "compressed" and drops. This is the assertion that keeps that from coming back.
        val peer = peer()
        peer.startConnect(clock.timeMs, connectData = 0)
        val datagram = peer.service(clock.timeMs, events).single()

        val decoded = requireNotNull(EnetPacketCodec.decode(datagram))
        assertFalse(decoded.header.isCompressed)
        assertTrue(decoded.header.hasSentTime)
        assertEquals(EnetProtocol.MAXIMUM_PEER_ID, decoded.header.peerId)
        assertTrue(decoded.header.sessionId <= EnetProtocol.MAXIMUM_SESSION_ID)
    }

    // ---- Retransmission --------------------------------------------------------------------

    @Test
    fun `an unacknowledged command is retransmitted once its timeout expires and not before`() {
        val config = EnetPeerLink.testConfig()
        val peer = peer(config)
        peer.startConnect(clock.timeMs, connectData = 0)
        val first = peer.service(clock.timeMs, events).single()

        // The initial timeout is rtt + 4 * variance; variance starts at zero.
        clock.timeMs = config.initialRoundTripTimeMs - 1
        assertTrue(peer.service(clock.timeMs, events).isEmpty())

        clock.timeMs = config.initialRoundTripTimeMs
        val retransmitted = peer.service(clock.timeMs, events).single()

        // Same command, fresh send time: the header's 16-bit clock is what measures the round trip,
        // so a retransmission that reused the original would poison the estimate.
        assertEquals(commandBytes(first), commandBytes(retransmitted))
        assertEquals("0000", sentTimeHex(first))
        assertEquals("0028", sentTimeHex(retransmitted)) // 40 ms
    }

    @Test
    fun `the retransmission timeout doubles on every attempt`() {
        val config = EnetPeerLink.testConfig()
        val rto = config.initialRoundTripTimeMs
        val peer = peer(config)
        peer.startConnect(clock.timeMs, connectData = 0)
        assertEquals(1, peer.service(clock.timeMs, events).size)

        val sendTimes = ArrayList<Int>()
        // Step one millisecond at a time so the exact moment of each retransmission is observed.
        for (time in 1..(rto * 16)) {
            clock.timeMs = time
            if (peer.service(clock.timeMs, events).isNotEmpty()) sendTimes.add(time)
        }

        // 40, then +80, then +160, then +320: 40, 120, 280, 600.
        assertEquals(listOf(rto, rto * 3, rto * 7, rto * 15), sendTimes)
    }

    @Test
    fun `a peer that never answers is declared dead once the ladder is exhausted`() {
        val config = EnetPeerLink.testConfig().copy(
            initialRoundTripTimeMs = 40,
            timeoutLimit = 4,
            timeoutMinimumMs = 200,
            timeoutMaximumMs = 2_000,
            connectTimeoutMs = 10_000,
        )
        val peer = peer(config)
        peer.startConnect(clock.timeMs, connectData = 0)
        peer.service(clock.timeMs, events)

        var declaredAt = -1
        for (time in 1..1_000) {
            clock.timeMs = time
            peer.service(clock.timeMs, events)
            if (events.any { it is EnetEvent.Disconnected }) {
                declaredAt = time
                break
            }
        }

        // rto 40 -> 80 -> 160; at t=280 the timeout is at its 160 ms ceiling and the oldest
        // unacknowledged command has been outstanding past the 200 ms minimum.
        assertEquals(280, declaredAt)
        val disconnected = events.filterIsInstance<EnetEvent.Disconnected>().single()
        assertTrue(disconnected.timedOut)
        assertEquals(EnetPeerState.DISCONNECTED, peer.state)
    }

    @Test
    fun `the handshake gives up after the connect timeout even with the ladder still climbing`() {
        // Spec §9.1: CONTROL_STREAM_TIMEOUT_SEC. Without this the generic dead-peer machinery would
        // keep a doomed handshake alive for half a minute behind a spinner.
        val config = EnetPeerLink.testConfig().copy(connectTimeoutMs = 500)
        val peer = peer(config)
        peer.startConnect(clock.timeMs, connectData = 0)
        peer.service(clock.timeMs, events)

        clock.timeMs = 499
        peer.service(clock.timeMs, events)
        assertTrue(events.none { it is EnetEvent.ConnectFailed })
        assertEquals(EnetPeerState.CONNECTING, peer.state)

        clock.timeMs = 500
        peer.service(clock.timeMs, events)
        assertEquals(1, events.filterIsInstance<EnetEvent.ConnectFailed>().size)
        assertEquals(EnetPeerState.DISCONNECTED, peer.state)
    }

    @Test
    fun `sending before the handshake completes is refused rather than queued`() {
        val peer = peer()
        assertFalse(peer.send(EnetUnverifiedConstants.CHANNEL_GENERIC, byteArrayOf(1), EnetDelivery.RELIABLE))
        peer.startConnect(clock.timeMs, connectData = 0)
        assertFalse(peer.send(EnetUnverifiedConstants.CHANNEL_GENERIC, byteArrayOf(1), EnetDelivery.RELIABLE))
    }

    // ---- Round-trip estimate -------------------------------------------------------------------

    @Test
    fun `the round trip estimate moves toward the measured time`() {
        val link = EnetPeerLink(EnetPeerLink.testConfig())
        link.startConnect()
        assertTrue(link.runUntil { link.bothConnected() })

        // Starts at the assumed 40 ms and converges downward on a link where a hop costs 10 ms.
        val initial = link.client.roundTripTimeMs
        link.run(400)
        assertTrue(
            "expected the estimate to fall from $initial, got ${link.client.roundTripTimeMs}",
            link.client.roundTripTimeMs < initial,
        )
        assertTrue(link.client.roundTripTimeMs >= 1)
    }

    // ---- Keep-alive ----------------------------------------------------------------------------

    @Test
    fun `a connected peer pings on the configured interval`() {
        val config = EnetPeerLink.testConfig()
        val link = EnetPeerLink(config)
        link.startConnect()
        assertTrue(link.runUntil { link.bothConnected() })
        link.clientSentCommands.clear()

        link.run(steps = config.pingIntervalMs / 10 + 5)

        val pings = link.clientSentCommands.count { it.header.commandId == EnetProtocol.COMMAND_PING }
        assertTrue("expected at least one ENet PING, sent $pings", pings >= 1)
        val ping = link.clientSentCommands.first { it.header.commandId == EnetProtocol.COMMAND_PING }
        assertEquals(EnetProtocol.CHANNEL_ID_SYSTEM, ping.header.channelId)
        assertTrue(ping.header.requiresAcknowledgement)
    }

    // ---- Teardown ------------------------------------------------------------------------------

    @Test
    fun `disconnecting before the handshake completes needs no acknowledgement`() {
        val peer = peer()
        peer.startConnect(clock.timeMs, connectData = 0)
        peer.startDisconnect(data = 0)
        assertEquals(EnetPeerState.DISCONNECTED, peer.state)
        assertTrue(peer.service(clock.timeMs, events).isEmpty())
    }

    @Test
    fun `a peer with no connection produces nothing`() {
        val peer = peer()
        assertTrue(peer.service(clock.timeMs, events).isEmpty())
        assertEquals(EnetControlConstants.CHANNEL_COUNT, peer.channels.size)
    }

    /** The datagram minus its four-byte protocol header, so two sends can be compared. */
    private fun commandBytes(datagram: ByteArray): String =
        Hex.encode(datagram, EnetProtocol.PROTOCOL_HEADER_SIZE_WITH_SENT_TIME)

    /** The 16-bit send time from a datagram's protocol header. */
    private fun sentTimeHex(datagram: ByteArray): String = Hex.encode(datagram, 2, 2)
}
