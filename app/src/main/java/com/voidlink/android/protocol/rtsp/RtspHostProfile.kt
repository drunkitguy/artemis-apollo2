package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.http.AppVersion
import com.voidlink.android.protocol.http.ServerInfo
import com.voidlink.android.protocol.http.ServerKind

/**
 * The few facts about a host that the RTSP handshake branches on (spec §0.3, §6.3, §6.4).
 *
 * A deliberately narrow view of [ServerInfo]: the negotiator needs the generation, one
 * `appversion` predicate and the Sunshine-vs-GFE discriminator, and nothing else. Taking the whole
 * `/serverinfo` document instead would tie every RTSP test to a full XML fixture for no gain.
 *
 * @property appVersion the host's `appversion` quad.
 * @property serverKind which host family this is.
 */
class RtspHostProfile(
    val appVersion: AppVersion,
    val serverKind: ServerKind,
) {

    /** `AppVersionQuad[0]` — 3, 4, 5 or 7. Modern hosts all report 7 (spec §0.3). */
    val generation: Int get() = appVersion.generation

    /** True for Sunshine, Apollo and forks, which are the only hosts sent the `x-ss-*`/`x-ml-*` set. */
    val isSunshineish: Boolean get() = serverKind == ServerKind.SUNSHINE_FAMILY

    /** True for genuine NVIDIA GameStream. */
    val isNvidiaGfe: Boolean get() = serverKind == ServerKind.NVIDIA_GFE

    /**
     * Gen ≥ 5: the `streamid=<name>/0/0` target forms, and a control stream that exists at all
     * (spec §6.3).
     */
    val usesModernStreamIds: Boolean get() = generation >= RtspConstants.CONTROL_SETUP_MIN_GENERATION

    /** Gen ≥ 5: a control SETUP is performed. Below that the control stream is legacy TCP (§9.1). */
    val performsControlSetup: Boolean get() = usesModernStreamIds

    /**
     * `appversion >= 7.1.431`: the control stream id is `control/13/0` rather than `control/1/0`,
     * and ANNOUNCE is addressed to it rather than to `streamid=video` (spec §6.3).
     */
    val usesModernControlStreamId: Boolean
        get() = appVersion.atLeast(
            RtspConstants.CONTROL_STREAM_ID_MIN_MAJOR,
            RtspConstants.CONTROL_STREAM_ID_MIN_MINOR,
            RtspConstants.CONTROL_STREAM_ID_MIN_PATCH,
        )

    /**
     * Gen ≥ 7: the `…Kbps` bitrate spellings, `clientRefreshRateX100`, `encoderCscMode`,
     * `packetDuration`, and `useReliableUdp=13` (spec §6.4).
     */
    val usesModernAttributes: Boolean
        get() = generation >= RtspConstants.MODERN_ATTRIBUTE_MIN_GENERATION

    /** `x-nv-general.useReliableUdp` — a substream bitmask on Gen 7+, a plain flag below (§6.4). */
    val useReliableUdp: Int
        get() = if (usesModernAttributes) RtspConstants.USE_RELIABLE_UDP_GEN7
        else RtspConstants.USE_RELIABLE_UDP_LEGACY

    /** The SETUP target for the audio stream (spec §6.3). */
    val audioStreamId: String
        get() = if (usesModernStreamIds) RtspConstants.STREAM_ID_AUDIO_MODERN
        else RtspConstants.STREAM_ID_AUDIO_LEGACY

    /** The SETUP target for the video stream (spec §6.3). */
    val videoStreamId: String
        get() = if (usesModernStreamIds) RtspConstants.STREAM_ID_VIDEO_MODERN
        else RtspConstants.STREAM_ID_VIDEO_LEGACY

    /** The SETUP target for the control stream; only meaningful when [performsControlSetup]. */
    val controlStreamId: String
        get() = if (usesModernControlStreamId) RtspConstants.STREAM_ID_CONTROL_MODERN
        else RtspConstants.STREAM_ID_CONTROL_LEGACY

    /**
     * The ANNOUNCE target (spec §6.3): the control stream id on `appversion >= 7.1.431`, and the
     * bare `streamid=video` otherwise — note that this is the *legacy* video id even on a Gen 5
     * host that uses `streamid=video/0/0` for its SETUP.
     */
    val announceStreamId: String
        get() = if (usesModernControlStreamId) RtspConstants.STREAM_ID_CONTROL_MODERN
        else RtspConstants.STREAM_ID_VIDEO_LEGACY

    override fun toString(): String = "RtspHostProfile(appversion=$appVersion, kind=$serverKind)"

    companion object {
        /**
         * The one-liner the session wiring uses: everything the RTSP layer needs about a host comes
         * out of the `/serverinfo` document the app already holds.
         */
        fun fromServerInfo(serverInfo: ServerInfo): RtspHostProfile =
            RtspHostProfile(serverInfo.appVersion, serverInfo.serverKind)
    }
}
