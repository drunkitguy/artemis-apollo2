package com.voidlink.android.media

import com.voidlink.android.data.StreamResolution
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.data.VideoCodec

/**
 * Turns the user's settings and the device's display size into a concrete [VideoFormatRequest].
 *
 * Pure, and separated from the stream screen so it can be tested: `Native` resolution is the one
 * setting whose value depends on the hardware, and getting it wrong is the difference between a
 * stream that matches the panel and one that is subtly stretched or refuses to decode.
 */
object StreamFormatResolver {

    /** Smallest long edge a `Native` resolution is allowed to resolve to. */
    const val NATIVE_MIN_LONG_EDGE: Int = 640

    /** Largest long edge a `Native` resolution is allowed to resolve to (4K). */
    const val NATIVE_MAX_LONG_EDGE: Int = 3840

    /** Smallest short edge a `Native` resolution is allowed to resolve to. */
    const val NATIVE_MIN_SHORT_EDGE: Int = 360

    /** Largest short edge a `Native` resolution is allowed to resolve to (4K). */
    const val NATIVE_MAX_SHORT_EDGE: Int = 2160

    /**
     * Builds the format request for a session.
     *
     * `Native` resolves to the display's own size, always expressed **landscape** — the negotiated
     * stream dimensions are fixed at `/launch` and never change on rotation (UI spec §5.7), so
     * asking a desktop host for a portrait capture because the phone happened to be upright would
     * be wrong. The result is clamped to a sane range and forced to even dimensions, because odd
     * luma dimensions are rejected outright by a good many hardware decoders.
     *
     * @param settings the merged global + per-host settings for this session.
     * @param displayWidth the display's width in pixels; ignored unless the resolution is `Native`.
     * @param displayHeight the display's height in pixels; ignored unless the resolution is
     *   `Native`.
     */
    fun requestFor(
        settings: StreamSettings,
        displayWidth: Int,
        displayHeight: Int,
    ): VideoFormatRequest {
        val resolution = settings.resolution
        val width: Int
        val height: Int

        if (resolution.isNative) {
            val longEdge = maxOf(displayWidth, displayHeight)
            val shortEdge = minOf(displayWidth, displayHeight)
            width = if (longEdge > 0) {
                longEdge.coerceIn(NATIVE_MIN_LONG_EDGE, NATIVE_MAX_LONG_EDGE)
            } else {
                StreamResolution.RES_1080P.width
            }
            height = if (shortEdge > 0) {
                shortEdge.coerceIn(NATIVE_MIN_SHORT_EDGE, NATIVE_MAX_SHORT_EDGE)
            } else {
                StreamResolution.RES_1080P.height
            }
        } else {
            width = resolution.width
            height = resolution.height
        }

        return VideoFormatRequest(
            width = toEven(width),
            height = toEven(height),
            frameRate = settings.frameRate.fps,
            hdr = settings.hdrEnabled,
            preferredCodec = settings.codec,
        )
    }

    /**
     * Builds the request that re-selects a decoder for a format the host actually negotiated.
     *
     * The codec is no longer a preference at this point — the host is already sending that codec,
     * so anything else is unusable and the selection must either find a decoder for it or fail.
     */
    fun requestForNegotiated(format: VideoStreamFormat): VideoFormatRequest = VideoFormatRequest(
        width = format.width,
        height = format.height,
        frameRate = format.frameRate,
        hdr = format.hdr,
        preferredCodec = when (format.codec) {
            VideoCodecType.H264 -> VideoCodec.H264
            VideoCodecType.HEVC -> VideoCodec.HEVC
            VideoCodecType.AV1 -> VideoCodec.AV1
        },
    )

    /** Rounds down to the nearest even number, with a floor of 2. */
    private fun toEven(value: Int): Int = if (value < 2) 2 else value - (value % 2)
}
