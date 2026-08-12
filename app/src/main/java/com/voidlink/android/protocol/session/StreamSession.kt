package com.voidlink.android.protocol.session

import com.voidlink.android.data.StreamSettings
import com.voidlink.android.media.audio.AudioPipeline
import com.voidlink.android.media.audio.AudioSessionStats
import com.voidlink.android.media.audio.AudioSourceRequest
import com.voidlink.android.media.audio.AudioSourceResult
import com.voidlink.android.media.audio.AudioStreamFormat
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.control.ControlConstants
import com.voidlink.android.protocol.control.ControlEvent
import com.voidlink.android.protocol.control.ControlMessageIndex
import com.voidlink.android.protocol.control.ControlMessageTable
import com.voidlink.android.protocol.control.ControlStream
import com.voidlink.android.protocol.control.ControlStreamStats
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.input.HostFeedbackParser
import com.voidlink.android.protocol.input.InputConnection
import com.voidlink.android.protocol.input.InputPipeline
import com.voidlink.android.protocol.input.InputProfile
import com.voidlink.android.protocol.rtp.FrameAssemblerConfig
import com.voidlink.android.protocol.rtp.VideoBitstream
import com.voidlink.android.protocol.rtp.VideoFrame
import com.voidlink.android.protocol.rtsp.NegotiatedSession
import com.voidlink.android.protocol.rtsp.RtspHostProfile
import com.voidlink.android.protocol.rtsp.RtspSessionRequest
import com.voidlink.android.protocol.rtsp.RtspSessionResult
import com.voidlink.android.protocol.rtsp.UnverifiedRtspConstants
import com.voidlink.android.protocol.rtsp.VideoCodec
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the session layer is asked for.
 *
 * The video format fields are already *decided*, not preferences: the stream screen probes the
 * device's decoders and picks a codec before it asks for a session, because asking a host to encode
 * something this device cannot decode produces a stream that negotiates perfectly and then shows
 * nothing.
 *
 * @property hostId [com.voidlink.android.data.KnownHost.uuid] of the host.
 * @property appId the host-assigned app id, as text; parsed to a `Long` because some GFE ids exceed
 *   `Int.MAX_VALUE`.
 * @property appName display name, for logs.
 * @property settings the merged global + per-host settings.
 * @property width negotiated width, already resolved and clamped by decoder selection.
 * @property height negotiated height.
 * @property fps negotiated frame rate.
 * @property codec the codec a decoder was actually selected for.
 * @property hdr whether HDR survived decoder selection.
 * @property displayRefreshRateHz the panel's refresh rate, for `clientRefreshRateX100`.
 */
class StreamSessionRequest(
    val hostId: String?,
    val appId: String?,
    val appName: String?,
    val settings: StreamSettings,
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: VideoCodec,
    val hdr: Boolean,
    val displayRefreshRateHz: Int = fps,
)

/** The outcome of [StreamSession.start]. */
sealed interface StreamSessionResult {

    /** The stream is live and frames are on their way. */
    class Started(val session: ActiveStreamSession) : StreamSessionResult

    /** It did not start, and [failure] says precisely where and why. */
    class Failed(val failure: SessionFailure) : StreamSessionResult
}

/** Everything a caller can see of a running session. */
class ActiveStreamSession internal constructor(
    /** Complete decode units, in order, starting with the keyframe the watchdog waited for. */
    val frames: ReceiveChannel<VideoFrame>,
    /** What RTSP settled on, so the caller can re-select a decoder if the host clamped us. */
    val negotiated: NegotiatedSession,
    private val owner: StreamSession,
) {
    /** Control-stream counters for the stats overlay. */
    fun controlStats(): ControlStreamStats? = owner.controlStats()

    /**
     * Audio counters for the stats overlay, or `null` when this session has no audio.
     *
     * A `null` is an ordinary outcome, not an error: audio is the one part of a session that is
     * allowed to be missing (see [StreamSession.openAudio]).
     */
    fun audioStats(): AudioSessionStats? = owner.audioStats()

    /** What audio is playing, or `null` when there is none. */
    val audioFormat: AudioStreamFormat?
        get() = owner.audioFormat

    /** Why the stream ended, when it ended on its own rather than being stopped. */
    val endReason: SessionFailure?
        get() = owner.endReason

    /**
     * Tears the session down in spec §9.7's order. Idempotent, and safe to call from a cancelled
     * coroutine — the teardown itself runs [NonCancellable].
     */
    suspend fun stop() = owner.stop()
}

