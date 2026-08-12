package com.voidlink.android.protocol.rtp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Telling reordering apart from loss (`docs/01-PROTOCOL.md` §7.7, §11.2).
 *
 * The distinction is the whole reason this class exists. Reporting reordering as loss produces the
 * IDR storm spec §9.5 warns about on a link that is actually healthy; failing to report real loss
 * leaves the decoder waiting for shards that are never coming.
 */
class SequenceTrackerTest {

    private val tracker = SequenceTracker()

    private fun feed(sequences: IntArray): List<SequencePacketStatus> =
        sequences.map { tracker.receive(it) }

    @Test
    fun `an in-order run is entirely fresh and loses nothing`() {
        val statuses = feed(IntArray(10) { it + 1 })

        assertEquals(List(10) { SequencePacketStatus.FRESH }, statuses)
        assertEquals(0L, tracker.totalLost)
        assertEquals(10, tracker.highestSequenceNumber)
        assertEquals(11, tracker.nextContiguousSequenceNumber)
    }

    @Test
    fun `a swapped pair is not loss`() {
        feed(intArrayOf(1, 3, 2))

        assertEquals(0L, tracker.totalLost)
        assertEquals(0, tracker.takePendingLostCount())
        // Once the hole fills, the contiguous mark moves past both.
        assertEquals(4, tracker.nextContiguousSequenceNumber)
    }

    @Test
    fun `a badly reordered burst inside the tolerance is not loss`() {
        // Sixty-four packets delivered completely backwards is far beyond anything a real network
        // does, and it still must not be reported as loss.
        tracker.receive(1)
        for (sequence in 65 downTo 2) tracker.receive(sequence)

        assertEquals(0L, tracker.totalLost)
        assertEquals(0L, tracker.totalLate)
        assertEquals(66, tracker.nextContiguousSequenceNumber)
    }

    @Test
    fun `a hole is declared lost only once it falls outside the reorder tolerance`() {
        assertEquals(64, tracker.reorderTolerance)
        tracker.receive(1)

        // 3..66: the hole at 2 is exactly `reorderTolerance` behind the highest, so it is still
        // considered reorderable.
        for (sequence in 3..66) tracker.receive(sequence)
        assertEquals(0L, tracker.totalLost)
        assertEquals(2, tracker.nextContiguousSequenceNumber)

        // One more packet pushes it past the tolerance and it is given up on.
        tracker.receive(67)
        assertEquals(1L, tracker.totalLost)
        assertEquals(1, tracker.takePendingLostCount())
        assertEquals(0, tracker.takePendingLostCount())
        assertEquals(68, tracker.nextContiguousSequenceNumber)
    }

    @Test
    fun `a packet that arrives after its hole was closed is late, not lost twice`() {
        tracker.receive(1)
        for (sequence in 3..67) tracker.receive(sequence)
        assertEquals(1L, tracker.totalLost)

        assertEquals(SequencePacketStatus.LATE, tracker.receive(2))
        assertEquals(1L, tracker.totalLost)
        assertEquals(1L, tracker.totalLate)
    }

    @Test
    fun `a duplicate still inside the window is reported as a duplicate`() {
        tracker.receive(1)
        assertEquals(SequencePacketStatus.FRESH, tracker.receive(3))
        assertEquals(SequencePacketStatus.DUPLICATE, tracker.receive(3))

        assertEquals(1L, tracker.totalDuplicates)
        assertEquals(0L, tracker.totalLost)
    }

    @Test
    fun `a duplicate of an already-consumed packet is reported as late`() {
        feed(intArrayOf(1, 2))
        // 1 and 2 are contiguous and already consumed, so a repeat is behind the window entirely.
        assertEquals(SequencePacketStatus.LATE, tracker.receive(2))
        assertEquals(1L, tracker.totalLate)
        assertEquals(0L, tracker.totalLost)
    }

    @Test
    fun `the wrap from 65535 to 0 is not a discontinuity`() {
        feed(intArrayOf(65534, 65535, 0, 1))

        assertEquals(0L, tracker.totalLost)
        assertEquals(1, tracker.highestSequenceNumber)
        assertEquals(2, tracker.nextContiguousSequenceNumber)
    }

    @Test
    fun `reordering across the wrap point is still not loss`() {
        feed(intArrayOf(65534, 0, 65535, 1))

        assertEquals(0L, tracker.totalLost)
        assertEquals(2, tracker.nextContiguousSequenceNumber)
    }

    @Test
    fun `a jump beyond the window is treated as a discontinuity and counted`() {
        tracker.receive(1)
        assertEquals(SequencePacketStatus.FRESH, tracker.receive(1000))

        // Everything from 2 to 999 is gone; nothing that far behind could still be reordering.
        assertEquals(998L, tracker.totalLost)
        assertEquals(998, tracker.takePendingLostCount())
        assertEquals(1000, tracker.highestSequenceNumber)
        assertEquals(1001, tracker.nextContiguousSequenceNumber)
    }

    @Test
    fun `reset forgets everything`() {
        feed(intArrayOf(1, 5, 200))
        tracker.reset()

        assertEquals(0L, tracker.totalLost)
        assertEquals(0L, tracker.totalLate)
        assertEquals(0L, tracker.totalDuplicates)
        assertEquals(SequencePacketStatus.FRESH, tracker.receive(9000))
        assertEquals(9000, tracker.highestSequenceNumber)
    }

    @Test
    fun `a long lossless stream across several wraps stays exact`() {
        var sequence = 60000
        repeat(20000) {
            assertEquals(SequencePacketStatus.FRESH, tracker.receive(sequence))
            sequence = SequenceNumbers.advance(sequence, 1)
        }
        assertEquals(0L, tracker.totalLost)
        assertEquals(sequence, tracker.nextContiguousSequenceNumber)
    }
}
