package com.voidlink.android.protocol.audio

import com.voidlink.android.protocol.ProtocolLog
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** What an audio channel must offer, whether it is a real socket or a test double. */
interface AudioChannel {

    /** Opus packets in stream order, including concealment markers. */
    val samples: ReceiveChannel<OpusSample>

    /** Loss and status notices. */
    val events: ReceiveChannel<AudioStreamEvent>

    /** Counters, which separate "the host is not sending" from "we cannot use what arrives". */
    fun stats(): AudioStreamStats

    /** Releases the socket and stops the receive and ping threads. Idempotent. */
    fun close()
}

/**
 * The audio UDP socket, its receive thread and its keep-alive thread (spec §7.5, §8.1).
 *
 * The audio counterpart of [com.voidlink.android.protocol.session.VideoReceiver], deliberately
 * built the same way and for the same reasons, with three differences that are all about audio:
 *
 * * **The receive blocks with a timeout.** Video's receive loop can block indefinitely because
 *   nothing downstream needs a tick; audio's does not, because an FEC block held for reordering is
 *   released on a deadline and the start-up resync drop is cancelled by the socket going quiet.
 *   [RtpAudioConstants.RECEIVE_POLL_TIMEOUT_MS] is what gives [AudioDepacketizer.onIdle] its tick.
 * * **The receive buffer is small.** 64 KB rather than 2 MB: a large socket buffer is exactly the
 *   backlog this layer spends its start-up dropping.
 * * **Loss never asks the host for anything.** There is no audio equivalent of an IDR, and adding
 *   traffic to a link that is already losing packets would make it worse.
 *
 * The keep-alive is what makes audio arrive at all — the host will not send until it has seen a
 * packet from our source port (spec §7.5) — and send errors are ignored for the same reason as on
 * the video socket: a host that has not bound its socket yet answers with ICMP port-unreachable.
 *
 * Threads rather than coroutines for both, deliberately: `DatagramSocket.receive()` does not observe
 * coroutine cancellation, and closing the socket is the only thing that unblocks it (architecture
 * §3, rule 4).
 *
 * @param hostAddress the host to ping. Audio arrives from it, but the socket is not connected, so a
 *   host that answers from a different source port still reaches us.
 * @param audioPort the negotiated audio port — `server_port=` from `SETUP streamid=audio`.
 * @param pingPayload the 16-character `X-SS-Ping-Payload` from the SETUP response, or `null` to use
 *   the legacy 4-byte `PING`.
 * @param pipeline where datagrams go. Owned by the caller, which also reads its channels.
 * @param pingIntervalMs keep-alive cadence.
 * @param socketFactory how the socket is created; injectable so a test can bind a known port.
 */
