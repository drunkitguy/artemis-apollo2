package com.voidlink.android.protocol.audio

import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.rtp.SequenceNumbers

/**
 * How [AudioDepacketizer] behaves.
 *
 * @property packetDurationMs the negotiated `x-nv-aqos.packetDuration` — 5 ms by default, 10 ms for
 *   a slow decoder (spec §8.5). Drives both the FEC block deadline and the RTP timestamp a
 *   concealment sample is given, so a wrong value shows up as drift rather than as an error.
 * @property fecRecoveryEnabled whether Reed-Solomon recovery participates. Off by default and for
 *   the reasons set out in [UnverifiedRtpAudioConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT]; parity
 *   packets are still parsed and counted when it is off, so the statistics say what recovery
 *   *would* have bought.
 * @property audioEncryptionNegotiated whether `SS_ENC_AUDIO` was negotiated (spec §6.5). v1
 *   negotiates `encryptionEnabled=0`, so this should always be false; the day it is not, the
 *   depacketizer refuses rather than handing ciphertext to a decoder as if it were Opus.
 * @property initialResyncDropMs how much audio to discard at start-up. See
 *   [RtpAudioConstants.INITIAL_RESYNC_DROP_MS].
 * @property outOfOrderWaitMs how long past its due time an incomplete block is held (spec §8.4).
 * @property maxConcealedPacketsPerGap bound on synthesised concealment. See
 *   [RtpAudioConstants.MAX_CONCEALED_PACKETS_PER_GAP].
 * @property maxTrackedBlocks bound on queued FEC blocks.
 */
class AudioDepacketizerConfig(
    val packetDurationMs: Int = RtpAudioConstants.DEFAULT_PACKET_DURATION_MS,
    val fecRecoveryEnabled: Boolean = UnverifiedRtpAudioConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT,
    val audioEncryptionNegotiated: Boolean = false,
    val initialResyncDropMs: Int = RtpAudioConstants.INITIAL_RESYNC_DROP_MS,
    val outOfOrderWaitMs: Long = RtpAudioConstants.OUT_OF_ORDER_WAIT_MS,
    val maxConcealedPacketsPerGap: Int = RtpAudioConstants.MAX_CONCEALED_PACKETS_PER_GAP,
    val maxTrackedBlocks: Int = RtpAudioConstants.MAX_TRACKED_FEC_BLOCKS,
) {
    init {
        require(packetDurationMs > 0) { "packetDurationMs must be positive, was $packetDurationMs" }
        require(maxTrackedBlocks >= 2) { "maxTrackedBlocks must be at least 2 to detect a stall" }
    }
}

/** What one call to [AudioDepacketizer.submit] produced. */
class AudioSubmitResult(
    /** Samples ready to decode, in stream order. Usually empty or one element. */
    val samples: List<OpusSample>,
    /** Notices for the session and the stats overlay. */
    val events: List<AudioStreamEvent>,
) {
    companion object {
        /** The do-nothing result, shared so the hot path allocates nothing for a dropped packet. */
        val EMPTY: AudioSubmitResult = AudioSubmitResult(emptyList(), emptyList())
    }
}

