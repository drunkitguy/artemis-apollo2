package com.voidlink.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the recommendation engine.
 *
 * The rules here are the whole point of the feature, and every one of them is a judgement that
 * someone will later want to argue with — so each is pinned by a test that names the reasoning as
 * well as the number.
 */
class BitrateAdvisorTest {

    private fun link(
        jitterMs: Double = 2.0,
        lossPercent: Double = 0.0,
        driftMs: Double = 0.0,
        succeeded: Int = 20,
    ) = LinkQuality(
        requested = 20,
        succeeded = succeeded,
        minMs = 6.0,
        medianMs = 9.0,
        p95Ms = 15.0,
        jitterMs = jitterMs,
        lossPercent = lossPercent,
        driftMs = driftMs,
    )

    // ---- Baselines ---------------------------------------------------------------------------

    @Test
    fun `4K is four times 1080p, matching the reference client's pixel-linear table`() {
        val hd = BitrateAdvisor.baselineKbpsAt60(StreamResolution.RES_1080P)
        val uhd = BitrateAdvisor.baselineKbpsAt60(StreamResolution.RES_2160P)

        assertEquals(4 * hd, uhd)
    }

    @Test
    fun `frame rate scaling is anchored at 1x for 60fps and 1_5x for 120fps`() {
        assertEquals(1.0, BitrateAdvisor.frameRateFactor(FrameRate.FPS_60), TOLERANCE)
        assertEquals(1.5, BitrateAdvisor.frameRateFactor(FrameRate.FPS_120), TOLERANCE)
        assertEquals(1.25, BitrateAdvisor.frameRateFactor(FrameRate.FPS_90), TOLERANCE)
        assertEquals(0.75, BitrateAdvisor.frameRateFactor(FrameRate.FPS_30), TOLERANCE)
    }

    @Test
    fun `modern codecs are preferred and cost meaningfully less than H264`() {
        val h264 = BitrateAdvisor.codecFactor(VideoCodec.H264)

        assertEquals(1.0, h264, TOLERANCE)
        // 30-40% less for the same picture, which is what makes them worth the decode cost.
        assertTrue(BitrateAdvisor.codecFactor(VideoCodec.HEVC) <= 0.70)
        assertTrue(BitrateAdvisor.codecFactor(VideoCodec.AV1) <= 0.70)
        // Auto normally lands on a modern codec — H.264 is the reference client's last resort — so
        // it must not be priced as though H.264 were the likely outcome.
        assertTrue(BitrateAdvisor.codecFactor(VideoCodec.AUTO) < h264)
    }

