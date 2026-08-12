package com.voidlink.android.protocol.session

import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.rtsp.RtspError

/**
 * Which stage of session start-up an outcome belongs to (`docs/02-ARCHITECTURE.md` §4).
 *
 * Every failure names one. The state machine of architecture §4 has a distinct state per stage for
 * exactly this reason: "Cannot start the stream" is not a diagnosis, and this project has already
 * shipped once with generic errors that made a firewall, an unpaired host and a broken decoder look
 * identical from a bug report.
 *
 * @property label short human-readable name, used in log lines and error text.
 */
enum class SessionStage(val label: String) {

    /** Finding a reachable address for the host and reading `/serverinfo` (spec §3.3). */
    RESOLVE("finding the host"),

    /** `GET /launch` or `GET /resume` (spec §3.6, §3.7). */
    LAUNCH("starting the game"),

    /** The RTSP handshake (spec §6). */
    NEGOTIATE("negotiating the stream"),

    /** ENet connect and the session-start sequence (spec §9.1, §9.4). */
    CONTROL("connecting the control channel"),

    /** Binding the video socket and starting the keep-alive ping (spec §7.5). */
    VIDEO_SETUP("opening the video channel"),

    /** Waiting for the first decodable frame (spec §11.1). */
    FIRST_FRAME("waiting for video"),

    /** The stream was up and something ended it. */
    STREAMING("streaming"),
}

/**
 * Why a streaming session did not start, or did not survive.
 *
 * A closed set of precisely separated causes, in the same style and for the same reason as
 * [NvHttpResult] and [RtspError]: the user is told which stage failed and what to do about it, and
 * the caller can decide whether "Retry" is worth offering. Spec §11.1 makes the same point about
 * the two video-timeout codes specifically — `NO_VIDEO_TRAFFIC` and `NO_VIDEO_FRAME` "are worth
 * surfacing as distinct user-facing text", because the first is a firewall and the second is a
 * protocol bug on our side.
 *
 * Cancellation is deliberately absent: a cancelled session throws
 * [kotlinx.coroutines.CancellationException] like every other suspending function in this codebase,
 * rather than being reported as a failure the user should be told about.
 *
 * @property stage where it happened.
 * @property summary one sentence naming the cause, fit to show a user as the failure screen's body.
 * @property detail technical text — an error code, a host response, a counter — for the small
 *   print. Never `null` when there is anything at all to say.
 * @property recoverable whether retrying unchanged could plausibly work, which is what decides
 *   whether the UI offers "Retry" (architecture §4.2).
 */
