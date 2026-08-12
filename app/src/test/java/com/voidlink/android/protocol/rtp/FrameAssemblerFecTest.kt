package com.voidlink.android.protocol.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reed-Solomon recovery wired into reassembly, and — more importantly — the proof that it is
 * **not on the critical path** (`docs/01-PROTOCOL.md` §7.7).
 *
 * Spec §7.7 makes one mitigation mandatory: a stream must work with recovery disabled, because the
 * RS matrix variant is the riskiest unverified detail in the protocol and getting it wrong corrupts
 * frames silently rather than failing. So the first two tests here are the load-bearing ones:
 * recovery is **off by default**, and with it off a lossless stream is byte-identical to one
 * assembled with it on.
 *
 * The parity shards these tests feed in are produced by our own encoder. That is honest about what
 * it proves — the wiring, not interoperability. What matrix the host uses cannot be established
 * from this machine at all; see [ReedSolomonTest] for what can.
 */
class FrameAssemblerFecTest {

    private val idr = VideoPacketFixtures.h264IdrPayload(0x11, 0x22, 0x33, 0x44, 0x55)
    private val shardPayloads = listOf(
        VideoPacketFixtures.bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0xA0, 0xA1, 0xA2),
        VideoPacketFixtures.bytes(0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7),
        VideoPacketFixtures.bytes(0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7),
        VideoPacketFixtures.bytes(0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7),
    )

    private fun assembler(fecEnabled: Boolean): FrameAssembler = FrameAssembler(
        FrameAssemblerConfig(
            bitstream = VideoBitstream.H264,
            requireKeyFrameToStart = false,
            fecRecoveryEnabled = fecEnabled,
        ),
    )

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

    /**
     * Builds one FEC block: `dataShards` data datagrams and the parity datagrams that go with them.
     *
     * The parity is computed over the **payloads**, zero-padded to a common length, which is what
     * [UnverifiedRtpVideoConstants.FEC_SHARD_IS_PAYLOAD_ONLY] records — and it is also the only
     * reading that leaves a parity packet with a readable NV header, which spec §7.4 and §7.7
     * step 5 both require. Each parity datagram is an ordinary video packet whose `fecIndex` puts
     * it past the data shards and whose payload is the parity bytes.
     */
    private fun blockWithParity(
        frameIndex: Long,
        baseSequenceNumber: Int,
        payloads: List<ByteArray>,
        fecPercentage: Int,
    ): Pair<List<ByteArray>, List<ByteArray>> {
        val dataShards = payloads.size
        val parityShards = (dataShards * fecPercentage + 99) / 100
        require(parityShards >= 1) { "this fixture needs at least one parity shard" }

        val dataDatagrams = VideoPacketFixtures.frame(
            frameIndex = frameIndex,
            baseSequenceNumber = baseSequenceNumber,
            payloads = payloads,
            fecPercentage = fecPercentage,
        )

        var shardSize = 0
        for (payload in payloads) {
            if (payload.size > shardSize) shardSize = payload.size
        }
        val codec = ReedSolomon.create(
            dataShards,
            parityShards,
            UnverifiedRtpVideoConstants.FEC_MATRIX_VARIANT,
        )
        val shards = arrayOfNulls<ByteArray>(codec.totalShards)
        for (index in payloads.indices) shards[index] = payloads[index].copyOf(shardSize)
        codec.encodeParity(shards, shardSize)

        val parityDatagrams = (dataShards until codec.totalShards).map { index ->
            VideoPacketFixtures.packet(
                sequenceNumber = SequenceNumbers.advance(baseSequenceNumber, index),
                frameIndex = frameIndex,
                fecIndex = index,
                dataShards = dataShards,
                fecPercentage = fecPercentage,
                payload = requireNotNull(shards[index]),
                flags = 0,
            )
        }
        return Pair(dataDatagrams, parityDatagrams)
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

    @Test
    fun `FEC recovery is off unless it is explicitly asked for`() {
        // Spec §7.7's mandatory mitigation, and the single most important assertion in this file.
        assertFalse(UnverifiedRtpVideoConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT)
        assertFalse(FrameAssemblerConfig().fecRecoveryEnabled)
    }

    @Test
    fun `a lossless stream assembles identically whether FEC is on or off`() {
        // The RS code must not be touched when every data shard arrives, so both paths have to
        // produce exactly the same bytes.
        val (data, parity) = blockWithParity(1L, 100, shardPayloads, fecPercentage = 50)

        val withoutFec = feed(assembler(fecEnabled = false), data + parity).first
        val withFec = feed(assembler(fecEnabled = true), data + parity).first

        assertEquals(1, withoutFec.size)
        assertEquals(1, withFec.size)
        assertArrayEquals(concat(shardPayloads), withoutFec[0].data)
        assertArrayEquals(withoutFec[0].data, withFec[0].data)
        assertEquals(0, withoutFec[0].recoveredShardCount)
        assertEquals(0, withFec[0].recoveredShardCount)
    }

    @Test
    fun `with FEC off a lost shard drops the frame and asks for an IDR`() {
        val (data, parity) = blockWithParity(1L, 100, shardPayloads, fecPercentage = 50)
        val assembler = assembler(fecEnabled = false)

        // Shard 2 is lost; the parity shard that could have replaced it arrives.
        val delivered = listOf(data[0], data[1], data[3]) + parity
        val (frames, events) = feed(assembler, delivered)

        assertTrue(frames.isEmpty())
        assertEquals(0L, assembler.stats().shardsRecovered)

        // The frame is only pronounced dead once a later frame starts (spec §7.7 step 8).
        val (later, laterEvents) = feed(
            assembler,
            VideoPacketFixtures.frame(2L, 110, listOf(idr)),
        )
        assertEquals(1, later.size)
        val dropped = (events + laterEvents).filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(FrameDropReason.INCOMPLETE, dropped[0].reason)
        assertTrue(dropped[0].requestsIdr)
    }

    @Test
    fun `with FEC on a parity shard rebuilds the lost data shard exactly`() {
        val (data, parity) = blockWithParity(1L, 100, shardPayloads, fecPercentage = 50)
        val assembler = assembler(fecEnabled = true)

        val delivered = listOf(data[0], data[1], data[3]) + parity
        val (frames, events) = feed(assembler, delivered)

        assertEquals(1, frames.size)
        assertArrayEquals(concat(shardPayloads), frames[0].data)
        assertEquals(1, frames[0].recoveredShardCount)
        assertEquals(1L, assembler.stats().shardsRecovered)
        assertEquals(1L, assembler.stats().framesRecovered)

        val recovered = events.filterIsInstance<VideoStreamEvent.FrameRecovered>()
        assertEquals(1, recovered.size)
        assertEquals(1, recovered[0].recoveredShards)
        assertFalse(recovered[0].requestsIdr)
    }

    @Test
    fun `each data shard in turn can be the one that is lost`() {
        for (lost in shardPayloads.indices) {
            val (data, parity) = blockWithParity(1L, 100, shardPayloads, fecPercentage = 50)
            val delivered = data.filterIndexed { index, _ -> index != lost } + parity
            val (frames, _) = feed(assembler(fecEnabled = true), delivered)

            assertEquals("lost shard $lost", 1, frames.size)
            assertArrayEquals("lost shard $lost", concat(shardPayloads), frames[0].data)
        }
    }

    @Test
    fun `two parity shards recover two lost data shards`() {
        // 50% of four data shards is two parity shards.
        val (data, parity) = blockWithParity(1L, 100, shardPayloads, fecPercentage = 50)
        assertEquals(2, parity.size)

        val delivered = listOf(data[1], data[2]) + parity
        val (frames, _) = feed(assembler(fecEnabled = true), delivered)

        assertEquals(1, frames.size)
        assertArrayEquals(concat(shardPayloads), frames[0].data)
        assertEquals(2, frames[0].recoveredShardCount)
    }

    @Test
    fun `more losses than parity shards still drops the frame rather than guessing`() {
        val (data, parity) = blockWithParity(1L, 100, shardPayloads, fecPercentage = 25)
        assertEquals(1, parity.size)
        val assembler = assembler(fecEnabled = true)

        // Two data shards lost, one parity shard available: unrecoverable by construction.
        val (frames, _) = feed(assembler, listOf(data[0], data[3]) + parity)
        assertTrue(frames.isEmpty())

        val (_, laterEvents) = feed(
            assembler,
            VideoPacketFixtures.frame(2L, 120, listOf(idr)),
        )
        val dropped = laterEvents.filterIsInstance<VideoStreamEvent.FrameDropped>()
        assertEquals(1, dropped.size)
        assertEquals(FrameDropReason.INCOMPLETE, dropped[0].reason)
        assertEquals(2, dropped[0].missingDataShards)
    }

    @Test
    fun `recovery works when parity and data shards are interleaved out of order`() {
        val (data, parity) = blockWithParity(1L, 100, shardPayloads, fecPercentage = 50)
        // A parity shard arrives in the middle of the data shards, and shards 1 and 2 never come.
        val delivered = listOf(data[0], parity[0], data[3], parity[1])
        val (frames, _) = feed(assembler(fecEnabled = true), delivered)

        assertEquals(1, frames.size)
        assertArrayEquals(concat(shardPayloads), frames[0].data)
        assertEquals(2, frames[0].recoveredShardCount)
    }

    @Test
    fun `a recovered shard is assumed to carry picture data and is included`() {
        // A rebuilt shard has no NV header of its own, so spec §7.8's FLAG_CONTAINS_PIC_DATA
        // exclusion cannot be read from it and is assumed — see
        // FEC_RECOVERED_SHARD_CARRIES_PICTURE_DATA. This pins that assumption's effect.
        assertTrue(UnverifiedRtpVideoConstants.FEC_RECOVERED_SHARD_CARRIES_PICTURE_DATA)
        val payloads = listOf(
            VideoPacketFixtures.bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0x01, 0x02, 0x03),
            VideoPacketFixtures.bytes(0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17),
        )
        val (data, parity) = blockWithParity(5L, 200, payloads, fecPercentage = 50)

        val (frames, _) = feed(assembler(fecEnabled = true), listOf(data[0]) + parity)

        assertEquals(1, frames.size)
        assertArrayEquals(concat(payloads), frames[0].data)
        assertEquals(1, frames[0].recoveredShardCount)
    }
}
