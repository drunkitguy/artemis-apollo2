package com.voidlink.android.protocol.rtp

/**
 * The 12- or 16-byte RTP header every video datagram begins with (spec §7.3).
 *
 * ```
 * offset 0  : uint8   flags        // V/P/X/CC; bit 0x10 = extension present
 * offset 1  : uint8   packetType   // payload type
 * offset 2  : uint16  sequenceNumber   BIG-ENDIAN
 * offset 4  : uint32  timestamp        BIG-ENDIAN
 * offset 8  : uint32  ssrc             BIG-ENDIAN
 * ```
 *
 * When the extension bit is set a four-byte extension follows the fixed header and is skipped;
 * [headerSize] is then 16 rather than 12.
 *
 * @property flags the raw first byte, kept whole so an unexpected bit pattern is diagnosable.
 * @property payloadType the RTP payload type. The video path deliberately does **not** filter on
 *   it: spec §7.3 does not enumerate the video payload types the way §8.1 does for audio, so
 *   rejecting on it would be guessing. Data and parity shards are told apart by `fecIndex` instead
 *   (see [NvVideoPacketHeader.isParityShard]).
 * @property sequenceNumber 16-bit, wrapping. Compare only through [SequenceNumbers].
 * @property timestamp the raw 32-bit RTP timestamp; see [timestampUnsigned].
 * @property headerSize 12, or 16 when [hasExtension].
 */
data class RtpHeader(
    val flags: Int,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Int,
    val ssrc: Int,
    val headerSize: Int,
) {

    /** True when a four-byte header extension follows the fixed header (spec §7.3). */
    val hasExtension: Boolean
        get() = (flags and RtpVideoConstants.RTP_FLAG_EXTENSION) != 0

    /**
     * The RTP timestamp widened to an unsigned value.
     *
     * Spec §7.8 uses it only for relative ordering — presentation timestamps handed to
     * `MediaCodec` come from the local clock — so it never needs wrap-aware comparison.
     */
    val timestampUnsigned: Long
        get() = timestamp.toLong() and 0xFFFFFFFFL

    companion object {

        /**
         * Parses an RTP header out of [data].
         *
         * @param data the received datagram; not retained.
         * @param offset first byte of the header.
         * @param length bytes available from [offset].
         * @return the parsed header, or `null` when fewer bytes are present than the header needs.
         *   `null` rather than an exception because a short datagram is an ordinary network event,
         *   not a bug (spec §7.7 step 1: "drop if `length < 12`").
         */
        fun parse(
            data: ByteArray,
            offset: Int = 0,
            length: Int = data.size - offset,
        ): RtpHeader? {
            if (offset < 0 || length < 0 || offset > data.size - length) return null
            if (length < RtpVideoConstants.FIXED_RTP_HEADER_SIZE) return null

            val flags = RtpBytes.u8(data, offset + RtpVideoConstants.RTP_OFFSET_FLAGS)
            val headerSize = if ((flags and RtpVideoConstants.RTP_FLAG_EXTENSION) != 0) {
                RtpVideoConstants.MAX_RTP_HEADER_SIZE
            } else {
                RtpVideoConstants.FIXED_RTP_HEADER_SIZE
            }
            if (length < headerSize) return null

            return RtpHeader(
                flags = flags,
                payloadType = RtpBytes.u8(data, offset + RtpVideoConstants.RTP_OFFSET_PACKET_TYPE),
                sequenceNumber = RtpBytes.beU16(
                    data,
                    offset + RtpVideoConstants.RTP_OFFSET_SEQUENCE_NUMBER,
                ),
                timestamp = RtpBytes.beI32(data, offset + RtpVideoConstants.RTP_OFFSET_TIMESTAMP),
                ssrc = RtpBytes.beI32(data, offset + RtpVideoConstants.RTP_OFFSET_SSRC),
                headerSize = headerSize,
            )
        }
    }
}