class AudioReceiver(
    private val hostAddress: InetAddress,
    private val audioPort: Int,
    private val pingPayload: String?,
    private val pipeline: AudioSamplePipeline,
    private val pingIntervalMs: Long = RtpAudioConstants.PING_INTERVAL_MS,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : AudioChannel {

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var receiveThread: Thread? = null
    private var pingThread: Thread? = null

    override val samples: ReceiveChannel<OpusSample>
        get() = pipeline.samples

    override val events: ReceiveChannel<AudioStreamEvent>
        get() = pipeline.events

    /** The local port audio will arrive on. `-1` before [start]. */
    val localPort: Int
        get() = socket?.localPort ?: -1

    /** Keep-alives written so far. Zero after a few seconds means the ping thread died. */
    @Volatile
    var pingsSent: Long = 0L
        private set

    override fun stats(): AudioStreamStats = pipeline.stats()

    /**
     * Opens the socket and starts both threads.
     *
     * @throws SocketException when the socket cannot be created or bound. Thrown rather than
     *   swallowed so the caller can tell a device problem from a host problem — though for audio,
     *   unlike video, the caller's response is to continue without audio rather than to fail.
     */
    fun start() {
        check(!running) { "AudioReceiver.start() called twice" }
        val created = socketFactory()
        try {
            created.reuseAddress = true
            created.receiveBufferSize = RtpAudioConstants.AUDIO_RECEIVE_BUFFER_BYTES
        } catch (ignored: SocketException) {
            ProtocolLog.w(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "the OS refused a ${RtpAudioConstants.AUDIO_RECEIVE_BUFFER_BYTES}-byte audio " +
                    "receive buffer; brief dropouts under load are likelier",
            )
        }
        try {
            created.soTimeout = RtpAudioConstants.RECEIVE_POLL_TIMEOUT_MS
        } catch (ignored: SocketException) {
            // Without a timeout the receive thread blocks indefinitely. Everything still works;
            // held FEC blocks are then released by the next arriving packet rather than on time.
            ProtocolLog.w(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "the OS refused a receive timeout on the audio socket; reordered packets will be " +
                    "released late rather than on a deadline",
            )
        }
        socket = created
        running = true

        receiveThread = Thread({ receiveLoop(created) }, RtpAudioConstants.THREAD_AUDIO_RX).apply {
            isDaemon = true
            start()
        }
        pingThread = Thread({ pingLoop(created) }, RtpAudioConstants.THREAD_AUDIO_PING).apply {
            isDaemon = true
            start()
        }
        ProtocolLog.i(
            RtpAudioConstants.LOG_TAG_AUDIO,
            "audio receiver listening on local port ${created.localPort}, pinging " +
                "${hostAddress.hostAddress}:$audioPort every ${pingIntervalMs}ms " +
                (if (pingPayload != null) "with an X-SS-Ping-Payload" else "with legacy PING"),
        )
    }

    /**
     * Stops both threads and closes the socket.
     *
     * Idempotent, and safe to call from any thread including one that is failing.
     */
    override fun close() {
        if (!running) {
            socket?.close()
            return
        }
        running = false
        socket?.close()
        pingThread?.interrupt()
        joinQuietly(receiveThread)
        joinQuietly(pingThread)
        receiveThread = null
        pingThread = null
        pipeline.close()
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(RtpAudioConstants.MAX_AUDIO_DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
                pipeline.onDatagram(buffer, packet.length)
            } catch (idle: SocketTimeoutException) {
                // The expected quiet path, and not an error: it is what releases a held FEC block
                // on time and what cancels the start-up resync drop on a host with no backlog.
                pipeline.onIdle()
            } catch (closed: SocketException) {
                // The expected way out: close() shut the socket from another thread.
                if (running) {
                    ProtocolLog.w(
                        RtpAudioConstants.LOG_TAG_AUDIO,
                        "audio socket failed: ${closed.message}",
                    )
                }
                return
            } catch (io: IOException) {
                // A single bad datagram — commonly an ICMP port-unreachable surfacing on the next
                // receive — must not end the stream (spec §7.5).
                ProtocolLog.d(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "audio receive error: ${io.message}",
                )
            }
        }
    }

    private fun pingLoop(socket: DatagramSocket) {
        val destination = InetSocketAddress(hostAddress, audioPort)
        val payload = pingPayload?.toByteArray(StandardCharsets.US_ASCII)
        if (payload != null && payload.size != SS_PING_PAYLOAD_BYTES) {
            ProtocolLog.w(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "X-SS-Ping-Payload was ${payload.size} bytes, not $SS_PING_PAYLOAD_BYTES; " +
                    "sending it anyway (spec §7.5)",
            )
        }
        var sequence = SS_PING_FIRST_SEQUENCE
        while (running) {
            val datagram = if (payload == null) {
                LEGACY_PING_PAYLOAD
            } else {
                buildSunshinePing(payload, sequence++)
            }
            try {
                socket.send(DatagramPacket(datagram, datagram.size, destination))
                pingsSent++
            } catch (io: IOException) {
                // Spec §7.5: ignore all send errors here.
                ProtocolLog.d(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "audio keep-alive send failed: ${io.message}",
                )
            }
            try {
                Thread.sleep(pingIntervalMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * The 20-byte Sunshine keep-alive: the 16-byte payload then a **big-endian** `uint32` sequence
     * number starting at 1 (spec §7.5).
     */
    private fun buildSunshinePing(payload: ByteArray, sequence: Int): ByteArray {
        val buffer = ByteBuffer
            .allocate(payload.size + SS_PING_SEQUENCE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(payload)
        buffer.putInt(sequence)
        return buffer.array()
    }

    private fun joinQuietly(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(RtpAudioConstants.THREAD_JOIN_TIMEOUT_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        /**
         * The keep-alive shape of spec §7.5.
         *
         * Repeated here rather than taken from
         * [com.voidlink.android.protocol.session.SessionConstants] so that `protocol/audio` has no
         * dependency on `protocol/session` — the dependency runs the other way, and audio must be
         * attachable to a session without the session having to hand it anything but a port.
         */
        const val SS_PING_PAYLOAD_BYTES: Int = 16
        const val SS_PING_SEQUENCE_BYTES: Int = 4
        const val SS_PING_FIRST_SEQUENCE: Int = 1
        val LEGACY_PING_PAYLOAD: ByteArray = byteArrayOf(0x50, 0x49, 0x4E, 0x47)
    }
}
