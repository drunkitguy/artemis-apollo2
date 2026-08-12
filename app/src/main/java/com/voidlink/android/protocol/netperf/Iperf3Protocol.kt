package com.voidlink.android.protocol.netperf

import kotlin.math.abs
import kotlin.random.Random

/**
 * Everything the iperf3 control protocol is made of, in one place.
 *
 * iperf3's wire protocol is small and documented (`esnet/iperf` wiki, *IperfProtocolStates*, plus
 * `src/iperf_api.c`), which is what makes re-implementing the client side reasonable:
 *
 * 1. The client opens a TCP **control connection** and writes a [COOKIE_SIZE]-byte cookie.
 * 2. From then on the server drives the exchange by sending **one signed byte per state change**.
 *    Two of those states carry a JSON document, length-prefixed by a 32-bit big-endian count.
 * 3. On `CREATE_STREAMS` the client opens the **data connection(s)** and writes the same cookie, so
 *    the server can tie them to the control session.
 * 4. The client ends the transfer by sending `TEST_END` itself; results are then exchanged as JSON
 *    in both directions and the client says `IPERF_DONE`.
 *
 * Everything in this file is pure: byte layouts, JSON text and the two running estimators. It is
 * therefore unit-testable without a server, which matters because the only way to test the socket
 * half is to have an `iperf3 -s` running.
 */
object Iperf3Protocol {

    /** `iperf3 -s` listens here unless started with `-p`. */
    const val DEFAULT_PORT: Int = 5201

    /**
     * The cookie is 36 random characters plus a NUL terminator: `COOKIE_SIZE` in `iperf.h` is 37 and
     * the C code writes the whole buffer, terminator included.
     */
    const val COOKIE_SIZE: Int = 37

    /** The alphabet `make_cookie` draws from — lower-case letters plus the digits 2-7. */
    const val COOKIE_ALPHABET: String = "abcdefghijklmnopqrstuvwxyz234567"

    // ---- Protocol states (signed bytes; `iperf_api.h`) ------------------------------------------

    const val TEST_START: Int = 1
    const val TEST_RUNNING: Int = 2
    const val TEST_END: Int = 4
    const val PARAM_EXCHANGE: Int = 9
    const val CREATE_STREAMS: Int = 10
    const val SERVER_TERMINATE: Int = 11
    const val CLIENT_TERMINATE: Int = 12
    const val EXCHANGE_RESULTS: Int = 13
    const val DISPLAY_RESULTS: Int = 14
    const val IPERF_START: Int = 15
    const val IPERF_DONE: Int = 16

    /** The server is already running a test for somebody else. */
    const val ACCESS_DENIED: Int = -1

    /** The server hit an error of its own; two big-endian 32-bit codes follow on the wire. */
    const val SERVER_ERROR: Int = -2

    /** Names a state byte for a log line or an error message. */
    fun stateName(state: Int): String = when (state) {
        TEST_START -> "TEST_START"
        TEST_RUNNING -> "TEST_RUNNING"
        TEST_END -> "TEST_END"
        PARAM_EXCHANGE -> "PARAM_EXCHANGE"
        CREATE_STREAMS -> "CREATE_STREAMS"
        SERVER_TERMINATE -> "SERVER_TERMINATE"
        CLIENT_TERMINATE -> "CLIENT_TERMINATE"
        EXCHANGE_RESULTS -> "EXCHANGE_RESULTS"
        DISPLAY_RESULTS -> "DISPLAY_RESULTS"
        IPERF_START -> "IPERF_START"
        IPERF_DONE -> "IPERF_DONE"
        ACCESS_DENIED -> "ACCESS_DENIED"
        SERVER_ERROR -> "SERVER_ERROR"
        else -> "state $state"
    }

    // ---- UDP data stream ------------------------------------------------------------------------

    /**
     * The four bytes a UDP client sends so the server learns its address.
     *
     * `iperf.h` defines this as an integer written in host order, with a different constant per
     * endianness precisely so that **the bytes on the wire are the same either way**. Those bytes
     * are what we write, which sidesteps the whole question.
     */
    val UDP_CONNECT_MSG: ByteArray = byteArrayOf(0x39, 0x38, 0x37, 0x36)

    /** The server's acknowledgement of [UDP_CONNECT_MSG]. */
    val UDP_CONNECT_REPLY: ByteArray = byteArrayOf(0x36, 0x37, 0x38, 0x39)

    /** What servers older than iperf 3.16 reply with; still accepted. */
    val LEGACY_UDP_CONNECT_REPLY: ByteArray =
        byteArrayOf(0xB1.toByte(), 0x68, 0xDE.toByte(), 0x3A)

