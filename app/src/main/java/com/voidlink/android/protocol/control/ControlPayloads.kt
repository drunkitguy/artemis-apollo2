package com.voidlink.android.protocol.control

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Sunshine per-frame FEC status report (`docs/01-PROTOCOL.md` §9.5).
 *
 * Every field is **big-endian** on the wire, unlike almost everything else on this stream — spec
 * §0.1 lists it in the endianness table for exactly that reason. Sent unsequenced, one per frame,
 * and only when the host is Sunshine-family *and* we advertised the `x-ml-general.featureFlags`
 * bit `0x1`; it drives Sunshine's adaptive FEC.
 *
 * @property frameIndex the host's frame index (spec §7.4).
 * @property highestReceivedSequenceNumber highest RTP sequence number seen for the frame.
 * @property nextContiguousSequenceNumber the first sequence number not yet received in order.
 * @property missingPacketsBeforeHighestReceived holes below [highestReceivedSequenceNumber].
 * @property totalDataPackets data shards the frame's FEC block declared.
 * @property totalParityPackets parity shards the block declared.
 * @property receivedDataPackets data shards that arrived.
 * @property receivedParityPackets parity shards that arrived.
 * @property fecPercentage the block's parity overhead percentage (spec §7.4).
 * @property multiFecBlockIndex which block of the frame this reports on.
 * @property multiFecBlockCount how many blocks the frame was split into.
 */
class FrameFecStatus(
    val frameIndex: Long,
    val highestReceivedSequenceNumber: Int,
    val nextContiguousSequenceNumber: Int,
    val missingPacketsBeforeHighestReceived: Int,
    val totalDataPackets: Int,
    val totalParityPackets: Int,
    val receivedDataPackets: Int,
    val receivedParityPackets: Int,
    val fecPercentage: Int,
    val multiFecBlockIndex: Int,
    val multiFecBlockCount: Int,
)

/**
 * Every control payload this client sends, as pure `ByteArray` builders (spec §9.4, §9.5, §9.7).
 *
 * Separate from [ControlFraming] and from [ControlStream] on purpose. A payload builder takes
 * numbers and returns bytes; it opens nothing, holds nothing and can be pinned byte for byte by a
 * test with a hex fixture. Spec §0.1 calls endianness "the number-one bug source" and the protocol
 * genuinely mixes orders *within this one stream* — the loss-stats payload is little-endian, the
 * per-frame FEC status is big-endian, and the termination code we receive is big-endian too — so
 * every buffer here sets its order explicitly and none of them relies on a default.
 *
 * Spec §0.2 applies throughout: the C structs are `#pragma pack(1)`, so fields are written
 * back-to-back with no alignment padding.
 *
 * **There is deliberately no bitrate-change message.** `docs/05-DYNAMIC-BITRATE.md` §5 records that
 * none exists in this protocol: the bitrate is pinned at ANNOUNCE as both floor and ceiling and
 * cannot be renegotiated without re-launching the session. Nothing here should ever grow one.
 */
object ControlPayloads {

    /**
     * Start A (spec §9.4 step 1).
     *
     * `{0x00, 0x00}` for Gen 5+, a single zero byte for Gen 4, and the four little-endian ints of
     * Gen 3's Start B shape are *not* used here — Gen 3's Start A is the two-byte form as well.
     */
    fun startA(generation: Int): ByteArray =
        if (generation >= GEN5) ControlConstants.START_A_PAYLOAD_GEN5
        else if (generation == GEN4) ControlConstants.START_A_PAYLOAD_GEN4
        else ControlConstants.REQUEST_IDR_PAYLOAD_LEGACY

    /**
     * Start B (spec §9.4 step 2).
     *
     * A single zero byte on Gen 4+; on Gen 3 the four little-endian ints `0, 0, 0, 0x0a`.
     */
    fun startB(generation: Int): ByteArray {
        if (generation >= GEN4) return ControlConstants.START_B_PAYLOAD_GEN5
        val values = ControlConstants.START_B_PAYLOAD_GEN3_INTS
        val buffer = littleEndian(values.size * Int.SIZE_BYTES)
        for (value in values) buffer.putInt(value)
        return buffer.array()
    }

    /**
     * The IDR-request payload for hosts that have a dedicated IDR message (spec §9.3, §9.5).
     *
     * `{0x00, 0x00}` on Gen 3, Gen 4 and encrypted Gen 7 — the three columns of spec §9.3's table
     * whose slot 0 is "Request IDR frame". On unencrypted Gen 5/7 slot 0 is Start A instead and
     * there is no IDR message at all; [invalidateReferenceFrames] is what asks for a keyframe
     * there.
     */
    fun requestIdrFrame(): ByteArray = ControlConstants.REQUEST_IDR_PAYLOAD_LEGACY

    /**
     * The periodic ping payload (spec §9.5).
     *
     * Eight bytes, little-endian: `uint16 4` ("length of payload"), `uint32 0` (a timestamp
     * placeholder the host does not read back), and two trailing zero bytes. Sent **reliably** on
     * the generic channel, because the RTT estimate is derived from its acknowledgement.
     */
    fun periodicPing(): ByteArray {
        val buffer = littleEndian(ControlConstants.PERIODIC_PING_PAYLOAD_SIZE)
        buffer.putShort(ControlConstants.PERIODIC_PING_LENGTH_FIELD.toShort())
        buffer.putInt(0)
        // The remaining two bytes stay zero. ByteBuffer.allocate zeroes its array, so writing them
        // explicitly would only be a way to get the count wrong.
        return buffer.array()
    }

