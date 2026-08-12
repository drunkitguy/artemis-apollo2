package com.voidlink.android.media

import com.voidlink.android.data.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec selection, which is the part of the decode path most likely to be wrong on hardware we do
 * not own and the only part CI can check.
 *
 * The AV1 cases carry the most weight: `MediaCodecList` returns Google's software AV1 decoder on
 * devices with no AV1 hardware, and selecting it would produce a stream the user experiences as a
 * bad network rather than as a bad codec choice.
 */
class DecoderSelectorTest {

    private fun candidate(
        name: String,
        codec: VideoCodecType,
        hardware: Boolean = true,
        size: Boolean = true,
        rate: Boolean = true,
        tenBit: Boolean = false,
    ): DecoderCandidate = DecoderCandidate(
        name = name,
        codec = codec,
        hardwareAccelerated = hardware,
        supportsRequestedSize = size,
        supportsRequestedFrameRate = rate,
        supportsTenBit = tenBit,
        maxWidth = if (size) 3840 else 1280,
        maxHeight = if (size) 2160 else 720,
        maxFrameRate = if (rate) 120 else 30,
        maxFrameRateAtRequestedSize = if (rate) 120 else 30,
    )

    private fun request(
        preferredCodec: VideoCodec = VideoCodec.AUTO,
        hdr: Boolean = false,
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Int = 60,
    ): VideoFormatRequest = VideoFormatRequest(
        width = width,
        height = height,
        frameRate = frameRate,
        hdr = hdr,
        preferredCodec = preferredCodec,
    )

    private fun selected(result: DecoderSelectionResult): DecoderChoice {
        assertTrue("expected a decoder, got $result", result is DecoderSelectionResult.Selected)
        return (result as DecoderSelectionResult.Selected).choice
    }

    private val hardwareAv1 = candidate("c2.qti.av1.decoder", VideoCodecType.AV1, tenBit = true)
    private val softwareAv1 = candidate("c2.android.av1-decoder", VideoCodecType.AV1, hardware = false)
    private val hardwareHevc = candidate("c2.qti.hevc.decoder", VideoCodecType.HEVC, tenBit = true)
    private val hardwareH264 = candidate("c2.qti.avc.decoder", VideoCodecType.H264)

    // ---- nothing usable ----------------------------------------------------------------------

    @Test
    fun `an empty codec list is reported as no decoders at all`() {
        val result = DecoderSelector.select(emptyList(), request())

        assertTrue(result is DecoderSelectionResult.NoDecoder)
        assertTrue((result as DecoderSelectionResult.NoDecoder).summary.contains("no video decoders"))
    }

    @Test
    fun `a resolution nothing supports names the resolution and the fix`() {
        val result = DecoderSelector.select(
            listOf(candidate("c2.qti.hevc.decoder", VideoCodecType.HEVC, size = false)),
            request(width = 3840, height = 2160),
        )

        val failure = result as DecoderSelectionResult.NoDecoder
        assertTrue(failure.summary.contains("3840×2160"))
        assertTrue(failure.summary.contains("Lower the resolution"))
    }

    // ---- AV1, hardware versus software -------------------------------------------------------

    @Test
    fun `a software-only AV1 decoder is never selected and the reason is said out loud`() {
        val result = DecoderSelector.select(
            listOf(softwareAv1, hardwareHevc, hardwareH264),
            request(preferredCodec = VideoCodec.AV1),
        )

        val choice = selected(result)
        assertEquals(VideoCodecType.HEVC, choice.format.codec)
        assertTrue(
            "expected an explanation, got ${choice.notes}",
            choice.notes.any { it.contains("no hardware AV1 decoder") && it.contains("HEVC") },
        )
    }

    @Test
    fun `software AV1 as the only decoder is a failure, not a slow stream`() {
        val result = DecoderSelector.select(listOf(softwareAv1), request(preferredCodec = VideoCodec.AV1))

        val failure = result as DecoderSelectionResult.NoDecoder
        assertTrue(failure.summary.contains("software"))
        assertTrue(failure.summary.contains("AV1"))
    }

