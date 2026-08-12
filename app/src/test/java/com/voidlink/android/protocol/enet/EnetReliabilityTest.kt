package com.voidlink.android.protocol.enet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `[CI]` gate of `04-ROADMAP.md` Phase 8, in its deterministic form: two peers over a link that
 * drops, duplicates, holds and reverses datagrams, asserting that reliable delivery converges and
 * ordering holds (`docs/01-PROTOCOL.md` §9.1).
 *
 * `EnetLoopbackTest` runs the same shape of test over real UDP sockets. This one runs it over a
 * link whose every fault is drawn from a seeded generator on a clock the test advances by hand, so
 * it is fast, reproducible, and able to inject far more loss than a loopback interface ever would.
 */
class EnetReliabilityTest {

    private val config = EnetPeerLink.testConfig()

    // ---- The handshake -------------------------------------------------------------------------

    @Test
    fun `the handshake completes and both sides agree on the negotiated parameters`() {
        val link = EnetPeerLink(config)
        link.startConnect(connectData = EnetPeerLink.CONNECT_DATA)

        assertTrue(link.runUntil { link.bothConnected() })

        val server = requireNotNull(link.server)
        assertEquals(EnetPeerState.CONNECTED, link.client.state)
        assertEquals(EnetPeerState.CONNECTED, server.state)
        assertEquals(EnetProtocol.DEFAULT_MTU, link.client.mtu)
        assertEquals(server.mtu, link.client.mtu)
        assertEquals(EnetControlConstants.CHANNEL_COUNT, link.client.channelCount)
        assertEquals(server.channelCount, link.client.channelCount)

        // Each side ends up addressing the other by the peer id that side asked for.
        assertEquals(server.incomingPeerId, link.client.outgoingPeerId)
        assertEquals(link.client.incomingPeerId, server.outgoingPeerId)

        // Session ids are exchanged, land inside the two bits the header reserves, and mirror.
        assertTrue(link.client.outgoingSessionId <= EnetProtocol.MAXIMUM_SESSION_ID)
        assertTrue(link.client.incomingSessionId <= EnetProtocol.MAXIMUM_SESSION_ID)
        assertEquals(server.incomingSessionId, link.client.outgoingSessionId)
        assertEquals(server.outgoingSessionId, link.client.incomingSessionId)

        assertEquals(1, link.clientEvents.filterIsInstance<EnetEvent.Connected>().size)
        assertEquals(1, link.serverEvents.filterIsInstance<EnetEvent.Connected>().size)
    }

    @Test
    fun `the host receives the connect data from the RTSP setup response`() {
        val link = EnetPeerLink(config)
        link.startConnect(connectData = EnetPeerLink.CONNECT_DATA)
        assertTrue(link.runUntil { link.bothConnected() })

        // Spec §6.3: X-SS-Connect-Data rides in the CONNECT command and nowhere else. Getting it
        // there is the only reason this layer knows the value exists.
        val connect = link.clientSentCommands.filterIsInstance<EnetCommand.Connect>().first()
        assertEquals(EnetPeerLink.CONNECT_DATA, connect.data)
        assertEquals(EnetPeerLink.CLIENT_CONNECT_ID, connect.connectId)
    }

    @Test
    fun `a handshake survives a link that loses most of it`() {
        val link = EnetPeerLink(
            config,
            EnetLinkFaults(dropPercent = 60, duplicatePercent = 20, holdPercent = 20, reorder = true),
        )
        link.startConnect()

        assertTrue("handshake never completed", link.runUntil(maxSteps = 4_000) { link.bothConnected() })
        assertTrue("the link dropped nothing, so this proved nothing", link.dropped > 0)
        // Whatever the link did to the handshake, it happened exactly once at each end.
        assertEquals(1, link.clientEvents.filterIsInstance<EnetEvent.Connected>().size)
        assertEquals(1, link.serverEvents.filterIsInstance<EnetEvent.Connected>().size)
    }

    // ---- Reliable delivery ---------------------------------------------------------------------

    @Test
    fun `reliable payloads arrive exactly once and in order on a clean link`() {
        val link = connectedLink(EnetLinkFaults())
        val sent = sendNumbered(link, count = 40)

        assertTrue(link.runUntil { link.serverPayloads.size >= sent.size })
        assertPayloadsInOrder(sent, link.serverPayloads)
    }

