package com.voidlink.android.protocol.audio

import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.rtsp.OpusMultistreamConfig

/**
 * The channel layouts spec §8.2 tabulates, and the wire-versus-playback order fix-up of §8.3.
 *
 * ### Why this file exists at all
 *
 * The host's normal-quality surround mapping is in the order
 *
 * ```
 * FL FR C RL RR SL SR LFE
 * ```
 *
 * and every decoder — and `AudioTrack`'s channel mask, and the `OpusHead` mapping table — expects
 *
 * ```
 * FL FR C LFE RL RR SL SR
 * ```
 *
 * Getting that wrong produces audio that plays perfectly, at the right volume, with no error
 * anywhere, and puts centre-channel dialogue in a surround speaker. It is the single most
 * checkable and least noticeable mistake in the audio path, which is why the transform has exactly
 * **one** implementation in this codebase — [OpusMultistreamConfig.reorderForDecoder], applied
 * once, at SDP-parse time — and why this object delegates to it rather than repeating it.
 *
 * ### Who applies it
 *
 * `protocol/rtsp` applies it while parsing `a=fmtp:97 surround-params=…`, so
 * [com.voidlink.android.protocol.rtsp.NegotiatedSession.opusConfig] is **already in playback
 * order** and must not be reordered a second time. [remapWireToPlayback] is here for a caller that
 * has a raw mapping straight off the wire — a test, a fixture, or a future high-quality-surround
 * path — and for the tests that pin the transform's behaviour.
 */
object OpusChannelOrder {

    /** The host's normal-quality wire order (spec §8.3). Index = position in the mapping array. */
    val WIRE_ORDER: List<String> = listOf("FL", "FR", "C", "RL", "RR", "SL", "SR", "LFE")

    /** The order decoders and `AudioTrack` expect (spec §8.3). */
    val PLAYBACK_ORDER: List<String> = listOf("FL", "FR", "C", "LFE", "RL", "RR", "SL", "SR")

    /** Index of the LFE channel in [PLAYBACK_ORDER] — where the fix-up moves it to. */
    const val PLAYBACK_LFE_INDEX: Int = 3

    // ---- Android channel masks -----------------------------------------------------------------
    //
    // Written as literals rather than as `AudioFormat.CHANNEL_OUT_*` for the reason `LowLatencyKeys`
    // gives for its format keys: which mask we pick for a channel count is exactly the sort of thing
    // worth a unit test, and exactly the sort of thing that cannot be tested if it needs a device.
    // The values are fixed platform constants and have never changed.

    /** `AudioFormat.CHANNEL_OUT_STEREO` — FRONT_LEFT | FRONT_RIGHT. */
    const val CHANNEL_OUT_STEREO: Int = 0x0C

    /** `AudioFormat.CHANNEL_OUT_5POINT1` — adds FRONT_CENTER, LOW_FREQUENCY, BACK_LEFT/RIGHT. */
    const val CHANNEL_OUT_5POINT1: Int = 0xFC

    /**
     * `AudioFormat.CHANNEL_OUT_7POINT1_SURROUND` — 5.1 plus SIDE_LEFT (0x800) and SIDE_RIGHT
     * (0x1000).
     *
     * The named constant is API 23 and this app's `minSdk` is 26, so the constant would compile;
     * the literal is used anyway so that this object stays a plain JVM class with no `android.media`
     * import, which is what lets [maskFor] be tested.
     */
    const val CHANNEL_OUT_7POINT1_SURROUND: Int = 0x18FC

    /**
     * The `AudioTrack` output mask for [channelCount], or `null` for a count we have no layout for.
     *
     * A `null` is not a failure to hide: it means the host announced a layout this client has never
     * been told how to place, and playing it against a guessed mask would put channels in arbitrary
     * speakers. The caller reports it and continues without audio.
     */
    fun maskFor(channelCount: Int): Int? = when (channelCount) {
        RtpAudioConstants.CHANNELS_STEREO -> CHANNEL_OUT_STEREO
        RtpAudioConstants.CHANNELS_51_SURROUND -> CHANNEL_OUT_5POINT1
        RtpAudioConstants.CHANNELS_71_SURROUND -> CHANNEL_OUT_7POINT1_SURROUND
        else -> null
    }

    /** True for the layouts spec §8.2 tabulates: stereo, 5.1 and 7.1. */
    fun isSupportedLayout(channelCount: Int): Boolean = maskFor(channelCount) != null

    /**
     * Applies spec §8.3's channel-order fix-up to a mapping read straight off the wire.
     *
     * Delegates to [OpusMultistreamConfig.reorderForDecoder] so that the transform has one
     * implementation. Stereo and anything shorter than [channelCount] are returned unchanged, which
     * is what §8.3 specifies ("required for 6 and 8 channels").
     *
     * **Do not call this on
     * [com.voidlink.android.protocol.rtsp.NegotiatedSession.opusConfig]'s mapping.** That one has
     * already been through it; a second application moves LFE again and silently breaks the layout
     * this function exists to fix.
     */
    fun remapWireToPlayback(mapping: IntArray, channelCount: Int): IntArray =
        OpusMultistreamConfig.reorderForDecoder(mapping, channelCount)

    /**
     * Names the speaker each entry of a playback-order mapping feeds, for a log line.
     *
     * Purely diagnostic, and the only cheap defence against the failure mode this file is about:
     * one line in logcat that a human can compare against what they hear.
     */
    fun describePlaybackOrder(mapping: IntArray): String =
        mapping.withIndex().joinToString(" ") { (index, source) ->
            val speaker = PLAYBACK_ORDER.getOrElse(index) { "ch$index" }
            "$speaker<-$source"
        }

    /**
     * Records that surround playback is being attempted, once per process.
     *
     * Spec §8.5's v1 decision is stereo-only playback, recorded in
     * [UnverifiedRtpAudioConstants.MULTISTREAM_PLAYBACK_ENABLED]. Anything that reaches a surround
     * layout at all — whether to play it or to refuse it — announces here, so that a bug report from
     * a surround host carries the mapping we computed.
     */
    fun announceSurround(channelCount: Int, mapping: IntArray) {
        ProtocolLog.unverified(
            RtpAudioConstants.LOG_TAG_AUDIO,
            "audio-multistream-playback",
            "a ${channelCount}-channel Opus stream was negotiated; MediaCodec multistream support " +
                "is unreliable (spec 01 §8.5) so playback is " +
                (if (UnverifiedRtpAudioConstants.MULTISTREAM_PLAYBACK_ENABLED) "attempted" else "declined") +
                ". Playback-order mapping: ${describePlaybackOrder(mapping)}",
        )
    }
}
