package com.voidlink.android.protocol.session

import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.rtp.FrameAssemblerStats
import com.voidlink.android.protocol.rtp.VideoFrame
import com.voidlink.android.protocol.rtp.VideoFramePipeline
import com.voidlink.android.protocol.rtp.VideoStreamEvent
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The video UDP socket, its receive thread and its keep-alive thread (`docs/01-PROTOCOL.md` §7.5,
 * `docs/02-ARCHITECTURE.md` §3).
 *
 * Two threads, exactly as architecture §3's table prescribes:
 *
 * * **`video-rx`** — blocks in `DatagramSocket.receive()`, hands each datagram to
 *   [VideoFramePipeline], and does nothing else. It never decodes, never allocates a new buffer
 *   (one receive buffer is reused for the life of the session) and never waits on a coroutine.
 * * **`video-ping`** — sends the keep-alive of spec §7.5 from **the same socket**, every
 *   [SessionConstants.VIDEO_PING_INTERVAL_MS], starting immediately.
 *
 * The keep-alive is what makes video arrive at all: the host will not send until it has seen a
 * packet from our source port, because on a home network there is a NAT or firewall between us that
 * has no pinhole for an unsolicited inbound stream. Send errors are ignored, as spec §7.5 requires
 * — a host that has not bound its socket yet answers with ICMP port-unreachable, and the receive
 * side is where that is handled.
 *
 * Threads rather than coroutines for both, deliberately. `DatagramSocket.receive()` is a blocking
 * call that does not observe coroutine cancellation; the only thing that unblocks it is closing the
 * socket, which is exactly what [close] does (architecture §3, rule 4).
 *
 * @param hostAddress the host to ping. Video arrives from it, but the socket is not connected, so a
 *   host that answers from a different source port still reaches us.
 * @param videoPort the negotiated video port — `server_port=` from `SETUP streamid=video`.
 * @param pingPayload the 16-character `X-SS-Ping-Payload` from the SETUP response, or `null` to use
 *   the legacy 4-byte `PING`.
 * @param pipeline where datagrams go. Owned by the caller, which also reads its channels.
 * @param pingIntervalMs keep-alive cadence.
 * @param socketFactory how the socket is created; injectable so a test can bind a known port.
 */
class VideoReceiver(
    private val hostAddress: InetAddress,
    private val videoPort: Int,
    private val pingPayload: String?,
    private val pipeline: VideoFramePipeline,
    private val pingIntervalMs: Long = SessionConstants.VIDEO_PING_INTERVAL_MS,
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : VideoChannel {

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var receiveThread: Thread? = null
    private var pingThread: Thread? = null

    /** Complete frames, straight from the reassembler. */
    override val frames: ReceiveChannel<VideoFrame>
        get() = pipeline.frames

    /** Loss and status notices for the control channel (spec §9.5). */
    override val events: ReceiveChannel<VideoStreamEvent>
        get() = pipeline.events

    /** The local port video will arrive on. `-1` before [start]. */
    val localPort: Int
        get() = socket?.localPort ?: -1

    /** Keep-alives written so far. Zero after a few seconds means the ping thread died. */
    @Volatile
    var pingsSent: Long = 0L
        private set

    /** The reassembler's counters, which is how [SessionFailure.NoVideoFrame] is told apart from
     * [SessionFailure.NoVideoTraffic] (spec §11.1). */
    override fun stats(): FrameAssemblerStats = pipeline.stats()

    /**
     * Opens the socket and starts both threads.
     *
     * @throws SocketException when the socket cannot be created or bound, which the session reports
     *   as [SessionFailure.VideoSocketFailed]. Thrown rather than swallowed because a client that
     *   cannot open a UDP socket has a device problem, not a host problem, and the two must not
     *   produce the same message.
     */
    fun start() {
        check(!running) { "VideoReceiver.start() called twice" }
        val created = socketFactory()
        try {
            created.reuseAddress = true
            created.receiveBufferSize = SessionConstants.VIDEO_RECEIVE_BUFFER_BYTES
        } catch (ignored: SocketException) {
            // A refused buffer size is a hint, not a failure: the stream works with the default,
            // just with less tolerance for a scheduling hiccup on the receive thread.
            ProtocolLog.w(
                SessionConstants.TAG,
                "the OS refused a ${SessionConstants.VIDEO_RECEIVE_BUFFER_BYTES}-byte video " +
                    "receive buffer; packet loss under load is likelier",
            )
        }
        socket = created
        running = true

        receiveThread = Thread({ receiveLoop(created) }, SessionConstants.THREAD_VIDEO_RX).apply {
            isDaemon = true
            start()
        }
        pingThread = Thread({ pingLoop(created) }, SessionConstants.THREAD_VIDEO_PING).apply {
            isDaemon = true
            start()
        }
        ProtocolLog.i(
            SessionConstants.TAG,
            "video receiver listening on local port ${created.localPort}, pinging " +
                "${hostAddress.hostAddress}:$videoPort every ${pingIntervalMs}ms " +
                (if (pingPayload != null) "with an X-SS-Ping-Payload" else "with legacy PING"),
        )
    }

    /**
     * Stops both threads and closes the socket (spec §9.7 step 4).
     *
     * Idempotent, and safe to call from any thread including one that is failing. Closing the
     * socket is what unblocks the receive thread; the join is bounded so a wedged thread cannot
     * stall teardown, and both threads are daemons so even that cannot keep the process alive.
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
        val buffer = ByteArray(SessionConstants.MAX_VIDEO_DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
                pipeline.onDatagram(buffer, packet.length)
            } catch (closed: SocketException) {
                // The expected way out: close() shut the socket from another thread.
                if (running) {
                    ProtocolLog.w(SessionConstants.TAG, "video socket failed: ${closed.message}")
                }
                return
            } catch (io: IOException) {
                // A single bad datagram — commonly an ICMP port-unreachable surfacing as an
                // exception on the next receive — must not end the stream (spec §7.5).
                ProtocolLog.d(SessionConstants.TAG, "video receive error: ${io.message}")
            }
        }
    }

    private fun pingLoop(socket: DatagramSocket) {
        val destination = InetSocketAddress(hostAddress, videoPort)
        val payload = pingPayload?.toByteArray(StandardCharsets.US_ASCII)
        if (payload != null && payload.size != SessionConstants.SS_PING_PAYLOAD_BYTES) {
            ProtocolLog.w(
                SessionConstants.TAG,
                "X-SS-Ping-Payload was ${payload.size} bytes, not " +
                    "${SessionConstants.SS_PING_PAYLOAD_BYTES}; sending it anyway (spec §7.5)",
            )
        }
        var sequence = SessionConstants.SS_PING_FIRST_SEQUENCE
        while (running) {
            val datagram = if (payload == null) {
                SessionConstants.LEGACY_PING_PAYLOAD
            } else {
                buildSunshinePing(payload, sequence++)
            }
            try {
                socket.send(DatagramPacket(datagram, datagram.size, destination))
                pingsSent++
            } catch (io: IOException) {
                // Spec §7.5: "Ignore all send errors here — the host may not have bound its socket
                // yet, which produces ICMP port-unreachable."
                ProtocolLog.d(SessionConstants.TAG, "video keep-alive send failed: ${io.message}")
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
            .allocate(payload.size + SessionConstants.SS_PING_SEQUENCE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(payload)
        buffer.putInt(sequence)
        return buffer.array()
    }

    private fun joinQuietly(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(SessionConstants.THREAD_JOIN_TIMEOUT_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
