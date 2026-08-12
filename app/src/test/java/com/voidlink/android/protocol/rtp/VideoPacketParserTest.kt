package com.voidlink.android.protocol.rtp

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end parsing of a whole datagram — RTP header, NV header and payload — against committed
 * hex (`docs/01-PROTOCOL.md` §7.3, §7.4, §7.6, §7.7).
 *
 * The two fixtures are the boundary cases that matter: a plain 12-byte RTP header, and one with the
 * four-byte extension that shifts every subsequent field.
 */
class VideoPacketParserTest {

    private val parser = VideoPacketParser()

    /** 12-byte RTP header, NV header, then `00 00 00 01 65 aa bb cc`. */
    private val plainDatagram =
        "80001234deadbeef11223344" +
            "000100002a000000050100014011c000" +
            "0000000165aabbcc"

    /** 16-byte RTP header (extension present), NV header, then `00 00 01 67`. */
    private val extendedDatagram =
        "9000ffff0000000111223344deadbeef" +
            "0100000007000000030000010000400000000167"

    private fun decode(hex: String): ByteArray = requireNotNull(Hex.decodeOrNull(hex))

    private fun parse(hex: String): VideoPacket {
        val result = parser.parse(decode(hex))
        assertTrue("expected a parse, got $result", result is VideoPacketParseResult.Parsed)
        return (result as VideoPacketParseResult.Parsed).packet
    }

    @Test
    fun `a plain datagram yields both headers and the payload`() {
        val packet = parse(plainDatagram)

        assertEquals(0x1234, packet.rtp.sequenceNumber)
        assertEquals(12, packet.rtp.headerSize)
        assertEquals(42L, packet.nv.frameIndex)
        assertEquals(3, packet.nv.dataShards)
        assertEquals(1, packet.nv.fecIndex)
        assertEquals(8, packet.payloadLength)
        assertArrayEquals(decode("0000000165aabbcc"), packet.copyPayload())
    }

    @Test
    fun `the NV header is found past the RTP extension, not at a fixed offset`() {
        val packet = parse(extendedDatagram)

        assertEquals(16, packet.rtp.headerSize)
        assertEquals(0xFFFF, packet.rtp.sequenceNumber)
        // If the extension were not skipped, frameIndex would read 0xEFBEADDE rather than 7.
        assertEquals(7L, packet.nv.frameIndex)
        assertEquals(1, packet.nv.dataShards)
        assertArrayEquals(decode("00000167"), packet.copyPayload())
    }

    @Test
    fun `the shard spans the NV header and the payload together`() {
        // Spec §7.7 leaves the shard boundary unstated; we assume it starts at the NV header
        // (UnverifiedRtpVideoConstants.FEC_SHARD_INCLUDES_NV_HEADER) so a recovered shard carries
        // its own flags byte. This pins that decision.
        assertTrue(UnverifiedRtpVideoConstants.FEC_SHARD_INCLUDES_NV_HEADER)

        val packet = parse(plainDatagram)
        assertEquals(12, packet.shardOffset)
        assertEquals(24, packet.shardLength)
        assertArrayEquals(
            decode("000100002a000000050100014011c0000000000165aabbcc"),
            packet.copyShard(),
        )
    }

    @Test
    fun `blockBaseSequenceNumber is a wrapping subtract of fecIndex`() {
        // Spec §7.4. The interesting case is the one that wraps.
        val packet = parse(plainDatagram)
        assertEquals(0x1233, packet.blockBaseSequenceNumber)

        val wrapping = VideoPacketFixtures.packet(
            sequenceNumber = 2,
            fecIndex = 5,
            dataShards = 8,
        )
        val parsed = (parser.parse(wrapping) as VideoPacketParseResult.Parsed).packet
        assertEquals(65533, parsed.blockBaseSequenceNumber)
    }

    @Test
    fun `a single-packet frame parses as a one-shard block`() {
        val datagram = VideoPacketFixtures.packet(
            sequenceNumber = 7,
            frameIndex = 3L,
            fecIndex = 0,
            dataShards = 1,
            payload = VideoPacketFixtures.h264IdrPayload(0xAA),
            flags = RtpVideoConstants.FLAG_CONTAINS_PIC_DATA or
                RtpVideoConstants.FLAG_SOF or
                RtpVideoConstants.FLAG_EOF,
        )
        val packet = (parser.parse(datagram) as VideoPacketParseResult.Parsed).packet

        assertEquals(1, packet.nv.dataShards)
        assertEquals(0, packet.nv.parityShards)
        assertEquals(7, packet.blockBaseSequenceNumber)
        assertTrue(packet.nv.isStartOfFrame)
        assertTrue(packet.nv.isEndOfFrame)
    }

    @Test
    fun `a datagram with no payload at all still parses`() {
        // Legal: a shard may carry only the NV header, and spec §7.8 says to exclude it from the
        // frame rather than treat it as an error.
        val datagram = VideoPacketFixtures.packet(sequenceNumber = 1, flags = 0)
        val packet = (parser.parse(datagram) as VideoPacketParseResult.Parsed).packet

        assertEquals(0, packet.payloadLength)
        assertEquals(0, packet.copyPayload().size)
        assertFalse(packet.nv.containsPictureData)
    }

    @Test
    fun `a datagram too short for an RTP header is rejected with that reason`() {
        val result = parser.parse(ByteArray(11))
        assertEquals(
            VideoPacketRejection.DATAGRAM_TOO_SHORT,
            (result as VideoPacketParseResult.Rejected).reason,
        )
    }

    @Test
    fun `a datagram too short for the NV header is rejected with that reason`() {
        val result = parser.parse(decode(plainDatagram), 12 + 15)
        assertEquals(
            VideoPacketRejection.NV_HEADER_TRUNCATED,
            (result as VideoPacketParseResult.Rejected).reason,
        )
    }

    @Test
    fun `an impossible FEC geometry is rejected and names encryption as the likely cause`() {
        val datagram = VideoPacketFixtures.packet(sequenceNumber = 1, dataShards = 0)
        val result = parser.parse(datagram) as VideoPacketParseResult.Rejected

        assertEquals(VideoPacketRejection.IMPLAUSIBLE_FEC_GEOMETRY, result.reason)
        assertTrue(result.detail.contains("§7.6"))
    }

    @Test
    fun `a negotiated encrypted stream fails loudly rather than producing garbage`() {
        // Spec §7.6: v1 does not implement video encryption and must say so plainly.
        val encrypting = VideoPacketParser(videoEncryptionNegotiated = true)
        val result = encrypting.parse(decode(plainDatagram)) as VideoPacketParseResult.Rejected

        assertEquals(VideoPacketRejection.ENCRYPTED_VIDEO_UNSUPPORTED, result.reason)
        assertTrue(result.detail.contains("32"))
    }

    @Test
    fun `a maximum-size datagram parses without truncation`() {
        // A jumbo-adjacent frame shard: nothing in the parser may assume an MTU.
        val payload = ByteArray(9000) { (it and 0xFF).toByte() }
        val datagram = VideoPacketFixtures.packet(
            sequenceNumber = 100,
            dataShards = 4,
            fecIndex = 2,
            payload = payload,
        )
        val packet = (parser.parse(datagram) as VideoPacketParseResult.Parsed).packet

        assertEquals(9000, packet.payloadLength)
        assertArrayEquals(payload, packet.copyPayload())
    }
}
