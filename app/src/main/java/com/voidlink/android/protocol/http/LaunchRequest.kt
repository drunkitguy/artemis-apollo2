package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.UnverifiedProtocolConstants

/**
 * The audio channel layout asked for at launch (spec §8.2).
 *
 * Declared here rather than reused from the settings model so the protocol layer stays independent
 * of the data layer, as architecture §1 requires.
 *
 * @property channelCount number of channels.
 * @property channelMask the layout mask that accompanies it.
 */
enum class AudioChannelLayout(val channelCount: Int, val channelMask: Int) {
    STEREO(2, 0x3),
    SURROUND_5_1(6, 0x3F),
    SURROUND_7_1(8, 0x63F),
    ;

    /**
     * `(channelMask shl 16) or channelCount` — the `surroundAudioInfo` query value (spec §8.2).
     *
     * Note this is *not* the composite `MAKE_AUDIO_CONFIGURATION` value: that one carries an
     * additional `0xCA` marker and never appears on the wire.
     */
    val surroundAudioInfo: Int get() = (channelMask shl 16) or channelCount
}

/**
 * Everything `/launch` and `/resume` need (spec §3.6, §3.7).
 *
 * [toQueryParams] is pure, which matters: the SOPS clamp and the NVIDIA frame-rate workaround are
 * exactly the sort of conditional that silently rots, and here they are unit-testable without a
 * host.
 *
 * @property appId the app's `ID` from `/applist`.
 * @property width negotiated width.
 * @property height negotiated height.
 * @property fps negotiated frame rate.
 * @property remoteInputKey the 16-byte AES remote-input key (spec §5).
 * @property remoteInputKeyId the 32-bit key id, sent in decimal.
 * @property optimizeGameSettings the user's SOPS preference, before clamping.
 * @property hdr whether HDR was requested.
 * @property playAudioOnHost whether the host should also play the audio locally.
 * @property audioLayout requested channel layout.
 * @property attachedGamepadMask bitmask of connected controllers.
 * @property persistGamepadsAfterDisconnect whether virtual pads outlive a controller unplug.
 */
class LaunchRequest(
    val appId: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val remoteInputKey: ByteArray,
    val remoteInputKeyId: Int,
    val optimizeGameSettings: Boolean,
    val hdr: Boolean,
    val playAudioOnHost: Boolean,
    val audioLayout: AudioChannelLayout,
    val attachedGamepadMask: Int,
    val persistGamepadsAfterDisconnect: Boolean,
) {

    /**
     * Renders the query parameters in the order the reference implementation sends them.
     *
     * Two host-specific corrections are applied here, both from spec §3.6:
     *
     * * **SOPS clamp** — on NVIDIA hosts, asking for a non-standard resolution above 720p with
     *   `sops=1` makes GFE clamp the whole session to 720p60, so SOPS is forced off in that case.
     * * **Frame-rate workaround** — some GFE builds reject `fps > 60` in `mode`, and the
     *   established workaround is to send `0` and let RTSP negotiate. UNVERIFIED which builds
     *   need it, so it is applied only to NVIDIA hosts above 60 fps and logged when it fires.
     *
     * @param isNvidiaGfe whether the host is genuine GFE (spec §0.3).
     */
    fun toQueryParams(isNvidiaGfe: Boolean): List<Pair<String, String>> {
        val effectiveSops = resolveSops(isNvidiaGfe)
        val modeFps = resolveModeFps(isNvidiaGfe)

        val params = ArrayList<Pair<String, String>>(16)
        params += "appid" to appId.toString()
        params += "mode" to "${width}x${height}x$modeFps"
        params += "additionalStates" to "1"
        params += "sops" to if (effectiveSops) "1" else "0"
        params += "rikey" to Hex.encode(remoteInputKey)
        params += "rikeyid" to remoteInputKeyId.toString()
        if (hdr) {
            // These four accompany hdrMode and are only sent when HDR is requested (spec §3.6).
            params += "hdrMode" to "1"
            params += "clientHdrCapVersion" to "0"
            params += "clientHdrCapSupportedFlagsInUint32" to "0"
            params += "clientHdrCapMetaDataId" to "NV_STATIC_METADATA_TYPE_1"
            params += "clientHdrCapDisplayData" to "0x0x0x0x0x0x0x0x0x0x0"
        }
        params += "localAudioPlayMode" to if (playAudioOnHost) "1" else "0"
        params += "surroundAudioInfo" to audioLayout.surroundAudioInfo.toString()
        params += "remoteControllersBitmap" to attachedGamepadMask.toString()
        params += "gcmap" to attachedGamepadMask.toString()
        params += "gcpersist" to if (persistGamepadsAfterDisconnect) "1" else "0"
        return params
    }

    /**
     * Applies the SOPS clamp of spec §3.6.
     *
     * @return the SOPS flag actually sent.
     */
    fun resolveSops(isNvidiaGfe: Boolean): Boolean {
        if (!optimizeGameSettings || !isNvidiaGfe) return optimizeGameSettings
        val pixels = width.toLong() * height.toLong()
        if (pixels <= SOPS_SAFE_PIXELS) return true
        val isStandard = STANDARD_SOPS_MODES.any { (w, h) -> w == width && h == height }
        if (isStandard) return true
        ProtocolLog.w(
            ProtocolLog.TAG_HTTP,
            "Forcing sops=0: GFE clamps non-standard ${width}x$height to 720p60 (spec 01 §3.6)",
        )
        return false
    }

    /**
     * Applies the NVIDIA frame-rate workaround of spec §3.6.
     *
     * @return the value to place in the `mode` string's fps position.
     */
    fun resolveModeFps(isNvidiaGfe: Boolean): Int {
        val applies = UnverifiedProtocolConstants.NVIDIA_FPS_WORKAROUND_ENABLED &&
            isNvidiaGfe &&
            fps > UnverifiedProtocolConstants.NVIDIA_FPS_WORKAROUND_THRESHOLD
        if (!applies) return fps
        ProtocolLog.unverified(
            ProtocolLog.TAG_HTTP,
            "gfe-fps-zero-workaround",
            "sending mode fps=0 for a GFE host at ${fps}fps and letting RTSP negotiate " +
                "(spec 01 §3.6, item 23)",
        )
        return 0
    }

    private companion object {
        /** At or below 720p, GFE never clamps, so SOPS is always safe. */
        const val SOPS_SAFE_PIXELS = 1280L * 720L

        /** Resolutions GFE handles correctly with SOPS enabled above 720p (spec §3.6). */
        val STANDARD_SOPS_MODES = listOf(1920 to 1080, 3840 to 2160)
    }
}

