package com.voidlink.android.protocol.rtp

/**
 * Every constant the video RTP receive path needs, transcribed from `docs/01-PROTOCOL.md` §7 and
 * cross-referenced by section.
 *
 * This mirrors the role `com.voidlink.android.protocol.ProtocolConstants` plays for the rest of the
 * protocol layer. It is a **separate** object only because `ProtocolConstants.kt` is owned by
 * another workstream while this tree is being written; the two should be merged once both have
 * landed, at which point every name here moves across unchanged. Nothing else under
 * `protocol/rtp/` may define a protocol constant.
 *
 * Values the spec explicitly marks **UNVERIFIED** live in [UnverifiedRtpVideoConstants] so the
 * guessed surface of the video path stays countable at a glance.
 */
object RtpVideoConstants {

    // ---- Logging -----------------------------------------------------------------------------

    /** Subsystem tag for the video path, matching architecture §9's tag table. */
    const val LOG_TAG_VIDEO: String = "VL.Video"

    // ---- RTP header (spec §7.3) --------------------------------------------------------------

    /** The fixed RTP header every video datagram starts with. */
    const val FIXED_RTP_HEADER_SIZE: Int = 12

    /** The header size once the four-byte extension of [RTP_FLAG_EXTENSION] is present. */
    const val MAX_RTP_HEADER_SIZE: Int = 16

    /** Size of the header extension we skip over. */
    const val RTP_EXTENSION_SIZE: Int = MAX_RTP_HEADER_SIZE - FIXED_RTP_HEADER_SIZE

    /** `header and 0x10 != 0` means a four-byte extension follows the fixed header (spec §7.3). */
    const val RTP_FLAG_EXTENSION: Int = 0x10

    /** Field offsets within the fixed RTP header. Sequence/timestamp/ssrc are **big-endian**. */
    const val RTP_OFFSET_FLAGS: Int = 0
    const val RTP_OFFSET_PACKET_TYPE: Int = 1
    const val RTP_OFFSET_SEQUENCE_NUMBER: Int = 2
    const val RTP_OFFSET_TIMESTAMP: Int = 4
    const val RTP_OFFSET_SSRC: Int = 8

    // ---- NV video packet header (spec §7.4) --------------------------------------------------

    /** The vendor header that sits immediately after the RTP header. */
    const val NV_VIDEO_HEADER_SIZE: Int = 16

    /** Field offsets within the NV header. The 32-bit fields are **little-endian**. */
    const val NV_OFFSET_STREAM_PACKET_INDEX: Int = 0
    const val NV_OFFSET_FRAME_INDEX: Int = 4
    const val NV_OFFSET_FLAGS: Int = 8
    const val NV_OFFSET_EXTRA_FLAGS: Int = 9
    const val NV_OFFSET_MULTI_FEC_FLAGS: Int = 10
    const val NV_OFFSET_MULTI_FEC_BLOCKS: Int = 11
    const val NV_OFFSET_FEC_INFO: Int = 12

    /** `flags` bits (spec §7.4). */
    const val FLAG_CONTAINS_PIC_DATA: Int = 0x1
    const val FLAG_EOF: Int = 0x2
    const val FLAG_SOF: Int = 0x4

    /** `extraFlags` bits (spec §7.4). */
    const val EXTRA_FLAG_LTR_FRAME: Int = 0x1

    /**
     * `fecInfo` field extraction (spec §7.4).
     *
     * The spec writes these as `fecInfo and MASK shr SHIFT`. Shifting first and masking second is
     * the identical operation and avoids writing `0xFFC00000` as an `Int` literal, which Kotlin
     * types as `Long` — a mistake that compiles into a silently wrong shard count.
     */
    const val FEC_INDEX_SHIFT: Int = 12
    const val FEC_INDEX_MASK: Int = 0x3FF
    const val FEC_PERCENTAGE_SHIFT: Int = 4
    const val FEC_PERCENTAGE_MASK: Int = 0xFF
    const val FEC_DATA_SHARDS_SHIFT: Int = 22
    const val FEC_DATA_SHARDS_MASK: Int = 0x3FF

