package com.voidlink.android.protocol.audio

/**
 * Every constant the audio RTP receive path needs, transcribed from `docs/01-PROTOCOL.md` §8 and
 * cross-referenced by section.
 *
 * Same role, same reason and same eventual fate as
 * [com.voidlink.android.protocol.rtp.RtpVideoConstants] and
 * [com.voidlink.android.protocol.session.SessionConstants]: one file per subsystem while the
 * packages are being written, folded into `ProtocolConstants` when the tree settles. Nothing else
 * under `protocol/audio/` may define a protocol constant.
 *
 * Values the spec explicitly marks **UNVERIFIED** live in [UnverifiedRtpAudioConstants], so the
 * guessed surface of the audio path stays countable at a glance.
 */
object RtpAudioConstants {

    // ---- Logging -------------------------------------------------------------------------------

    /** Subsystem tag for the audio path, matching architecture §9's tag table. */
    const val LOG_TAG_AUDIO: String = "VL.Audio"

    // ---- RTP payload types (spec §8.1) ---------------------------------------------------------

    /**
     * `packetType` 97 — an Opus data packet.
     *
     * Unlike the video path, which deliberately does not filter on payload type, §8.1 *does*
     * enumerate the audio types, and filtering is load-bearing here: parity packets share the data
     * stream's sequence-number space (a host emits data 0–3, then parity numbered 4 and 5, then
     * data 4–7), so feeding a parity packet to the data sequence logic desynchronises it
     * immediately.
     */
    const val PAYLOAD_TYPE_OPUS: Int = 97

    /** `packetType` 127 — an FEC parity shard (spec §8.1). */
    const val PAYLOAD_TYPE_FEC: Int = 127

    // ---- FEC geometry (spec §8.4) --------------------------------------------------------------

    /** `RTPA_DATA_SHARDS` — audio FEC geometry is fixed, unlike video's. */
    const val FEC_DATA_SHARDS: Int = 4

    /** `RTPA_FEC_SHARDS`. */
    const val FEC_PARITY_SHARDS: Int = 2

    /** `RTPA_TOTAL_SHARDS`. */
    const val FEC_TOTAL_SHARDS: Int = FEC_DATA_SHARDS + FEC_PARITY_SHARDS

    /**
     * Mask that rounds a sequence number down to its FEC block base.
     *
     * Spec §8.4 writes this as `(sequenceNumber / 4) * 4`. As a mask it is correct across the 16-bit
     * wrap, which the division is not once the value has been widened to a signed `Int`.
     */
    const val FEC_BLOCK_BASE_MASK: Int = 0xFFFF and (FEC_DATA_SHARDS - 1).inv()

    /** The parity header that follows the RTP header of a `packetType == 127` packet (spec §8.4). */
    const val FEC_HEADER_SIZE: Int = 12

    /** Field offsets within the parity header. The 16- and 32-bit fields are **big-endian**. */
    const val FEC_OFFSET_SHARD_INDEX: Int = 0
    const val FEC_OFFSET_PAYLOAD_TYPE: Int = 1
    const val FEC_OFFSET_BASE_SEQUENCE: Int = 2
    const val FEC_OFFSET_BASE_TIMESTAMP: Int = 4
    const val FEC_OFFSET_SSRC: Int = 8

    /**
     * How long an incomplete FEC block is held past the point it should have completed (spec §8.4).
     *
     * "Out-of-order wait: hold an incomplete block for at most 10 ms past when it should have
     * completed, then release what we have." Every millisecond here is a millisecond of audio
     * latency for every listener, paid to rescue a reordered packet for one of them, so it is small
     * on purpose.
     */
    const val OUT_OF_ORDER_WAIT_MS: Long = 10L

    // ---- Opus (spec §8.3, §8.5) ----------------------------------------------------------------

    /** Sample rate is always this. Never negotiated (spec §8.3). */
    const val SAMPLE_RATE_HZ: Int = 48_000

    /** `x-nv-aqos.packetDuration` default — one Opus frame per RTP packet (spec §8.5). */
    const val DEFAULT_PACKET_DURATION_MS: Int = 5

    /** The slow-decoder packet duration (spec §8.5). */
    const val SLOW_PACKET_DURATION_MS: Int = 10

