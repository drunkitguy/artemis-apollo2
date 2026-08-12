package com.voidlink.android.media.audio

import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.audio.AudioChannel
import com.voidlink.android.protocol.audio.AudioDepacketizerConfig
import com.voidlink.android.protocol.audio.AudioReceiver
import com.voidlink.android.protocol.audio.AudioSamplePipeline
import com.voidlink.android.protocol.audio.AudioStreamEvent
import com.voidlink.android.protocol.audio.AudioStreamStats
import com.voidlink.android.protocol.audio.OpusChannelOrder
import com.voidlink.android.protocol.audio.RtpAudioConstants
import com.voidlink.android.protocol.audio.UnverifiedRtpAudioConstants
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * What the session asks the audio layer for — everything RTSP negotiated, and nothing else.
 *
 * @property host the host to receive from and keep alive, unbracketed even for IPv6:
 *   `NegotiatedSession.host`.
 * @property port `NegotiatedSession.audioPort`.
 * @property pingPayload `NegotiatedSession.audioPingPayload`; `null` for the legacy `PING`
 *   (spec §7.5).
 * @property channelCount `opusConfig.channelCount` — 2, 6 or 8 (spec §8.2).
 * @property streams `opusConfig.streams`.
 * @property coupledStreams `opusConfig.coupledStreams`.
 * @property mapping `opusConfig.mapping`. **Already in playback order.**
 *   [com.voidlink.android.protocol.rtsp.OpusMultistreamConfig] applies spec §8.3's channel-order
 *   fix-up while parsing the DESCRIBE body, so this must not be reordered again — doing so would
 *   move LFE a second time and put centre-channel dialogue in a surround speaker. See
 *   [OpusChannelOrder].
 * @property sampleRateHz `opusConfig.sampleRateHz`; always 48 000 (spec §8.3).
 * @property packetDurationMs `StreamConfiguration.audioPacketDurationMs` — 5, or 10 for a slow
 *   decoder (spec §8.5).
 * @property audioEncryptionNegotiated whether `SS_ENC_AUDIO` appeared in the negotiated
 *   `encryptionFlags` (spec §6.5). v1 negotiates none; a host that turned it on gets an explained
 *   unavailability rather than full-scale noise.
 */
class AudioSourceRequest(
    val host: String,
    val port: Int,
    val pingPayload: String?,
    val channelCount: Int,
    val streams: Int,
    val coupledStreams: Int,
    val mapping: IntArray,
    val sampleRateHz: Int = RtpAudioConstants.SAMPLE_RATE_HZ,
    val packetDurationMs: Int = RtpAudioConstants.DEFAULT_PACKET_DURATION_MS,
    val audioEncryptionNegotiated: Boolean = false,
)

/**
 * Everything a caller can see of a running audio stream.
 *
 * @property receive counters from the socket and depacketizer (spec §8.4).
 * @property playback counters from the decoder and output device (spec §8.5).
 * @property samplesDroppedByBackpressure packets evicted between the two, because the decoder was
 *   not draining fast enough.
 */
class AudioSessionStats(
    val receive: AudioStreamStats,
    val playback: AudioPlaybackStats,
    val samplesDroppedByBackpressure: Long,
) {
    /** One line for the log and the stats overlay. */
    fun describe(): String =
        "rx: pkts=${receive.packetsReceived} lost=${receive.packetsLost} " +
            "concealed=${receive.samplesConcealed} rejected=${receive.packetsRejected} " +
            "backpressure=$samplesDroppedByBackpressure | play: ${playback.describe()}"
}

/** The outcome of asking for an audio stream. */
sealed interface AudioSourceResult {

    /**
     * Audio is live and playing.
     *
     * @property format what is being played, after any capability decision.
     * @property stats the current counters. Safe to call from any thread, at any cadence.
     * @property onClose stops the receive threads, releases the socket, the codec and the output
     *   device. Called exactly once, by the session's teardown.
     */
    class Ready(
        val format: AudioStreamFormat,
        val stats: () -> AudioSessionStats,
        val onClose: suspend () -> Unit,
    ) : AudioSourceResult

    /**
     * There will be no audio.
     *
     * **This is never a reason to fail the session.** A stream with no audio is far better than no
     * stream, so every path that cannot produce audio arrives here rather than throwing.
     *
     * @property summary one sentence naming the cause, suitable for a log line or a toast.
     * @property detail optional technical text.
     */
    data class Unavailable(val summary: String, val detail: String? = null) : AudioSourceResult
}

