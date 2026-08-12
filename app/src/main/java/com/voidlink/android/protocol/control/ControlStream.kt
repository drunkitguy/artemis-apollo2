package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.enet.EnetDelivery
import com.voidlink.android.protocol.enet.EnetUnverifiedConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Something the host told us on the control stream (`docs/01-PROTOCOL.md` §9.6).
 *
 * Only the messages v1 acts on are modelled. Everything else arrives as [Unrecognized], which spec
 * §9.3 requires to be ignored rather than treated as an error: "**v1: ignore unrecognized control
 * message types** (log the type + length at debug)".
 */
sealed interface ControlEvent {

    /**
     * The host is ending the session (spec §9.6).
     *
     * @property errorCode the raw big-endian `HRESULT`, or `null` when the host sent none.
     * @property graceful true only for the code spec §9.6 lists as a normal close — and that entry
     *   is itself marked UNVERIFIED, so a `false` here is not proof that anything went wrong.
     */
    class Terminated(val errorCode: Int?, val graceful: Boolean) : ControlEvent {

        /** The code as hex, or a phrase saying there was none. Never invented text (spec §9.6). */
        fun describe(): String = when {
            errorCode == null -> "the host ended the session without giving a reason"
            graceful -> "the host ended the session normally (0x${hex(errorCode)})"
            errorCode == ControlConstants.TERMINATION_FRAME_CONVERSION ->
                "the host's video encoder failed to convert a frame (0x${hex(errorCode)})"
            errorCode == ControlConstants.TERMINATION_PROTECTED_CONTENT ->
                "the host reported protected content on screen (0x${hex(errorCode)})"
            else -> "the host ended the session with code 0x${hex(errorCode)}"
        }

        private fun hex(code: Int): String = code.toLong().and(0xFFFFFFFFL).toString(16)
    }

    /**
     * The host toggled HDR mid-stream (spec §9.6).
     *
     * v1 reads the enable flag only; the mastering-display metadata that follows it is UNVERIFIED
     * in layout and is carried here as raw bytes so the log can show it.
     */
    class HdrModeChanged(val enabled: Boolean, val payload: ByteArray) : ControlEvent

    /**
     * A controller-feedback message, handed on **unparsed** (spec §9.6, §9.3 indices 6, 9, 10).
     *
     * Rumble, trigger rumble and the motion-report request all belong to the input layer, and
     * `protocol/input`'s `HostFeedbackParser` already reads all three — including the asymmetry that
     * makes this worth not duplicating: rumble carries four leading bytes and rumble-triggers does
     * not, despite being neighbouring messages. Parsing them a second time here would be one more
     * place for that asymmetry to be "cleaned up" into a bug.
     *
     * @property message which slot of spec §9.3's table it arrived in.
     * @property payload the message body, owned by the receiver.
     */
    class HostFeedback(
        val message: ControlMessageIndex,
        val payload: ByteArray,
    ) : ControlEvent

    /** A message this build does not act on. Carried so the session can count and log it. */
    class Unrecognized(val type: Int, val payloadLength: Int) : ControlEvent
}

/**
 * Counters the stream keeps, for the stats overlay and for bug reports.
 *
 * @property pingsSent periodic pings written (spec §9.5).
 * @property idrRequestsSent IDR requests that made it onto the wire.
 * @property idrRequestsSuppressed IDR requests the rate limiter swallowed — the number that says
 *   whether the link is merely lossy or is in the storm spec §9.5 warns about.
 * @property messagesReceived control messages the host sent us.
 * @property unrecognizedReceived how many of those this build ignored.
 */
class ControlStreamStats(
    val pingsSent: Long,
    val idrRequestsSent: Long,
    val idrRequestsSuppressed: Long,
    val messagesReceived: Long,
    val unrecognizedReceived: Long,
)

