package com.voidlink.android.protocol.enet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * `04-ROADMAP.md` Phase 8's `[CI]` gate, over real sockets: two [EnetHost] instances talking to
 * each other on `127.0.0.1`, proving connect → reliable send → acknowledge → ordered delivery →
 * disconnect (`docs/01-PROTOCOL.md` §9.1, §9.7).
 *
 * What this covers that `EnetReliabilityTest` cannot: the service loop, the request queue that
 * carries a send from a caller's thread onto it, the datagram socket, address matching, and the
 * suspend API the rest of the app will actually call. What it deliberately does not cover is
 * behaviour under loss — that needs determinism, and determinism needs a clock the test owns, which
 * is why the fault injection lives in `EnetReliabilityTest` instead.
 *
 * [runBlocking] rather than `runTest`: `withTimeout` inside a `TestScope` runs on virtual time,
 * which never advances while a coroutine is blocked on a socket, so every timeout here would be
 * either instant or infinite. Real time is the correct clock for a test with a real socket in it;
 * the budgets are seconds where the work is milliseconds, so a slow CI machine does not fail them.
 */
class EnetLoopbackTest {

    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    /**
     * Short service interval and retransmission timeout so the test finishes quickly, with
     * dead-peer deadlines left long so a stalled CI machine cannot look like a dead link.
     */
    private fun loopbackConfig(acceptIncomingConnections: Boolean) = EnetConfig(
        channelCount = EnetControlConstants.CHANNEL_COUNT,
        serviceIntervalMs = 10,
        pingIntervalMs = 250,
        initialRoundTripTimeMs = 100,
        connectTimeoutMs = 10_000,
        timeoutMinimumMs = 60_000,
        timeoutMaximumMs = 120_000,
        acceptIncomingConnections = acceptIncomingConnections,
    )

    @Test
    fun `connect, reliable exchange, ordered delivery and disconnect over the loopback interface`() {
        withHosts { client, server, serverAddress ->
            assertTrue(
                "the ENet handshake did not complete",
                client.connect(serverAddress, connectData = 0x0BADF00D, timeoutMs = 10_000),
            )
            assertEquals(EnetPeerState.CONNECTED, client.state.value)
            withTimeout(TIMEOUT_MS) { server.state.first { it == EnetPeerState.CONNECTED } }

            // Client to host on the urgent channel — where spec §9.1 puts input and IDR requests.
            val sent = (0 until 40).map { index -> numbered(index) }
            for (payload in sent) {
                assertTrue(client.send(EnetUnverifiedConstants.CHANNEL_URGENT, payload, EnetDelivery.RELIABLE))
            }
            val received = withTimeout(TIMEOUT_MS) {
                List(sent.size) { server.inbound.receive() }
            }
            for (index in sent.indices) {
                assertEquals(EnetUnverifiedConstants.CHANNEL_URGENT, received[index].channelId)
                assertArrayEquals("payload $index", sent[index], received[index].payload)
            }

            // Host to client, so the acknowledgement path is exercised in both directions.
            val fromHost = byteArrayOf(0x01, 0x0B, 0x00, 0x00)
            assertTrue(server.send(EnetUnverifiedConstants.CHANNEL_GENERIC, fromHost, EnetDelivery.RELIABLE))
            val inbound = withTimeout(TIMEOUT_MS) { client.inbound.receive() }
            assertArrayEquals(fromHost, inbound.payload)
            assertEquals(EnetUnverifiedConstants.CHANNEL_GENERIC, inbound.channelId)

            // Acknowledgements came back, so there is a real round-trip measurement by now.
            val rtt = client.roundTripTimeMs
            assertTrue("expected an RTT estimate, got $rtt", rtt != null && rtt >= 0)

            // Spec §9.7 step 3: disconnect and pump for the acknowledgement.
            assertTrue("the host did not acknowledge the disconnect", client.disconnect(lingerMs = TIMEOUT_MS))
            withTimeout(TIMEOUT_MS) { server.state.first { it == EnetPeerState.DISCONNECTED } }
            assertEquals(EnetPeerState.DISCONNECTED, client.state.value)
        }
    }

