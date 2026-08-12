package com.voidlink.android.protocol.audio

import com.voidlink.android.protocol.rtp.RtpBytes
import com.voidlink.android.protocol.rtp.RtpHeader

/**
 * The 12-byte parity header that follows the RTP header of a `packetType == 127` packet (spec §8.4).
 *
 * ```
 * offset 0  : uint8  fecShardIndex        // 0..1
 * offset 1  : uint8  payloadType          // the payload type of the data shards (97)
 * offset 2  : uint16 baseSequenceNumber   BIG-ENDIAN
 * offset 4  : uint32 baseTimestamp        BIG-ENDIAN
 * offset 8  : uint32 ssrc                 BIG-ENDIAN
 * ```
 *
 * @property shardIndex which of the two parity shards this is.
 * @property payloadType the payload type of the *data* shards this parity covers — 97.
 * @property baseSequenceNumber the first data sequence number of the block. Always a multiple of
 *   [RtpAudioConstants.FEC_DATA_SHARDS] on a host new enough to send usable audio FEC.
 * @property baseTimestamp the RTP timestamp of that first data packet.
 * @property ssrc the stream's ssrc, echoed.
 */
class AudioFecHeader(
    val shardIndex: Int,
    val payloadType: Int,
    val baseSequenceNumber: Int,
    val baseTimestamp: Int,
    val ssrc: Int,
) {
    /** True when [baseSequenceNumber] starts on a block boundary, as the queue logic requires. */
    val isBlockAligned: Boolean
        get() = (baseSequenceNumber % RtpAudioConstants.FEC_DATA_SHARDS) == 0

    /** True when [shardIndex] addresses a parity shard that exists. */
    val isShardIndexValid: Boolean
        get() = shardIndex in 0 until RtpAudioConstants.FEC_PARITY_SHARDS

    override fun toString(): String =
        "AudioFecHeader(shard=$shardIndex, type=$payloadType, base=$baseSequenceNumber, " +
            "ts=$baseTimestamp)"

    companion object {

        /**
         * Parses a parity header out of [data].
         *
         * @return the header, or `null` when fewer than [RtpAudioConstants.FEC_HEADER_SIZE] bytes
         *   are available. `null` rather than an exception, for the same reason
         *   [RtpHeader.parse] returns one: a short datagram is an ordinary network event.
         */
        fun parse(data: ByteArray, offset: Int, length: Int): AudioFecHeader? {
            if (offset < 0 || length < RtpAudioConstants.FEC_HEADER_SIZE) return null
            if (offset > data.size - RtpAudioConstants.FEC_HEADER_SIZE) return null
            return AudioFecHeader(
                shardIndex = RtpBytes.u8(data, offset + RtpAudioConstants.FEC_OFFSET_SHARD_INDEX),
                payloadType = RtpBytes.u8(data, offset + RtpAudioConstants.FEC_OFFSET_PAYLOAD_TYPE),
                baseSequenceNumber = RtpBytes.beU16(
                    data,
                    offset + RtpAudioConstants.FEC_OFFSET_BASE_SEQUENCE,
                ),
                baseTimestamp = RtpBytes.beI32(
                    data,
                    offset + RtpAudioConstants.FEC_OFFSET_BASE_TIMESTAMP,
                ),
                ssrc = RtpBytes.beI32(data, offset + RtpAudioConstants.FEC_OFFSET_SSRC),
            )
        }
    }
}

/** Why a datagram was not usable. Counted rather than logged, so a flood costs nothing. */
enum class AudioPacketRejection {

    /** Shorter than an RTP header, or shorter than the parity header it claims to carry. */
    TOO_SHORT,

    /** A payload type §8.1 does not enumerate. */
    UNKNOWN_PAYLOAD_TYPE,

    /** A data packet whose Opus payload is empty. */
    EMPTY_PAYLOAD,

    /** A parity packet whose shard index or block base is outside what the geometry allows. */
    MALFORMED_PARITY,
}

