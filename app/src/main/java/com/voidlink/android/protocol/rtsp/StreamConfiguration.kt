package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.http.AudioChannelLayout

/** The codec we are asking the host to encode with (spec §6.4, §7.1). */
enum class VideoCodec(
    /** `x-nv-vqos[0].bitStreamFormat`. */
    val bitStreamFormat: Int,
) {
    H264(0),
    HEVC(1),
    AV1(2),
    ;

    /**
     * `x-nv-clientSupportHevc` — `1` only when HEVC is what we are requesting (spec §6.4).
     *
     * Notably `0` for AV1: the attribute names HEVC specifically, and an AV1 session that claims
     * HEVC support invites the host to fall back to a codec we did not ask for.
     */
    val clientSupportHevc: Int get() = if (this == HEVC) 1 else 0
}

/** `colorSpace` half of `x-nv-video[0].encoderCscMode` (spec §7.1). */
enum class VideoColorSpace(val value: Int) {
    REC_601(0),
    REC_709(1),
    REC_2020(2),
}

/** `colorRange` half of `x-nv-video[0].encoderCscMode` (spec §7.1). */
enum class VideoColorRange(val value: Int) {
    LIMITED(0),
    FULL(1),
}

/**
 * Which side of the FEC/QoS fork we are on (spec §5, §6.4).
 *
 * Not a guess about the network: it is the user's remote-vs-local choice, and it changes the video
 * packet size, the FEC overhead and both QoS traffic classes together.
 */
enum class NetworkProfile {
    LAN,
    WAN,
}

/**
 * Everything the ANNOUNCE SDP is generated from (spec §6.4).
 *
 * Pure data with pure derivations, which is the point: the whole `x-nv-*` / `x-ss-*` / `x-ml-*`
 * attribute set is a function of this object plus the host's generation, so it can be pinned byte
 * for byte by a test that never opens a socket.
 *
 * The values here must match what was sent to `/launch` — the resolution, frame rate and channel
 * layout in particular. The host builds its DESCRIBE answer (including the Opus surround
 * parameters of spec §8.3) from the launch request, so a mismatch is not caught anywhere; it just
 * produces a stream that is subtly the wrong shape.
 *
 * @property width negotiated width in pixels.
 * @property height negotiated height in pixels.
 * @property fps negotiated frame rate.
 * @property bitrateKbps the user's configured bitrate. Sent as both the floor and the ceiling,
 *   which disables the host's adaptive bitrate — see [SdpGenerator] for why that is deliberate,
 *   and note the consequence: bitrate cannot change without re-launching the session.
 * @property codec the codec being requested.
 * @property hdr whether to request 10-bit HDR. Forces a 10-bit profile, hence HEVC or AV1.
 * @property yuv444 whether to request 4:4:4 chroma. Sunshine-only, and rarely decodable on mobile.
 * @property audioLayout requested channel layout, matching `/launch`'s `surroundAudioInfo`.
 * @property network LAN or WAN, which picks packet size, FEC overhead and QoS classes.
 * @property displayRefreshRateHz the panel's refresh rate, for `clientRefreshRateX100`. Defaults to
 *   [fps] because a client asking for 120 fps on a 60 Hz panel is the odd case, not the norm.
 * @property colorSpace defaults to BT.2020 for HDR and BT.709 otherwise (spec §7.1).
 * @property colorRange defaults to limited range for both (spec §7.1).
 * @property videoEncoderSlicesPerFrame slices per frame; `1` for a hardware decoder.
 * @property encryptionFlags the `x-ss-general.encryptionEnabled` mask (spec §6.5). Defaults to
 *   [UnverifiedRtspConstants.ENCRYPTION_FLAGS_DEFAULT], which is `0` — the spec's v1 decision.
 * @property slowAudioDecoder lengthens `x-nv-aqos.packetDuration` to 10 ms (spec §6.4).
 * @property packetSizeOverride replaces the LAN/WAN default video payload size. For experiments
 *   against a host with an unusual MTU; leave `null`.
 */
