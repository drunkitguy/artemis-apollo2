package com.voidlink.android.protocol.enet

/**
 * One ENet channel: sequence counters, the reorder buffer, and fragment reassembly
 * (`docs/01-PROTOCOL.md` §9.1, "Reliable ordered delivery per channel, with sequence numbers and
 * ACKs").
 *
 * Channels are independent ordered streams over one connection, which is the whole point of spec
 * §9.1's split between `CTRL_CHANNEL_GENERIC` and `CTRL_CHANNEL_URGENT`: a periodic ping waiting
 * to be retransmitted must not hold up an IDR request.
 *
 * Every counter is 16-bit and wraps. No comparison in this class uses `<` on a sequence number;
 * they all go through [EnetProtocol.sequenceDistance], because "is 0x0001 newer than 0xFFFF" has
 * only one right answer and it is not the one `<` gives.
 *
 * Not thread-safe by design — `02-ARCHITECTURE.md` §3 makes the `enet-io` loop the only thread that
 * touches ENet state.
 *
 * @property id the channel id, 0 based.
 */
class EnetChannel(val id: Int) {

    /** Sequence number of the last reliable command we sent. Pre-incremented, so the first is 1. */
    var outgoingReliableSequenceNumber: Int = 0
        internal set

    /** Sequence number of the last unreliable command we sent within the current reliable epoch. */
    var outgoingUnreliableSequenceNumber: Int = 0
        internal set

    /** Sequence number of the last reliable command we delivered to the application. */
    var incomingReliableSequenceNumber: Int = 0
        private set

    /** Sequence number of the last unreliable command we delivered within the current epoch. */
    var incomingUnreliableSequenceNumber: Int = 0
        private set

    /** Reliable commands that arrived early, keyed by sequence number. */
    private val pending = HashMap<Int, PendingPacket>()

    /** Number of reliable commands held back waiting for a gap to fill. Diagnostics and tests. */
    val pendingCount: Int get() = pending.size

    /**
     * Takes the next outgoing reliable sequence number.
     *
     * Sending a reliable command also resets the unreliable counter: unreliable packets are
     * sequenced *within* a reliable epoch, so that one stranded behind a reliable command it should
     * have preceded is discarded rather than delivered out of order.
     */
    fun nextOutgoingReliableSequenceNumber(): Int {
        outgoingReliableSequenceNumber = EnetProtocol.nextSequenceNumber(outgoingReliableSequenceNumber)
        outgoingUnreliableSequenceNumber = 0
        return outgoingReliableSequenceNumber
    }

    /** Takes the next outgoing unreliable sequence number, leaving the reliable counter alone. */
    fun nextOutgoingUnreliableSequenceNumber(): Int {
        outgoingUnreliableSequenceNumber =
            EnetProtocol.nextSequenceNumber(outgoingUnreliableSequenceNumber)
        return outgoingUnreliableSequenceNumber
    }

    /**
     * Accepts a whole reliable payload.
     *
     * @param sequenceNumber the command's reliable sequence number.
     * @param payload the application bytes.
     * @param out receives every payload that became deliverable, in order. Usually zero or one, but
     *   a command that fills a gap releases everything queued behind it at once.
     * @return false when the command was a duplicate or outside the window and nothing was stored.
     *   The caller acknowledges it either way — a duplicate almost always means our previous
     *   acknowledgement was the thing that got lost.
     */
    fun receiveReliable(sequenceNumber: Int, payload: ByteArray, out: MutableList<ByteArray>): Boolean {
        if (!isWithinReceiveWindow(sequenceNumber)) return false
        if (pending.containsKey(sequenceNumber)) return false
        pending[sequenceNumber] = PendingPacket(payload, fragmentCount = 0, remaining = 0)
        drain(out)
        return true
    }

