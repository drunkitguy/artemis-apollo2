package com.voidlink.android.protocol.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * The audio socket, its keep-alive and its receive thread, over a real loopback socket
 * (`docs/01-PROTOCOL.md` §7.5, §8.1).
 *
 * A real socket rather than a mock, for the same reason the video receiver's test uses one: the
 * keep-alive's whole job is to make a *real* NAT or firewall open a pinhole for the host's source
 * port, and the two things that can go wrong — the ping never leaves, or the socket we ping from is
 * not the socket we receive on — are both invisible to a test that stubs the socket out.
 */
class AudioReceiverTest {

    private var host: DatagramSocket? = null
    private var receiver: AudioReceiver? = null

    @After
    fun tearDown() {
        receiver?.close()
        host?.close()
    }

    private fun receiverFor(
        hostSocket: DatagramSocket,
        pingPayload: String?,
        pipeline: AudioSamplePipeline = AudioSamplePipeline(
            config = AudioDepacketizerConfig(initialResyncDropMs = 0),
        ),
        pingIntervalMs: Long = 20L,
    ): AudioReceiver = AudioReceiver(
        hostAddress = InetAddress.getLoopbackAddress(),
        audioPort = hostSocket.localPort,
        pingPayload = pingPayload,
        pipeline = pipeline,
        pingIntervalMs = pingIntervalMs,
        socketFactory = { DatagramSocket(0, InetAddress.getLoopbackAddress()) },
    ).also {
        receiver = it
        it.start()
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
        // The ping came from the socket audio will arrive on — that is the pinhole (spec §7.5).
        assertEquals(started.localPort, packet.port)
    }

    @Test
    fun `a Sunshine keep-alive is the payload plus a big-endian sequence number from one`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS

        receiverFor(hostSocket, pingPayload = "0123456789abcdef")

        val first = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(first)
        assertEquals(20, first.length)
        assertEquals("0123456789abcdef", String(first.data, 0, 16, StandardCharsets.US_ASCII))
        assertEquals(1, sequenceOf(first))

        val second = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(second)
        assertEquals(2, sequenceOf(second))
    }

    @Test
    fun `Opus packets sent by the host reach the sample channel`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS
        val pipeline = AudioSamplePipeline(
            config = AudioDepacketizerConfig(initialResyncDropMs = 0),
        )
        val started = receiverFor(hostSocket, pingPayload = null, pipeline = pipeline)

        // Wait for the pinhole, then reply to the source port the ping came from.
        val ping = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(ping)
        val clientPort = ping.port

        for (sequence in 100..107) {
            val datagram = AudioPacketFixtures.dataPacket(sequence)
            hostSocket.send(
                DatagramPacket(
                    datagram,
                    datagram.size,
                    InetAddress.getLoopbackAddress(),
                    clientPort,
                ),
            )
        }

        val received = awaitSamples(pipeline, expected = 4)

        // 100 synchronises the queue and 101..103 belong to the block it skipped; 104..107 play.
        assertEquals(listOf(104, 105, 106, 107), received)
        assertTrue(started.localPort > 0)
        assertTrue(started.pingsSent > 0L)
        assertTrue(pipeline.stats().packetsReceived >= 8L)
    }

    @Test
    fun `the receive thread survives a datagram it cannot parse`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS
        val pipeline = AudioSamplePipeline(
            config = AudioDepacketizerConfig(initialResyncDropMs = 0),
        )
        receiverFor(hostSocket, pingPayload = null, pipeline = pipeline)

        val ping = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(ping)
        val clientPort = ping.port

        val runt = ByteArray(5)
        hostSocket.send(
            DatagramPacket(runt, runt.size, InetAddress.getLoopbackAddress(), clientPort),
        )
        for (sequence in 100..107) {
            val datagram = AudioPacketFixtures.dataPacket(sequence)
            hostSocket.send(
                DatagramPacket(
                    datagram,
                    datagram.size,
                    InetAddress.getLoopbackAddress(),
                    clientPort,
                ),
            )
        }

        assertEquals(listOf(104, 105, 106, 107), awaitSamples(pipeline, expected = 4))
        assertTrue(pipeline.stats().packetsRejected >= 1L)
    }

    @Test
    fun `closing twice is harmless and stops the keep-alive`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        hostSocket.soTimeout = TIMEOUT_MS
        val started = receiverFor(hostSocket, pingPayload = null)

        val packet = DatagramPacket(ByteArray(64), 64)
        hostSocket.receive(packet)

        started.close()
        started.close()

        val sentByClose = started.pingsSent
        Thread.sleep(80L)
        assertEquals(sentByClose, started.pingsSent)
    }

    @Test
    fun `starting twice is refused rather than leaking a socket`() {
        val hostSocket = DatagramSocket(0, InetAddress.getLoopbackAddress()).also { host = it }
        val started = receiverFor(hostSocket, pingPayload = null)

        val rejected = runCatching { started.start() }

        assertTrue(rejected.exceptionOrNull() is IllegalStateException)
    }

    private fun awaitSamples(pipeline: AudioSamplePipeline, expected: Int): List<Int> {
        val received = mutableListOf<Int>()
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        while (received.size < expected && System.nanoTime() < deadline) {
            val sample = pipeline.samples.tryReceive().getOrNull()
            if (sample == null) {
                Thread.sleep(2L)
            } else {
                received += sample.sequenceNumber
            }
        }
        return received
    }

    /** The big-endian `uint32` at the end of a Sunshine keep-alive (spec §7.5). */
    private fun sequenceOf(packet: DatagramPacket): Int {
        var value = 0
        for (index in 16 until 20) {
            value = (value shl 8) or (packet.data[index].toInt() and 0xFF)
        }
        return value
    }

    private companion object {
        const val TIMEOUT_MS: Int = 2_000
    }
}
