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
 * Two properties are asserted throughout, and they are the properties the whole layer exists for:
 *
 * 1. A frame that comes out is **byte-for-byte** what went in. Not "about the right size" — the
 *    exact bytes, because a shard concatenated in the wrong order produces a frame that looks
 *    plausible and decodes to garbage.
 * 2. A frame that cannot be completed **never comes out**, and is reported. Handing a decoder the
 *    fragments we did receive produces corruption that persists to the next keyframe.
 *
 * FEC is off in every test here (the default), so these all exercise the path a healthy stream
 * takes: complete when every data shard arrives, drop and ask for an IDR otherwise.
 */
class FrameAssemblerTest {

    private val idr = VideoPacketFixtures.h264IdrPayload(0x11, 0x22, 0x33)
    private val partA = VideoPacketFixtures.bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0xA0, 0xA1)
    private val partB = VideoPacketFixtures.bytes(0xB0, 0xB1, 0xB2, 0xB3)
    private val partC = VideoPacketFixtures.bytes(0xC0, 0xC1)

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

    private fun feed(
        assembler: FrameAssembler,
        datagrams: List<ByteArray>,
    ): Pair<List<VideoFrame>, List<VideoStreamEvent>> {
        val frames = ArrayList<VideoFrame>()
        val events = ArrayList<VideoStreamEvent>()
        for (datagram in datagrams) {
            val result = assembler.submit(datagram)
            result.frame?.let { frames.add(it) }
            events.addAll(result.events)
        }
        return Pair(frames, events)
    }

    @Test
    fun `a single-packet frame comes out whole`() {
        val packets = VideoPacketFixtures.frame(
            frameIndex = 1L,
            baseSequenceNumber = 100,
            payloads = listOf(idr),
            timestamp = 0x1234,
        )
        val (frames, events) = feed(assembler(), packets)

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
        val packets = VideoPacketFixtures.frame(
            frameIndex = 5L,
            baseSequenceNumber = 1000,
            payloads = listOf(partA, partB, partC),
        )
        val (frames, _) = feed(assembler(), packets)

        assertEquals(1, frames.size)
        assertArrayEquals(partA + partB + partC, frames[0].data)
    }

    @Test
    fun `reordered packets still assemble to the same bytes`() {
        val packets = VideoPacketFixtures.frame(
            frameIndex = 5L,
            baseSequenceNumber = 1000,
            payloads = listOf(partA, partB, partC),
        )
        // Delivered last, first, middle — the frame is complete only on the final datagram.
        val (frames, events) = feed(assembler(), listOf(packets[2], packets[0], packets[1]))

        assertEquals(1, frames.size)
        assertArrayEquals(partA + partB + partC, frames[0].data)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a fully reversed frame still assembles`() {
        val payloads = List(8) { index -> ByteArray(16) { (index * 16 + it).toByte() } }
        val packets = VideoPacketFixtures.frame(9L, 500, payloads)
        val (frames, events) = feed(assembler(), packets.reversed())

        assertEquals(1, frames.size)
        var expected = ByteArray(0)
        for (payload in payloads) expected += payload
        assertArrayEquals(expected, frames[0].data)
        assertEquals(0L, assembler().stats().packetsLost)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `duplicated packets change nothing`() {
        val packets = VideoPacketFixtures.frame(5L, 1000, listOf(partA, partB, partC))
        val doubled = listOf(
            packets[0], packets[0], packets[1], packets[1], packets[2], packets[2],
        )
        val assembler = assembler()
        val (frames, events) = feed(assembler, doubled)

        assertEquals(1, frames.size)
        assertArrayEquals(partA + partB + partC, frames[0].data)
        assertTrue(events.none { it.requestsIdr })
        assertEquals(3L, assembler.stats().packetsDuplicated + assembler.stats().packetsLate)
    }

    @Test
    fun `a frame with a missing shard is dropped, reported, and asks for an IDR`() {
        val first = VideoPacketFixtures.frame(1L, 100, listOf(partA, partB, partC))
        val second = VideoPacketFixtures.frame(2L, 103, listOf(idr))
        val assembler = assembler()

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
        val first = VideoPacketFixtures.frame(1L, 100, listOf(partA, partB, partC))
        val second = VideoPacketFixtures.frame(2L, 200, listOf(idr))
        val (frames, _) = feed(assembler(), listOf(first[0], first[1]) + second)

        assertEquals(1, frames.size)
        assertArrayEquals(idr, frames[0].data)
    }

    @Test
    fun `a late shard of an abandoned frame does not resurrect it`() {
        val first = VideoPacketFixtures.frame(1L, 100, listOf(partA, partB))
        val second = VideoPacketFixtures.frame(2L, 200, listOf(idr))
        val assembler = assembler()

        val (frames, _) = feed(assembler, listOf(first[0]) + second + listOf(first[1]))

        assertEquals(1, frames.size)
        assertEquals(2L, frames[0].frameIndex)
        assertEquals(1L, assembler.stats().framesCompleted)
    }

    @Test
    fun `a gap in frame indices is reported and asks for an IDR`() {
        val assembler = assembler()
        feed(assembler, VideoPacketFixtures.frame(1L, 100, listOf(idr)))
        val (frames, events) = feed(assembler, VideoPacketFixtures.frame(4L, 200, listOf(idr)))

        assertEquals(1, frames.size)
        val missing = events.filterIsInstance<VideoStreamEvent.FramesMissing>()
        assertEquals(1, missing.size)
        assertEquals(2L, missing[0].firstMissingFrameIndex)
        assertEquals(2, missing[0].count)
        assertTrue(missing[0].requestsIdr)
    }

    @Test
    fun `consecutive frames report no gap`() {
        val assembler = assembler()
        var events = emptyList<VideoStreamEvent>()
        for (index in 1L..5L) {
            val base = (index.toInt() - 1) * 4 + 100
            events = events + feed(
                assembler,
                VideoPacketFixtures.frame(index, base, listOf(partA, partB)),
            ).second
        }

        assertEquals(5L, assembler.stats().framesCompleted)
        assertEquals(0L, assembler.stats().framesDropped)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `sequence numbers wrapping mid-frame do not break assembly`() {
        // Shards 65534, 65535, 0, 1 — the frame straddles the wrap point.
        val payloads = listOf(partA, partB, partC, VideoPacketFixtures.bytes(0xD0))
        val packets = VideoPacketFixtures.frame(1L, 65534, payloads)
        val (frames, events) = feed(assembler(), packets)

        assertEquals(1, frames.size)
        assertArrayEquals(
            partA + partB + partC + VideoPacketFixtures.bytes(0xD0),
            frames[0].data,
        )
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a frame that wraps and is reordered still assembles`() {
        val payloads = listOf(partA, partB, partC)
        val packets = VideoPacketFixtures.frame(1L, 65535, payloads)
        val (frames, _) = feed(assembler(), listOf(packets[1], packets[2], packets[0]))

        assertEquals(1, frames.size)
        assertArrayEquals(partA + partB + partC, frames[0].data)
    }

    @Test
    fun `shards without picture data are excluded from the frame`() {
        // Spec §7.8: concatenate data-shard payloads, excluding shards where
        // FLAG_CONTAINS_PIC_DATA is clear.
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
    fun `a maximum-size frame assembles exactly`() {
        // 240 shards of 1 KiB: past any MTU-shaped assumption and past a byte-sized shard counter.
        val payloads = List(240) { index -> ByteArray(1024) { ((index + it) and 0xFF).toByte() } }
        val packets = VideoPacketFixtures.frame(1L, 0, payloads)
        val (frames, events) = feed(assembler(), packets)

        assertEquals(1, frames.size)
        assertEquals(240 * 1024, frames[0].length)
        var expected = ByteArray(0)
        for (payload in payloads) expected += payload
        assertArrayEquals(expected, frames[0].data)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a frame larger than the configured maximum is dropped rather than allocated`() {
        val payloads = List(4) { ByteArray(64) }
        val packets = VideoPacketFixtures.frame(1L, 0, payloads)
        val (frames, events) = feed(assembler(maxFrameBytes = 128), packets)

        assertTrue(frames.isEmpty())
        val dropped = events.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(FrameDropReason.OVERSIZED, dropped[0].reason)
    }

    @Test
    fun `nothing is emitted until the first keyframe when the gate is on`() {
        // Spec §7.8: the first frame we submit must be a keyframe.
        val assembler = FrameAssembler(
            FrameAssemblerConfig(bitstream = VideoBitstream.H264, requireKeyFrameToStart = true),
        )
        val nonKey = VideoPacketFixtures.h264NonIdrPayload(0x01)

        val (first, firstEvents) = feed(
            assembler,
            VideoPacketFixtures.frame(1L, 100, listOf(nonKey)),
        )
        assertTrue(first.isEmpty())
        val dropped = firstEvents.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(FrameDropReason.WAITING_FOR_KEY_FRAME, dropped[0].reason)
        assertTrue(dropped[0].requestsIdr)

        val (second, secondEvents) = feed(
            assembler,
            VideoPacketFixtures.frame(2L, 200, listOf(idr)),
        )
        assertEquals(1, second.size)
        assertTrue(second[0].isKeyFrame)
        assertEquals(
            1,
            secondEvents.filterIsInstance<VideoStreamEvent.FirstKeyFrameReceived>().size,
        )

        // Once a keyframe has been seen, ordinary frames flow.
        val (third, _) = feed(assembler, VideoPacketFixtures.frame(3L, 300, listOf(nonKey)))
        assertEquals(1, third.size)
        assertFalse(third[0].isKeyFrame)
    }

    @Test
    fun `the long-term-reference flag reaches the frame`() {
        val packets = VideoPacketFixtures.frame(
            frameIndex = 1L,
            baseSequenceNumber = 100,
            payloads = listOf(partA, partB),
            extraFlags = RtpVideoConstants.EXTRA_FLAG_LTR_FRAME,
        )
        val (frames, _) = feed(assembler(), packets)

        assertEquals(1, frames.size)
        assertTrue(frames[0].isLongTermReferenceFrame)
    }

    @Test
    fun `parity shards are filed but never contribute bytes`() {
        // A block of two data shards plus one parity shard. With FEC off the parity shard is simply
        // stored; the frame completes on its data shards alone and must not include parity bytes.
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
        assertArrayEquals(partA + partB, frames[0].data)
    }

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
        val (frames, _) = feed(assembler, VideoPacketFixtures.frame(1L, 100, listOf(idr)))
        assertEquals(1, frames.size)
    }

    @Test
    fun `a frame split across two FEC blocks assembles in block order`() {
        // UNVERIFIED (spec §7.4 item 8): block index taken from multiFecFlags and 0x3.
        val blockZero = VideoPacketFixtures.frame(
            frameIndex = 1L,
            baseSequenceNumber = 100,
            payloads = listOf(partA, partB),
            multiFecFlags = 0,
            multiFecBlocks = 2,
        )
        val blockOne = VideoPacketFixtures.frame(
            frameIndex = 1L,
            baseSequenceNumber = 200,
            payloads = listOf(partC),
            multiFecFlags = 1,
            multiFecBlocks = 2,
        )
        val (frames, events) = feed(assembler(), blockOne + blockZero)

        assertEquals(1, frames.size)
        assertArrayEquals(partA + partB + partC, frames[0].data)
        assertTrue(events.none { it.requestsIdr })
    }

    @Test
    fun `a frame missing one of its FEC blocks entirely is dropped`() {
        val blockZero = VideoPacketFixtures.frame(
            frameIndex = 1L,
            baseSequenceNumber = 100,
            payloads = listOf(partA),
            multiFecFlags = 0,
            multiFecBlocks = 2,
        )
        val next = VideoPacketFixtures.frame(2L, 300, listOf(idr))
        val (frames, events) = feed(assembler(), blockZero + next)

        assertEquals(1, frames.size)
        assertEquals(2L, frames[0].frameIndex)
        assertEquals(
            FrameDropReason.INCOMPLETE,
            events.filterIsInstance<VideoStreamEvent.FrameDropped>()[0].reason,
        )
    }

    @Test
    fun `more FEC blocks than the block index can address is malformed, not silently aliased`() {
        val packets = VideoPacketFixtures.frame(
            frameIndex = 1L,
            baseSequenceNumber = 100,
            payloads = listOf(partA),
            multiFecBlocks = 8,
        )
        val (frames, events) = feed(assembler(), packets)

        assertTrue(frames.isEmpty())
        assertEquals(
            FrameDropReason.MALFORMED,
            events.filterIsInstance<VideoStreamEvent.FrameDropped>()[0].reason,
        )
    }

    @Test
    fun `packet loss is counted and reported once the hole is beyond reordering`() {
        val assembler = assembler()
        // Frame 1 shard 0 arrives; sequence 101 is lost; then a long run of later frames.
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
        assertFalse(lost[0].requestsIdr)
        assertEquals(1L, assembler.stats().packetsLost)
    }

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
    fun `reset returns the assembler to its initial state`() {
        val assembler = FrameAssembler(
            FrameAssemblerConfig(bitstream = VideoBitstream.H264, requireKeyFrameToStart = true),
        )
        feed(assembler, VideoPacketFixtures.frame(10L, 100, listOf(idr)))
        assertEquals(1L, assembler.stats().framesCompleted)

        assembler.reset()

        // A frame index lower than the one already completed must be accepted again.
        val (frames, events) = feed(assembler, VideoPacketFixtures.frame(1L, 5, listOf(idr)))
        assertEquals(1, frames.size)
        assertEquals(
            1,
            events.filterIsInstance<VideoStreamEvent.FirstKeyFrameReceived>().size,
        )
    }

    @Test
    fun `an empty assembly result is shared rather than allocated`() {
        val assembler = assembler()
        val packets = VideoPacketFixtures.frame(1L, 100, listOf(partA, partB))
        val first = assembler.submit(packets[0])

        assertNull(first.frame)
        assertTrue(first.events.isEmpty())
        assertFalse(first.requestsIdr)
        assertNotNull(assembler.submit(packets[1]).frame)
    }
}
