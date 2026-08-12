package com.voidlink.android.media

import com.voidlink.android.data.FrameRate
import com.voidlink.android.data.StreamResolution
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.data.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings plus display size into a concrete format request.
 *
 * `Native` is the only resolution whose value depends on the hardware, and it is the one worth
 * testing: getting the orientation or the clamping wrong produces a stream that is subtly
 * stretched, or one the host refuses outright.
 */
class StreamFormatResolverTest {

    private fun settings(
        resolution: StreamResolution = StreamResolution.RES_1080P,
        frameRate: FrameRate = FrameRate.FPS_60,
        codec: VideoCodec = VideoCodec.AUTO,
        hdr: Boolean = false,
    ): StreamSettings = StreamSettings(
        resolution = resolution,
        frameRate = frameRate,
        codec = codec,
        hdrEnabled = hdr,
    )

    @Test
    fun `a fixed resolution ignores the display entirely`() {
        val request = StreamFormatResolver.requestFor(
            settings = settings(resolution = StreamResolution.RES_1440P),
            displayWidth = 1080,
            displayHeight = 2400,
        )

        assertEquals(2560, request.width)
        assertEquals(1440, request.height)
        assertEquals(60, request.frameRate)
    }

    @Test
    fun `Native resolves to the display size, always expressed landscape`() {
        val request = StreamFormatResolver.requestFor(
            settings = settings(resolution = StreamResolution.NATIVE),
            displayWidth = 1080,
            displayHeight = 2400,
        )

        assertEquals(2400, request.width)
        assertEquals(1080, request.height)
    }

    @Test
    fun `Native from a landscape window gives the same answer as from a portrait one`() {
        val portrait = StreamFormatResolver.requestFor(
            settings(resolution = StreamResolution.NATIVE),
            displayWidth = 1080,
            displayHeight = 2400,
        )
        val landscape = StreamFormatResolver.requestFor(
            settings(resolution = StreamResolution.NATIVE),
            displayWidth = 2400,
            displayHeight = 1080,
        )

        assertEquals(portrait, landscape)
    }

    @Test
    fun `Native is clamped to 4K`() {
        val request = StreamFormatResolver.requestFor(
            settings(resolution = StreamResolution.NATIVE),
            displayWidth = 7680,
            displayHeight = 4320,
        )

        assertEquals(3840, request.width)
        assertEquals(2160, request.height)
    }

    @Test
    fun `Native falls back to 1080p when the display size is unknown`() {
        val request = StreamFormatResolver.requestFor(
            settings(resolution = StreamResolution.NATIVE),
            displayWidth = 0,
            displayHeight = 0,
        )

        assertEquals(1920, request.width)
        assertEquals(1080, request.height)
    }

    @Test
    fun `odd display dimensions are rounded down to even ones`() {
        val request = StreamFormatResolver.requestFor(
            settings(resolution = StreamResolution.NATIVE),
            displayWidth = 1081,
            displayHeight = 2401,
        )

        assertEquals(2400, request.width)
        assertEquals(1080, request.height)
    }

    @Test
    fun `the codec preference and HDR flag pass straight through`() {
        val request = StreamFormatResolver.requestFor(
            settings(codec = VideoCodec.AV1, hdr = true, frameRate = FrameRate.FPS_120),
            displayWidth = 1920,
            displayHeight = 1080,
        )

        assertEquals(VideoCodec.AV1, request.preferredCodec)
        assertEquals(120, request.frameRate)
        assertTrue(request.hdr)
    }

    @Test
    fun `re-selecting for a negotiated format forces that codec rather than preferring it`() {
        val negotiated = VideoStreamFormat(VideoCodecType.AV1, 2560, 1440, 120, hdr = true)

        val request = StreamFormatResolver.requestForNegotiated(negotiated)

        assertEquals(VideoCodec.AV1, request.preferredCodec)
        assertEquals(2560, request.width)
        assertEquals(1440, request.height)
        assertEquals(120, request.frameRate)
        assertTrue(request.hdr)
    }

    @Test
    fun `describe reads as a human sentence`() {
        assertEquals(
            "AV1 1920×1080 60 fps HDR",
            VideoStreamFormat(VideoCodecType.AV1, 1920, 1080, 60, hdr = true).describe(),
        )
        assertEquals(
            "1920×1080 60 fps",
            VideoFormatRequest(1920, 1080, 60).describe(),
        )
    }

    @Test
    fun `the maximum input size never falls below the floor`() {
        assertEquals(
            VideoStreamFormat.MIN_INPUT_SIZE,
            VideoStreamFormat(VideoCodecType.H264, 640, 360, 30).maxInputSize,
        )
        assertEquals(
            1920 * 1080,
            VideoStreamFormat(VideoCodecType.HEVC, 1920, 1080, 60).maxInputSize,
        )
    }
}