/**
 * The session state machine: `/launch` → RTSP → control + video → frames → teardown
 * (`docs/02-ARCHITECTURE.md` §4).
 *
 * This is the class that joins the layers. Every piece below it already existed and was tested in
 * isolation — ENet on a real loopback, RTP reassembly against fixtures, the RTSP handshake against
 * a scripted transport, `/launch` against a fake HTTP server — and none of them called each other.
 * The order here is the whole product:
 *
 * 1. **Resolve** the host and read `/serverinfo`, which supplies the generation everything branches
 *    on (spec §0.3) and `currentgame`, which decides launch-versus-resume.
 * 2. **`/launch`** (or `/resume` when this app is already the one running — spec §3.7 says a resume
 *    is a brand new streaming session even though the game keeps running, which is why the
 *    remote-input key is regenerated either way).
 * 3. **RTSP**, which turns a launched session into ports, a session id, ping payloads and the ENet
 *    connect datum (spec §6.3).
 * 4. **Control**, which must come up before video: it is where the IDR requests that recover the
 *    first frames go (spec §9.4). **Input attaches here**, as soon as there is a transport to carry
 *    it — a key pressed during the connect animation should arrive.
 * 5. **Video**, whose keep-alive is what makes the host send anything at all (spec §7.5), and then
 *    **audio**, which is allowed to fail and never fails the session (spec §8).
 * 6. **The first-frame watchdog**, which converts a stall into one of two specific sentences
 *    instead of a spinner that never stops (spec §11.1).
 *
 * **Every failure is classified** ([SessionFailure]). A launch refused by a busy host, a UDP port a
 * firewall is eating, and reassembly that is broken on our side are three completely different
 * problems with three completely different fixes, and this project has been burned by reporting all
 * of them as "could not connect".
 *
 * **Lifetimes.** One [CoroutineScope] is created per session and cancelled by [stop]; it is *not* a
 * child of the caller's job, because teardown has to outlive a cancelled caller — the stream screen
 * calls `onClose` from a `finally` block that may already be cancelled. Both UDP sockets and both
 * receive threads are released on every path: success, classified failure, thrown exception and
 * cancellation. [releaseResources] is the single place that does it.
 *
 * @param launcher NVHTTP: resolve, then `/launch` or `/resume`.
 * @param negotiator the RTSP handshake.
 * @param controlChannels opens the ENet control connection.
 * @param videoChannels opens the video socket.
 * @param random source of the per-session remote-input key (spec §5).
 * @param clock monotonic nanosecond source, injectable so the first-frame watchdog can be tested
 *   against virtual time rather than by waiting ten real seconds twice.
 */