/**
 * The parsed reply to `/launch` or `/resume` (spec §3.6, §3.7).
 *
 * @property started true when the host reported success — `<gamesession>` for a launch,
 *   `<resume>` for a resume.
 * @property sessionUrl the raw `sessionUrl0`, when the host supplied one.
 * @property rtspPort the port parsed out of [sessionUrl], or `null` to fall back to the default.
 * @property rtspOverEnet true when the URL used the `rtspru://` scheme.
 */
class LaunchResponse(
    val started: Boolean,
    val sessionUrl: String?,
    val rtspPort: Int?,
    val rtspOverEnet: Boolean,
) {
    companion object {
        /**
         * Maps a `/launch` or `/resume` `<root>` onto [LaunchResponse].
         *
         * @param root the response root element.
         * @param successElement `gamesession` for a launch, `resume` for a resume.
         */
        fun fromXml(root: XmlNode, successElement: String): LaunchResponse {
            val sessionUrl = root.textOf("sessionUrl0")
            val parsed = parseSessionUrl(sessionUrl)
            return LaunchResponse(
                started = (root.textOf(successElement)?.toLongOrNull() ?: 0L) != 0L,
                sessionUrl = sessionUrl,
                rtspPort = parsed?.first,
                rtspOverEnet = parsed?.second == true,
            )
        }

        /**
         * Extracts the port and scheme from `rtsp://host:port` / `rtspru://host:port`.
         *
         * Hand-parsed rather than handed to `java.net.URI` because `rtspru` is not a scheme the
         * platform knows and older hosts emit URLs that do not survive strict parsing.
         *
         * @return port and "is rtspru", or `null` when no port could be found.
         */
        fun parseSessionUrl(url: String?): Pair<Int?, Boolean>? {
            val text = url?.trim().orEmpty()
            if (text.isEmpty()) return null
            val schemeEnd = text.indexOf("://")
            if (schemeEnd <= 0) return null
            val isEnet = text.substring(0, schemeEnd).equals("rtspru", ignoreCase = true)
            val authority = text.substring(schemeEnd + 3).substringBefore('/')
            val port = if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) null else authority.substring(close + 1).removePrefix(":").toIntOrNull()
            } else {
                authority.substringAfterLast(':', "").toIntOrNull()
            }
            return (port?.takeIf { it in 1..65535 }) to isEnet
        }
    }
}
