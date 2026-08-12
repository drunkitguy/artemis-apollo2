package com.voidlink.android.media

/**
 * The phases a [VideoDecoderCore] moves through.
 *
 * The machine is small on purpose. Everything that can go wrong in decode lands in one of
 * [RECOVERING] (we are rebuilding the codec) or [FAILED] (we are not getting a picture), and the
 * UI only ever needs to distinguish those two from "running".
 */
enum class DecoderPhase {
    /** Created but not started. */
    IDLE,

    /** The codec is running and accepting frames. */
    RUNNING,

    /** A recoverable codec error is being handled by rebuilding the codec. */
    RECOVERING,

    /** The codec failed unrecoverably; the session cannot continue. */
    FAILED,

    /** Released. Terminal. */
    RELEASED,
}

/**
 * Something the decoder wants the rest of the app to know about.
 *
 * These drive three things: the stream screen's state (spinner → picture → failure), the control
 * stream's IDR requests ([KeyFrameRequested]), and the log. They are delivered on the codec
 * callback thread, so handlers must not block.
 */
sealed interface DecoderEvent {

    /** The codec configured and started. */
    data class Started(val decoderName: String, val format: VideoStreamFormat) : DecoderEvent

    /**
     * The first frame reached the surface.
     *
     * This, not "the codec started", is what ends the connecting spinner: a codec can start and
     * then never produce anything if the stream never arrives.
     */
    data object FirstFrameRendered : DecoderEvent

    /** The codec's output format settled or changed mid-stream. */
    data class FormatChanged(
        val width: Int,
        val height: Int,
        val description: String,
    ) : DecoderEvent

    /**
     * The decoder needs a keyframe before it can produce a picture again.
     *
     * The control stream must turn this into an IDR request (spec §9.5), **rate-limited to about
     * one per 100 ms** — this event can fire once per dropped frame, and an unthrottled IDR storm
     * makes a lossy link worse rather than better.
     */
    data class KeyFrameRequested(val reason: String) : DecoderEvent

    /** A transient codec error was handled by flushing. Streaming continues. */
    data class TransientError(val message: String) : DecoderEvent

    /** A recoverable codec error was handled by rebuilding the codec. Streaming continues. */
    data class Recovered(val message: String) : DecoderEvent

    /** The decoder cannot continue. The session must end and the user must be told why. */
    data class FatalError(val message: String, val cause: Throwable? = null) : DecoderEvent

    /** The decoder released its codec. */
    data object Released : DecoderEvent
}