data class StreamConfiguration(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val codec: VideoCodec,
    val hdr: Boolean = false,
    val yuv444: Boolean = false,
    val audioLayout: AudioChannelLayout = AudioChannelLayout.STEREO,
    val network: NetworkProfile = NetworkProfile.LAN,
    val displayRefreshRateHz: Int = fps,
    val colorSpace: VideoColorSpace = if (hdr) VideoColorSpace.REC_2020 else VideoColorSpace.REC_709,
    val colorRange: VideoColorRange = VideoColorRange.LIMITED,
    val videoEncoderSlicesPerFrame: Int = RtspConstants.VIDEO_ENCODER_SLICES_PER_FRAME,
    val encryptionFlags: Int = UnverifiedRtspConstants.ENCRYPTION_FLAGS_DEFAULT,
    val slowAudioDecoder: Boolean = false,
    val packetSizeOverride: Int? = null,
) {

    /** True when [encryptionFlags] asks for video payload encryption (spec §6.5, §7.6). */
    val videoEncryptionEnabled: Boolean
        get() = encryptionFlags and UnverifiedRtspConstants.SS_ENC_VIDEO != 0

    /** The LAN/WAN video payload size before the encryption adjustment (spec §5). */
    val basePacketSize: Int
        get() = packetSizeOverride ?: when (network) {
            NetworkProfile.LAN -> RtspConstants.PACKET_SIZE_LAN
            NetworkProfile.WAN -> RtspConstants.PACKET_SIZE_WAN
        }

    /**
     * `x-nv-video[0].packetSize` — the value actually announced (spec §5, §6.4).
     *
     * With video encryption on, 32 bytes go to the `ENC_VIDEO_HEADER` of spec §7.6 and the result
     * must stay a multiple of 16. Both LAN and WAN defaults already satisfy that after the
     * subtraction; the explicit round-down exists so a [packetSizeOverride] cannot quietly break
     * the alignment rule.
     */
    val packetSize: Int
        get() {
            if (!videoEncryptionEnabled) return basePacketSize
            val reduced = basePacketSize - RtspConstants.PACKET_SIZE_ENCRYPTION_REDUCTION
            val alignment = RtspConstants.PACKET_SIZE_ENCRYPTION_ALIGNMENT
            return (reduced / alignment) * alignment
        }

    /** `x-nv-vqos[0].fec.repairPercent` — the parity overhead percentage (spec §6.4). */
    val fecRepairPercent: Int
        get() = when (network) {
            NetworkProfile.LAN -> RtspConstants.FEC_REPAIR_PERCENT_LAN
            NetworkProfile.WAN -> RtspConstants.FEC_REPAIR_PERCENT_WAN
        }

    /** `x-nv-vqos[0].qosTrafficType` (spec §6.4). */
    val videoQosTrafficType: Int
        get() = when (network) {
            NetworkProfile.LAN -> RtspConstants.QOS_TRAFFIC_TYPE_VIDEO_LAN
            NetworkProfile.WAN -> RtspConstants.QOS_TRAFFIC_TYPE_VIDEO_WAN
        }

    /** `x-nv-aqos.qosTrafficType` (spec §6.4). */
    val audioQosTrafficType: Int
        get() = when (network) {
            NetworkProfile.LAN -> RtspConstants.QOS_TRAFFIC_TYPE_AUDIO_LAN
            NetworkProfile.WAN -> RtspConstants.QOS_TRAFFIC_TYPE_AUDIO_WAN
        }

    /** `x-nv-video[0].encoderCscMode` — `(colorSpace shl 1) or colorRange` (spec §6.4). */
    val encoderCscMode: Int get() = (colorSpace.value shl 1) or colorRange.value

    /** `x-nv-video[0].clientRefreshRateX100` (spec §6.4). */
    val clientRefreshRateX100: Int
        get() = displayRefreshRateHz * RtspConstants.REFRESH_RATE_SCALE

    /** `x-nv-aqos.packetDuration`, in milliseconds (spec §6.4). */
    val audioPacketDurationMs: Int
        get() = if (slowAudioDecoder) RtspConstants.AUDIO_PACKET_DURATION_SLOW_MS
        else RtspConstants.AUDIO_PACKET_DURATION_MS

    /** `x-nv-audio.surround.enable` — surround is on whenever there are more than two channels. */
    val surroundEnabled: Boolean get() = audioLayout.channelCount > 2

    /** `x-ss-video[0].chromaSamplingType` (spec §6.4). */
    val chromaSamplingType: Int
        get() = if (yuv444) RtspConstants.CHROMA_SAMPLING_444 else RtspConstants.CHROMA_SAMPLING_420

    override fun toString(): String =
        "${width}x${height}x$fps ${codec.name} ${bitrateKbps}kbps " +
            (if (hdr) "HDR " else "SDR ") +
            "${audioLayout.channelCount}ch $network"
}
