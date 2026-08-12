package com.voidlink.android.media.audio

import com.voidlink.android.protocol.audio.OpusChannelOrder
import com.voidlink.android.protocol.audio.RtpAudioConstants

/**
 * Everything the audio decoder and the output track need to be configured (spec §8.3, §8.5).
 *
 * A plain value with no Android types in it, for the same reason
 * [com.voidlink.android.media.VideoStreamFormat] is one: every decision derived from it — the codec
 * specific data, the output buffer size, the channel mask — is a decision worth a unit test, and a
 * type that needs a device cannot be unit-tested.
 *
 * @property channelCount 2, 6 or 8 (spec §8.2).
 * @property sampleRateHz always 48 000; carried rather than assumed so nothing here has a magic
 *   number of its own (spec §8.3).
 * @property streams number of Opus streams in the multistream configuration.
 * @property coupledStreams how many of those are coupled stereo pairs.
 * @property mapping the channel mapping table, **already in playback order**
 *   (`FL FR C LFE RL RR SL SR`). See [OpusChannelOrder] for why that matters and where the
 *   reordering happens.
 * @property packetDurationMs the negotiated `x-nv-aqos.packetDuration`: 5 ms, or 10 ms for a slow
 *   decoder (spec §8.5). This is the length of one Opus packet and therefore the length of one
 *   concealment fill.
 */
class AudioStreamFormat(
    val channelCount: Int,
    val sampleRateHz: Int = RtpAudioConstants.SAMPLE_RATE_HZ,
    val streams: Int = DEFAULT_STEREO_STREAMS,
    val coupledStreams: Int = DEFAULT_STEREO_COUPLED_STREAMS,
    val mapping: IntArray = intArrayOf(0, 1),
    val packetDurationMs: Int = RtpAudioConstants.DEFAULT_PACKET_DURATION_MS,
) {

    init {
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(packetDurationMs > 0) {
            "packetDurationMs must be positive, was $packetDurationMs"
        }
        require(mapping.size >= channelCount) {
            "mapping has ${mapping.size} entries for $channelCount channels"
        }
    }

    /** The `MediaCodec` mime type. Opus is the only audio codec GameStream uses (spec §8.5). */
    val mimeType: String get() = MIME_TYPE_OPUS

    /** True when the stream needs Opus mapping family 1 — i.e. surround (spec §8.5). */
    val isMultistream: Boolean get() = channelCount > 2

    /** Bytes of 16-bit PCM per sample frame — one sample for every channel. */
    val bytesPerPcmFrame: Int get() = channelCount * BYTES_PER_PCM_SAMPLE

    /** PCM sample frames in one Opus packet. */
    val framesPerPacket: Int get() = sampleRateHz * packetDurationMs / MILLIS_PER_SECOND

    /** Bytes of 16-bit PCM one Opus packet decodes to. */
    val bytesPerPacket: Int get() = framesPerPacket * bytesPerPcmFrame

    /** The `AudioTrack` output channel mask, or `null` for a layout we cannot place. */
    val channelMask: Int? get() = OpusChannelOrder.maskFor(channelCount)

    /** One line for the log and the stats overlay. */
    fun describe(): String =
        "${channelCount}ch Opus @ ${sampleRateHz}Hz, ${packetDurationMs}ms packets" +
            (if (isMultistream) ", $streams streams/$coupledStreams coupled" else "")

    override fun toString(): String = "AudioStreamFormat(${describe()})"

    companion object {
        /** Spec §8.3: "Stereo needs no negotiation." */
        const val DEFAULT_STEREO_STREAMS: Int = 1
        const val DEFAULT_STEREO_COUPLED_STREAMS: Int = 1

        /** `MediaFormat.MIMETYPE_AUDIO_OPUS`, spelled out to keep this file free of Android types. */
        const val MIME_TYPE_OPUS: String = "audio/opus"

        /** `AudioFormat.ENCODING_PCM_16BIT` is two bytes per sample (spec §8.5). */
        const val BYTES_PER_PCM_SAMPLE: Int = 2

        private const val MILLIS_PER_SECOND: Int = 1_000

        /** The stereo format, which is what v1 negotiates and plays (spec §8.5). */
        fun stereo(
            packetDurationMs: Int = RtpAudioConstants.DEFAULT_PACKET_DURATION_MS,
        ): AudioStreamFormat = AudioStreamFormat(
            channelCount = RtpAudioConstants.CHANNELS_STEREO,
            packetDurationMs = packetDurationMs,
        )
    }
}