    /** Channel counts spec §8.2 tabulates. */
    const val CHANNELS_STEREO: Int = 2
    const val CHANNELS_51_SURROUND: Int = 6
    const val CHANNELS_71_SURROUND: Int = 8

    /**
     * The RTP timestamp step per packet.
     *
     * The audio RTP timestamp does **not** run at the 48 kHz sample clock: spec §8.4's
     * `blockBaseTimestamp = timestamp - ((sequenceNumber - blockBase) * packetDurationMs)` defines
     * one tick as one millisecond, and a host increments it by `packetDuration` per packet. Deriving
     * a timestamp any other way puts synthesised concealment packets on a different timeline from
     * the received ones.
     */
    fun timestampStepFor(packetDurationMs: Int): Int = packetDurationMs

    // ---- Socket and threads --------------------------------------------------------------------

    /**
     * Largest datagram the audio socket will read.
     *
     * Hosts cap an audio packet at 1400 bytes, and an encrypted one is padded up to the next 16-byte
     * boundary on top of that. 2048 leaves room for both plus the RTP and parity headers, and
     * matches the video socket's buffer so a thread dump reads consistently.
     */
    const val MAX_AUDIO_DATAGRAM_BYTES: Int = 2048

    /**
     * Receive buffer requested on the audio socket.
     *
     * Two orders of magnitude smaller than video's: 200 packets per second of a few hundred bytes
     * each. It exists so that a scheduling hiccup on the receive thread costs no packets, not so
     * that a backlog can build up — see [INITIAL_RESYNC_DROP_MS] for why a backlog is the enemy.
     */
    const val AUDIO_RECEIVE_BUFFER_BYTES: Int = 1 shl 16

    /** Keep-alive cadence on the audio socket, matching the video socket's (spec §7.5). */
    const val PING_INTERVAL_MS: Long = 500L

    /**
     * How long the receive thread blocks before checking for work it can do while idle.
     *
     * A timeout rather than an indefinite block, because two decisions need a tick even when no
     * packet arrives: releasing an FEC block that has waited out [OUT_OF_ORDER_WAIT_MS], and
     * cancelling the start-up resync drop on a host that has no backlog to discard.
     */
    const val RECEIVE_POLL_TIMEOUT_MS: Int = 100

    /** Thread names from architecture §3's table. */
    const val THREAD_AUDIO_RX: String = "audio-rx"
    const val THREAD_AUDIO_PING: String = "audio-ping"

    /** How long teardown waits for a receive thread to notice its socket closed. */
    const val THREAD_JOIN_TIMEOUT_MS: Long = 1_000L

    // ---- Queueing ------------------------------------------------------------------------------

    /**
     * How much audio may sit between the receive thread and the decoder.
     *
     * Sized in packets, which at the default 5 ms packet duration makes this 60 ms. Deliberately
     * larger than the video queue's two frames — an audio packet is 5 ms of content, not 16 — and
     * deliberately far short of anything that would let a stalled decoder accumulate a backlog: on
     * overflow the *oldest* packet is evicted, so latency is capped by the queue's depth rather
     * than by the decoder's worst moment.
     */
    const val SAMPLE_QUEUE_CAPACITY: Int = 12

    /** Bound on the event queue, drop-oldest, for the same reason the video path bounds its own. */
    const val EVENT_QUEUE_CAPACITY: Int = 32

    /**
     * How much audio is discarded at the start of a session.
     *
     * A host accumulates audio samples from the moment it starts encoding, which is before our
     * socket exists; delivering that backlog would put audio permanently that far behind video.
     * Dropping it costs half a second of audio once, at a moment when a game is still loading.
     *
     * The drop is abandoned early if the socket ever goes quiet ([RECEIVE_POLL_TIMEOUT_MS]), since
     * a host with nothing queued has nothing for us to discard.
     */
    const val INITIAL_RESYNC_DROP_MS: Int = 500

    /**
     * How many consecutive missing packets are concealed before the receiver simply resynchronises.
     *
     * Spec §8.5 asks for a concealment packet per gap to keep the timeline aligned. That is right
     * for the one- and two-packet gaps that reordering and single losses produce, and wrong for a
     * two-second Wi-Fi dropout: synthesising four hundred silent packets to "keep the timeline"
     * would hand the decoder two seconds of work it has to play before it reaches live audio again,
     * which is precisely the accumulated latency this layer exists to avoid. Past this many, the
     * loss is reported and the stream jumps forward.
     */
    const val MAX_CONCEALED_PACKETS_PER_GAP: Int = 8

