package com.voidlink.android.protocol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receive-thread seam of architecture §3, and the one policy that makes it an audio seam rather
 * than a generic queue: **evict the oldest, never the newest, and count every eviction.**
 */
class AudioSamplePipelineTest {

    private fun pipeline(sampleCapacity: Int = 4) = AudioSamplePipeline(
        config = AudioDepacketizerConfig(initialResyncDropMs = 0),
        sampleCapacity = sampleCapacity,
    )

    private fun AudioSamplePipeline.data(sequence: Int): Int {
        val packet = AudioPacketFixtures.dataPacket(sequence)
        return onDatagram(packet, packet.size)
    }

    private fun drain(pipeline: AudioSamplePipeline): List<Int> {
        val received = mutableListOf<Int>()
        while (true) {
            val result = pipeline.samples.tryReceive()
            val sample = result.getOrNull() ?: return received
            received += sample.sequenceNumber
        }
    }

    @Test
    fun `samples reach the channel in stream order`() {
        val pipeline = pipeline()
        pipeline.data(100)

        for (sequence in 104..107) pipeline.data(sequence)

        assertEquals(listOf(104, 105, 106, 107), drain(pipeline))
    }

    @Test
    fun `a full queue evicts the oldest sample, so playback resumes at live audio`() {
        // Keeping the oldest and rejecting the newest would mean the consumer eventually plays a
        // fixed distance behind live and never catches up. Audio latency does not recover on its
        // own, so the only correct eviction is the oldest.
        val pipeline = pipeline(sampleCapacity = 2)
        pipeline.data(100)

        for (sequence in 104..111) pipeline.data(sequence)

        assertEquals(listOf(110, 111), drain(pipeline))
        assertEquals(6L, pipeline.samplesDroppedByBackpressure)
    }

    @Test
    fun `evictions are counted rather than hidden`() {
        val pipeline = pipeline(sampleCapacity = 1)
        pipeline.data(100)
        assertEquals(0L, pipeline.samplesDroppedByBackpressure)

        pipeline.data(104)
        pipeline.data(105)

        assertEquals(1L, pipeline.samplesDroppedByBackpressure)
    }

    @Test
    fun `the depacketizer's counters are republished after every datagram`() {
        val pipeline = pipeline()
        assertEquals(AudioStreamStats.EMPTY, pipeline.stats())

        pipeline.data(100)

        assertEquals(1L, pipeline.stats().packetsReceived)
        assertTrue(pipeline.stats().sawTraffic)
    }

    @Test
    fun `an unparseable datagram is counted and releases nothing`() {
        val pipeline = pipeline()

        assertEquals(0, pipeline.onDatagram(ByteArray(4), 4))

        assertEquals(1L, pipeline.stats().packetsRejected)
        assertNull(pipeline.samples.tryReceive().getOrNull())
    }

    @Test
    fun `events reach their own channel`() {
        val pipeline = pipeline()
        pipeline.data(100)
        for (sequence in 104..107) pipeline.data(sequence)

        // 108..111 are lost, 112 opens the next block: a concealed gap and one PacketsLost notice.
        pipeline.data(112)

        val event = pipeline.events.tryReceive().getOrNull()
        assertNotNull(event)
        assertTrue(event is AudioStreamEvent.PacketsLost)
    }

    @Test
    fun `closing ends the consumer's loop`() {
        val pipeline = pipeline()
        pipeline.data(100)
        pipeline.data(104)

        pipeline.close()

        // The buffered sample is still delivered; the channel is closed behind it.
        assertEquals(104, pipeline.samples.tryReceive().getOrNull()?.sequenceNumber)
        assertTrue(pipeline.samples.tryReceive().isClosed)
    }

    @Test
    fun `offering into a closed channel neither throws nor spins`() {
        val pipeline = pipeline(sampleCapacity = 1)
        pipeline.data(100)
        pipeline.close()

        // The receive thread may still be mid-datagram when teardown closes the channel.
        for (sequence in 104..107) pipeline.data(sequence)

        assertTrue(pipeline.samples.tryReceive().isClosed)
    }

    @Test
    fun `an idle tick drains a block whose deadline has passed`() {
        var nanos = 0L
        val pipeline = AudioSamplePipeline(
            config = AudioDepacketizerConfig(initialResyncDropMs = 0),
            clock = { nanos },
        )
        val sync = AudioPacketFixtures.dataPacket(100)
        pipeline.onDatagram(sync, sync.size)
        // 104 never arrives; 105..107 do.
        for (sequence in 105..107) {
            val packet = AudioPacketFixtures.dataPacket(sequence)
            pipeline.onDatagram(packet, packet.size)
        }
        assertEquals(0, drain(pipeline).size)

        nanos = 31_000_000L
        assertEquals(4, pipeline.onIdle())

        assertEquals(listOf(104, 105, 106, 107), drain(pipeline))
    }

    @Test
    fun `capacity must be positive`() {
        val rejected = runCatching { AudioSamplePipeline(sampleCapacity = 0) }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `onDatagram returns without blocking even when nothing is consuming`() {
        // The receive thread is the only thing draining the socket buffer. If a full queue could
        // make onDatagram wait, every moment the decoder was busy would cost kernel-dropped packets.
        val pipeline = pipeline(sampleCapacity = 1)
        pipeline.data(100)

        val before = System.nanoTime()
        for (sequence in 104..203) pipeline.data(sequence)
        val elapsedMs = (System.nanoTime() - before) / 1_000_000L

        assertTrue("100 datagrams took ${elapsedMs}ms", elapsedMs < 500L)
        assertEquals(99L, pipeline.samplesDroppedByBackpressure)
    }
}
