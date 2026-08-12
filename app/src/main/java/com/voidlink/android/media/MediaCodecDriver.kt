package com.voidlink.android.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.view.Surface
import com.voidlink.android.protocol.ProtocolLog

/**
 * The Android half of the decoder: an asynchronous `MediaCodec` rendering straight to a [Surface].
 *
 * Follows `docs/01-PROTOCOL.md` §12.1 to the letter on the points that cost latency:
 *
 * * **Asynchronous mode only.** `setCallback` on a dedicated `HandlerThread`; there is no
 *   `dequeueInputBuffer` loop anywhere in this file.
 * * **Immediate rendering.** `releaseOutputBuffer(index, true)`, never the timestamped variant,
 *   which schedules the frame and costs a frame of latency.
 * * **Low-latency configuration applied defensively.** Configuration is attempted in three tiers —
 *   everything, then standard keys only, then the bare minimum — each on a *fresh* `MediaCodec`,
 *   because a codec that has thrown from `configure()` cannot be reconfigured. An unrecognised
 *   vendor key can therefore cost a configure attempt but can never cost the session.
 *
 * All decision-making lives in [VideoDecoderCore]; this class only translates. That split is what
 * lets the interesting behaviour be unit-tested in an environment with no Android runtime.
 *
 * @param choice the decoder and format chosen by [DecoderSelector].
 * @param surface the [Surface] to render into. Must outlive this driver — [VideoDecoder] enforces
 *   that by releasing synchronously from `surfaceDestroyed`.
 * @param callbackHandler a handler on the dedicated `decoder-cb` thread. Codec callbacks arrive
 *   on it, so it must never be the main looper.
 */
