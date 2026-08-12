package com.voidlink.android.ui.stream

import com.voidlink.android.media.CodecSupport
import com.voidlink.android.media.DecoderStats
import com.voidlink.android.media.VideoStreamFormat

/**
 * The steps the stream screen walks through before a picture appears.
 *
 * Each has its own label because a bare spinner tells the user nothing about *where* a slow
 * connection is stuck, and because the step names are the first thing to look at when a connection
 * takes too long (architecture §4.1).
 *
 * @property label the sentence shown under the progress indicator.
 */
enum class StreamPreparationStep(val label: String) {
    /** Loading the merged global + per-host settings. */
    READING_SETTINGS("Reading your settings…"),

    /** Enumerating the device's video decoders. */
    PROBING_DECODER("Checking video decoders…"),

    /** Waiting for the `SurfaceView` to hand over a render target. */
    WAITING_FOR_SURFACE("Preparing the display…"),

    /** The session layer is negotiating with the host. */
    STARTING_SESSION("Starting the session…"),

    /** Connected; nothing has been decoded yet. */
    WAITING_FOR_VIDEO("Waiting for the first frame…"),
}

/** Which of the three things the stream screen can be doing. */
sealed interface StreamPhase {

    /** Getting ready. Shows a progress indicator and [step]'s label. */
    data class Preparing(val step: StreamPreparationStep) : StreamPhase

    /**
     * A picture is on screen.
     *
     * @property decoderName the platform codec name, shown in the stats overlay.
     * @property notes plain-language capability downgrades from [com.voidlink.android.media.DecoderChoice.notes],
     *   shown briefly so a fallback is never silent.
     */
    data class Streaming(
        val decoderName: String,
        val notes: List<String>,
    ) : StreamPhase

    /**
     * Something went wrong, and the user is being told what.
     *
     * **This is the state that must never be replaced by a black screen.** Every failure path in
     * [StreamController] ends here with all three fields populated.
     *
     * @property title a short heading, e.g. "This device cannot decode the stream".
     * @property message one or two sentences naming the cause and, where there is one, the fix.
     * @property detail optional technical small print: the decoders that were inspected, a host
     *   error code. Present for a bug report, not for the user to act on.
     */
    data class Failed(
        val title: String,
        val message: String,
        val detail: String? = null,
    ) : StreamPhase
}

/**
 * Everything the stream screen draws.
 *
 * @property appName the application being streamed, echoed back so the screen is visibly a
 *   response to the user's tap rather than a generic dialog.
 * @property phase what the screen is doing.
 * @property surfaceFormat the stream's dimensions once a decoder has been chosen, or `null`
 *   before that. Non-null is the signal to mount the `SurfaceView`: the surface must exist before
 *   the session starts, because the decoder cannot be configured without one.
 * @property stats latest decode metrics, refreshed at 2 Hz per UI spec §5.2.
 * @property showStats whether the stats chip is drawn, mirroring
 *   [com.voidlink.android.data.StreamSettings.showStatsOverlay].
 * @property codecSupport what this device reported for AV1, HEVC and H.264 — whether a decoder
 *   exists, whether it is hardware, its maximum resolution and frame rate, and whether it does
 *   10-bit. Populated as soon as the probe runs, so it is available whether the session succeeds
 *   or fails, and shown on the failure screen: a user picking a codec in Settings is guessing
 *   unless they can see which of the three their hardware can actually decode.
 */
data class StreamUiState(
    val appName: String? = null,
    val phase: StreamPhase = StreamPhase.Preparing(StreamPreparationStep.READING_SETTINGS),
    val surfaceFormat: VideoStreamFormat? = null,
    val stats: DecoderStats = DecoderStats.EMPTY,
    val showStats: Boolean = false,
    val codecSupport: List<CodecSupport> = emptyList(),
)
