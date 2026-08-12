package com.voidlink.android.media.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.audio.RtpAudioConstants
import java.nio.ByteBuffer

/**
 * The Android half of the audio path: `MediaCodec` `audio/opus` into an `AudioTrack` (spec §8.5).
 *
 * All decision-making lives in [AudioDecoderCore]; this class only translates. That split is what
 * lets the interesting behaviour be unit-tested in an environment with no Android runtime, and it
 * is the same split [com.voidlink.android.media.MediaCodecDriver] makes for video.
 *
 * Follows spec §8.5 on the points that cost latency or silence:
 *
 * * **Synchronous `MediaCodec`.** Unlike video (spec §12.1, asynchronous mandatory), audio is
 *   decoded on one pump thread that submits and drains in the same call. There is no surface to
 *   render to and no callback thread worth the complexity; a 5 ms packet decodes in microseconds.
 * * **Codec-specific data built by [OpusCodecSpecificData].** csd-0/1/2 are exactly the bytes that
 *   file's unit tests pin, because wrong CSD gives silence or noise and no error.
 * * **`PERFORMANCE_MODE_LOW_LATENCY`**, requested on the `AudioTrack` builder. API 26, which is
 *   this app's `minSdk`, so it needs no version guard.
 * * **Non-blocking writes.** `AudioTrack.write(..., WRITE_NON_BLOCKING)` returns what the device
 *   accepted rather than waiting for room. Waiting for room is how a blocked pump becomes a growing
 *   backlog, and the backlog is the thing this whole layer exists to prevent.
 * * **Buffer size from `getMinBufferSize`**, floored at 30 ms by [AudioBufferPlan.trackBufferBytes]
 *   — never an invented constant.
 *
 * @param format the negotiated stream.
 * @param trackBufferBytes the output buffer size, already computed by
 *   [AudioBufferPlan.trackBufferBytes] from `AudioTrack.getMinBufferSize`. Passed in rather than
 *   computed here so the sizing rule stays testable.
 * @param channelMask the `AudioFormat.CHANNEL_OUT_*` mask for [format].
 */