    @Test
    fun `a payload larger than the MTU crosses the socket and reassembles`() {
        withHosts { client, server, serverAddress ->
            assertTrue(client.connect(serverAddress, connectData = 0, timeoutMs = 10_000))

            val large = ByteArray(9_000) { ((it * 13) and 0xFF).toByte() }
            assertTrue(client.send(EnetUnverifiedConstants.CHANNEL_URGENT, large, EnetDelivery.RELIABLE))

            val inbound = withTimeout(TIMEOUT_MS) { server.inbound.receive() }
            assertArrayEquals(large, inbound.payload)
        }
    }

    @Test
    fun `unsequenced and unreliable payloads cross the socket`() {
        withHosts { client, server, serverAddress ->
            assertTrue(client.connect(serverAddress, connectData = 0, timeoutMs = 10_000))

            val unsequenced = byteArrayOf(0x02, 0x55, 0x00, 0x11)
            val unreliable = byteArrayOf(0x03, 0x55, 0x00, 0x22)
            assertTrue(
                client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, unsequenced, EnetDelivery.UNSEQUENCED),
            )
            assertTrue(
                client.send(EnetUnverifiedConstants.CHANNEL_GENERIC, unreliable, EnetDelivery.UNRELIABLE),
            )

            // Nothing is lost on a loopback interface, so both arrive; order between the two
            // delivery classes is not guaranteed, which is why they are matched rather than indexed.
            val first = withTimeout(TIMEOUT_MS) { server.inbound.receive() }.payload
            val second = withTimeout(TIMEOUT_MS) { server.inbound.receive() }.payload
            val arrived = listOf(first.toList(), second.toList())
            assertTrue(arrived.contains(unsequenced.toList()))
            assertTrue(arrived.contains(unreliable.toList()))
        }
    }

    @Test
    fun `connecting to a port with nothing behind it fails instead of hanging`() {
        val clientSocket = DatagramSocket(0, loopback)
        val deadSocket = DatagramSocket(0, loopback)
        val deadPort = deadSocket.localPort
        deadSocket.close()

        val client = EnetHost(DatagramEnetTransport(clientSocket), loopbackConfig(acceptIncomingConnections = false))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            runBlocking {
                client.startIn(scope)
                val connected = client.connect(
                    InetSocketAddress(loopback, deadPort),
                    connectData = 0,
                    timeoutMs = 1_500,
                )
                assertFalse("nothing is listening; the handshake must not report success", connected)
                // The caller's deadline is the deadline: the abandoned peer must be gone, not left
                // retransmitting for the rest of EnetConfig.connectTimeoutMs behind a caller who
                // has moved on — and the host must be ready for another attempt.
                withTimeout(TIMEOUT_MS) { client.state.first { it == EnetPeerState.DISCONNECTED } }
                assertNull(client.peer)
            }
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun `a second connect on a host that already has a peer is refused`() {
        withHosts { client, _, serverAddress ->
            assertTrue(client.connect(serverAddress, connectData = 0, timeoutMs = 10_000))
            assertFalse(client.connect(serverAddress, connectData = 0, timeoutMs = 1_000))
        }
    }

    /**
     * Builds a client and a server host on the loopback interface, runs both service loops, hands
     * them to [body], and tears everything down whatever happens.
     */
    private fun withHosts(body: suspend (client: EnetHost, server: EnetHost, serverAddress: InetSocketAddress) -> Unit) {
        val serverSocket = DatagramSocket(0, loopback)
        val clientSocket = DatagramSocket(0, loopback)
        val server = EnetHost(
            DatagramEnetTransport(serverSocket),
            loopbackConfig(acceptIncomingConnections = true),
        )
        val client = EnetHost(
            DatagramEnetTransport(clientSocket),
            loopbackConfig(acceptIncomingConnections = false),
        )
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            runBlocking {
                server.startIn(scope)
                client.startIn(scope)
                body(client, server, InetSocketAddress(loopback, serverSocket.localPort))
            }
        } finally {
            client.close()
            server.close()
            scope.cancel()
        }
    }

    private fun numbered(index: Int): ByteArray = byteArrayOf(
        ((index ushr 8) and 0xFF).toByte(),
        (index and 0xFF).toByte(),
        0x5A,
    )

    private companion object {
        /**
         * Seconds where the work is milliseconds. A loopback round trip is microseconds; this
         * budget exists so a contended CI machine fails slowly rather than flakily.
         */
        const val TIMEOUT_MS: Long = 15_000L
    }
}