/**
 * Opens an audio stream. **This is the seam the session layer plugs into.**
 *
 * The whole attachment is one call and one close, taken from what RTSP already negotiated:
 *
 * ```kotlin
 * // in StreamSession, after the video receiver is up:
 * val audio = AudioPipeline.audioSourceFactory.open(
 *     AudioSourceRequest(
 *         host = session.host,
 *         port = session.audioPort,
 *         pingPayload = session.audioPingPayload,
 *         channelCount = session.opusConfig.channelCount,
 *         streams = session.opusConfig.streams,
 *         coupledStreams = session.opusConfig.coupledStreams,
 *         mapping = session.opusConfig.mapping,
 *         sampleRateHz = session.opusConfig.sampleRateHz,
 *         packetDurationMs = parameters.configuration.audioPacketDurationMs,
 *         audioEncryptionNegotiated =
 *             session.encryptionFlags and UnverifiedRtspConstants.SS_ENC_AUDIO != 0,
 *     ),
 * )
 * when (audio) {
 *     is AudioSourceResult.Ready -> audioStream = audio          // and audio.onClose() in teardown
 *     is AudioSourceResult.Unavailable ->
 *         ProtocolLog.w(SessionConstants.TAG, "no audio: ${audio.summary}")
 * }
 * ```
 *
 * The session keeps the `Ready` and calls `onClose()` in its teardown, between closing the control
 * channel and closing the video socket — the ordering is not load-bearing, because the audio socket
 * is independent of both. Nothing else is required, and nothing in `protocol/session` needs to know
 * what an Opus packet is.
 */
interface AudioSourceFactory {
    /**
     * Starts receiving and playing audio.
     *
     * Returns quickly — it opens a socket, configures a codec and starts two threads, and does not
     * wait for the first packet. Implementations must return [AudioSourceResult.Unavailable] rather
     * than throwing, for the reason that type's documentation gives.
     */
    suspend fun open(request: AudioSourceRequest): AudioSourceResult
}

/**
 * The audio pipeline's swappable parts, mirroring [com.voidlink.android.media.VideoPipeline].
 *
 * Unlike the video one this defaults to the **real** implementation rather than to a placeholder:
 * the audio path needs no `Context`, no surface and no decoder probe, so there is nothing for a
 * dependency graph to inject and nothing for a preview to break.
 */
object AudioPipeline {

    /** Where audio comes from. Replaced only by tests. */
    @Volatile
    var audioSourceFactory: AudioSourceFactory = DefaultAudioSourceFactory()

    /** Restores the default. For tests. */
    fun resetForTesting() {
        audioSourceFactory = DefaultAudioSourceFactory()
    }
}

/**
 * The production [AudioSourceFactory]: socket, depacketizer, decoder and output device.
 *
 * ### Why every failure here is a shrug
 *
 * Audio is the one part of a streaming session that is genuinely optional. A device with no Opus
 * decoder, a host that turned on audio encryption, a surround layout `MediaCodec` cannot be trusted
 * with, an `AudioTrack` the platform refuses to build — every one of these produces an
 * [AudioSourceResult.Unavailable] with a sentence in it, and a session that streams video exactly
 * as it would have anyway.
 *
 * @param driverFactory how the codec and output device are built. Injectable so a test can drive
 *   the whole factory without an Android runtime.
 * @param receiverFactory how the UDP socket is opened. Injectable for the same reason.
 * @param multistreamEnabled whether surround is attempted. Defaults to spec §8.5's v1 decision, in
 *   [UnverifiedRtpAudioConstants.MULTISTREAM_PLAYBACK_ENABLED].
 */
