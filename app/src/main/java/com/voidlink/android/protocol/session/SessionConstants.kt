package com.voidlink.android.protocol.session

import com.voidlink.android.protocol.ProtocolLog

/**
 * Every constant the session layer needs, from `docs/01-PROTOCOL.md` §7.5 and §11.1 and
 * `docs/02-ARCHITECTURE.md` §3 and §4.2.
 *
 * Same role, same reason and same eventual fate as
 * [com.voidlink.android.protocol.rtp.RtpVideoConstants] and
 * [com.voidlink.android.protocol.control.ControlConstants]: one file per subsystem while the
 * packages are being written, folded into `ProtocolConstants` when the tree settles.
 */
object SessionConstants {

    /** Subsystem tag for the session layer, matching architecture §9's tag table. */
    const val TAG: String = "VL.Session"

    // ---- Video keep-alive (spec §7.5) ----------------------------------------------------------

    /**
     * Keep-alive cadence on the video and audio sockets (spec §7.5).
     *
     * The host will not send video until it has seen a packet from our source port, so this is not
     * a nicety: it is the packet that punches the NAT/firewall pinhole. Sent from **the same socket
     * we receive on**, starting immediately after PLAY.
     */
    const val VIDEO_PING_INTERVAL_MS: Long = 500L

    /** The legacy 4-byte keep-alive: the ASCII bytes `PING` (spec §7.5). */
    val LEGACY_PING_PAYLOAD: ByteArray get() = byteArrayOf(0x50, 0x49, 0x4E, 0x47)

    /** Sunshine's `X-SS-Ping-Payload` is 16 characters (spec §7.5). */
    const val SS_PING_PAYLOAD_BYTES: Int = 16

    /** The big-endian `uint32` sequence number appended to a Sunshine ping (spec §7.5). */
    const val SS_PING_SEQUENCE_BYTES: Int = 4

    /** Sunshine's keep-alive is the 16-byte payload plus a 4-byte sequence number (spec §7.5). */
    const val SS_PING_TOTAL_BYTES: Int = SS_PING_PAYLOAD_BYTES + SS_PING_SEQUENCE_BYTES

    /** Spec §7.5: the sequence number starts at 1 and increments per ping. */
    const val SS_PING_FIRST_SEQUENCE: Int = 1

    // ---- Video socket --------------------------------------------------------------------------

    /**
     * Receive buffer requested on the video socket.
     *
     * A 4K60 stream at 100 Mbps delivers roughly 9,000 packets per second; the default socket
     * buffer holds a few dozen. Every packet the kernel drops here is a frame we must then request
     * an IDR for, which costs far more than the memory. The OS is free to grant less.
     */
    const val VIDEO_RECEIVE_BUFFER_BYTES: Int = 1 shl 21

    /**
     * Largest datagram the video socket will read.
     *
     * The negotiated `packetSize` is 1392 on a LAN, plus RTP and NV headers and, on a Sunshine host
     * that enables it, a 32-byte encryption header. 2048 leaves room for all of it and for a host
     * with an unusual MTU, and anything larger is not ours.
     */
    const val MAX_VIDEO_DATAGRAM_BYTES: Int = 2048

    /** Thread names from architecture §3's table, so a thread dump reads like the design document. */
    const val THREAD_VIDEO_RX: String = "video-rx"
    const val THREAD_VIDEO_PING: String = "video-ping"

    /** How long teardown waits for a receive thread to notice its socket closed. */
    const val THREAD_JOIN_TIMEOUT_MS: Long = 1_000L

    // ---- Start-up timeouts (architecture §4.2) --------------------------------------------------

    /**
     * `ML_ERROR_NO_VIDEO_TRAFFIC` — how long we wait for the *first packet* (spec §11.1).
     *
     * Architecture §4.2's table: "First video traffic — 10 s ⇒ `NO_VIDEO_TRAFFIC`".
     */
    const val FIRST_TRAFFIC_TIMEOUT_MS: Long = 10_000L

    /**
     * `ML_ERROR_NO_VIDEO_FRAME` — how long we then wait for the first *complete frame* (spec §11.1).
     *
     * Architecture §4.2: "First complete frame — 10 s after first traffic ⇒ `NO_VIDEO_FRAME`". The
     * two timers are separate because the two failures have different causes and different fixes.
     */
    const val FIRST_FRAME_TIMEOUT_MS: Long = 10_000L

    /**
     * How often the first-frame watchdog looks at the frame queue.
     *
     * Polled rather than awaited: cancelling a suspended `receive()` on a rendezvous-capable channel
     * can consume the very element that would have satisfied it, and losing the first keyframe is
     * the one loss this watchdog exists to prevent. 20 ms of extra latency, once per session.
     */
    const val FRAME_POLL_INTERVAL_MS: Long = 20L

    /** ENet connect deadline (spec §9.1's `CONTROL_STREAM_TIMEOUT_SEC`). */
    const val CONTROL_CONNECT_TIMEOUT_MS: Long = 10_000L

    /**
     * Announces the session layer's one structural assumption, once per process.
     *
     * The audio stream is negotiated (RTSP SETUP audio happens, and the host will send Opus to a
     * port nobody is listening on) but not received: `protocol/audio/` is a separate workstream. A
     * host that requires the audio keep-alive to consider the session healthy would show this up as
     * a session that starts and then stops, which is worth being able to look up.
     */
    fun announce() {
        ProtocolLog.unverified(
            TAG,
            "session-no-audio-receiver",
            "the audio stream is negotiated but not received in this build; only the video socket " +
                "is opened and pinged (spec 01 §7.5, §8.1)",
        )
    }
}
