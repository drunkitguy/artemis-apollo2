package com.voidlink.android.protocol

/**
 * Every constant the GameStream/Sunshine protocol requires, transcribed from
 * `docs/01-PROTOCOL.md` and cross-referenced by section.
 *
 * Nothing else in the protocol layer may define a protocol constant — when a value turns out to be
 * wrong on real hardware, this is the single file to edit.
 *
 * Values whose correctness is not established by the spec live in [UnverifiedProtocolConstants] so
 * that the guessed surface of the implementation is enumerable at a glance.
 */
object ProtocolConstants {

    // ---- Ports (spec §0.4) -------------------------------------------------------------------

    /** NVHTTP plaintext port. Fixed, or supplied by the user for a manual host. */
    const val DEFAULT_HTTP_PORT: Int = 47989

    /** NVHTTP TLS port. Overridden by `<HttpsPort>` from `/serverinfo` when present. */
    const val DEFAULT_HTTPS_PORT: Int = 47984

    /** RTSP port; parsed from `sessionUrl0` when the host supplies one. Not used until phase 5. */
    const val DEFAULT_RTSP_PORT: Int = 48010

    // ---- Discovery (spec §1.1) ---------------------------------------------------------------

    /**
     * The mDNS service type hosts advertise.
     *
     * `NsdManager` wants the type without the trailing domain; it appends `.local.` itself.
     * Confirmed against Sunshine's `SERVICE_TYPE` (spec §14).
     *
     * Note the spec's prose mentions the fully-qualified form `_nvstream._tcp.` while its own
     * `discoverServices` example passes the undotted form, which is what is used here. Android
     * normalises both, but if discovery ever finds nothing on a network where a host is definitely
     * advertising, adding the trailing dot is the first thing to try.
     */
    const val MDNS_SERVICE_TYPE: String = "_nvstream._tcp"

    // ---- NVHTTP (spec §3.1) ------------------------------------------------------------------

    /** Query parameter carrying our persistent client id, sent on every request. */
    const val PARAM_UNIQUE_ID: String = "uniqueid"

    /** Query parameter carrying a fresh per-request nonce UUID. */
    const val PARAM_UUID: String = "uuid"

    /** Endpoint paths. */
    const val PATH_SERVER_INFO: String = "serverinfo"
    const val PATH_APP_LIST: String = "applist"
    const val PATH_APP_ASSET: String = "appasset"
    const val PATH_LAUNCH: String = "launch"
    const val PATH_RESUME: String = "resume"
    const val PATH_CANCEL: String = "cancel"
    const val PATH_PAIR: String = "pair"
    const val PATH_UNPAIR: String = "unpair"

    /** `AssetType=2` selects box art; `AssetIdx=0` selects the primary image (spec §3.5). */
    const val ASSET_TYPE_BOX_ART: Int = 2
    const val ASSET_INDEX_PRIMARY: Int = 0

    /** The XML envelope attribute that must read `200` for a response to be usable (spec §3.2). */
    const val ATTR_STATUS_CODE: String = "status_code"
    const val ATTR_STATUS_MESSAGE: String = "status_message"
    const val STATUS_CODE_OK: Int = 200

    /** Root element name of every NVHTTP XML response. */
    const val ELEMENT_ROOT: String = "root"

    // ---- Timeouts (spec §1.3, architecture §4.2) ---------------------------------------------

    /** Connect/read timeout for a host we believe is offline — a dead host must not stall the list. */
    const val PROBE_TIMEOUT_OFFLINE_MS: Int = 1_000

    /** Connect/read timeout for a host we believe is online. */
    const val PROBE_TIMEOUT_ONLINE_MS: Int = 5_000

    /** A host seen within this window is "believed online" for timeout selection. */
    const val BELIEVED_ONLINE_WINDOW_MS: Long = 60_000L

    /** Timeout for ordinary HTTPS calls such as `/applist`, `/appasset` and `/cancel`. */
    const val DEFAULT_REQUEST_TIMEOUT_MS: Int = 10_000

    /** `/launch` and `/resume` may legitimately take tens of seconds (spec §3.6). */
    const val LAUNCH_TIMEOUT_MS: Int = 60_000

    /** Pairing phases 2–5 (architecture §4.2). */
    const val PAIRING_PHASE_TIMEOUT_MS: Int = 10_000

    /**
     * Pairing phase 1 blocks until the user types the PIN on the host (spec §4.3).
     *
     * `0` means "no read timeout" for `HttpURLConnection`. Cancellation is what ends this call,
     * not a timer.
     */
    const val PAIRING_PHASE1_READ_TIMEOUT_MS: Int = 0

