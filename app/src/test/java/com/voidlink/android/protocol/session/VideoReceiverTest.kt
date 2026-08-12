package com.voidlink.android.protocol.session

import com.voidlink.android.protocol.rtp.VideoFramePipeline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The video socket, its keep-alive and its receive thread, over a real loopback socket
 * (`docs/01-PROTOCOL.md` §7.5).
 *
 * A real socket rather than a mock, for the same reason `protocol/enet`'s loopback test uses one:
 * the keep-alive's whole job is to make a *real* NAT or firewall open a pinhole for the host's
 * source port, and the two things that can go wrong — the ping never leaves, or the socket we ping
 * from is not the socket we receive on — are both invisible to a test that stubs the socket out.
 * Spec §7.5 is explicit that both must be the same socket.
 */
class VideoReceiverTest {

    private var host: DatagramSocket? = null
    private var receiver: VideoReceiver? = null

    @After
    fun tearDown() {
        receiver?.close()
        host?.close()
    }

    @Test
    fun `the keep-alive is the legacy PING when the host offered no payload`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS

        val started = receiverFor(hostSocket, pingPayload = null)

        val packet = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(packet)
        assertEquals(4, packet.length)
        assertEquals("PING", String(packet.data, 0, 4, StandardCharsets.US_ASCII))
        // The ping came from the socket video will arrive on — that is the pinhole (spec §7.5).
        assertEquals(started.localPort, packet.port)
    }

    @Test
    fun `a Sunshine keep-alive is the payload plus a big-endian sequence number from one`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS

        receiverFor(hostSocket, pingPayload = "0123456789abcdef")

        val first = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(first)
        assertEquals(SessionConstants.SS_PING_TOTAL_BYTES, first.length)
        assertEquals(
            "0123456789abcdef",
            String(first.data, 0, 16, StandardCharsets.US_ASCII),
        )
        assertEquals(1, sequenceOf(first))

        val second = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(second)
        assertEquals(2, sequenceOf(second))
    }

    @Test
    fun `datagrams the host sends are counted, even when they do not parse`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS
        val started = receiverFor(hostSocket, pingPayload = null)

        val garbage = ByteArray(20) { it.toByte() }
        hostSocket.send(
            DatagramPacket(garbage, garbage.size, InetAddress.getLoopbackAddress(), started.localPort),
        )

        // Counted as *rejected*, not received — which is exactly the distinction the first-frame
        // watchdog uses to tell a firewall (nothing at all) from a parser bug (spec §11.1).
        val stats = awaitStats(started) { it.packetsRejected > 0L }
        assertTrue(stats.packetsRejected >= 1L)
        assertEquals(0L, stats.framesCompleted)
    }

    @Test
    fun `closing releases the socket and stops both threads`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS
        val started = receiverFor(hostSocket, pingPayload = null)
        val port = started.localPort

        started.close()

        // The port is free again, which is only true if the receive thread let go of it.
        DatagramSocket(port, InetAddress.getLoopbackAddress()).close()
        // And idempotent: teardown runs on failure paths where it may be called twice.
        started.close()
    }

    private fun receiverFor(hostSocket: DatagramSocket, pingPayload: String?): VideoReceiver {
        val created = VideoReceiver(
            hostAddress = InetAddress.getLoopbackAddress(),
            videoPort = hostSocket.localPort,
            pingPayload = pingPayload,
            pipeline = VideoFramePipeline(),
            pingIntervalMs = PING_INTERVAL_MS,
            socketFactory = { DatagramSocket(0, InetAddress.getLoopbackAddress()) },
        )
        receiver = created
        created.start()
        return created
    }

    private fun sequenceOf(packet: DatagramPacket): Int =
        ByteBuffer.wrap(packet.data, SessionConstants.SS_PING_PAYLOAD_BYTES, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int

    private fun awaitStats(
        receiver: VideoReceiver,
        predicate: (com.voidlink.android.protocol.rtp.FrameAssemblerStats) -> Boolean,
    ): com.voidlink.android.protocol.rtp.FrameAssemblerStats {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            val stats = receiver.stats()
            if (predicate(stats)) return stats
            Thread.sleep(5L)
        }
        throw AssertionError("the receive thread never saw the datagram")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val PING_INTERVAL_MS = 20L
    }
}
