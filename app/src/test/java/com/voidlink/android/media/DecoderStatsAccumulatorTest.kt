package com.voidlink.android.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stats aggregation.
 *
 * Decode time is the number that decides whether a codec choice is viable on a given device, so
 * the arithmetic behind it is worth pinning down exactly rather than eyeballing on a phone.
 */
class DecoderStatsAccumulatorTest {

    @Test
    fun `an untouched accumulator reports zeroes`() {
        val stats = DecoderStatsAccumulator().snapshot(1_000L)

        assertEquals(0L, stats.framesDecoded)
        assertEquals(0L, stats.framesDropped)
        assertEquals(0f, stats.renderedFps, 0.0001f)
        assertEquals(0f, stats.averageDecodeTimeMs, 0.0001f)
    }

    @Test
    fun `frame rates are per second over the interval since the last snapshot`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 0L)
        repeat(30) { accumulator.onFrameDecoded(0L, 3_000L) }
        repeat(30) { accumulator.onFrameSubmitted(0L, 1_000) }

        val stats = accumulator.snapshot(500L)

        assertEquals(60f, stats.renderedFps, 0.001f)
        assertEquals(60f, stats.submittedFps, 0.001f)
    }

    @Test
    fun `bitrate is computed from submitted bytes over the interval`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 0L)
        // 60 frames of 50 kB in one second = 24 Mbps.
        repeat(60) { accumulator.onFrameSubmitted(0L, 50_000) }

        val stats = accumulator.snapshot(1_000L)

        assertEquals(24f, stats.bitrateMbps, 0.01f)
    }

    @Test
    fun `decode time averages over the interval and reports the interval peak`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 0L)
        accumulator.onFrameDecoded(0L, 2_000L)
        accumulator.onFrameDecoded(0L, 4_000L)
        accumulator.onFrameDecoded(0L, 12_000L)

        val stats = accumulator.snapshot(500L)

        assertEquals(6f, stats.averageDecodeTimeMs, 0.001f)
        assertEquals(12f, stats.peakDecodeTimeMs, 0.001f)
    }

    @Test
    fun `an unmatched decode does not drag the average towards zero`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 0L)
        accumulator.onFrameDecoded(0L, 4_000L)
        // The frames right after a flush cannot be matched to a submission.
        accumulator.onFrameDecoded(0L, 0L)
        accumulator.onFrameDecoded(0L, -1L)

        val stats = accumulator.snapshot(500L)

        assertEquals(4f, stats.averageDecodeTimeMs, 0.001f)
        assertEquals(3L, stats.framesDecoded)
    }

    @Test
    fun `totals are lifetime, rates are per interval`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 0L)
        repeat(30) { accumulator.onFrameDecoded(0L, 1_000L) }
        val first = accumulator.snapshot(500L)

        repeat(15) { accumulator.onFrameDecoded(600L, 1_000L) }
        val second = accumulator.snapshot(1_000L)

        assertEquals(30L, first.framesDecoded)
        assertEquals(60f, first.renderedFps, 0.001f)

        assertEquals(45L, second.framesDecoded)
        assertEquals(30f, second.renderedFps, 0.001f)
    }

    @Test
    fun `dropped frames accumulate for the lifetime of the session`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 0L)
        accumulator.onFramesDropped(0L, 3)
        accumulator.onFramesDropped(0L)
        accumulator.onFramesDropped(0L, 0)
        accumulator.onFramesDropped(0L, -5)

        assertEquals(4L, accumulator.snapshot(500L).framesDropped)
        assertEquals(4L, accumulator.snapshot(1_000L).framesDropped)
    }

    @Test
    fun `two snapshots in the same millisecond report zero rates rather than infinity`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 500L)
        accumulator.onFrameDecoded(500L, 1_000L)

        val stats = accumulator.snapshot(500L)

        assertEquals(0f, stats.renderedFps, 0.0001f)
        assertEquals(0f, stats.bitrateMbps, 0.0001f)
        assertEquals(1L, stats.framesDecoded)
    }

    @Test
    fun `reset clears the lifetime totals too`() {
        val accumulator = DecoderStatsAccumulator(startMillis = 0L)
        accumulator.onFrameDecoded(0L, 1_000L)
        accumulator.onFramesDropped(0L, 5)

        accumulator.reset(1_000L)
        val stats = accumulator.snapshot(2_000L)

        assertEquals(0L, stats.framesDecoded)
        assertEquals(0L, stats.framesDropped)
    }
}