    /**
     * Bytes of header at the front of every UDP datagram: seconds, microseconds, sequence number,
     * each a 32-bit big-endian value.
     *
     * iperf3 can be asked for 64-bit sequence numbers; we never ask, so the sender uses the 32-bit
     * layout and this is the only header we have to parse.
     */
    const val UDP_HEADER_BYTES: Int = 12

    /**
     * Payload size for UDP datagrams.
     *
     * Comfortably below a 1500-byte Ethernet MTU once IP and UDP headers are counted, with room to
     * spare for a tunnel or a PPPoE link. A datagram that fragments would measure the fragmentation
     * rather than the link.
     */
    const val UDP_BLOCK_BYTES: Int = 1_400

    /** TCP send size. iperf3's own default, and large enough that syscall overhead is invisible. */
    const val TCP_BLOCK_BYTES: Int = 128 * 1_024

    /**
     * The sequence number carried by a datagram, or `null` when the datagram is too short to be one.
     *
     * @param buffer the received datagram.
     * @param length how many bytes of it are valid.
     */
    fun sequenceNumber(buffer: ByteArray, length: Int): Long? {
        if (length < UDP_HEADER_BYTES) return null
        return readUInt32(buffer, 8)
    }

    /**
     * The sender's timestamp, in seconds, or `null` when the datagram is too short.
     *
     * The sender's and receiver's clocks are not synchronised, so this value is meaningless on its
     * own. Only differences between successive datagrams are used, and the constant offset cancels
     * — which is exactly why RFC 1889 jitter needs no clock synchronisation.
     */
    fun sentAtSeconds(buffer: ByteArray, length: Int): Double? {
        if (length < UDP_HEADER_BYTES) return null
        val seconds = readUInt32(buffer, 0)
        val micros = readUInt32(buffer, 4)
        return seconds + micros / 1_000_000.0
    }

    /** Reads a 32-bit big-endian unsigned value as a [Long] so the sign bit cannot bite. */
    fun readUInt32(buffer: ByteArray, offset: Int): Long {
        val b0 = (buffer[offset].toLong() and 0xFF) shl 24
        val b1 = (buffer[offset + 1].toLong() and 0xFF) shl 16
        val b2 = (buffer[offset + 2].toLong() and 0xFF) shl 8
        val b3 = buffer[offset + 3].toLong() and 0xFF
        return b0 or b1 or b2 or b3
    }

    /** True when [buffer] starts with [expected]. */
    fun startsWith(buffer: ByteArray, length: Int, expected: ByteArray): Boolean {
        if (length < expected.size) return false
        for (index in expected.indices) {
            if (buffer[index] != expected[index]) return false
        }
        return true
    }

    // ---- JSON documents -------------------------------------------------------------------------

    /**
     * The parameter document the client sends when the server asks for `PARAM_EXCHANGE`.
     *
     * Written by hand rather than through a serializer because the shape is fixed and the server is
     * strict about types: iperf 3.16 and later check that `tcp`/`udp`/`reverse` are JSON `true` and
     * that every other value is a JSON number, and a serializer that emitted `1` for a boolean
     * would be rejected with an unhelpful error.
     *
     * `reverse` is always set: video flows PC → handheld, so the direction worth measuring is the
     * one where the server sends and we receive.
     *
     * @param udp true for a paced UDP test, false for a TCP throughput test.
     * @param seconds how long the transfer should run.
     * @param bitsPerSecond the rate to pace UDP at; ignored (and omitted) for TCP, which runs flat
     *   out.
     * @param blockBytes payload size the server should send in.
     */
    fun parametersJson(
        udp: Boolean,
        seconds: Int,
        bitsPerSecond: Long,
        blockBytes: Int,
    ): String = buildString {
        append('{')
        append(if (udp) "\"udp\":true" else "\"tcp\":true")
        append(",\"omit\":0")
        append(",\"time\":").append(seconds)
        append(",\"num\":0")
        append(",\"blockcount\":0")
        append(",\"parallel\":1")
        append(",\"reverse\":true")
        append(",\"len\":").append(blockBytes)
        if (udp) {
            append(",\"bandwidth\":").append(bitsPerSecond)
        }
        append(",\"client_version\":\"").append(CLIENT_VERSION).append('"')
        append('}')
    }

    /**
     * The version string we claim.
     *
     * Servers record it and print it; none of them gate on it. It names the protocol generation
     * this implementation follows, not a build of the C program.
     */
    const val CLIENT_VERSION: String = "3.16"

