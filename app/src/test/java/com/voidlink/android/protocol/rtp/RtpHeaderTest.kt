package com.voidlink.android.protocol.rtp

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 12/16-byte RTP header of `docs/01-PROTOCOL.md` §7.3, against committed hex fixtures.
 *
 * Hex rather than a builder for the header fields themselves: spec §0.1 calls endianness "the
 * number-one bug source", and a fixture written as bytes is the only kind of test that catches a
 * byte-swapped read, because a builder that swaps the same way agrees with the parser perfectly.
 */
class RtpHeaderTest {

    /** flags=0x80, packetType=0x00, seq=0x1234, timestamp=0xDEADBEEF, ssrc=0x11223344. */
    private val plainHeader = "80001234deadbeef11223344"

    /** As above but flags=0x90 (extension present), seq=0xFFFF, with a four-byte extension. */
    private val extendedHeader = "9000ffff0000000111223344deadbeef"

    private fun decode(hex: String): ByteArray = requireNotNull(Hex.decodeOrNull(hex))

    @Test
    fun `every field of a plain header is read from the right offset in the right endianness`() {
        val header = requireNotNull(RtpHeader.parse(decode(plainHeader)))

        assertEquals(0x80, header.flags)
        assertEquals(0x00, header.payloadType)
        assertEquals(0x1234, header.sequenceNumber)
        assertEquals(0xDEADBEEF.toInt(), header.timestamp)
        assertEquals(0x11223344, header.ssrc)
        assertEquals(12, header.headerSize)
        assertFalse(header.hasExtension)
    }

    @Test
    fun `the timestamp is available unsigned`() {
        val header = requireNotNull(RtpHeader.parse(decode(plainHeader)))
        // 0xDEADBEEF is negative as an Int; reading it as a Long must not sign-extend.
        assertTrue(header.timestamp < 0)
        assertEquals(0xDEADBEEFL, header.timestampUnsigned)
    }

    @Test
    fun `the extension bit adds four bytes to the header size`() {
        val header = requireNotNull(RtpHeader.parse(decode(extendedHeader)))

        assertTrue(header.hasExtension)
        assertEquals(16, header.headerSize)
        // The extension itself is skipped, not interpreted (spec §7.3).
        assertEquals(0xFFFF, header.sequenceNumber)
        assertEquals(1, header.timestamp)
    }

    @Test
    fun `the sequence number is unsigned across its whole range`() {
        // 0xFFFF read as a signed 16-bit value would be -1, which would order every comparison
        // backwards exactly once per wrap.
        val header = requireNotNull(RtpHeader.parse(decode(extendedHeader)))
        assertEquals(65535, header.sequenceNumber)
        assertTrue(header.sequenceNumber > 0)
    }

    @Test
    fun `a datagram shorter than twelve bytes is rejected`() {
        // Spec §7.7 step 1: drop if length < 12.
        for (size in 0 until 12) {
            assertNull("size $size", RtpHeader.parse(ByteArray(size)))
        }
        assertNotNull(RtpHeader.parse(ByteArray(12)))
    }

    @Test
    fun `an extension flag with no extension bytes present is rejected`() {
        val truncated = decode(plainHeader).copyOf()
        truncated[0] = 0x90.toByte()
        assertNull(RtpHeader.parse(truncated))
        assertNotNull(RtpHeader.parse(truncated.copyOf(16)))
    }

    @Test
    fun `a length shorter than the array is honoured`() {
        val bytes = decode(extendedHeader)
        assertNull(RtpHeader.parse(bytes, 0, 15))
        assertNotNull(RtpHeader.parse(bytes, 0, 16))
    }

    @Test
    fun `out-of-range offsets are rejected rather than thrown`() {
        val bytes = decode(plainHeader)
        assertNull(RtpHeader.parse(bytes, -1, 12))
        assertNull(RtpHeader.parse(bytes, 4, 12))
        assertNull(RtpHeader.parse(bytes, 0, -1))
    }
}
