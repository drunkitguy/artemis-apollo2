package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.ProtocolLog

/**
 * Every constant the GameStream control stream needs, transcribed from `docs/01-PROTOCOL.md` §9
 * and cross-referenced by section.
 *
 * Mirrors the role `com.voidlink.android.protocol.ProtocolConstants` plays for the rest of the
 * protocol layer, and the role [com.voidlink.android.protocol.rtp.RtpVideoConstants] plays for the
 * video path: one file per subsystem while the packages are being written, folded into
 * `ProtocolConstants` once the tree settles. Nothing else under `protocol/control/` may define a
 * protocol constant.
 *
 * Values the spec explicitly marks **UNVERIFIED** live in [UnverifiedControlConstants], so the
 * guessed surface of the control stream stays countable at a glance.
 */
object ControlConstants {

    /** Subsystem tag for the control stream, matching architecture §9's tag table. */
    const val TAG: String = "VL.Control"

    // ---- Framing (spec §9.2) -------------------------------------------------------------------

    /** `NVCTL_ENET_PACKET_HEADER_V1`: `{ uint16 type }`, little-endian. */
    const val HEADER_SIZE_V1: Int = 2

    /** `NVCTL_ENET_PACKET_HEADER_V2`: `{ uint16 type; uint16 payloadLength }`, little-endian. */
    const val HEADER_SIZE_V2: Int = 4

    /** `encryptedHeaderType`, always `0x0001` little-endian (spec §9.2). */
    const val ENCRYPTED_HEADER_TYPE: Int = 0x0001

    /** `{ uint16 type; uint16 length; uint32 seq; uint8[16] tag }` — the ciphertext starts at 24. */
    const val ENCRYPTED_HEADER_SIZE: Int = 24

    /** Size of the AES-GCM authentication tag carried at offset 8 of an encrypted packet. */
    const val GCM_TAG_BYTES: Int = 16

    /** IV length for `SS_ENC_CONTROL_V2` (spec §9.2). */
    const val CONTROL_V2_IV_BYTES: Int = 12

    /** IV length for the older, non-v2 encrypted control path (spec §9.2). */
    const val LEGACY_IV_BYTES: Int = 16

    /** The AES-GCM transformation the encrypted control stream uses. */
    const val GCM_TRANSFORMATION: String = "AES/GCM/NoPadding"

    /** JCE key algorithm for the remote-input key that doubles as the control key. */
    const val GCM_KEY_ALGORITHM: String = "AES"

    /** Bit length of the GCM tag, for `GCMParameterSpec`. */
    const val GCM_TAG_BITS: Int = GCM_TAG_BYTES * 8

    /**
     * The two IV marker bytes `SS_ENC_CONTROL_V2` sets to keep client and host IV spaces disjoint.
     *
     * `iv[10] = 'C'` (client originated), `iv[11] = 'C'` (control stream). Spec §9.2 documents only
     * the four sequence bytes and says "remaining bytes zero"; these two are the reference client's
     * collision guard, and a host that computes the same IV we do will not decrypt a packet whose
     * IV is missing them. Recorded here rather than in [UnverifiedControlConstants] because the two
     * bytes are visible in `moonlight-common-c`'s `encryptControlMessage`, not inferred — but the
     * whole encrypted path is off in v1 (spec §6.5), so nothing depends on it yet.
     */
    const val IV_MARKER_CLIENT: Byte = 'C'.code.toByte()

    /** Offset of the "client originated" marker within the 12-byte control-v2 IV. */
    const val IV_MARKER_ORIGIN_OFFSET: Int = 10

    /** Offset of the "control stream" marker within the 12-byte control-v2 IV. */
    const val IV_MARKER_STREAM_OFFSET: Int = 11

    // ---- Well-known message types (spec §9.3) --------------------------------------------------

    /** Periodic ping, client→host, required on `appversion >= 7.1.415`. */
    const val TYPE_PERIODIC_PING: Int = 0x0200

