package com.voidlink.android.protocol.rtp

/** What a sequence number meant when it arrived. */
enum class SequencePacketStatus {

    /** Not seen before and still inside the reordering window. */
    FRESH,

    /** Seen before — a duplicated datagram, which UDP networks produce routinely. */
    DUPLICATE,

    /** Arrived after its slot was already declared lost or consumed; too late to be useful. */
    LATE,
}

/**
 * Tells packet **reordering** apart from packet **loss** on the video socket (spec §7.7, §11.2).
 *
 * A naive "is this sequence number the one I expected?" check reports loss every time two
 * datagrams swap places, and Wi-Fi swaps datagrams constantly. Reporting that as loss means an IDR
 * storm on a link that is actually fine — precisely what spec §9.5 warns makes things worse.
 *
 * So this keeps a sliding window of received sequence numbers. A hole is only declared lost once
 * the highest received sequence number has moved [reorderTolerance] packets past it, by which
 * point a reordered packet would have had to be absurdly late. Everything else — duplicates, late
 * arrivals, wraparound at 65 535 — is classified rather than counted as loss.
 *
 * The tracker also maintains the two figures spec §9.5's Sunshine FEC-status message needs:
 * [highestSequenceNumber] and [nextContiguousSequenceNumber].
 *
 * **Not thread-safe.** One instance belongs to one `video-rx` thread (architecture §3).
 *
 * @param reorderTolerance how far behind the highest sequence number a hole may sit before it
 *   counts as lost.
 * @param windowSize width of the received-packet window; must be a power of two and comfortably
 *   larger than [reorderTolerance].
 */
class SequenceTracker(
    val reorderTolerance: Int = RtpVideoConstants.SEQUENCE_REORDER_TOLERANCE,
    val windowSize: Int = RtpVideoConstants.SEQUENCE_WINDOW_SIZE,
) {

    init {
        require(windowSize > 0 && (windowSize and (windowSize - 1)) == 0) {
            "windowSize must be a power of two, was $windowSize"
        }
        require(reorderTolerance in 0 until windowSize) {
            "reorderTolerance $reorderTolerance must be less than windowSize $windowSize"
        }
        require(windowSize <= RtpVideoConstants.SEQUENCE_NUMBER_MODULUS / 2) {
            "windowSize $windowSize must not exceed half the sequence space"
        }
    }

    private val received = BooleanArray(windowSize)
    private val indexMask = windowSize - 1
    private var started = false

    /** Highest sequence number seen so far, in modular order. */
    var highestSequenceNumber: Int = 0
        private set

    /** Lowest sequence number not yet accounted for — received, or given up on. */
    var nextContiguousSequenceNumber: Int = 0
        private set

    /** Total packets given up on since the last [reset]. */
    var totalLost: Long = 0L
        private set

    /** Total duplicated packets seen since the last [reset]. */
    var totalDuplicates: Long = 0L
        private set

    /** Total packets that arrived after their slot was closed. */
    var totalLate: Long = 0L
        private set

    private var pendingLost: Int = 0

    /**
     * Records a received sequence number.
     *
     * @return how the packet should be treated. Only [SequencePacketStatus.FRESH] packets should
     *   be fed into frame assembly; the others are already accounted for.
     */
    fun receive(sequenceNumber: Int): SequencePacketStatus {
        val sequence = SequenceNumbers.normalize(sequenceNumber)

        if (!started) {
            started = true
            nextContiguousSequenceNumber = sequence
            highestSequenceNumber = sequence
            mark(sequence)
            advance()
            return SequencePacketStatus.FRESH
        }

        val offset = SequenceNumbers.difference(sequence, nextContiguousSequenceNumber)
        if (offset < 0) {
            totalLate++
            return SequencePacketStatus.LATE
        }
        if (offset >= windowSize) {
            // Further ahead than the window can represent. This is a discontinuity — a restarted
            // stream or a loss burst far beyond anything FEC could repair — not reordering, so the
            // window is abandoned rather than slid.
            declareDiscontinuity(sequence, offset)
            return SequencePacketStatus.FRESH
        }
        if (isMarked(sequence)) {
            totalDuplicates++
            return SequencePacketStatus.DUPLICATE
        }

        mark(sequence)
        if (SequenceNumbers.isAfter(sequence, highestSequenceNumber)) {
            highestSequenceNumber = sequence
        }
        advance()
        return SequencePacketStatus.FRESH
    }

    /**
     * Returns the number of packets declared lost since the last call, and clears the count.
     *
     * The caller reports these upward; [totalLost] keeps the running total for the connection
     * quality figure of spec §11.2.
     */
    fun takePendingLostCount(): Int {
        val lost = pendingLost
        pendingLost = 0
        return lost
    }

    /** Forgets everything. Used when a stream restarts within one session. */
    fun reset() {
        received.fill(false)
        started = false
        highestSequenceNumber = 0
        nextContiguousSequenceNumber = 0
        totalLost = 0L
        totalDuplicates = 0L
        totalLate = 0L
        pendingLost = 0
    }

    private fun declareDiscontinuity(sequence: Int, skipped: Int) {
        received.fill(false)
        pendingLost += skipped
        totalLost += skipped.toLong()
        nextContiguousSequenceNumber = sequence
        highestSequenceNumber = sequence
        mark(sequence)
        advance()
    }

    /**
     * Moves [nextContiguousSequenceNumber] forward over everything that is settled.
     *
     * A slot is settled when it has been received, or when the highest sequence number has moved
     * more than [reorderTolerance] past it — at which point waiting longer only adds latency.
     */
    private fun advance() {
        while (true) {
            val slot = nextContiguousSequenceNumber
            if (isMarked(slot)) {
                clear(slot)
            } else if (
                SequenceNumbers.difference(highestSequenceNumber, slot) > reorderTolerance
            ) {
                pendingLost++
                totalLost++
            } else {
                return
            }
            nextContiguousSequenceNumber = SequenceNumbers.advance(slot, 1)
        }
    }

    private fun mark(sequence: Int) {
        received[sequence and indexMask] = true
    }

    private fun clear(sequence: Int) {
        received[sequence and indexMask] = false
    }

    private fun isMarked(sequence: Int): Boolean = received[sequence and indexMask]
}
