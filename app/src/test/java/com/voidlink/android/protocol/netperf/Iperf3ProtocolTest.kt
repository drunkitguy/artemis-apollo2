package com.voidlink.android.protocol.netperf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Covers the byte layouts, JSON documents and estimators of the iperf3 client.
 *
 * This is the half of the implementation that can be tested without a server. The socket half needs
 * a real `iperf3 -s`, so everything that *can* be pinned down here is, including the exact bytes of
 * the UDP handshake — a byte-order mistake there would look like "the server never answered", which
 * is indistinguishable from a firewall and would cost hours to diagnose on real hardware.
 */
class Iperf3ProtocolTest {

    // ---- Cookie ------------------------------------------------------------------------------

    @Test
    fun `the cookie is 36 characters plus a NUL, matching COOKIE_SIZE in iperf_h`() {
        val cookie = Iperf3Protocol.makeCookie(Random(1))

        assertEquals(37, cookie.size)
        assertEquals(0.toByte(), cookie[36])
        val allowed = Iperf3Protocol.COOKIE_ALPHABET.map { it.code.toByte() }.toSet()
        for (index in 0 until 36) {
            assertTrue(
                "cookie byte ${cookie[index]} at $index is outside iperf3's alphabet",
                cookie[index] in allowed,
            )
        }
    }

    @Test
    fun `two cookies differ, so two sessions cannot be confused for one`() {
        val first = Iperf3Protocol.makeCookie(Random(1))
        val second = Iperf3Protocol.makeCookie(Random(2))

        assertFalse(first.contentEquals(second))
    }

    // ---- Parameters --------------------------------------------------------------------------

