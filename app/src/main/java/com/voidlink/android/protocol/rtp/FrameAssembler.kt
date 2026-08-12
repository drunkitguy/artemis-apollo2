package com.voidlink.android.protocol.rtp

import com.voidlink.android.protocol.ProtocolLog

/**
 * How a [FrameAssembler] behaves. All of it is decided at session start from RTSP negotiation.
 *
 * @param bitstream the negotiated codec, needed only to recognise a keyframe (spec §7.8).
 * @param requireKeyFrameToStart drop every frame until the first keyframe arrives, as spec §7.8
 *   requires. Turning it off is a debugging affordance, not a supported mode.
 * @param fecRecoveryEnabled whether Reed-Solomon may repair a frame with missing data shards.
 *   Defaults to [UnverifiedRtpVideoConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT], which is `false`
 *   — see spec §7.7's mandatory mitigation and the KDoc on that constant. With it off, a frame
 *   completes only when every data shard arrives, and loss costs one dropped frame plus one IDR
 *   request.
 * @param fecMatrixVariant which generator matrix to use if recovery is on.
 * @param maxFrameBytes refuse to assemble anything larger; a bigger frame means misread geometry.
 * @param videoEncryptionNegotiated set when RTSP agreed `SS_ENC_VIDEO`, which v1 cannot decrypt
 *   (spec §7.6). Makes every packet fail loudly instead of producing garbage.
 */
data class FrameAssemblerConfig(
    val bitstream: VideoBitstream = VideoBitstream.H264,
    val requireKeyFrameToStart: Boolean = true,
    val fecRecoveryEnabled: Boolean =
        UnverifiedRtpVideoConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT,
    val fecMatrixVariant: ReedSolomonMatrix = UnverifiedRtpVideoConstants.FEC_MATRIX_VARIANT,
    val maxFrameBytes: Int = RtpVideoConstants.MAX_FRAME_BYTES,
    val videoEncryptionNegotiated: Boolean = false,
)

/**
 * A snapshot of what the video receive path has seen (spec §11.1, §11.2).
 *
 * [packetsReceived] staying at zero is `ML_ERROR_NO_VIDEO_TRAFFIC`; [packetsReceived] climbing
 * while [framesCompleted] stays at zero is `ML_ERROR_NO_VIDEO_FRAME`. Spec §11.1 is explicit that
 * these two diagnoses deserve different user-facing text, which is only possible if both numbers
 * are kept.
 *
 * [highestSequenceNumber] and [nextContiguousSequenceNumber] are the two figures spec §9.5's
 * Sunshine per-frame FEC status message needs.
 */
data class FrameAssemblerStats(
    val packetsReceived: Long,
    val packetsRejected: Long,
    val packetsLost: Long,
    val packetsDuplicated: Long,
    val packetsLate: Long,
    val framesCompleted: Long,
    val framesDropped: Long,
    val framesRecovered: Long,
    val shardsRecovered: Long,
    val highestSequenceNumber: Int,
    val nextContiguousSequenceNumber: Int,
)

/**
 * What one datagram produced.
 *
 * @property frame a complete frame, when this datagram was the one that finished it.
 * @property events everything the session needs to know about; empty on the common path.
 */
data class AssemblyResult(
    val frame: VideoFrame?,
    val events: List<VideoStreamEvent>,
) {

    /** True when any event in this result means the host should be asked for an IDR (spec §9.5). */
    val requestsIdr: Boolean
        get() {
            for (event in events) {
                if (event.requestsIdr) return true
            }
            return false
        }

    companion object {
        /** The overwhelmingly common outcome: a packet was filed and nothing else happened. */
        val NOTHING: AssemblyResult = AssemblyResult(null, emptyList())
    }
}