/**
 * One parsed audio datagram (spec §8.1).
 *
 * Audio reuses video's 12-byte RTP header verbatim, so [RtpHeader] does that half; this type adds
 * the payload-type split §8.1 tabulates and the parity header §8.4 defines. It holds *offsets into
 * the caller's receive buffer* and never copies — copying is [AudioDepacketizer]'s job, and only
 * for the packets it actually keeps.
 */
sealed interface AudioRtpPacket {

    /** The RTP header, common to both kinds. */
    val header: RtpHeader

    /** First byte of this packet's own payload within the datagram. */
    val payloadOffset: Int

    /** Length of that payload. */
    val payloadLength: Int

    /** A `packetType == 97` Opus data packet. [payloadOffset] points at the Opus TOC byte. */
    class Data(
        override val header: RtpHeader,
        override val payloadOffset: Int,
        override val payloadLength: Int,
    ) : AudioRtpPacket

    /** A `packetType == 127` parity shard. [payloadOffset] points past the parity header. */
    class Parity(
        override val header: RtpHeader,
        val fec: AudioFecHeader,
        override val payloadOffset: Int,
        override val payloadLength: Int,
    ) : AudioRtpPacket

    companion object {

        /**
         * Parses one received datagram.
         *
         * @param data the receive buffer; not retained.
         * @param length bytes actually received.
         * @return the packet, or `null` with [rejectionOf] explaining why not.
         */
        fun parse(data: ByteArray, length: Int): AudioRtpPacket? {
            val header = RtpHeader.parse(data, 0, length) ?: return null
            val bodyOffset = header.headerSize
            val bodyLength = length - bodyOffset
            if (bodyLength <= 0) return null

            return when (header.payloadType) {
                RtpAudioConstants.PAYLOAD_TYPE_OPUS ->
                    Data(header, bodyOffset, bodyLength)

                RtpAudioConstants.PAYLOAD_TYPE_FEC -> {
                    val fec = AudioFecHeader.parse(data, bodyOffset, bodyLength) ?: return null
                    if (!fec.isShardIndexValid || !fec.isBlockAligned) return null
                    val payloadOffset = bodyOffset + RtpAudioConstants.FEC_HEADER_SIZE
                    val payloadLength = length - payloadOffset
                    if (payloadLength <= 0) return null
                    Parity(header, fec, payloadOffset, payloadLength)
                }

                else -> null
            }
        }

        /**
         * Why [parse] would return `null` for this datagram.
         *
         * Separate from [parse] so the hot path allocates nothing and returns one reference, while
         * the statistics path — which runs once per rejected packet, not once per packet — can still
         * say something specific. Returns `null` when the datagram is in fact fine.
         */
        fun rejectionOf(data: ByteArray, length: Int): AudioPacketRejection? {
            val header = RtpHeader.parse(data, 0, length)
                ?: return AudioPacketRejection.TOO_SHORT
            val bodyOffset = header.headerSize
            val bodyLength = length - bodyOffset
            return when (header.payloadType) {
                RtpAudioConstants.PAYLOAD_TYPE_OPUS ->
                    if (bodyLength <= 0) AudioPacketRejection.EMPTY_PAYLOAD else null

                RtpAudioConstants.PAYLOAD_TYPE_FEC -> {
                    val fec = AudioFecHeader.parse(data, bodyOffset, bodyLength)
                        ?: return AudioPacketRejection.TOO_SHORT
                    if (!fec.isShardIndexValid || !fec.isBlockAligned) {
                        AudioPacketRejection.MALFORMED_PARITY
                    } else if (length - bodyOffset - RtpAudioConstants.FEC_HEADER_SIZE <= 0) {
                        AudioPacketRejection.EMPTY_PAYLOAD
                    } else {
                        null
                    }
                }

                else -> AudioPacketRejection.UNKNOWN_PAYLOAD_TYPE
            }
        }
    }
}