class MediaCodecDriver(
    private val choice: DecoderChoice,
    private val surface: Surface,
    private val callbackHandler: Handler,
) : CodecDriver {

    override val name: String get() = choice.candidate.name

    @Volatile
    private var codec: MediaCodec? = null

    @Volatile
    private var sink: CodecEventSink? = null

    /**
     * Which codec instance callbacks are currently accepted from.
     *
     * A rebuild leaves the old codec's already-posted callbacks queued on the handler thread.
     * Delivering one of those to the core would hand it a buffer index belonging to a dead codec.
     * Each instance therefore gets a generation number, and its callbacks are ignored once the
     * generation moves on.
     */
    @Volatile
    private var generation: Int = 0

    override fun start(sink: CodecEventSink) {
        this.sink = sink
        codec = createConfiguredCodec(sink)
    }

    override fun submit(bufferIndex: Int, frame: VideoFrame, presentationTimeUs: Long): Boolean {
        val active = codec ?: return false
        return try {
            val buffer = active.getInputBuffer(bufferIndex)
            if (buffer == null) {
                false
            } else {
                buffer.clear()
                if (buffer.remaining() < frame.length) {
                    // Give the buffer back empty rather than leaking the index. This is the
                    // KEY_MAX_INPUT_SIZE failure spec §12.1 warns about; log it loudly because the
                    // symptom (one dropped IDR) looks nothing like the cause.
                    ProtocolLog.w(
                        MediaCodecProbe.TAG,
                        "Input buffer of ${buffer.remaining()} bytes is too small for a " +
                            "${frame.length}-byte frame; dropping it",
                    )
                    active.queueInputBuffer(bufferIndex, 0, 0, presentationTimeUs, 0)
                    false
                } else {
                    buffer.put(frame.data, frame.offset, frame.length)
                    val flags = if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    active.queueInputBuffer(
                        bufferIndex,
                        0,
                        frame.length,
                        presentationTimeUs,
                        flags,
                    )
                    true
                }
            }
        } catch (error: Throwable) {
            ProtocolLog.w(MediaCodecProbe.TAG, "Queueing an input buffer failed", error)
            false
        }
    }

    override fun render(bufferIndex: Int) {
        val active = codec ?: return
        try {
            active.releaseOutputBuffer(bufferIndex, true)
        } catch (error: Throwable) {
            ProtocolLog.w(MediaCodecProbe.TAG, "Releasing an output buffer failed", error)
        }
    }

    override fun discard(bufferIndex: Int) {
        val active = codec ?: return
        try {
            active.releaseOutputBuffer(bufferIndex, false)
        } catch (error: Throwable) {
            // Nothing to do: the buffer belongs to a codec that is going away regardless.
        }
    }

    override fun flush() {
        val active = codec ?: return
        try {
            active.flush()
            // In asynchronous mode flush() stops callbacks until start() is called again. Omitting
            // this is a decoder that goes permanently silent after its first transient error.
            active.start()
        } catch (error: Throwable) {
            ProtocolLog.w(MediaCodecProbe.TAG, "Flushing the codec failed", error)
        }
    }

    override fun restart() {
        val listener = sink ?: throw IllegalStateException("restart() before start()")
        releaseCodec()
        codec = createConfiguredCodec(listener)
    }

    override fun release() {
        releaseCodec()
        sink = null
    }

    private fun releaseCodec() {
        val active = codec ?: return
        codec = null
        generation++
        try {
            active.stop()
        } catch (error: Throwable) {
            // stop() throws if the codec never started; releasing is still correct.
        }
        try {
            active.release()
        } catch (error: Throwable) {
            ProtocolLog.w(MediaCodecProbe.TAG, "Releasing the codec failed", error)
        }
    }

    /**
     * Builds, configures and starts a codec, degrading through the format tiers.
     *
     * Each attempt gets its own `MediaCodec`: once `configure()` has thrown, the instance is in an
     * error state and cannot be reused, so retrying on the same object would fail for a reason
     * that has nothing to do with the keys we changed.
     *
     * @throws IllegalStateException when every tier failed. The core turns that into a fatal error
     *   with a message the user sees.
     */
    private fun createConfiguredCodec(listener: CodecEventSink): MediaCodec {
        var lastError: Throwable? = null

        for (tier in FormatTier.values()) {
            val instance = try {
                MediaCodec.createByCodecName(name)
            } catch (error: Throwable) {
                lastError = error
                continue
            }

            val instanceGeneration = generation + 1
            try {
                generation = instanceGeneration
                instance.setCallback(callbackFor(listener, instanceGeneration), callbackHandler)
                instance.configure(buildFormat(tier), surface, null, 0)
                // Assigned before start() because start() can deliver an input buffer callback
                // immediately, and a callback that finds no codec would starve the input path.
                codec = instance
                instance.start()
                if (tier != FormatTier.FULL) {
                    ProtocolLog.w(
                        MediaCodecProbe.TAG,
                        "Decoder $name rejected the ${FormatTier.FULL.name} format; " +
                            "configured with ${tier.name} instead",
                    )
                }
                ProtocolLog.i(
                    MediaCodecProbe.TAG,
                    "Decoder $name started for ${choice.format.describe()} (${tier.name})",
                )
                return instance
            } catch (error: Throwable) {
                lastError = error
                codec = null
                try {
                    instance.release()
                } catch (releaseError: Throwable) {
                    // Already failing; the release failure adds nothing.
                }
            }
        }

        throw IllegalStateException(
            "No configuration of $name was accepted for ${choice.format.describe()}",
            lastError,
        )
    }

    /** Configuration attempts, most capable first. */
    private enum class FormatTier {
        /** Standard keys, the low-latency key, and every applicable vendor key. */
        FULL,

        /** Standard keys and the low-latency key. */
        STANDARD,

        /** Dimensions, frame rate and input size only — what any decoder must accept. */
        MINIMAL,
    }

    /**
     * Builds the `MediaFormat` for one tier.
     *
     * Every key here is from spec §12.1's table. `KEY_OPERATING_RATE` and `KEY_PRIORITY` say
     * "realtime, as fast as you can"; the colour keys are what stop an HDR stream from arriving
     * washed out.
     */
    private fun buildFormat(tier: FormatTier): MediaFormat {
        val format = choice.format
        val media = MediaFormat.createVideoFormat(format.codec.mimeType, format.width, format.height)
        media.setInteger(MediaFormat.KEY_WIDTH, format.width)
        media.setInteger(MediaFormat.KEY_HEIGHT, format.height)
        media.setInteger(MediaFormat.KEY_FRAME_RATE, format.frameRate)
        media.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize)

        if (tier == FormatTier.MINIMAL) return media

        media.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        media.setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
        media.setInteger(
            MediaFormat.KEY_COLOR_STANDARD,
            if (format.hdr) {
                MediaFormat.COLOR_STANDARD_BT2020
            } else {
                MediaFormat.COLOR_STANDARD_BT709
            },
        )
        media.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        if (format.hdr) {
            media.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
        }

        // Set by string literal, guarded by the runtime API level, exactly as spec §12.1 asks.
        if (Build.VERSION.SDK_INT >= LowLatencyKeys.STANDARD_LOW_LATENCY_MIN_API) {
            media.setInteger(LowLatencyKeys.STANDARD_LOW_LATENCY, 1)
        }

        if (tier == FormatTier.FULL) {
            for (key in LowLatencyKeys.vendorKeysFor(choice.candidate)) {
                media.setInteger(key.name, key.value)
            }
        }

        return media
    }

    /**
     * The `MediaCodec.Callback` for one codec instance, tagged with [instanceGeneration] so a
     * straggling callback from a replaced codec is discarded rather than acted on.
     */
    private fun callbackFor(listener: CodecEventSink, instanceGeneration: Int): MediaCodec.Callback =
        object : MediaCodec.Callback() {

            override fun onInputBufferAvailable(mediaCodec: MediaCodec, index: Int) {
                if (instanceGeneration != generation) return
                listener.onInputBufferAvailable(index)
            }

            override fun onOutputBufferAvailable(
                mediaCodec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                if (instanceGeneration != generation) return
                val endOfStream = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                listener.onOutputBufferAvailable(index, info.presentationTimeUs, endOfStream)
            }

            override fun onOutputFormatChanged(mediaCodec: MediaCodec, outputFormat: MediaFormat) {
                if (instanceGeneration != generation) return
                val width = readInteger(outputFormat, MediaFormat.KEY_WIDTH, choice.format.width)
                val height = readInteger(outputFormat, MediaFormat.KEY_HEIGHT, choice.format.height)
                listener.onOutputFormatChanged(width, height, outputFormat.toString())
            }

            override fun onError(mediaCodec: MediaCodec, error: MediaCodec.CodecException) {
                if (instanceGeneration != generation) return
                val transient = try {
                    error.isTransient
                } catch (ignored: Throwable) {
                    false
                }
                val recoverable = try {
                    error.isRecoverable
                } catch (ignored: Throwable) {
                    false
                }
                val diagnostic = try {
                    error.diagnosticInfo
                } catch (ignored: Throwable) {
                    null
                }
                ProtocolLog.e(
                    MediaCodecProbe.TAG,
                    "Codec $name error (transient=$transient, recoverable=$recoverable)",
                    error,
                )
                listener.onCodecFailure(
                    CodecFailure(
                        message = error.message ?: "unspecified codec error",
                        transient = transient,
                        recoverable = recoverable,
                        diagnosticInfo = diagnostic,
                    ),
                )
            }
        }

    private fun readInteger(format: MediaFormat, key: String, fallback: Int): Int = try {
        if (format.containsKey(key)) format.getInteger(key) else fallback
    } catch (error: Throwable) {
        fallback
    }

    private companion object {
        /** `MediaFormat.KEY_PRIORITY` value meaning "realtime" (spec §12.1). */
        const val PRIORITY_REALTIME: Int = 0
    }
}