/**
 * Reassembles RTP video datagrams into whole frames (spec §7.7, §7.8).
 *
 * The algorithm is spec §7.7's, with reordering tolerated throughout:
 *
 * 1. Parse the RTP and NV headers ([VideoPacketParser]).
 * 2. Classify the sequence number as fresh, duplicate or late ([SequenceTracker]) — this is what
 *    keeps ordinary Wi-Fi reordering from being reported as loss.
 * 3. File the shard under `(frameIndex, multiFecBlockIndex)`, at its `fecIndex` within the block.
 * 4. When every data shard of every block of a frame is present, concatenate their payloads in
 *    ascending shard order, skipping shards without `FLAG_CONTAINS_PIC_DATA` (spec §7.8).
 * 5. When a *newer* frame starts before the current one is complete, the current one can never
 *    complete: drop it and say so. Spec §7.7 step 8.
 *
 * **Loss is never papered over.** Every frame that cannot be completed is dropped whole and
 * reported through a [VideoStreamEvent] whose [VideoStreamEvent.requestsIdr] is true. Feeding a
 * decoder the fragments we did receive would produce corruption that persists until the next
 * keyframe and looks, from the outside, exactly like a decoder bug.
 *
 * Reed-Solomon recovery (spec §7.7 step 7) is attempted only when [FrameAssemblerConfig.fecRecoveryEnabled]
 * is on, which by default it is not — the RS code is not on the path a healthy stream takes at all.
 *
 * **Not thread-safe.** One instance belongs to one `video-rx` thread (architecture §3). Plain
 * JVM: no Android API is touched beyond `ProtocolLog`, so the whole class is unit-testable.
 */