    @Test
    fun `the settings alone decide the number when nothing has been measured`() {
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P),
            link = null,
            throughput = null,
        )

        assertEquals(30_000, advice.recommendedKbps)
        assertEquals(BitrateLimit.CODEC_BASELINE, advice.limitedBy)
        // Nothing was measured, so the UI has to say the number is unverified.
        assertFalse(advice.confident)
    }

    @Test
    fun `HDR and 4-4-4 chroma both raise the target`() {
        val plain = BitrateAdvisor.recommend(
            StreamSettings(codec = VideoCodec.HEVC, resolution = StreamResolution.RES_1080P),
            null,
            null,
        )
        val hdr = BitrateAdvisor.recommend(
            StreamSettings(
                codec = VideoCodec.HEVC,
                resolution = StreamResolution.RES_1080P,
                hdrEnabled = true,
            ),
            null,
            null,
        )
        val chroma = BitrateAdvisor.recommend(
            StreamSettings(
                codec = VideoCodec.HEVC,
                resolution = StreamResolution.RES_1080P,
                yuv444Enabled = true,
            ),
            null,
            null,
        )

        assertTrue(hdr.recommendedKbps > plain.recommendedKbps)
        assertTrue(chroma.recommendedKbps > hdr.recommendedKbps)
    }

    // ---- The headroom rule -------------------------------------------------------------------

    @Test
    fun `a TCP measurement caps the recommendation at 55 percent of what was measured`() {
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_2160P),
            link = link(),
            throughput = ThroughputEvidence.Sustained(
                megabitsPerSecond = 100.0,
                bytes = 125_000_000L,
                seconds = 10.0,
            ),
        )

        // 100 Mbps measured, 55% of it available, and 4K60 would have wanted 120.
        assertEquals(55_000, advice.recommendedKbps)
        assertEquals(BitrateLimit.MEASURED_LINK, advice.limitedBy)
        assertTrue(advice.confident)
    }

    @Test
    fun `the requested bitrate is compared against throughput with no inflation factor`() {
        // The bitrate setting is the whole session's budget on the wire, not a video-only figure,
        // so nothing is added to it before the comparison. 60 Mbps measured leaves 33 Mbps of
        // budget, which comfortably covers a 30 Mbps target — an engine that inflated the target by
        // a transport-overhead factor would wrongly clamp this to about 26.5 Mbps.
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P),
            link = link(),
            throughput = ThroughputEvidence.Sustained(
                megabitsPerSecond = 60.0,
                bytes = 75_000_000L,
                seconds = 10.0,
            ),
        )

        assertEquals(30_000, advice.recommendedKbps)
        assertEquals(BitrateLimit.CODEC_BASELINE, advice.limitedBy)
    }

    @Test
    fun `the overhead split is reported the honest way round - out of the number, not on top`() {
        val advice = BitrateAdvisor.recommend(
            StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P),
            null,
            null,
        )

        assertTrue(advice.videoKbps < advice.recommendedKbps)
        assertEquals(24_000, advice.videoKbps)
    }

    // ---- Paced UDP evidence ------------------------------------------------------------------

    @Test
    fun `a clean paced UDP run at the requested rate endorses that rate`() {
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P),
            link = link(),
            throughput = ThroughputEvidence.Loaded(
                targetMbps = 30.0,
                receivedMbps = 29.9,
                lossPercent = 0.1,
                jitterMs = 1.2,
                packets = 26_000L,
            ),
        )

        assertEquals(30_000, advice.recommendedKbps)
        assertEquals(BitrateLimit.CODEC_BASELINE, advice.limitedBy)
    }

    @Test
    fun `loss during a paced run cuts the budget sharply rather than proportionally`() {
        // One lost packet costs a whole frame plus the keyframe that follows, so loss is not
        // proportional damage and the response must not be proportional either.
        assertEquals(1.0, BitrateAdvisor.loadedFactor(0.2), TOLERANCE)
        assertEquals(0.75, BitrateAdvisor.loadedFactor(1.5), TOLERANCE)
        assertEquals(0.50, BitrateAdvisor.loadedFactor(6.0), TOLERANCE)
        assertEquals(0.30, BitrateAdvisor.loadedFactor(25.0), TOLERANCE)

        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P),
            link = link(),
            throughput = ThroughputEvidence.Loaded(
                targetMbps = 30.0,
                receivedMbps = 24.0,
                lossPercent = 6.0,
                jitterMs = 9.0,
                packets = 21_000L,
            ),
        )

        assertEquals(15_000, advice.recommendedKbps)
        assertEquals(BitrateLimit.MEASURED_LINK, advice.limitedBy)
    }

    // ---- Link quality ------------------------------------------------------------------------

    @Test
    fun `jitter alone lowers the recommendation and says so`() {
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P),
            link = link(jitterMs = 15.0),
            throughput = null,
        )

        assertEquals(24_000, advice.recommendedKbps)
        assertEquals(BitrateLimit.LINK_QUALITY, advice.limitedBy)
        assertTrue(advice.reasons.any { it.contains("Jitter") })
    }

    @Test
    fun `severe jitter cuts harder than mild jitter`() {
        val settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P)
        val mild = BitrateAdvisor.recommend(settings, link(jitterMs = 15.0), null)
        val severe = BitrateAdvisor.recommend(settings, link(jitterMs = 40.0), null)

        assertTrue(severe.recommendedKbps < mild.recommendedKbps)
    }

    @Test
    fun `loss and a degrading link compound with jitter`() {
        val settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P)
        val one = BitrateAdvisor.recommend(settings, link(jitterMs = 15.0), null)
        val three = BitrateAdvisor.recommend(
            settings,
            link(jitterMs = 15.0, lossPercent = 4.0, driftMs = 40.0),
            null,
        )

        assertTrue(three.recommendedKbps < one.recommendedKbps)
        assertEquals(BitrateLimit.LINK_QUALITY, three.limitedBy)
    }

    @Test
    fun `a link test with too few answers is ignored rather than believed`() {
        val settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P)
        val advice = BitrateAdvisor.recommend(
            settings,
            link(jitterMs = 90.0, lossPercent = 90.0, succeeded = 1),
            null,
        )

        assertEquals(30_000, advice.recommendedKbps)
        assertEquals(BitrateLimit.CODEC_BASELINE, advice.limitedBy)
        assertTrue(advice.reasons.any { it.contains("not enough to measure") })
    }

    // ---- Clamping and rounding ---------------------------------------------------------------

    @Test
    fun `the recommendation never exceeds the app's own ceiling`() {
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(
                codec = VideoCodec.H264,
                resolution = StreamResolution.RES_2160P,
                frameRate = FrameRate.FPS_120,
                hdrEnabled = true,
                yuv444Enabled = true,
            ),
            link = null,
            throughput = null,
        )

        assertEquals(StreamSettings.BITRATE_MAX_KBPS, advice.recommendedKbps)
        assertEquals(BitrateLimit.CLIENT_CEILING, advice.limitedBy)
    }

    @Test
    fun `a barely usable link still produces a value the slider can hold`() {
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.RES_1080P),
            link = link(),
            throughput = ThroughputEvidence.Sustained(
                megabitsPerSecond = 0.5,
                bytes = 625_000L,
                seconds = 10.0,
            ),
        )

        assertEquals(StreamSettings.BITRATE_MIN_KBPS, advice.recommendedKbps)
        assertEquals(BitrateLimit.MEASURED_LINK, advice.limitedBy)
    }

    @Test
    fun `every recommendation lands on a step the bitrate slider can actually store`() {
        val settings = StreamSettings(codec = VideoCodec.AUTO, resolution = StreamResolution.RES_1440P)
        for (measured in 5..200) {
            val advice = BitrateAdvisor.recommend(
                settings,
                link(jitterMs = 11.0),
                ThroughputEvidence.Sustained(measured.toDouble(), 0L, 10.0),
            )
            assertTrue(
                "recommendation for $measured Mbps is off the 500 kbps grid",
                advice.recommendedKbps % BitrateAdvisor.ROUNDING_KBPS == 0,
            )
            assertTrue(advice.recommendedKbps >= StreamSettings.BITRATE_MIN_KBPS)
            assertTrue(advice.recommendedKbps <= StreamSettings.BITRATE_MAX_KBPS)
        }
    }

    // ---- The explanation ---------------------------------------------------------------------

    @Test
    fun `the explanation always names what the number came from`() {
        val advice = BitrateAdvisor.recommend(
            settings = StreamSettings(codec = VideoCodec.HEVC, resolution = StreamResolution.RES_1080P),
            link = link(),
            throughput = ThroughputEvidence.Sustained(80.0, 100_000_000L, 10.0),
        )

        assertTrue(advice.reasons.size >= 3)
        // What the picture asked for.
        assertTrue(advice.reasons.any { it.contains("1080p60") })
        // What the link was measured to do, and the headroom rule applied to it.
        assertTrue(advice.reasons.any { it.contains("55%") })
        // What the final number actually means.
        assertTrue(advice.reasons.any { it.contains("budget on the network") })
        assertTrue(advice.headline.isNotBlank())
    }

    @Test
    fun `an unmeasured recommendation admits that it is unmeasured`() {
        val advice = BitrateAdvisor.recommend(StreamSettings(), null, null)

        assertFalse(advice.confident)
        assertTrue(advice.reasons.any { it.contains("No throughput was measured") })
    }

    @Test
    fun `Native resolution is priced as 1080p and says so`() {
        val advice = BitrateAdvisor.recommend(
            StreamSettings(codec = VideoCodec.H264, resolution = StreamResolution.NATIVE),
            null,
            null,
        )

        assertEquals(30_000, advice.recommendedKbps)
        assertTrue(advice.reasons.any { it.contains("assumes 1080p") })
    }

    private companion object {
        const val TOLERANCE = 0.0001
    }
}
