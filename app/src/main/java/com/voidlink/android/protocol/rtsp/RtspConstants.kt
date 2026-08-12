package com.voidlink.android.protocol.rtsp

/**
 * Every constant the RTSP session negotiation needs, transcribed from `docs/01-PROTOCOL.md` §6 and
 * cross-referenced by section.
 *
 * This is the RTSP counterpart of [com.voidlink.android.protocol.ProtocolConstants]: that object
 * documents itself as the single home for protocol constants, and these values belong there in
 * spirit. They live here only because `protocol/rtsp/**` is the tree this work is allowed to touch;
 * folding them into `ProtocolConstants` later is a pure move with no behaviour change. Values that
 * `ProtocolConstants` already carries — notably
 * [com.voidlink.android.protocol.ProtocolConstants.DEFAULT_RTSP_PORT] — are *referenced*, never
 * duplicated.
 *
 * Values the spec explicitly marks **UNVERIFIED** live in [UnverifiedRtspConstants] instead, so the
 * guessed surface of the RTSP layer stays countable at a glance.
 */
object RtspConstants {

    /** Logcat tag for the RTSP subsystem, matching the `VL.<Subsystem>` convention. */
    const val TAG: String = "VL.Rtsp"

    // ---- Message format (spec §6.2) ----------------------------------------------------------

    /** Protocol token on every request line and every status line. */
    const val PROTOCOL_VERSION: String = "RTSP/1.0"

    /** Line terminator. RTSP is HTTP-shaped: CRLF, everywhere, including inside the SDP body. */
    const val CRLF: String = "\r\n"

    /**
     * `X-GS-ClientVersion` — the RTSP client version modern hosts expect (spec §6.2).
     *
     * The spec notes it is UNVERIFIED whether a *lower* value degrades anything on Sunshine, but
     * `14` itself is mandated rather than guessed, so this is not one of the swappable guesses in
     * [UnverifiedRtspConstants]. It doubles as the version field of the SDP `o=` line (spec §6.4).
     */
    const val CLIENT_VERSION: String = "14"

    /** The successful RTSP status code. Every step of the handshake requires exactly this. */
    const val STATUS_OK: Int = 200

    /** A response larger than this is treated as malformed rather than buffered indefinitely. */
    const val MAX_RESPONSE_BYTES: Int = 256 * 1024

    /** Read chunk used while framing a response. Responses are small; this only bounds syscalls. */
    const val READ_CHUNK_BYTES: Int = 8 * 1024

    // ---- Methods (spec §6.3) -----------------------------------------------------------------

    const val METHOD_OPTIONS: String = "OPTIONS"
    const val METHOD_DESCRIBE: String = "DESCRIBE"
    const val METHOD_SETUP: String = "SETUP"
    const val METHOD_ANNOUNCE: String = "ANNOUNCE"
    const val METHOD_PLAY: String = "PLAY"

    // ---- Headers (spec §6.2, §6.3) -----------------------------------------------------------

    const val HEADER_CSEQ: String = "CSeq"
    const val HEADER_CLIENT_VERSION: String = "X-GS-ClientVersion"
    const val HEADER_ACCEPT: String = "Accept"
    const val HEADER_IF_MODIFIED_SINCE: String = "If-Modified-Since"
    const val HEADER_TRANSPORT: String = "Transport"
    const val HEADER_SESSION: String = "Session"
    const val HEADER_CONTENT_TYPE: String = "Content-type"
    const val HEADER_CONTENT_LENGTH: String = "Content-length"

    /** Sunshine: the 16-character payload to echo in UDP keep-alive pings (spec §6.3, §7.5). */
    const val HEADER_SS_PING_PAYLOAD: String = "X-SS-Ping-Payload"

    /** Sunshine: the unsigned 32-bit ENet connect data (spec §6.3, §9.1). */
    const val HEADER_SS_CONNECT_DATA: String = "X-SS-Connect-Data"