class StreamSession(
    private val launcher: SessionLauncher,
    private val negotiator: SessionNegotiator = RtspSessionNegotiatorAdapter(),
    private val controlChannels: ControlChannelFactory = EnetControlChannelFactory(),
    private val videoChannels: VideoChannelFactory = UdpVideoChannelFactory(),
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = { System.nanoTime() },
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName(SessionConstants.TAG),
    )

    private val stopped = AtomicBoolean(false)

    private var control: ControlStream? = null
    private var controlChannel: ControlChannel? = null
    private var controlJob: Job? = null
    private var video: VideoChannel? = null
    private var audio: AudioSourceResult.Ready? = null
    private var inputAttached: Boolean = false
    private var output: Channel<VideoFrame>? = null

    /** Why the stream ended on its own, when it did. Read by the caller after the channel closes. */
    @Volatile
    var endReason: SessionFailure? = null
        private set

    /** Control-stream counters, or `null` before the control stream is up. */
    fun controlStats(): ControlStreamStats? = control?.stats()

    /** Audio counters, or `null` when this session has no audio. */
    fun audioStats(): AudioSessionStats? = audio?.stats?.invoke()

    /** What audio is playing, or `null` when there is none. */
    val audioFormat: AudioStreamFormat?
        get() = audio?.format

    /**
     * Runs the whole start-up sequence.
     *
     * Returns only once video is genuinely flowing — the returned channel's first element is the
     * keyframe this call waited for — or once a stage has failed in a way worth naming.
     *
     * Cancellation propagates as [kotlinx.coroutines.CancellationException] after releasing
     * everything opened so far, rather than being reported as a failure the user should read.
     */
    suspend fun start(request: StreamSessionRequest): StreamSessionResult {
        return try {
            runStart(request)
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            ProtocolLog.i(SessionConstants.TAG, "session start cancelled; releasing everything")
            releaseResources()
            scope.cancel()
            throw cancellation
        } catch (failure: Throwable) {
            ProtocolLog.e(SessionConstants.TAG, "session start threw", failure)
            releaseResources()
            scope.cancel()
            StreamSessionResult.Failed(
                SessionFailure.Unexpected(SessionStage.STREAMING, failure),
            )
        }
    }

    private suspend fun runStart(request: StreamSessionRequest): StreamSessionResult {
        // ---- (1) Resolve the host (spec §3.3) --------------------------------------------------
        val appId = request.appId?.trim()?.toLongOrNull()
            ?: return failed(SessionFailure.UnknownApp(request.appId))
        val resolution = launcher.resolve(request.hostId)
        if (resolution is SessionHostResult.Failed) return failed(resolution.failure)
        val host = (resolution as SessionHostResult.Resolved).host
        val serverInfo = host.serverInfo
        val profile = RtspHostProfile.fromServerInfo(serverInfo)

        // ---- (2) /launch or /resume (spec §3.6, §3.7) ------------------------------------------
        val resume = serverInfo.currentGameId?.toLongOrNull() == appId
        val parameters = SessionParameterMapper.build(
            settings = request.settings,
            appId = appId,
            width = request.width,
            height = request.height,
            fps = request.fps,
            codec = request.codec,
            hdr = request.hdr,
            displayRefreshRateHz = request.displayRefreshRateHz,
            remoteInputKey = RemoteInputKey.generate(random),
        )
        ProtocolLog.i(
            SessionConstants.TAG,
            "${if (resume) "resuming" else "launching"} ${request.appName ?: appId} on " +
                "${host.name} (${serverInfo.appVersion}, ${serverInfo.serverKind}) with " +
                "${parameters.configuration}",
        )
        val launchResult = launcher.launch(host, parameters.launch, resume)
        if (launchResult !is NvHttpResult.Success) {
            return failed(SessionFailure.fromLaunchResult(launchResult, resume))
        }
        val launch = launchResult.value
        if (!launch.started) {
            return failed(
                SessionFailure.LaunchRefused(
                    resumed = resume,
                    reason = "the host answered without starting a session " +
                        "(sessionUrl0=${launch.sessionUrl ?: "<absent>"})",
                ),
            )
        }

        // ---- (3) RTSP (spec §6.3) ---------------------------------------------------------------
        val negotiation = negotiator.negotiate(
            RtspSessionRequest(
                host = host.address.host,
                launch = launch,
                profile = profile,
                configuration = parameters.configuration,
            ),
        )
        if (negotiation is RtspSessionResult.Failure) {
            return failed(SessionFailure.NegotiationFailed(negotiation.error))
        }
        val session = (negotiation as RtspSessionResult.Success).session

        // ---- (4) Control stream (spec §9.1, §9.4) -----------------------------------------------
        val channel = controlChannels.connect(
            scope = scope,
            host = session.host,
            port = session.controlPort,
            connectData = session.controlConnectData,
            timeoutMs = SessionConstants.CONTROL_CONNECT_TIMEOUT_MS,
        ) ?: return failed(
            SessionFailure.ControlConnectFailed(
                session.controlPort,
                SessionConstants.CONTROL_CONNECT_TIMEOUT_MS,
            ),
        )
        controlChannel = channel
        val stream = ControlStream(
            link = channel.link,
            table = ControlMessageTable.forHost(profile.generation, encrypted = false),
            generation = profile.generation,
            isSunshine = profile.isSunshineish,
            usePeriodicPing = serverInfo.appVersion.atLeast(
                ControlConstants.PERIODIC_PING_MIN_MAJOR,
                ControlConstants.PERIODIC_PING_MIN_MINOR,
                ControlConstants.PERIODIC_PING_MIN_PATCH,
            ),
        )
        control = stream
        controlJob = stream.start(scope)
        attachInput(stream, profile, parameters)

        // ---- (5) Video and audio sockets, and their keep-alives (spec §7.5, §8.1) ---------------
        val receiver = try {
            videoChannels.open(
                VideoChannelSpec(
                    host = session.host,
                    port = session.videoPort,
                    pingPayload = session.videoPingPayload,
                    assembler = assemblerConfigFor(session),
                ),
            )
        } catch (failure: Exception) {
            return failed(
                SessionFailure.VideoSocketFailed(
                    failure.message ?: failure.javaClass.simpleName,
                ),
            )
        }
        video = receiver
        scope.launch { pumpVideoEvents(receiver, stream) }
        scope.launch { pumpControlEvents(stream) }
        openAudio(session, parameters)

        // ---- (6) First-frame watchdog (spec §11.1, architecture §4.2) ---------------------------
        val first = awaitFirstFrame(receiver, session.videoPort)
        if (first is FirstFrame.Failed) return failed(first.failure)
        val frame = (first as FirstFrame.Received).frame

        val frames = Channel<VideoFrame>(FRAME_QUEUE_CAPACITY)
        output = frames
        scope.launch { forwardFrames(receiver, frames, stream, frame) }

        ProtocolLog.i(
            SessionConstants.TAG,
            "streaming: ${session.width}x${session.height}@${session.fps} ${session.codec}, " +
                "video port ${session.videoPort}, control port ${session.controlPort}",
        )
        return StreamSessionResult.Started(ActiveStreamSession(frames, session, this))
    }

    /**
     * Tears everything down in the order spec §9.7 mandates, and only once.
     *
     * 1. Stop sending input — [InputPipeline.detach], which releases every held key, button and
     *    touch *through the still-live control stream*. Skipping it leaves the host with a stuck
     *    key after every disconnect, which is why it is step one and not an afterthought.
     * 2. Termination message, then ENet DISCONNECT with a 2 s linger.
     * 3. Close the ENet socket and stop its service loop.
     * 4. Close the audio stream, then the video socket and its receive and ping threads.
     * 5. The video decoder is the caller's to release.
     *
     * Runs [NonCancellable] because it is called from `finally` blocks that may already be
     * cancelled, and a teardown that gives up halfway leaves the host holding a live session.
     */
    suspend fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            detachInput()
            val stream = control
            if (stream != null) {
                val acknowledged = stream.terminate()
                if (!acknowledged) {
                    ProtocolLog.w(
                        SessionConstants.TAG,
                        "the host did not acknowledge our disconnect; it will time the session " +
                            "out on its own in 10–30 s (spec §9.7)",
                    )
                }
                stream.close()
            }
            releaseResources()
            scope.cancel()
        }
    }

    /**
     * Releases every socket and thread, in teardown order, without talking to the host.
     *
     * Separate from [stop] because the failure and cancellation paths must release exactly the same
     * things without first trying to disconnect a peer that may never have connected.
     */
    private suspend fun releaseResources() {
        // Input first, for the reason spec §9.7 puts it first: its release packets travel over the
        // control stream, which is about to go.
        detachInput()
        // The ping loop and the inbound pump go next: they write to the link, and cancelling them
        // after the socket is gone means one last send into a closed transport on every teardown.
        controlJob?.cancel()
        controlJob = null
        runCatching { controlChannel?.close?.invoke() }
            .onFailure { ProtocolLog.w(SessionConstants.TAG, "ENet close failed: ${it.message}") }
        controlChannel = null
        closeAudio()
        runCatching { video?.close() }
            .onFailure { ProtocolLog.w(SessionConstants.TAG, "video close failed: ${it.message}") }
        video = null
        output?.close()
        output = null
    }

    /**
     * Classifies, logs, releases and reports — the one exit used by every failing branch.
     *
     * Cancelling the scope is part of the release, not an afterthought: a failure after the control
     * stream came up leaves a ping loop and an ENet service loop running in it, and the caller of a
     * failed `start()` never receives a handle it could call [stop] on. Without this the session
     * keeps pinging a host it has given up on for the life of the process.
     */
    private suspend fun failed(failure: SessionFailure): StreamSessionResult.Failed {
        ProtocolLog.w(SessionConstants.TAG, "session failed: ${failure.describe()}")
        releaseResources()
        control?.close()
        control = null
        scope.cancel()
        return StreamSessionResult.Failed(failure)
    }

    /**
     * Hands one controller-feedback message to the input layer (spec §9.6, §10.3).
     *
     * Parsed by `protocol/input`'s `HostFeedbackParser` rather than here, because the three payloads
     * disagree with each other in ways that are easy to normalise into a bug: rumble carries four
     * leading bytes and rumble-triggers does not, and the motion-state message's fields are not in
     * the order spec §10.3 lists them in. One parser, one place to fix, one set of tests.
     */
    private fun publishFeedback(event: ControlEvent.HostFeedback) {
        val feedback = when (event.message) {
            ControlMessageIndex.RUMBLE -> HostFeedbackParser.rumble(event.payload)
            ControlMessageIndex.RUMBLE_TRIGGERS -> HostFeedbackParser.rumbleTriggers(event.payload)
            ControlMessageIndex.SET_MOTION_EVENT ->
                HostFeedbackParser.setMotionEventState(event.payload)
            else -> null
        }
        if (feedback == null) {
            ProtocolLog.w(
                SessionConstants.TAG,
                "discarding an unreadable ${event.message.label} payload of " +
                    "${event.payload.size} bytes",
            )
            return
        }
        InputPipeline.publish(feedback)
    }

    // ---- Audio (spec §8) -------------------------------------------------------------------------

    /**
     * Starts audio, and shrugs if it cannot (spec §8.1, §8.5).
     *
     * **Audio can never fail a session.** A stream with no sound is far better than no stream, so
     * every outcome here is either a running audio stream or a log line — the failure
     * classification of [SessionFailure] is deliberately not reachable from this method. The factory
     * is documented never to throw; it is called inside a `try` anyway, because "documented never
     * to throw" is not a property this state machine should stake a session on.
     *
     * Opened before the first-frame watchdog rather than after, so the audio keep-alive of spec §7.5
     * starts punching its own pinhole during the ten seconds we may spend waiting for video.
     *
     * Everything RTSP negotiated is passed straight through. In particular
     * [com.voidlink.android.protocol.rtsp.OpusMultistreamConfig.mapping] is handed over **as it
     * came**: spec §8.3's channel-order fix-up is applied once, while parsing the DESCRIBE body, and
     * applying it a second time here would move LFE again and put centre-channel dialogue in a
     * surround speaker — a fault that is inaudible in a log and obvious in a game.
     */
    private suspend fun openAudio(session: NegotiatedSession, parameters: SessionParameters) {
        val opus = session.opusConfig
        val result = try {
            AudioPipeline.audioSourceFactory.open(
                AudioSourceRequest(
                    host = session.host,
                    port = session.audioPort,
                    pingPayload = session.audioPingPayload,
                    channelCount = opus.channelCount,
                    streams = opus.streams,
                    coupledStreams = opus.coupledStreams,
                    mapping = opus.mapping,
                    sampleRateHz = opus.sampleRateHz,
                    packetDurationMs = parameters.configuration.audioPacketDurationMs,
                    audioEncryptionNegotiated =
                        session.encryptionFlags and UnverifiedRtspConstants.SS_ENC_AUDIO != 0,
                ),
            )
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            ProtocolLog.w(SessionConstants.TAG, "opening audio threw; continuing without it", failure)
            return
        }
        when (result) {
            is AudioSourceResult.Ready -> {
                audio = result
                ProtocolLog.i(
                    SessionConstants.TAG,
                    "audio playing on port ${session.audioPort}: ${result.format.describe()}",
                )
            }

            is AudioSourceResult.Unavailable -> ProtocolLog.w(
                SessionConstants.TAG,
                "no audio (${result.summary})" + (result.detail?.let { " — $it" } ?: ""),
            )
        }
    }

    /**
     * Stops audio, on whatever path teardown took.
     *
     * Runs [NonCancellable] because the codec and the socket must be released even when the caller
     * is already cancelled, and because a second failure here must not mask the first.
     */
    private suspend fun closeAudio() {
        val playing = audio ?: return
        audio = null
        withContext(NonCancellable) {
            runCatching { playing.onClose.invoke() }
                .onFailure { ProtocolLog.w(SessionConstants.TAG, "audio close failed: ${it.message}") }
        }
    }

    // ---- Input (spec §10) ------------------------------------------------------------------------

    /**
     * Publishes the live input connection (spec §10.1, §10.4).
     *
     * Attached as soon as the control stream is up and before video is expected: a user pressing a
     * key during the connect animation should have it arrive, and the host is already listening.
     *
     * The division of labour is the one `protocol/input`'s `InputPacketTransport` describes.
     * Encryption, IV chaining and batching are the input layer's — it owns the riKey semantics and
     * the runtime switches over the parts of spec §10 it found to be wrong. Framing, channel and
     * delivery are ours: the payload arrives complete and goes out as an `INPUT_DATA` message on the
     * urgent channel, reliably, unread and unmodified.
     */
    private fun attachInput(
        stream: ControlStream,
        profile: RtspHostProfile,
        parameters: SessionParameters,
    ) {
        if (stream.supportsInput()) {
            InputPipeline.attach(
                InputConnection(
                    remoteInputKey = parameters.remoteInputKey.key,
                    remoteInputKeyId = parameters.remoteInputKey.keyId,
                    profile = InputProfile(
                        generation = profile.generation,
                        isSunshine = profile.isSunshineish,
                        // v1 announces encryptionEnabled=0 (spec §6.5), so the control stream this
                        // session built is plaintext; the input layer needs to know because the
                        // message type it is framed with differs per column of spec §9.3's table.
                        controlStreamEncrypted = false,
                    ),
                    transport = { payload -> stream.sendInputPayload(payload) },
                ),
            )
            inputAttached = true
        } else {
            ProtocolLog.w(
                SessionConstants.TAG,
                "this host has no control-stream input message (generation " +
                    "${profile.generation}, spec §9.3); the session will stream but not accept input",
            )
        }
    }

    /** Releases held keys, buttons and touches, then disconnects the sink (spec §9.7 step 1). */
    private fun detachInput() {
        if (!inputAttached) return
        inputAttached = false
        runCatching { InputPipeline.detach() }
            .onFailure { ProtocolLog.w(SessionConstants.TAG, "detaching input failed: ${it.message}") }
    }

    // ---- Watchdog -------------------------------------------------------------------------------

    private sealed interface FirstFrame {
        class Received(val frame: VideoFrame) : FirstFrame
        class Failed(val failure: SessionFailure) : FirstFrame
    }

    /**
     * Waits for the first decodable frame, with the two separate deadlines of architecture §4.2.
     *
     * The first covers "nothing arrived at all" (`ML_ERROR_NO_VIDEO_TRAFFIC`, spec §11.1 — a
     * firewall, almost always); the second covers "packets arrived but nothing assembled"
     * (`ML_ERROR_NO_VIDEO_FRAME` — a bug on our side). Telling them apart requires only the packet
     * counter, and telling the user them apart is the difference between a fix and a reinstall.
     *
     * Polled rather than awaited on purpose; see [SessionConstants.FRAME_POLL_INTERVAL_MS].
     */
    private suspend fun awaitFirstFrame(receiver: VideoChannel, videoPort: Int): FirstFrame {
        val started = clock()
        var sawTraffic = false
        var trafficAtMs = 0L
        while (currentScopeActive()) {
            val received = receiver.frames.tryReceive()
            received.getOrNull()?.let { return FirstFrame.Received(it) }
            if (received.isClosed) {
                return FirstFrame.Failed(
                    endReason ?: SessionFailure.NoVideoTraffic(videoPort, elapsedMs(started)),
                )
            }

            val stats = receiver.stats()
            val elapsed = elapsedMs(started)
            // Rejected packets count as traffic: a datagram we could not parse still proves the
            // host is sending and the pinhole is open, and calling that "no video traffic" would
            // point the user at their firewall for a bug in our parser (spec §11.1).
            if (!sawTraffic && stats.packetsReceived + stats.packetsRejected > 0L) {
                sawTraffic = true
                trafficAtMs = elapsed
                ProtocolLog.i(
                    SessionConstants.TAG,
                    "first video packet after ${elapsed} ms; waiting for a complete frame",
                )
            }
            if (!sawTraffic && elapsed >= SessionConstants.FIRST_TRAFFIC_TIMEOUT_MS) {
                return FirstFrame.Failed(SessionFailure.NoVideoTraffic(videoPort, elapsed))
            }
            if (sawTraffic && elapsed - trafficAtMs >= SessionConstants.FIRST_FRAME_TIMEOUT_MS) {
                return FirstFrame.Failed(
                    SessionFailure.NoVideoFrame(
                        packetsReceived = stats.packetsReceived,
                        packetsRejected = stats.packetsRejected,
                        framesDropped = stats.framesDropped,
                        waitedMs = elapsed - trafficAtMs,
                    ),
                )
            }
            delay(SessionConstants.FRAME_POLL_INTERVAL_MS)
        }
        return FirstFrame.Failed(SessionFailure.NoVideoTraffic(videoPort, elapsedMs(started)))
    }

    // ---- Pumps ----------------------------------------------------------------------------------

    /**
     * Forwards frames to the caller, newest-biased under pressure.
     *
     * Drop-oldest, capacity two, exactly as architecture §3 rule 1 requires — and every eviction
     * asks the host for a keyframe, because the decoder has just lost a reference frame. Growing
     * this queue "for smoothness" converts loss into latency, which is the one thing this product
     * cannot trade away.
     */
    private suspend fun forwardFrames(
        receiver: VideoChannel,
        out: Channel<VideoFrame>,
        stream: ControlStream,
        firstFrame: VideoFrame,
    ) {
        var lastGood = firstFrame.frameIndex
        out.trySend(firstFrame)
        stream.onFrameProgress(firstFrame.frameIndex, lastGood)
        for (frame in receiver.frames) {
            lastGood = frame.frameIndex
            stream.onFrameProgress(frame.frameIndex, lastGood)
            if (out.trySend(frame).isSuccess) continue
            // The decoder is behind. Drop the oldest, keep the newest, ask for a keyframe.
            out.tryReceive()
            if (out.trySend(frame).isFailure) break
            stream.requestIdrFrame()
        }
        out.close()
    }

    /**
     * Turns reassembly loss into IDR requests (spec §9.5).
     *
     * The video layer reports honestly and never throttles itself; the rate limiting lives in
     * [ControlStream.requestIdrFrame], which is what keeps a burst of twenty lost frames from
     * becoming twenty keyframe requests — each one a large intra frame that makes the congestion
     * that caused the loss worse.
     */
    private suspend fun pumpVideoEvents(receiver: VideoChannel, stream: ControlStream) {
        for (event in receiver.events) {
            if (event.requestsIdr) stream.requestIdrFrame()
        }
    }

    /**
     * Acts on what the host tells us (spec §9.6).
     *
     * Two kinds of message reach here. Controller feedback is forwarded to the input layer, which
     * owns both the parsers and the motors; a termination ends the session. Everything else was
     * already logged and counted by [ControlStream].
     */
    private suspend fun pumpControlEvents(stream: ControlStream) {
        for (event in stream.events) {
            if (event is ControlEvent.HostFeedback) {
                publishFeedback(event)
                continue
            }
            if (event !is ControlEvent.Terminated) continue
            val failure = SessionFailure.HostTerminated(
                errorCode = event.errorCode,
                duringStartup = output == null,
                description = event.describe(),
            )
            endReason = failure
            ProtocolLog.i(SessionConstants.TAG, "ending the session: ${failure.describe()}")
            // Closing the frame channel is what the stream screen is watching; it renders the
            // reason, and the caller's onClose runs the teardown of spec §9.7.
            output?.close()
            video?.close()
            return
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /**
     * How the reassembler is configured from what RTSP settled on (spec §7.7, §7.8).
     *
     * The codec is needed only to recognise a keyframe, and the encryption flag exists so that a
     * host which enabled `SS_ENC_VIDEO` fails loudly rather than producing garbage — v1 negotiates
     * `encryptionEnabled=0` (spec §6.5), so this should always be false, and the day it is not, the
     * parser says so.
     */
    private fun assemblerConfigFor(session: NegotiatedSession): FrameAssemblerConfig =
        FrameAssemblerConfig(
            bitstream = when (session.codec) {
                VideoCodec.H264 -> VideoBitstream.H264
                VideoCodec.HEVC -> VideoBitstream.HEVC
                VideoCodec.AV1 -> VideoBitstream.AV1
            },
            videoEncryptionNegotiated = session.videoEncryptionNegotiated(),
        )

    private fun NegotiatedSession.videoEncryptionNegotiated(): Boolean =
        encryptionFlags and UnverifiedRtspConstants.SS_ENC_VIDEO != 0

    private fun currentScopeActive(): Boolean = scope.isActive

    private fun elapsedMs(startNanos: Long): Long = (clock() - startNanos) / 1_000_000L

    private companion object {
        /** Architecture §3 rule 1: two decode units, drop oldest. Never grow this. */
        const val FRAME_QUEUE_CAPACITY: Int = 2
    }
}
