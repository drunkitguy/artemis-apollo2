package com.voidlink.android.protocol.session

import com.voidlink.android.data.StreamSettings
import com.voidlink.android.data.SurroundMode
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.http.AudioChannelLayout
import com.voidlink.android.protocol.http.LaunchRequest
import com.voidlink.android.protocol.rtsp.NetworkProfile
import com.voidlink.android.protocol.rtsp.StreamConfiguration
import com.voidlink.android.protocol.rtsp.VideoCodec
import java.security.SecureRandom

/**
 * The per-session secrets generated before `/launch` (spec §5).
 *
 * @property key the 16-byte remote-input AES key, sent hex as `rikey`.
 * @property keyId the 32-bit key id, sent decimal as `rikeyid`.
 */
class RemoteInputKey(val key: ByteArray, val keyId: Int) {

    companion object {
        /** Spec §5: 16 bytes from `SecureRandom`, and an `Int` from the same source. */
        const val KEY_BYTES: Int = 16

        /** Generates a fresh key. A resume must generate a new one too (spec §3.7). */
        fun generate(random: SecureRandom = SecureRandom()): RemoteInputKey {
            val key = ByteArray(KEY_BYTES)
            random.nextBytes(key)
            return RemoteInputKey(key, random.nextInt())
        }
    }
}

/**
 * Everything `/launch` and RTSP need, derived from one [StreamSettings] (spec §5, §6.4).
 *
 * @property launch the `/launch` (or `/resume`) request.
 * @property configuration the ANNOUNCE configuration, which **must** agree with [launch] on
 *   resolution, frame rate and channel layout — the host builds its DESCRIBE answer from what
 *   `/launch` asked for, and a mismatch produces a session that negotiates cleanly and then behaves
 *   oddly. Building both from one function is how that agreement is guaranteed rather than hoped
 *   for.
 * @property remoteInputKey the key pair that travels in `/launch` and is consumed by input
 *   encryption (spec §10.1) and, when it is ever enabled, by control encryption (spec §9.2).
 */
class SessionParameters(
    val launch: LaunchRequest,
    val configuration: StreamConfiguration,
    val remoteInputKey: RemoteInputKey,
)

/**
 * Where the user's settings finally reach the wire (`docs/02-ARCHITECTURE.md` §6.1).
 *
 * Architecture §6.1 gives a field-by-field table of what each setting maps to, and says there is no
 * third category between "maps to something in the protocol" and "explicitly local-only". This
 * object is that table in code, for the fields that reach `/launch` and ANNOUNCE:
 *
 * | Setting | Destination |
 * |---|---|
 * | `bitrateKbps` | `initialBitrateKbps` / `bw.minimumBitrateKbps` / `bw.maximumBitrateKbps` |
 * | `codec` | resolved to a concrete codec by the decoder probe, then `bitStreamFormat` |
 * | `hdrEnabled` | `/launch?hdrMode=1&clientHdrCap*` and SDP `dynamicRangeMode` |
 * | `yuv444Enabled` | SDP `x-ss-video[0].chromaSamplingType` |
 * | `resolution`, `frameRate` | `/launch?mode=WxHxF` and SDP `clientViewportWd/Ht`, `maxFPS` |
 * | `optimizeGameSettings` | `/launch?sops=`, subject to the NVIDIA clamp inside [LaunchRequest] |
 * | `surroundMode` | `/launch?surroundAudioInfo=` and the SDP surround attributes |
 * | `muteHostAudio` | `/launch?localAudioPlayMode=`, **inverted** |
 *
 * Pure and free of I/O on purpose: this is the one place a wrong mapping would be invisible until a
 * user reported that a setting "does nothing", and it is fully unit-testable.
 */
object SessionParameterMapper {