    /** `Accept` / `Content-type` value for every SDP the handshake carries. */
    const val MIME_SDP: String = "application/sdp"

    /**
     * Sent verbatim on DESCRIBE and every SETUP (spec §6.3).
     *
     * Some GFE builds require the header to be *present*; the value is never inspected, which is
     * why it is a fixed epoch string rather than a formatted date.
     */
    const val IF_MODIFIED_SINCE_VALUE: String = "Thu, 01 Jan 1970 00:00:00 GMT"

    /**
     * The literal `Transport` line every SETUP carries (spec §6.3).
     *
     * The port range in it is decorative — we bind ephemeral local UDP sockets and never these
     * ports. It is sent unchanged because hosts pattern-match on it.
     */
    const val TRANSPORT_REQUEST_VALUE: String = "unicast;X-GS-ClientPort=50000-50001"

    /** Token introducing the host's chosen port inside a SETUP response `Transport` header. */
    const val TRANSPORT_SERVER_PORT_TOKEN: String = "server_port="

    /**
     * The 16-character `X-SS-Ping-Payload` length (spec §6.3, §7.5).
     *
     * A payload of any other length means we have misread the header, so it is validated rather
     * than trusted — the keep-alive is what opens the host's NAT pinhole, and a wrong one produces
     * a session that negotiates perfectly and then delivers no video at all.
     */
    const val SS_PING_PAYLOAD_CHARS: Int = 16

    // ---- Stream id targets (spec §6.3) --------------------------------------------------------

    const val STREAM_ID_AUDIO_MODERN: String = "streamid=audio/0/0"
    const val STREAM_ID_VIDEO_MODERN: String = "streamid=video/0/0"
    const val STREAM_ID_AUDIO_LEGACY: String = "streamid=audio"
    const val STREAM_ID_VIDEO_LEGACY: String = "streamid=video"

    /** Control stream id for `appversion >= 7.1.431`. */
    const val STREAM_ID_CONTROL_MODERN: String = "streamid=control/13/0"

    /** Control stream id for Gen 5 hosts below `7.1.431`. */
    const val STREAM_ID_CONTROL_LEGACY: String = "streamid=control/1/0"

    /** The `appversion` at and above which the modern control stream id and ANNOUNCE target apply. */
    const val CONTROL_STREAM_ID_MIN_MAJOR: Int = 7
    const val CONTROL_STREAM_ID_MIN_MINOR: Int = 1
    const val CONTROL_STREAM_ID_MIN_PATCH: Int = 431

    /** Generations at and above which a control SETUP is performed at all (spec §6.3). */
    const val CONTROL_SETUP_MIN_GENERATION: Int = 5

    /** Generation at and above which the Gen-7 attribute spellings and extras apply (spec §6.4). */
    const val MODERN_ATTRIBUTE_MIN_GENERATION: Int = 7

    // ---- Default media ports (spec §0.4) ------------------------------------------------------

    /** Fallback when a SETUP response carries no usable `server_port=`. */
    const val DEFAULT_VIDEO_PORT: Int = 47998
    const val DEFAULT_CONTROL_PORT: Int = 47999
    const val DEFAULT_AUDIO_PORT: Int = 48000

    // ---- Timeouts ------------------------------------------------------------------------------
    //
    // Every step names its own constant rather than sharing one. The pairing engine learned this
    // the hard way (see ProtocolConstants.PAIRING_PHASE_TIMEOUT_MS): the steps are not equivalent,
    // and a single shared number hides which one is actually the problem when a host stalls.

    /** TCP connect to port 48010 (spec §6.1). Reaching the host must be quick even if it is busy. */
    const val CONNECT_TIMEOUT_MS: Int = 10_000

    /** OPTIONS is a pure liveness probe — a host that cannot answer it in this long is not there. */
    const val OPTIONS_TIMEOUT_MS: Int = 10_000