/**
 * The GameStream control stream: message framing, the session-start sequence, the periodic ping,
 * rate-limited IDR requests, and host-feedback dispatch (`docs/01-PROTOCOL.md` §9).
 *
 * Sits on a [ControlLink] — architecture §5.3's `ControlTransport`, satisfied in production by
 * [EnetControlLink] over the real ENet host, which carries opaque payloads on numbered channels and
 * knows nothing about GameStream. Everything above the ENet layer and below the session state
 * machine lives here.
 *
 * **Channel discipline** (spec §9.1): pings and FEC status go on
 * [EnetUnverifiedConstants.CHANNEL_GENERIC]; IDR requests and termination go on
 * [EnetUnverifiedConstants.CHANNEL_URGENT]. On a non-Sunshine host every message is forced onto
 * channel 0 and sent reliably, which is what the reference client does — GFE never negotiated more
 * than one usable channel.
 *
 * **Rate limiting** (spec §9.5): [requestIdrFrame] is capped at one per
 * [UnverifiedControlConstants.IDR_REQUEST_MIN_INTERVAL_MS]. This is the whole reason the video
 * layer reports loss honestly and does not throttle itself — deciding how often to ask is this
 * class's job, and a burst of twenty lost frames must produce one request, not twenty.
 *
 * **There is no bitrate-change message and there must never be one.**
 * `docs/05-DYNAMIC-BITRATE.md` §5: the bitrate is pinned at ANNOUNCE as both floor and ceiling, and
 * nothing on this stream can move it.
 *
 * **Threading:** [requestIdrFrame], [sendFrameFecStatus] and [onFrameProgress] are safe to call
 * from any thread — including the `video-rx` thread, which is where loss is noticed — because
 * `EnetHost` posts sends to its own service loop and the rate limiter is a compare-and-set. [start] owns the
 * two coroutines that run for the life of the stream; cancelling the returned [Job] stops both.
 *
 * @param link the connected control transport. Not owned: the caller opened it and closes it.
 * @param table the per-generation type table (spec §9.3).
 * @param generation `AppVersionQuad[0]`, which decides the Start A/B payload shapes (spec §9.4).
 * @param isSunshine whether the host is Sunshine-family, which decides channel and delivery use.
 * @param usePeriodicPing whether the host is new enough to want the periodic ping rather than the
 *   legacy loss-stats message (spec §9.5).
 * @param crypto the AES-GCM framing when `SS_ENC_CONTROL_V2` was negotiated; `null` in v1
 *   (spec §6.5).
 * @param pingIntervalMs cadence of the periodic ping.
 * @param idrMinIntervalMs the IDR rate limit.
 * @param clock monotonic nanosecond source, injectable so the rate limiter is testable without
 *   sleeping.
 */