    @Test
    fun `reliable delivery converges and order holds through loss, duplication and reordering`() {
        val link = connectedLink(
            EnetLinkFaults(dropPercent = 30, duplicatePercent = 25, holdPercent = 25, reorder = true),
        )
        val sent = sendNumbered(link, count = 40)

        assertTrue(
            "only ${link.serverPayloads.size} of ${sent.size} payloads arrived",
            link.runUntil(maxSteps = 4_000) { link.serverPayloads.size >= sent.size },
        )
        // Exactly once: a duplicated datagram must not become a duplicated delivery.
        assertEquals(sent.size, link.serverPayloads.size)
        assertPayloadsInOrder(sent, link.serverPayloads)
        assertTrue("the link dropped nothing, so this proved nothing", link.dropped > 0)
        assertTrue("the link duplicated nothing, so this proved nothing", link.duplicated > 0)
        assertEquals(EnetPeerState.CONNECTED, link.client.state)
    }

    @Test
    fun `the two channels are independent ordered streams`() {
        val link = connectedLink(EnetLinkFaults(dropPercent = 20, holdPercent = 25, reorder = true))

        val generic = ArrayList<ByteArray>()
        val urgent = ArrayList<ByteArray>()
        for (index in 0 until 20) {
            val onGeneric = byteArrayOf(0, index.toByte())
            val onUrgent = byteArrayOf(1, index.toByte())
            generic.add(onGeneric)
            urgent.add(onUrgent)
            link.client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, onGeneric, EnetDelivery.RELIABLE)
            link.client.send(EnetUnverifiedConstants.CHANNEL_URGENT, onUrgent, EnetDelivery.RELIABLE)
        }