class MediaCodecAudioDriver(
    private val format: AudioStreamFormat,
    private val trackBufferBytes: Int,
    private val channelMask: Int,
) : AudioCodecDriver {

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var codecName: String = "audio/opus"

    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var loggedOutputFormat: Boolean = false

    /** Reused silence for concealment: exactly one packet duration (spec §8.5). */
    private val silence: ByteArray by lazy { ByteArray(format.bytesPerPacket) }

    override val name: String get() = codecName

    override fun start() {
        val mediaFormat = buildFormat()
        val decoder = selectDecoder()
        val created = MediaCodec.createByCodecName(decoder)
        codecName = decoder
        try {
            created.configure(mediaFormat, null, null, 0)
            created.start()
        } catch (error: Throwable) {
            runCatching { created.release() }
            throw error
        }
        codec = created

        val created2 = try {
            buildTrack()
        } catch (error: Throwable) {
            runCatching { created.stop() }
            runCatching { created.release() }
            codec = null
            throw error
        }
        track = created2
        created2.play()

        ProtocolLog.i(
            RtpAudioConstants.LOG_TAG_AUDIO,
            "audio decoder $decoder started for ${format.describe()}; " +
                "AudioTrack buffer ${trackBufferBytes}B, channel mask 0x" +
                channelMask.toString(HEX_RADIX),
        )
    }

    override fun decode(
        packet: ByteArray?,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        play: Boolean,
    ): Int {
        if (packet == null) {
            // Spec §8.5: MediaCodec has no PLC API; the practical substitute is one packet
            // duration of silence, which keeps the timeline aligned rather than shortening it.
            return if (play) writePcm(silence, 0, silence.size) else 0
        }

        val active = codec ?: return -1
        val inputIndex = try {
            active.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        } catch (error: Throwable) {
            ProtocolLog.w(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "dequeueing an audio input buffer failed",
                error,
            )
            return -1
        }
        if (inputIndex < 0) {
            // No input buffer within the timeout. The codec is wedged or the device is very busy;
            // the packet is lost, which costs 5 ms, and the next one will very likely be accepted.
            return -1
        }

        try {
            val buffer = active.getInputBuffer(inputIndex) ?: return -1
            buffer.clear()
            if (buffer.remaining() < length) {
                // Give the buffer back empty rather than leaking the index.
                active.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, 0)
                return -1
            }
            buffer.put(packet, offset, length)
            active.queueInputBuffer(inputIndex, 0, length, presentationTimeUs, 0)
        } catch (error: Throwable) {
            ProtocolLog.w(RtpAudioConstants.LOG_TAG_AUDIO, "queueing an Opus packet failed", error)
            return -1
        }

        return drainOutput(active, play)
    }

    /**
     * Drains every output buffer the codec has ready.
     *
     * A loop rather than a single dequeue because the Opus decoder can emit more than one buffer for
     * one packet, and because a buffer left undrained is an input buffer that never comes back.
     */
    private fun drainOutput(active: MediaCodec, play: Boolean): Int {
        var frames = 0
        var drained = 0
        while (drained < MAX_OUTPUT_BUFFERS_PER_PACKET) {
            val outputIndex = try {
                active.dequeueOutputBuffer(bufferInfo, 0L)
            } catch (error: Throwable) {
                ProtocolLog.w(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "dequeueing an audio output buffer failed",
                    error,
                )
                return if (frames > 0) frames else -1
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The decoder has settled. Worth one line, because a channel count or sample rate
                // that differs from what we configured means the AudioTrack is now wrong, and the
                // symptom would be chipmunk audio rather than an error.
                if (!loggedOutputFormat) {
                    loggedOutputFormat = true
                    ProtocolLog.i(
                        RtpAudioConstants.LOG_TAG_AUDIO,
                        "Opus decoder output format: ${runCatching { active.outputFormat }
                            .getOrNull()}",
                    )
                }
                drained++
                continue
            }
            if (outputIndex < 0) break
            drained++

            try {
                val codecConfig =
                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (play && !codecConfig && bufferInfo.size > 0) {
                    val buffer = active.getOutputBuffer(outputIndex)
                    if (buffer != null) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        frames += writePcm(buffer, bufferInfo.size)
                    }
                }
            } finally {
                runCatching { active.releaseOutputBuffer(outputIndex, false) }
            }
        }
        return frames
    }

    /** Writes PCM without blocking, returning the sample frames the device accepted. */
    private fun writePcm(buffer: ByteBuffer, size: Int): Int {
        val active = track ?: return 0
        val written = try {
            active.write(buffer, size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (error: Throwable) {
            ProtocolLog.w(RtpAudioConstants.LOG_TAG_AUDIO, "AudioTrack.write failed", error)
            return 0
        }
        return if (written > 0) written / format.bytesPerPcmFrame else 0
    }

    /** The byte-array overload, for concealment silence. */
    private fun writePcm(data: ByteArray, offset: Int, size: Int): Int {
        val active = track ?: return 0
        val written = try {
            active.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (error: Throwable) {
            ProtocolLog.w(RtpAudioConstants.LOG_TAG_AUDIO, "AudioTrack.write failed", error)
            return 0
        }
        return if (written > 0) written / format.bytesPerPcmFrame else 0
    }

    /**
     * Sample frames played, widened from the platform's signed 32-bit counter.
     *
     * `getPlaybackHeadPosition` returns an `Int` that is documented as wrapping; masking rather than
     * sign-extending is what stops the backlog going hugely negative once every 24 hours of
     * playback and suppressing every drop from then on.
     */
    override fun playbackPositionFrames(): Long {
        val active = track ?: return 0L
        return try {
            active.playbackHeadPosition.toLong() and UNSIGNED_INT_MASK
        } catch (error: Throwable) {
            0L
        }
    }

    override fun underrunCount(): Int {
        val active = track ?: return -1
        return try {
            active.underrunCount
        } catch (error: Throwable) {
            -1
        }
    }

    override fun flush() {
        runCatching { codec?.flush() }
        val active = track ?: return
        runCatching {
            active.pause()
            active.flush()
            active.play()
        }
    }

    override fun release() {
        val activeTrack = track
        track = null
        runCatching { activeTrack?.pause() }
        runCatching { activeTrack?.flush() }
        runCatching { activeTrack?.release() }

        val activeCodec = codec
        codec = null
        runCatching { activeCodec?.stop() }
        runCatching { activeCodec?.release() }
    }

    /**
     * The `MediaFormat` for the Opus decoder.
     *
     * The three codec-specific-data buffers are the load-bearing part; everything else is what any
     * audio decoder needs. See [OpusCodecSpecificData] for what each one is and why it matters.
     */
    private fun buildFormat(): MediaFormat {
        val media = MediaFormat.createAudioFormat(
            format.mimeType,
            format.sampleRateHz,
            format.channelCount,
        )
        media.setByteBuffer(
            CSD_IDENTIFICATION,
            ByteBuffer.wrap(OpusCodecSpecificData.identificationHeader(format)),
        )
        media.setByteBuffer(
            CSD_PRE_SKIP,
            ByteBuffer.wrap(OpusCodecSpecificData.preSkipNanos(format)),
        )
        media.setByteBuffer(
            CSD_SEEK_PRE_ROLL,
            ByteBuffer.wrap(OpusCodecSpecificData.seekPreRollNanos()),
        )
        return media
    }

    /**
     * Names a decoder for this stream.
     *
     * The lookup deliberately uses a **bare** format — mime, sample rate, channel count — rather
     * than the configured one. `findDecoderForFormat` matches against a codec's declared
     * capabilities, and the codec-specific-data buffers are not capabilities; passing them in only
     * creates opportunities for a platform to reject a query it would otherwise have answered.
     *
     * @throws IllegalStateException when the device has no Opus decoder at all. Spec §8.5:
     *   "Available since API 21 in theory; reliable from API 29+" — so a device without one is a
     *   real possibility, and the caller's response is an explained unavailability rather than a
     *   failed session.
     */
    private fun selectDecoder(): String {
        val query = MediaFormat.createAudioFormat(
            format.mimeType,
            format.sampleRateHz,
            format.channelCount,
        )
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(query)
            ?: throw IllegalStateException(
                "this device has no ${format.mimeType} decoder for ${format.describe()}",
            )
    }

    /**
     * The output track (spec §8.5).
     *
     * `USAGE_GAME` + `CONTENT_TYPE_MOVIE`, `MODE_STREAM`, 16-bit PCM at 48 kHz, and
     * `PERFORMANCE_MODE_LOW_LATENCY`. Low-latency mode is a request, not a guarantee: a device that
     * cannot honour it builds an ordinary track, which is why there is no fallback path here.
     */
    private fun buildTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(format.sampleRateHz)
                .setChannelMask(channelMask)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(trackBufferBytes)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    private companion object {
        /** `MediaFormat` keys for the three codec-specific-data buffers (spec §8.5). */
        const val CSD_IDENTIFICATION = "csd-0"
        const val CSD_PRE_SKIP = "csd-1"
        const val CSD_SEEK_PRE_ROLL = "csd-2"

        /**
         * How long a dequeue waits for a free input buffer.
         *
         * One packet duration's worth. Waiting longer would delay the whole pump; not waiting at
         * all would drop packets whenever the codec was mid-decode.
         */
        const val DEQUEUE_TIMEOUT_US: Long = 5_000L

        /** Bound on the drain loop, so a misbehaving codec cannot spin the pump thread. */
        const val MAX_OUTPUT_BUFFERS_PER_PACKET: Int = 8

        const val UNSIGNED_INT_MASK: Long = 0xFFFF_FFFFL
        const val HEX_RADIX: Int = 16
    }
}

/**
 * How an [AudioCodecDriver] is built. Injectable so a test can drive the whole audio path with a
 * fake, exactly as `driverFactory` does for [com.voidlink.android.media.VideoDecoder].
 */
fun interface AudioCodecDriverFactory {
    /**
     * @param format the negotiated stream.
     * @param channelMask the `AudioFormat.CHANNEL_OUT_*` mask.
     * @throws Exception when the platform cannot supply a driver at all.
     */
    fun create(format: AudioStreamFormat, channelMask: Int): AudioCodecDriver
}

/**
 * The production driver factory: sizes the output buffer from the platform, then builds the driver.
 *
 * The `getMinBufferSize` call is here rather than inside [MediaCodecAudioDriver] because it is the
 * one piece of the sizing rule that needs Android — [AudioBufferPlan.trackBufferBytes] does the
 * arithmetic, and is tested.
 */
object PlatformAudioCodecDriverFactory : AudioCodecDriverFactory {
    override fun create(format: AudioStreamFormat, channelMask: Int): AudioCodecDriver {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            ProtocolLog.w(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "AudioTrack.getMinBufferSize returned $minBufferBytes for ${format.describe()}; " +
                    "falling back to the ${AudioBufferPlan.TARGET_BUFFER_MS}ms target of spec §8.5",
            )
        }
        return MediaCodecAudioDriver(
            format = format,
            trackBufferBytes = AudioBufferPlan.trackBufferBytes(minBufferBytes, format),
            channelMask = channelMask,
        )
    }
}
