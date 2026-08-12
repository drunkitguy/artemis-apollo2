package com.voidlink.android.protocol.enet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering, duplicate suppression and fragment reassembly on one channel
 * (`docs/01-PROTOCOL.md` §9.1).
 *
 * Everything here is a pure function of the sequence numbers handed in, so the awkward cases —
 * reordering, duplication, a wrap from 0xFFFF to 0x0000 — are ordinary test inputs rather than
 * something to hope the network produces.
 */
class EnetChannelTest {

    private val channel = EnetChannel(EnetUnverifiedConstants.CHANNEL_GENERIC)
    private val delivered = ArrayList<ByteArray>()

    // ---- Reliable ordering ---------------------------------------------------------------------

    @Test
    fun `in-order reliable packets are delivered immediately`() {
        assertTrue(channel.receiveReliable(1, payload(1), delivered))
        assertTrue(channel.receiveReliable(2, payload(2), delivered))
        assertTrue(channel.receiveReliable(3, payload(3), delivered))

        assertEquals(listOf(1, 2, 3), delivered.map { it[0].toInt() })
        assertEquals(3, channel.incomingReliableSequenceNumber)
        assertEquals(0, channel.pendingCount)
    }

    @Test
    fun `a reordered packet waits for the gap to fill and then order is restored`() {
        assertTrue(channel.receiveReliable(3, payload(3), delivered))
        assertTrue(channel.receiveReliable(2, payload(2), delivered))
        assertTrue(delivered.isEmpty())
        assertEquals(2, channel.pendingCount)

        assertTrue(channel.receiveReliable(1, payload(1), delivered))

        // All three released at once, in send order rather than arrival order.
        assertEquals(listOf(1, 2, 3), delivered.map { it[0].toInt() })
        assertEquals(3, channel.incomingReliableSequenceNumber)
        assertEquals(0, channel.pendingCount)
    }

    @Test
    fun `a duplicate of an already delivered packet is dropped`() {
        channel.receiveReliable(1, payload(1), delivered)
        delivered.clear()

        assertFalse(channel.receiveReliable(1, payload(1), delivered))
        assertTrue(delivered.isEmpty())
        assertEquals(1, channel.incomingReliableSequenceNumber)
    }

    @Test
    fun `a duplicate of a packet still waiting is dropped without disturbing the queue`() {
        assertTrue(channel.receiveReliable(2, payload(2), delivered))
        assertFalse(channel.receiveReliable(2, payload(0x22), delivered))
        assertEquals(1, channel.pendingCount)

        channel.receiveReliable(1, payload(1), delivered)
        // The first copy is the one that survives; the duplicate never replaced it.
        assertEquals(listOf(1, 2), delivered.map { it[0].toInt() })
    }

    @Test
    fun `a sequence number far ahead is refused rather than buffered`() {
        // Otherwise a single bogus datagram makes us hold a gigabyte for a gap nobody will fill.
        assertFalse(channel.receiveReliable(EnetProtocol.RELIABLE_WINDOW_SIZE + 2, payload(9), delivered))
        assertEquals(0, channel.pendingCount)
        assertTrue(channel.receiveReliable(EnetProtocol.RELIABLE_WINDOW_SIZE, payload(9), delivered))
        assertEquals(1, channel.pendingCount)
    }

    @Test
    fun `ordering survives the sixteen-bit wrap`() {
        // Drive the counter up to 0xFFFF, then cross zero.
        for (sequence in 1..0xFFFF) {
            channel.receiveReliable(sequence, payload(sequence and 0xFF), delivered)
        }
        delivered.clear()
        assertEquals(0xFFFF, channel.incomingReliableSequenceNumber)

        assertTrue(channel.receiveReliable(1, payload(0x11), delivered))
        assertTrue(delivered.isEmpty()) // 1 is not next; 0 is.
        assertTrue(channel.receiveReliable(0, payload(0x00), delivered))

        assertEquals(listOf(0x00, 0x11), delivered.map { it[0].toInt() })
        assertEquals(1, channel.incomingReliableSequenceNumber)
    }

    // ---- Fragmentation -------------------------------------------------------------------------

    @Test
    fun `fragments arriving in order reassemble into the original payload`() {
        val original = ByteArray(10) { (it + 1).toByte() }
        for (number in 0 until 4) {
            channel.receiveFragment(fragment(original, number, 4, start = 1), MAX_PACKET, delivered)
        }

        assertEquals(1, delivered.size)
        assertArrayEquals(original, delivered[0])
        // Four fragments consume four sequence numbers: 1, 2, 3, 4.
        assertEquals(4, channel.incomingReliableSequenceNumber)
    }

    @Test
    fun `fragments arriving out of order and duplicated still reassemble exactly once`() {
        val original = ByteArray(10) { (it + 1).toByte() }
        val arrival = listOf(3, 1, 1, 0, 2, 3)
        for (number in arrival) {
            channel.receiveFragment(fragment(original, number, 4, start = 1), MAX_PACKET, delivered)
        }

        assertEquals(1, delivered.size)
        assertArrayEquals(original, delivered[0])
    }

    @Test
    fun `a half-arrived fragmented packet blocks the packet queued behind it`() {
        val original = ByteArray(10) { (it + 1).toByte() }
        channel.receiveFragment(fragment(original, 0, 4, start = 1), MAX_PACKET, delivered)
        channel.receiveFragment(fragment(original, 1, 4, start = 1), MAX_PACKET, delivered)
        // Sequence numbers 1..4 belong to the fragmented packet, so the next whole packet is 5.
        assertTrue(channel.receiveReliable(5, payload(0x55), delivered))
        assertTrue(delivered.isEmpty())

        channel.receiveFragment(fragment(original, 2, 4, start = 1), MAX_PACKET, delivered)
        channel.receiveFragment(fragment(original, 3, 4, start = 1), MAX_PACKET, delivered)

        assertEquals(2, delivered.size)
        assertArrayEquals(original, delivered[0])
        assertEquals(0x55, delivered[1][0].toInt())
        assertEquals(5, channel.incomingReliableSequenceNumber)
    }