        assertTrue(link.runUntil(maxSteps = 4_000) { link.serverPayloads.size >= 40 })
        assertEquals(40, link.serverPayloads.size)
        // Interleaving between channels is allowed; ordering within each is not negotiable.
        assertPayloadsInOrder(generic, link.serverPayloads.filter { it[0].toInt() == 0 })
        assertPayloadsInOrder(urgent, link.serverPayloads.filter { it[0].toInt() == 1 })
    }

    // ---- Fragmentation -------------------------------------------------------------------------

    @Test
    fun `a payload larger than the MTU is fragmented and reassembled byte for byte`() {
        val link = connectedLink(EnetLinkFaults())
        val large = ByteArray(5_000) { ((it * 31) and 0xFF).toByte() }

        assertTrue(link.client.send(EnetUnverifiedConstants.CHANNEL_URGENT, large, EnetDelivery.RELIABLE))
        assertTrue(link.runUntil { link.serverPayloads.isNotEmpty() })

        assertEquals(1, link.serverPayloads.size)
        assertArrayEquals(large, link.serverPayloads[0])

        val fragments = link.clientSentCommands.filterIsInstance<EnetCommand.SendFragment>()
        assertTrue("expected more than one fragment, got ${fragments.size}", fragments.size > 1)
        assertEquals(large.size, fragments[0].totalLength)
    }

    @Test
    fun `fragments survive loss and reordering, and the packet behind them still arrives in order`() {
        val link = connectedLink(
            EnetLinkFaults(dropPercent = 30, duplicatePercent = 20, holdPercent = 25, reorder = true),
        )
        val large = ByteArray(4_500) { ((it * 7) and 0xFF).toByte() }
        val small = byteArrayOf(0x5A, 0x5A)

        assertTrue(link.client.send(EnetUnverifiedConstants.CHANNEL_URGENT, large, EnetDelivery.RELIABLE))
        assertTrue(link.client.send(EnetUnverifiedConstants.CHANNEL_URGENT, small, EnetDelivery.RELIABLE))

        assertTrue(link.runUntil(maxSteps = 4_000) { link.serverPayloads.size >= 2 })
        assertEquals(2, link.serverPayloads.size)
        // The fragmented packet was sent first, so it must be delivered first however its fragments
        // arrived — a reassembled packet takes its place at its start sequence number, not at the
        // sequence number of whichever fragment happened to complete it.
        assertArrayEquals(large, link.serverPayloads[0])
        assertArrayEquals(small, link.serverPayloads[1])
    }

    // ---- Unreliable and unsequenced --------------------------------------------------------------

    @Test
    fun `unsequenced payloads are delivered as they arrive and are never retransmitted`() {
        val link = connectedLink(EnetLinkFaults(dropPercent = 30))
        repeat(30) { index ->
            link.client.send(
                EnetUnverifiedConstants.CHANNEL_GENERIC,
                byteArrayOf(index.toByte()),
                EnetDelivery.UNSEQUENCED,
            )
        }

        link.run(200)
        // Spec §9.5 sends the Sunshine FEC report this way: losing one is the point, and nothing
        // may hold up a later report waiting for it.
        assertTrue(link.serverPayloads.isNotEmpty())
        assertTrue(link.serverPayloads.size < 30)
        assertEquals(0, link.clientSentCommands.count { it.header.commandId == EnetProtocol.COMMAND_SEND_FRAGMENT })
    }

    @Test
    fun `unreliable payloads are never delivered out of order`() {
        val link = connectedLink(EnetLinkFaults(holdPercent = 40, reorder = true))
        repeat(30) { index ->
            link.client.send(
                EnetUnverifiedConstants.CHANNEL_GENERIC,
                byteArrayOf(index.toByte()),
                EnetDelivery.UNRELIABLE,
            )
        }

        link.run(200)
        assertTrue(link.serverPayloads.isNotEmpty())
        val order = link.serverPayloads.map { it[0].toInt() }
        assertEquals("unreliable delivery must drop stale packets, not reorder them", order.sorted(), order)
        assertEquals(order.distinct(), order)
    }

    @Test
    fun `an oversized unreliable payload is refused instead of silently truncated`() {
        val link = connectedLink(EnetLinkFaults())
        val huge = ByteArray(config.fragmentLength + 1)
        assertFalse(link.client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, huge, EnetDelivery.UNRELIABLE))
        assertFalse(link.client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, huge, EnetDelivery.UNSEQUENCED))
        assertTrue(link.client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, huge, EnetDelivery.RELIABLE))
    }

    @Test
    fun `a send to a channel the connection does not have is refused`() {
        val link = connectedLink(EnetLinkFaults())
        assertFalse(
            link.client.send(EnetControlConstants.CHANNEL_COUNT, byteArrayOf(1), EnetDelivery.RELIABLE),
        )
    }

    // ---- Teardown ------------------------------------------------------------------------------

    @Test
    fun `disconnect is acknowledged and both sides end up disconnected`() {
        val link = connectedLink(EnetLinkFaults())
        link.client.startDisconnect(data = 0)

        assertTrue(
            link.runUntil {
                link.client.state == EnetPeerState.DISCONNECTED &&
                    link.server?.state == EnetPeerState.DISCONNECTED
            },
        )

        val clientDisconnect = link.clientEvents.filterIsInstance<EnetEvent.Disconnected>().single()
        assertFalse("a clean teardown must not look like a timeout", clientDisconnect.timedOut)
        val serverDisconnect = link.serverEvents.filterIsInstance<EnetEvent.Disconnected>().single()
        assertFalse(serverDisconnect.timedOut)
    }

    @Test
    fun `a disconnect gets through a lossy link`() {
        val link = connectedLink(EnetLinkFaults(dropPercent = 40, holdPercent = 20, reorder = true))
        link.client.startDisconnect(data = 0)

        assertTrue(
            "spec §9.7: getting this wrong leaves the host holding a live session",
            link.runUntil(maxSteps = 4_000) {
                link.client.state == EnetPeerState.DISCONNECTED &&
                    link.server?.state == EnetPeerState.DISCONNECTED
            },
        )
    }

    // ---- Helpers -------------------------------------------------------------------------------

    /** A link whose handshake has already completed, with [faults] applied from that point on. */
    private fun connectedLink(faults: EnetLinkFaults): EnetPeerLink {
        val link = EnetPeerLink(config, faults)
        link.startConnect()
        assertTrue("the handshake did not complete", link.runUntil(maxSteps = 4_000) { link.bothConnected() })
        link.serverPayloads.clear()
        link.clientSentCommands.clear()
        return link
    }

    /** Queues [count] distinguishable reliable payloads on the generic channel. */
    private fun sendNumbered(link: EnetPeerLink, count: Int): List<ByteArray> {
        val sent = ArrayList<ByteArray>(count)
        for (index in 0 until count) {
            val payload = byteArrayOf(
                ((index ushr 8) and 0xFF).toByte(),
                (index and 0xFF).toByte(),
                0x7F,
            )
            sent.add(payload)
            assertTrue(
                link.client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, payload, EnetDelivery.RELIABLE),
            )
        }
        return sent
    }

    private fun assertPayloadsInOrder(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals("payload count", expected.size, actual.size)
        for (index in expected.indices) {
            assertArrayEquals("payload $index", expected[index], actual[index])
        }
    }
}