sealed class SessionFailure(
    val stage: SessionStage,
    val summary: String,
    val detail: String?,
    val recoverable: Boolean,
) {

    /** A one-line form for logs: `[stage] summary`. */
    fun describe(): String = "[${stage.label}] $summary" + (detail?.let { " ($it)" } ?: "")

    /** No address of this host answered `/serverinfo` (spec §1.3, §3.3). */
    class HostUnreachable(hostName: String, addresses: List<String>) : SessionFailure(
        stage = SessionStage.RESOLVE,
        summary = "$hostName did not answer. It may be asleep, off, or on a different network.",
        detail = if (addresses.isEmpty()) "No address is saved for this host." else
            "Tried: " + addresses.joinToString(", "),
        recoverable = true,
    )

    /** The host is saved but this client is not paired with it (spec §3.1). */
    class NotPaired(hostName: String) : SessionFailure(
        stage = SessionStage.RESOLVE,
        summary = "$hostName has not been paired with this device. Pair with a PIN first.",
        detail = null,
        recoverable = false,
    )

    /** The stream screen asked for a host id that no longer exists in the saved list. */
    class UnknownHost(hostId: String?) : SessionFailure(
        stage = SessionStage.RESOLVE,
        summary = "That host is no longer saved on this device.",
        detail = "Requested host id: ${hostId ?: "<none>"}",
        recoverable = false,
    )

    /** The stream screen asked for an app the host list cannot identify. */
    class UnknownApp(appId: String?) : SessionFailure(
        stage = SessionStage.RESOLVE,
        summary = "That game could not be identified, so there is nothing to launch.",
        detail = "Requested app id: ${appId ?: "<none>"}",
        recoverable = false,
    )

    /**
     * `/launch` or `/resume` failed, or reported that no session had started (spec §3.6, §3.7).
     *
     * The single most useful failure in the set: it is the one that means the host is reachable,
     * trusts us, and refused anyway — usually because another client already holds a session, or
     * because the requested mode is one it will not encode.
     */
    class LaunchRefused(
        val resumed: Boolean,
        val reason: String,
        val hostStatusCode: Int? = null,
    ) : SessionFailure(
        stage = SessionStage.LAUNCH,
        summary = if (resumed) "The host would not resume the running session."
        else "The host would not start the game.",
        detail = if (hostStatusCode != null) "$reason (host status $hostStatusCode)" else reason,
        recoverable = true,
    )

    /** The RTSP handshake failed; [error] says which of its eight steps and why (spec §6.3). */
    class NegotiationFailed(val error: RtspError) : SessionFailure(
        stage = SessionStage.NEGOTIATE,
        summary = "The host started the game but would not negotiate a stream: ${error.describe()}.",
        detail = "RTSP step: ${error.step.label}",
        recoverable = error is RtspError.Timeout || error is RtspError.Unreachable,
    )

    /**
     * The ENet control connection did not come up (spec §9.1).
     *
     * Distinct from an RTSP failure even though both are "the host stopped talking to us": RTSP is
     * TCP and the control stream is UDP on a different port, so this failure specifically implicates
     * UDP being blocked or a Sunshine base-port mismatch.
     */
    class ControlConnectFailed(val port: Int, val waitedMs: Long) : SessionFailure(
        stage = SessionStage.CONTROL,
        summary = "The control connection to the host timed out. UDP port $port may be blocked.",
        detail = "No ENet handshake completed within ${waitedMs} ms (spec §9.1).",
        recoverable = true,
    )

    /** The local UDP socket for video could not be opened or bound (spec §7.5). */
    class VideoSocketFailed(val message: String) : SessionFailure(
        stage = SessionStage.VIDEO_SETUP,
        summary = "This device would not open a socket to receive video on.",
        detail = message,
        recoverable = true,
    )

    /**
     * `ML_ERROR_NO_VIDEO_TRAFFIC` — not one packet arrived on the video port (spec §11.1).
     *
     * Spec §11.1: "almost always means a firewall or a NAT problem (our ping never opened the
     * pinhole)". Said plainly, because the fix is on the host's machine and no amount of retrying
     * from here will find it.
     */
    class NoVideoTraffic(val port: Int, val waitedMs: Long) : SessionFailure(
        stage = SessionStage.FIRST_FRAME,
        summary = "The stream started but no video arrived. A firewall on the host is the usual " +
            "cause — UDP port $port has to be open to this device.",
        detail = "Nothing was received in ${waitedMs} ms (ML_ERROR_NO_VIDEO_TRAFFIC).",
        recoverable = true,
    )

    /**
     * `ML_ERROR_NO_VIDEO_FRAME` — packets arrived but no frame ever completed (spec §11.1).
     *
     * Spec §11.1: "we are receiving but reassembly is failing — the FEC/reassembly code is broken or
     * the packet size is wrong". That is a bug in this client, and the counters in [detail] are what
     * make it reportable.
     */
    class NoVideoFrame(
        val packetsReceived: Long,
        val packetsRejected: Long,
        val framesDropped: Long,
        val waitedMs: Long,
    ) : SessionFailure(
        stage = SessionStage.FIRST_FRAME,
        summary = "Video packets are arriving but none of them formed a complete frame. That is a " +
            "fault in this app's reassembly, not in your network.",
        detail = "$packetsReceived packets received, $packetsRejected rejected, $framesDropped " +
            "frames dropped in ${waitedMs} ms (ML_ERROR_NO_VIDEO_FRAME).",
        recoverable = true,
    )

    /** The host sent a termination message (spec §9.6). */
    class HostTerminated(
        val errorCode: Int?,
        val duringStartup: Boolean,
        description: String,
    ) : SessionFailure(
        stage = if (duringStartup) SessionStage.FIRST_FRAME else SessionStage.STREAMING,
        summary = description,
        detail = errorCode?.let {
            "Host termination code 0x" + it.toLong().and(0xFFFFFFFFL).toString(16)
        },
        recoverable = true,
    )

    /**
     * Something threw where nothing was expected to.
     *
     * Deliberately last and deliberately ugly to read: every occurrence is a gap in the
     * classification above, and it carries the exception class name so the gap can be found.
     */
    class Unexpected(stage: SessionStage, cause: Throwable) : SessionFailure(
        stage = stage,
        summary = "The session failed while ${stage.label}.",
        detail = cause.message?.let { "${cause.javaClass.simpleName}: $it" }
            ?: cause.javaClass.simpleName,
        recoverable = true,
    )

    companion object {

        /**
         * Maps a failed `/launch` or `/resume` onto [LaunchRefused].
         *
         * Every [NvHttpResult] failure shape lands here, because from the session's point of view
         * they all mean the same thing — no game is running — while carrying very different text.
         */
        fun fromLaunchResult(result: NvHttpResult<*>, resumed: Boolean): LaunchRefused {
            val statusCode = (result as? NvHttpResult.HostError)?.statusCode
            return LaunchRefused(
                resumed = resumed,
                reason = result.errorDescription() ?: "the host gave no reason",
                hostStatusCode = statusCode,
            )
        }
    }
}