class ControlStream(
    private val link: ControlLink,
    private val table: ControlMessageTable,
    private val generation: Int,
    private val isSunshine: Boolean,
    private val usePeriodicPing: Boolean,
    private val crypto: ControlCrypto? = null,
    private val pingIntervalMs: Long = UnverifiedControlConstants.PERIODIC_PING_INTERVAL_MS,
    private val idrMinIntervalMs: Long = UnverifiedControlConstants.IDR_REQUEST_MIN_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() },
) {

    private val _events = Channel<ControlEvent>(Channel.UNLIMITED)

    /**
     * Host→client messages, in arrival order.
     *
     * Unbounded for the same reason [ControlLink.inbound] is: the pump must never block, and a control
     * message the session has not read yet is a termination notice we would otherwise lose.
     */
    val events: ReceiveChannel<ControlEvent> = _events

    /** Nanosecond timestamp of the last IDR request; `Long.MIN_VALUE` until the first one. */
    private val lastIdrRequestNanos = AtomicLong(Long.MIN_VALUE)

    private val pingCount = AtomicLong(0L)
    private val idrSentCount = AtomicLong(0L)
    private val idrSuppressedCount = AtomicLong(0L)
    private val receivedCount = AtomicLong(0L)
    private val unrecognizedCount = AtomicLong(0L)

    /** The newest frame index the video path has seen, for the invalidation fallback (spec §9.5). */
    @Volatile
    private var lastSeenFrameIndex: Long = 0L

    /** The newest frame that decoded completely, for the legacy loss-stats payload (spec §9.5). */
    @Volatile
    private var lastGoodFrameIndex: Long = 0L

    /** A snapshot of the counters. Safe from any thread. */
    fun stats(): ControlStreamStats = ControlStreamStats(
        pingsSent = pingCount.get(),
        idrRequestsSent = idrSentCount.get(),
        idrRequestsSuppressed = idrSuppressedCount.get(),
        messagesReceived = receivedCount.get(),
        unrecognizedReceived = unrecognizedCount.get(),
    )

    /**
     * Sends the session-start sequence and launches the stream's coroutines (spec §9.4).
     *
     * Start A then Start B, fire and forget — on ENet neither expects a reply, unlike the Gen 3/4
     * TCP control stream this client does not implement.
     *
     * @param scope the session scope. The returned [Job] is a child of it, so cancelling the
     *   session stops the ping loop and the inbound pump without a second teardown path.
     * @return the job running the ping loop and the inbound pump.
     */
    fun start(scope: CoroutineScope): Job {
        UnverifiedControlConstants.announce()

        val startA = table.typeOf(ControlMessageIndex.START_A)
        if (startA != null) {
            send(startA, ControlPayloads.startA(generation), urgent = false, EnetDelivery.RELIABLE)
        }
        val startB = table.typeOf(ControlMessageIndex.START_B)
        if (startB != null) {
            send(startB, ControlPayloads.startB(generation), urgent = false, EnetDelivery.RELIABLE)
        }
        ProtocolLog.i(
            ControlConstants.TAG,
            "control stream started: ${table.label}, sunshine=$isSunshine, " +
                "periodicPing=$usePeriodicPing, encrypted=${crypto != null}",
        )

        return scope.launch {
            launch { pumpInbound() }
            launch { runPeriodicMessages() }
        }
    }

    /**
     * Asks the host for a keyframe, at most once per [idrMinIntervalMs] (spec §9.5).
     *
     * Spec §9.5 is emphatic about the limit: "at most one per ~100 ms, or a lossy link turns into
     * an IDR storm that makes things worse". Each request costs the host a full intra frame, which
     * is several times the size of a normal one — on a link that is already dropping packets,
     * asking twenty times makes the next twenty frames likelier to drop too.
     *
     * On a host whose message table has no dedicated IDR message (unencrypted Gen 5/7), the request
     * is expressed as a reference-frame invalidation reaching
     * [ControlConstants.INVALIDATE_LOOKBACK_FRAMES] frames back, which is what the reference client
     * does and has the same effect.
     *
     * @return true when a request was actually written; false when the limiter suppressed it.
     */
    fun requestIdrFrame(): Boolean {
        val now = clock()
        val previous = lastIdrRequestNanos.get()
        val minimumGap = idrMinIntervalMs * NANOS_PER_MILLI
        if (previous != Long.MIN_VALUE && now - previous < minimumGap) {
            idrSuppressedCount.incrementAndGet()
            return false
        }
        if (!lastIdrRequestNanos.compareAndSet(previous, now)) {
            // Another thread got there first within the same window; that request stands.
            idrSuppressedCount.incrementAndGet()
            return false
        }

        val sent = if (table.supportsIdrRequest) {
            val type = table.typeOf(ControlMessageIndex.START_A) ?: return false
            send(type, ControlPayloads.requestIdrFrame(), urgent = true, EnetDelivery.RELIABLE)
        } else {
            val type = table.typeOf(ControlMessageIndex.INVALIDATE_REFERENCE_FRAMES) ?: return false
            val range = ControlPayloads.idrInvalidationRange(lastSeenFrameIndex)
            send(
                type,
                ControlPayloads.invalidateReferenceFrames(range[0], range[1]),
                urgent = true,
                EnetDelivery.RELIABLE,
            )
        }
        if (sent) idrSentCount.incrementAndGet()
        return sent
    }

    /**
     * Records the newest frame index seen and the newest that completed.
     *
     * Both feed messages rather than statistics: [lastSeenFrameIndex] bounds the invalidation range
     * an IDR request falls back to, and [lastGoodFrameIndex] is a field of the legacy loss-stats
     * payload. Called from the `video-rx` thread, so both are plain volatile writes.
     */
    fun onFrameProgress(lastSeenFrameIndex: Long, lastGoodFrameIndex: Long) {
        this.lastSeenFrameIndex = lastSeenFrameIndex
        this.lastGoodFrameIndex = lastGoodFrameIndex
    }

    /**
     * Sends one Sunshine per-frame FEC status report (spec §9.5).
     *
     * Unsequenced, because a late report describes a frame the host has already moved past. Only
     * meaningful on a Sunshine-family host that we told about the feature; the caller decides, and
     * this method does not second-guess it beyond skipping the send on a non-Sunshine host, where
     * the type is not defined at all.
     */
    fun sendFrameFecStatus(status: FrameFecStatus): Boolean {
        if (!isSunshine) return false
        return send(
            ControlConstants.TYPE_FRAME_FEC_STATUS,
            ControlPayloads.frameFecStatus(status),
            urgent = false,
            EnetDelivery.UNSEQUENCED,
        )
    }

    /**
     * Sends one already-built input payload (spec §10, §10.4).
     *
     * The seam `protocol/input`'s `InputPacketTransport` fills: the payload arrives complete —
     * big-endian length prefix, tag and ciphertext — and is framed with the control header and put
     * on the **urgent** channel, reliably. Nothing here reads, re-frames or re-encrypts it;
     * encryption and IV chaining are the input layer's, framing and delivery are ours.
     *
     * @return false when this host has no input message at all (Gen 3/4, spec §9.3), or when the
     *   transport refused it. A single lost input packet is a dropped keystroke, not a dead session.
     */
    /**
     * Whether this host has an input message at all (spec §9.3).
     *
     * False on Gen 3/4, whose input travelled over the legacy TCP control stream this client does
     * not implement — a session there streams video and accepts no input, which is worth saying out
     * loud rather than discovering one keystroke at a time.
     */
    fun supportsInput(): Boolean = table.typeOf(ControlMessageIndex.INPUT_DATA) != null

    fun sendInputPayload(payload: ByteArray): Boolean {
        val type = table.typeOf(ControlMessageIndex.INPUT_DATA) ?: return false
        return send(type, payload, urgent = true, EnetDelivery.RELIABLE)
    }

    /**
     * Tells the host we are done, in spec §9.7's order.
     *
     * 1. Termination message on the urgent channel — see
     *    [UnverifiedControlConstants.SEND_CLIENT_TERMINATION].
     * 2. ENet DISCONNECT, pumped for [ControlConstants.LINGER_TIMEOUT_MS] for its acknowledgement.
     *
     * Never throws: teardown runs on failure paths, where a second failure must not mask the first.
     *
     * @return true when the host acknowledged the disconnect. A false means the host will time the
     *   session out on its own in 10–30 s (spec §9.7) — worth telling the user, not worth retrying.
     */
    suspend fun terminate(): Boolean {
        if (UnverifiedControlConstants.SEND_CLIENT_TERMINATION) {
            val type = table.typeOf(ControlMessageIndex.TERMINATION)
            if (type != null) {
                send(type, ControlPayloads.termination(), urgent = true, EnetDelivery.RELIABLE)
            }
        }
        return try {
            link.disconnect(ControlConstants.LINGER_TIMEOUT_MS)
        } catch (failure: Exception) {
            ProtocolLog.w(ControlConstants.TAG, "ENet disconnect failed: ${failure.message}")
            false
        }
    }

    /** Closes the event channel. Called once the session is finished with the stream. */
    fun close() {
        _events.close()
    }

    // ---- Periodic messages (spec §9.5) ---------------------------------------------------------

    /**
     * The `control-work` loop of architecture §3: one periodic message, forever.
     *
     * Either the periodic ping (7.1.415+) or the legacy loss-stats report, never both — they are
     * the same slot in the protocol serving hosts of different ages, and sending both to a modern
     * host would have it parse a 32-byte payload it does not expect.
     */
    private suspend fun runPeriodicMessages() {
        val intervalMs = if (usePeriodicPing) pingIntervalMs else
            UnverifiedControlConstants.LOSS_REPORT_INTERVAL_MS
        while (currentCoroutineContext().isActive) {
            val written = if (usePeriodicPing) {
                send(
                    ControlConstants.TYPE_PERIODIC_PING,
                    ControlPayloads.periodicPing(),
                    urgent = false,
                    // Reliable on purpose (spec §9.5): the RTT estimate comes from the ACK.
                    EnetDelivery.RELIABLE,
                )
            } else {
                val type = table.typeOf(ControlMessageIndex.LOSS_STATS)
                if (type == null) false else send(
                    type,
                    ControlPayloads.lossStats(
                        UnverifiedControlConstants.LOSS_REPORT_INTERVAL_MS.toInt(),
                        lastGoodFrameIndex,
                    ),
                    urgent = false,
                    EnetDelivery.RELIABLE,
                )
            }
            if (written) pingCount.incrementAndGet()
            delay(intervalMs)
        }
    }

    // ---- Inbound (spec §9.6) -------------------------------------------------------------------

    /** Drains [ControlLink.inbound], decodes, and publishes what the session acts on. */
    private suspend fun pumpInbound() {
        for (packet in link.inbound) {
            receivedCount.incrementAndGet()
            val message = decode(packet.payload) ?: continue
            dispatch(message)
        }
        _events.close()
    }

    private fun decode(payload: ByteArray): ControlMessage? {
        val cipher = crypto
        return if (cipher != null) cipher.open(payload)
        else ControlFraming.decode(payload, UnverifiedControlConstants.UNENCRYPTED_HEADER)
    }

    private fun dispatch(message: ControlMessage) {
        when (table.indexOf(message.type)) {
            ControlMessageIndex.TERMINATION -> {
                val code = ControlPayloads.terminationErrorCode(message.payload)
                val graceful = code == ControlConstants.TERMINATION_GRACEFUL
                if (graceful) {
                    ProtocolLog.unverified(
                        ControlConstants.TAG,
                        "termination-graceful-code",
                        "treating 0x80030023 as a graceful termination (spec 01 §9.6 marks the " +
                            "meaning of this code UNVERIFIED)",
                    )
                }
                val event = ControlEvent.Terminated(code, graceful)
                ProtocolLog.i(
                    ControlConstants.TAG,
                    "host terminated the session: ${event.describe()}",
                )
                _events.trySend(event)
            }

            ControlMessageIndex.HDR_MODE -> {
                val enabled = message.payload.isNotEmpty() && message.payload[0].toInt() != 0
                ProtocolLog.unverified(
                    ControlConstants.TAG,
                    "control-hdr-metadata-layout",
                    "reading only the HDR enable flag; the metadata after it is UNVERIFIED in " +
                        "layout (spec 01 §9.6). Payload: " +
                        ControlFraming.describe(message.payload),
                )
                _events.trySend(ControlEvent.HdrModeChanged(enabled, message.payload))
            }

            ControlMessageIndex.RUMBLE,
            ControlMessageIndex.RUMBLE_TRIGGERS,
            ControlMessageIndex.SET_MOTION_EVENT,
            -> {
                val index = requireNotNull(table.indexOf(message.type))
                ProtocolLog.d(
                    ControlConstants.TAG,
                    "host feedback ${index.label}: ${ControlFraming.describe(message.payload)}",
                )
                _events.trySend(ControlEvent.HostFeedback(index, message.payload))
            }

            else -> {
                unrecognizedCount.incrementAndGet()
                ProtocolLog.d(
                    ControlConstants.TAG,
                    "ignoring control message ${message.typeHex()} of " +
                        "${message.payload.size} bytes: " + ControlFraming.describe(message.payload),
                )
                _events.trySend(ControlEvent.Unrecognized(message.type, message.payload.size))
            }
        }
    }

    // ---- Sending -------------------------------------------------------------------------------

    /**
     * Frames one message and hands it to ENet.
     *
     * @param urgent whether it belongs on [EnetUnverifiedConstants.CHANNEL_URGENT] (spec §9.1).
     */
    private fun send(
        type: Int,
        payload: ByteArray,
        urgent: Boolean,
        delivery: EnetDelivery,
    ): Boolean {
        val cipher = crypto
        val framed = if (cipher != null) {
            cipher.seal(type, payload) ?: return false
        } else {
            ControlFraming.encode(type, payload, UnverifiedControlConstants.UNENCRYPTED_HEADER)
        }
        return link.send(channelFor(urgent), framed, deliveryFor(delivery))
    }

    /**
     * Which ENet channel a message goes on (spec §9.1).
     *
     * GFE negotiated a single usable channel, so everything is forced onto 0 there; the reference
     * client does the same, and also falls back to 0 whenever the peer negotiated fewer channels
     * than the id being asked for.
     */
    private fun channelFor(urgent: Boolean): Int {
        if (!isSunshine) return EnetUnverifiedConstants.CHANNEL_GENERIC
        val requested = if (urgent) EnetUnverifiedConstants.CHANNEL_URGENT
        else EnetUnverifiedConstants.CHANNEL_GENERIC
        val negotiated = link.negotiatedChannelCount ?: return requested
        return if (requested >= negotiated) EnetUnverifiedConstants.CHANNEL_GENERIC else requested
    }

    /** GFE gets everything reliably; only Sunshine understands our unsequenced traffic (spec §9.1). */
    private fun deliveryFor(requested: EnetDelivery): EnetDelivery =
        if (isSunshine) requested else EnetDelivery.RELIABLE

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