    @Test
    fun `hardware AV1 is chosen when explicitly preferred`() {
        val result = DecoderSelector.select(
            listOf(hardwareAv1, hardwareHevc, hardwareH264),
            request(preferredCodec = VideoCodec.AV1),
        )

        val choice = selected(result)
        assertEquals(VideoCodecType.AV1, choice.format.codec)
        assertEquals("c2.qti.av1.decoder", choice.candidate.name)
        assertTrue(choice.notes.isEmpty())
    }

    @Test
    fun `a device with no AV1 decoder at all says so`() {
        val result = DecoderSelector.select(
            listOf(hardwareHevc, hardwareH264),
            request(preferredCodec = VideoCodec.AV1),
        )

        val choice = selected(result)
        assertEquals(VideoCodecType.HEVC, choice.format.codec)
        assertTrue(choice.notes.any { it.contains("no AV1 decoder at all") })
    }

    // ---- the Auto ladder ---------------------------------------------------------------------

    @Test
    fun `Auto leads with AV1 when hardware AV1 covers the size and the rate`() {
        val result = DecoderSelector.select(
            listOf(hardwareH264, hardwareHevc, hardwareAv1),
            request(preferredCodec = VideoCodec.AUTO),
        )

        assertEquals(VideoCodecType.AV1, selected(result).format.codec)
    }

    @Test
    fun `Auto drops AV1 behind HEVC when AV1 does not advertise the frame rate`() {
        val result = DecoderSelector.select(
            listOf(candidate("c2.qti.av1.decoder", VideoCodecType.AV1, rate = false), hardwareHevc),
            request(preferredCodec = VideoCodec.AUTO, frameRate = 120),
        )

        assertEquals(VideoCodecType.HEVC, selected(result).format.codec)
    }

    @Test
    fun `H264 is the last resort, never chosen ahead of AV1`() {
        val result = DecoderSelector.select(
            listOf(hardwareH264, candidate("c2.qti.av1.decoder", VideoCodecType.AV1, rate = false)),
            request(preferredCodec = VideoCodec.AUTO, frameRate = 120),
        )

        assertEquals(VideoCodecType.AV1, selected(result).format.codec)
    }

    @Test
    fun `Auto falls all the way to H264 when it is the only hardware decoder`() {
        val result = DecoderSelector.select(
            listOf(softwareAv1, hardwareH264),
            request(preferredCodec = VideoCodec.AUTO),
        )

        assertEquals(VideoCodecType.H264, selected(result).format.codec)
    }

    // ---- explicit preferences ----------------------------------------------------------------

    @Test
    fun `an explicit H264 preference is not silently upgraded`() {
        val result = DecoderSelector.select(
            listOf(hardwareAv1, hardwareHevc, hardwareH264),
            request(preferredCodec = VideoCodec.H264),
        )

        assertEquals(VideoCodecType.H264, selected(result).format.codec)
    }

    @Test
    fun `an H264 preference on a device with no H264 decoder points at the Auto setting`() {
        val result = DecoderSelector.select(listOf(hardwareHevc), request(preferredCodec = VideoCodec.H264))

        val failure = result as DecoderSelectionResult.NoDecoder
        assertTrue(failure.summary.contains("Preferred Codec"))
        assertTrue(failure.summary.contains("Auto"))
    }

    @Test
    fun `an HEVC preference relaxes to AV1 before H264`() {
        val result = DecoderSelector.select(
            listOf(hardwareAv1, hardwareH264),
            request(preferredCodec = VideoCodec.HEVC),
        )

        val choice = selected(result)
        assertEquals(VideoCodecType.AV1, choice.format.codec)
        assertTrue(choice.notes.any { it.contains("HEVC is not available") })
    }

    // ---- software fallback -------------------------------------------------------------------