    /** Connect timeout even for the blocking phase-1 call — reaching the host must still be quick. */
    const val PAIRING_CONNECT_TIMEOUT_MS: Int = 10_000

    // ---- Pairing (spec §4) -------------------------------------------------------------------

    /** Salt length in bytes (spec §4.1). */
    const val PAIRING_SALT_BYTES: Int = 16

    /** Challenge / secret length in bytes (spec §4.4, §4.5). */
    const val PAIRING_CHALLENGE_BYTES: Int = 16

    /** AES key length for the pairing cipher (spec §4.1 — first 16 bytes of the digest). */
    const val PAIRING_AES_KEY_BYTES: Int = 16

    /** The PIN shown to the user is exactly four decimal digits, leading zeros preserved. */
    const val PAIRING_PIN_DIGITS: Int = 4

    /** Major `appversion` at and above which pairing uses SHA-256 instead of SHA-1 (spec §4.0). */
    const val PAIRING_SHA256_MIN_GENERATION: Int = 7

    /** Digest lengths that follow from the hash selection (spec §4.0). */
    const val SHA1_DIGEST_BYTES: Int = 20
    const val SHA256_DIGEST_BYTES: Int = 32

    /** `<paired>` must equal this at every phase (spec §4.0). */
    const val PAIRED_OK: String = "1"

    // ---- Server info (spec §3.3) -------------------------------------------------------------

    /** `<state>` containing this marker identifies a genuine NVIDIA GFE host (spec §0.3). */
    const val STATE_MARKER_NVIDIA: String = "MJOLNIR"

    /** `<state>` starting with this prefix identifies Sunshine, Apollo and their forks (spec §0.3). */
    const val STATE_PREFIX_SUNSHINE: String = "SUNSHINE"

    /** `<state>` suffix meaning an app is currently streaming. */
    const val STATE_SUFFIX_BUSY: String = "_SERVER_BUSY"

    /** Sunshine returns this placeholder MAC over plaintext HTTP; treat it as unknown (spec §1.4). */
    const val MAC_UNKNOWN: String = "00:00:00:00:00:00"

    /** Sunshine reports this `LocalIP` for IPv6 requests; ignore it (spec §3.3). */
    const val LOCAL_IP_IGNORED: String = "127.0.0.1"

    /** `<currentgame>` value meaning the host is idle. */
    const val CURRENT_GAME_IDLE: String = "0"

    /** Title that must sort first in the app list when the host offers it (spec §3.4). */
    const val APP_TITLE_DESKTOP: String = "Desktop"

    // ---- Identity (spec §2) ------------------------------------------------------------------

    /** Subject and issuer of our self-signed client certificate. Any CN works; keep it stable. */
    const val CLIENT_CERT_SUBJECT: String = "CN=NVIDIA GameStream Client"

    /** Client key algorithm and size. GFE requires RSA; Sunshine is best-tested with it. */
    const val CLIENT_KEY_ALGORITHM: String = "RSA"
    const val CLIENT_KEY_BITS: Int = 2048

    /** Signature algorithm for the self-signed certificate and for the phase-4 signature. */
    const val CLIENT_SIGNATURE_ALGORITHM: String = "SHA256withRSA"

    /** Signature algorithm used to verify a server whose key is elliptic curve (spec §4.5). */
    const val SERVER_EC_SIGNATURE_ALGORITHM: String = "SHA256withECDSA"

    /** Certificate validity, generous because hosts pin it (spec §2). */
    const val CERT_BACKDATE_DAYS: Long = 1L
    const val CERT_VALIDITY_YEARS: Long = 20L

    // ---- Wake-on-LAN (spec §1.4) -------------------------------------------------------------

    /** A magic packet is 6 × 0xFF followed by the 6-byte MAC repeated 16 times. */
    const val WOL_MAC_REPEAT_COUNT: Int = 16
    const val WOL_SYNC_STREAM_BYTES: Int = 6
    const val WOL_MAC_BYTES: Int = 6
    const val WOL_PACKET_BYTES: Int =
        WOL_SYNC_STREAM_BYTES + (WOL_MAC_BYTES * WOL_MAC_REPEAT_COUNT)

    /** The all-subnets broadcast address, always tried in addition to per-interface broadcasts. */
    const val WOL_GLOBAL_BROADCAST: String = "255.255.255.255"
}

