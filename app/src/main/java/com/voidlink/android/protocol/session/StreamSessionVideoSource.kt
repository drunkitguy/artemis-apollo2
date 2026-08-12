package com.voidlink.android.protocol.session

import com.voidlink.android.media.MediaClock
import com.voidlink.android.media.VideoCodecType
import com.voidlink.android.media.VideoFrame as DecodeUnit
import com.voidlink.android.media.VideoSourceFactory
import com.voidlink.android.media.VideoSourceRequest
import com.voidlink.android.media.VideoSourceResult
import com.voidlink.android.media.VideoStreamFormat
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.rtsp.NegotiatedSession
import com.voidlink.android.protocol.rtsp.VideoCodec
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * The seam, filled: a [VideoSourceFactory] backed by a real [StreamSession].
 *
 * `media/VideoSource.kt` describes this as "one assignment to `VideoPipeline.videoSourceFactory`",
 * and that is exactly what wiring it costs — the stream screen keeps depending on nothing from
 * `protocol/` and this class does the only two conversions the boundary needs:
 *
 * * **Format** — the decoder's [VideoStreamFormat] in, the *negotiated* one back out. The stream
 *   screen re-runs decoder selection when they differ, which is what makes a host clamping our
 *   request survivable rather than fatal.
 * * **Frames** — [com.voidlink.android.protocol.rtp.VideoFrame] (a reassembled Annex-B fragment
 *   with an RTP frame index) to [com.voidlink.android.media.VideoFrame] (a decode unit with a
 *   release hook). Both wrap the same `ByteArray`; nothing is copied.
 *
 * Failures are returned as [VideoSourceResult.Unavailable] carrying [SessionFailure]'s own
 * sentences, never thrown: the stream screen puts `summary` on the failure screen and `detail` in
 * its small print, and a thrown exception has no user-facing sentence in it.
 *
 * @param launcher NVHTTP resolve + `/launch`; supplied by the service locator.
 * @param sessionFactory how a session is built. Injectable so a test can drive the whole factory
 *   with fakes, and so the production graph can pass its own gateways.
 */
class StreamSessionVideoSource(
    private val launcher: SessionLauncher,
    private val sessionFactory: (SessionLauncher) -> StreamSession = { StreamSession(it) },
) : VideoSourceFactory {

    override suspend fun open(request: VideoSourceRequest): VideoSourceResult {
        val session = sessionFactory(launcher)
        val sessionRequest = StreamSessionRequest(
            hostId = request.hostId,
            appId = request.appId,
            appName = request.appName,
            settings = request.settings,
            width = request.format.width,
            height = request.format.height,
            fps = request.format.frameRate,
            codec = codecFor(request.format.codec),
            hdr = request.format.hdr,
        )

        return when (val result = session.start(sessionRequest)) {
            is StreamSessionResult.Failed -> {
                val failure = result.failure
                VideoSourceResult.Unavailable(
                    summary = failure.summary,
                    detail = listOfNotNull(
                        failure.detail,
                        "Stage: ${failure.stage.label}.",
                        if (failure.recoverable) "Retrying may work." else null,
                    ).joinToString(" "),
                )
            }

            is StreamSessionResult.Started -> {
                val active = result.session
                val decodeUnits = Channel<DecodeUnit>(FORWARD_QUEUE_CAPACITY)
                val scope = CoroutineScope(
                    SupervisorJob() + Dispatchers.Default + CoroutineName(FORWARD_NAME),
                )
                scope.launch {
                    for (frame in active.frames) {
                        val unit = DecodeUnit(
                            data = frame.data,
                            frameNumber = frame.frameIndex.toInt(),
                            keyFrame = frame.isKeyFrame,
                            receivedAtMicros = MediaClock.SYSTEM.nowMicros(),
                        )
                        if (decodeUnits.trySend(unit).isSuccess) continue
                        // The session's own queue already dropped the oldest and asked for a
                        // keyframe; here we only make sure the newest frame wins.
                        decodeUnits.tryReceive()
                        if (decodeUnits.trySend(unit).isFailure) break
                    }
                    decodeUnits.close()
                }

                VideoSourceResult.Ready(
                    frames = decodeUnits,
                    format = formatOf(active.negotiated, request.format),
                    onClose = {
                        // Runs from the stream screen's `finally`, which may already be cancelled;
                        // the teardown of spec §9.7 must complete anyway.
                        withContext(NonCancellable) {
                            active.stop()
                            scope.cancel()
                            decodeUnits.close()
                        }
                        active.endReason?.let {
                            ProtocolLog.i(SessionConstants.TAG, "session ended: ${it.describe()}")
                        }
                    },
                )
            }
        }
    }

    /** The decoder's codec enum to the protocol's (spec §7.1). */
    private fun codecFor(codec: VideoCodecType): VideoCodec = when (codec) {
        VideoCodecType.H264 -> VideoCodec.H264
        VideoCodecType.HEVC -> VideoCodec.HEVC
        VideoCodecType.AV1 -> VideoCodec.AV1
    }

    /** The protocol's codec enum back to the decoder's. */
    private fun codecTypeFor(codec: VideoCodec): VideoCodecType = when (codec) {
        VideoCodec.H264 -> VideoCodecType.H264
        VideoCodec.HEVC -> VideoCodecType.HEVC
        VideoCodec.AV1 -> VideoCodecType.AV1
    }

    /**
     * What was actually negotiated, which the stream screen compares against what it asked for.
     *
     * @param requested the format the decoder was selected for, used only for the log line that
     *   says a host changed something under us.
     */
    private fun formatOf(
        session: NegotiatedSession,
        requested: VideoStreamFormat,
    ): VideoStreamFormat {
        val negotiated = VideoStreamFormat(
            codec = codecTypeFor(session.codec),
            width = session.width,
            height = session.height,
            frameRate = session.fps,
            hdr = session.hdr,
        )
        if (negotiated != requested) {
            ProtocolLog.i(
                SessionConstants.TAG,
                "the host negotiated ${negotiated.describe()} rather than the requested " +
                    "${requested.describe()}; the decoder will be re-selected",
            )
        }
        return negotiated
    }

    private companion object {
        /**
         * Architecture §3 rule 1 again, on the second hop.
         *
         * The session's own output queue is the one that matters; this one exists because the
         * decode-unit type belongs to `media/` and the frame type belongs to `protocol/rtp/`, and
         * neither layer should learn about the other to save a channel.
         */
        const val FORWARD_QUEUE_CAPACITY: Int = 2

        const val FORWARD_NAME: String = "video-forward"
    }
}

/**
 * Builds the production factory.
 *
 * A free function rather than a constructor default so `di/ServiceLocator` stays the only place
 * that knows which concrete gateways the app uses, and so a preview or an instrumentation test can
 * assemble a different set without touching this file.
 */
fun streamSessionVideoSource(
    launcher: SessionLauncher,
    random: SecureRandom = SecureRandom(),
): VideoSourceFactory = StreamSessionVideoSource(
    launcher = launcher,
    sessionFactory = { StreamSession(launcher = it, random = random) },
)