    /**
     * The results document the client sends during `EXCHANGE_RESULTS`.
     *
     * The server parses this to render its own summary, and it is strict about which keys must be
     * present: `cpu_util_total`/`user`/`system`, `sender_has_retransmits`, and a `streams` array
     * whose entries carry `id`, `bytes`, `retransmits`, `jitter`, `errors` and `packets`. It also
     * insists that `omitted_errors` and `omitted_packets` are either both present or both absent.
     * Leaving any of them out makes the server abort the test at the last moment.
     *
     * `sender_has_retransmits` is `-1` because in reverse mode we are the receiver and have no
     * retransmit count to report — that is the value iperf3's own client sends in this position.
     */
    fun resultsJson(
        bytes: Long,
        packets: Long,
        lostPackets: Long,
        jitterSeconds: Double,
        seconds: Double,
    ): String = buildString {
        append("{\"cpu_util_total\":0,\"cpu_util_user\":0,\"cpu_util_system\":0")
        append(",\"sender_has_retransmits\":-1")
        append(",\"streams\":[{\"id\":1")
        append(",\"bytes\":").append(bytes)
        append(",\"retransmits\":-1")
        append(",\"jitter\":").append(jsonNumber(jitterSeconds))
        append(",\"errors\":").append(lostPackets)
        append(",\"omitted_errors\":0")
        append(",\"packets\":").append(packets)
        append(",\"omitted_packets\":0")
        append(",\"start_time\":0")
        append(",\"end_time\":").append(jsonNumber(seconds))
        append("}]}")
    }

    /**
     * Renders a double as JSON, with no locale and no exponent.
     *
     * `toString()` on a Kotlin Double can produce `1.0E-5`, which cJSON parses, and `NaN`, which it
     * does not. Six decimal places is far finer than either figure needs.
     */
    fun jsonNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        return String.format(java.util.Locale.US, "%.6f", value)
    }

    // ---- Estimators -----------------------------------------------------------------------------

    /**
     * Generates a fresh session cookie.
     *
     * 36 characters from [COOKIE_ALPHABET] followed by a NUL, matching `make_cookie`. Only the
     * server's equality check depends on it, so an ordinary random source is enough; nothing here
     * is a secret.
     */
    fun makeCookie(random: Random = Random.Default): ByteArray {
        val cookie = ByteArray(COOKIE_SIZE)
        for (index in 0 until COOKIE_SIZE - 1) {
            cookie[index] = COOKIE_ALPHABET[random.nextInt(COOKIE_ALPHABET.length)].code.toByte()
        }
        cookie[COOKIE_SIZE - 1] = 0
        return cookie
    }

    /**
     * The running loss and reordering count iperf3 keeps for a UDP stream.
     *
     * Sequence numbers that jump forward leave a gap, and the gap is counted as loss. One that goes
     * backwards is a reordered datagram, and it cancels one previously counted loss — otherwise a
     * network that merely shuffles packets would be reported as one that drops them, and the user
     * would go looking for the wrong fault.
     */
    class SequenceTracker {
        /** Highest sequence number seen so far. */
        var highest: Long = 0L
            private set

        /** How many datagrams appear to be missing. */
        var lost: Long = 0L
            private set

        /** How many arrived after a later one. */
        var outOfOrder: Long = 0L
            private set

        /** How many datagrams have been accounted for. */
        var received: Long = 0L
            private set

        /** Folds one datagram's sequence number in. */
        fun accept(sequence: Long) {
            received++
            if (sequence >= highest + 1) {
                if (sequence > highest + 1) {
                    lost += (sequence - 1) - highest
                }
                highest = sequence
            } else {
                outOfOrder++
                if (lost > 0) lost--
            }
        }

        /** Loss as a percentage of what the sender appears to have sent. */
        fun lossPercent(): Double {
            val expected = received + lost
            if (expected <= 0L) return 0.0
            return lost * 100.0 / expected
        }
    }

    /**
     * RFC 1889 §6.3.1 interarrival jitter, the estimator iperf3 reports.
     *
     * `J += (|D(i-1, i)| - J) / 16`, where `D` is the difference between the transit times of two
     * consecutive datagrams. Transit time is arrival minus the sender's timestamp; the clocks are
     * unrelated, but the constant offset cancels in the difference, so no synchronisation is needed.
     *
     * The divisor of 16 is the standard first-order smoothing: it makes the figure track sustained
     * variation rather than jumping on a single late packet.
     */
    class JitterEstimator {
        private var previousTransit: Double = 0.0
        private var seenOne: Boolean = false

        /** The current estimate, in seconds. */
        var jitterSeconds: Double = 0.0
            private set

        /**
         * Folds in one datagram.
         *
         * @param transitSeconds arrival time minus the sender's timestamp, in seconds.
         */
        fun accept(transitSeconds: Double) {
            if (!seenOne) {
                seenOne = true
                previousTransit = transitSeconds
                return
            }
            val delta = transitSeconds - previousTransit
            previousTransit = transitSeconds
            jitterSeconds += (abs(delta) - jitterSeconds) / JITTER_SMOOTHING
        }

        private companion object {
            const val JITTER_SMOOTHING = 16.0
        }
    }
}
