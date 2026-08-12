package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.http.AudioChannelLayout

/**
 * Which step of the handshake an outcome belongs to (spec §6.1, §6.3).
 *
 * Every error names one. "RTSP failed" is not a diagnosis; "the host refused our ANNOUNCE" is, and
 * it points straight at the resolution/codec/bitrate combination the user picked.
 *
 * @property label short human-readable name, used in log lines and error text.
 */
enum class RtspStep(val label: String) {
    /** Before any RTSP at all: checking the `/launch` reply we were handed. */
    LAUNCH("launch"),

    /** TCP connect to the RTSP port. */
    CONNECT("connect"),

    OPTIONS("OPTIONS"),
    DESCRIBE("DESCRIBE"),
    SETUP_AUDIO("SETUP audio"),
    SETUP_VIDEO("SETUP video"),
    SETUP_CONTROL("SETUP control"),
    ANNOUNCE("ANNOUNCE"),
    PLAY_VIDEO("PLAY video"),
    PLAY_AUDIO("PLAY audio"),
}

/**
 * Why a session failed to negotiate.
 *
 * Five shapes rather than one, because the caller genuinely acts differently on each and because
 * collapsing them is a mistake this project has already paid for once (see
 * [com.voidlink.android.protocol.http.NvHttpResult], which split for the same reason):
 *
 * * [Refused] — the host answered, and said no. Its own status code and text are the best
 *   explanation anyone will get, and at [RtspStep.ANNOUNCE] it almost always means the requested
 *   resolution, codec or bitrate is not something this host can do. Retrying unchanged is pointless.
 * * [Timeout] — the host said nothing in time. Says nothing about whether our request was valid.
 *   Retrying is reasonable.
 * * [Malformed] — the host answered with something we cannot parse. That is a protocol
 *   disagreement, and the response is worth capturing, not retrying.
 * * [Unreachable] — we never got a usable connection: refused, reset, or closed underneath us.
 *   The host is off, busy, or listening somewhere else.
 * * [NotLaunched] — the `/launch` reply we were handed does not describe a started session, so
 *   there is nothing to negotiate with. Distinct because the fix is upstream of RTSP entirely.
 *
 * Cancellation is deliberately absent: a cancelled negotiation throws
 * [kotlinx.coroutines.CancellationException] like every other suspending function in this codebase,
 * rather than being reported as a failure the user should be told about.
 */
sealed interface RtspError {

    /** The step that produced this error. */
    val step: RtspStep

    /** A short line fit to show a user, always naming the step. */
    fun describe(): String

    /**
     * The host answered with a non-200 status.
     *
     * @property statusCode the RTSP status the host returned.
     * @property reasonPhrase the host's own text, possibly empty.
     */
    class Refused(
        override val step: RtspStep,
        val statusCode: Int,
        val reasonPhrase: String,
    ) : RtspError {
        override fun describe(): String {
            val reason = reasonPhrase.trim()
            return if (reason.isEmpty()) "the host refused ${step.label} with status $statusCode"
            else "the host refused ${step.label}: $statusCode $reason"
        }
    }

    /**
     * The step ran out of time.
     *
     * @property waitedMs the deadline that elapsed.
     * @property budgetExhausted true when it was the whole-handshake budget that ran out rather
     *   than this step's own timeout — a different story to tell, and a different constant to tune.
     */
    class Timeout(
        override val step: RtspStep,
        val waitedMs: Long,
        val budgetExhausted: Boolean = false,
    ) : RtspError {
        override fun describe(): String =
            if (budgetExhausted) "the session did not come up within ${waitedMs} ms (at ${step.label})"
            else "the host did not answer ${step.label} within ${waitedMs} ms"
    }

    /**
     * The host answered with something unparseable, or left out something required.
     *
     * @property reason what was wrong, specifically enough to act on.
     */
    class Malformed(
        override val step: RtspStep,
        val reason: String,
    ) : RtspError {
        override fun describe(): String = "unusable response to ${step.label}: $reason"
    }

    /**
     * The connection could not be established or did not survive.
     *
     * @property message what the transport reported.
     * @property cause the underlying exception, for the log only.
     */
    class Unreachable(
        override val step: RtspStep,
        val message: String,
        val cause: Throwable? = null,
    ) : RtspError {
        override fun describe(): String = "could not reach the host's RTSP port (${step.label}): $message"
    }

    /**
     * The `/launch` reply handed to us does not describe a started session.
     *
     * @property reason why it was unusable.
     */
    class NotLaunched(val reason: String) : RtspError {
        override val step: RtspStep get() = RtspStep.LAUNCH
        override fun describe(): String = "the host did not start a session: $reason"
    }
}

