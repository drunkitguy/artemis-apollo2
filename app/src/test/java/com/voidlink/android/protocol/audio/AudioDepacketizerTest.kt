package com.voidlink.android.protocol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio ordering rules of `docs/01-PROTOCOL.md` §8.4 and the concealment rule of §8.5.
 *
 * Every test drives a virtual clock, because the one timing rule in this layer — hold an incomplete
 * FEC block for 10 ms past its due time — is the only thing standing between reordering and
 * latency, and testing it by sleeping would make the suite slow *and* flaky.
 */
class AudioDepacketizerTest {

    private var nanos = 0L

    private fun depacketizer(
        packetDurationMs: Int = 5,
        initialResyncDropMs: Int = 0,
        maxConcealedPacketsPerGap: Int = RtpAudioConstants.MAX_CONCEALED_PACKETS_PER_GAP,
        maxTrackedBlocks: Int = RtpAudioConstants.MAX_TRACKED_FEC_BLOCKS,
        fecRecoveryEnabled: Boolean = false,
        audioEncryptionNegotiated: Boolean = false,
    ) = AudioDepacketizer(
        AudioDepacketizerConfig(
            packetDurationMs = packetDurationMs,
            initialResyncDropMs = initialResyncDropMs,
            maxConcealedPacketsPerGap = maxConcealedPacketsPerGap,
            maxTrackedBlocks = maxTrackedBlocks,
            fecRecoveryEnabled = fecRecoveryEnabled,
            audioEncryptionNegotiated = audioEncryptionNegotiated,
        ),
    ) { nanos }

    private fun advanceMs(millis: Long) {
        nanos += millis * 1_000_000L
    }

    /** Feeds one data packet and returns what it released. */
    private fun AudioDepacketizer.data(
        sequence: Int,
        timestamp: Int = sequence * 5,
    ): AudioSubmitResult {
        val packet = AudioPacketFixtures.dataPacket(sequence, timestamp)
        return submit(packet, packet.size)
    }

    private fun AudioDepacketizer.parity(base: Int, shard: Int): AudioSubmitResult {
        val packet = AudioPacketFixtures.parityPacket(base, shard)
        return submit(packet, packet.size)
    }

    /** Consumes the synchronisation packet, leaving the queue expecting [start] + 4. */
    private fun AudioDepacketizer.synchronizeAt(start: Int) {
        val released = data(start)
        assertTrue("synchronisation must release nothing", released.samples.isEmpty())
    }

    private fun sequencesOf(result: AudioSubmitResult): List<Int> =
        result.samples.map { it.sequenceNumber }

    @Test
    fun `the first partial block is discarded so a session does not open with a false loss burst`() {
        val queue = depacketizer()

        // Joining mid-block would make packets 100..102 look lost on every single connection.
        assertTrue(queue.data(101).samples.isEmpty())
        // 100..103 all belong to a block we decided to skip; they are late, not lost.
        assertTrue(queue.data(102).samples.isEmpty())
        assertTrue(queue.data(103).samples.isEmpty())

        assertEquals(listOf(104), sequencesOf(queue.data(104)))
        assertEquals(2L, queue.stats().late)
        assertEquals(0L, queue.stats().packetsLost)
    }

