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
 *
 * Loss rates and step budgets are chosen so that the retransmission ladder has ten or more attempts
 * inside the budget. A test that fails once a month because a seeded coin came up badly is worse
 * than no test at all, so the margins are deliberately wide.
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
        // The connect timeout is lifted for this one test: spec §9.1's 10 s is a product decision
        // about how long a user waits, and asserting it here would only measure the coin flips.
        val link = EnetPeerLink(
            config.copy(connectTimeoutMs = 120_000),
            EnetLinkFaults(dropPercent = 40, duplicatePercent = 20, holdPercent = 20, reorder = true),
        )
        link.startConnect()

        assertTrue("handshake never completed", link.runUntil(maxSteps = 4_000) { link.bothConnected() })
        // Whatever the link did to the handshake, it completed exactly once at each end.
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
        assertEquals(EnetPeerState.CONNECTED, link.client.state)
    }

    @Test
    fun `the fault injector really does drop and duplicate`() {
        // Every convergence test above is only worth something if the link is genuinely hostile.
        // This one pumps enough datagrams that "no fault happened" is not a coin flip.
        val link = connectedLink(
            EnetLinkFaults(dropPercent = 30, duplicatePercent = 25, holdPercent = 25, reorder = true),
        )
        repeat(30) { round ->
            repeat(10) { index ->
                link.client.send(
                    EnetUnverifiedConstants.CHANNEL_GENERIC,
                    byteArrayOf(round.toByte(), index.toByte()),
                    EnetDelivery.RELIABLE,
                )
            }
            link.run(20)
        }

        assertTrue("dropped only ${link.dropped} datagrams", link.dropped > 5)
        assertTrue("duplicated only ${link.duplicated} datagrams", link.duplicated > 5)
        assertTrue(link.runUntil(maxSteps = 4_000) { link.serverPayloads.size >= 300 })
        assertEquals(300, link.serverPayloads.size)
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
        // Interleaving between channels is allowed — that is the point of spec §9.1's split between
        // the generic and urgent channels. Ordering within each is not negotiable.
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
        assertEquals(fragments[0].fragmentCount, fragments.map { it.fragmentNumber }.distinct().size)
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

    // ---- Unreliable and unsequenced ------------------------------------------------------------

    @Test
    fun `unsequenced payloads are delivered as they arrive and are never retransmitted`() {
        val link = connectedLink(EnetLinkFaults(dropPercent = 30))
        repeat(30) { index ->
            link.client.send(
                EnetUnverifiedConstants.CHANNEL_GENERIC,
                byteArrayOf(index.toByte()),
                EnetDelivery.UNSEQUENCED,
            )
            // One per step, so each gets its own datagram and the link can lose them individually.
            link.run(2)
        }
        link.run(50)

        // Spec §9.5 sends the Sunshine FEC report this way: losing one is the point, and nothing may
        // hold up a later report waiting for it.
        assertTrue(link.serverPayloads.isNotEmpty())
        assertTrue("nothing was lost, so nothing was proved", link.serverPayloads.size < 30)
        // Sent once each and never again: an unsequenced command is not tracked for retransmission.
        assertEquals(30, link.clientSentCommands.filterIsInstance<EnetCommand.SendUnsequenced>().size)
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
            link.run(2)
        }
        link.run(50)

        assertTrue(link.serverPayloads.isNotEmpty())
        val order = link.serverPayloads.map { it[0].toInt() }
        assertEquals("stale unreliable packets must be dropped, not reordered", order.sorted(), order)
        assertEquals("an unreliable packet must not be delivered twice", order.distinct(), order)
        assertEquals(30, link.clientSentCommands.filterIsInstance<EnetCommand.SendUnreliable>().size)
    }

    @Test
    fun `an oversized unreliable payload is refused instead of silently truncated`() {
        val link = connectedLink(EnetLinkFaults())
        val huge = ByteArray(config.fragmentLength + 1)
        assertFalse(link.client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, huge, EnetDelivery.UNRELIABLE))
        assertFalse(link.client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, huge, EnetDelivery.UNSEQUENCED))
        // The same payload sent reliably is fragmented instead, which is the whole difference.
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
    fun `a disconnect reaches the host through a lossy link`() {
        val link = connectedLink(EnetLinkFaults(dropPercent = 40, holdPercent = 20, reorder = true))
        link.client.startDisconnect(data = 0)

        // What matters in spec §9.7 is that the *host* learns the session is over — that is what
        // stops it holding a live session. Our own confirmation is best-effort by construction:
        // once the host has torn its peer down it will not answer a retransmitted DISCONNECT, which
        // is exactly why the spec gives the linger a 2 s budget and then moves on.
        assertTrue(
            "the host was never told the session ended",
            link.runUntil(maxSteps = 4_000) { link.server?.state == EnetPeerState.DISCONNECTED },
        )
        val serverDisconnect = link.serverEvents.filterIsInstance<EnetEvent.Disconnected>().single()
        assertFalse(serverDisconnect.timedOut)
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
