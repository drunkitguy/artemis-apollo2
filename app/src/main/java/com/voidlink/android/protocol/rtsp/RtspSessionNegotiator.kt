package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.http.AudioChannelLayout
import com.voidlink.android.protocol.http.LaunchResponse

/**
 * Everything [RtspSessionNegotiator.negotiate] needs — and the seam between `/launch` and RTSP.
 *
 * **This is where the two halves meet.** `NvHttpClient.launch` / `NvHttpClient.resume` and
 * `LaunchRequest` are implemented and tested but have no callers yet; nothing in this package
 * calls them, and nothing in this package needs to. The negotiator *accepts* an already-obtained
 * [LaunchResponse], so wiring the two together is a few lines in whatever owns the session, with
 * no change to either side:
 *
 * ```kotlin
 * val launch = nvHttp.launch(hostKey, address, launchRequest, serverInfo.isNvidiaGfe, serverInfo.httpsPort)
 * when (launch) {
 *     is NvHttpResult.Success -> negotiator.negotiate(
 *         RtspSessionRequest(
 *             host = address.host,
 *             launch = launch.value,
 *             profile = RtspHostProfile.fromServerInfo(serverInfo),
 *             configuration = streamConfiguration,
 *         ),
 *     )
 *     else -> // surface launch.errorDescription() — RTSP is not reached at all
 * }
 * ```
 *
 * Two things must line up across that seam, and neither is checked anywhere else:
 *
 * 1. **[configuration] must agree with the `LaunchRequest`** — resolution, frame rate and channel
 *    layout in particular. The host builds its DESCRIBE answer from what `/launch` asked for, so a
 *    mismatch produces a session that negotiates cleanly and then behaves oddly.
 * 2. **`rikey` / `rikeyid` are not used here.** They travel in `/launch` and are consumed by the
 *    input encryption of spec §10.1; the RTSP handshake never sees them. A resume must regenerate
 *    them (spec §3.7), which is again a `/launch`-side concern.
 *
 * @property host the host address, unbracketed even for an IPv6 literal — pass
 *   [com.voidlink.android.protocol.HostAddress.host], not its `authority()`.
 * @property launch the reply from `/launch` or `/resume`. Its `sessionUrl0` supplies the RTSP port.
 * @property profile the host's generation and family (spec §0.3).
 * @property configuration what to ask the host to encode (spec §6.4).
 */
class RtspSessionRequest(
    val host: String,
    val launch: LaunchResponse,
    val profile: RtspHostProfile,
    val configuration: StreamConfiguration,
)

/**
 * Runs the RTSP handshake that turns a launched session into stream parameters (spec §6).
 *
 * The sequence, in the order spec §6.3 mandates and for the reasons it gives:
 *
 * 1. **OPTIONS** — liveness only; the `Public:` header is not parsed.
 * 2. **DESCRIBE** — the host's capability SDP, which is where the Opus surround configuration of
 *    spec §8.3 comes from. The whole body is logged at debug on the way past, because spec §6.3
 *    says outright that we do not know the full attribute set Sunshine emits.
 * 3. **SETUP audio, then video, then control** — in that order, because the audio SETUP is what
 *    establishes the `Session` id everything after it carries. Control is skipped on Gen < 5.
 * 4. **ANNOUNCE** — our configuration. A non-200 here is the host rejecting the requested
 *    resolution, codec or bitrate, and it is reported as exactly that.
 * 5. **PLAY video, then PLAY audio** — after which media flows on the negotiated UDP ports.
 *
 * Header order within a request is uniform (`CSeq`, `X-GS-ClientVersion`, then whatever the method
 * needs) rather than copying the spec's per-method examples verbatim, which differ only in where
 * `Session` sits. RTSP headers are order-independent, and one order makes the requests directly
 * comparable byte for byte in tests.
 *
 * Every step has its own named timeout in [RtspConstants], and the whole run additionally has
 * [RtspConstants.SESSION_BUDGET_MS] checked between steps — eight steps that are each individually
 * reasonable still add up to minutes in front of a screen that says nothing.
 *
 * @param transportFactory how to open the link; defaults to a real TCP socket.
 * @param clock monotonic nanosecond source, injectable so budget behaviour is testable without
 *   waiting for real time to pass.
 */
