package com.voidlink.android.protocol.rtp

/**
 * The 16-byte vendor `NV_VIDEO_PACKET` header that sits immediately after the RTP header
 * (spec §7.4).
 *
 * ```
 * offset 0  : uint32 streamPacketIndex   LITTLE-ENDIAN
 * offset 4  : uint32 frameIndex          LITTLE-ENDIAN
 * offset 8  : uint8  flags
 * offset 9  : uint8  extraFlags
 * offset 10 : uint8  multiFecFlags
 * offset 11 : uint8  multiFecBlocks
 * offset 12 : uint32 fecInfo             LITTLE-ENDIAN
 * ```
 *
 * Everything about a packet's place in its frame and its FEC block comes from here, so the derived
 * accessors below are the load-bearing part of the whole receive path.
 *
 * @property streamPacketIndex the raw global packet counter; only its top 24 bits are meaningful
 *   (spec §7.4), which [streamPacketIndexValue] extracts.
 * @property frameIndex widened to `Long` because the wire field is an unsigned 32-bit counter and
 *   the assembler orders frames by it.
 * @property fecInfo the packed FEC descriptor; decoded by [fecIndex], [fecPercentage] and
 *   [dataShards].
 */
data class NvVideoPacketHeader(
    val streamPacketIndex: Int,
    val frameIndex: Long,
    val flags: Int,
    val extraFlags: Int,
    val multiFecFlags: Int,
    val multiFecBlocks: Int,
    val fecInfo: Int,
) {

    /** The meaningful part of [streamPacketIndex] — its top 24 bits (spec §7.4). */
    val streamPacketIndexValue: Int
        get() = (streamPacketIndex ushr 8) and 0xFFFFFF

    /** This packet carries encoded picture bytes (`FLAG_CONTAINS_PIC_DATA`). */
    val containsPictureData: Boolean
        get() = (flags and RtpVideoConstants.FLAG_CONTAINS_PIC_DATA) != 0

    /** Last packet of the frame (`FLAG_EOF`). */
    val isEndOfFrame: Boolean
        get() = (flags and RtpVideoConstants.FLAG_EOF) != 0

    /** First packet of the frame (`FLAG_SOF`). */
    val isStartOfFrame: Boolean
        get() = (flags and RtpVideoConstants.FLAG_SOF) != 0

    /** The frame is a long-term reference frame (`NV_VIDEO_PACKET_EXTRA_FLAG_LTR_FRAME`). */
    val isLongTermReferenceFrame: Boolean
        get() = (extraFlags and RtpVideoConstants.EXTRA_FLAG_LTR_FRAME) != 0

    /** Index of this shard within its FEC block; data shards first, then parity (spec §7.4). */
    val fecIndex: Int
        get() = (fecInfo ushr RtpVideoConstants.FEC_INDEX_SHIFT) and
            RtpVideoConstants.FEC_INDEX_MASK

    /** Parity overhead as a percentage of the data shards (spec §7.4). */
    val fecPercentage: Int
        get() = (fecInfo ushr RtpVideoConstants.FEC_PERCENTAGE_SHIFT) and
            RtpVideoConstants.FEC_PERCENTAGE_MASK

    /** Number of data shards in this FEC block (spec §7.4). */
    val dataShards: Int
        get() = (fecInfo ushr RtpVideoConstants.FEC_DATA_SHARDS_SHIFT) and
            RtpVideoConstants.FEC_DATA_SHARDS_MASK

    /**
     * Parity shard count, `ceil(dataShards * fecPercentage / 100)` (spec §7.4).
     *
     * Written as the spec writes it. The widest possible operands (1023 shards at 255 %) stay well
     * inside `Int`.
     */
    val parityShards: Int
        get() = (dataShards * fecPercentage + 99) / 100

    /** Data plus parity shards in this block. */
    val totalShards: Int
        get() = dataShards + parityShards

    /** True when this packet is a parity shard rather than one carrying frame bytes. */
    val isParityShard: Boolean
        get() = fecIndex >= dataShards

    /**
     * Index of the FEC block this packet belongs to, for frames split across several blocks.
     *
     * **UNVERIFIED** (spec §7.4, consolidated item 8): the bit packing of `multiFecFlags`. The
     * spec's own v1 instruction — derive the index from `multiFecFlags and 0x3` — is what
     * [UnverifiedRtpVideoConstants.MULTI_FEC_BLOCK_INDEX_MASK] encodes. Always `0` for the
     * single-block case, which is the only case that matters below very large frames.
     */
    val multiFecBlockIndex: Int
        get() = multiFecFlags and UnverifiedRtpVideoConstants.MULTI_FEC_BLOCK_INDEX_MASK

    /** Number of FEC blocks this frame is split across, never reported as less than one. */
    val multiFecBlockCount: Int
        get() = if (multiFecBlocks < 1) 1 else multiFecBlocks

    /**
     * Whether the FEC geometry this header describes is internally consistent.
     *
     * A block needs at least one data shard, cannot exceed what GF(2^8) can address, and this
     * packet's own index must fall inside it. Beyond catching corruption, this is the check that
     * catches an *encrypted* stream (spec §7.6): with a 32-byte encryption header in front of it,
     * what we read as `fecInfo` is ciphertext, and ciphertext almost never satisfies these three
     * constraints at once.
     */
    val hasPlausibleFecGeometry: Boolean
        get() {
            val data = dataShards
            if (data < 1) return false
            val total = totalShards
            if (total > RtpVideoConstants.MAX_SHARDS_PER_BLOCK) return false
            if (fecIndex >= total) return false
            return multiFecBlockCount <= UnverifiedRtpVideoConstants.MAX_FEC_BLOCKS_PER_FRAME
        }

    companion object {

        /**
         * Parses an NV video packet header out of [data].
         *
         * @param data the received datagram; not retained.
         * @param offset first byte of the NV header, i.e. just past the RTP header.
         * @param length bytes available from [offset].
         * @return the parsed header, or `null` when the datagram is shorter than the header.
         */
        fun parse(
            data: ByteArray,
            offset: Int,
            length: Int = data.size - offset,
        ): NvVideoPacketHeader? {
            if (offset < 0 || length < 0 || offset > data.size - length) return null
            if (length < RtpVideoConstants.NV_VIDEO_HEADER_SIZE) return null
            val frameIndexRaw = RtpBytes.leI32(
                data,
                offset + RtpVideoConstants.NV_OFFSET_FRAME_INDEX,
            )
            return NvVideoPacketHeader(
                streamPacketIndex = RtpBytes.leI32(
                    data,
                    offset + RtpVideoConstants.NV_OFFSET_STREAM_PACKET_INDEX,
                ),
                frameIndex = frameIndexRaw.toLong() and 0xFFFFFFFFL,
                flags = RtpBytes.u8(data, offset + RtpVideoConstants.NV_OFFSET_FLAGS),
                extraFlags = RtpBytes.u8(data, offset + RtpVideoConstants.NV_OFFSET_EXTRA_FLAGS),
                multiFecFlags = RtpBytes.u8(
                    data,
                    offset + RtpVideoConstants.NV_OFFSET_MULTI_FEC_FLAGS,
                ),
                multiFecBlocks = RtpBytes.u8(
                    data,
                    offset + RtpVideoConstants.NV_OFFSET_MULTI_FEC_BLOCKS,
                ),
                fecInfo = RtpBytes.leI32(data, offset + RtpVideoConstants.NV_OFFSET_FEC_INFO),
            )
        }
    }
}