/**
 * Turns audio datagrams into an in-order stream of Opus packets (spec §8.4, §8.5).
 *
 * ### What it does
 *
 * Audio is not reassembled the way video is — one datagram is one Opus packet — so the only real
 * work is **ordering**, and ordering is the whole difficulty. The host lays packets out in FEC
 * blocks of four ([RtpAudioConstants.FEC_DATA_SHARDS]) whose base sequence number is always a
 * multiple of four, and emits two parity packets per block *whose RTP sequence numbers occupy the
 * same space as the data packets that follow them*. A queue that fed parity packets into its data
 * sequence logic would report loss on every block forever, so the split by payload type of §8.1 is
 * the first thing that happens here and is not optional.
 *
 * From there:
 *
 * * A packet that is the one we are waiting for is released immediately. This is the overwhelmingly
 *   common case and it costs no buffering at all — nothing waits for its block to fill.
 * * A packet that is ahead of us is filed in its block, and we keep waiting.
 * * A block that is stalled is released with concealment in the holes once it has waited
 *   `4 × packetDuration + `[AudioDepacketizerConfig.outOfOrderWaitMs] — spec §8.4's rule, expressed
 *   as a deadline rather than as a packet count so a stream that stops entirely still drains.
 * * A gap larger than [AudioDepacketizerConfig.maxConcealedPacketsPerGap] is reported and skipped
 *   rather than concealed, because synthesising a second of silence to "keep the timeline" hands
 *   the decoder a second of work it must play before it is live again. That is the accumulated
 *   latency this layer exists to prevent.
 *
 * ### What it deliberately does not do
 *
 * **Reed-Solomon recovery.** Spec §8.4's v1 instruction is to build the block machinery and skip
 * the recovery, because a wrong generator matrix corrupts rather than fails, and a corrupt audio
 * shard is an audible click where a missing one is 5 ms nobody hears. The blocks, the parity
 * bookkeeping and the counters are all here; [UnverifiedRtpAudioConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT]
 * is the one switch, and turning it on needs a `decode` implementation and a host to test against.
 *
 * ### Threading
 *
 * **Not thread-safe.** One instance belongs to one `audio-rx` thread, exactly as
 * [com.voidlink.android.protocol.rtp.FrameAssembler] belongs to one `video-rx` thread
 * (architecture §3).
 *
 * @param config see [AudioDepacketizerConfig].
 * @param clock monotonic nanosecond source, injectable so the out-of-order deadline can be tested
 *   against virtual time rather than by sleeping.
 */