    @Test
    fun `TCP parameters ask for a reverse test with JSON booleans, not numbers`() {
        val json = Iperf3Protocol.parametersJson(
            udp = false,
            seconds = 10,
            bitsPerSecond = 0L,
            blockBytes = Iperf3Protocol.TCP_BLOCK_BYTES,
        )

        // iperf 3.16+ type-checks these: a `1` in place of `true` is rejected outright.
        assertTrue(json.contains("\"tcp\":true"))
        assertTrue(json.contains("\"reverse\":true"))
        assertFalse(json.contains("\"udp\""))
        // TCP runs flat out, so no rate is offered.
        assertFalse(json.contains("\"bandwidth\""))
        assertTrue(json.contains("\"time\":10"))
        assertTrue(json.contains("\"parallel\":1"))
        assertTrue(json.contains("\"omit\":0"))
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun `UDP parameters carry the target rate in bits per second`() {
        val json = Iperf3Protocol.parametersJson(
            udp = true,
            seconds = 10,
            bitsPerSecond = 37_500_000L,
            blockBytes = Iperf3Protocol.UDP_BLOCK_BYTES,
        )

        assertTrue(json.contains("\"udp\":true"))
        assertFalse(json.contains("\"tcp\""))
        assertTrue(json.contains("\"bandwidth\":37500000"))
        assertTrue(json.contains("\"len\":1400"))
    }

    @Test
    fun `the UDP block fits inside an ordinary Ethernet MTU`() {
        // A datagram that fragments measures the fragmentation, not the link.
        assertTrue(Iperf3Protocol.UDP_BLOCK_BYTES + 28 <= 1_500)
    }

    // ---- Results -----------------------------------------------------------------------------

    @Test
    fun `results carry every key the server refuses to proceed without`() {
        val json = Iperf3Protocol.resultsJson(
            bytes = 46_875_000L,
            packets = 33_482L,
            lostPackets = 12L,
            jitterSeconds = 0.0014,
            seconds = 10.0,
        )

        for (key in REQUIRED_RESULT_KEYS) {
            assertTrue("results JSON is missing \"$key\"", json.contains("\"$key\""))
        }
        // The server insists these two are either both present or both absent.
        assertTrue(json.contains("\"omitted_errors\""))
        assertTrue(json.contains("\"omitted_packets\""))
        // We are the receiver in reverse mode and have no retransmit count to report; -1 is what
        // iperf3's own client sends in this position.
        assertTrue(json.contains("\"sender_has_retransmits\":-1"))
        assertTrue(json.contains("\"bytes\":46875000"))
        assertTrue(json.contains("\"errors\":12"))
    }

    @Test
    fun `JSON numbers never use exponents or a locale decimal separator`() {
        assertEquals("0.000010", Iperf3Protocol.jsonNumber(0.00001))
        assertEquals("10.000000", Iperf3Protocol.jsonNumber(10.0))
        // cJSON cannot parse NaN, so a degenerate estimate must degrade to a number.
        assertEquals("0", Iperf3Protocol.jsonNumber(Double.NaN))
        assertEquals("0", Iperf3Protocol.jsonNumber(Double.POSITIVE_INFINITY))
    }

    // ---- UDP handshake bytes -----------------------------------------------------------------

    @Test
    fun `the UDP handshake constants are the bytes iperf3 puts on the wire`() {
        // iperf.h defines a different integer per host endianness precisely so that the bytes are
        // identical either way; these are those bytes.
        assertArrayEquals(byteArrayOf(0x39, 0x38, 0x37, 0x36), Iperf3Protocol.UDP_CONNECT_MSG)
        assertArrayEquals(byteArrayOf(0x36, 0x37, 0x38, 0x39), Iperf3Protocol.UDP_CONNECT_REPLY)
        assertArrayEquals(
            byteArrayOf(0xB1.toByte(), 0x68, 0xDE.toByte(), 0x3A),
            Iperf3Protocol.LEGACY_UDP_CONNECT_REPLY,
        )
    }

    @Test
    fun `a reply is recognised even when the datagram carries more after it`() {
        val datagram = ByteArray(64)
        Iperf3Protocol.UDP_CONNECT_REPLY.copyInto(datagram)

        assertTrue(
            Iperf3Protocol.startsWith(datagram, 64, Iperf3Protocol.UDP_CONNECT_REPLY),
        )
        assertFalse(
            Iperf3Protocol.startsWith(datagram, 64, Iperf3Protocol.UDP_CONNECT_MSG),
        )
        // A truncated datagram must never be read as a match.
        assertFalse(
            Iperf3Protocol.startsWith(datagram, 3, Iperf3Protocol.UDP_CONNECT_REPLY),
        )
    }

    // ---- Datagram header ---------------------------------------------------------------------

    @Test
    fun `the datagram header is three big-endian 32-bit fields`() {
        val datagram = ByteArray(1_400)
        // seconds = 0x01020304, micros = 500_000, sequence = 0x000186A0 (100_000)
        writeUInt32(datagram, 0, 0x01020304L)
        writeUInt32(datagram, 4, 500_000L)
        writeUInt32(datagram, 8, 100_000L)

        assertEquals(100_000L, Iperf3Protocol.sequenceNumber(datagram, 1_400))
        val sentAt = Iperf3Protocol.sentAtSeconds(datagram, 1_400)
        assertEquals(0x01020304L + 0.5, sentAt!!, 1e-9)
    }

    @Test
    fun `a sequence number with the high bit set is read as unsigned`() {
        val datagram = ByteArray(16)
        writeUInt32(datagram, 8, 0xFFFFFFFFL)

        assertEquals(4_294_967_295L, Iperf3Protocol.sequenceNumber(datagram, 16))
    }

    @Test
    fun `a runt datagram carries no header and is reported as such`() {
        assertNull(Iperf3Protocol.sequenceNumber(ByteArray(8), 8))
        assertNull(Iperf3Protocol.sentAtSeconds(ByteArray(8), 8))
    }

    // ---- Loss and reordering -----------------------------------------------------------------

    @Test
    fun `an unbroken sequence reports no loss`() {
        val tracker = Iperf3Protocol.SequenceTracker()
        for (sequence in 1L..100L) tracker.accept(sequence)

        assertEquals(100L, tracker.received)
        assertEquals(0L, tracker.lost)
        assertEquals(0.0, tracker.lossPercent(), 1e-9)
    }

    @Test
    fun `a gap in the sequence is counted as loss`() {
        val tracker = Iperf3Protocol.SequenceTracker()
        tracker.accept(1L)
        tracker.accept(2L)
        // 3, 4 and 5 never arrived.
        tracker.accept(6L)

        assertEquals(3L, tracker.lost)
        assertEquals(3, tracker.received.toInt())
        assertEquals(50.0, tracker.lossPercent(), 1e-9)
    }

    @Test
    fun `a reordered datagram cancels a loss instead of adding one`() {
        // A network that merely shuffles packets is not one that drops them, and telling the user
        // otherwise sends them hunting for the wrong fault.
        val tracker = Iperf3Protocol.SequenceTracker()
        tracker.accept(1L)
        tracker.accept(3L)
        assertEquals(1L, tracker.lost)

        tracker.accept(2L)

        assertEquals(0L, tracker.lost)
        assertEquals(1L, tracker.outOfOrder)
        assertEquals(0.0, tracker.lossPercent(), 1e-9)
    }

    @Test
    fun `loss can never be driven negative by a flood of reordering`() {
        val tracker = Iperf3Protocol.SequenceTracker()
        tracker.accept(10L)
        repeat(5) { tracker.accept(1L) }

        assertTrue(tracker.lost >= 0L)
        assertTrue(tracker.lossPercent() >= 0.0)
    }

    // ---- Jitter --------------------------------------------------------------------------------

    @Test
    fun `a perfectly paced stream has no jitter, whatever the clock offset between the ends`() {
        val estimator = Iperf3Protocol.JitterEstimator()
        // Transit times all equal: the sender's clock may be hours out, but the offset is constant
        // and cancels in the difference, which is why RFC 1889 needs no clock synchronisation.
        repeat(50) { estimator.accept(3_600.0) }

        assertEquals(0.0, estimator.jitterSeconds, 1e-12)
    }

    @Test
    fun `jitter rises with variation and is smoothed rather than spiky`() {
        val estimator = Iperf3Protocol.JitterEstimator()
        estimator.accept(0.010)
        estimator.accept(0.050)

        // One 40ms step moves the estimate by a sixteenth of itself, not the whole way.
        assertEquals(0.040 / 16.0, estimator.jitterSeconds, 1e-12)

        repeat(200) { index ->
            estimator.accept(if (index % 2 == 0) 0.010 else 0.050)
        }
        // Sustained variation converges towards the actual swing.
        assertTrue(estimator.jitterSeconds > 0.03)
        assertTrue(estimator.jitterSeconds <= 0.04)
    }

    // ---- State names ---------------------------------------------------------------------------

    @Test
    fun `state bytes match the values in iperf_api_h`() {
        assertEquals(1, Iperf3Protocol.TEST_START)
        assertEquals(2, Iperf3Protocol.TEST_RUNNING)
        assertEquals(4, Iperf3Protocol.TEST_END)
        assertEquals(9, Iperf3Protocol.PARAM_EXCHANGE)
        assertEquals(10, Iperf3Protocol.CREATE_STREAMS)
        assertEquals(13, Iperf3Protocol.EXCHANGE_RESULTS)
        assertEquals(14, Iperf3Protocol.DISPLAY_RESULTS)
        assertEquals(16, Iperf3Protocol.IPERF_DONE)
        // Both failure states are negative, which is why the byte must be read as signed.
        assertEquals(-1, Iperf3Protocol.ACCESS_DENIED)
        assertEquals(-2, Iperf3Protocol.SERVER_ERROR)
        assertEquals(255.toByte().toInt(), Iperf3Protocol.ACCESS_DENIED)
        assertEquals("PARAM_EXCHANGE", Iperf3Protocol.stateName(9))
        assertEquals("state 77", Iperf3Protocol.stateName(77))
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value ushr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "expected ${expected.joinToString { it.toString() }} " +
                "but was ${actual.joinToString { it.toString() }}",
            expected.contentEquals(actual),
        )
    }

    private companion object {
        val REQUIRED_RESULT_KEYS = listOf(
            "cpu_util_total",
            "cpu_util_user",
            "cpu_util_system",
            "sender_has_retransmits",
            "streams",
            "id",
            "bytes",
            "retransmits",
            "jitter",
            "errors",
            "packets",
            "start_time",
            "end_time",
        )
    }
}