class FrameAssembler(
    private val config: FrameAssemblerConfig = FrameAssemblerConfig(),
) {

    private val parser = VideoPacketParser(config.videoEncryptionNegotiated)
    private val sequences = SequenceTracker()
    private val fecCache: ReedSolomonCache? =
        if (config.fecRecoveryEnabled) ReedSolomonCache(config.fecMatrixVariant) else null

    private var pendingFrame: PendingFrame? = null
    private var lastFinishedFrameIndex: Long = -1L
    private var keyFrameSeen: Boolean = false

    private var packetsReceived: Long = 0L
    private var packetsRejected: Long = 0L
    private var framesCompleted: Long = 0L
    private var framesDropped: Long = 0L
    private var framesRecovered: Long = 0L
    private var shardsRecovered: Long = 0L

    init {
        if (config.fecRecoveryEnabled) {
            ProtocolLog.unverified(
                RtpVideoConstants.LOG_TAG_VIDEO,
                "video-fec-matrix",
                "Reed-Solomon recovery is ENABLED with matrix variant " +
                    "${config.fecMatrixVariant}; spec 01 §7.7 marks the matrix construction as " +
                    "the riskiest unverified detail in the protocol, and a mismatch corrupts " +
                    "recovered frames silently rather than failing. If the picture breaks up " +
                    "where it used to merely stutter, turn fecRecoveryEnabled off first.",
            )
            ProtocolLog.unverified(
                RtpVideoConstants.LOG_TAG_VIDEO,
                "video-fec-shard-framing",
                "assuming an FEC shard spans the NV video packet header plus the payload " +
                    "(spec 01 §7.7 does not say where a shard begins); " +
                    "UnverifiedRtpVideoConstants.FEC_SHARD_INCLUDES_NV_HEADER=" +
                    "${UnverifiedRtpVideoConstants.FEC_SHARD_INCLUDES_NV_HEADER}",
            )
        }
    }

    /**
     * Feeds one received datagram in.
     *
     * @param datagram the receive buffer. Not retained — every byte the assembler keeps is copied,
     *   so the caller is free to recycle it the moment this returns (architecture §3, rule 2).
     * @param length bytes actually received.
     */
    fun submit(datagram: ByteArray, length: Int = datagram.size): AssemblyResult {
        val packet = when (val parsed = parser.parse(datagram, length)) {
            is VideoPacketParseResult.Rejected -> {
                packetsRejected++
                return AssemblyResult(
                    null,
                    listOf(VideoStreamEvent.PacketRejected(parsed.reason, parsed.detail)),
                )
            }

            is VideoPacketParseResult.Parsed -> parsed.packet
        }

        packetsReceived++
        val events = ArrayList<VideoStreamEvent>(2)

        val status = sequences.receive(packet.rtp.sequenceNumber)
        val lost = sequences.takePendingLostCount()
        if (lost > 0) {
            events.add(
                VideoStreamEvent.PacketsLost(lost, sequences.highestSequenceNumber),
            )
        }
        if (status != SequencePacketStatus.FRESH) {
            return result(null, events)
        }

        val frame = accept(packet, events)
        return result(frame, events)
    }

    /** Drops all in-progress state. Used when a stream restarts inside one session. */
    fun reset() {
        pendingFrame = null
        lastFinishedFrameIndex = -1L
        keyFrameSeen = false
        sequences.reset()
    }

    /**
     * A snapshot of the counters.
     *
     * Call it from the receive thread, or accept a slightly stale reading: the counters are plain
     * fields because incrementing them must not cost anything per packet.
     */
    fun stats(): FrameAssemblerStats = FrameAssemblerStats(
        packetsReceived = packetsReceived,
        packetsRejected = packetsRejected,
        packetsLost = sequences.totalLost,
        packetsDuplicated = sequences.totalDuplicates,
        packetsLate = sequences.totalLate,
        framesCompleted = framesCompleted,
        framesDropped = framesDropped,
        framesRecovered = framesRecovered,
        shardsRecovered = shardsRecovered,
        highestSequenceNumber = sequences.highestSequenceNumber,
        nextContiguousSequenceNumber = sequences.nextContiguousSequenceNumber,
    )

    private fun result(frame: VideoFrame?, events: List<VideoStreamEvent>): AssemblyResult =
        if (frame == null && events.isEmpty()) AssemblyResult.NOTHING else AssemblyResult(frame, events)

    /** Files one fresh packet and returns a frame if it was the one that completed it. */
    private fun accept(
        packet: VideoPacket,
        events: MutableList<VideoStreamEvent>,
    ): VideoFrame? {
        val frameIndex = packet.nv.frameIndex
        if (frameIndex <= lastFinishedFrameIndex) {
            // The frame this belongs to has already been decoded or given up on. Nothing useful
            // can be done with it; counting it as loss would be wrong, it did arrive.
            return null
        }

        val inProgress = pendingFrame
        if (inProgress != null && inProgress.frameIndex != frameIndex) {
            if (frameIndex < inProgress.frameIndex) {
                // A straggler from a frame older than the one in progress, but newer than the last
                // we finished. It cannot be completed either.
                return null
            }
            // Spec §7.7 step 8: a later frame has begun, so this one will never be completed.
            abandon(inProgress, FrameDropReason.INCOMPLETE, events)
        }

        val pending = pendingFrame ?: startFrame(frameIndex, packet, events) ?: return null

        if (!insert(pending, packet, events)) return null
        if (!pending.isComplete()) return null
        return complete(pending, events)
    }

    /**
     * Begins gathering a new frame.
     *
     * @return the new frame, or `null` when the packet's own block count is one we cannot address
     *   (spec §7.4, UNVERIFIED item 8), in which case the frame is dropped before it starts.
     */
    private fun startFrame(
        frameIndex: Long,
        packet: VideoPacket,
        events: MutableList<VideoStreamEvent>,
    ): PendingFrame? {
        noteSkippedFrames(frameIndex, events)
        val blockCount = packet.nv.multiFecBlockCount
        if (blockCount > UnverifiedRtpVideoConstants.MAX_FEC_BLOCKS_PER_FRAME) {
            framesDropped++
            lastFinishedFrameIndex = frameIndex
            events.add(VideoStreamEvent.FrameDropped(frameIndex, FrameDropReason.MALFORMED, 0))
            return null
        }
        if (blockCount > 1) {
            ProtocolLog.unverified(
                RtpVideoConstants.LOG_TAG_VIDEO,
                "video-multi-fec-flags",
                "a frame claims $blockCount FEC blocks; deriving the block index from " +
                    "multiFecFlags and mask " +
                    UnverifiedRtpVideoConstants.MULTI_FEC_BLOCK_INDEX_MASK +
                    " per spec 01 §7.4 (UNVERIFIED item 8). A frame that fails this path is " +
                    "dropped and an IDR requested, so the worst case is a stutter.",
            )
        }
        val created = PendingFrame(frameIndex, blockCount)
        pendingFrame = created
        return created
    }

    /** Reports frame indices we never saw a single packet of (spec §7.8). */
    private fun noteSkippedFrames(frameIndex: Long, events: MutableList<VideoStreamEvent>) {
        if (lastFinishedFrameIndex < 0L) return
        val expected = lastFinishedFrameIndex + 1L
        if (frameIndex <= expected) return
        val missing = frameIndex - expected
        val reported = if (missing > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else missing.toInt()
        framesDropped += reported.toLong()
        events.add(VideoStreamEvent.FramesMissing(expected, reported))
    }

    /**
     * Stores one shard.
     *
     * @return false when the packet could not be filed, in which case the frame has already been
     *   abandoned or the packet was a duplicate within its block.
     */
    private fun insert(
        pending: PendingFrame,
        packet: VideoPacket,
        events: MutableList<VideoStreamEvent>,
    ): Boolean {
        val nv = packet.nv
        val blockIndex = nv.multiFecBlockIndex
        if (blockIndex >= pending.expectedBlocks) {
            abandon(pending, FrameDropReason.MALFORMED, events)
            return false
        }

        val existing = pending.blocks[blockIndex]
        val block: PendingBlock
        if (existing == null) {
            block = PendingBlock(packet.blockBaseSequenceNumber, nv.dataShards, nv.parityShards)
            pending.blocks[blockIndex] = block
        } else {
            if (existing.dataShards != nv.dataShards ||
                existing.parityShards != nv.parityShards ||
                existing.baseSequenceNumber != packet.blockBaseSequenceNumber
            ) {
                // A block's geometry and base sequence number are fixed for its lifetime (spec
                // §7.7 step 5). If either changes, one of the two packets is misparsed and neither
                // can be trusted — filing them together would build a frame out of the wrong
                // bytes, which is precisely the corruption this layer exists to prevent.
                abandon(pending, FrameDropReason.MALFORMED, events)
                return false
            }
            block = existing
        }

        val fecIndex = nv.fecIndex
        if (fecIndex >= block.totalShards) {
            abandon(pending, FrameDropReason.MALFORMED, events)
            return false
        }
        if (block.shards[fecIndex] != null) return false

        val shard = packet.copyShard()
        block.shards[fecIndex] = shard
        if (shard.size > block.shardSize) block.shardSize = shard.size
        if (fecIndex < block.dataShards) block.receivedDataShards++ else block.receivedParityShards++

        pending.rtpTimestamp = packet.rtp.timestamp
        if (nv.isLongTermReferenceFrame) pending.longTermReference = true

        recoverIfPossible(pending, block, events)
        return true
    }

    /**
     * Spec §7.7 step 7 — the only place Reed-Solomon is touched, and only when it is switched on
     * *and* a data shard is actually missing.
     */
    private fun recoverIfPossible(
        pending: PendingFrame,
        block: PendingBlock,
        events: MutableList<VideoStreamEvent>,
    ) {
        val cache = fecCache ?: return
        if (block.receivedDataShards == block.dataShards) return
        if (block.receivedDataShards + block.receivedParityShards < block.dataShards) return
        if (block.shardSize < RtpVideoConstants.NV_VIDEO_HEADER_SIZE) return

        val codec = cache.get(block.dataShards, block.parityShards) ?: return
        val shardSize = block.shardSize
        val work = arrayOfNulls<ByteArray>(block.totalShards)
        for (index in 0 until block.totalShards) {
            val shard = block.shards[index] ?: continue
            // Spec §7.7: all shards in a block are the same size, zero-padded as needed. A short
            // trailing datagram is padded here rather than on the wire.
            work[index] = if (shard.size == shardSize) shard else shard.copyOf(shardSize)
        }
        if (!codec.decodeMissing(work, shardSize)) return

        var recovered = 0
        for (index in 0 until block.dataShards) {
            if (block.shards[index] != null) continue
            val rebuilt = work[index] ?: continue
            block.shards[index] = rebuilt
            block.receivedDataShards++
            recovered++
        }
        if (recovered <= 0) return

        shardsRecovered += recovered.toLong()
        pending.recoveredShards += recovered
        events.add(VideoStreamEvent.FrameRecovered(pending.frameIndex, recovered))
    }

    /** Concatenates a complete frame's payloads and applies the keyframe gate of spec §7.8. */
    private fun complete(
        pending: PendingFrame,
        events: MutableList<VideoStreamEvent>,
    ): VideoFrame? {
        pendingFrame = null
        lastFinishedFrameIndex = pending.frameIndex

        var total = 0L
        for (blockIndex in 0 until pending.expectedBlocks) {
            val block = pending.blocks[blockIndex] ?: return malformed(pending, events)
            for (shardIndex in 0 until block.dataShards) {
                val shard = block.shards[shardIndex] ?: return malformed(pending, events)
                if (!carriesPictureData(shard)) continue
                total += (shard.size - RtpVideoConstants.NV_VIDEO_HEADER_SIZE).toLong()
            }
        }

        if (total <= 0L) {
            // Every data shard was present and not one of them carried picture data. That is not a
            // frame; it is a sign the flags byte was read from the wrong offset.
            return malformed(pending, events)
        }
        if (total > config.maxFrameBytes.toLong()) {
            framesDropped++
            events.add(
                VideoStreamEvent.FrameDropped(
                    pending.frameIndex,
                    FrameDropReason.OVERSIZED,
                    0,
                ),
            )
            return null
        }

        val output = ByteArray(total.toInt())
        var cursor = 0
        for (blockIndex in 0 until pending.expectedBlocks) {
            val block = pending.blocks[blockIndex] ?: return malformed(pending, events)
            for (shardIndex in 0 until block.dataShards) {
                val shard = block.shards[shardIndex] ?: return malformed(pending, events)
                if (!carriesPictureData(shard)) continue
                val payload = shard.size - RtpVideoConstants.NV_VIDEO_HEADER_SIZE
                if (payload <= 0) continue
                System.arraycopy(
                    shard,
                    RtpVideoConstants.NV_VIDEO_HEADER_SIZE,
                    output,
                    cursor,
                    payload,
                )
                cursor += payload
            }
        }

        val keyFrame = AnnexB.isKeyFrame(config.bitstream, output)
        if (config.requireKeyFrameToStart && !keyFrameSeen && !keyFrame) {
            // Spec §7.8: the first frame we submit must be a keyframe. Submitting anything else
            // gives the decoder no reference and produces a screen of garbage.
            framesDropped++
            events.add(
                VideoStreamEvent.FrameDropped(
                    pending.frameIndex,
                    FrameDropReason.WAITING_FOR_KEY_FRAME,
                    0,
                ),
            )
            return null
        }
        if (keyFrame && !keyFrameSeen) {
            keyFrameSeen = true
            events.add(VideoStreamEvent.FirstKeyFrameReceived(pending.frameIndex))
        }

        framesCompleted++
        if (pending.recoveredShards > 0) framesRecovered++
        return VideoFrame(
            frameIndex = pending.frameIndex,
            rtpTimestamp = pending.rtpTimestamp,
            data = output,
            isKeyFrame = keyFrame,
            isLongTermReferenceFrame = pending.longTermReference,
            recoveredShardCount = pending.recoveredShards,
        )
    }

    private fun carriesPictureData(shard: ByteArray): Boolean {
        val flags = shard[RtpVideoConstants.NV_OFFSET_FLAGS].toInt() and 0xFF
        return (flags and RtpVideoConstants.FLAG_CONTAINS_PIC_DATA) != 0
    }

    private fun malformed(
        pending: PendingFrame,
        events: MutableList<VideoStreamEvent>,
    ): VideoFrame? {
        framesDropped++
        events.add(
            VideoStreamEvent.FrameDropped(pending.frameIndex, FrameDropReason.MALFORMED, 0),
        )
        return null
    }

    /** Gives up on the frame in progress and says so. */
    private fun abandon(
        pending: PendingFrame,
        reason: FrameDropReason,
        events: MutableList<VideoStreamEvent>,
    ) {
        pendingFrame = null
        lastFinishedFrameIndex = pending.frameIndex
        framesDropped++
        events.add(
            VideoStreamEvent.FrameDropped(
                pending.frameIndex,
                reason,
                pending.missingDataShards(),
            ),
        )
    }

    /** One frame being gathered, across one or more FEC blocks (spec §7.4, §7.7). */
    private class PendingFrame(val frameIndex: Long, val expectedBlocks: Int) {
        val blocks = HashMap<Int, PendingBlock>()
        var rtpTimestamp: Int = 0
        var longTermReference: Boolean = false
        var recoveredShards: Int = 0

        fun isComplete(): Boolean {
            if (blocks.size < expectedBlocks) return false
            for (index in 0 until expectedBlocks) {
                val block = blocks[index] ?: return false
                if (block.receivedDataShards < block.dataShards) return false
            }
            return true
        }

        /** How many data shards are still outstanding, for the drop report. */
        fun missingDataShards(): Int {
            var missing = 0
            for (index in 0 until expectedBlocks) {
                val block = blocks[index]
                missing += if (block == null) 0 else block.dataShards - block.receivedDataShards
            }
            return missing
        }
    }

    /**
     * One FEC block of one frame, keyed in its frame by block index and identified on the wire by
     * `(frameIndex, blockBaseSequenceNumber)` (spec §7.7 step 5).
     *
     * A shard is stored whole — NV header included — because a *recovered* shard must carry its
     * own `flags` byte for spec §7.8's "exclude shards without `FLAG_CONTAINS_PIC_DATA`" rule to
     * be applicable to it. See [UnverifiedRtpVideoConstants.FEC_SHARD_INCLUDES_NV_HEADER].
     */
    private class PendingBlock(
        val baseSequenceNumber: Int,
        val dataShards: Int,
        val parityShards: Int,
    ) {
        val totalShards: Int = dataShards + parityShards
        val shards = arrayOfNulls<ByteArray>(totalShards)
        var receivedDataShards: Int = 0
        var receivedParityShards: Int = 0

        /** The common shard length for FEC, i.e. the longest shard seen in this block. */
        var shardSize: Int = 0
    }
}