/**
 * Everything the video, audio, control and input layers need once RTSP is done (spec §6.3).
 *
 * This is the handshake's entire product. Nothing downstream should have to re-derive any of it, and
 * nothing downstream should have to hold on to the RTSP objects that produced it.
 *
 * @property host the host we negotiated with, unbracketed even for IPv6.
 * @property rtspPort the port the handshake ran over.
 * @property sessionId the RTSP `Session` id, already truncated at the first `;` (spec §6.3).
 * @property videoPort UDP port for the video RTP stream (spec §7).
 * @property audioPort UDP port for the audio RTP stream (spec §8).
 * @property controlPort UDP port for the ENet control stream (spec §9). Equal to
 *   [RtspConstants.DEFAULT_CONTROL_PORT] on a host too old to do a control SETUP at all.
 * @property controlSetupPerformed whether a control SETUP actually happened — false on Gen < 5,
 *   where spec §9.1 says the control stream is legacy TCP on a hardcoded port instead.
 * @property videoPingPayload the 16-character `X-SS-Ping-Payload` for the video socket, or `null`
 *   to use the legacy 4-byte `PING` (spec §7.5).
 * @property audioPingPayload the same for the audio socket.
 * @property controlConnectData the 32-bit ENet connect datum, `0` when the host sent none.
 * @property codec the codec that was announced.
 * @property hdr whether HDR was announced.
 * @property chromaSamplingType the announced `x-ss-video[0].chromaSamplingType`.
 * @property width announced width.
 * @property height announced height.
 * @property fps announced frame rate.
 * @property bitrateKbps announced bitrate — pinned as both floor and ceiling, so it is *the*
 *   bitrate for the whole session and cannot be changed without re-launching (spec §6.4). A total
 *   wire budget covering FEC, audio and headers, not a video-only figure
 *   (`docs/05-DYNAMIC-BITRATE.md` §1.3), so a link-quality estimator comparing measured throughput
 *   against it must not expect the encoder to be producing this many bits.
 * @property configuredBitrateKbps the raw number the user chose, as announced in
 *   `x-ml-video.configuredBitrateKbps`. Equal to [bitrateKbps] while no client-side adjustment is
 *   applied. Carried here so that anything reporting "your bitrate" to the user quotes the number
 *   the user actually set.
 * @property packetSize announced video payload size, which the video reassembler needs (spec §7.7).
 * @property encryptionFlags the `x-ss-general.encryptionEnabled` mask that was announced. `0` in
 *   v1, which is what tells the video and control layers not to expect encryption headers.
 * @property audioLayout the channel layout actually announced — not necessarily the one requested;
 *   see [audioLayoutDowngraded].
 * @property audioLayoutDowngraded true when surround was requested but the host's DESCRIBE offered
 *   no matching Opus configuration, so stereo was announced instead.
 * @property opusConfig the Opus multistream configuration for the audio decoder (spec §8.3).
 * @property announcedSdp the exact SDP body sent in ANNOUNCE, kept for logging and bug reports.
 * @property hostDescription the host's parsed DESCRIBE body, kept so the audio layer and future
 *   feature detection can read attributes without a second round trip.
 */
class NegotiatedSession(
    val host: String,
    val rtspPort: Int,
    val sessionId: String,
    val videoPort: Int,
    val audioPort: Int,
    val controlPort: Int,
    val controlSetupPerformed: Boolean,
    val videoPingPayload: String?,
    val audioPingPayload: String?,
    val controlConnectData: Int,
    val codec: VideoCodec,
    val hdr: Boolean,
    val chromaSamplingType: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val configuredBitrateKbps: Int,
    val packetSize: Int,
    val encryptionFlags: Int,
    val audioLayout: AudioChannelLayout,
    val audioLayoutDowngraded: Boolean,
    val opusConfig: OpusMultistreamConfig,
    val announcedSdp: String,
    val hostDescription: SessionDescription,
) {
    override fun toString(): String =
        "NegotiatedSession(session=$sessionId, video=$videoPort, audio=$audioPort, " +
            "control=$controlPort, codec=$codec, ${width}x${height}x$fps, " +
            "${bitrateKbps}kbps, packet=$packetSize, channels=${audioLayout.channelCount})"
}

/**
 * The outcome of [RtspSessionNegotiator.negotiate].
 *
 * A sealed result rather than an exception, for the same reason
 * [com.voidlink.android.protocol.http.NvHttpResult] is one: every failure here is an ordinary,
 * expected result of talking to a machine on someone's home network, and the caller has to render
 * each of them differently.
 */
sealed interface RtspSessionResult {

    /** The handshake completed and media is about to flow on the negotiated ports. */
    class Success(val session: NegotiatedSession) : RtspSessionResult

    /** The handshake did not complete. */
    class Failure(val error: RtspError) : RtspSessionResult

    /** The session on success, `null` otherwise. */
    fun sessionOrNull(): NegotiatedSession? = when (this) {
        is Success -> session
        is Failure -> null
    }

    /** True when the handshake completed. */
    val isSuccess: Boolean get() = this is Success
}
