package com.voidlink.android.protocol.rtp

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the socket-receive thread and the coroutine world (architecture §3, rule 1).
 *
 * The contract under test is narrow and non-negotiable: publishing a frame must **never block and
 * never suspend**, because the receive thread is the only thing draining the socket buffer, and
 * when the queue is full the *oldest* frame goes — an old frame is worth less than a new one, and
 * growing the queue trades away the one thing this product cannot trade, latency.
 *
 * Every eviction is counted and reported, because a silently discarded frame is exactly the kind
 * of loss this layer must not paper over.
 *
 * [runBlocking] rather than `runTest`: every receive here is already satisfiable, so there is no
 * delay to skip and nothing to gain from virtual time.
 */
class VideoFramePipelineTest {

    private val idr = VideoPacketFixtures.h264IdrPayload(0x11, 0x22)
    private val nonIdr = VideoPacketFixtures.h264NonIdrPayload(0x33)

    private fun pipeline(frameCapacity: Int = RtpVideoConstants.DECODE_QUEUE_CAPACITY) =
        VideoFramePipeline(
            config = FrameAssemblerConfig(
                bitstream = VideoBitstream.H264,
                requireKeyFrameToStart = false,
            ),
            frameCapacity = frameCapacity,
        )

    private fun frameDatagram(frameIndex: Long, sequenceNumber: Int, payload: ByteArray) =
        VideoPacketFixtures.packet(
            sequenceNumber = sequenceNumber,
            frameIndex = frameIndex,
            fecIndex = 0,
            dataShards = 1,
            payload = payload,
            flags = RtpVideoConstants.FLAG_CONTAINS_PIC_DATA or
                RtpVideoConstants.FLAG_SOF or
                RtpVideoConstants.FLAG_EOF,
        )

    @Test
    fun `a completed frame is published on the frame channel`() {
        val pipeline = pipeline()
        assertTrue(pipeline.onDatagram(frameDatagram(1L, 0, idr)))

        runBlocking {
            val frame = pipeline.frames.receive()
            assertEquals(1L, frame.frameIndex)
            assertArrayEquals(idr, frame.data)
            assertTrue(frame.isKeyFrame)
        }
    }

    @Test
    fun `a datagram that completes nothing publishes nothing`() {
        val pipeline = pipeline()
        val partial = VideoPacketFixtures.packet(
            sequenceNumber = 0,
            frameIndex = 1L,
            fecIndex = 0,
            dataShards = 2,
            payload = idr,
        )
        assertFalse(pipeline.onDatagram(partial))
        assertNull(pipeline.frames.tryReceive().getOrNull())
    }

    @Test
    fun `the queue keeps the newest frames and drops the oldest`() {
        val pipeline = pipeline(frameCapacity = 2)
        for (index in 1..5) {
            pipeline.onDatagram(
                frameDatagram(index.toLong(), index - 1, VideoPacketFixtures.bytes(index)),
            )
        }

        // Capacity two: frames 1, 2 and 3 were evicted, 4 and 5 survive, newest last.
        assertEquals(3L, pipeline.framesDroppedByBackpressure)
        runBlocking {
            assertEquals(4L, pipeline.frames.receive().frameIndex)
            assertEquals(5L, pipeline.frames.receive().frameIndex)
        }
        assertNull(pipeline.frames.tryReceive().getOrNull())
    }

    @Test
    fun `publishing never blocks even with no consumer at all`() {
        // The receive thread must be able to run indefinitely against a stalled decoder. If this
        // ever suspends or blocks, the test hangs rather than failing — which is the honest signal.
        val pipeline = pipeline(frameCapacity = 2)
        for (index in 1..500) {
            pipeline.onDatagram(
                frameDatagram(
                    index.toLong(),
                    index - 1,
                    VideoPacketFixtures.bytes(index and 0xFF),
                ),
            )
        }

        assertEquals(498L, pipeline.framesDroppedByBackpressure)
        assertEquals(500L, pipeline.stats().framesCompleted)
        assertEquals(500L, pipeline.stats().packetsReceived)
    }

    @Test
    fun `every backpressure eviction is reported as a drop that asks for an IDR`() {
        val pipeline = pipeline(frameCapacity = 1)
        pipeline.onDatagram(frameDatagram(1L, 0, nonIdr))
        pipeline.onDatagram(frameDatagram(2L, 1, nonIdr))

        assertEquals(1L, pipeline.framesDroppedByBackpressure)
        val dropped = pipeline.events.tryReceive().getOrNull() as VideoStreamEvent.FrameDropped
        assertEquals(1L, dropped.frameIndex)
        assertEquals(FrameDropReason.QUEUE_OVERFLOW, dropped.reason)
        assertTrue(dropped.requestsIdr)
    }

    @Test
    fun `loss events reach the event channel`() {
        val pipeline = pipeline()
        // Frame 1 needs two shards and only gets one; frame 2 then proves it dead.
        pipeline.onDatagram(
            VideoPacketFixtures.packet(
                sequenceNumber = 0,
                frameIndex = 1L,
                fecIndex = 0,
                dataShards = 2,
                payload = nonIdr,
            ),
        )
        pipeline.onDatagram(frameDatagram(2L, 2, nonIdr))

        val event = pipeline.events.tryReceive().getOrNull() as VideoStreamEvent.FrameDropped
        assertEquals(1L, event.frameIndex)
        assertEquals(FrameDropReason.INCOMPLETE, event.reason)
        assertTrue(event.requestsIdr)
    }

    @Test
    fun `stats are published after every datagram`() {
        val pipeline = pipeline()
        assertEquals(0L, pipeline.stats().packetsReceived)

        pipeline.onDatagram(frameDatagram(1L, 0, idr))
        assertEquals(1L, pipeline.stats().packetsReceived)
        assertEquals(1L, pipeline.stats().framesCompleted)
    }

    @Test
    fun `closing ends a consumer's loop`() {
        val pipeline = pipeline()
        pipeline.onDatagram(frameDatagram(1L, 0, idr))
        pipeline.close()

        val received = ArrayList<Long>()
        runBlocking {
            try {
                while (true) received.add(pipeline.frames.receive().frameIndex)
            } catch (expected: ClosedReceiveChannelException) {
                // The consumer's loop ends here, which is the point.
            }
        }
        assertEquals(listOf(1L), received)
    }

    @Test
    fun `publishing after close is silently ignored rather than throwing`() {
        // Teardown is not perfectly synchronised with the receive thread (architecture §3 rule 4
        // closes the socket to unblock it), so a datagram in flight must not crash it.
        val pipeline = pipeline()
        pipeline.close()
        pipeline.onDatagram(frameDatagram(1L, 0, idr))
        assertEquals(1L, pipeline.stats().framesCompleted)
    }

    @Test
    fun `capacity is the two decode units the architecture mandates`() {
        assertEquals(2, RtpVideoConstants.DECODE_QUEUE_CAPACITY)
    }
}
