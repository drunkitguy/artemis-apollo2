package com.voidlink.android.media

/**
 * A codec error, classified the way `MediaCodec.CodecException` classifies it.
 *
 * Spec §12.1 makes the distinction load-bearing: transient means retry, recoverable means
 * stop/reconfigure/start, and neither means the session is over. Modelling it as data rather than
 * as the exception itself is what lets [VideoDecoderCore]'s recovery path be tested without a
 * codec.
 *
 * @property message a short description for logs and the failure screen.
 * @property transient the codec expects the operation to succeed if retried later.
 * @property recoverable the codec can be used again after being reset.
 * @property diagnosticInfo the vendor's diagnostic string, when there is one. Never shown to the
 *   user as the primary message — it is usually a numeric vendor code — but always logged.
 */
data class CodecFailure(
    val message: String,
    val transient: Boolean = false,
    val recoverable: Boolean = false,
    val diagnosticInfo: String? = null,
)

/**
 * The callbacks an asynchronous codec delivers.
 *
 * [VideoDecoderCore] implements this; [MediaCodecDriver] adapts `MediaCodec.Callback` onto it.
 * Every method is called on the codec's callback thread (the `decoder-cb` `HandlerThread`), and
 * none of them may block.
 */
interface CodecEventSink {

    /** An input buffer is free. Its index stays valid until it is passed to [CodecDriver.submit]. */
    fun onInputBufferAvailable(index: Int)

    /**
     * A decoded frame is ready.
     *
     * @param index the output buffer index, to be released with [CodecDriver.render].
     * @param presentationTimeUs the timestamp the frame was submitted with.
     * @param endOfStream whether the codec flagged this buffer as the end of the stream.
     */
    fun onOutputBufferAvailable(index: Int, presentationTimeUs: Long, endOfStream: Boolean)

    /** The codec's output format settled or changed. */
    fun onOutputFormatChanged(width: Int, height: Int, description: String)

    /** The codec failed. */
    fun onCodecFailure(failure: CodecFailure)
}

/**
 * Everything [VideoDecoderCore] needs from a codec, and nothing more.
 *
 * This interface is the boundary that makes the decoder testable. `MediaCodec` cannot run in CI —
 * there is no emulator here — so the decoder's queueing, keyframe gating, drop accounting and
 * error recovery all live in [VideoDecoderCore] against this interface, and the parts that genuinely
 * need Android live in [MediaCodecDriver] behind it. A fake implementation in the unit tests
 * drives every state transition.
 *
 * Threading: [start] and [release] are called from the owning thread; [submit], [render],
 * [discard] and [flush] are called from the codec callback thread. Implementations must tolerate
 * being called after [release] by returning quietly rather than throwing.
 */
interface CodecDriver {

    /** The platform name of the underlying codec, for logs and the stats overlay. */
    val name: String

    /**
     * Creates, configures and starts the codec, wiring its callbacks to [sink].
     *
     * @throws Exception when the codec cannot be created or configured at all. The caller treats
     *   that as fatal: there is no picture without a codec.
     */
    fun start(sink: CodecEventSink)

    /**
     * Copies [frame] into input buffer [bufferIndex] and queues it.
     *
     * @param presentationTimeUs the timestamp to tag the buffer with. Per spec §7.8 this is a
     *   monotonic microsecond value used for correlation only; nothing schedules on it.
     * @return `false` when the frame could not be queued — most often because the frame is larger
     *   than the input buffer. The buffer is returned to the codec either way, so a `false` costs
     *   a frame, not the session.
     */
    fun submit(bufferIndex: Int, frame: VideoFrame, presentationTimeUs: Long): Boolean

    /**
     * Releases output buffer [bufferIndex] **to the surface, immediately**.
     *
     * Spec §12.1: the timestamped `releaseOutputBuffer` variant schedules the frame and adds a
     * frame of latency, so implementations must use the immediate one.
     */
    fun render(bufferIndex: Int)

    /** Releases output buffer [bufferIndex] without rendering it. */
    fun discard(bufferIndex: Int)

    /**
     * Drops everything in flight and makes the codec ready for a fresh keyframe.
     *
     * Implementations must leave the codec running: in asynchronous mode `MediaCodec.flush()`
     * stops the callbacks until `start()` is called again, and forgetting that is a decoder that
     * goes permanently silent after the first transient error.
     */
    fun flush()

    /**
     * Tears the codec down and builds a new one, for a recoverable error.
     *
     * @throws Exception when the codec cannot be rebuilt, which escalates to a fatal error.
     */
    fun restart()

    /** Releases the codec. Idempotent; safe to call from any state. */
    fun release()
}