    /**
     * The loss-statistics payload for pre-7.1.415 hosts (spec §9.5).
     *
     * Thirty-two bytes, little-endian:
     * `uint32 lostFrames(0)`, `uint32 intervalMs`, `uint32 1000`, `uint64 lastGoodFrameIndex`,
     * `uint32 0`, `uint32 0`, `uint32 0x14`.
     *
     * `lostFrames` is sent as zero, which is what the reference client does: the host derives loss
     * from the frame index it last heard about, not from a count we assert.
     *
     * @param intervalMs the reporting interval, which is also transmitted so both ends agree on the
     *   scale of what follows.
     * @param lastGoodFrameIndex the newest frame that decoded completely.
     */
    fun lossStats(intervalMs: Int, lastGoodFrameIndex: Long): ByteArray {
        val buffer = littleEndian(ControlConstants.LOSS_STATS_PAYLOAD_SIZE)
        buffer.putInt(0)
        buffer.putInt(intervalMs)
        buffer.putInt(ControlConstants.LOSS_STATS_SCALE)
        buffer.putLong(lastGoodFrameIndex)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(ControlConstants.LOSS_STATS_TRAILER)
        return buffer.array()
    }

    /**
     * The reference-frame invalidation payload — three little-endian `int64`s (spec §9.3, §9.5).
     *
     * Also the IDR request on a host with no IDR message: asking the host to invalidate everything
     * back to `lastSeenFrame - 0x20` forces it to emit a frame that does not reference anything we
     * have lost, which is a keyframe by another name.
     *
     * @param startFrame first frame to invalidate.
     * @param endFrame last frame to invalidate; must not precede [startFrame].
     */
    fun invalidateReferenceFrames(startFrame: Long, endFrame: Long): ByteArray {
        require(startFrame <= endFrame) {
            "startFrame $startFrame must not exceed endFrame $endFrame"
        }
        val buffer = littleEndian(ControlConstants.INVALIDATE_REFERENCE_FRAMES_PAYLOAD_SIZE)
        buffer.putLong(startFrame)
        buffer.putLong(endFrame)
        buffer.putLong(0L)
        return buffer.array()
    }

    /**
     * The invalidation range that stands in for an IDR request (spec §9.5).
     *
     * Reaches [ControlConstants.INVALIDATE_LOOKBACK_FRAMES] frames back from the newest frame seen,
     * clamped at zero so a session that loses its very first frames still produces a valid range.
     */
    fun idrInvalidationRange(lastSeenFrameIndex: Long): LongArray {
        val end = if (lastSeenFrameIndex < 0L) 0L else lastSeenFrameIndex
        val start = if (end < ControlConstants.INVALIDATE_LOOKBACK_FRAMES) {
            0L
        } else {
            end - ControlConstants.INVALIDATE_LOOKBACK_FRAMES
        }
        return longArrayOf(start, end)
    }

    /**
     * The long-term-reference frame acknowledgement — one little-endian `uint32` (spec §9.3).
     *
     * Not sent in v1 (reference-frame invalidation is not negotiated), but the type and payload are
     * both known, so the builder exists and is pinned by a test rather than being reinvented later.
     */
    fun longTermReferenceFrameAck(frameIndex: Long): ByteArray {
        val buffer = littleEndian(ControlConstants.LTR_FRAME_ACK_PAYLOAD_SIZE)
        buffer.putInt(frameIndex.toInt())
        return buffer.array()
    }

    /**
     * The Sunshine per-frame FEC status report — 23 bytes, **big-endian** (spec §9.5).
     *
     * The one payload on this stream that is not little-endian. Sent unsequenced: a late report is
     * worse than a missing one, because it describes a frame the host has already moved past.
     */
    fun frameFecStatus(status: FrameFecStatus): ByteArray {
        val buffer = ByteBuffer.allocate(ControlConstants.FRAME_FEC_STATUS_PAYLOAD_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(status.frameIndex.toInt())
        buffer.putShort(status.highestReceivedSequenceNumber.toShort())
        buffer.putShort(status.nextContiguousSequenceNumber.toShort())
        buffer.putShort(status.missingPacketsBeforeHighestReceived.toShort())
        buffer.putShort(status.totalDataPackets.toShort())
        buffer.putShort(status.totalParityPackets.toShort())
        buffer.putShort(status.receivedDataPackets.toShort())
        buffer.putShort(status.receivedParityPackets.toShort())
        buffer.put(status.fecPercentage.toByte())
        buffer.put(status.multiFecBlockIndex.toByte())
        buffer.put(status.multiFecBlockCount.toByte())
        return buffer.array()
    }

    /**
     * The client's termination notice (spec §9.7 step 2).
     *
     * Carries no payload. Spec §9.7 names the Gen 7 type `0x0100` and puts this message on the
     * urgent channel *before* the ENet disconnect, because a host told nothing keeps the session
     * alive until it times out — 10 to 30 seconds during which the next launch attempt sees a busy
     * host. See [UnverifiedControlConstants.SEND_CLIENT_TERMINATION] for the one way this differs
     * from the reference client.
     */
    fun termination(): ByteArray = ControlFraming.EMPTY

    /**
     * Reads the termination error code a host sent us (spec §9.6).
     *
     * **Big-endian**, unlike everything else we write on this stream. Spec §9.6 phrases the guard
     * as `payloadLength >= 6` counting the two header bytes, so in payload terms the code is
     * present when at least four bytes arrived.
     *
     * @return the raw `HRESULT`, or `null` when the host sent no code — a real case, and one the
     *   caller must render differently from a code it does not recognise.
     */
    fun terminationErrorCode(payload: ByteArray): Int? {
        if (payload.size < ControlConstants.TERMINATION_ERROR_CODE_BYTES) return null
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
    }

    private const val GEN4: Int = 4
    private const val GEN5: Int = 5

    private fun littleEndian(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