    /**
     * Accepts one fragment of a reliable payload.
     *
     * Fragments are reassembled into a buffer allocated on the first arrival, tracked by a
     * `received` flag per fragment so that a duplicate cannot decrement the outstanding count
     * twice. The assembled packet occupies [EnetCommand.SendFragment.startSequenceNumber] in the
     * ordered stream and consumes [EnetCommand.SendFragment.fragmentCount] sequence numbers, which
     * is what keeps a fragmented packet from reordering itself past a whole one sent after it.
     *
     * @param maximumPacketSize refuses a `totalLength` beyond this; a length field is the one thing
     *   in the datagram that decides how much we allocate.
     * @param out receives every payload that became deliverable, in order.
     * @return false when the fragment was a duplicate, malformed, or outside the window.
     */
    fun receiveFragment(
        command: EnetCommand.SendFragment,
        maximumPacketSize: Int,
        out: MutableList<ByteArray>,
    ): Boolean {
        val start = command.startSequenceNumber
        if (!isWithinReceiveWindow(start)) return false
        if (command.fragmentCount <= 0 || command.fragmentCount > EnetProtocol.MAXIMUM_FRAGMENT_COUNT) return false
        if (command.fragmentNumber < 0 || command.fragmentNumber >= command.fragmentCount) return false
        if (command.totalLength <= 0 || command.totalLength > maximumPacketSize) return false
        if (command.totalLength < command.fragmentCount) return false
        if (command.fragmentOffset < 0 || command.fragmentOffset >= command.totalLength) return false
        if (command.payload.size > command.totalLength - command.fragmentOffset) return false

        val existing = pending[start]
        val entry = if (existing != null) {
            // A whole packet already sitting at this sequence number is not a fragment holder; a
            // sender that produced both is broken and the fragment is the thing to drop.
            if (existing.fragmentCount != command.fragmentCount) return false
            existing
        } else {
            val fresh = PendingPacket(
                data = ByteArray(command.totalLength),
                fragmentCount = command.fragmentCount,
                remaining = command.fragmentCount,
            )
            fresh.received = BooleanArray(command.fragmentCount)
            pending[start] = fresh
            fresh
        }

        val received = entry.received ?: return false
        if (received[command.fragmentNumber]) return false
        received[command.fragmentNumber] = true
        entry.remaining--
        command.payload.copyInto(entry.data, command.fragmentOffset)
        drain(out)
        return true
    }

    /**
     * Accepts an unreliable sequenced payload.
     *
     * @param reliableSequenceNumber the reliable epoch the sender stamped on it.
     * @param unreliableSequenceNumber its position within that epoch.
     * @return the payload when it is newer than everything already delivered, or `null` when it is
     *   stale — which for unreliable traffic means "drop it", not "hold it".
     */
    fun receiveUnreliable(
        reliableSequenceNumber: Int,
        unreliableSequenceNumber: Int,
        payload: ByteArray,
    ): ByteArray? {
        val epochDistance = EnetProtocol.sequenceDistance(incomingReliableSequenceNumber, reliableSequenceNumber)
        // A distance in the top half of the space means the sender's epoch is behind ours.
        if (epochDistance > EnetProtocol.RELIABLE_WINDOW_SIZE) return null
        if (epochDistance == 0) {
            val distance =
                EnetProtocol.sequenceDistance(incomingUnreliableSequenceNumber, unreliableSequenceNumber)
            if (distance == 0 || distance > EnetProtocol.RELIABLE_WINDOW_SIZE) return null
            incomingUnreliableSequenceNumber = unreliableSequenceNumber
        } else {
            // The sender has moved on to a newer reliable epoch than anything we have delivered.
            incomingUnreliableSequenceNumber = unreliableSequenceNumber
        }
        return payload
    }

    /** Discards everything held for reordering or reassembly. Used when the connection ends. */
    fun reset() {
        pending.clear()
    }

    /**
     * True when [sequenceNumber] is ahead of what we have delivered and inside the receive window.
     *
     * Anything at or behind [incomingReliableSequenceNumber] is a duplicate of something already
     * delivered; anything more than [EnetProtocol.RELIABLE_WINDOW_SIZE] ahead would mean holding an
     * unbounded amount of memory for a gap that a sender obeying the same window can never create.
     */
    private fun isWithinReceiveWindow(sequenceNumber: Int): Boolean {
        val distance = EnetProtocol.sequenceDistance(incomingReliableSequenceNumber, sequenceNumber)
        return distance in 1..EnetProtocol.RELIABLE_WINDOW_SIZE
    }

    /** Releases every complete packet sitting at the head of the ordered stream. */
    private fun drain(out: MutableList<ByteArray>) {
        while (true) {
            val next = EnetProtocol.nextSequenceNumber(incomingReliableSequenceNumber)
            val entry = pending[next] ?: return
            if (entry.remaining > 0) return
            pending.remove(next)
            out.add(entry.data)
            // A fragmented packet occupies one sequence number per fragment.
            val consumed = if (entry.fragmentCount > 0) entry.fragmentCount else 1
            incomingReliableSequenceNumber = (next + consumed - 1) and EnetProtocol.SEQUENCE_MASK
            incomingUnreliableSequenceNumber = 0
        }
    }

    /**
     * A reliable payload waiting its turn, whole or under construction.
     *
     * @property fragmentCount 0 for a whole packet, otherwise the number of fragments it was split
     *   into — which is also how many sequence numbers it consumes on delivery.
     * @property remaining fragments still missing; 0 means deliverable.
     */
    private class PendingPacket(
        val data: ByteArray,
        val fragmentCount: Int,
        var remaining: Int,
    ) {
        /** Which fragments have arrived, or null for a whole packet. */
        var received: BooleanArray? = null
    }
}