    @Test
    fun `in-order packets are released immediately, with no block buffering at all`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)

        for (sequence in 104..111) {
            val released = queue.data(sequence)
            assertEquals(
                "packet $sequence should be released on arrival",
                listOf(sequence),
                sequencesOf(released),
            )
        }
        assertEquals(8L, queue.stats().samplesDelivered)
        assertEquals(0L, queue.stats().samplesConcealed)
    }

    @Test
    fun `a reordered packet inside a block is held and then released in order`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)

        assertEquals(listOf(104), sequencesOf(queue.data(104)))
        // 106 arrives before 105: nothing may be released, because releasing 106 first would play
        // the two 5 ms slices in the wrong order.
        assertTrue(queue.data(106).samples.isEmpty())
        // 105 arrives and unblocks both.
        assertEquals(listOf(105, 106), sequencesOf(queue.data(105)))
        assertEquals(0L, queue.stats().packetsLost)
    }

    @Test
    fun `a duplicate is counted and never played twice`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)
        queue.data(104)

        // 105 arrives twice, out of order relative to 106 so the second copy takes the block path.
        assertTrue(queue.data(106).samples.isEmpty())
        assertEquals(listOf(105, 106), sequencesOf(queue.data(105)))
        assertTrue(queue.data(105).samples.isEmpty())

        assertEquals(1L, queue.stats().duplicates)
        assertEquals(3L, queue.stats().samplesDelivered)
    }

    @Test
    fun `a packet whose block has already been released is late, not lost`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)
        for (sequence in 104..107) queue.data(sequence)
        queue.data(108)

        // 104's block is retired; a copy arriving now cannot be played without going backwards.
        assertTrue(queue.data(105).samples.isEmpty())

        assertEquals(1L, queue.stats().late)
        assertEquals(0L, queue.stats().packetsLost)
    }

    @Test
    fun `a stalled block is released with concealment once its deadline passes`() {
        val queue = depacketizer(packetDurationMs = 5)
        queue.synchronizeAt(100)

        // 104 never arrives. 105, 106, 107 do.
        assertTrue(queue.data(105).samples.isEmpty())
        assertTrue(queue.data(106).samples.isEmpty())
        assertTrue(queue.data(107).samples.isEmpty())

        // Deadline is 4 packets (20 ms) plus the 10 ms out-of-order grace of spec §8.4.
        advanceMs(29)
        assertTrue("29 ms is inside the grace period", queue.onIdle().samples.isEmpty())

        advanceMs(2)
        val released = queue.onIdle()
        assertEquals(listOf(104, 105, 106, 107), sequencesOf(released))
        assertTrue("the missing slot is concealment", released.samples[0].concealment)
        assertFalse(released.samples[1].concealment)
        assertEquals(1L, queue.stats().packetsLost)
        assertEquals(1L, queue.stats().samplesConcealed)
        assertEquals(1L, queue.stats().blocksIncomplete)
    }

    @Test
    fun `a concealment sample carries one packet duration of timeline, not a zero timestamp`() {
        val queue = depacketizer(packetDurationMs = 5)
        queue.synchronizeAt(100)
        queue.data(104, timestamp = 1000)
        // 105 is lost; 106 and 107 arrive.
        queue.data(106, timestamp = 1010)
        queue.data(107, timestamp = 1015)

        advanceMs(40)
        val released = queue.onIdle()

        assertEquals(listOf(105, 106, 107), sequencesOf(released))
        val concealed = released.samples.first()
        assertTrue(concealed.concealment)
        // Base timestamp of the block is 1000, and 105 is one slot in: 1000 + 5.
        assertEquals(1005, concealed.timestamp)
        assertEquals(0, concealed.length)
    }

    @Test
    fun `a gap larger than the concealment bound resynchronises instead of flooding silence`() {
        val queue = depacketizer(maxConcealedPacketsPerGap = 8)
        queue.synchronizeAt(100)
        for (sequence in 104..107) queue.data(sequence)

        // A two-second dropout. Concealing it would hand the decoder two seconds of silence to play
        // before it reached live audio again, which is exactly the latency this layer prevents.
        val released = queue.data(500)

        assertEquals(listOf(500), sequencesOf(released))
        assertEquals(0L, queue.stats().samplesConcealed)
        assertTrue(released.events.any { it is AudioStreamEvent.Resynchronised })
        val lost = released.events.filterIsInstance<AudioStreamEvent.PacketsLost>().single()
        assertEquals(392, lost.count)
        assertEquals(0, lost.concealed)
        assertEquals(108, lost.firstSequenceNumber)
    }

    @Test
    fun `a small gap is concealed so the timeline stays aligned`() {
        val queue = depacketizer(maxConcealedPacketsPerGap = 8)
        queue.synchronizeAt(100)
        for (sequence in 104..107) queue.data(sequence)

        // Packets 108..111 are gone and the next block starts. Four slots is inside the bound.
        val released = queue.data(112)

        assertEquals(listOf(108, 109, 110, 111, 112), sequencesOf(released))
        assertEquals(4, released.samples.count { it.concealment })
        val lost = released.events.filterIsInstance<AudioStreamEvent.PacketsLost>().single()
        assertEquals(4, lost.count)
        assertEquals(4, lost.concealed)
    }

    @Test
    fun `one lost packet costs 5 ms, not the full out-of-order grace period`() {
        // The head block is abandoned the moment a later block arrives, because a host sends in
        // order and a later packet is proof the missing one is already overdue. Waiting the full
        // 30 ms deadline here would add 30 ms of latency to every single loss.
        val queue = depacketizer()
        queue.synchronizeAt(100)
        for (sequence in 104..107) queue.data(sequence)

        assertTrue(queue.data(109).samples.isEmpty())
        assertTrue(queue.data(110).samples.isEmpty())
        assertTrue(queue.data(111).samples.isEmpty())

        // No time passes at all; block 112's first packet is what releases block 108.
        val released = queue.data(112)

        assertEquals(listOf(108, 109, 110, 111, 112), sequencesOf(released))
        assertEquals(1, released.samples.count { it.concealment })
    }

    @Test
    fun `a link that genuinely reorders gets the full grace period instead`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)
        for (sequence in 104..107) queue.data(sequence)

        // 108 is missing, 109..111 arrive, 112 opens the next block: block 108 is abandoned and
        // 108 is concealed. The real 108 then turns up far too late — which is the evidence that
        // this link reorders.
        queue.data(109)
        queue.data(110)
        queue.data(111)
        queue.data(112)
        queue.data(108)
        assertEquals(1L, queue.stats().late)

        // 113 is now missing. 114 and 115 arrive, then 116 opens the next block — which under the
        // fast rule would abandon 113 on the spot. It must not: on a reordering link, 113 may still
        // be in flight, so the full grace period of spec §8.4 applies.
        assertTrue(queue.data(114).samples.isEmpty())
        assertTrue(queue.data(115).samples.isEmpty())
        assertTrue(queue.data(116).samples.isEmpty())

        advanceMs(31)
        val released = queue.onIdle()
        // Block 112 drains first, 113 concealed. Block 116's own deadline has passed by now too,
        // so it drains behind it with its three unfilled slots concealed.
        assertEquals(listOf(113, 114, 115, 116, 117, 118, 119), sequencesOf(released))
        assertTrue(released.samples.first().concealment)
        assertEquals(4, released.samples.count { it.concealment })
    }

    @Test
    fun `too many queued blocks releases the head without consulting the clock`() {
        val queue = depacketizer(maxTrackedBlocks = 2)
        queue.synchronizeAt(100)

        // 104 is lost; 105 fills block 104, then 108 opens a second block.
        assertTrue(queue.data(105).samples.isEmpty())
        // No time passes at all — the queue is over its bound, so waiting only adds latency.
        val released = queue.data(108)

        assertEquals(listOf(104, 105, 106, 107, 108), sequencesOf(released))
        assertEquals(3, released.samples.count { it.concealment })
    }

    @Test
    fun `parity packets are counted but never enter the data sequence logic`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)
        queue.data(104)

        // A host numbers parity 108 and 109 for the block based at 104. Treating those as data
        // would skip two real packets and report loss on every block forever.
        assertTrue(queue.parity(base = 104, shard = 0).samples.isEmpty())
        assertTrue(queue.parity(base = 104, shard = 1).samples.isEmpty())

        assertEquals(listOf(105), sequencesOf(queue.data(105)))
        assertEquals(2L, queue.stats().parityReceived)
        assertEquals(2L, queue.stats().parityIgnored)
        assertEquals(0L, queue.stats().packetsLost)
        // Parity is not audio and must not inflate the received count spec §11.1 reads.
        assertEquals(3L, queue.stats().packetsReceived)
    }

    @Test
    fun `enabling FEC recovery stores parity shards without recovering anything yet`() {
        val queue = depacketizer(fecRecoveryEnabled = true)
        queue.synchronizeAt(100)

        // 104 and 105 are lost; both parity shards arrive. RS(4,2) could rebuild them — spec §8.4's
        // v1 approach deliberately does not, because a wrong generator matrix corrupts silently.
        queue.parity(base = 104, shard = 0)
        queue.parity(base = 104, shard = 1)
        queue.data(106)
        queue.data(107)

        advanceMs(40)
        val released = queue.onIdle()

        assertEquals(listOf(104, 105, 106, 107), sequencesOf(released))
        assertEquals(2, released.samples.count { it.concealment })
        assertEquals(0L, queue.stats().parityIgnored)
        val incomplete = released.events.filterIsInstance<AudioStreamEvent.BlockIncomplete>().single()
        // The counters say what recovery *would* have bought: 2 data + 2 parity is enough for RS.
        assertEquals(2, incomplete.dataShardsReceived)
        assertEquals(2, incomplete.parityShardsReceived)
    }

    @Test
    fun `the start-up drop discards the host's backlog and then plays`() {
        // 500 ms at 5 ms packets is 100 packets, exactly as spec-adjacent hosts accumulate.
        val queue = depacketizer(initialResyncDropMs = 50)

        for (sequence in 100 until 110) {
            assertTrue(queue.data(sequence).samples.isEmpty())
        }
        assertEquals(10L, queue.stats().resyncDropped)

        // Packet 110 is the first kept, and it synchronises rather than playing: the queue starts
        // on the next block boundary, which is 112.
        assertTrue(queue.data(110).samples.isEmpty())
        assertEquals(listOf(112), sequencesOf(queue.data(112)))
    }

    @Test
    fun `a quiet socket cancels the start-up drop, because a quiet host has no backlog`() {
        val queue = depacketizer(initialResyncDropMs = 500)

        queue.data(100)
        assertEquals(1L, queue.stats().resyncDropped)

        queue.onIdle()

        // The drop is abandoned: the next packet synchronises and the one after plays.
        assertTrue(queue.data(104).samples.isEmpty())
        assertEquals(listOf(108), sequencesOf(queue.data(108)))
        assertEquals(1L, queue.stats().resyncDropped)
    }

    @Test
    fun `sequence numbers wrap at 65535 without reporting a loss burst`() {
        val queue = depacketizer()
        queue.synchronizeAt(65532)

        // The block after 65532 is 0..3.
        assertEquals(listOf(0), sequencesOf(queue.data(0)))
        assertEquals(listOf(1), sequencesOf(queue.data(1)))
        assertEquals(listOf(2), sequencesOf(queue.data(2)))
        assertEquals(listOf(3), sequencesOf(queue.data(3)))
        assertEquals(listOf(4), sequencesOf(queue.data(4)))

        assertEquals(0L, queue.stats().packetsLost)
        assertEquals(0L, queue.stats().late)
    }

    @Test
    fun `a TOC byte that changes mid-stream is reported once, not once per packet`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)
        queue.data(104)

        val changedPayload = AudioPacketFixtures.opusPayload(105).also { it[0] = 0x08 }
        val changed = AudioPacketFixtures.dataPacket(105, payload = changedPayload)
        val first = queue.submit(changed, changed.size)

        val event = first.events.filterIsInstance<AudioStreamEvent.TocChanged>().single()
        assertEquals(AudioPacketFixtures.TOC_CELT_FB_5MS_STEREO, event.previous)
        assertEquals(0x08, event.current)

        // The next packet with the same new byte is not a change.
        val again = AudioPacketFixtures.dataPacket(
            106,
            payload = AudioPacketFixtures.opusPayload(106).also { it[0] = 0x08 },
        )
        assertTrue(
            queue.submit(again, again.size).events
                .filterIsInstance<AudioStreamEvent.TocChanged>()
                .isEmpty(),
        )
    }

    @Test
    fun `a datagram that does not parse is counted rather than crashing the receive thread`() {
        val queue = depacketizer()
        val runt = ByteArray(6)

        assertTrue(queue.submit(runt, runt.size).samples.isEmpty())
        assertEquals(1L, queue.stats().packetsRejected)
        assertEquals(0L, queue.stats().packetsReceived)
    }

    @Test
    fun `encrypted audio is discarded rather than handed to a decoder as noise`() {
        val queue = depacketizer(audioEncryptionNegotiated = true)

        for (sequence in 100..110) {
            assertTrue(queue.data(sequence).samples.isEmpty())
        }

        assertEquals(0L, queue.stats().samplesDelivered)
        assertEquals(11L, queue.stats().packetsRejected)
    }

    @Test
    fun `stats say whether traffic arrived at all, which is a different diagnosis from unusable`() {
        val queue = depacketizer()
        assertFalse(queue.stats().sawTraffic)

        queue.data(100)
        assertTrue(queue.stats().sawTraffic)
    }

    @Test
    fun `resetting forgets the stream position but keeps the counters`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)
        queue.data(104)
        val before = queue.stats().samplesDelivered

        queue.reset()

        // A restarted stream re-synchronises from scratch rather than reporting a huge gap.
        assertTrue(queue.data(200).samples.isEmpty())
        assertEquals(listOf(204), sequencesOf(queue.data(204)))
        assertEquals(before + 1L, queue.stats().samplesDelivered)
        assertEquals(0L, queue.stats().packetsLost)
    }

    @Test
    fun `payload bytes are copied, so the receive buffer can be reused immediately`() {
        val queue = depacketizer()
        queue.synchronizeAt(100)

        val buffer = AudioPacketFixtures.dataPacket(104)
        val released = queue.submit(buffer, buffer.size)
        val sample = released.samples.single()
        val originalToc = sample.tocByte

        // The receive loop reuses one buffer for the life of the session.
        buffer.fill(0x5A)

        assertEquals(originalToc, sample.tocByte)
        assertEquals(AudioPacketFixtures.TOC_CELT_FB_5MS_STEREO, sample.tocByte)
        assertEquals(104, AudioPacketFixtures.sequenceOf(sample.data))
    }
}
