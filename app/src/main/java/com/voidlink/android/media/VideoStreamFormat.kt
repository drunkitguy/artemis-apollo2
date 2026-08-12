package com.voidlink.android.media

import com.voidlink.android.data.VideoCodec

/**
 * A video codec this client can ask a decoder for.
 *
 * The three entries are exactly the codecs `docs/01-PROTOCOL.md` §7.1 lets us negotiate. The
 * [mimeType] values are the Android `MediaFormat` mime strings; they are written out literally
 * rather than read from `MediaFormat` constants so this type stays free of Android imports and
 * therefore unit-testable on the JVM.
 *
 * @property mimeType the `video/…` mime string a `MediaCodec` decoder advertises.
 * @property label the user-facing name, matching the settings panel's segmented control.
 * @property tenBitCapable whether the codec family has a 10-bit profile at all — the gate HDR has
 *   to pass before a per-device probe is even worth running (spec §7.2 step 6).
 */
enum class VideoCodecType(
    val mimeType: String,
    val label: String,
    val tenBitCapable: Boolean,
) {
    /** H.264 / AVC. Universally supported; the floor we can always fall back to. */
    H264("video/avc", "H.264", false),

    /** HEVC / H.265. The default preference on anything modern. */
    HEVC("video/hevc", "HEVC", true),

    /** AV1. Chosen only when a hardware decoder probes clean — see [DecoderSelector]. */
    AV1("video/av01", "AV1", true),
    ;

    companion object {
        /**
         * The codecs in descending order of desirability, ignoring per-device probe results.
         *
         * [DecoderSelector] re-orders this according to what actually probed, so this is only the
         * starting point and the order the probe walks the codec list in.
         */
        val ordered: List<VideoCodecType> = listOf(AV1, HEVC, H264)

        /** Returns the codec whose [mimeType] is [mimeType], or `null` for anything else. */
        fun fromMimeType(mimeType: String): VideoCodecType? =
            values().firstOrNull { it.mimeType.equals(mimeType, ignoreCase = true) }

        /**
         * Maps the user's settings preference onto a concrete codec.
         *
         * Returns `null` for [VideoCodec.AUTO], which has no single answer — the selector decides
         * that one from the probe.
         */
        fun fromPreference(preference: VideoCodec): VideoCodecType? = when (preference) {
            VideoCodec.H264 -> H264
            VideoCodec.HEVC -> HEVC
            VideoCodec.AV1 -> AV1
            VideoCodec.AUTO -> null
        }
    }
}

/**
 * What the client wants to decode, before any device capability is known.
 *
 * This is the input to [DecoderProbe] and [DecoderSelector]: it carries the user's *preference*
 * rather than a decided codec, because which codec we end up on is a function of what the device
 * reports. The decided form is [VideoStreamFormat].
 *
 * @property width requested luma width in pixels.
 * @property height requested luma height in pixels.
 * @property frameRate requested frame rate in whole frames per second.
 * @property hdr whether HDR10 was requested; may be cleared by the selector when unavailable.
 * @property preferredCodec the `Preferred Codec` setting.
 */
data class VideoFormatRequest(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val hdr: Boolean = false,
    val preferredCodec: VideoCodec = VideoCodec.AUTO,
) {
    /** Human-readable `1920×1080 60 fps` summary, used in failure text. */
    fun describe(): String = buildString {
        append(width)
        append('×')
        append(height)
        append(' ')
        append(frameRate)
        append(" fps")
        if (hdr) append(" HDR")
    }
}

/**
 * A decided video format: the codec plus the dimensions the decoder is configured with.
 *
 * Produced by [DecoderSelector] from a [VideoFormatRequest] and the device's real capabilities,
 * so its [hdr] flag reflects what the device can actually do rather than what was asked for.
 *
 * @property codec the codec the decoder was chosen for.
 * @property width luma width in pixels.
 * @property height luma height in pixels.
 * @property frameRate frame rate in whole frames per second.
 * @property hdr whether the stream carries an HDR10 (10-bit, ST 2084) signal.
 */
data class VideoStreamFormat(
    val codec: VideoCodecType,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val hdr: Boolean = false,
) {
    /**
     * The value for `MediaFormat.KEY_MAX_INPUT_SIZE`.
     *
     * Spec §12.1 suggests `width * height` bytes; under-sizing this is what produces
     * "buffer too small" failures on the first big IDR. The floor keeps tiny resolutions from
     * producing an input buffer that a single keyframe would overflow.
     */
    val maxInputSize: Int
        get() = maxOf(width.toLong() * height.toLong(), MIN_INPUT_SIZE.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    /** Human-readable `HEVC 1920×1080 60 fps` summary for the stats chip and failure text. */
    fun describe(): String = buildString {
        append(codec.label)
        append(' ')
        append(width)
        append('×')
        append(height)
        append(' ')
        append(frameRate)
        append(" fps")
        if (hdr) append(" HDR")
    }

    companion object {
        /** Smallest input buffer we will ever ask for, in bytes. */
        const val MIN_INPUT_SIZE: Int = 512 * 1024
    }
}