    /** Sunshine per-frame FEC status report, client→host, all fields big-endian (spec §9.5). */
    const val TYPE_FRAME_FEC_STATUS: Int = 0x5502

    /** Long-term-reference frame ACK, client→host (spec §9.3). */
    const val TYPE_LTR_FRAME_ACK: Int = 0x0350

    // ---- Payload shapes (spec §9.4, §9.5) ------------------------------------------------------

    /** Start A payload for Gen 5+: `{0x00, 0x00}`. */
    val START_A_PAYLOAD_GEN5: ByteArray get() = byteArrayOf(0, 0)

    /** Start A payload for Gen 4: a single zero byte. */
    val START_A_PAYLOAD_GEN4: ByteArray get() = byteArrayOf(0)

    /** Start B payload for Gen 5+: a single zero byte. */
    val START_B_PAYLOAD_GEN5: ByteArray get() = byteArrayOf(0)

    /** Gen 3's Start B payload is the four little-endian ints `0, 0, 0, 0x0a` (spec §9.4). */
    val START_B_PAYLOAD_GEN3_INTS: IntArray get() = intArrayOf(0, 0, 0, 0x0a)

    /** Gen 3 and Gen 4 request an IDR with the two-byte payload `{0x00, 0x00}`. */
    val REQUEST_IDR_PAYLOAD_LEGACY: ByteArray get() = byteArrayOf(0, 0)

    /** The periodic ping payload is 8 bytes (spec §9.5). */
    const val PERIODIC_PING_PAYLOAD_SIZE: Int = 8

    /** The `uint16` that opens the periodic ping payload — "length of payload" (spec §9.5). */
    const val PERIODIC_PING_LENGTH_FIELD: Int = 4

    /** The loss-stats payload is 32 bytes on every generation (spec §9.5). */
    const val LOSS_STATS_PAYLOAD_SIZE: Int = 32

    /** `uint32 1000` — the third field of the loss-stats payload (spec §9.5). */
    const val LOSS_STATS_SCALE: Int = 1000

    /** `uint32 0x14` — the trailing field of the loss-stats payload (spec §9.5). */
    const val LOSS_STATS_TRAILER: Int = 0x14

    /**
     * The Sunshine per-frame FEC status payload, big-endian (spec §9.5).
     *
     * Twenty-one bytes: `uint32` + seven `uint16` + three `uint8`, with no padding because spec §0.2
     * makes every struct here `#pragma pack(1)`. Counted rather than assumed — an over-long payload
     * is two trailing zero bytes the host reads as the start of the next field.
     */
    const val FRAME_FEC_STATUS_PAYLOAD_SIZE: Int = 4 + 7 * 2 + 3

    /** The reference-frame invalidation payload is three little-endian `int64`s (spec §9.3). */
    const val INVALIDATE_REFERENCE_FRAMES_PAYLOAD_SIZE: Int = 24

    /**
     * How far back an IDR request reaches when it has to be expressed as a reference-frame
     * invalidation, on a host with no IDR message of its own.
     */
    const val INVALIDATE_LOOKBACK_FRAMES: Long = 0x20L

    /** The LTR-frame-ACK payload is one little-endian `uint32`. */
    const val LTR_FRAME_ACK_PAYLOAD_SIZE: Int = 4

    // ---- Host→client payloads (spec §9.6) ------------------------------------------------------

    /**
     * Smallest termination payload that carries an error code.
     *
     * Spec §9.6 writes the test as `payloadLength >= 6`, counting the two header bytes: a
     * termination message is usable when the *whole packet* is at least six bytes, i.e. the payload
     * holds at least the four-byte big-endian `HRESULT`. Stated here in payload terms so the check
     * cannot drift when the header version changes.
     */
    const val TERMINATION_ERROR_CODE_BYTES: Int = 4