    /**
     * How many FEC blocks may be tracked at once.
     *
     * Four blocks is 80 ms at the default packet duration, which is far more reordering than any
     * link worth streaming over produces. Exceeding it means the stream has moved on without us, so
     * the oldest block is released with whatever it has rather than held forever.
     */
    const val MAX_TRACKED_FEC_BLOCKS: Int = 4
}

/**
 * Audio-path constants whose values `docs/01-PROTOCOL.md` explicitly marks **UNVERIFIED**.
 *
 * Each carries the spec section that flags it and the consequence of the guess being wrong, in the
 * style of [com.voidlink.android.protocol.rtp.UnverifiedRtpVideoConstants]. Every code path that
 * depends on one of these logs once per process through
 * [com.voidlink.android.protocol.ProtocolLog.unverified].
 */
object UnverifiedRtpAudioConstants {

    /**
     * Whether Reed-Solomon recovery participates in audio depacketization at all.
     *
     * UNVERIFIED(spec 01 §8.4, and §7.7's consolidated item 1 by reference): "Recovery uses RS(4,2)
     * over GF(2^8) with constant-size shards. Same interoperability caveat as §7.7 applies." That
     * caveat is the riskiest item in the document — two implementations that both claim GF(2^8) do
     * not interoperate if the generator matrix differs, and the failure mode is *silent corruption*
     * of recovered shards rather than a clean error.
     *
     * Corrupt audio is worse than missing audio: a dropped packet is 5 ms of concealment nobody
     * notices, while a corrupt one is a click through the whole mix. Spec §8.4's own v1 instruction
     * is therefore to "implement the block assembly and in-order dequeue, but skip RS recovery for
     * audio entirely", which is exactly what this `false` selects. [AudioDepacketizer] still parses
     * parity headers and still uses them for block bookkeeping, so turning this on later is a
     * change to one recovery function rather than to the queue.
     *
     * Risk if wrong (i.e. if this is ever set `true` against a mismatched matrix): audible clicks
     * that look like a decoder fault.
     */
    const val FEC_RECOVERY_ENABLED_BY_DEFAULT: Boolean = false

    /**
     * The low-byte marker in `MAKE_AUDIO_CONFIGURATION`.
     *
     * UNVERIFIED(spec 01 §8.2, consolidated item). Carried for completeness and for the one log line
     * that names it: nothing in this package composes or parses that value, because it is never on
     * the wire. The wire carries `surroundAudioInfo = (channelMask shl 16) or channelCount`, which
     * [com.voidlink.android.protocol.http.AudioChannelLayout] already builds, and the SDP's
     * `numChannels` / `channelMask` pair.
     *
     * Risk if wrong: none here. It is listed so that a reader looking for §8.2's magic number finds
     * a statement that we do not depend on it rather than silence.
     */
    const val AUDIO_CONFIGURATION_MAGIC: Int = 0xCA

    /**
     * Whether multistream (surround) Opus is played back through `MediaCodec`.
     *
     * UNVERIFIED(spec 01 §8.5): "Multistream (surround) Opus via MediaCodec is unreliable — many
     * devices only handle mono/stereo", with the v1 decision "MediaCodec for stereo only". The
     * unverified part is which devices, not whether: the spec is confident enough to make the
     * decision, and this constant is where that decision is written down rather than scattered.
     *
     * Everything needed to *try* is implemented and tested — the channel-order fix-up of §8.3, the
     * `mappingFamily = 1` codec-specific data of §8.5 — so flipping this to `true` is a
     * one-character change once a device has been tested. Until then a surround stream reports an
     * explained unavailability and the session continues without audio rather than playing
     * dialogue out of a surround speaker.
     *
     * Risk if wrong (i.e. if set `true` on a device that lies about its support): silence, noise,
     * or centre-channel dialogue in the wrong speaker.
     */
    const val MULTISTREAM_PLAYBACK_ENABLED: Boolean = false
}