    /** Reed-Solomon over GF(2^8) cannot address more than 255 shards in a block (spec §7.7). */
    const val MAX_SHARDS_PER_BLOCK: Int = 255

    // ---- Encryption (spec §7.6) --------------------------------------------------------------

    /**
     * The Sunshine `SS_ENC_VIDEO` header that would sit between the RTP and NV headers.
     *
     * v1 does not negotiate video encryption. The size is recorded so that the parser can say
     * exactly what it would have to skip, rather than producing garbage (spec §7.6).
     */
    const val ENCRYPTION_HEADER_SIZE: Int = 32

    // ---- Sequence numbers --------------------------------------------------------------------

    /** RTP sequence numbers are 16-bit and wrap; all arithmetic on them is modular. */
    const val SEQUENCE_NUMBER_MASK: Int = 0xFFFF
    const val SEQUENCE_NUMBER_MODULUS: Int = 0x10000

    /**
     * How far behind the highest received sequence number a hole may sit before it is declared
     * lost rather than merely reordered.
     *
     * Too small and ordinary Wi-Fi reordering is reported as loss, which triggers pointless IDR
     * requests; too large and genuine loss is noticed a frame or two late. Sixty-four packets is
     * a fraction of one frame at any bitrate we support.
     */
    const val SEQUENCE_REORDER_TOLERANCE: Int = 64

    /**
     * Width of the received-packet window used to tell reordering from loss.
     *
     * Must exceed [SEQUENCE_REORDER_TOLERANCE] with room to spare and must be a power of two, so
     * that indexing is a mask rather than a division.
     */
    const val SEQUENCE_WINDOW_SIZE: Int = 256

    // ---- Assembly and queueing ---------------------------------------------------------------

    /**
     * Decode-unit queue capacity (architecture §3, rule 1).
     *
     * Two. Never grow this "for smoothness": a deeper queue converts loss into latency, which is
     * the one thing this product cannot trade away.
     */
    const val DECODE_QUEUE_CAPACITY: Int = 2

    /** Capacity of the loss/status event queue handed to the control channel. */
    const val EVENT_QUEUE_CAPACITY: Int = 64

    /**
     * Upper bound on a single reassembled frame.
     *
     * A frame larger than this is a sign that the shard geometry was misparsed, not that the host
     * sent a genuinely enormous IDR; assembling it would allocate wildly on the receive thread.
     */
    const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

    // ---- Annex-B / NAL (spec §7.8) -----------------------------------------------------------

    /** H.264 NAL unit types that mean "decoding can start here". */
    const val NAL_H264_IDR: Int = 5
    const val NAL_H264_SPS: Int = 7

    /** HEVC NAL unit type ranges that mean "decoding can start here". */
    const val NAL_HEVC_IRAP_FIRST: Int = 16
    const val NAL_HEVC_IRAP_LAST: Int = 21
    const val NAL_HEVC_PARAMETER_SET_FIRST: Int = 32
    const val NAL_HEVC_PARAMETER_SET_LAST: Int = 34

    /** AV1 OBU type for a sequence header. */
    const val OBU_SEQUENCE_HEADER: Int = 1

    /** Bound on the OBU walk, so a malformed AV1 frame cannot spin the receive thread. */
    const val MAX_OBUS_SCANNED: Int = 64
}

/**
 * Video-path constants whose values `docs/01-PROTOCOL.md` explicitly marks **UNVERIFIED**.
 *
 * Each carries the spec section that flags it and the consequence of the guess being wrong, in the
 * style of `com.voidlink.android.protocol.UnverifiedProtocolConstants`. Every code path that
 * depends on one of these logs once per process through `ProtocolLog.unverified`.
 */
object UnverifiedRtpVideoConstants {

