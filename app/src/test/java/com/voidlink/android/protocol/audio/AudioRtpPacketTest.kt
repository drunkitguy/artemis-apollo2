package com.voidlink.android.protocol.audio

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio packet split of `docs/01-PROTOCOL.md` §8.1 and the parity header of §8.4, against
 * committed hex fixtures.
 *
 * Hex rather than a builder for every field with a defined byte position: §0.1 calls endianness
 * "the number-one bug source", and a fixture written as bytes is the only kind of test that catches
 * a byte-swapped read.
 */
class AudioRtpPacketTest {

    /** flags=0x80, packetType=97 (0x61), seq=0x1234, ts=0x000003E8, ssrc=0x11223344, 4B payload. */
    private val dataPacket = "80611234000003e811223344e401ff7f"

    /**
     * A parity packet: flags=0x80, packetType=127 (0x7F), seq=0x1238, ts, ssrc, then the 12-byte
     * parity header — shard=1, payloadType=97, base=0x1234, baseTs=0x000003E8, ssrc — then payload.
     */
    private val parityPacket =
        "807f1238000003e811223344" + "0161" + "1234" + "000003e8" + "11223344" + "aabbccdd"

    private fun decode(hex: String): ByteArray = requireNotNull(Hex.decodeOrNull(hex))

    @Test
    fun `an Opus data packet is split at the end of the RTP header`() {
        val bytes = decode(dataPacket)
        val packet = AudioRtpPacket.parse(bytes, bytes.size)

        assertTrue(packet is AudioRtpPacket.Data)
        packet as AudioRtpPacket.Data
        assertEquals(RtpAudioConstants.PAYLOAD_TYPE_OPUS, packet.header.payloadType)
        assertEquals(0x1234, packet.header.sequenceNumber)
        assertEquals(1000, packet.header.timestamp)
        assertEquals(12, packet.payloadOffset)
        assertEquals(4, packet.payloadLength)
        // The payload begins at the Opus TOC byte, which is what spec §8.5 says must stay constant.
        assertEquals(0xE4, bytes[packet.payloadOffset].toInt() and 0xFF)
    }

    @Test
    fun `every parity header field is read from the right offset in the right endianness`() {
        val bytes = decode(parityPacket)
        val packet = AudioRtpPacket.parse(bytes, bytes.size)

        assertTrue(packet is AudioRtpPacket.Parity)
        packet as AudioRtpPacket.Parity
        assertEquals(1, packet.fec.shardIndex)
        assertEquals(RtpAudioConstants.PAYLOAD_TYPE_OPUS, packet.fec.payloadType)
        assertEquals(0x1234, packet.fec.baseSequenceNumber)
        assertEquals(1000, packet.fec.baseTimestamp)
        assertEquals(0x11223344, packet.fec.ssrc)
        // Payload starts past the RTP header *and* the 12-byte parity header.
        assertEquals(12 + 12, packet.payloadOffset)
        assertEquals(4, packet.payloadLength)
    }

    @Test
    fun `a parity packet carries its own RTP sequence number, not the block base`() {
        // This is the trap: a host numbers parity packets in the same sequence space as the data
        // packets that follow the block. Confusing the two desynchronises the queue permanently.
        val bytes = decode(parityPacket)
        val packet = AudioRtpPacket.parse(bytes, bytes.size) as AudioRtpPacket.Parity

        assertEquals(0x1238, packet.header.sequenceNumber)
        assertEquals(0x1234, packet.fec.baseSequenceNumber)
    }

    @Test
    fun `a payload type spec section 8_1 does not enumerate is rejected`() {
        // packetType 0x62 = 98, which is neither 97 nor 127.
        val bytes = decode("80621234000003e811223344e401ff7f")

        assertNull(AudioRtpPacket.parse(bytes, bytes.size))
        assertEquals(
            AudioPacketRejection.UNKNOWN_PAYLOAD_TYPE,
            AudioRtpPacket.rejectionOf(bytes, bytes.size),
        )
    }

    @Test
    fun `a datagram shorter than the RTP header is rejected rather than throwing`() {
        val bytes = decode("806112340000")

        assertNull(AudioRtpPacket.parse(bytes, bytes.size))
        assertEquals(AudioPacketRejection.TOO_SHORT, AudioRtpPacket.rejectionOf(bytes, bytes.size))
    }

    @Test
    fun `a data packet with no payload is rejected`() {
        val bytes = decode("80611234000003e811223344")

        assertNull(AudioRtpPacket.parse(bytes, bytes.size))
        assertEquals(
            AudioPacketRejection.EMPTY_PAYLOAD,
            AudioRtpPacket.rejectionOf(bytes, bytes.size),
        )
    }

    @Test
    fun `a parity packet with an out of range shard index is rejected`() {
        // shardIndex = 2, but RTPA_FEC_SHARDS is 2, so only 0 and 1 exist. Accepting it would be an
        // out-of-bounds write into the parity array during recovery.
        val bytes = decode(
            "807f1238000003e811223344" + "0261" + "1234" + "000003e8" + "11223344" + "aabbccdd",
        )

        assertNull(AudioRtpPacket.parse(bytes, bytes.size))
        assertEquals(
            AudioPacketRejection.MALFORMED_PARITY,
            AudioRtpPacket.rejectionOf(bytes, bytes.size),
        )
    }

    @Test
    fun `a parity packet whose block base is not on a four-packet boundary is rejected`() {
        // base = 0x1235, which is not a multiple of RTPA_DATA_SHARDS. Older GFE does this and the
        // queue logic cannot represent it; spec §8.4 fixes the geometry at multiples of four.
        val bytes = decode(
            "807f1239000003e811223344" + "0161" + "1235" + "000003e8" + "11223344" + "aabbccdd",
        )

        assertNull(AudioRtpPacket.parse(bytes, bytes.size))
        assertEquals(
            AudioPacketRejection.MALFORMED_PARITY,
            AudioRtpPacket.rejectionOf(bytes, bytes.size),
        )
    }

    @Test
    fun `the RTP extension bit moves the payload four bytes further in`() {
        // flags=0x90 sets the extension bit, so the header is 16 bytes, not 12 (spec §7.3).
        val bytes = decode("90611234000003e81122334400000001e401ff7f")
        val packet = AudioRtpPacket.parse(bytes, bytes.size) as AudioRtpPacket.Data

        assertEquals(16, packet.payloadOffset)
        assertEquals(4, packet.payloadLength)
        assertEquals(0xE4, bytes[packet.payloadOffset].toInt() and 0xFF)
    }

    @Test
    fun `the builders in the fixtures agree with the parser`() {
        val data = AudioPacketFixtures.dataPacket(sequenceNumber = 100, timestamp = 500)
        val parsed = AudioRtpPacket.parse(data, data.size) as AudioRtpPacket.Data

        assertEquals(100, parsed.header.sequenceNumber)
        assertEquals(500, parsed.header.timestamp)
        assertEquals(40, parsed.payloadLength)

        val parity = AudioPacketFixtures.parityPacket(baseSequenceNumber = 100, shardIndex = 0)
        val parsedParity = AudioRtpPacket.parse(parity, parity.size) as AudioRtpPacket.Parity

        assertEquals(100, parsedParity.fec.baseSequenceNumber)
        assertEquals(0, parsedParity.fec.shardIndex)
        assertEquals(104, parsedParity.header.sequenceNumber)
    }
}