    /** DESCRIBE makes the host assemble its capability SDP. Cheap, but not free. */
    const val DESCRIBE_TIMEOUT_MS: Int = 10_000

    /** Each SETUP binds a UDP socket on the host side. */
    const val SETUP_TIMEOUT_MS: Int = 10_000

    /**
     * ANNOUNCE is the expensive one: the host validates our whole configuration and initialises
     * its encoder against it, which on a cold GPU is measurably slower than any other step.
     */
    const val ANNOUNCE_TIMEOUT_MS: Int = 20_000

    /** PLAY only flips the host into streaming; the encoder is already up by then. */
    const val PLAY_TIMEOUT_MS: Int = 10_000

    /**
     * One hard deadline for the whole handshake, checked between steps.
     *
     * Per-step budgets are not enough — eight steps that are each "reasonable" still add up to
     * minutes in front of a screen that says nothing. This is the number that decides how long the
     * user waits before being told the session did not come up, so it is the number to change.
     */
    const val SESSION_BUDGET_MS: Long = 60_000L

    // ---- SDP framing (spec §6.4) --------------------------------------------------------------

    /** `v=` — SDP protocol version. */
    const val SDP_VERSION_LINE: String = "v=0"

    /** Origin username in the `o=` line. */
    const val SDP_ORIGIN_USER: String = "android"

    /** Origin session id in the `o=` line. */
    const val SDP_ORIGIN_SESSION_ID: String = "0"

    /** Network type in the `o=` line. */
    const val SDP_NETWORK_TYPE: String = "IN"

    /** Address types in the `o=` line. */
    const val SDP_ADDRESS_TYPE_IPV4: String = "IP4"
    const val SDP_ADDRESS_TYPE_IPV6: String = "IP6"

    /** `s=` — session name, sent verbatim by every known client. */
    const val SDP_SESSION_NAME: String = "NVIDIA Streaming Client"

    // ---- Packet size (spec §5, §6.4) ----------------------------------------------------------

    /** Video RTP payload size on a LAN. */
    const val PACKET_SIZE_LAN: Int = 1392

    /** Video RTP payload size over a WAN, where the path MTU is not ours to assume. */
    const val PACKET_SIZE_WAN: Int = 1024

    /**
     * Bytes given back to the encryption header when video payload encryption is on (spec §6.4).
     *
     * 32 is both the `ENC_VIDEO_HEADER` size of §7.6 and a multiple of 16, so subtracting it keeps
     * the packet size a multiple of 16 as the spec requires.
     */
    const val PACKET_SIZE_ENCRYPTION_REDUCTION: Int = 32

    /** Video packet size must be a multiple of this when video encryption is enabled (spec §5). */
    const val PACKET_SIZE_ENCRYPTION_ALIGNMENT: Int = 16

    // ---- Fixed SDP attribute values (spec §6.4) -----------------------------------------------

    const val RATE_CONTROL_MODE: Int = 4
    const val VIDEO_TIMEOUT_LENGTH_MS: Int = 7_000
    const val FRAMES_WITH_INVALID_REF_THRESHOLD: Int = 0

    /** One slice: more only helps multi-threaded software decode, and we use `MediaCodec`. */
    const val VIDEO_ENCODER_SLICES_PER_FRAME: Int = 1

    /** Gen < 7 spelling of the bitrate attributes uses these fixed selector values. */
    const val LEGACY_AVERAGE_BITRATE_SELECTOR: Int = 4
    const val LEGACY_PEAK_BITRATE_SELECTOR: Int = 4