    @Test
    fun `a software HEVC decoder is used only as a last resort, and never silently`() {
        val result = DecoderSelector.select(
            listOf(candidate("c2.android.hevc.decoder", VideoCodecType.HEVC, hardware = false)),
            request(),
        )

        val choice = selected(result)
        assertEquals(VideoCodecType.HEVC, choice.format.codec)
        assertTrue(choice.notes.any { it.contains("software") && it.contains("high latency") })
    }

    @Test
    fun `hardware always beats software for the same codec`() {
        val result = DecoderSelector.select(
            listOf(
                candidate("c2.android.hevc.decoder", VideoCodecType.HEVC, hardware = false),
                hardwareHevc,
            ),
            request(preferredCodec = VideoCodec.HEVC),
        )

        assertEquals("c2.qti.hevc.decoder", selected(result).candidate.name)
        assertTrue(selected(result).notes.isEmpty())
    }

    // ---- 10-bit and HDR ----------------------------------------------------------------------

    @Test
    fun `HDR survives on a 10-bit AV1 decoder`() {
        val result = DecoderSelector.select(listOf(hardwareAv1), request(hdr = true))

        val choice = selected(result)
        assertEquals(VideoCodecType.AV1, choice.format.codec)
        assertTrue(choice.format.hdr)
        assertTrue(choice.notes.isEmpty())
    }

    @Test
    fun `an 8-bit-only AV1 decoder loses HDR to a 10-bit HEVC decoder`() {
        val eightBitAv1 = candidate("c2.qti.av1.decoder", VideoCodecType.AV1, tenBit = false)

        val result = DecoderSelector.select(listOf(eightBitAv1, hardwareHevc), request(hdr = true))

        val choice = selected(result)
        assertEquals(VideoCodecType.HEVC, choice.format.codec)
        assertTrue(choice.format.hdr)
    }

    @Test
    fun `HDR is cleared, and said to be cleared, when nothing does 10-bit`() {
        val result = DecoderSelector.select(
            listOf(candidate("c2.qti.hevc.decoder", VideoCodecType.HEVC, tenBit = false)),
            request(hdr = true),
        )

        val choice = selected(result)
        assertFalse(choice.format.hdr)
        assertTrue(choice.notes.any { it.contains("10-bit") && it.contains("SDR") })
    }

    @Test
    fun `HDR on H264 explains that the protocol has no 10-bit H264`() {
        val result = DecoderSelector.select(
            listOf(hardwareH264),
            request(preferredCodec = VideoCodec.H264, hdr = true),
        )

        val choice = selected(result)
        assertFalse(choice.format.hdr)
        assertTrue(choice.notes.any { it.contains("no 10-bit H.264") })
    }

    // ---- frame rate --------------------------------------------------------------------------

    @Test
    fun `a decoder that handles the size but not the rate is used, with a warning`() {
        val result = DecoderSelector.select(
            listOf(candidate("c2.qti.hevc.decoder", VideoCodecType.HEVC, rate = false)),
            request(frameRate = 120),
        )

        val choice = selected(result)
        assertEquals(VideoCodecType.HEVC, choice.format.codec)
        assertTrue(choice.notes.any { it.contains("120 fps") && it.contains("dropped") })
    }

    @Test
    fun `the chosen format carries the requested dimensions through`() {
        val result = DecoderSelector.select(listOf(hardwareHevc), request(width = 2560, height = 1440))

        val format = selected(result).format
        assertEquals(2560, format.width)
        assertEquals(1440, format.height)
        assertEquals(60, format.frameRate)
    }

    @Test
    fun `an invalid request is rejected rather than configured`() {
        val result = DecoderSelector.select(listOf(hardwareHevc), request(width = 0, height = 0))

        assertTrue(result is DecoderSelectionResult.NoDecoder)
    }

    @Test
    fun `every result carries the device capability report`() {
        val result = DecoderSelector.select(listOf(softwareAv1, hardwareHevc), request())

        val inventory = selected(result).inventory
        assertEquals(3, inventory.size)
        val av1 = inventory.single { it.codec == VideoCodecType.AV1 }
        assertTrue(av1.available)
        assertFalse(av1.hardwareAccelerated)
        assertFalse(av1.usableForRealTime)
    }
}