class RtspSessionNegotiator(
    private val transportFactory: RtspTransportFactory = SocketRtspTransport.FACTORY,
    private val clock: () -> Long = { System.nanoTime() },
) {

    /**
     * Performs the whole handshake.
     *
     * @return [RtspSessionResult.Success] with everything the media layers need, or
     *   [RtspSessionResult.Failure] with a precisely classified [RtspError]. Cancellation
     *   propagates as [kotlinx.coroutines.CancellationException] rather than becoming a failure.
     */
    suspend fun negotiate(request: RtspSessionRequest): RtspSessionResult {
        val launch = request.launch
        if (!launch.started) {
            return RtspSessionResult.Failure(
                RtspError.NotLaunched(
                    "the reply reported no started session (sessionUrl0=" +
                        "${launch.sessionUrl ?: "<absent>"})",
                ),
            )
        }

        val host = normalizeHost(request.host)
        if (host.isEmpty()) {
            return RtspSessionResult.Failure(RtspError.NotLaunched("no host address to connect to"))
        }

        val port = launch.rtspPort ?: ProtocolConstants.DEFAULT_RTSP_PORT
        if (launch.rtspOverEnet) {
            ProtocolLog.unverified(
                RtspConstants.TAG,
                "rtspru-tcp-listener",
                "sessionUrl0 advertised rtspru:// (RTSP over ENet); connecting over TCP to " +
                    "$host:$port anyway per spec 01 §6.1, item 14. If this connect fails, " +
                    "RTSP-over-ENet is the missing piece.",
            )
        }

        val transport = transportFactory.create(host, port)
        val connection = RtspConnection(transport)
        val deadline = SessionDeadline(clock, RtspConstants.SESSION_BUDGET_MS)
        return try {
            runHandshake(request, host, port, connection, deadline)
        } finally {
            connection.close()
        }
    }

    private suspend fun runHandshake(
        request: RtspSessionRequest,
        host: String,
        port: Int,
        connection: RtspConnection,
        deadline: SessionDeadline,
    ): RtspSessionResult {
        val profile = request.profile
        val configuration = request.configuration
        val base = "rtsp://" + authorityOf(host, port)

        ProtocolLog.i(
            RtspConstants.TAG,
            "negotiating RTSP with $base — $profile, $configuration",
        )

        deadline.expiredAt(RtspStep.CONNECT)?.let { return RtspSessionResult.Failure(it) }
        connection.connect(RtspConstants.CONNECT_TIMEOUT_MS)?.let {
            return RtspSessionResult.Failure(it)
        }

        // ---- (1) OPTIONS — liveness probe (spec §6.3) ----------------------------------------
        deadline.expiredAt(RtspStep.OPTIONS)?.let { return RtspSessionResult.Failure(it) }
        val options = connection.exchange(
            step = RtspStep.OPTIONS,
            method = RtspConstants.METHOD_OPTIONS,
            target = base,
            timeoutMs = RtspConstants.OPTIONS_TIMEOUT_MS,
        )
        if (options is RtspExchange.Failed) return RtspSessionResult.Failure(options.error)

        // ---- (2) DESCRIBE — the host's capabilities (spec §6.3) -------------------------------
        deadline.expiredAt(RtspStep.DESCRIBE)?.let { return RtspSessionResult.Failure(it) }
        val describe = connection.exchange(
            step = RtspStep.DESCRIBE,
            method = RtspConstants.METHOD_DESCRIBE,
            target = base,
            headers = listOf(
                RtspConstants.HEADER_ACCEPT to RtspConstants.MIME_SDP,
                RtspConstants.HEADER_IF_MODIFIED_SINCE to RtspConstants.IF_MODIFIED_SINCE_VALUE,
            ),
            timeoutMs = RtspConstants.DESCRIBE_TIMEOUT_MS,
        )
        if (describe is RtspExchange.Failed) return RtspSessionResult.Failure(describe.error)
        val describeBody = (describe as RtspExchange.Answered).response.body
        val hostDescription = SessionDescription.parse(describeBody)
        // Spec §6.3 cannot enumerate what Sunshine emits here, and says to log the body so we can
        // learn from real hosts. Unknown attributes are kept, never rejected.
        ProtocolLog.d(RtspConstants.TAG, "DESCRIBE SDP:\n$describeBody")
        hostDescription.spropParameterSets?.let {
            ProtocolLog.d(
                RtspConstants.TAG,
                "host offered sprop-parameter-sets=$it; ignoring it, SPS/PPS arrive in-band (§6.3)",
            )
        }

        // ---- (3) SETUP audio, video, control — in that order (spec §6.3) ----------------------
        deadline.expiredAt(RtspStep.SETUP_AUDIO)?.let { return RtspSessionResult.Failure(it) }
        val audioSetup = connection.exchange(
            step = RtspStep.SETUP_AUDIO,
            method = RtspConstants.METHOD_SETUP,
            target = "$base/${profile.audioStreamId}",
            headers = setupHeaders(sessionId = null),
            timeoutMs = RtspConstants.SETUP_TIMEOUT_MS,
        )
        if (audioSetup is RtspExchange.Failed) return RtspSessionResult.Failure(audioSetup.error)
        val audioResponse = (audioSetup as RtspExchange.Answered).response

        val sessionId = RtspHeaderParser.sessionId(audioResponse.header(RtspConstants.HEADER_SESSION))
            ?: return RtspSessionResult.Failure(
                RtspError.Malformed(
                    RtspStep.SETUP_AUDIO,
                    "the audio SETUP carried no usable Session header, so nothing after it can be " +
                        "addressed to a session",
                ),
            )
        val audioPort = portOrDefault(
            RtspStep.SETUP_AUDIO,
            audioResponse.header(RtspConstants.HEADER_TRANSPORT),
            RtspConstants.DEFAULT_AUDIO_PORT,
        )
        val audioPingPayload =
            RtspHeaderParser.pingPayload(audioResponse.header(RtspConstants.HEADER_SS_PING_PAYLOAD))

        deadline.expiredAt(RtspStep.SETUP_VIDEO)?.let { return RtspSessionResult.Failure(it) }
        val videoSetup = connection.exchange(
            step = RtspStep.SETUP_VIDEO,
            method = RtspConstants.METHOD_SETUP,
            target = "$base/${profile.videoStreamId}",
            headers = setupHeaders(sessionId),
            timeoutMs = RtspConstants.SETUP_TIMEOUT_MS,
        )
        if (videoSetup is RtspExchange.Failed) return RtspSessionResult.Failure(videoSetup.error)
        val videoResponse = (videoSetup as RtspExchange.Answered).response
        val videoPort = portOrDefault(
            RtspStep.SETUP_VIDEO,
            videoResponse.header(RtspConstants.HEADER_TRANSPORT),
            RtspConstants.DEFAULT_VIDEO_PORT,
        )
        val videoPingPayload =
            RtspHeaderParser.pingPayload(videoResponse.header(RtspConstants.HEADER_SS_PING_PAYLOAD))

        var controlPort = RtspConstants.DEFAULT_CONTROL_PORT
        var controlConnectData = 0
        if (profile.performsControlSetup) {
            deadline.expiredAt(RtspStep.SETUP_CONTROL)?.let { return RtspSessionResult.Failure(it) }
            val controlSetup = connection.exchange(
                step = RtspStep.SETUP_CONTROL,
                method = RtspConstants.METHOD_SETUP,
                target = "$base/${profile.controlStreamId}",
                headers = setupHeaders(sessionId),
                timeoutMs = RtspConstants.SETUP_TIMEOUT_MS,
            )
            if (controlSetup is RtspExchange.Failed) {
                return RtspSessionResult.Failure(controlSetup.error)
            }
            val controlResponse = (controlSetup as RtspExchange.Answered).response
            controlPort = portOrDefault(
                RtspStep.SETUP_CONTROL,
                controlResponse.header(RtspConstants.HEADER_TRANSPORT),
                RtspConstants.DEFAULT_CONTROL_PORT,
            )
            controlConnectData = RtspHeaderParser.connectData(
                controlResponse.header(RtspConstants.HEADER_SS_CONNECT_DATA),
            )
        } else {
            ProtocolLog.i(
                RtspConstants.TAG,
                "skipping the control SETUP on a Gen ${profile.generation} host; spec §9.1 puts " +
                    "its control stream on legacy TCP 47995 instead",
            )
        }

        // ---- Audio layout, resolved against what the host actually offered (spec §8.3) --------
        val requestedLayout = configuration.audioLayout
        val parsedOpus = OpusMultistreamConfig.parseSurround(
            hostDescription,
            requestedLayout.channelCount,
        )
        val layoutDowngraded = parsedOpus == null
        val opusConfig = parsedOpus ?: OpusMultistreamConfig.stereo()
        val effectiveLayout = if (layoutDowngraded) AudioChannelLayout.STEREO else requestedLayout
        if (layoutDowngraded) {
            // Not fatal, and not silent. The host cannot do the layout we asked for; announcing it
            // anyway would give us surround SDP and a stereo stream, which is worse than asking for
            // stereo in the first place.
            ProtocolLog.w(
                RtspConstants.TAG,
                "DESCRIBE offered no surround-params for ${requestedLayout.channelCount} " +
                    "channels; announcing stereo instead (spec §8.3)",
            )
        }
        val announcedConfiguration = configuration.copy(audioLayout = effectiveLayout)

        // ---- (4) ANNOUNCE — our configuration (spec §6.3, §6.4) -------------------------------
        deadline.expiredAt(RtspStep.ANNOUNCE)?.let { return RtspSessionResult.Failure(it) }
        val sdp = SdpGenerator.generate(announcedConfiguration, profile, host, videoPort)
        val announce = connection.exchange(
            step = RtspStep.ANNOUNCE,
            method = RtspConstants.METHOD_ANNOUNCE,
            target = "$base/${profile.announceStreamId}",
            headers = listOf(RtspConstants.HEADER_SESSION to sessionId),
            body = sdp,
            timeoutMs = RtspConstants.ANNOUNCE_TIMEOUT_MS,
        )
        if (announce is RtspExchange.Failed) {
            val error = announce.error
            if (error is RtspError.Refused) {
                ProtocolLog.w(
                    RtspConstants.TAG,
                    "the host rejected our ANNOUNCE (${error.statusCode} ${error.reasonPhrase}). " +
                        "The usual cause is an unsupported resolution/codec/bitrate combination: " +
                        "$announcedConfiguration",
                )
            }
            return RtspSessionResult.Failure(error)
        }

        // ---- (5) PLAY video, then audio (spec §6.3) -------------------------------------------
        deadline.expiredAt(RtspStep.PLAY_VIDEO)?.let { return RtspSessionResult.Failure(it) }
        val playVideo = connection.exchange(
            step = RtspStep.PLAY_VIDEO,
            method = RtspConstants.METHOD_PLAY,
            target = "$base/${RtspConstants.STREAM_ID_VIDEO_LEGACY}",
            headers = listOf(RtspConstants.HEADER_SESSION to sessionId),
            timeoutMs = RtspConstants.PLAY_TIMEOUT_MS,
        )
        if (playVideo is RtspExchange.Failed) return RtspSessionResult.Failure(playVideo.error)

        deadline.expiredAt(RtspStep.PLAY_AUDIO)?.let { return RtspSessionResult.Failure(it) }
        val playAudio = connection.exchange(
            step = RtspStep.PLAY_AUDIO,
            method = RtspConstants.METHOD_PLAY,
            target = "$base/${RtspConstants.STREAM_ID_AUDIO_LEGACY}",
            headers = listOf(RtspConstants.HEADER_SESSION to sessionId),
            timeoutMs = RtspConstants.PLAY_TIMEOUT_MS,
        )
        if (playAudio is RtspExchange.Failed) return RtspSessionResult.Failure(playAudio.error)

        val session = NegotiatedSession(
            host = host,
            rtspPort = port,
            sessionId = sessionId,
            videoPort = videoPort,
            audioPort = audioPort,
            controlPort = controlPort,
            controlSetupPerformed = profile.performsControlSetup,
            videoPingPayload = videoPingPayload,
            audioPingPayload = audioPingPayload,
            controlConnectData = controlConnectData,
            codec = announcedConfiguration.codec,
            hdr = announcedConfiguration.hdr,
            chromaSamplingType = announcedConfiguration.chromaSamplingType,
            width = announcedConfiguration.width,
            height = announcedConfiguration.height,
            fps = announcedConfiguration.fps,
            bitrateKbps = announcedConfiguration.bitrateKbps,
            packetSize = announcedConfiguration.packetSize,
            encryptionFlags = announcedConfiguration.encryptionFlags,
            audioLayout = effectiveLayout,
            audioLayoutDowngraded = layoutDowngraded,
            opusConfig = opusConfig,
            announcedSdp = sdp,
            hostDescription = hostDescription,
        )
        ProtocolLog.i(RtspConstants.TAG, "RTSP negotiation complete: $session")
        return RtspSessionResult.Success(session)
    }

    /**
     * The headers every SETUP carries (spec §6.3).
     *
     * @param sessionId `null` for the audio SETUP, which is the one that *creates* the session.
     */
    private fun setupHeaders(sessionId: String?): List<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>(3)
        headers.add(RtspConstants.HEADER_TRANSPORT to RtspConstants.TRANSPORT_REQUEST_VALUE)
        headers.add(
            RtspConstants.HEADER_IF_MODIFIED_SINCE to RtspConstants.IF_MODIFIED_SINCE_VALUE,
        )
        if (sessionId != null) headers.add(RtspConstants.HEADER_SESSION to sessionId)
        return headers
    }

    /**
     * `server_port=` from a SETUP response, or the documented per-stream default (spec §0.4, §6.3).
     *
     * The fallback is announced rather than taken quietly: a defaulted port is indistinguishable
     * from a working one right up until no media arrives, and Sunshine's configurable base port
     * makes "the default happened to be right" a coin flip.
     */
    private fun portOrDefault(step: RtspStep, transportHeader: String?, fallback: Int): Int {
        val parsed = RtspHeaderParser.serverPort(transportHeader)
        if (parsed != null) return parsed
        ProtocolLog.w(
            RtspConstants.TAG,
            "${step.label}: no usable server_port= in Transport=" +
                "\"${transportHeader ?: "<absent>"}\"; falling back to $fallback. If the host uses " +
                "a non-default base port, no media will arrive.",
        )
        return fallback
    }

    /** Accepts a bracketed IPv6 literal defensively, since callers hold both forms. */
    private fun normalizeHost(raw: String): String =
        raw.trim().removePrefix("[").removeSuffix("]").trim()

    /** `host:port`, bracketing an IPv6 literal, for the RTSP request target. */
    private fun authorityOf(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

    /**
     * The whole-handshake budget of [RtspConstants.SESSION_BUDGET_MS], checked between steps.
     *
     * Checked between steps rather than enforced with `withTimeout` on purpose: the socket reads
     * underneath are blocking and do not observe coroutine cancellation, so a coroutine timeout
     * would report success at stopping something that is still running. The per-step `SO_TIMEOUT`
     * is what actually bounds a read; this bounds the sum of them.
     */
    private class SessionDeadline(private val clock: () -> Long, private val budgetMs: Long) {
        private val startNanos: Long = clock()

        /** @return a [RtspError.Timeout] naming [step] if the budget is gone, else `null`. */
        fun expiredAt(step: RtspStep): RtspError? {
            val elapsedMs = (clock() - startNanos) / 1_000_000L
            if (elapsedMs < budgetMs) return null
            return RtspError.Timeout(step, budgetMs, budgetExhausted = true)
        }
    }
}