/**
 * Constants whose values the spec explicitly marks **UNVERIFIED**.
 *
 * Each carries the spec section that flags it and the consequence of the guess being wrong. They
 * are collected here rather than scattered through the code so that a debugging session against a
 * real host has one file to experiment in, and so the guessed surface stays countable.
 *
 * Every code path that depends on one of these logs once per process via
 * [ProtocolLog.unverified].
 */
object UnverifiedProtocolConstants {

    /**
     * `devicename=roth` — sent verbatim by every known client (`roth` was the Shield tablet
     * codename).
     *
     * UNVERIFIED(spec 01 §4.0, consolidated item 19): whether any host validates it.
     * Risk if wrong: none expected.
     */
    const val PAIRING_DEVICE_NAME: String = "roth"

    /** `updateState=1` accompanies `devicename` on every `/pair` call (spec §4.0). */
    const val PAIRING_UPDATE_STATE: String = "1"

    /**
     * Length of the persistent client id, in hex characters.
     *
     * UNVERIFIED(spec 01 §2, consolidated item 21): whether any host validates the format. Both
     * GFE and Sunshine appear to treat it as opaque; Sunshine rejects only a *missing* `uniqueid`.
     * Risk if wrong: pairing rejected outright, which would show up on the first attempt.
     */
    const val UNIQUE_ID_HEX_CHARS: Int = 16

    /**
     * TLS protocol versions offered to a host.
     *
     * UNVERIFIED(spec 01 §3.1): whether any still-in-use host requires < TLSv1.2. We enable
     * TLSv1.2 explicitly and let the platform add newer versions.
     * Risk if wrong: `no cipher suites in common` on an ancient GFE; surfaced verbatim.
     */
    val TLS_PROTOCOLS: List<String> = listOf("TLSv1.2", "TLSv1.3")

    /**
     * UDP ports a Wake-on-LAN magic packet is sent to.
     *
     * UNVERIFIED(spec 01 §1.4, consolidated item 18-adjacent): whether hosts respond on ports
     * other than 9 and 7. Sending to both is common practice.
     * Risk if wrong: the machine does not wake; the user power-buttons it.
     */
    val WOL_PORTS: List<Int> = listOf(9, 7)

    /**
     * Bit assignments of `<ServerCodecModeSupport>`.
     *
     * UNVERIFIED(spec 01 §3.3.1, consolidated item 10): inferred from the client-side
     * `VIDEO_FORMAT_*` masks, not formally documented. The spec's instruction is to treat the
     * field as a **hint** and never to hard-gate the UI on it — `MaxLumaPixelsHEVC == 0` is the
     * only reliable capability check.
     * Risk if wrong: a codec is offered that the host cannot do; fails later with a clear error.
     */
    const val CODEC_FLAG_H264: Int = 0x0001
    const val CODEC_FLAG_HEVC: Int = 0x0100
    const val CODEC_FLAG_HEVC_MAIN10: Int = 0x0200
    const val CODEC_FLAG_AV1_MAIN8: Int = 0x1000
    const val CODEC_FLAG_AV1_MAIN10: Int = 0x2000

    /**
     * Whether to trust `<SupportedDisplayMode>` as a restriction.
     *
     * UNVERIFIED(spec 01 §3.3): how complete the list is on Sunshine. The spec mandates using it
     * to *suggest* resolutions and never to restrict them, which is what `false` here means.
     */
    const val DISPLAY_MODES_ARE_AUTHORITATIVE: Boolean = false

    /**
     * On NVIDIA hosts, ask for `fps=0` in the `/launch` `mode` string when the desired rate
     * exceeds 60 and let RTSP negotiate the real rate.
     *
     * UNVERIFIED(spec 01 §3.6, consolidated item 23): which GFE builds require this. Applied only
     * when the host is GFE and the requested rate is above [NVIDIA_FPS_WORKAROUND_THRESHOLD].
     * Risk if wrong: only affects >60 fps on NVIDIA hosts, which are rare.
     */
    const val NVIDIA_FPS_WORKAROUND_ENABLED: Boolean = true
    const val NVIDIA_FPS_WORKAROUND_THRESHOLD: Int = 60

    /**
     * mDNS TXT records are deliberately ignored.
     *
     * UNVERIFIED(spec 01 §1.1, consolidated item 18): which TXT keys are guaranteed. The spec's
     * instruction is to treat discovery as "here is an IP and port", nothing more — so this flag
     * exists to document the decision, not to enable anything.
     */
    const val TRUST_MDNS_TXT_RECORDS: Boolean = false
}
