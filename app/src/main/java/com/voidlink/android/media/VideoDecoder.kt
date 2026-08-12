package com.voidlink.android.media

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.voidlink.android.protocol.ProtocolLog
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Decodes a GameStream video stream to a [Surface].
 *
 * This is the class the rest of the app uses. It owns the `decoder-cb` `HandlerThread` that
 * `MediaCodec` delivers its asynchronous callbacks on (architecture §3), wires a [MediaCodecDriver]
 * to a [VideoDecoderCore], and republishes the core's events as a [SharedFlow].
 *
 * ### Wiring the RTP layer to this — the input seam
 *
 * There are two equivalent ways in, and the producer picks whichever fits:
 *
 * ```kotlin
 * // 1. Push: the decoder is a VideoFrameSink.
 * val sink: VideoFrameSink = decoder
 * if (!sink.submit(frame)) controlStream.requestIdrFrame()
 *
 * // 2. Pull: hand the decoder a channel and let it drain it.
 * sessionScope.launch { decoder.consume(frameChannel) }
 * ```
 *
 * In both cases the producer builds a [VideoFrame] per spec §7.8 — the concatenated Annex-B bytes
 * of one decode unit, with [VideoFrame.keyFrame] set on IRAP frames — and hands over ownership.
 * The decoder calls [VideoFrame.release] when it is done with the bytes, which is where a pooled
 * receive buffer goes back to its pool. A `false` from `submit` means the frame was dropped for
 * lack of capacity and the host should be asked for an IDR (rate-limited to ~1/100 ms, spec §9.5);
 * [DecoderEvent.KeyFrameRequested] carries the same request for drops the decoder discovers on its
 * own, so a producer that watches [events] does not need to check the return value as well.
 *
 * ### Lifecycle
 *
 * The decoder must never outlive its surface. [release] is idempotent and synchronous precisely so
 * that `SurfaceHolder.Callback.surfaceDestroyed` can call it and be sure the codec is gone before
 * it returns.
 *
 * @param choice the decoder and format chosen by [DecoderSelector].
 * @param surface the render target.
 * @param clock timestamp source; overridable for tests.
 * @param driverFactory builds the codec driver. Defaults to [MediaCodecDriver]; overridden in
 *   tests and previews, which have no media stack.
 */
class VideoDecoder(
    private val choice: DecoderChoice,
    private val surface: Surface,
    private val clock: MediaClock = MediaClock.SYSTEM,
    private val driverFactory: (DecoderChoice, Surface, Handler) -> CodecDriver =
        { decoderChoice, target, handler -> MediaCodecDriver(decoderChoice, target, handler) },
) : VideoFrameSink {

    private val eventsFlow = MutableSharedFlow<DecoderEvent>(
        replay = REPLAY_EVENTS,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var handlerThread: HandlerThread? = null
    private var core: VideoDecoderCore? = null

    @Volatile
    private var released = false

    /** Everything the decoder reports. Replays recent events so a late collector still sees them. */
    val events: SharedFlow<DecoderEvent> = eventsFlow

    /** The platform name of the codec in use, for the stats overlay. */
    val decoderName: String get() = choice.candidate.name

    /** The format being decoded, after any capability downgrade. */
    val format: VideoStreamFormat get() = choice.format

    /** Plain-language notes about how [format] departs from what the user asked for. */
    val notes: List<String> get() = choice.notes

    /**
     * Starts the callback thread and the codec.
     *
     * @return true when the decoder is ready for frames. A false has already published a
     *   [DecoderEvent.FatalError] explaining why, which the stream screen shows verbatim.
     */
    fun start(): Boolean {
        if (released) return false
        if (core != null) return core?.isRunning == true

        val thread = HandlerThread(CALLBACK_THREAD_NAME, Process.THREAD_PRIORITY_URGENT_DISPLAY)
        thread.start()
        handlerThread = thread

        val driver = driverFactory(choice, surface, Handler(thread.looper))
        val created = VideoDecoderCore(
            driver = driver,
            format = choice.format,
            clock = clock,
            onEvent = { event -> publish(event) },
        )
        core = created
        return created.start()
    }

    override fun submit(frame: VideoFrame): Boolean {
        val active = core
        if (active == null) {
            frame.release()
            return false
        }
        return active.submit(frame)
    }

    /**
     * Drains [frames] into the decoder until the channel closes or the coroutine is cancelled.
     *
     * Cancellation is the surface-loss path: the stream screen cancels this, releases the decoder
     * and re-runs the whole thing against the new surface, which is why this function does not
     * close the channel — the frames belong to the session, not to one decoder instance.
     */
    suspend fun consume(frames: ReceiveChannel<VideoFrame>) {
        for (frame in frames) {
            submit(frame)
        }
    }

    /** Drops everything in flight and asks for a keyframe. See [VideoDecoderCore.flush]. */
    fun flush() {
        core?.flush()
    }

    /** Current metrics, advancing the rate window. */
    fun stats(): DecoderStats = core?.stats() ?: DecoderStats.EMPTY

    /**
     * Releases the codec and the callback thread, synchronously.
     *
     * Idempotent, and safe to call before [start]. Must be called before the [Surface] is
     * destroyed — see the class documentation.
     */
    fun release() {
        if (released) return
        released = true
        core?.release()
        core = null
        val thread = handlerThread
        handlerThread = null
        if (thread != null) {
            thread.quitSafely()
        }
    }

    private fun publish(event: DecoderEvent) {
        when (event) {
            is DecoderEvent.FatalError -> ProtocolLog.e(MediaCodecProbe.TAG, event.message, event.cause)
            is DecoderEvent.TransientError -> ProtocolLog.w(MediaCodecProbe.TAG, event.message)
            is DecoderEvent.Recovered -> ProtocolLog.w(MediaCodecProbe.TAG, event.message)
            is DecoderEvent.Started -> ProtocolLog.i(
                MediaCodecProbe.TAG,
                "Decoder ${event.decoderName} started for ${event.format.describe()}",
            )
            is DecoderEvent.FormatChanged -> ProtocolLog.i(
                MediaCodecProbe.TAG,
                "Decoder output format is now ${event.width}×${event.height}",
            )
            else -> Unit
        }
        eventsFlow.tryEmit(event)
    }

    private companion object {
        /** Name of the codec callback thread, matching the architecture §3 thread table. */
        const val CALLBACK_THREAD_NAME: String = "decoder-cb"

        /** Events replayed to a late collector. Enough to cover start-up. */
        const val REPLAY_EVENTS: Int = 8

        /** Extra buffered events before the oldest is dropped. */
        const val EVENT_BUFFER: Int = 64
    }
}
