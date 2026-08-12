package com.voidlink.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the statistics [LinkQuality] derives from a burst of samples.
 *
 * These are the figures the whole feature rests on: if the jitter estimate or the loss count is
 * wrong, the recommendation built on top of them is confidently wrong, which is worse than having
 * no recommendation at all.
 */
class LinkQualityTest {

    private fun ok(vararg millis: Long): List<LinkSample> = millis.map { LinkSample(it) }

    @Test
    fun `median averages the two middle values for an even sample count`() {
        val quality = LinkQuality.from(ok(10, 20, 30, 40))

        assertEquals(25.0, quality.medianMs, TOLERANCE)
        assertEquals(10.0, quality.minMs, TOLERANCE)
    }

    @Test
    fun `median takes the middle value for an odd sample count`() {
        val quality = LinkQuality.from(ok(30, 10, 20))

        assertEquals(20.0, quality.medianMs, TOLERANCE)
    }

    @Test
    fun `p95 is a value that was actually measured, not an interpolation`() {
        // Nearest rank over twenty samples puts p95 at the nineteenth, which is 190.
        val samples = ok(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160, 170, 180, 190, 200)

        assertEquals(190.0, LinkQuality.from(samples).p95Ms, TOLERANCE)
    }

    @Test
    fun `jitter is the mean gap between neighbouring samples in arrival order`() {
        // Deltas are 10, 10, 10 — a link that is slow but perfectly steady has low jitter, which is
        // exactly the distinction that matters for a stream.
        val quality = LinkQuality.from(ok(100, 110, 120, 130))

        assertEquals(10.0, quality.jitterMs, TOLERANCE)
    }

    @Test
    fun `a fast but erratic link scores worse jitter than a slow steady one`() {
        val steady = LinkQuality.from(ok(80, 80, 80, 80, 80, 80))
        val erratic = LinkQuality.from(ok(5, 45, 6, 50, 7, 44))

        assertTrue(erratic.jitterMs > steady.jitterMs)
        assertTrue(erratic.medianMs < steady.medianMs)
        assertEquals(LinkGrade.POOR, erratic.grade)
    }

    @Test
    fun `failed samples count as loss and are excluded from the latency figures`() {
        val samples = listOf(
            LinkSample(10),
            LinkSample(null, "timed out"),
            LinkSample(10),
            LinkSample(null, "timed out"),
        )

        val quality = LinkQuality.from(samples)

        assertEquals(4, quality.requested)
        assertEquals(2, quality.succeeded)
        assertEquals(50.0, quality.lossPercent, TOLERANCE)
        // A timeout must never be averaged in as though it were a very slow reply.
        assertEquals(10.0, quality.medianMs, TOLERANCE)
        assertEquals(10.0, quality.p95Ms, TOLERANCE)
    }

    @Test
    fun `a burst where nothing answered reports total loss and no latency`() {
        val quality = LinkQuality.from(List(5) { LinkSample(null, "refused") })

        assertEquals(0, quality.succeeded)
        assertEquals(100.0, quality.lossPercent, TOLERANCE)
        assertEquals(0.0, quality.medianMs, TOLERANCE)
        assertFalse(quality.isUsable)
        assertEquals(LinkGrade.POOR, quality.grade)
    }

    @Test
    fun `too few answers is reported as unusable rather than as a measurement`() {
        val quality = LinkQuality.from(
            listOf(LinkSample(9), LinkSample(null), LinkSample(null), LinkSample(null)),
        )

        assertFalse(quality.isUsable)
    }

    @Test
    fun `drift is positive when the link gets slower across the window`() {
        val quality = LinkQuality.from(ok(10, 10, 10, 10, 60, 60, 60, 60))

        assertEquals(50.0, quality.driftMs, TOLERANCE)
        assertTrue(quality.isDegrading)
        assertEquals("Getting slower", quality.stabilityLabel)
    }

    @Test
    fun `a steady window is not reported as degrading`() {
        val quality = LinkQuality.from(ok(12, 11, 13, 12, 12, 13, 11, 12))

        assertFalse(quality.isDegrading)
        assertEquals("Steady", quality.stabilityLabel)
    }

    @Test
    fun `a clean local link grades as excellent`() {
        val quality = LinkQuality.from(ok(4, 5, 4, 5, 4, 5, 4, 5, 4, 5))

        assertEquals(0.0, quality.lossPercent, TOLERANCE)
        assertEquals(LinkGrade.EXCELLENT, quality.grade)
    }

    @Test
    fun `a link with a slow tail is downgraded even when the median is fine`() {
        val quality = LinkQuality.from(ok(5, 5, 5, 5, 5, 5, 5, 5, 5, 120))

        assertEquals(5.0, quality.medianMs, TOLERANCE)
        assertEquals(LinkGrade.FAIR, quality.grade)
    }

    @Test
    fun `loss alone is enough to grade a link poor`() {
        val samples = ok(5, 5, 5, 5, 5, 5, 5, 5, 5) + listOf(LinkSample(null, "timed out"))

        assertEquals(LinkGrade.POOR, LinkQuality.from(samples).grade)
    }

    @Test
    fun `an empty burst degrades to zeroes rather than dividing by zero`() {
        val quality = LinkQuality.from(emptyList())

        assertEquals(0, quality.requested)
        assertEquals(0.0, quality.lossPercent, TOLERANCE)
        assertEquals(0.0, quality.jitterMs, TOLERANCE)
        assertFalse(quality.isUsable)
    }

    private companion object {
        const val TOLERANCE = 0.0001
    }
}