class DefaultAudioSourceFactory(
    private val driverFactory: AudioCodecDriverFactory = PlatformAudioCodecDriverFactory,
    private val receiverFactory: (AudioSourceRequest, AudioSamplePipeline) -> AudioChannel =
        ::openUdpAudioChannel,
    private val multistreamEnabled: Boolean =
        UnverifiedRtpAudioConstants.MULTISTREAM_PLAYBACK_ENABLED,
) : AudioSourceFactory {

    override suspend fun open(request: AudioSourceRequest): AudioSourceResult = try {
        openOrExplain(request)
    } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        // The last line of defence. Nothing below is supposed to throw, and if something does, the
        // session must still stream.
        ProtocolLog.w(RtpAudioConstants.LOG_TAG_AUDIO, "opening the audio stream threw", failure)
        AudioSourceResult.Unavailable(
            summary = "Audio could not be started, so this session is video only.",
            detail = failure.message ?: failure.javaClass.simpleName,
        )
    }

    private fun openOrExplain(request: AudioSourceRequest): AudioSourceResult {
        if (request.audioEncryptionNegotiated) {
            return AudioSourceResult.Unavailable(
                summary = "The host encrypted the audio stream, which this build cannot decrypt, " +
                    "so this session is video only.",
                detail = "SS_ENC_AUDIO was set in the negotiated encryption flags (spec §6.5).",
            )
        }

        val channelMask = OpusChannelOrder.maskFor(request.channelCount)
            ?: return AudioSourceResult.Unavailable(
                summary = "The host announced a ${request.channelCount}-channel layout this " +
                    "client cannot place, so this session is video only.",
                detail = "Spec §8.2 tabulates stereo, 5.1 and 7.1 only.",
            )

        val format = AudioStreamFormat(
            channelCount = request.channelCount,
            sampleRateHz = request.sampleRateHz,
            streams = request.streams,
            coupledStreams = request.coupledStreams,
            // Copied: the caller's array belongs to the negotiated session, which outlives us, and
            // a mapping that changed under the codec after configuration would be undiagnosable.
            mapping = request.mapping.copyOf(),
            packetDurationMs = request.packetDurationMs,
        )

        if (format.isMultistream) {
            OpusChannelOrder.announceSurround(format.channelCount, format.mapping)
            if (!multistreamEnabled) {
                return AudioSourceResult.Unavailable(
                    summary = "Surround audio is not played by this build, so this session is " +
                        "video only.",
                    detail = "Spec §8.5: multistream Opus through MediaCodec is unreliable, and " +
                        "v1 plays stereo only. Choose stereo in settings to get audio.",
                )
            }
        }

        val driver = try {
            driverFactory.create(format, channelMask)
        } catch (failure: Throwable) {
            return AudioSourceResult.Unavailable(
                summary = "This device has no usable Opus decoder, so this session is video only.",
                detail = failure.message ?: failure.javaClass.simpleName,
            )
        }

        var stopReason: AudioCodecFailure? = null
        val core = AudioDecoderCore(
            driver = driver,
            format = format,
            onEvent = { event -> onPlaybackEvent(event)?.let { stopReason = it } },
        )
        if (!core.start()) {
            core.release()
            return AudioSourceResult.Unavailable(
                summary = "The Opus decoder could not be configured, so this session is video " +
                    "only.",
                detail = stopReason?.describeForUser()
                    ?: "MediaCodec or AudioTrack refused the negotiated format.",
            )
        }

        val pipeline = AudioSamplePipeline(
            config = AudioDepacketizerConfig(
                packetDurationMs = request.packetDurationMs,
                audioEncryptionNegotiated = request.audioEncryptionNegotiated,
            ),
        )
        val receiver = try {
            receiverFactory(request, pipeline)
        } catch (failure: Throwable) {
            core.release()
            return AudioSourceResult.Unavailable(
                summary = "The audio socket could not be opened, so this session is video only.",
                detail = failure.message ?: failure.javaClass.simpleName,
            )
        }

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName(PUMP_NAME),
        )
        scope.launch { pumpSamples(receiver, core) }
        scope.launch { pumpEvents(receiver, format.packetDurationMs) }

        return AudioSourceResult.Ready(
            format = format,
            stats = {
                AudioSessionStats(
                    receive = receiver.stats(),
                    playback = core.stats(),
                    samplesDroppedByBackpressure = pipeline.samplesDroppedByBackpressure,
                )
            },
            onClose = {
                // Runs from the session's teardown, which may already be cancelled; releasing the
                // codec and the socket must happen anyway.
                withContext(NonCancellable) {
                    runCatching { receiver.close() }
                    core.release()
                    scope.cancel()
                }
                ProtocolLog.i(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "audio stopped: " + AudioSessionStats(
                        receive = receiver.stats(),
                        playback = core.stats(),
                        samplesDroppedByBackpressure = pipeline.samplesDroppedByBackpressure,
                    ).describe(),
                )
            },
        )
    }

    /**
     * Feeds the decoder, and keeps draining once it has given up.
     *
     * Two deliberate choices once the decoder has failed:
     *
     * * **Stop submitting.** Continuing would be a decode attempt per packet, two hundred times a
     *   second, against a codec that has already said it cannot.
     * * **Keep draining, and keep the socket open.** The samples are discarded rather than left to
     *   pile up, so the pipeline's backpressure counter stays meaningful; and the receiver keeps
     *   its keep-alive running, because a host that treats a silent audio port as a dead client
     *   would end the whole session — which is precisely the outcome this layer exists to avoid.
     */
    private suspend fun pumpSamples(receiver: AudioChannel, core: AudioDecoderCore) {
        for (sample in receiver.samples) {
            if (!core.isRunning) continue
            core.submit(
                data = sample.data,
                offset = 0,
                length = sample.length,
                concealment = sample.concealment,
            )
        }
    }

    /** Logs what the receive path reports. Nothing acts on it: audio loss asks the host for nothing. */
    private suspend fun pumpEvents(receiver: AudioChannel, packetDurationMs: Int) {
        for (event in receiver.events) {
            when (event) {
                is AudioStreamEvent.TocChanged -> ProtocolLog.w(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "the Opus TOC byte changed from 0x${event.previous.toString(HEX_RADIX)} to " +
                        "0x${event.current.toString(HEX_RADIX)}; spec §8.5 says it must not " +
                        "(Sunshine legitimately may, GFE must not)",
                )

                is AudioStreamEvent.Resynchronised -> ProtocolLog.i(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "audio resynchronised past ${event.skipped} lost packets rather than " +
                        "concealing them, which would have added " +
                        "${event.skipped * packetDurationMs}ms of delay",
                )

                is AudioStreamEvent.BlockIncomplete -> ProtocolLog.d(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "audio FEC block ${event.baseSequenceNumber} released with " +
                        "${event.dataShardsReceived}/4 data and " +
                        "${event.parityShardsReceived}/2 parity shards",
                )

                is AudioStreamEvent.PacketsLost -> ProtocolLog.d(
                    RtpAudioConstants.LOG_TAG_AUDIO,
                    "lost ${event.count} audio packets from ${event.firstSequenceNumber}, " +
                        "concealed ${event.concealed}",
                )
            }
        }
    }

    /** @return the failure when audio has stopped for good, `null` otherwise. */
    private fun onPlaybackEvent(event: AudioPlaybackEvent): AudioCodecFailure? = when (event) {
        is AudioPlaybackEvent.Started -> {
            ProtocolLog.i(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "audio playing through ${event.decoderName}: ${event.format.describe()}",
            )
            null
        }

        is AudioPlaybackEvent.FirstPacketPlayed -> {
            ProtocolLog.i(RtpAudioConstants.LOG_TAG_AUDIO, "first audio packet reached the speaker")
            null
        }

        is AudioPlaybackEvent.BacklogTrimmed -> {
            ProtocolLog.i(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "dropped ${event.dropped} audio packets to stop playback drifting behind video; " +
                    "backlog is back to ${event.backlogMs}ms",
            )
            null
        }

        is AudioPlaybackEvent.Underrun -> {
            ProtocolLog.d(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "the audio device ran dry (${event.count} times so far)",
            )
            null
        }

        is AudioPlaybackEvent.Stopped -> {
            ProtocolLog.w(
                RtpAudioConstants.LOG_TAG_AUDIO,
                "audio has stopped and the session continues without it: " +
                    event.failure.describeForUser(),
            )
            event.failure
        }
    }

    private companion object {
        const val PUMP_NAME: String = "audio-dec"
        const val HEX_RADIX: Int = 16
    }
}

/**
 * Opens the real UDP audio socket (spec §7.5, §8.1).
 *
 * A free function rather than a method so that [DefaultAudioSourceFactory] can be constructed with a
 * fake in one argument, and so the socket is the only thing a test has to replace to exercise the
 * whole factory.
 *
 * @throws java.io.IOException when the socket cannot be opened or bound.
 */
fun openUdpAudioChannel(
    request: AudioSourceRequest,
    pipeline: AudioSamplePipeline,
): AudioChannel {
    val receiver = AudioReceiver(
        hostAddress = InetAddress.getByName(request.host),
        audioPort = request.port,
        pingPayload = request.pingPayload,
        pipeline = pipeline,
    )
    receiver.start()
    return receiver
}