class AudioDepacketizer(
    private val config: AudioDepacketizerConfig = AudioDepacketizerConfig(),
    private val clock: () -> Long = { System.nanoTime() },
) {

    /** One FEC block: four data slots and two parity slots, keyed by base sequence number. */
    private class Block(val baseSequenceNumber: Int, val queuedAtNanos: Long) {
        val shards = arrayOfNulls<ByteArray>(RtpAudioConstants.FEC_DATA_SHARDS)
        val timestamps = IntArray(RtpAudioConstants.FEC_DATA_SHARDS)
        val parity = arrayOfNulls<ByteArray>(RtpAudioConstants.FEC_PARITY_SHARDS)
        var dataReceived: Int = 0
        var parityReceived: Int = 0
        var baseTimestamp: Int = 0
        var haveBaseTimestamp: Boolean = false
        var released: Boolean = false

        val isComplete: Boolean get() = dataReceived == RtpAudioConstants.FEC_DATA_SHARDS
    }

    /** Blocks awaiting release, ordered ascending by base sequence number. */
    private val blocks = ArrayDeque<Block>()

    private var synchronizing = true
    private var nextSequenceNumber = 0
    private var oldestBaseSequenceNumber = 0

    /**
     * The RTP timestamp of the last sample handed downstream.
     *
     * Kept separately from the block timestamps because a concealment sample synthesised for a gap
     * with no block behind it has no base timestamp to work from, and a decoder that receives a
     * concealment packet timestamped zero sees the stream jump backwards. Updated at every emission
     * point, so a gap discovered part-way through one drain still extends the right timeline.
     */
    private var lastTimestamp = 0
    private var resyncDropRemaining = config.initialResyncDropMs / config.packetDurationMs
    private var sawData = false

    /**
     * Whether this link has ever delivered a data packet after its block was released.
     *
     * The difference between "a later block arrived, so the head is overdue" and "a later block
     * arrived, but this link shuffles packets, so it may yet turn up". See [releaseStalled]. Latched
     * rather than windowed: one genuine reordering is enough to know the link can do it, and the
     * cost of being cautious is 30 ms of buffering rather than 5.
     */
    private var sawOutOfOrder = false
    private var expectedToc = -1
    private var announcedEncryption = false
    private var announcedTimestampStep = false

    private var packetsReceived = 0L
    private var packetsRejected = 0L
    private var parityReceived = 0L
    private var parityIgnored = 0L
    private var duplicates = 0L
    private var late = 0L
    private var resyncDropped = 0L
    private var samplesDelivered = 0L
    private var samplesConcealed = 0L
    private var packetsLost = 0L
    private var blocksIncomplete = 0L

    /** Current counters. Cheap; safe to call after every datagram. */
    fun stats(): AudioStreamStats = AudioStreamStats(
        packetsReceived = packetsReceived,
        packetsRejected = packetsRejected,
        parityReceived = parityReceived,
        parityIgnored = parityIgnored,
        duplicates = duplicates,
        late = late,
        resyncDropped = resyncDropped,
        samplesDelivered = samplesDelivered,
        samplesConcealed = samplesConcealed,
        packetsLost = packetsLost,
        blocksIncomplete = blocksIncomplete,
    )

    /**
     * Handles one received datagram.
     *
     * @param datagram the receive buffer; **not retained** — every byte kept is copied first.
     * @param length bytes actually received.
     */
    fun submit(datagram: ByteArray, length: Int): AudioSubmitResult {
        val packet = AudioRtpPacket.parse(datagram, length)
        if (packet == null) {
            packetsRejected++
            return AudioSubmitResult.EMPTY
        }

        if (config.audioEncryptionNegotiated) {
            // v1 negotiates encryptionEnabled=0 (spec §6.5). If a host turned SS_ENC_AUDIO on
            // anyway, the payload is AES-CBC ciphertext; handing it to an Opus decoder produces
            // full-scale noise, which is very much worse than no audio at all.
            if (!announcedEncryption) {
                announcedEncryption = true
                ProtocolLog.w(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "the host negotiated encrypted audio (SS_ENC_AUDIO, spec §6.5) which this " +
                        "build cannot decrypt; every audio packet is being discarded",
                )
            }
            packetsRejected++
            return AudioSubmitResult.EMPTY
        }

        return when (packet) {
            is AudioRtpPacket.Data -> submitData(packet, datagram)
            is AudioRtpPacket.Parity -> submitParity(packet, datagram)
        }
    }

    /**
     * Called when the socket has been quiet for a moment.
     *
     * Two jobs, both of which need a tick that a packet arrival cannot provide:
     *
     * * **Draining.** A block held for reordering is released on a deadline; if the stream stops,
     *   nothing else would ever come along to notice the deadline passed.
     * * **Cancelling the start-up drop.** The drop of
     *   [AudioDepacketizerConfig.initialResyncDropMs] exists to discard a host-side backlog. A host
     *   whose socket goes quiet has no backlog, so continuing to drop would throw away live audio.
     */
    fun onIdle(): AudioSubmitResult {
        if (sawData && resyncDropRemaining > 0) {
            ProtocolLog.i(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "the audio socket went quiet with $resyncDropRemaining resync packets left to " +
                    "drop; the host has no backlog, so playback starts now",
            )
            resyncDropRemaining = 0
        }
        val samples = ArrayList<OpusSample>(RtpAudioConstants.FEC_DATA_SHARDS)
        val events = ArrayList<AudioStreamEvent>(1)
        drain(samples, events)
        return resultOf(samples, events)
    }

    /** Forgets everything. Used when a stream restarts within one session. */
    fun reset() {
        blocks.clear()
        synchronizing = true
        nextSequenceNumber = 0
        oldestBaseSequenceNumber = 0
        resyncDropRemaining = config.initialResyncDropMs / config.packetDurationMs
        sawData = false
        sawOutOfOrder = false
        expectedToc = -1
        lastTimestamp = 0
    }

    // ---- Data packets ---------------------------------------------------------------------------

    private fun submitData(packet: AudioRtpPacket.Data, datagram: ByteArray): AudioSubmitResult {
        packetsReceived++
        sawData = true

        // The start-up drop happens before anything else touches the sequence state, so the stream
        // synchronises on the first packet we intend to keep rather than on one we are discarding.
        if (resyncDropRemaining > 0) {
            resyncDropRemaining--
            resyncDropped++
            return AudioSubmitResult.EMPTY
        }

        val sequence = packet.header.sequenceNumber
        val base = blockBaseOf(sequence)

        if (synchronizing) {
            // Start on the next block boundary. Starting mid-block would make the first block look
            // like it lost every packet before the one we joined on, and report a loss burst on
            // every single connection.
            synchronizing = false
            nextSequenceNumber = SequenceNumbers.advance(base, RtpAudioConstants.FEC_DATA_SHARDS)
            oldestBaseSequenceNumber = nextSequenceNumber
            checkTimestampStep(packet, datagram)
            return AudioSubmitResult.EMPTY
        }

        if (SequenceNumbers.difference(base, oldestBaseSequenceNumber) < 0) {
            // Its block has already been released; the packet is of no use, however genuine. It is
            // still evidence: this host or this link genuinely reorders, so from now on a stalled
            // block is given the full out-of-order grace period rather than abandoned as soon as a
            // later block appears. See [releaseStalled].
            late++
            sawOutOfOrder = true
            return AudioSubmitResult.EMPTY
        }

        val block = blockFor(base)
        val index = SequenceNumbers.difference(sequence, base)
        if (index < 0 || index >= RtpAudioConstants.FEC_DATA_SHARDS) {
            // Only reachable if blockBaseOf and the block list disagreed, which they cannot.
            packetsRejected++
            return AudioSubmitResult.EMPTY
        }
        if (block.shards[index] != null) {
            duplicates++
            return AudioSubmitResult.EMPTY
        }

        block.shards[index] = datagram.copyOfRange(
            packet.payloadOffset,
            packet.payloadOffset + packet.payloadLength,
        )
        block.timestamps[index] = packet.header.timestamp
        block.dataReceived++
        if (!block.haveBaseTimestamp) {
            // Spec §8.4: baseTimestamp = timestamp - ((sequence - base) * packetDurationMs).
            block.baseTimestamp = packet.header.timestamp -
                index * RtpAudioConstants.timestampStepFor(config.packetDurationMs)
            block.haveBaseTimestamp = true
        }

        val samples = ArrayList<OpusSample>(RtpAudioConstants.FEC_DATA_SHARDS)
        val events = ArrayList<AudioStreamEvent>(1)
        checkToc(block.shards[index]!!, events)
        drain(samples, events)
        return resultOf(samples, events)
    }

    // ---- Parity packets -------------------------------------------------------------------------

    private fun submitParity(packet: AudioRtpPacket.Parity, datagram: ByteArray): AudioSubmitResult {
        parityReceived++

        if (!config.fecRecoveryEnabled) {
            // Spec §8.4's v1 approach. Counted so the stats can say how much recovery was on offer.
            parityIgnored++
            return AudioSubmitResult.EMPTY
        }

        ProtocolLog.unverified(
            RtpAudioConstants.LOG_TAG_AUDIO,
            "audio-fec-recovery",
            "Reed-Solomon recovery is enabled for audio; spec 01 §8.4 defers to §7.7's " +
                "UNVERIFIED generator matrix, and a mismatch corrupts recovered shards silently",
        )

        val base = packet.fec.baseSequenceNumber
        if (synchronizing || SequenceNumbers.difference(base, oldestBaseSequenceNumber) < 0) {
            return AudioSubmitResult.EMPTY
        }
        val block = blockFor(base)
        val index = packet.fec.shardIndex
        if (block.parity[index] == null) {
            block.parity[index] = datagram.copyOfRange(
                packet.payloadOffset,
                packet.payloadOffset + packet.payloadLength,
            )
            block.parityReceived++
            if (!block.haveBaseTimestamp) {
                block.baseTimestamp = packet.fec.baseTimestamp
                block.haveBaseTimestamp = true
            }
        }
        // No recovery attempt: see the class comment. The parity shard's only job while
        // FEC_RECOVERY_ENABLED_BY_DEFAULT is false is to be counted.
        val samples = ArrayList<OpusSample>(RtpAudioConstants.FEC_DATA_SHARDS)
        val events = ArrayList<AudioStreamEvent>(1)
        drain(samples, events)
        return resultOf(samples, events)
    }

    // ---- Ordering -------------------------------------------------------------------------------

    /**
     * Releases everything that is ready, then decides whether anything stalled has waited long
     * enough (spec §8.4).
     */
    private fun drain(samples: MutableList<OpusSample>, events: MutableList<AudioStreamEvent>) {
        var progressed = true
        while (progressed) {
            progressed = releaseReady(samples, events)
            if (!progressed) {
                progressed = releaseStalled(events)
            }
        }
    }

    /** Emits every sample that is available in order. Returns true if anything moved. */
    private fun releaseReady(
        samples: MutableList<OpusSample>,
        events: MutableList<AudioStreamEvent>,
    ): Boolean {
        var moved = false
        while (true) {
            val head = blocks.firstOrNull() ?: return moved

            if (SequenceNumbers.difference(nextSequenceNumber, head.baseSequenceNumber) < 0) {
                // Everything between here and the oldest block we hold is gone for good.
                val missing = SequenceNumbers.difference(
                    head.baseSequenceNumber,
                    nextSequenceNumber,
                )
                concealGap(missing, samples, events)
                nextSequenceNumber = head.baseSequenceNumber
                oldestBaseSequenceNumber = head.baseSequenceNumber
                moved = true
                continue
            }

            val index = SequenceNumbers.difference(nextSequenceNumber, head.baseSequenceNumber)
            if (index >= RtpAudioConstants.FEC_DATA_SHARDS) {
                // The head block is entirely behind us; retire it.
                retire(head)
                moved = true
                continue
            }

            val shard = head.shards[index]
            if (shard == null) {
                if (!head.released) return moved
                // Released with holes: fill this one and move on.
                emitConcealment(head, index, samples)
                packetsLost++
                advance(head)
                moved = true
                continue
            }

            emit(
                OpusSample(
                    data = shard,
                    sequenceNumber = nextSequenceNumber,
                    timestamp = head.timestamps[index],
                ),
                samples,
            )
            advance(head)
            moved = true
        }
    }

    /**
     * Decides whether the head block has waited long enough, and releases it if so (spec §8.4).
     *
     * Three ways a block is given up on, in order of how much latency each costs:
     *
     * 1. **A later block has arrived and this link does not reorder.** A host sends in order, so a
     *    packet from a later block is proof that the head's missing packets are already overdue.
     *    Waiting further would add latency for nothing, so the head goes immediately. This is the
     *    common case and the reason a single lost packet costs 5 ms rather than 30.
     * 2. **The deadline passed.** Once [sawOutOfOrder] says this link really does deliver packets
     *    out of order, rule 1 is unsafe — a "later" block may simply have overtaken an earlier
     *    one — so the head is held until four packet durations after its first packet plus the
     *    out-of-order grace of spec §8.4. At the default 5 ms packet duration that is 30 ms. The
     *    deadline is also what drains a stream that has stopped entirely, which is why it applies
     *    even when no later block exists.
     * 3. **The queue is over its bound.** At that point the stream has demonstrably moved on
     *    without us and the clock is not worth consulting.
     *
     * @return true when a block was released, meaning [releaseReady] has more to do.
     */
    private fun releaseStalled(events: MutableList<AudioStreamEvent>): Boolean {
        val head = blocks.firstOrNull() ?: return false
        if (head.released || head.isComplete) return false

        val overflowing = blocks.size >= config.maxTrackedBlocks
        val overtaken = blocks.size > 1 && !sawOutOfOrder
        if (!overflowing && !overtaken) {
            val dueNanos = head.queuedAtNanos +
                (RtpAudioConstants.FEC_DATA_SHARDS.toLong() * config.packetDurationMs +
                    config.outOfOrderWaitMs) * NANOS_PER_MS
            if (clock() < dueNanos) return false
        }

        head.released = true
        blocksIncomplete++
        events += AudioStreamEvent.BlockIncomplete(
            baseSequenceNumber = head.baseSequenceNumber,
            dataShardsReceived = head.dataReceived,
            parityShardsReceived = head.parityReceived,
        )
        return true
    }

    /** Records one sample as delivered and keeps the concealment timeline with it. */
    private fun emit(sample: OpusSample, samples: MutableList<OpusSample>) {
        samples += sample
        samplesDelivered++
        if (sample.concealment) samplesConcealed++
        lastTimestamp = sample.timestamp
    }

    /** Moves past the slot just emitted, retiring the block when its last slot is consumed. */
    private fun advance(head: Block) {
        nextSequenceNumber = SequenceNumbers.advance(nextSequenceNumber, 1)
        val consumed = SequenceNumbers.difference(nextSequenceNumber, head.baseSequenceNumber)
        if (consumed >= RtpAudioConstants.FEC_DATA_SHARDS) retire(head)
    }

    private fun retire(head: Block) {
        blocks.removeFirst()
        oldestBaseSequenceNumber = SequenceNumbers.advance(
            head.baseSequenceNumber,
            RtpAudioConstants.FEC_DATA_SHARDS,
        )
    }

    /**
     * Handles a run of slots for which no block exists at all.
     *
     * Small gaps are concealed, which is what spec §8.5 asks for and what keeps the decoder's
     * timeline aligned across the single losses a normal link produces. Large ones are skipped and
     * reported: see [RtpAudioConstants.MAX_CONCEALED_PACKETS_PER_GAP].
     */
    private fun concealGap(
        missing: Int,
        samples: MutableList<OpusSample>,
        events: MutableList<AudioStreamEvent>,
    ) {
        if (missing <= 0) return
        val first = nextSequenceNumber
        val step = RtpAudioConstants.timestampStepFor(config.packetDurationMs)
        val conceal = if (missing <= config.maxConcealedPacketsPerGap) missing else 0
        for (offset in 0 until conceal) {
            emit(
                OpusSample.concealment(
                    sequenceNumber = SequenceNumbers.advance(first, offset),
                    timestamp = lastTimestamp + step,
                ),
                samples,
            )
        }
        packetsLost += missing.toLong()
        events += AudioStreamEvent.PacketsLost(missing, first, conceal)
        if (conceal == 0) events += AudioStreamEvent.Resynchronised(missing)
    }

    /** Fills one hole inside a block that was released without every shard. */
    private fun emitConcealment(head: Block, index: Int, samples: MutableList<OpusSample>) {
        val step = RtpAudioConstants.timestampStepFor(config.packetDurationMs)
        val timestamp = if (head.haveBaseTimestamp) {
            head.baseTimestamp + index * step
        } else {
            lastTimestamp + step
        }
        emit(OpusSample.concealment(nextSequenceNumber, timestamp), samples)
    }

    // ---- Bookkeeping ----------------------------------------------------------------------------

    /** Rounds a sequence number down to its FEC block base (spec §8.4). */
    private fun blockBaseOf(sequence: Int): Int =
        SequenceNumbers.normalize(sequence) and RtpAudioConstants.FEC_BLOCK_BASE_MASK

    /**
     * The block for [base], creating and inserting it in sequence order if it is new.
     *
     * Insertion is a linear walk from the back because the list holds at most
     * [AudioDepacketizerConfig.maxTrackedBlocks] entries and the new block almost always belongs at
     * the end; a sorted structure would cost more than it saved.
     */
    private fun blockFor(base: Int): Block {
        for (block in blocks) {
            if (block.baseSequenceNumber == base) return block
        }
        val created = Block(base, clock())
        var inserted = false
        for (index in blocks.indices.reversed()) {
            if (SequenceNumbers.difference(base, blocks[index].baseSequenceNumber) > 0) {
                blocks.add(index + 1, created)
                inserted = true
                break
            }
        }
        if (!inserted) blocks.addFirst(created)
        return created
    }

    private fun resultOf(
        samples: List<OpusSample>,
        events: List<AudioStreamEvent>,
    ): AudioSubmitResult {
        if (samples.isEmpty() && events.isEmpty()) return AudioSubmitResult.EMPTY
        return AudioSubmitResult(samples, events)
    }

    /**
     * Watches the TOC byte for the change spec §8.5 says must not happen.
     *
     * Reported once per change rather than once per packet: a Sunshine host that legitimately
     * varies it would otherwise emit two hundred events a second.
     */
    private fun checkToc(payload: ByteArray, events: MutableList<AudioStreamEvent>) {
        if (payload.isEmpty()) return
        val toc = payload[0].toInt() and 0xFF
        if (expectedToc == -1) {
            expectedToc = toc
            return
        }
        if (toc != expectedToc) {
            events += AudioStreamEvent.TocChanged(expectedToc, toc)
            expectedToc = toc
        }
    }

    /**
     * Compares the negotiated packet duration against what the stream's own TOC byte says, once.
     *
     * A mismatch is not fatal — the decoder decodes whatever arrives — but it means every
     * concealment packet is the wrong length and every synthesised timestamp is on the wrong
     * timeline, which shows up as slow drift rather than as an error. Worth one line.
     */
    private fun checkTimestampStep(packet: AudioRtpPacket.Data, datagram: ByteArray) {
        if (announcedTimestampStep) return
        announcedTimestampStep = true
        val observed = OpusToc.durationMicrosOf(
            datagram,
            packet.payloadOffset,
            packet.payloadLength,
        ) ?: return
        val negotiated = config.packetDurationMs * 1_000
        if (observed != negotiated) {
            ProtocolLog.w(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "the stream's Opus packets are ${observed}µs long but " +
                    "x-nv-aqos.packetDuration negotiated ${negotiated}µs (spec §8.5); " +
                    "concealment and timestamps will follow the negotiated value",
            )
        } else {
            ProtocolLog.i(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "audio stream synchronised: ${observed}µs Opus packets, " +
                    "${config.packetDurationMs}ms negotiated packet duration",
            )
        }
    }

    private companion object {
        const val NANOS_PER_MS: Long = 1_000_000L
    }
}
