package com.voidlink.android.protocol.rtp

/**
 * One parsed video datagram: its RTP header, its NV header, and the slice of the datagram that
 * forms its FEC shard (spec §7.3, §7.4).
 *
 * **Lifetime:** a [VideoPacket] borrows the receive buffer it was parsed from. The receive thread
 * recycles that buffer (architecture §3, rule 2), so a packet must not outlive the call it was
 * handed to. Anything that needs to keep bytes calls [copyShard] or [copyPayload].
 *
 * @property rtp the RTP header (spec §7.3).
 * @property nv the NV video packet header (spec §7.4).
 * @property shardOffset index of the first NV-header byte within [datagram].
 * @property shardLength NV header plus payload bytes actually present in this datagram. This is
 *   the FEC shard as we model it — see
 *   [UnverifiedRtpVideoConstants.FEC_SHARD_INCLUDES_NV_HEADER].
 */
class VideoPacket(
    val rtp: RtpHeader,
    val nv: NvVideoPacketHeader,
    private val datagram: ByteArray,
    val shardOffset: Int,
    val shardLength: Int,
) {

    /** Bytes of encoded picture data in this packet; zero for a header-only shard. */
    val payloadLength: Int
        get() = shardLength - RtpVideoConstants.NV_VIDEO_HEADER_SIZE

    /**
     * Sequence number of shard 0 of this packet's FEC block (spec §7.4).
     *
     * `rtpSequenceNumber - fecIndex`, as a 16-bit wrapping subtract. Together with the frame index
     * it is the key the block is filed under (spec §7.7 step 5).
     */
    val blockBaseSequenceNumber: Int
        get() = SequenceNumbers.advance(rtp.sequenceNumber, -nv.fecIndex)

    /** Copies the whole shard — NV header included — out of the borrowed receive buffer. */
    fun copyShard(): ByteArray =
        datagram.copyOfRange(shardOffset, shardOffset + shardLength)

    /** Copies only the encoded picture bytes out of the borrowed receive buffer. */
    fun copyPayload(): ByteArray {
        val start = shardOffset + RtpVideoConstants.NV_VIDEO_HEADER_SIZE
        return datagram.copyOfRange(start, start + payloadLength)
    }
}

/** Why a datagram could not be turned into a [VideoPacket]. */
enum class VideoPacketRejection {

    /** Fewer than 12 bytes, so not even an RTP header (spec §7.7 step 1). */
    DATAGRAM_TOO_SHORT,

    /** An RTP header, but not the 16 further bytes the NV header needs (spec §7.4). */
    NV_HEADER_TRUNCATED,

    /**
     * The FEC descriptor does not describe a possible block.
     *
     * The most likely cause is an encrypted payload we were not told to expect (spec §7.6): the
     * 32-byte encryption header displaces the NV header, so the bytes we decode as `fecInfo` are
     * ciphertext. Reporting that guess in the detail string is the difference between a five-minute
     * diagnosis and an afternoon of staring at corrupt frames.
     */
    IMPLAUSIBLE_FEC_GEOMETRY,

    /**
     * The session negotiated `SS_ENC_VIDEO` and we cannot decrypt it (spec §7.6, §6.5).
     *
     * v1 never asks for video encryption. If a host sends it anyway, spec §7.6 requires failing
     * with a clear message rather than producing garbage, which is exactly this.
     */
    ENCRYPTED_VIDEO_UNSUPPORTED,
}

/** Outcome of [VideoPacketParser.parse]. */
sealed interface VideoPacketParseResult {

    /** The datagram was a well-formed video packet. */
    data class Parsed(val packet: VideoPacket) : VideoPacketParseResult

    /** The datagram was not usable; [detail] is written to be read in a bug report. */
    data class Rejected(
        val reason: VideoPacketRejection,
        val detail: String,
    ) : VideoPacketParseResult
}

/**
 * Turns a received datagram into a [VideoPacket] (spec §7.3, §7.4, §7.7 steps 1–4).
 *
 * Stateless and allocation-free on the success path apart from the two small header objects, so it
 * is safe to call on the `video-rx` thread (architecture §3).
 *
 * @param videoEncryptionNegotiated set when RTSP agreed `SS_ENC_VIDEO`. v1 always passes `false`;
 *   passing `true` makes every packet fail fast with [VideoPacketRejection.ENCRYPTED_VIDEO_UNSUPPORTED]
 *   instead of producing garbage (spec §7.6).
 */
class VideoPacketParser(
    private val videoEncryptionNegotiated: Boolean = false,
) {

    /**
     * Parses one datagram.
     *
     * @param datagram the receive buffer.
     * @param length bytes actually received into it.
     */
    fun parse(datagram: ByteArray, length: Int = datagram.size): VideoPacketParseResult {
        if (videoEncryptionNegotiated) {
            return VideoPacketParseResult.Rejected(
                VideoPacketRejection.ENCRYPTED_VIDEO_UNSUPPORTED,
                "video payload encryption was negotiated but v1 cannot decrypt it; the " +
                    "${RtpVideoConstants.ENCRYPTION_HEADER_SIZE}-byte header of spec §7.6 is " +
                    "not implemented",
            )
        }

        val rtp = RtpHeader.parse(datagram, 0, length)
            ?: return VideoPacketParseResult.Rejected(
                VideoPacketRejection.DATAGRAM_TOO_SHORT,
                "datagram of $length bytes is shorter than an RTP header (spec §7.3)",
            )

        val nvOffset = rtp.headerSize
        val nvAvailable = length - nvOffset
        val nv = NvVideoPacketHeader.parse(datagram, nvOffset, nvAvailable)
            ?: return VideoPacketParseResult.Rejected(
                VideoPacketRejection.NV_HEADER_TRUNCATED,
                "only $nvAvailable bytes after the RTP header; the NV header needs " +
                    "${RtpVideoConstants.NV_VIDEO_HEADER_SIZE} (spec §7.4)",
            )

        if (!nv.hasPlausibleFecGeometry) {
            return VideoPacketParseResult.Rejected(
                VideoPacketRejection.IMPLAUSIBLE_FEC_GEOMETRY,
                "fecInfo=0x${java.lang.Integer.toHexString(nv.fecInfo)} decodes to " +
                    "dataShards=${nv.dataShards} parityShards=${nv.parityShards} " +
                    "fecIndex=${nv.fecIndex} multiFecBlocks=${nv.multiFecBlocks}, which is not a " +
                    "possible FEC block — an encrypted stream (spec §7.6) looks exactly like this",
            )
        }

        return VideoPacketParseResult.Parsed(
            VideoPacket(
                rtp = rtp,
                nv = nv,
                datagram = datagram,
                shardOffset = nvOffset,
                shardLength = nvAvailable,
            ),
        )
    }
}
