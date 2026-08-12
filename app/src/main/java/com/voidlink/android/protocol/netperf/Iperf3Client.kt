package com.voidlink.android.protocol.netperf

import com.voidlink.android.protocol.ProtocolLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * How long a blocking read waits before the control loop gets a turn.
 *
 * This is the resolution of both the progress display and of cancellation, so it is short. It is
 * not a timeout in the usual sense, and a read that hits it is never an error.
 */
private const val POLL_INTERVAL_MS: Int = 250

/** Sentinel distinguishing "the poll timeout fired" from a byte or from end-of-stream. */
private const val POLL_TICK = -99

private const val CONNECT_TIMEOUT_MS = 4_000
private const val MILLIS_PER_SECOND = 1_000
private const val NANOS_PER_SECOND = 1_000_000_000L

/** Head-room over the requested duration before the whole exchange is declared stuck. */
private const val SLACK_SECONDS = 25L

private const val UDP_HANDSHAKE_POLL_MS = 500
private const val UDP_HANDSHAKE_ATTEMPTS = 16

private const val RECEIVE_BUFFER_BYTES = 64 * 1024
private const val STREAM_RECEIVE_BUFFER_BYTES = 2 * 1024 * 1024

/** No legitimate iperf3 results document comes close; a larger claim is a protocol error. */
private const val MAX_JSON_BYTES = 4L * 1024L * 1024L

/** How an iperf3 run ended. */
sealed interface Iperf3Outcome {

    /**
     * The transfer completed.
     *
     * @property bytes payload bytes received during the measured window.
     * @property seconds length of the measured window.
     * @property packets datagrams received; zero for a TCP run.
     * @property lostPackets datagrams the sender's sequence numbers say never arrived.
     * @property lossPercent [lostPackets] as a share of what was apparently sent.
     * @property jitterMs RFC 1889 interarrival jitter; zero for a TCP run, which carries no
     *   per-packet timestamps to compute it from.
     */
    data class Success(
        val bytes: Long,
        val seconds: Double,
        val packets: Long,
        val lostPackets: Long,
        val lossPercent: Double,
        val jitterMs: Double,
    ) : Iperf3Outcome {
        /** Received rate over the measured window. */
        val megabitsPerSecond: Double
            get() = if (seconds <= 0.0) 0.0 else bytes * 8.0 / seconds / 1_000_000.0
    }

    /** Nothing is listening on the port — the overwhelmingly likely case, and it is actionable. */
    data class NotRunning(val detail: String) : Iperf3Outcome

    /** The address could not be reached at all. */
    data class Unreachable(val detail: String) : Iperf3Outcome

    /** An iperf3 server answered but is already serving another client. */
    data class Busy(val detail: String) : Iperf3Outcome

    /** The server reported a failure of its own, or ended the test early. */
    data class ServerFailed(val detail: String) : Iperf3Outcome

    /** Something answered on the port, but it does not speak iperf3. */
    data class Mismatch(val detail: String) : Iperf3Outcome

    /** The exchange stalled and the deadline passed. */
    data class TimedOut(val detail: String) : Iperf3Outcome
}

/**
 * A client for the iperf3 control protocol, in **reverse mode** — the server sends, we receive.
 *
 * Reverse is the only direction implemented, and deliberately so: video travels PC → handheld, so
 * the capacity worth knowing is the PC's ability to push. A forward test would measure the
 * handheld's uplink, which nothing in a game stream uses.
 *
 * Two transports:
 *
 * * **UDP at a fixed rate** — the primary mode. It answers "does this link carry the rate I intend
 *   to stream at, and what does it do to the packets", which is the question that predicts stutter.
 *   It reports loss and RFC 1889 jitter, the two things a real-time stream is destroyed by, and it
 *   is the same traffic shape as the stream itself.
 * * **TCP flat out** — the secondary mode, for finding the ceiling once the paced test passes and
 *   the user wants to know how much room is left.
 *
 * ### Why this is safe to run against a game host
 *
 * It never touches the game host's own services. `iperf3 -s` is a separate program on a separate
 * port that the user starts deliberately, so nothing here goes near the Sunshine/Apollo HTTPS
 * listener that leaks a socket per connection and serves them from one thread. That property is
 * the whole reason throughput is a second, opt-in tier rather than something bolted onto the
 * existing HTTP client.
 *
 * ### Protocol
 *
 * Implemented from `esnet/iperf` (`src/iperf_api.c`, `src/iperf_udp.c`) and the project wiki's
 * *IperfProtocolStates*. The byte layouts and JSON documents live in [Iperf3Protocol], which is
 * pure and unit-tested; this class is the socket half, which only a running `iperf3 -s` can test.
 *
 * ### Lifetime
 *
 * Every socket is closed on every path, including cancellation: blocking reads use a short poll
 * timeout so the loop notices a cancelled coroutine, and a completion handler closes the sockets
 * underneath a read that is already blocked.
 */
