package com.voidlink.android.protocol.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builders for audio datagrams shaped exactly as `docs/01-PROTOCOL.md` §8.1 and §8.4 describe.
 *
 * Builders rather than hex fixtures for the *bodies*, because an audio payload is arbitrary Opus
 * bytes and a hex blob of them would test nothing. The header fields that do have a defined
 * byte-level layout are covered by hex fixtures in [AudioRtpPacketTest], which is where a
 * byte-swapped read has to be caught — a builder that swaps the same way as the parser agrees with
 * it perfectly.
 */
object AudioPacketFixtures {

    /**
     * A plausible Opus TOC byte: CELT full-band, 5 ms frames, stereo, one frame per packet.
     *
     * config 29 (`0b11101`) shifted into bits 7..3, the stereo bit set, frame-count code 0.
     */
    const val TOC_CELT_FB_5MS_STEREO: Int = 0xEC

    /**
     * Builds a `packetType == 97` Opus data packet (spec §8.1).
     *
     * @param sequenceNumber the RTP sequence number.
     * @param timestamp the RTP timestamp, in the millisecond units of spec §8.4.
     * @param payload the Opus packet. Defaults to a TOC byte plus a recognisable filler.
     */
    fun dataPacket(
        sequenceNumber: Int,
        timestamp: Int = sequenceNumber * 5,
        payload: ByteArray = opusPayload(sequenceNumber),
        ssrc: Int = 0x11223344,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(RTP_HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(RTP_FLAGS.toByte())
        buffer.put(RtpAudioConstants.PAYLOAD_TYPE_OPUS.toByte())
        buffer.putShort(sequenceNumber.toShort())
        buffer.putInt(timestamp)
        buffer.putInt(ssrc)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Builds a `packetType == 127` parity packet with the 12-byte header of spec §8.4.
     *
     * Note the sequence number: a host numbers its parity packets from the *end* of the block it
     * covers, in the same space as the data packets that follow, which is why nothing downstream
     * may feed a parity packet into the data sequence logic.
     */
    fun parityPacket(
        baseSequenceNumber: Int,
        shardIndex: Int,
        baseTimestamp: Int = baseSequenceNumber * 5,
        sequenceNumber: Int = baseSequenceNumber + RtpAudioConstants.FEC_DATA_SHARDS + shardIndex,
        payload: ByteArray = ByteArray(PARITY_PAYLOAD_BYTES) { (shardIndex * 16 + it).toByte() },
        ssrc: Int = 0x11223344,
        payloadType: Int = RtpAudioConstants.PAYLOAD_TYPE_OPUS,
    ): ByteArray {
        val size = RTP_HEADER_SIZE + RtpAudioConstants.FEC_HEADER_SIZE + payload.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(RTP_FLAGS.toByte())
        buffer.put(RtpAudioConstants.PAYLOAD_TYPE_FEC.toByte())
        buffer.putShort(sequenceNumber.toShort())
        buffer.putInt(baseTimestamp)
        buffer.putInt(ssrc)
        // The parity header itself.
        buffer.put(shardIndex.toByte())
        buffer.put(payloadType.toByte())
        buffer.putShort(baseSequenceNumber.toShort())
        buffer.putInt(baseTimestamp)
        buffer.putInt(ssrc)
        buffer.put(payload)
        return buffer.array()
    }

    /** A distinguishable Opus payload whose second byte identifies which packet it came from. */
    fun opusPayload(sequenceNumber: Int, size: Int = 40): ByteArray =
        ByteArray(size) { index ->
            when (index) {
                0 -> TOC_CELT_FB_5MS_STEREO.toByte()
                1 -> sequenceNumber.toByte()
                else -> (index * 3).toByte()
            }
        }

    /** The sequence number a payload built by [opusPayload] belongs to. */
    fun sequenceOf(payload: ByteArray): Int = payload[1].toInt() and 0xFF

    private const val RTP_HEADER_SIZE = 12

    /** Version 2, no padding, no extension, no CSRCs. */
    private const val RTP_FLAGS = 0x80

    private const val PARITY_PAYLOAD_BYTES = 40
}