    /** Rumble payloads carry `(uint16 controller, uint16 lowFreq, uint16 highFreq)` = 6 bytes. */
    const val RUMBLE_FIELD_BYTES: Int = 6

    /** Spec §9.6: the rumble payload "appears to carry 4 leading bytes" before those three fields. */
    const val RUMBLE_LEADING_BYTES: Int = 4

    // ---- Known termination codes (spec §9.6) ---------------------------------------------------

    /** `NVST_DISCONN_SERVER_VIDEO_ENCODER_CONVERT_INPUT_FRAME_FAILED`. */
    const val TERMINATION_FRAME_CONVERSION: Int = 0x800e9403.toInt()

    /** Commonly reported as a graceful termination — **UNVERIFIED** (spec §9.6). */
    const val TERMINATION_GRACEFUL: Int = 0x80030023.toInt()

    /** Commonly reported as protected content — **UNVERIFIED** (spec §9.6). */
    const val TERMINATION_PROTECTED_CONTENT: Int = 0x800e9302.toInt()

    // ---- Timing (spec §9.1) ---------------------------------------------------------------------

    /** `CONTROL_STREAM_TIMEOUT_SEC` — 10 s to complete the ENet handshake (spec §9.1). */
    const val CONNECT_TIMEOUT_MS: Long = 10_000L

    /** `CONTROL_STREAM_LINGER_TIMEOUT_SEC` — pump for the disconnect ACK for 2 s (spec §9.7). */
    const val LINGER_TIMEOUT_MS: Long = 2_000L

    /** `appversion >= 7.1.415` is what makes the periodic ping mandatory (spec §9.5). */
    const val PERIODIC_PING_MIN_MAJOR: Int = 7
    const val PERIODIC_PING_MIN_MINOR: Int = 1
    const val PERIODIC_PING_MIN_PATCH: Int = 415
}

/**
 * Control-stream decisions the spec explicitly marks **UNVERIFIED**, plus the places where
 * `docs/01-PROTOCOL.md` §9 and the reference client disagree.
 *
 * Collected in one object for the same reason as
 * [com.voidlink.android.protocol.UnverifiedProtocolConstants] and
 * [com.voidlink.android.protocol.enet.EnetUnverifiedConstants]: a debugging session against a real
 * host needs exactly one file to experiment in, and the guessed surface has to stay countable.
 * Every entry names the spec section that flags it and what goes wrong if the guess is wrong.
 */
object UnverifiedControlConstants {

    /**
     * Which header the **unencrypted** ENet control stream uses.
     *
     * UNVERIFIED(spec 01 §9.2) — and the single highest-risk choice in this package. Spec §9.2
     * heads its unencrypted layout "V2 header, Gen 5+" (`{type, payloadLength}`) and then adds, in
     * parentheses, that "Gen-5 V1 framing omits `payloadLength` and has only the 2-byte type". The
     * two statements cannot both describe the same packet.
     *
     * We send [ControlHeaderVersion.V1], because that is the framing the host actually parses: the
     * reference client writes a `NVCTL_ENET_PACKET_HEADER_V1` (type only) for every unencrypted
     * ENet message and reads inbound messages the same way — its termination handler reads the
     * `HRESULT` from offset 2, not offset 4. The V2 header exists, but as the *plaintext inside*
     * the encrypted envelope of spec §9.2, which is exactly where [ControlFraming] uses it.
     *
     * Risk if wrong: every control message is two bytes longer than the host expects, so Start A
     * arrives as `{len, 0, 0}`, IDR requests are ignored, and the session comes up and then stalls
     * — a silent failure, not a crash. Flipping this one value is the first thing to try.
     */
    val UNENCRYPTED_HEADER: ControlHeaderVersion = ControlHeaderVersion.V1

