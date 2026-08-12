package com.voidlink.android.protocol.rtp

/**
 * One complete, decodable frame — the decode unit of architecture §2.2 (spec §7.8).
 *
 * [data] is the concatenation of every data shard's payload in ascending sequence order, with
 * shards that do not set `FLAG_CONTAINS_PIC_DATA` left out. For H.264 and HEVC that makes it an
 * Annex-B elementary stream fragment — start codes followed by NAL units — which spec §7.8 says to
 * hand to `MediaCodec` **verbatim**: no re-framing, no NAL rewriting, no start-code normalisation.
 *
 * A [VideoFrame] is only ever produced when the frame is *whole*. A frame missing bytes is dropped
 * and reported as [VideoStreamEvent.FrameDropped]; it never reaches here. That is the whole point
 * of the layer, because a decoder fed a partial frame produces corruption that persists until the
 * next keyframe.
 *
 * Not a `data class`: [data] is a `ByteArray`, whose generated `equals` would compare identity and
 * quietly mislead every test that used it.
 *
 * @property frameIndex the host's `frameIndex` (spec §7.4), monotonically increasing.
 * @property rtpTimestamp the RTP timestamp shared by the frame's packets. Spec §7.8 uses it for
 *   relative ordering only — the `presentationTimeUs` handed to `MediaCodec` comes from the local
 *   clock, because we render immediately and never schedule.
 * @property data the exact frame bytes; the array is owned by the receiver and is never recycled.
 * @property isKeyFrame whether a decoder can start on this frame; drives `BUFFER_FLAG_KEY_FRAME`.
 * @property isLongTermReferenceFrame the host marked this an LTR frame (spec §7.4).
 * @property recoveredShardCount how many of the frame's data shards were rebuilt by Reed-Solomon
 *   rather than received. Always `0` unless FEC recovery is enabled; when it is non-zero and the
 *   picture is corrupt, spec §7.7's UNVERIFIED matrix variant is the first suspect.
 */
class VideoFrame(
    val frameIndex: Long,
    val rtpTimestamp: Int,
    val data: ByteArray,
    val isKeyFrame: Boolean,
    val isLongTermReferenceFrame: Boolean,
    val recoveredShardCount: Int,
) {

    /** Size of [data], for callers that would rather not reach into the array. */
    val length: Int
        get() = data.size

    override fun toString(): String =
        "VideoFrame(frameIndex=$frameIndex, bytes=${data.size}, keyFrame=$isKeyFrame, " +
            "ltr=$isLongTermReferenceFrame, recoveredShards=$recoveredShardCount)"
}
