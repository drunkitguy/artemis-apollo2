package com.voidlink.android.protocol.audio

/**
 * One Opus packet, ready to decode (spec §8.5).
 *
 * The payload after the RTP header is a raw Opus packet — one frame of `packetDuration` ms — and
 * this is that, copied out of the receive buffer so the buffer can be reused immediately. The copy
 * is deliberate: audio packets are a few hundred bytes at two hundred per second, and a pool would
 * buy nothing measurable while adding a lifetime rule to every path that touches a sample.
 *
 * @property data the Opus packet. **Empty for a concealment sample** — see [concealment].
 * @property sequenceNumber the RTP sequence number this occupies, real or synthesised.
 * @property timestamp the RTP timestamp, in the millisecond units of spec §8.4.
 * @property concealment true when nothing arrived for this slot and the decoder should synthesise
 *   `packetDuration` ms of concealment rather than decode [data]. Spec §8.5: "Prefer submitting
 *   silence of exactly `packetDuration` ms to keep the timeline aligned."
 */
class OpusSample(
    val data: ByteArray,
    val sequenceNumber: Int,
    val timestamp: Int,
    val concealment: Boolean = false,
) {

    /** Bytes of Opus payload. Zero for a concealment sample. */
    val length: Int get() = data.size

    /** The TOC byte, or `null` for a concealment sample (spec §8.5). */
    val tocByte: Int? get() = if (data.isEmpty()) null else data[0].toInt() and 0xFF

    override fun toString(): String =
        "OpusSample(seq=$sequenceNumber, ts=$timestamp, ${length}B" +
            (if (concealment) ", concealment)" else ")")

    companion object {

        /** A synthesised sample for a slot nothing arrived for. */
        fun concealment(sequenceNumber: Int, timestamp: Int): OpusSample =
            OpusSample(EMPTY, sequenceNumber, timestamp, concealment = true)

        private val EMPTY = ByteArray(0)
    }
}

/** Something the audio receive path wants the session and the stats overlay to know about. */
sealed interface AudioStreamEvent {

    /**
     * Audio packets were given up on.
     *
     * Nothing acts on this the way [com.voidlink.android.protocol.rtp.VideoStreamEvent] drives IDR
     * requests — there is no audio equivalent of a keyframe to ask for, and asking the host for
     * anything on account of audio loss would only add traffic to a link that is already losing
     * packets. It exists to be counted and logged.
     *
     * @property count how many sequence slots were abandoned.
     * @property firstSequenceNumber the first of them.
     * @property concealed how many of them were filled with concealment rather than skipped.
     */
    class PacketsLost(
        val count: Int,
        val firstSequenceNumber: Int,
        val concealed: Int,
    ) : AudioStreamEvent

    /**
     * An FEC block was released without all four data shards (spec §8.4).
     *
     * @property baseSequenceNumber the block's base.
     * @property dataShardsReceived how many of the four arrived.
     * @property parityShardsReceived how many parity shards arrived — the figure that says whether
     *   enabling RS recovery would have rescued this block.
     */
    class BlockIncomplete(
        val baseSequenceNumber: Int,
        val dataShardsReceived: Int,
        val parityShardsReceived: Int,
    ) : AudioStreamEvent

    /**
     * The Opus TOC byte changed mid-stream (spec §8.5).
     *
     * "must stay constant for the whole stream. Log a warning if it changes (Sunshine legitimately
     * may vary it; GFE must not)." Emitted once per change, not once per packet.
     */
    class TocChanged(val previous: Int, val current: Int) : AudioStreamEvent

    /**
     * The stream resynchronised after a gap too large to conceal.
     *
     * @property skipped how many packet slots were jumped over.
     */
    class Resynchronised(val skipped: Int) : AudioStreamEvent
}

/**
 * Counters for the audio receive path (spec §11.2).
 *
 * Lifetime totals for the session, in the spirit of
 * [com.voidlink.android.protocol.rtp.FrameAssemblerStats]: the session tells "the host is not
 * sending audio" apart from "audio arrives and we cannot use it" from these, and neither diagnosis
 * is available from a log line.
 *
 * @property packetsReceived data packets that parsed.
 * @property packetsRejected datagrams that did not parse at all.
 * @property parityReceived parity packets that parsed.
 * @property parityIgnored parity packets discarded because RS recovery is off (spec §8.4).
 * @property duplicates data packets whose slot was already filled.
 * @property late data packets belonging to a block already released.
 * @property resyncDropped packets discarded by the start-up resync drop.
 * @property samplesDelivered samples handed downstream, concealment included.
 * @property samplesConcealed how many of those were concealment.
 * @property packetsLost sequence slots given up on, concealed or not.
 * @property blocksIncomplete FEC blocks released with fewer than four data shards.
 */
data class AudioStreamStats(
    val packetsReceived: Long = 0L,
    val packetsRejected: Long = 0L,
    val parityReceived: Long = 0L,
    val parityIgnored: Long = 0L,
    val duplicates: Long = 0L,
    val late: Long = 0L,
    val resyncDropped: Long = 0L,
    val samplesDelivered: Long = 0L,
    val samplesConcealed: Long = 0L,
    val packetsLost: Long = 0L,
    val blocksIncomplete: Long = 0L,
) {
    /** True once anything at all has arrived on the socket — parseable or not. */
    val sawTraffic: Boolean get() = packetsReceived + packetsRejected + parityReceived > 0L

    companion object {
        /** The all-zero snapshot, used before anything has arrived. */
        val EMPTY: AudioStreamStats = AudioStreamStats()
    }
}