    /**
     * Whether Reed-Solomon recovery participates in reassembly at all.
     *
     * UNVERIFIED(spec 01 §7.7, consolidated item 1 — **the riskiest item in the document**): the
     * exact RS generator-matrix construction the host uses. Two implementations that both claim
     * GF(2^8) will not interoperate if the matrix differs, and the failure mode is *silent
     * corruption* of recovered frames rather than a clean error.
     *
     * Risk if wrong: persistent visual corruption that looks like a decoder bug.
     * Mitigation, and the reason this defaults to `false`: when every data shard arrives — the
     * overwhelmingly common case on a LAN — the RS code is never touched, so a stream works
     * correctly with recovery off. Loss then costs one dropped frame and one IDR request, which is
     * a bounded, visible, honest failure.
     */
    const val FEC_RECOVERY_ENABLED_BY_DEFAULT: Boolean = false

    /**
     * Which generator matrix [ReedSolomon] builds.
     *
     * UNVERIFIED(spec 01 §7.7, consolidated item 1). The default follows the Rizzo `fec.c` lineage
     * that `nanors` — the library `moonlight-common-c` links for video FEC (spec §14) — descends
     * from. [ReedSolomonMatrix.INTEGER_POWER_ROWS] is the other construction in wide circulation
     * and is one assignment away.
     *
     * Risk if wrong: recovered frames are corrupt. Only reachable when
     * [FEC_RECOVERY_ENABLED_BY_DEFAULT] (or the per-session override) is on.
     */
    val FEC_MATRIX_VARIANT: ReedSolomonMatrix = ReedSolomonMatrix.ALPHA_POWER_ROWS

    /**
     * Whether an FEC shard is the packet's **payload only**, excluding the NV video packet header.
     *
     * UNVERIFIED(spec 01 §7.7): the spec states only that "all shards in a block are the same size
     * (`blockSize`), zero-padded as needed" and never says where a shard begins.
     *
     * Payload-only is the reading the rest of the spec forces. Spec §7.4 defines `fecIndex` as
     * living in the NV header and describes it as identifying which shards are parity ("data shards
     * come first, then parity shards"), and §7.7 step 5 keys a block on
     * `rtpSequenceNumber - fecIndex`. Both require a **parity** packet to carry a readable NV
     * header — which it could not, if that header were itself parity bytes.
     *
     * Risk if wrong: recovery produces misaligned shards, i.e. corrupt frames. Same blast radius
     * as the matrix variant, and gated behind the same flag.
     */
    const val FEC_SHARD_IS_PAYLOAD_ONLY: Boolean = true

    /**
     * Whether a data shard rebuilt by Reed-Solomon is assumed to carry picture data.
     *
     * UNVERIFIED(spec 01 §7.7, §7.8), and a direct consequence of [FEC_SHARD_IS_PAYLOAD_ONLY]: a
     * recovered shard has no NV header, so its `FLAG_CONTAINS_PIC_DATA` bit cannot be read and must
     * be assumed. `true` is the safe assumption — every data shard of a frame carries frame bytes;
     * a shard without them would be padding, which no observed host emits mid-frame.
     *
     * Risk if wrong: a recovered frame gains a few bytes of padding. In Annex-B those decode as the
     * `trailing_zero_8bits` the standard already permits, so the practical blast radius is small
     * even relative to the flag that gates it.
     */
    const val FEC_RECOVERED_SHARD_CARRIES_PICTURE_DATA: Boolean = true

    /**
     * Where the current FEC block index lives inside `multiFecFlags`.
     *
     * UNVERIFIED(spec 01 §7.4, consolidated item 8): the exact bit packing. The spec's own v1
     * instruction is to derive the block index from `multiFecFlags and 0x3` and log a warning,
     * which is what this mask is.
     *
     * Risk if wrong: frames large enough to be split across FEC blocks fail to assemble. Recovered
     * by dropping the frame and requesting an IDR, so it degrades rather than corrupts.
     */
    const val MULTI_FEC_BLOCK_INDEX_MASK: Int = 0x3

    /**
     * Maximum FEC blocks per frame we are prepared to track.
     *
     * Follows directly from [MULTI_FEC_BLOCK_INDEX_MASK]: a two-bit index cannot address more than
     * four blocks. A frame claiming more is treated as malformed rather than assembled from
     * aliased block indices.
     */
    const val MAX_FEC_BLOCKS_PER_FRAME: Int = MULTI_FEC_BLOCK_INDEX_MASK + 1
}