    const val FEC_ENABLE: Int = 1
    const val FEC_REPAIR_PERCENT_LAN: Int = 20
    const val FEC_REPAIR_PERCENT_WAN: Int = 5
    const val FEC_MIN_REQUIRED_PACKETS: Int = 2
    const val BLL_FEC_ENABLE: Int = 0
    const val VIDEO_QUALITY_SCORE_UPDATE_TIME: Int = 5_000
    const val QOS_TRAFFIC_TYPE_VIDEO_LAN: Int = 5
    const val QOS_TRAFFIC_TYPE_VIDEO_WAN: Int = 0
    const val QOS_TRAFFIC_TYPE_AUDIO_LAN: Int = 4
    const val QOS_TRAFFIC_TYPE_AUDIO_WAN: Int = 0

    /**
     * Dynamic resolution change, off for v1 (spec §6.4).
     *
     * Accepting DRC would mean reconfiguring `MediaCodec` mid-stream, which the video layer does
     * not do. Sending `1` here and then ignoring the resolution change produces a picture that is
     * silently the wrong size.
     */
    const val DRC_ENABLE: Int = 0

    /** Only meaningful when [DRC_ENABLE] is `1`; sent regardless, as the reference clients do. */
    const val DRC_TABLE_TYPE: Int = 2

    const val ENABLE_RECOVERY_MODE: Int = 0

    /** `x-nv-general.useReliableUdp` — a substream bitmask on Gen 7+, a plain flag below it. */
    const val USE_RELIABLE_UDP_GEN7: Int = 13
    const val USE_RELIABLE_UDP_LEGACY: Int = 1

    /** Input travels on the control channel rather than a socket of its own. Required by §9/§10. */
    const val USE_CONTROL_CHANNEL: Int = 1

    /**
     * Reference-frame invalidation is not implemented, so `0` (spec §6.4).
     *
     * Sending `1` would let the host encode frames that depend on references we may have dropped
     * and then expect us to ask for them back, which we have no code to do.
     */
    const val MAX_NUM_REFERENCE_FRAMES: Int = 0

    /** `x-nv-video[0].encoderFeatureSetting` — `0` is "no RFI", which follows from the above. */
    const val ENCODER_FEATURE_SETTING: Int = 0

    /** Multiplier turning a refresh rate in Hz into `clientRefreshRateX100`. */
    const val REFRESH_RATE_SCALE: Int = 100

    /** `x-nv-aqos.packetDuration`, in milliseconds (Gen 7+ only; legacy is always the default). */
    const val AUDIO_PACKET_DURATION_MS: Int = 5
    const val AUDIO_PACKET_DURATION_SLOW_MS: Int = 10

    /**
     * `x-ml-general.featureFlags` — `0x1` per-frame FEC status, `0x2` session-id v1 (spec §6.4).
     *
     * Rendered in **decimal**, like every other integer attribute in the SDP. The spec writes the
     * value as `0x3`; nothing in it suggests hex on the wire, and the one attribute it explicitly
     * calls out as decimal (`x-nv-audio.surround.channelMask`) is a bitmask too.
     */
    const val ML_FEATURE_FLAGS: Int = 3

    /** `x-ss-video[0].chromaSamplingType` — `1` for YUV 4:4:4, `0` for 4:2:0. */
    const val CHROMA_SAMPLING_444: Int = 1
    const val CHROMA_SAMPLING_420: Int = 0

    // ---- DESCRIBE response attributes (spec §6.3, §8.3) ---------------------------------------

    /** The literal prefix carrying the host's Opus multistream configuration. */
    const val SURROUND_PARAMS_PREFIX: String = "surround-params="

    /** The `fmtp` payload type the surround parameters hang off — Opus audio (spec §8.1). */
    const val OPUS_PAYLOAD_TYPE: Int = 97

    /** Present for H.264 in some DESCRIBE bodies. Logged, never used: SPS/PPS arrive in-band. */
    const val SPROP_PARAMETER_SETS: String = "sprop-parameter-sets"

    /** Opus sample rate; always this, never negotiated (spec §8.3). */
    const val OPUS_SAMPLE_RATE_HZ: Int = 48_000

    /** Stereo needs no negotiation at all (spec §8.3). */
    const val STEREO_STREAMS: Int = 1
    const val STEREO_COUPLED_STREAMS: Int = 1
}

