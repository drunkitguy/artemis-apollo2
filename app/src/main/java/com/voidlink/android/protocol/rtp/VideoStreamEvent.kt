package com.voidlink.android.protocol.rtp

/** Why a frame was thrown away instead of being decoded. */
enum class FrameDropReason {

    /**
     * A later frame started before this one had all its data shards, and FEC either was off or
     * could not repair it (spec §7.7 step 8).
     */
    INCOMPLETE,

    /**
     * The assembled frame would exceed [RtpVideoConstants.MAX_FRAME_BYTES].
     *
     * In practice this means the shard geometry was misread, not that the host sent an enormous
     * IDR — but either way the frame is not trustworthy.
     */
    OVERSIZED,

    /**
     * A complete frame arrived, but no keyframe has been seen yet, so a decoder cannot start on it
     * (spec §7.8: "The first frame we submit must be a keyframe. Drop everything until one
     * arrives.").
     */
    WAITING_FOR_KEY_FRAME,

    /**
     * The frame's own headers contradicted each other — a shard index outside the block, a shard
     * count that changed mid-frame, or more FEC blocks than the block index can address (spec
     * §7.4, UNVERIFIED item 8).
     */
    MALFORMED,

    /**
     * The decode queue was full and this frame was evicted to make room for a newer one
     * (architecture §3, rule 1: capacity 2, drop oldest, request an IDR).
     */
    QUEUE_OVERFLOW,
}

/**
 * Something the video receive path needs the rest of the session to know (spec §7.7, §9.5, §11).
 *
 * These are the events the control channel consumes. [requestsIdr] marks the ones that mean "the
 * decoder can no longer make sense of this stream" — spec §9.5 requires an IDR request on exactly
 * those, and requires the *control* side to rate-limit it to roughly one per 100 ms, because a
 * lossy link otherwise turns into an IDR storm that makes things worse. This layer therefore
 * reports honestly and does not rate-limit; deciding how often to ask is the control channel's job.
 *
 * Nothing here is silently swallowed. A frame that cannot be completed is dropped **and** reported,
 * because a decoder handed a partial frame produces corruption that persists until the next
 * keyframe — which is worse than a visible stutter and far harder to diagnose.
 */
sealed interface VideoStreamEvent {

    /** True when the host must be asked for an IDR frame before decoding can recover. */
    val requestsIdr: Boolean

    /**
     * Packets given up on by the sequence tracker.
     *
     * Advisory: it says the link is lossy (spec §11.2's connection-quality figure), not that a
     * particular frame is unusable. Reordering never produces this event.
     */
    data class PacketsLost(
        val count: Int,
        val highestSequenceNumber: Int,
    ) : VideoStreamEvent {
        override val requestsIdr: Boolean
            get() = false
    }

    /** A datagram that could not be parsed at all (spec §7.7 step 1, §7.6). */
    data class PacketRejected(
        val reason: VideoPacketRejection,
        val detail: String,
    ) : VideoStreamEvent {
        override val requestsIdr: Boolean
            get() = false
    }

    /**
     * A frame was thrown away.
     *
     * @param missingDataShards how many data shards never arrived, so a log line says *why* the
     *   frame failed rather than only that it did.
     */
    data class FrameDropped(
        val frameIndex: Long,
        val reason: FrameDropReason,
        val missingDataShards: Int,
    ) : VideoStreamEvent {
        override val requestsIdr: Boolean
            get() = true
    }

    /**
     * Frame indices we never saw a single packet of (spec §7.8: "`frameIndex` is monotonically
     * increasing; a gap means a dropped frame ⇒ request IDR").
     */
    data class FramesMissing(
        val firstMissingFrameIndex: Long,
        val count: Int,
    ) : VideoStreamEvent {
        override val requestsIdr: Boolean
            get() = true
    }

    /**
     * Reed-Solomon rebuilt shards that never arrived, so the frame survived (spec §7.7 step 7).
     *
     * Only ever emitted when FEC recovery is explicitly enabled; see
     * [UnverifiedRtpVideoConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT].
     */
    data class FrameRecovered(
        val frameIndex: Long,
        val recoveredShards: Int,
    ) : VideoStreamEvent {
        override val requestsIdr: Boolean
            get() = false
    }

    /**
     * The first decodable frame of the session (spec §7.8).
     *
     * The session state machine uses it to leave "Waiting for first frame…" and to cancel the
     * `ML_ERROR_NO_VIDEO_FRAME` timer of spec §11.1.
     */
    data class FirstKeyFrameReceived(
        val frameIndex: Long,
    ) : VideoStreamEvent {
        override val requestsIdr: Boolean
            get() = false
    }
}