class Iperf3Client {

    /**
     * Runs one test to completion.
     *
     * Must be called from a dispatcher that tolerates blocking I/O; it does **not** switch context
     * itself, so a caller inside `flow { … }.flowOn(Dispatchers.IO)` can emit progress directly from
     * [onProgress] without breaking the flow's context preservation.
     *
     * @param hostAddress the PC's address; the same machine the stream would come from.
     * @param port the iperf3 control port, normally [Iperf3Protocol.DEFAULT_PORT].
     * @param udp true for the paced UDP test, false for the TCP throughput test.
     * @param seconds how long the transfer should run.
     * @param targetBitsPerSecond the rate to pace UDP at; ignored for TCP, which runs flat out.
     * @param onProgress called roughly [POLL_INTERVAL_MS] apart while the transfer runs.
     */
    suspend fun run(
        hostAddress: String,
        port: Int,
        udp: Boolean,
        seconds: Int,
        targetBitsPerSecond: Long,
        onProgress: suspend (elapsedSeconds: Double, megabitsPerSecond: Double) -> Unit,
    ): Iperf3Outcome = coroutineScope {
        val session = Session(
            hostAddress = hostAddress,
            port = port,
            udp = udp,
            seconds = seconds,
            targetBitsPerSecond = targetBitsPerSecond,
        )
        // A blocking read cannot be interrupted from outside; closing the socket under it can. The
        // poll timeout already bounds how long cancellation takes to be noticed, but a user who
        // taps Cancel should not have to wait even that long for the sockets to go away.
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) session.close()
        }
        try {
            session.execute(this, onProgress)
        } finally {
            cancelHandle?.dispose()
            session.close()
        }
    }

    /**
     * One test's sockets, counters and state machine.
     *
     * A short-lived object rather than a pile of local variables so that closing everything is a
     * single call that the cancellation handler and the `finally` can both make safely.
     */
    private class Session(
        private val hostAddress: String,
        private val port: Int,
        private val udp: Boolean,
        private val seconds: Int,
        private val targetBitsPerSecond: Long,
    ) {
        private val control = Socket()

        @Volatile
        private var tcpStream: Socket? = null

        @Volatile
        private var udpStream: DatagramSocket? = null

        @Volatile
        private var bytes: Long = 0L

        @Volatile
        private var counting: Boolean = false

        @Volatile
        private var stopped: Boolean = false

        private val sequences = Iperf3Protocol.SequenceTracker()
        private val jitter = Iperf3Protocol.JitterEstimator()

        /** Closes every socket this session opened. Safe to call repeatedly and from any thread. */
        fun close() {
            stopped = true
            runCatching { control.close() }
            runCatching { tcpStream?.close() }
            runCatching { udpStream?.close() }
        }

        /**
         * Drives the whole exchange.
         *
         * @param scope used only to launch the receive loop, which has to run alongside the control
         *   channel rather than after it.
         */
        suspend fun execute(
            scope: CoroutineScope,
            onProgress: suspend (Double, Double) -> Unit,
        ): Iperf3Outcome {
            try {
                control.tcpNoDelay = true
                control.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
                control.soTimeout = POLL_INTERVAL_MS
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return classifyConnectFailure(failure)
            }

            val input = control.getInputStream()
            val output = control.getOutputStream()
            val cookie = Iperf3Protocol.makeCookie()
            output.write(cookie)
            output.flush()

            val deadlineNanos =
                System.nanoTime() + (seconds.toLong() + SLACK_SECONDS) * NANOS_PER_SECOND
            val durationNanos = seconds.toLong() * NANOS_PER_SECOND

            var receiver: Job? = null
            var sawParamExchange = false
            var startNanos = 0L
            var measuredNanos = 0L
            var endSent = false

            while (true) {
                coroutineContext.ensureActive()
                if (System.nanoTime() > deadlineNanos) {
                    return Iperf3Outcome.TimedOut(
                        "The iperf3 server stopped answering part-way through the test.",
                    )
                }

                val raw = try {
                    input.read()
                } catch (timeout: SocketTimeoutException) {
                    POLL_TICK
                } catch (failure: IOException) {
                    return if (sawParamExchange) {
                        Iperf3Outcome.ServerFailed(
                            "The connection to the iperf3 server dropped: ${failure.message}",
                        )
                    } else {
                        Iperf3Outcome.Mismatch(
                            "The connection to $hostAddress:$port dropped before the iperf3 " +
                                "handshake finished (${failure.message}).",
                        )
                    }
                }

                if (raw == POLL_TICK) {
                    if (counting && !endSent) {
                        val elapsed = System.nanoTime() - startNanos
                        if (elapsed >= durationNanos) {
                            measuredNanos = elapsed
                            counting = false
                            endSent = true
                            writeState(output, Iperf3Protocol.TEST_END)
                        } else {
                            reportProgress(elapsed, onProgress)
                        }
                    }
                    continue
                }

                if (raw < 0) {
                    return if (sawParamExchange) {
                        Iperf3Outcome.ServerFailed("The iperf3 server closed the connection.")
                    } else {
                        Iperf3Outcome.Mismatch(
                            "Something is listening on $hostAddress:$port but it closed the " +
                                "connection without answering. It does not look like iperf3.",
                        )
                    }
                }

                // The wire carries a signed byte: ACCESS_DENIED and SERVER_ERROR are negative.
                when (val state = raw.toByte().toInt()) {
                    Iperf3Protocol.PARAM_EXCHANGE -> {
                        sawParamExchange = true
                        writeJson(
                            output,
                            Iperf3Protocol.parametersJson(
                                udp = udp,
                                seconds = seconds,
                                bitsPerSecond = targetBitsPerSecond,
                                blockBytes = if (udp) {
                                    Iperf3Protocol.UDP_BLOCK_BYTES
                                } else {
                                    Iperf3Protocol.TCP_BLOCK_BYTES
                                },
                            ),
                        )
                    }

                    Iperf3Protocol.CREATE_STREAMS -> {
                        if (udp) {
                            val opened = openUdpStream()
                                ?: return Iperf3Outcome.Mismatch(
                                    "The iperf3 server never acknowledged the UDP stream. Some " +
                                        "networks block UDP; try the TCP test instead.",
                                )
                            udpStream = opened
                        } else {
                            val opened = openTcpStream(cookie)
                                ?: return Iperf3Outcome.ServerFailed(
                                    "The iperf3 server would not accept a data connection on " +
                                        "$hostAddress:$port.",
                                )
                            tcpStream = opened
                        }
                    }

                    // The transfer's clock is ours to run, so there is nothing to do here; the
                    // server is simply telling us it is ready.
                    Iperf3Protocol.TEST_START -> Unit

                    Iperf3Protocol.TEST_RUNNING -> {
                        startNanos = System.nanoTime()
                        counting = true
                        val datagrams = udpStream
                        val stream = tcpStream
                        receiver = when {
                            datagrams != null -> scope.launch { receiveUdp(datagrams) }
                            stream != null -> scope.launch { receiveTcp(stream) }
                            else -> null
                        }
                    }

                    Iperf3Protocol.EXCHANGE_RESULTS -> {
                        if (measuredNanos == 0L && startNanos != 0L) {
                            measuredNanos = System.nanoTime() - startNanos
                        }
                        counting = false
                        stopped = true
                        runCatching { udpStream?.close() }
                        runCatching { tcpStream?.close() }
                        receiver?.join()
                        receiver = null

                        writeJson(
                            output,
                            Iperf3Protocol.resultsJson(
                                bytes = bytes,
                                packets = sequences.received,
                                lostPackets = sequences.lost,
                                jitterSeconds = jitter.jitterSeconds,
                                seconds = measuredNanos.toDouble() / NANOS_PER_SECOND,
                            ),
                        )
                        // The server's own results are read purely to keep the exchange in step.
                        // Our figures come from what actually arrived here, which is the only side
                        // of the link the user is asking about.
                        readJson(input, deadlineNanos)
                    }

                    Iperf3Protocol.DISPLAY_RESULTS, Iperf3Protocol.IPERF_DONE -> {
                        if (measuredNanos == 0L && startNanos != 0L) {
                            measuredNanos = System.nanoTime() - startNanos
                        }
                        runCatching { writeState(output, Iperf3Protocol.IPERF_DONE) }
                        return success(measuredNanos)
                    }

                    Iperf3Protocol.ACCESS_DENIED -> return Iperf3Outcome.Busy(
                        "The iperf3 server is already running a test for another client.",
                    )

                    Iperf3Protocol.SERVER_ERROR -> {
                        val code = readInt32(input, deadlineNanos)
                        val systemErrno = readInt32(input, deadlineNanos)
                        return Iperf3Outcome.ServerFailed(
                            "The iperf3 server reported an error (code $code, errno $systemErrno).",
                        )
                    }

                    Iperf3Protocol.SERVER_TERMINATE, Iperf3Protocol.CLIENT_TERMINATE ->
                        return Iperf3Outcome.ServerFailed(
                            "The iperf3 server ended the test early.",
                        )

                    else -> return Iperf3Outcome.Mismatch(
                        "Unexpected iperf3 message ${Iperf3Protocol.stateName(state)} from " +
                            "$hostAddress:$port.",
                    )
                }
            }
        }

        private fun success(measuredNanos: Long): Iperf3Outcome = Iperf3Outcome.Success(
            bytes = bytes,
            seconds = measuredNanos.toDouble() / NANOS_PER_SECOND,
            packets = sequences.received,
            lostPackets = sequences.lost,
            lossPercent = sequences.lossPercent(),
            jitterMs = jitter.jitterSeconds * 1_000.0,
        )

        private suspend fun reportProgress(
            elapsedNanos: Long,
            onProgress: suspend (Double, Double) -> Unit,
        ) {
            val elapsedSeconds = elapsedNanos.toDouble() / NANOS_PER_SECOND
            if (elapsedSeconds <= 0.0) return
            val megabits = bytes * 8.0 / 1_000_000.0
            onProgress(elapsedSeconds, megabits / elapsedSeconds)
        }

        /** Opens the TCP data connection and identifies it with the session cookie. */
        private fun openTcpStream(cookie: ByteArray): Socket? {
            var opened: Socket? = null
            return try {
                val socket = Socket()
                opened = socket
                socket.tcpNoDelay = true
                // A small receive window throttles the sender, which would make this measure the
                // buffer rather than the link. Best effort — the platform may clamp it.
                runCatching { socket.receiveBufferSize = STREAM_RECEIVE_BUFFER_BYTES }
                socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = POLL_INTERVAL_MS
                val stream = socket.getOutputStream()
                stream.write(cookie)
                stream.flush()
                socket
            } catch (failure: IOException) {
                ProtocolLog.w(ProtocolLog.TAG_NETPERF, "iperf3 TCP stream setup failed", failure)
                runCatching { opened?.close() }
                null
            }
        }

        /**
         * Opens the UDP data socket and completes iperf3's little UDP handshake.
         *
         * A server has no way to learn a UDP client's address other than by being sent something,
         * so the client sends a fixed four-byte message and waits for a fixed four-byte reply. In
         * reverse mode a data datagram can overtake that reply, so several are tolerated before
         * giving up. No cookie is involved: a UDP stream is identified by its source address, which
         * is precisely what this handshake tells the server.
         *
         * @return the connected socket, or `null` when the server never acknowledged.
         */
        private fun openUdpStream(): DatagramSocket? {
            var opened: DatagramSocket? = null
            return try {
                val socket = DatagramSocket()
                opened = socket
                runCatching { socket.receiveBufferSize = STREAM_RECEIVE_BUFFER_BYTES }
                socket.connect(InetAddress.getByName(hostAddress), port)
                socket.soTimeout = UDP_HANDSHAKE_POLL_MS
                socket.send(
                    DatagramPacket(
                        Iperf3Protocol.UDP_CONNECT_MSG,
                        Iperf3Protocol.UDP_CONNECT_MSG.size,
                    ),
                )

                val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
                val packet = DatagramPacket(buffer, buffer.size)
                var acknowledged = false
                var attempts = 0
                while (attempts < UDP_HANDSHAKE_ATTEMPTS && !acknowledged) {
                    attempts++
                    try {
                        packet.setLength(buffer.size)
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    }
                    val length = packet.length
                    acknowledged =
                        Iperf3Protocol.startsWith(buffer, length, Iperf3Protocol.UDP_CONNECT_REPLY) ||
                        Iperf3Protocol.startsWith(
                            buffer,
                            length,
                            Iperf3Protocol.LEGACY_UDP_CONNECT_REPLY,
                        )
                }
                if (!acknowledged) {
                    runCatching { socket.close() }
                    null
                } else {
                    socket.soTimeout = POLL_INTERVAL_MS
                    socket
                }
            } catch (failure: IOException) {
                ProtocolLog.w(ProtocolLog.TAG_NETPERF, "iperf3 UDP stream setup failed", failure)
                runCatching { opened?.close() }
                null
            }
        }

        /** Drains the TCP data connection, counting payload bytes. */
        private fun receiveTcp(socket: Socket) {
            val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
            val input = try {
                socket.getInputStream()
            } catch (failure: IOException) {
                return
            }
            while (!stopped) {
                val read = try {
                    input.read(buffer)
                } catch (timeout: SocketTimeoutException) {
                    continue
                } catch (failure: IOException) {
                    return
                }
                if (read < 0) return
                if (counting) bytes += read.toLong()
            }
        }

        /**
         * Drains the UDP data socket, counting bytes and folding each datagram into the loss and
         * jitter estimators.
         */
        private fun receiveUdp(socket: DatagramSocket) {
            val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
            val packet = DatagramPacket(buffer, buffer.size)
            while (!stopped) {
                try {
                    packet.setLength(buffer.size)
                    socket.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                } catch (failure: IOException) {
                    return
                }
                if (!counting) continue
                val length = packet.length
                bytes += length.toLong()
                val sequence = Iperf3Protocol.sequenceNumber(buffer, length) ?: continue
                sequences.accept(sequence)
                val sentAt = Iperf3Protocol.sentAtSeconds(buffer, length) ?: continue
                // Monotonic on this side, wall clock on the other. Only the difference between two
                // consecutive transit times is used, so the constant offset between the two clocks
                // cancels — which is the whole trick behind RFC 1889 jitter.
                val arrival = System.nanoTime().toDouble() / NANOS_PER_SECOND
                jitter.accept(arrival - sentAt)
            }
        }

        // ---- Control-channel framing -------------------------------------------------------

        private fun writeState(output: OutputStream, state: Int) {
            output.write(byteArrayOf(state.toByte()))
            output.flush()
        }

        private fun writeJson(output: OutputStream, json: String) {
            val payload = json.toByteArray(Charsets.UTF_8)
            val header = ByteArray(4)
            header[0] = ((payload.size ushr 24) and 0xFF).toByte()
            header[1] = ((payload.size ushr 16) and 0xFF).toByte()
            header[2] = ((payload.size ushr 8) and 0xFF).toByte()
            header[3] = (payload.size and 0xFF).toByte()
            output.write(header)
            output.write(payload)
            output.flush()
        }

        /** Reads one length-prefixed JSON document and discards it. */
        private suspend fun readJson(input: InputStream, deadlineNanos: Long) {
            val header = ByteArray(4)
            if (!readFully(input, header, 4, deadlineNanos)) return
            val size = Iperf3Protocol.readUInt32(header, 0)
            if (size <= 0L || size > MAX_JSON_BYTES) return
            readFully(input, ByteArray(size.toInt()), size.toInt(), deadlineNanos)
        }

        private suspend fun readInt32(input: InputStream, deadlineNanos: Long): Long {
            val buffer = ByteArray(4)
            if (!readFully(input, buffer, 4, deadlineNanos)) return -1L
            return Iperf3Protocol.readUInt32(buffer, 0)
        }

        /**
         * Fills [count] bytes of [buffer], retrying across the socket's short poll timeout.
         *
         * That timeout is deliberately far shorter than a document takes to arrive, because it is
         * also what makes the state loop responsive to cancellation. A partial read is therefore
         * normal here and is not an error.
         */
        private suspend fun readFully(
            input: InputStream,
            buffer: ByteArray,
            count: Int,
            deadlineNanos: Long,
        ): Boolean {
            var filled = 0
            while (filled < count) {
                coroutineContext.ensureActive()
                if (System.nanoTime() > deadlineNanos) return false
                val read = try {
                    input.read(buffer, filled, count - filled)
                } catch (timeout: SocketTimeoutException) {
                    continue
                } catch (failure: IOException) {
                    return false
                }
                if (read < 0) return false
                filled += read
            }
            return true
        }

        private fun classifyConnectFailure(failure: Throwable): Iperf3Outcome {
            val message = failure.message.orEmpty()
            ProtocolLog.i(
                ProtocolLog.TAG_NETPERF,
                "iperf3 connect to $hostAddress:$port failed: " +
                    "${failure.javaClass.simpleName}: $message",
            )
            return when {
                // The one users will actually hit, and the one worth its own sentence: the PC is
                // reachable, the port simply has nothing behind it.
                failure is ConnectException && message.contains("refused", ignoreCase = true) ->
                    Iperf3Outcome.NotRunning("Nothing is listening on $hostAddress:$port.")

                failure is SocketTimeoutException -> Iperf3Outcome.Unreachable(
                    "$hostAddress:$port did not answer within " +
                        "${CONNECT_TIMEOUT_MS / MILLIS_PER_SECOND} seconds.",
                )

                failure is UnknownHostException -> Iperf3Outcome.Unreachable(
                    "The address $hostAddress could not be resolved.",
                )

                else -> Iperf3Outcome.Unreachable("Could not reach $hostAddress:$port: $message")
            }
        }
    }
}
