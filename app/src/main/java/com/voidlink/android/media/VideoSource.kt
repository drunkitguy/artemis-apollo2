package com.voidlink.android.media

import com.voidlink.android.data.StreamSettings
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * What the stream screen asks the session layer for.
 *
 * @property hostId [com.voidlink.android.data.KnownHost.uuid] of the host to stream from.
 * @property appId host-assigned id of the application to launch.
 * @property appName display name of the application, for logs and notifications.
 * @property format the format the decoder was successfully selected for. The session layer must
 *   negotiate this, or return something it *did* negotiate in
 *   [VideoSourceResult.Ready.format] so the decoder can be re-selected.
 * @property settings the merged (global + per-host) settings for this session.
 */
data class VideoSourceRequest(
    val hostId: String?,
    val appId: String?,
    val appName: String?,
    val format: VideoStreamFormat,
    val settings: StreamSettings,
)

/** The outcome of asking for a video stream. */
sealed interface VideoSourceResult {

    /**
     * The session is live and frames are on their way.
     *
     * @property frames complete decode units, per spec §7.8. The decoder drains this; closing it
     *   is how the session tells the decoder the stream is over.
     * @property format what was actually negotiated, which may differ from what was requested if
     *   the host clamped it. The stream screen re-runs decoder selection against this before
     *   configuring anything.
     * @property onClose tears the session down. Called exactly once, when the stream screen is
     *   finished — including when the user backs out mid-connect.
     */
    class Ready(
        val frames: ReceiveChannel<VideoFrame>,
        val format: VideoStreamFormat,
        val onClose: suspend () -> Unit,
    ) : VideoSourceResult

    /**
     * The session could not start.
     *
     * @property summary one sentence naming the cause, shown as the failure screen's body.
     * @property detail optional technical text (an error code, a host response) shown in the
     *   failure screen's small print.
     */
    data class Unavailable(val summary: String, val detail: String? = null) : VideoSourceResult
}

/**
 * Opens a video stream. **This is the seam the session layer plugs into.**
 *
 * The stream screen depends on this interface and nothing else from `protocol/`: it probes the
 * device's decoders, waits for a surface, asks a `VideoSourceFactory` for frames, and decodes what
 * arrives. Turning streaming on is therefore one assignment to
 * [VideoPipeline.videoSourceFactory] — the same shape as the three provider swaps in
 * `ServiceLocator` (architecture §2.3), and for the same reason: no UI change should be needed to
 * light up a protocol capability.
 */
interface VideoSourceFactory {
    /**
     * Starts a session and returns its frame channel.
     *
     * Called from the stream screen's coroutine scope, so it may suspend for as long as the
     * handshake takes; the screen shows a connecting state throughout. Implementations should
     * return [VideoSourceResult.Unavailable] rather than throwing, because a thrown exception has
     * no user-facing sentence in it.
     */
    suspend fun open(request: VideoSourceRequest): VideoSourceResult
}

/**
 * The default factory: no session layer is wired up.
 *
 * It exists so the stream screen has honest behaviour before the protocol work lands. This build
 * decodes video correctly and has no one to get video from, and saying exactly that beats a black
 * fullscreen window — which is indistinguishable from a crash, and which this app shipped once
 * already.
 */
object UnavailableVideoSourceFactory : VideoSourceFactory {
    override suspend fun open(request: VideoSourceRequest): VideoSourceResult =
        VideoSourceResult.Unavailable(
            summary = "There is no streaming session in this build yet. Pairing, your app " +
                "library and host controls all work, but the RTSP handshake and the video " +
                "receiver that would feed the decoder are not implemented.",
            detail = "Requested ${request.format.describe()}",
        )
}

/**
 * The video pipeline's swappable parts.
 *
 * Deliberately tiny and deliberately mutable: the stream screen reads these at session start, and
 * the session layer writes them once during application start-up. Keeping them here rather than in
 * `di/ServiceLocator` means the decode layer owns its own contract, and means a preview or an
 * instrumentation test can substitute a fake without touching the app's dependency graph.
 */
object VideoPipeline {

    /**
     * Where frames come from. Assign the real implementation during application start-up:
     *
     * ```kotlin
     * VideoPipeline.videoSourceFactory = StreamSessionVideoSource(serviceLocator)
     * ```
     */
    @Volatile
    var videoSourceFactory: VideoSourceFactory = UnavailableVideoSourceFactory

    /**
     * How the device's decoders are enumerated. Replaced only by tests and previews; production
     * always uses [MediaCodecProbe].
     */
    @Volatile
    var decoderProbe: DecoderProbe = MediaCodecProbe

    /** Restores both fields to their defaults. For tests. */
    fun resetForTesting() {
        videoSourceFactory = UnavailableVideoSourceFactory
        decoderProbe = MediaCodecProbe
    }
}