    /**
     * Builds both halves of the session request.
     *
     * @param settings the merged global + per-host settings for this session.
     * @param appId the host-assigned app id from `/applist`.
     * @param width negotiated width, already resolved from the resolution setting and clamped by
     *   the decoder probe (`media/StreamFormatResolver`).
     * @param height negotiated height, likewise.
     * @param fps negotiated frame rate.
     * @param codec the codec the decoder was actually selected for — *not* the raw preference,
     *   because `AUTO` has no wire representation and because a device that cannot decode HEVC must
     *   not ask a host to send it.
     * @param hdr whether HDR survived decoder selection. May be false even when
     *   `settings.hdrEnabled` is true, which architecture §6.3 requires the UI to say plainly.
     * @param displayRefreshRateHz the panel's refresh rate, for `clientRefreshRateX100`.
     * @param attachedGamepadMask bitmask of pads the host should expose; see [gamepadMaskFor].
     * @param network LAN or WAN, which picks packet size, FEC overhead and both QoS classes.
     * @param remoteInputKey the freshly generated key pair; a resume needs a new one (spec §3.7).
     */
    fun build(
        settings: StreamSettings,
        appId: Long,
        width: Int,
        height: Int,
        fps: Int,
        codec: VideoCodec,
        hdr: Boolean,
        displayRefreshRateHz: Int = fps,
        attachedGamepadMask: Int = gamepadMaskFor(settings),
        network: NetworkProfile = DEFAULT_NETWORK_PROFILE,
        remoteInputKey: RemoteInputKey = RemoteInputKey.generate(),
    ): SessionParameters {
        val coerced = settings.coerced()
        val layout = audioLayoutFor(coerced.surroundMode)

        val launch = LaunchRequest(
            appId = appId,
            width = width,
            height = height,
            fps = fps,
            remoteInputKey = remoteInputKey.key,
            remoteInputKeyId = remoteInputKey.keyId,
            optimizeGameSettings = coerced.optimizeGameSettings,
            hdr = hdr,
            // Inverted, and the inversion is the whole point: "mute host audio" is the user-facing
            // phrasing, `localAudioPlayMode=1` is the host-facing one (architecture §6.1).
            playAudioOnHost = !coerced.muteHostAudio,
            audioLayout = layout,
            attachedGamepadMask = attachedGamepadMask,
            // A derived constant, not a user-facing row (architecture §6.1's second table).
            persistGamepadsAfterDisconnect = PERSIST_GAMEPADS,
        )

        val configuration = StreamConfiguration(
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = coerced.bitrateKbps,
            codec = codec,
            // Equal in v1, and they must stay separately named: the negotiated value is a total
            // wire budget the host deducts FEC and audio from, while the configured value is the
            // number the user chose and the number the UI must quote back
            // (`docs/05-DYNAMIC-BITRATE.md` §1.3).
            configuredBitrateKbps = coerced.bitrateKbps,
            hdr = hdr,
            yuv444 = coerced.yuv444Enabled,
            audioLayout = layout,
            network = network,
            displayRefreshRateHz = displayRefreshRateHz,
        )

        return SessionParameters(launch, configuration, remoteInputKey)
    }

    /** Maps the user's surround choice onto the protocol's channel layout (spec §8.2). */
    fun audioLayoutFor(mode: SurroundMode): AudioChannelLayout = when (mode) {
        SurroundMode.STEREO -> AudioChannelLayout.STEREO
        SurroundMode.SURROUND_5_1 -> AudioChannelLayout.SURROUND_5_1
        SurroundMode.SURROUND_7_1 -> AudioChannelLayout.SURROUND_7_1
    }

    /**
     * The `gcmap` / `remoteControllersBitmap` value (spec §3.6, architecture §6.1).
     *
     * Architecture §6.1 says this is "computed live from connected controllers", which requires the
     * controller manager of `platform/input/` — a package that does not exist yet and is not in this
     * layer's scope. Until it does, the mask is derived from `emulatedControllerCount`: asking the
     * host for the number of pads the user configured is both what the setting means and the only
     * behaviour that makes a controller work at all before input lands.
     *
     * A stand-in, and logged as one. When `platform/input/` arrives, the live mask should be passed
     * to [build] explicitly and this fallback should stop being reached.
     */
    fun gamepadMaskFor(settings: StreamSettings): Int {
        val count = settings.emulatedControllerCount
            .coerceIn(StreamSettings.CONTROLLERS_MIN, StreamSettings.CONTROLLERS_MAX)
        val mask = (1 shl count) - 1
        ProtocolLog.unverified(
            SessionConstants.TAG,
            "session-gamepad-mask",
            "sending gcmap/remoteControllersBitmap=0x${mask.toString(16)} derived from the " +
                "emulatedControllerCount setting ($count pads); architecture §6.1 wants this " +
                "computed live from connected controllers once platform/input exists",
        )
        return mask
    }

    /**
     * `gcpersist` — always `1` (architecture §6.1's derived-constants table).
     *
     * Virtual pads outlive a physical controller unplug, so a controller that drops off Bluetooth
     * for a second does not make the game lose its player.
     */
    const val PERSIST_GAMEPADS: Boolean = true

    /**
     * The network profile assumed when nothing says otherwise.
     *
     * There is no LAN/WAN setting in [StreamSettings], and spec §5 makes the choice matter: it sets
     * the video packet size (1392 vs 1024), the FEC overhead and both QoS traffic classes. LAN is
     * the right default for this product — `docs/00-OVERVIEW.md` targets local streaming — and a
     * 1392-byte payload on a path with a smaller MTU fragments rather than fails.
     */
    val DEFAULT_NETWORK_PROFILE: NetworkProfile = NetworkProfile.LAN
}
