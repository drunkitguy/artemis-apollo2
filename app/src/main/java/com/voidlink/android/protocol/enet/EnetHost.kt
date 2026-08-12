package com.voidlink.android.protocol.enet

import com.voidlink.android.protocol.ProtocolLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.random.Random

/**
 * The ENet service loop and the public face of the control transport
 * (`docs/01-PROTOCOL.md` §9.1, `02-ARCHITECTURE.md` §5.3 `ControlTransport`).
 *
 * One host owns one socket and at most one peer — GameStream's "peer count 1, no bandwidth
 * throttling" (spec §9.1). [run] is the `enet-io` loop of `02-ARCHITECTURE.md` §3: the only thread
 * that touches the socket or any ENet state. Everything callers do from other threads
 * ([connect], [send], [disconnect]) is posted to it through an unbounded channel and executed
 * there, which is what makes the lock-free peer implementation safe.
 *
 * Typical use:
 *
 * ```
 * val host = EnetHost(DatagramEnetTransport(DatagramSocket()))
 * val loop = host.startIn(sessionScope)          // must be running before connect() can complete
 * if (host.connect(controlAddress, connectData)) {
 *     host.send(EnetUnverifiedConstants.CHANNEL_GENERIC, pingPayload, EnetDelivery.RELIABLE)
 *     for (packet in host.inbound) { /* GameStream control messages, spec §9.2 */ }
 * }
 * host.disconnect()                              // spec §9.7 step 3
 * loop.cancel()
 * ```
 *
 * This layer knows nothing about GameStream message types: it moves opaque payloads on numbered
 * channels. The `{ uint16 type; uint16 payloadLength; }` little-endian framing of spec §9.2 belongs
 * to `protocol/control/`, one level up.
 *
 * @param transport the socket to run on. Closed by [close].
 * @param config tunables; the defaults are the values spec §9.1 states.
 * @param clock injectable so retransmission can be tested without waiting for it.
 * @param random source of the connect id, which only has to be unpredictable enough not to collide
 *   with the previous session on the same port.
 */
class EnetHost(
    private val transport: EnetTransport,
    private val config: EnetConfig = EnetConfig(),
    private val clock: EnetClock = SystemEnetClock,
    private val random: Random = Random.Default,
) {

    private val _state = MutableStateFlow(EnetPeerState.DISCONNECTED)

    /** The peer's state, observable from any thread. */
    val state: StateFlow<EnetPeerState> = _state.asStateFlow()

    private val _inbound = Channel<EnetInboundPacket>(Channel.UNLIMITED)

    /**
     * Payloads received from the remote, in per-channel delivery order.
     *
     * Unbounded, and closed when [run] returns. Unbounded because the service loop must never block
     * on a slow consumer — a control channel that stalls its own socket reader stalls the ACKs that
     * keep the session alive — and because control messages are small and few.
     */
    val inbound: ReceiveChannel<EnetInboundPacket> = _inbound

    /** The peer, once one exists. Diagnostics only; mutated on the service loop. */
    @Volatile
    var peer: EnetPeer? = null
        private set

    /** The ENet round-trip estimate in milliseconds, for `StreamStats.rttMs` (architecture §5.2). */
    val roundTripTimeMs: Int?
        get() = peer?.roundTripTimeMs

    /** The local UDP port, which is also the port the host must send video keep-alives from. */
    val localPort: Int
        get() = transport.localPort

    @Volatile
    private var closed: Boolean = false

    private val requests = Channel<Request>(Channel.UNLIMITED)

    private var connectResult: CompletableDeferred<Boolean>? = null
    private var disconnectResult: CompletableDeferred<Boolean>? = null

    /** Scratch list reused by the service loop so a quiet pass allocates nothing. */
    private val events = ArrayList<EnetEvent>()

    /**
     * Runs the service loop until the coroutine is cancelled, [close] is called, or the socket dies.
     *
     * Blocks on the socket for [EnetConfig.serviceIntervalMs] at a time, so it **must** be launched
     * on a dispatcher that tolerates blocking — `Dispatchers.IO`, or a dedicated thread as
     * `02-ARCHITECTURE.md` §3 prescribes. The short timeout is also what bounds how quickly the
     * loop notices cancellation.
     */
    suspend fun run() {
        try {
            while (!closed) {
                currentCoroutineContext().ensureActive()
                drainRequests()
                pump()
                val datagram = receiveOrNull() ?: continue
                handleDatagram(datagram)
                pump()
            }
        } finally {
            _inbound.close()
            _state.value = EnetPeerState.DISCONNECTED
        }
    }

    /** Convenience wrapper: launches [run] in [scope]. The caller owns the returned [Job]. */
    fun startIn(scope: CoroutineScope): Job = scope.launch { run() }

    /**
     * Performs the ENet handshake (spec §9.1).
     *
     * Suspends until the host answers with VERIFY_CONNECT or the attempt times out. [run] must
     * already be executing; without it the request simply waits in the queue and this returns false
     * when [timeoutMs] expires.
     *
     * @param address the control endpoint — `server_port=` from `SETUP streamid=control`, or 47999.
     * @param connectData the 32-bit `X-SS-Connect-Data` value from the RTSP SETUP response
     *   (spec §6.3), or 0 when the host did not send one.
     * @param timeoutMs how long to wait. Spec §9.1: `CONTROL_STREAM_TIMEOUT_SEC`, 10 s. This is the
     *   deadline that counts: when it expires the attempt is abandoned outright, rather than left
     *   retransmitting into the dark behind a caller who has already given up.
     * @return true when the peer reached [EnetPeerState.CONNECTED]. On false the host is back to
     *   [EnetPeerState.DISCONNECTED] and ready for another attempt.
     */
    suspend fun connect(
        address: InetSocketAddress,
        connectData: Int,
        timeoutMs: Long = config.connectTimeoutMs.toLong(),
    ): Boolean {
        EnetUnverifiedConstants.announce()
        val result = CompletableDeferred<Boolean>()
        if (requests.trySend(Request.Connect(address, connectData, result)).isFailure) return false
        if (withTimeoutOrNull(timeoutMs) { result.await() } == true) return true
        // The caller's deadline has passed, so the peer must go. Leaving it in CONNECTING would
        // keep it retransmitting for the remainder of EnetConfig.connectTimeoutMs and — worse —
        // make the next connect() bounce off a peer nobody is waiting on any more.
        requests.trySend(Request.Abandon(result))
        return false
    }

    /**
     * Queues an application payload for the connected peer.
     *
     * Returns as soon as the request is queued; the peer applies it on the service loop, so a
     * `true` here means "accepted for sending", not "sent". The only way it can be false is a
     * closed host, which is a state the caller already knows about.
     *
     * @param channelId [EnetUnverifiedConstants.CHANNEL_GENERIC] for pings and FEC status,
     *   [EnetUnverifiedConstants.CHANNEL_URGENT] for input, IDR requests and termination (spec §9.1).
     * @param delivery see [EnetDelivery]; spec §9.5 requires the periodic ping to be reliable.
     */
    fun send(
        channelId: Int,
        payload: ByteArray,
        delivery: EnetDelivery = EnetDelivery.RELIABLE,
    ): Boolean = requests.trySend(Request.Send(channelId, payload, delivery)).isSuccess

    /**
     * Sends a DISCONNECT and waits for its acknowledgement (spec §9.7 step 3).
     *
     * @param lingerMs how long to pump for the acknowledgement. Spec §9.7:
     *   `CONTROL_STREAM_LINGER_TIMEOUT_SEC`, 2 s.
     * @return true when the remote acknowledged. False means we could not confirm the teardown, in
     *   which case the host will time the session out on its own (spec §9.7) — worth telling the
     *   user, not worth retrying.
     */
    suspend fun disconnect(
        data: Int = 0,
        lingerMs: Long = EnetControlConstants.LINGER_TIMEOUT_MS.toLong(),
    ): Boolean {
        val result = CompletableDeferred<Boolean>()
        if (requests.trySend(Request.Disconnect(data, result)).isFailure) return false
        return withTimeoutOrNull(lingerMs) { result.await() } ?: false
    }

    /**
     * Closes the socket and stops the loop.
     *
     * Safe from any thread, and safe to call twice. Does *not* send a DISCONNECT — call
     * [disconnect] first if the host should be told, which spec §9.7 says it should.
     */
    fun close() {
        if (closed) return
        closed = true
        requests.close()
        transport.close()
    }

    // ---- Service loop --------------------------------------------------------------------------

    /** Applies everything other threads have posted since the last pass. */
    private fun drainRequests() {
        while (true) {
            val request = requests.tryReceive().getOrNull() ?: return
            when (request) {
                is Request.Connect -> applyConnect(request)
                is Request.Abandon -> applyAbandon(request)
                is Request.Send -> applySend(request)
                is Request.Disconnect -> applyDisconnect(request)
            }
        }
    }

    private fun applyConnect(request: Request.Connect) {
        if (peer != null) {
            request.result.complete(false)
            return
        }
        val created = EnetPeer(
            address = request.address,
            config = config,
            incomingPeerId = LOCAL_PEER_ID,
            connectId = random.nextInt(),
        )
        peer = created
        connectResult = request.result
        created.startConnect(clock.nowMs(), request.connectData)
    }

    /**
     * Drops a handshake whose caller has stopped waiting.
     *
     * Requests reach the loop in order, so an [Request.Abandon] queued by a timed-out `connect()`
     * is always applied before any retry that caller makes — the peer is gone by the time the new
     * CONNECT is built.
     */
    private fun applyAbandon(request: Request.Abandon) {
        // complete() returns false when the handshake had already resolved this deferred: it landed
        // in the window between the caller's deadline expiring and this request arriving. The
        // connection is real and [state] says so, so it is left alone rather than torn down for
        // losing a race by microseconds.
        if (!request.result.complete(false)) return
        if (connectResult === request.result) connectResult = null
        val abandoned = peer ?: return
        peer = null
        _state.value = EnetPeerState.DISCONNECTED
        ProtocolLog.w(
            EnetControlConstants.TAG,
            "abandoning the ENet handshake in state ${abandoned.state}: the caller's deadline passed",
        )
    }

    private fun applySend(request: Request.Send) {
        val current = peer
        if (current == null) {
            ProtocolLog.d(EnetControlConstants.TAG, "send on channel ${request.channelId} with no peer")
            return
        }
        current.send(request.channelId, request.payload, request.delivery)
    }

    private fun applyDisconnect(request: Request.Disconnect) {
        val current = peer
        if (current == null) {
            request.result.complete(true)
            return
        }
        disconnectResult = request.result
        current.startDisconnect(request.data)
        if (current.state == EnetPeerState.DISCONNECTED) {
            // Nothing was established, so there is nothing to wait for an acknowledgement of.
            peer = null
            _state.value = EnetPeerState.DISCONNECTED
            disconnectResult = null
            request.result.complete(true)
        }
    }

    /** Runs the peer's timers, sends whatever came out, and dispatches the resulting events. */
    private fun pump() {
        val current = peer ?: return
        events.clear()
        val datagrams = current.service(clock.nowMs(), events)
        for (datagram in datagrams) sendDatagram(current.address, datagram)
        dispatch(events)
        _state.value = peer?.state ?: EnetPeerState.DISCONNECTED
    }

    private fun sendDatagram(destination: InetSocketAddress, datagram: ByteArray) {
        try {
            transport.send(datagram, datagram.size, destination)
        } catch (io: IOException) {
            // Spec §7.5's reasoning applies here too: a host that has not bound its socket yet
            // answers with ICMP port-unreachable, and the receive side is where that is handled.
            ProtocolLog.d(EnetControlConstants.TAG, "ENet send failed: ${io.message}")
        }
    }

    private fun receiveOrNull(): EnetDatagram? = try {
        transport.receive(config.serviceIntervalMs)
    } catch (socketClosed: SocketException) {
        if (!closed) {
            ProtocolLog.w(EnetControlConstants.TAG, "ENet socket failed: ${socketClosed.message}")
        }
        closed = true
        null
    } catch (io: IOException) {
        ProtocolLog.d(EnetControlConstants.TAG, "ENet receive error: ${io.message}")
        null
    }

    private fun handleDatagram(datagram: EnetDatagram) {
        val parsed = EnetPacketCodec.decode(datagram.data, datagram.length) ?: return
        val now = clock.nowMs()
        val current = peer
        if (current == null) {
            acceptIncoming(parsed, datagram.source, now)
            return
        }
        if (!isFromPeer(datagram.source, current.address)) return
        if (EnetUnverifiedConstants.VALIDATE_SESSION_ID &&
            current.state == EnetPeerState.CONNECTED &&
            parsed.header.sessionId != current.incomingSessionId
        ) {
            return
        }
        events.clear()
        for (command in parsed.commands) {
            current.handleCommand(command, parsed.header.sentTime, now, events)
        }
        dispatch(events)
    }

    /**
     * Answers an inbound CONNECT, when configured to (`EnetConfig.acceptIncomingConnections`).
     *
     * The client never does this; the minimal server peer the loopback tests run against is the
     * whole reason it exists, and having both halves of the handshake in one implementation is what
     * lets that test prove the handshake rather than assert our encoder against our own decoder.
     *
     * Note that CONNECT is *not* separately acknowledged: VERIFY_CONNECT is its acknowledgement,
     * which is why the client removes its in-flight CONNECT on receiving one.
     */
    private fun acceptIncoming(datagram: EnetIncomingDatagram, source: InetSocketAddress, nowMs: Int) {
        if (!config.acceptIncomingConnections) return
        var connect: EnetCommand.Connect? = null
        for (command in datagram.commands) {
            if (command is EnetCommand.Connect) {
                connect = command
                break
            }
        }
        if (connect == null) return

        val created = EnetPeer(
            address = source,
            config = config,
            incomingPeerId = LOCAL_PEER_ID,
            connectId = connect.connectId,
        )
        peer = created
        created.acceptConnect(connect, nowMs)
        _state.value = created.state
    }

    /**
     * Turns peer events into public state.
     *
     * The order inside each branch matters: [_state] and [peer] are settled **before** any deferred
     * is completed. Completing a deferred resumes the caller, possibly on another thread and
     * immediately, so a caller that does the obvious thing — `connect()` then read `state` — would
     * otherwise be racing this loop for the answer it was just given.
     */
    private fun dispatch(pending: List<EnetEvent>) {
        for (event in pending) {
            when (event) {
                is EnetEvent.Connected -> {
                    _state.value = EnetPeerState.CONNECTED
                    ProtocolLog.i(
                        EnetControlConstants.TAG,
                        "ENet connected: mtu=${event.peer.mtu} channels=${event.peer.channelCount}",
                    )
                    connectResult?.complete(true)
                    connectResult = null
                }

                is EnetEvent.ConnectFailed -> {
                    peer = null
                    _state.value = EnetPeerState.DISCONNECTED
                    ProtocolLog.w(EnetControlConstants.TAG, "ENet connect failed: ${event.reason}")
                    connectResult?.complete(false)
                    connectResult = null
                }

                is EnetEvent.Disconnected -> {
                    peer = null
                    _state.value = EnetPeerState.DISCONNECTED
                    ProtocolLog.i(
                        EnetControlConstants.TAG,
                        "ENet disconnected (timedOut=${event.timedOut}, data=${event.data})",
                    )
                    connectResult?.complete(false)
                    connectResult = null
                    disconnectResult?.complete(!event.timedOut)
                    disconnectResult = null
                }

                is EnetEvent.Received -> _inbound.trySend(
                    EnetInboundPacket(event.channelId, event.payload),
                )
            }
        }
    }

    /**
     * True when a datagram came from the peer we are talking to.
     *
     * Compares the address and port rather than the [InetSocketAddress] itself: the caller may have
     * built the peer address from a hostname, and an unresolved one never equals a resolved one.
     */
    private fun isFromPeer(source: InetSocketAddress, peerAddress: InetSocketAddress): Boolean {
        if (source.port != peerAddress.port) return false
        val expected = peerAddress.address ?: return true
        return source.address == expected
    }

    /** Work posted to the service loop from another thread. */
    private sealed class Request {
        class Connect(
            val address: InetSocketAddress,
            val connectData: Int,
            val result: CompletableDeferred<Boolean>,
        ) : Request()

        class Abandon(
            val result: CompletableDeferred<Boolean>,
        ) : Request()

        class Send(
            val channelId: Int,
            val payload: ByteArray,
            val delivery: EnetDelivery,
        ) : Request()

        class Disconnect(
            val data: Int,
            val result: CompletableDeferred<Boolean>,
        ) : Request()
    }

    private companion object {
        /**
         * The peer id we ask the remote to stamp on datagrams it sends us.
         *
         * Always 0: with a peer count of one there is only ever one slot to address.
         */
        const val LOCAL_PEER_ID: Int = 0
    }
}