    @Test
    fun `a fragment claiming an impossible descriptor is refused`() {
        val base = fragment(ByteArray(10) { 1 }, 0, 4, start = 1)

        // fragmentNumber outside fragmentCount
        assertFalse(channel.receiveFragment(base.withFragmentNumber(4), MAX_PACKET, delivered))
        // totalLength beyond the configured ceiling
        assertFalse(channel.receiveFragment(base.withTotalLength(MAX_PACKET + 1), MAX_PACKET, delivered))
        // offset past the end of the packet
        assertFalse(channel.receiveFragment(base.withFragmentOffset(10), MAX_PACKET, delivered))
        assertEquals(0, channel.pendingCount)
    }

    @Test
    fun `a fragment payload that would overrun the reassembly buffer is refused`() {
        // totalLength 10, offset 8, but nine bytes of payload: three past the end.
        val overrun = EnetCommand.SendFragment(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_SEND_FRAGMENT or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = channel.id,
                reliableSequenceNumber = 2,
            ),
            startSequenceNumber = 1,
            fragmentCount = 2,
            fragmentNumber = 1,
            totalLength = 10,
            fragmentOffset = 8,
            payload = ByteArray(9),
        )
        assertFalse(channel.receiveFragment(overrun, MAX_PACKET, delivered))
        assertEquals(0, channel.pendingCount)
    }

    // ---- Unreliable ----------------------------------------------------------------------------

    @Test
    fun `unreliable packets are delivered while they move forward`() {
        assertNotNullPayload(channel.receiveUnreliable(0, 1, payload(1)))
        assertNotNullPayload(channel.receiveUnreliable(0, 2, payload(2)))
        assertNotNullPayload(channel.receiveUnreliable(0, 5, payload(5)))
    }

    @Test
    fun `an unreliable packet that arrives late is dropped, not delivered out of order`() {
        assertNotNullPayload(channel.receiveUnreliable(0, 5, payload(5)))
        assertNull(channel.receiveUnreliable(0, 4, payload(4)))
        assertNull(channel.receiveUnreliable(0, 5, payload(5)))
    }

    @Test
    fun `sending a reliable packet restarts the unreliable numbering on both sides`() {
        // The sender resets its unreliable counter whenever it sends a reliable packet, so the
        // receiver must do the same or the first unreliable packet of the new epoch looks stale.
        assertNotNullPayload(channel.receiveUnreliable(0, 7, payload(7)))
        channel.receiveReliable(1, payload(1), delivered)
        assertNotNullPayload(channel.receiveUnreliable(1, 1, payload(1)))
    }

    // ---- Outgoing counters ---------------------------------------------------------------------

    @Test
    fun `outgoing reliable numbering starts at one and resets the unreliable counter`() {
        assertEquals(1, channel.nextOutgoingReliableSequenceNumber())
        assertEquals(1, channel.nextOutgoingUnreliableSequenceNumber())
        assertEquals(2, channel.nextOutgoingUnreliableSequenceNumber())

        assertEquals(2, channel.nextOutgoingReliableSequenceNumber())
        assertEquals(0, channel.outgoingUnreliableSequenceNumber)
        assertEquals(1, channel.nextOutgoingUnreliableSequenceNumber())
    }

    @Test
    fun `reset drops everything held for reordering`() {
        channel.receiveReliable(4, payload(4), delivered)
        assertEquals(1, channel.pendingCount)
        channel.reset()
        assertEquals(0, channel.pendingCount)
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun payload(marker: Int): ByteArray = byteArrayOf(marker.toByte(), 0x7F)

    private fun assertNotNullPayload(actual: ByteArray?) {
        assertTrue("expected the packet to be delivered", actual != null)
    }

    /** Builds fragment [number] of [count] covering [original], all fragments the same size. */
    private fun fragment(
        original: ByteArray,
        number: Int,
        count: Int,
        start: Int,
    ): EnetCommand.SendFragment {
        val size = (original.size + count - 1) / count
        val offset = number * size
        val end = minOf(offset + size, original.size)
        return EnetCommand.SendFragment(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_SEND_FRAGMENT or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = channel.id,
                reliableSequenceNumber = start + number,
            ),
            startSequenceNumber = start,
            fragmentCount = count,
            fragmentNumber = number,
            totalLength = original.size,
            fragmentOffset = offset,
            payload = original.copyOfRange(offset, end),
        )
    }

    private fun EnetCommand.SendFragment.withFragmentNumber(value: Int) = copyWith(fragmentNumber = value)

    private fun EnetCommand.SendFragment.withTotalLength(value: Int) = copyWith(totalLength = value)

    private fun EnetCommand.SendFragment.withFragmentOffset(value: Int) = copyWith(fragmentOffset = value)

    private fun EnetCommand.SendFragment.copyWith(
        fragmentNumber: Int = this.fragmentNumber,
        totalLength: Int = this.totalLength,
        fragmentOffset: Int = this.fragmentOffset,
    ) = EnetCommand.SendFragment(
        header = header,
        startSequenceNumber = startSequenceNumber,
        fragmentCount = fragmentCount,
        fragmentNumber = fragmentNumber,
        totalLength = totalLength,
        fragmentOffset = fragmentOffset,
        payload = payload,
    )

    private companion object {
        const val MAX_PACKET = 1 shl 16
    }
}
