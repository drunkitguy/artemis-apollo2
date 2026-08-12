package com.voidlink.android.protocol.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame reassembly against synthetic packet sequences (`docs/01-PROTOCOL.md` §7.7, §7.8).
 *
 * This is the layer's main test and it needs no host: every packet is generated from the byte
 * layout the spec publishes, so in-order delivery, reordering, duplication, loss and frame gaps can
 * all be produced exactly and repeatably.
 *
 * Two properties are asserted throughout, and they are what the whole layer exists for:
 *
 * 1. A frame that comes out is **byte-for-byte** what went in. Not "about the right size" — the
 *    exact bytes, because shards concatenated in the wrong order produce a frame that looks
 *    plausible and decodes to garbage.
 * 2. A frame that cannot be completed **never comes out**, and is reported. Handing a decoder the
 *    fragments we did receive produces corruption that persists to the next keyframe.
 *
 * FEC is off in every test here (the default), so these all exercise the path a healthy stream
 * takes: complete when every data shard arrives, drop and ask for an IDR otherwise.
 */
class FrameAssemblerTest {

    private val idr = VideoPacketFixtures.h264IdrPayload(0x11, 0x22, 0x33)
    private val nonIdr = VideoPacketFixtures.h264NonIdrPayload(0x44)
    private val partA = VideoPacketFixtures.bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0xA0, 0xA1)
    private val partB = VideoPacketFixtures.bytes(0xB0, 0xB1, 0xB2, 0xB3)
    private val partC = VideoPacketFixtures.bytes(0xC0, 0xC1)

    /**
     * Sequence numbers are handed out contiguously across a test.
     *
     * This matters: the first datagram a stream ever receives *defines* the sequence-number
     * baseline, so a packet numbered below it is genuinely before the beginning and is classified
     * late rather than reordered. Reordering is a mid-stream phenomenon and the tests model it as
     * one.
     */
    private var nextSequence = 0

    private fun assembler(
        requireKeyFrameToStart: Boolean = false,
        maxFrameBytes: Int = RtpVideoConstants.MAX_FRAME_BYTES,
    ): FrameAssembler = FrameAssembler(
        FrameAssemblerConfig(
            bitstream = VideoBitstream.H264,
            requireKeyFrameToStart = requireKeyFrameToStart,
            maxFrameBytes = maxFrameBytes,
        ),
    )

    /** Datagrams for one frame, taking the next run of sequence numbers. */
    private fun datagrams(
        frameIndex: Long,
        payloads: List<ByteArray>,
        fecPercentage: Int = 0,
        timestamp: Int = 0,
        extraFlags: Int = 0,
        multiFecFlags: Int = 0,
        multiFecBlocks: Int = 1,
    ): List<ByteArray> {
        val base = nextSequence
        nextSequence = SequenceNumbers.advance(base, payloads.size)
        return VideoPacketFixtures.frame(
            frameIndex = frameIndex,
            baseSequenceNumber = base,
            payloads = payloads,
            fecPercentage = fecPercentage,
            timestamp = timestamp,
            extraFlags = extraFlags,
            multiFecFlags = multiFecFlags,
            multiFecBlocks = multiFecBlocks,
        )
    }

    private fun feed(
        assembler: FrameAssembler,
        received: List<ByteArray>,
    ): Pair<List<VideoFrame>, List<VideoStreamEvent>> {
        val frames = ArrayList<VideoFrame>()
        val events = ArrayList<VideoStreamEvent>()
        for (datagram in received) {
            val result = assembler.submit(datagram)
            val frame = result.frame
            if (frame != null) frames.add(frame)
            events.addAll(result.events)
        }
        return Pair(frames, events)
    }

    private fun concat(payloads: List<ByteArray>): ByteArray {
        var total = 0
        for (payload in payloads) total += payload.size
        val out = ByteArray(total)
        var cursor = 0
        for (payload in payloads) {
            System.arraycopy(payload, 0, out, cursor, payload.size)
            cursor += payload.size
        }
        return out
    }

    // ---- Happy paths -----------------------------------------------------------------------

    @Test
    fun `a single-packet frame comes out whole`() {
        val (frames, events) = feed(
            assembler(),
            datagrams(1L, listOf(idr), timestamp = 0x1234),
        )

        assertEquals(1, frames.size)
        assertArrayEquals(idr, frames[0].data)
        assertEquals(1L, frames[0].frameIndex)
        assertEquals(0x1234, frames[0].rtpTimestamp)
        assertTrue(frames[0].isKeyFrame)
        assertEquals(0, frames[0].recoveredShardCount)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a multi-packet frame is concatenated in shard order`() {
        val (frames, _) = feed(assembler(), datagrams(1L, listOf(partA, partB, partC)))

        assertEquals(1, frames.size)
        assertArrayEquals(concat(listOf(partA, partB, partC)), frames[0].data)
    }

    @Test
    fun `consecutive frames complete without a gap being reported`() {
        val assembler = assembler()
        var events = emptyList<VideoStreamEvent>()
        for (index in 1L..5L) {
            events = events + feed(assembler, datagrams(index, listOf(partA, partB))).second
        }

        assertEquals(5L, assembler.stats().framesCompleted)
        assertEquals(0L, assembler.stats().framesDropped)
        assertTrue(events.none { it.requestsIdr })
    }

    // ---- Reordering ------------------------------------------------------------------------

    @Test
    fun `reordered packets still assemble to the same bytes`() {
        val assembler = assembler()
        feed(assembler, datagrams(1L, listOf(idr)))

        val packets = datagrams(2L, listOf(partA, partB, partC))
        // Delivered last, first, middle — the frame is complete only on the final datagram.
        val (frames, events) = feed(assembler, listOf(packets[2], packets[0], packets[1]))

        assertEquals(1, frames.size)
        assertArrayEquals(concat(listOf(partA, partB, partC)), frames[0].data)
        assertTrue(events.none { it.requestsIdr })
        assertEquals(0L, assembler.stats().packetsLost)
    }

    @Test
    fun `a fully reversed frame still assembles`() {
        val assembler = assembler()
        feed(assembler, datagrams(1L, listOf(idr)))

        val payloads = List(8) { index -> ByteArray(16) { (index * 16 + it).toByte() } }
        val packets = datagrams(2L, payloads)
        val (frames, events) = feed(assembler, packets.reversed())

        assertEquals(1, frames.size)
        assertArrayEquals(concat(payloads), frames[0].data)
        assertEquals(0L, assembler.stats().packetsLost)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `duplicated packets change nothing`() {
        val assembler = assembler()
        val packets = datagrams(1L, listOf(partA, partB, partC))
        val doubled = listOf(
            packets[0], packets[0], packets[1], packets[1], packets[2], packets[2],
        )
        val (frames, events) = feed(assembler, doubled)

        assertEquals(1, frames.size)
        assertArrayEquals(concat(listOf(partA, partB, partC)), frames[0].data)
        assertTrue(events.none { it.requestsIdr })
        assertEquals(0L, assembler.stats().packetsLost)
        // Three repeats, classified as duplicate or late depending on whether their slot had
        // already been consumed — either way, accounted for and not fed into the frame twice.
        val stats = assembler.stats()
        assertEquals(3L, stats.packetsDuplicated + stats.packetsLate)
    }

    @Test
    fun `sequence numbers wrapping mid-frame do not break assembly`() {
        nextSequence = 65534
        val payloads = listOf(partA, partB, partC, VideoPacketFixtures.bytes(0xD0))
        val (frames, events) = feed(assembler(), datagrams(1L, payloads))

        assertEquals(1, frames.size)
        assertArrayEquals(concat(payloads), frames[0].data)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a frame that wraps and is reordered still assembles`() {
        // The priming frame takes 65533; the frame under test then runs 65534, 65535, 0.
        nextSequence = 65533
        val assembler = assembler()
        feed(assembler, datagrams(1L, listOf(idr)))

        val payloads = listOf(partA, partB, partC)
        val packets = datagrams(2L, payloads)
        val (frames, _) = feed(assembler, listOf(packets[1], packets[2], packets[0]))

        assertEquals(1, frames.size)
        assertArrayEquals(concat(payloads), frames[0].data)
        assertEquals(0L, assembler.stats().packetsLost)
    }

    // ---- Loss ------------------------------------------------------------------------------

    @Test
    fun `a frame with a missing shard is dropped, reported, and asks for an IDR`() {
        val assembler = assembler()
        val first = datagrams(1L, listOf(partA, partB, partC))
        val second = datagrams(2L, listOf(idr))

        // Shard 1 of frame 1 never arrives; frame 2 then starts, which is what proves frame 1 dead.
        val (frames, events) = feed(assembler, listOf(first[0], first[2]) + second)

        assertEquals(1, frames.size)
        assertEquals(2L, frames[0].frameIndex)

        val dropped = events.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(1L, dropped[0].frameIndex)
        assertEquals(FrameDropReason.INCOMPLETE, dropped[0].reason)
        assertEquals(1, dropped[0].missingDataShards)
        assertTrue(dropped[0].requestsIdr)
        assertEquals(1L, assembler.stats().framesDropped)
        assertEquals(1L, assembler.stats().framesCompleted)
    }

    @Test
    fun `no fragment of an incomplete frame is ever emitted`() {
        // The property that matters most: partial frames must not reach a decoder at all.
        val assembler = assembler()
        val first = datagrams(1L, listOf(partA, partB, partC))
        val second = datagrams(2L, listOf(idr))
        val (frames, _) = feed(assembler, listOf(first[0], first[1]) + second)

        assertEquals(1, frames.size)
        assertArrayEquals(idr, frames[0].data)
    }

    @Test
    fun `a late shard of an abandoned frame does not resurrect it`() {
        val assembler = assembler()
        val first = datagrams(1L, listOf(partA, partB))
        val second = datagrams(2L, listOf(idr))

        val (frames, _) = feed(assembler, listOf(first[0]) + second + listOf(first[1]))

        assertEquals(1, frames.size)
        assertEquals(2L, frames[0].frameIndex)
        assertEquals(1L, assembler.stats().framesCompleted)
    }

    @Test
    fun `a gap in frame indices is reported and asks for an IDR`() {
        val assembler = assembler()
        feed(assembler, datagrams(1L, listOf(idr)))
        val (frames, events) = feed(assembler, datagrams(4L, listOf(idr)))

        assertEquals(1, frames.size)
        val missing = events.filterIsInstance<VideoStreamEvent.FramesMissing>()
        assertEquals(1, missing.size)
        assertEquals(2L, missing[0].firstMissingFrameIndex)
        assertEquals(2, missing[0].count)
        assertTrue(missing[0].requestsIdr)
    }

    @Test
    fun `packet loss is counted and reported once the hole is beyond reordering`() {
        val assembler = assembler()
        // Frame 1 shard 0 arrives at sequence 100; sequence 101 is lost; a long run follows.
        assembler.submit(
            VideoPacketFixtures.packet(
                sequenceNumber = 100,
                frameIndex = 1L,
                fecIndex = 0,
                dataShards = 2,
                payload = partA,
            ),
        )
        var events = emptyList<VideoStreamEvent>()
        for (index in 0 until 80) {
            val result = assembler.submit(
                VideoPacketFixtures.packet(
                    sequenceNumber = 102 + index,
                    frameIndex = 2L + index,
                    fecIndex = 0,
                    dataShards = 1,
                    payload = idr,
                ),
            )
            events = events + result.events
        }

        val lost = events.filterIsInstance<VideoStreamEvent.PacketsLost>()
        assertEquals(1, lost.size)
        assertEquals(1, lost[0].count)
        // Loss of a packet is a link-quality signal (spec §11.2), not on its own a reason to ask
        // for an IDR — the frame drop it causes is.
        assertFalse(lost[0].requestsIdr)
        assertEquals(1L, assembler.stats().packetsLost)
    }

    // ---- Frame content rules (spec §7.8) ---------------------------------------------------

    @Test
    fun `shards without picture data are excluded from the frame`() {
        val withData = VideoPacketFixtures.packet(
            sequenceNumber = 10,
            frameIndex = 1L,
            fecIndex = 0,
            dataShards = 2,
            payload = idr,
            flags = RtpVideoConstants.FLAG_CONTAINS_PIC_DATA or RtpVideoConstants.FLAG_SOF,
        )
        val withoutData = VideoPacketFixtures.packet(
            sequenceNumber = 11,
            frameIndex = 1L,
            fecIndex = 1,
            dataShards = 2,
            payload = VideoPacketFixtures.bytes(0xDE, 0xAD, 0xBE, 0xEF),
            flags = RtpVideoConstants.FLAG_EOF,
        )
        val (frames, _) = feed(assembler(), listOf(withData, withoutData))

        assertEquals(1, frames.size)
        assertArrayEquals(idr, frames[0].data)
    }

    @Test
    fun `parity shards are filed but never contribute bytes`() {
        // Two data shards plus one parity shard. With FEC off the parity shard is simply stored;
        // the frame completes on its data shards and must not include parity bytes.
        val data0 = VideoPacketFixtures.packet(
            sequenceNumber = 10,
            frameIndex = 1L,
            fecIndex = 0,
            dataShards = 2,
            fecPercentage = 50,
            payload = partA,
            flags = RtpVideoConstants.FLAG_CONTAINS_PIC_DATA or RtpVideoConstants.FLAG_SOF,
        )
        val parity = VideoPacketFixtures.packet(
            sequenceNumber = 12,
            frameIndex = 1L,
            fecIndex = 2,
            dataShards = 2,
            fecPercentage = 50,
            payload = VideoPacketFixtures.bytes(0xFF, 0xFF, 0xFF, 0xFF),
            flags = 0,
        )
        val data1 = VideoPacketFixtures.packet(
            sequenceNumber = 11,
            frameIndex = 1L,
            fecIndex = 1,
            dataShards = 2,
            fecPercentage = 50,
            payload = partB,
            flags = RtpVideoConstants.FLAG_CONTAINS_PIC_DATA or RtpVideoConstants.FLAG_EOF,
        )
        val (frames, _) = feed(assembler(), listOf(data0, parity, data1))

        assertEquals(1, frames.size)
        assertArrayEquals(concat(listOf(partA, partB)), frames[0].data)
    }

    @Test
    fun `the long-term-reference flag reaches the frame`() {
        val (frames, _) = feed(
            assembler(),
            datagrams(
                1L,
                listOf(partA, partB),
                extraFlags = RtpVideoConstants.EXTRA_FLAG_LTR_FRAME,
            ),
        )

        assertEquals(1, frames.size)
        assertTrue(frames[0].isLongTermReferenceFrame)
    }

    @Test
    fun `nothing is emitted until the first keyframe when the gate is on`() {
        // Spec §7.8: the first frame we submit must be a keyframe.
        val assembler = assembler(requireKeyFrameToStart = true)

        val (first, firstEvents) = feed(assembler, datagrams(1L, listOf(nonIdr)))
        assertTrue(first.isEmpty())
        val dropped = firstEvents.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(FrameDropReason.WAITING_FOR_KEY_FRAME, dropped[0].reason)
        assertTrue(dropped[0].requestsIdr)

        val (second, secondEvents) = feed(assembler, datagrams(2L, listOf(idr)))
        assertEquals(1, second.size)
        assertTrue(second[0].isKeyFrame)
        assertEquals(
            1,
            secondEvents.filterIsInstance<VideoStreamEvent.FirstKeyFrameReceived>().size,
        )

        // Once a keyframe has been seen, ordinary frames flow.
        val (third, _) = feed(assembler, datagrams(3L, listOf(nonIdr)))
        assertEquals(1, third.size)
        assertFalse(third[0].isKeyFrame)
    }

    // ---- Size boundaries -------------------------------------------------------------------

    @Test
    fun `a maximum-size frame assembles exactly`() {
        // 240 shards of 1 KiB: past any MTU-shaped assumption, and near the 255-shard ceiling
        // GF(2^8) imposes on a block.
        val payloads = List(240) { index -> ByteArray(1024) { ((index + it) and 0xFF).toByte() } }
        val (frames, events) = feed(assembler(), datagrams(1L, payloads))

        assertEquals(1, frames.size)
        assertEquals(240 * 1024, frames[0].length)
        assertArrayEquals(concat(payloads), frames[0].data)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a frame larger than the configured maximum is dropped rather than allocated`() {
        val payloads = List(4) { ByteArray(64) }
        val (frames, events) = feed(assembler(maxFrameBytes = 128), datagrams(1L, payloads))

        assertTrue(frames.isEmpty())
        val dropped = events.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(FrameDropReason.OVERSIZED, dropped[0].reason)
        assertTrue(dropped[0].requestsIdr)
    }

    // ---- Malformed input -------------------------------------------------------------------

    @Test
    fun `a block whose geometry changes mid-frame is dropped as malformed`() {
        val good = VideoPacketFixtures.packet(
            sequenceNumber = 10,
            frameIndex = 1L,
            fecIndex = 0,
            dataShards = 3,
            payload = partA,
        )
        val inconsistent = VideoPacketFixtures.packet(
            sequenceNumber = 11,
            frameIndex = 1L,
            fecIndex = 1,
            dataShards = 5,
            payload = partB,
        )
        val (frames, events) = feed(assembler(), listOf(good, inconsistent))

        assertTrue(frames.isEmpty())
        val dropped = events.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(FrameDropReason.MALFORMED, dropped[0].reason)
    }

    @Test
    fun `an unparseable datagram is reported without disturbing assembly`() {
        val assembler = assembler()
        val (_, events) = feed(assembler, listOf(ByteArray(4)))

        assertEquals(1, events.size)
        val rejected = events[0] as VideoStreamEvent.PacketRejected
        assertEquals(VideoPacketRejection.DATAGRAM_TOO_SHORT, rejected.reason)
        assertFalse(rejected.requestsIdr)
        assertEquals(1L, assembler.stats().packetsRejected)
        assertEquals(0L, assembler.stats().packetsReceived)

        // ... and a good frame right afterwards still comes through.
        val (frames, _) = feed(assembler, datagrams(1L, listOf(idr)))
        assertEquals(1, frames.size)
    }

    @Test
    fun `more FEC blocks than the block index can address is refused, not silently aliased`() {
        // UNVERIFIED (spec §7.4, item 8): the block index is two bits wide, so five blocks cannot
        // be told apart. Aliasing them would assemble a frame out of the wrong shards, so the
        // packet is refused outright and the frame is simply never built.
        val packets = datagrams(1L, listOf(partA), multiFecBlocks = 8)
        val (frames, events) = feed(assembler(), packets)

        assertTrue(frames.isEmpty())
        val rejected = events.filterIsInstance<VideoStreamEvent.PacketRejected>()
        assertEquals(1, rejected.size)
        assertEquals(VideoPacketRejection.IMPLAUSIBLE_FEC_GEOMETRY, rejected[0].reason)
    }

    // ---- Multiple FEC blocks per frame ------------------------------------------------------

    @Test
    fun `a frame split across two FEC blocks assembles in block order`() {
        // UNVERIFIED (spec §7.4, item 8): block index taken from multiFecFlags and 0x3.
        val blockZero = datagrams(
            frameIndex = 1L,
            payloads = listOf(partA, partB),
            multiFecFlags = 0,
            multiFecBlocks = 2,
        )
        val blockOne = datagrams(
            frameIndex = 1L,
            payloads = listOf(partC),
            multiFecFlags = 1,
            multiFecBlocks = 2,
        )
        // Block one arrives in the middle of block zero: the frame must still be assembled by
        // block index, not by arrival order.
        val (frames, events) = feed(
            assembler(),
            listOf(blockZero[0], blockOne[0], blockZero[1]),
        )

        assertEquals(1, frames.size)
        assertArrayEquals(concat(listOf(partA, partB, partC)), frames[0].data)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a frame missing one of its FEC blocks entirely is dropped`() {
        val assembler = assembler()
        val blockZero = datagrams(
            frameIndex = 1L,
            payloads = listOf(partA),
            multiFecFlags = 0,
            multiFecBlocks = 2,
        )
        val next = datagrams(2L, listOf(idr))
        val (frames, events) = feed(assembler, blockZero + next)

        assertEquals(1, frames.size)
        assertEquals(2L, frames[0].frameIndex)
        val dropped = events.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(FrameDropReason.INCOMPLETE, dropped[0].reason)
    }

    // ---- Bookkeeping -------------------------------------------------------------------------

    @Test
    fun `stats separate no traffic from no frames`() {
        // Spec §11.1 wants ML_ERROR_NO_VIDEO_TRAFFIC and ML_ERROR_NO_VIDEO_FRAME to be
        // distinguishable, which needs both counters.
        val assembler = assembler()
        assertEquals(0L, assembler.stats().packetsReceived)
        assertEquals(0L, assembler.stats().framesCompleted)

        assembler.submit(
            VideoPacketFixtures.packet(
                sequenceNumber = 1,
                frameIndex = 1L,
                fecIndex = 0,
                dataShards = 2,
                payload = partA,
            ),
        )
        assertEquals(1L, assembler.stats().packetsReceived)
        assertEquals(0L, assembler.stats().framesCompleted)
    }

    @Test
    fun `the sequence figures the FEC status message needs are exposed`() {
        // Spec §9.5's Sunshine per-frame FEC status carries both of these.
        val assembler = assembler()
        feed(assembler, datagrams(1L, listOf(partA, partB, partC)))

        assertEquals(2, assembler.stats().highestSequenceNumber)
        assertEquals(3, assembler.stats().nextContiguousSequenceNumber)
    }

    @Test
    fun `reset returns the assembler to its initial state`() {
        val assembler = assembler(requireKeyFrameToStart = true)
        feed(assembler, datagrams(10L, listOf(idr)))
        assertEquals(1L, assembler.stats().framesCompleted)

        assembler.reset()
        nextSequence = 0

        // A frame index lower than the one already completed must be accepted again.
        val (frames, events) = feed(assembler, datagrams(1L, listOf(idr)))
        assertEquals(1, frames.size)
        assertEquals(
            1,
            events.filterIsInstance<VideoStreamEvent.FirstKeyFrameReceived>().size,
        )
    }

    @Test
    fun `a packet that completes nothing produces the shared empty result`() {
        val assembler = assembler()
        val packets = datagrams(1L, listOf(partA, partB))
        val first = assembler.submit(packets[0])

        assertNull(first.frame)
        assertTrue(first.events.isEmpty())
        assertFalse(first.requestsIdr)
        assertNotNull(assembler.submit(packets[1]).frame)
    }
}