    /**
     * `PERIODIC_PING_INTERVAL_MS` (spec §9.5).
     *
     * UNVERIFIED(spec 01 §9.5): "Existing clients use a value on the order of **500 ms**. Implement
     * `500` as a named constant; if the host times us out, this is the knob."
     *
     * We use **100 ms**, which is the value the reference client compiles in. The spec's own
     * reasoning points the same way — the ping is sent reliably *because the RTT estimate is
     * derived from its ACK*, and a 500 ms sampling interval makes that estimate useless for the
     * link-quality readout. Pinging five times as often costs 8 payload bytes per message on a
     * link carrying tens of megabits of video.
     *
     * Risk if wrong: a host that dislikes the rate could throttle or drop us. If a session dies
     * after a few seconds with no termination message, raise this to 500 first.
     */
    const val PERIODIC_PING_INTERVAL_MS: Long = 100L

    /**
     * `LOSS_REPORT_INTERVAL_MS` — how often pre-7.1.415 hosts want loss statistics (spec §9.5).
     *
     * UNVERIFIED(spec 01 §9.5): "on the order of 50–100 ms". 50 ms matches the reference client,
     * and the value is also *transmitted inside the payload*, so host and client agree on it
     * whatever we pick.
     *
     * Risk if wrong: an old host's bitrate heuristics see a mis-scaled loss rate. Modern hosts take
     * the periodic-ping path instead and never see this message.
     */
    const val LOSS_REPORT_INTERVAL_MS: Long = 50L

    /**
     * Minimum spacing between IDR requests (spec §9.5).
     *
     * Spec §9.5: "**Rate-limit it**: at most one per ~100 ms, or a lossy link turns into an IDR
     * storm that makes things worse." The number itself is the spec's, the "~" is why it is here.
     *
     * Risk if wrong: too low and a loss burst floods the host with keyframe requests, each of which
     * costs a large intra frame and *causes* more loss; too high and recovery from a dropped frame
     * takes visibly longer.
     */
    const val IDR_REQUEST_MIN_INTERVAL_MS: Long = 100L

    /**
     * Whether we send a termination message before disconnecting the ENet peer.
     *
     * Spec §9.7 step 2 says to: "Send the termination/disconnect message on the urgent channel
     * (Gen 7 type `0x0100`)", and warns that getting the teardown order wrong "leaves the host
     * stuck with a live session". The reference client does **not** do this — it only performs a
     * graceful ENet disconnect and lets the host notice. `0x0100` is otherwise a host→client type.
     *
     * We follow the spec, because an unrecognised client→host type is ignored by every host we
     * know of, whereas a host left holding a session is a user-visible failure on the *next*
     * launch. Flip this to `false` if a host ever answers a termination message with anything.
     */
    const val SEND_CLIENT_TERMINATION: Boolean = true

    /**
     * Logs, once per process, that the control stream is running on assumed values.
     *
     * Called from [ControlStream.start], because every one of these takes effect the moment the
     * first control message is written.
     */
    fun announce() {
        ProtocolLog.unverified(
            ControlConstants.TAG,
            "control-unencrypted-header",
            "unencrypted control messages use the ${UNENCRYPTED_HEADER.label} header " +
                "(${UNENCRYPTED_HEADER.headerSize} bytes) per spec 01 §9.2's parenthetical and the " +
                "reference client; if the host ignores our Start A / IDR requests, switching to " +
                "${ControlHeaderVersion.V2.label} is the first thing to try",
        )
        ProtocolLog.unverified(
            ControlConstants.TAG,
            "control-ping-interval",
            "periodic ping every ${PERIODIC_PING_INTERVAL_MS}ms (spec 01 §9.5 suggests 500ms and " +
                "marks it UNVERIFIED; the reference client uses 100ms and derives its RTT estimate " +
                "from the ACK)",
        )
        ProtocolLog.unverified(
            ControlConstants.TAG,
            "control-idr-rate-limit",
            "IDR requests are rate-limited to one per ${IDR_REQUEST_MIN_INTERVAL_MS}ms (spec 01 §9.5)",
        )
    }
}