/**
 * RTSP constants whose values `docs/01-PROTOCOL.md` explicitly marks **UNVERIFIED**.
 *
 * Each carries the spec section that flags it, the item number from the consolidated list in §13,
 * and the consequence of the guess being wrong. Every code path that depends on one of these logs
 * once per process via [com.voidlink.android.protocol.ProtocolLog.unverified], so a single run
 * against real hardware turns §13 into a checklist that ticks itself off.
 */
object UnverifiedRtspConstants {

    /**
     * The timing line of the ANNOUNCE SDP tail.
     *
     * UNVERIFIED(spec 01 §6.4, consolidated item 13): the precise `m=`/`t=` lines. Existing clients
     * emit a minimal tail referencing the video port and hosts do not appear to parse it strictly.
     * Risk if wrong: ANNOUNCE is rejected — which shows up on the very first real host, with the
     * host's own status text to go on.
     */
    const val SDP_TAIL_TIMING_LINE: String = "t=0 0"

    /**
     * Prefix of the tail's media line; the negotiated video port and [SDP_TAIL_MEDIA_SUFFIX]
     * complete it.
     *
     * UNVERIFIED(spec 01 §6.4, consolidated item 13): as above. The trailing double space is
     * reproduced deliberately — the spec writes `m=video <videoPort>  \r\n` and a host that does
     * pattern-match the line would not forgive its absence.
     */
    const val SDP_TAIL_MEDIA_PREFIX: String = "m=video "
    const val SDP_TAIL_MEDIA_SUFFIX: String = "  "

    /**
     * Whether a host advertising `rtspru://` still keeps its TCP RTSP listener open.
     *
     * UNVERIFIED(spec 01 §6.1, consolidated item 14): v1 implements TCP RTSP only and connects over
     * TCP to the advertised port regardless of scheme.
     * Risk if wrong: we cannot connect at all to some Sunshine builds, and RTSP-over-ENet becomes a
     * required follow-up. This is the first thing to suspect when TCP connect fails on Sunshine.
     */
    const val RTSPRU_KEEPS_TCP_LISTENER: Boolean = true

    /**
     * Bit values of `x-ss-general.encryptionEnabled` (spec §6.5).
     *
     * UNVERIFIED(spec 01 §6.5, consolidated item 5): inferred from the ordering of the feature
     * flags and the way they are combined. Where the host advertises its *supported* set is
     * likewise unknown — `/serverinfo` or DESCRIBE, the spec cannot say which.
     * Risk if wrong: mitigated to nothing in v1, because [ENCRYPTION_FLAGS_DEFAULT] is `0`.
     */
    const val SS_ENC_CONTROL_V2: Int = 0x01
    const val SS_ENC_VIDEO: Int = 0x02
    const val SS_ENC_AUDIO: Int = 0x04

    /**
     * The encryption mask v1 actually sends: none.
     *
     * Spec §6.5's explicit v1 decision. Plain control-stream framing works on all hosts, and
     * enabling video encryption would additionally require the 32-byte `ENC_VIDEO_HEADER` handling
     * of §7.6 that the video layer does not have. Changing this constant is not enough on its own.
     */
    const val ENCRYPTION_FLAGS_DEFAULT: Int = 0

    /**
     * Whether to ask for high-quality surround (`x-nv-audio.surround.AudioQuality=1`).
     *
     * UNVERIFIED(spec 01 §8.3, consolidated item 12): the high-quality variant uses a *different*
     * `surround-params` SDP key, and the spec cannot say which. Requesting it and then parsing the
     * normal key would apply the channel-order fix-up to a mapping that must not be fixed up.
     * Spec's v1 decision: never request it.
     * Risk if wrong: mitigated to nothing while this stays `false`.
     */
    const val REQUEST_HIGH_QUALITY_SURROUND: Boolean = false
}
