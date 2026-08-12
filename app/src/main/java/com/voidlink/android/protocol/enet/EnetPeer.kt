package com.voidlink.android.protocol.enet

import com.voidlink.android.protocol.ProtocolLog
import java.net.InetSocketAddress

/**
 * One end of an ENet connection: the state machine, the outgoing queues, retransmission, and the
 * round-trip estimate (`docs/01-PROTOCOL.md` §9.1).
 *
 * A peer owns everything that is per-connection rather than per-datagram. Its inputs are commands
 * pulled out of received datagrams ([handleCommand]) and application sends ([send]); its outputs
 * are encoded datagrams and [EnetEvent]s, both produced by [service].
 *
 * **Single-threaded by contract.** `02-ARCHITECTURE.md` §3 rule 3: "`enet-io` is the only thread
 * that touches the ENet socket. All sends are posted to it through a lock-free queue. ENet state is
 * not thread-safe." Nothing here takes a lock, and [EnetHost] is what keeps that promise.
 *
 * **What this subset leaves out**, deliberately, per `04-ROADMAP.md`'s "minimum viable subset":
 * * no bandwidth throttling and no send window — the control channel is a few small messages per
 *   frame, and a window that is never reached is a window that is never tested;
 * * no unsequenced-window duplicate suppression — unsequenced traffic is the FEC report of spec
 *   §9.5, for which a duplicate is harmless;
 * * no outbound unreliable fragmentation, since nothing in spec §9 sends an unreliable message
 *   anywhere near an MTU. Inbound unreliable fragments still parse.
 *
 * @property address the remote endpoint.
 * @property incomingPeerId the id we ask the remote to stamp on datagrams it sends us.
 * @property connectId the opaque nonce that ties a VERIFY_CONNECT to our CONNECT.
 */
class EnetPeer(
    val address: InetSocketAddress,
    private val config: EnetConfig,
    val incomingPeerId: Int,
    val connectId: Int,
) {

    /** Where this peer is in the handshake/teardown sequence of spec §9.1 and §9.7. */
    var state: EnetPeerState = EnetPeerState.DISCONNECTED
        private set

    /** The id the remote asked us to stamp on datagrams. 0x0FFF until the handshake assigns one. */
    var outgoingPeerId: Int = EnetProtocol.MAXIMUM_PEER_ID
        private set

    /** Session id we expect on datagrams from the remote. */
    var incomingSessionId: Int = 0
        private set

    /** Session id we stamp on datagrams we send. Kept in 0..3 so it never collides with the header flags. */
    var outgoingSessionId: Int = 0
        private set

    /** Negotiated MTU: the smaller of what we offered and what the remote offered. */
    var mtu: Int = config.mtu
        private set

    /** Negotiated window size. Recorded for diagnostics; this subset does not throttle. */
    var windowSize: Int = config.windowSize
        private set

    /** Negotiated channel count: the smaller of the two sides' counts. */
    var channelCount: Int = config.channelCount
        private set

    /**
     * Smoothed round-trip time in milliseconds.
     *
     * ENet's estimator, which is the classic 1/8 gain on the mean and 1/4 on the variance. Surfaced
     * because `02-ARCHITECTURE.md` §5.2 puts `rttMs` "from ENet" in `StreamStats`.
     */
    var roundTripTimeMs: Int = config.initialRoundTripTimeMs
        private set

    /** Variance of the round-trip estimate; four of these are added to the mean to get the RTO. */
    var roundTripTimeVarianceMs: Int = 0
        private set

    /** The channels, indexed by id. Sized from [EnetConfig.channelCount] and never resized. */
    val channels: List<EnetChannel> = List(config.channelCount) { EnetChannel(it) }

    /** Reliable sequence counter for the peer-level commands (CONNECT, PING, DISCONNECT). */
    private var outgoingReliableSequenceNumber: Int = 0

    /** Group counter stamped on unsequenced sends so a receiver could deduplicate them. */
    private var outgoingUnsequencedGroup: Int = 0

    /** Commands queued but not yet handed to the socket, in send order. */
    private val outgoing = ArrayList<OutgoingCommand>()

    /** Reliable commands sent and not yet acknowledged, in send order. */
    private val sentReliable = ArrayList<OutgoingCommand>()

    /** Acknowledgements owed to the remote, emitted on the next datagram. */
    private val pendingAcks = ArrayList<PendingAck>()

    /** Payload bytes sitting in [outgoing] plus [sentReliable]; bounded by config. */
    private var queuedBytes: Int = 0

    /** When the handshake started, for [EnetConfig.connectTimeoutMs]. */
    private var connectStartedMs: Int = 0

    /** When to send the next ENet-level PING. */
    private var nextPingMs: Int = 0

    /** Send time of the oldest command currently overdue, and whether one is. */
    private var earliestTimeoutMs: Int = 0
    private var hasEarliestTimeout: Boolean = false

    /** The 32-bit word from an inbound DISCONNECT, surfaced on the [EnetEvent.Disconnected]. */
    private var disconnectData: Int = 0

    /** True once [service] has emitted a terminal event, so it is never emitted twice. */
    private var terminated: Boolean = false

    /** Largest payload that fits in a single un-fragmented command at the negotiated MTU. */
    private val fragmentLength: Int
        get() = mtu - EnetProtocol.PROTOCOL_HEADER_SIZE_WITH_SENT_TIME - EnetProtocol.SEND_FRAGMENT_SIZE

    // ---- Connection establishment --------------------------------------------------------------

    /**
     * Queues the CONNECT command that opens the handshake (spec §9.1).
     *
     * @param connectData the 32-bit value from the RTSP `X-SS-Connect-Data` header (spec §6.3), or
     *   0 when the host did not supply one.
     */
    fun startConnect(nowMs: Int, connectData: Int) {
        check(state == EnetPeerState.DISCONNECTED) { "startConnect in state $state" }
        state = EnetPeerState.CONNECTING
        connectStartedMs = nowMs
        nextPingMs = nowMs + config.pingIntervalMs
        // The header session id must stay inside the two bits the peer-id word reserves for it.
        // Stock ENet leaves it at 0xFF here, which truncates into the compression flag and makes
        // the datagram undecodable; 0 is the only value that cannot. The *body* still carries
        // 0xFF ("no preference"), which is what the remote actually reads.
        outgoingSessionId = 0
        queue(
            EnetCommand.Connect(
                header = systemHeader(EnetProtocol.COMMAND_CONNECT, acknowledged = true),
                outgoingPeerId = incomingPeerId,
                incomingSessionId = EnetProtocol.SESSION_ID_UNSET,
                outgoingSessionId = EnetProtocol.SESSION_ID_UNSET,
                mtu = config.mtu,
                windowSize = config.windowSize,
                channelCount = config.channelCount,
                incomingBandwidth = 0,
                outgoingBandwidth = 0,
                packetThrottleInterval = EnetProtocol.PACKET_THROTTLE_INTERVAL_MS,
                packetThrottleAcceleration = EnetProtocol.PACKET_THROTTLE_ACCELERATION,
                packetThrottleDeceleration = EnetProtocol.PACKET_THROTTLE_DECELERATION,
                connectId = connectId,
                data = connectData,
            ),
            reliable = true,
        )
    }

    /**
     * Answers an inbound CONNECT with a VERIFY_CONNECT (the host side of the handshake).
     *
     * Used by the minimal server peer the loopback tests run against, and by nothing in the client
     * path — but it is the other half of the handshake, and having both halves in one file is what
     * makes the loopback test able to prove the handshake rather than assert our own encoder
     * against our own decoder.
     *
     * The session-id dance mirrors ENet exactly: each side advances the other's proposal by one,
     * skipping a value that would collide with the one already in use, so that a connection reusing
     * an address and port cannot be confused with its predecessor.
     */
    fun acceptConnect(command: EnetCommand.Connect, nowMs: Int) {
        check(state == EnetPeerState.DISCONNECTED) { "acceptConnect in state $state" }
        state = EnetPeerState.ACKNOWLEDGING_CONNECT
        connectStartedMs = nowMs
        nextPingMs = nowMs + config.pingIntervalMs

        outgoingPeerId = command.outgoingPeerId and EnetProtocol.MAXIMUM_PEER_ID
        outgoingSessionId = advanceSessionId(
            proposed = command.incomingSessionId,
            fallback = outgoingSessionId,
            avoid = outgoingSessionId,
        )
        incomingSessionId = advanceSessionId(
            proposed = command.outgoingSessionId,
            fallback = incomingSessionId,
            avoid = incomingSessionId,
        )

        negotiate(command.mtu, command.windowSize, command.channelCount)

        queue(
            EnetCommand.VerifyConnect(
                header = systemHeader(EnetProtocol.COMMAND_VERIFY_CONNECT, acknowledged = true),
                outgoingPeerId = incomingPeerId,
                incomingSessionId = outgoingSessionId,
                outgoingSessionId = incomingSessionId,
                mtu = mtu,
                windowSize = windowSize,
                channelCount = channelCount,
                incomingBandwidth = 0,
                outgoingBandwidth = 0,
                packetThrottleInterval = command.packetThrottleInterval,
                packetThrottleAcceleration = command.packetThrottleAcceleration,
                packetThrottleDeceleration = command.packetThrottleDeceleration,
                connectId = command.connectId,
            ),
            reliable = true,
        )
    }

    // ---- Application traffic -------------------------------------------------------------------

    /**
     * Queues an application payload (spec §9.5 sends every kind of control message through here).
     *
     * @param channelId a channel id below [channelCount] — for GameStream,
     *   [EnetUnverifiedConstants.CHANNEL_GENERIC] or [EnetUnverifiedConstants.CHANNEL_URGENT].
     * @param delivery the guarantee wanted. [EnetDelivery.RELIABLE] fragments automatically;
     *   the other two do not, and an oversized payload is refused rather than silently truncated.
     * @return false when the payload was refused: a bad channel, a full queue, or an unreliable
     *   payload that does not fit one datagram. Never throws — a control message that cannot be
     *   sent is a runtime condition, not a programming error, and losing the stream over it would
     *   be worse than dropping it.
     */
    fun send(channelId: Int, payload: ByteArray, delivery: EnetDelivery): Boolean {
        if (state != EnetPeerState.CONNECTED) {
            ProtocolLog.d(EnetControlConstants.TAG, "send on channel $channelId dropped in state $state")
            return false
        }
        val channel = channels.getOrNull(channelId)
        if (channel == null) {
            ProtocolLog.w(EnetControlConstants.TAG, "send to unknown channel $channelId of $channelCount")
            return false
        }
        if (queuedBytes + payload.size > config.maximumQueuedSendBytes) {
            ProtocolLog.w(
                EnetControlConstants.TAG,
                "send of ${payload.size} B dropped; $queuedBytes B already queued",
            )
            return false
        }

        when (delivery) {
            EnetDelivery.RELIABLE -> {
                if (payload.size > fragmentLength) {
                    queueFragmented(channel, payload)
                } else {
                    queue(
                        EnetCommand.SendReliable(
                            header = EnetCommandHeader(
                                command = EnetProtocol.COMMAND_SEND_RELIABLE or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                                channelId = channelId,
                                reliableSequenceNumber = channel.nextOutgoingReliableSequenceNumber(),
                            ),
                            payload = payload,
                        ),
                        reliable = true,
                    )
                }
            }

            EnetDelivery.UNRELIABLE -> {
                if (payload.size > fragmentLength) {
                    ProtocolLog.w(
                        EnetControlConstants.TAG,
                        "unreliable payload of ${payload.size} B exceeds the $fragmentLength B " +
                            "fragment limit and this subset does not fragment unreliable sends",
                    )
                    return false
                }
                queue(
                    EnetCommand.SendUnreliable(
                        header = EnetCommandHeader(
                            command = EnetProtocol.COMMAND_SEND_UNRELIABLE,
                            channelId = channelId,
                            reliableSequenceNumber = channel.outgoingReliableSequenceNumber,
                        ),
                        unreliableSequenceNumber = channel.nextOutgoingUnreliableSequenceNumber(),
                        payload = payload,
                    ),
                    reliable = false,
                )
            }

            EnetDelivery.UNSEQUENCED -> {
                if (payload.size > fragmentLength) {
                    ProtocolLog.w(
                        EnetControlConstants.TAG,
                        "unsequenced payload of ${payload.size} B exceeds the $fragmentLength B limit",
                    )
                    return false
                }
                outgoingUnsequencedGroup = EnetProtocol.nextSequenceNumber(outgoingUnsequencedGroup)
                queue(
                    EnetCommand.SendUnsequenced(
                        header = EnetCommandHeader(
                            command = EnetProtocol.COMMAND_SEND_UNSEQUENCED or EnetProtocol.COMMAND_FLAG_UNSEQUENCED,
                            channelId = channelId,
                            reliableSequenceNumber = 0,
                        ),
                        unsequencedGroup = outgoingUnsequencedGroup,
                        payload = payload,
                    ),
                    reliable = false,
                )
            }
        }
        return true
    }

    /**
     * Queues the DISCONNECT of spec §9.7 step 3.
     *
     * Sent reliably when the connection is up, so that [service] can report when the remote has
     * acknowledged it and the caller knows the host is not left holding a stuck session. From any
     * other state there is nothing to acknowledge and the peer is simply dropped.
     */
    fun startDisconnect(data: Int) {
        if (state == EnetPeerState.DISCONNECTED || state == EnetPeerState.DISCONNECTING) return
        if (state != EnetPeerState.CONNECTED) {
            state = EnetPeerState.DISCONNECTED
            resetQueues()
            return
        }
        state = EnetPeerState.DISCONNECTING
        queue(
            EnetCommand.Disconnect(
                header = systemHeader(EnetProtocol.COMMAND_DISCONNECT, acknowledged = true),
                data = data,
            ),
            reliable = true,
        )
    }

    // ---- Inbound -------------------------------------------------------------------------------

    /**
     * Processes one command from a received datagram.
     *
     * @param sentTime the 16-bit send time from the datagram's protocol header, echoed back in the
     *   acknowledgement so the remote can measure the round trip.
     * @param events receives anything the application needs to hear about.
     */
    fun handleCommand(
        command: EnetCommand,
        sentTime: Int,
        nowMs: Int,
        events: MutableList<EnetEvent>,
    ) {
        when (command) {
            is EnetCommand.Acknowledge -> handleAcknowledge(command, nowMs, events)
            is EnetCommand.VerifyConnect -> handleVerifyConnect(command, events)
            is EnetCommand.Disconnect -> handleDisconnect(command)
            is EnetCommand.Ping -> Unit
            is EnetCommand.SendReliable -> handleSendReliable(command, events)
            is EnetCommand.SendFragment -> handleSendFragment(command, events)
            is EnetCommand.SendUnreliable -> handleSendUnreliable(command, events)
            is EnetCommand.SendUnsequenced -> events.add(
                EnetEvent.Received(command.header.channelId, command.payload),
            )

            is EnetCommand.BandwidthLimit,
            is EnetCommand.ThrottleConfigure,
            -> ProtocolLog.d(
                EnetControlConstants.TAG,
                "ignoring ENet command ${command.header.commandId} (no throttling in this subset)",
            )

            is EnetCommand.Connect -> ProtocolLog.d(
                EnetControlConstants.TAG,
                "ignoring CONNECT on an established peer",
            )
        }

        // CONNECT is the one acknowledged command that gets no ACKNOWLEDGE: VERIFY_CONNECT *is* its
        // acknowledgement. Answering a retransmitted CONNECT with a bare ACK would retire the
        // sender's in-flight CONNECT while it still had no VERIFY_CONNECT, and the handshake would
        // then sit silent until it timed out — a failure that only appears when a datagram is lost.
        val acknowledgeable = command.header.requiresAcknowledgement &&
            command.header.commandId != EnetProtocol.COMMAND_CONNECT
        if (acknowledgeable && state != EnetPeerState.DISCONNECTED) {
            pendingAcks.add(
                PendingAck(
                    channelId = command.header.channelId,
                    sequenceNumber = command.header.reliableSequenceNumber,
                    sentTime = sentTime,
                ),
            )
        }
    }

    private fun handleAcknowledge(command: EnetCommand.Acknowledge, nowMs: Int, events: MutableList<EnetEvent>) {
        hasEarliestTimeout = false
        earliestTimeoutMs = 0

        val acknowledged = removeSentReliable(
            command.header.channelId,
            command.receivedReliableSequenceNumber,
        ) ?: return
        updateRoundTripTime(reconstructSentTime(command.receivedSentTime, nowMs), nowMs)

        val acknowledgedId = acknowledged.header.commandId
        when (state) {
            EnetPeerState.ACKNOWLEDGING_CONNECT ->
                if (acknowledgedId == EnetProtocol.COMMAND_VERIFY_CONNECT) {
                    state = EnetPeerState.CONNECTED
                    events.add(EnetEvent.Connected(this))
                }

            EnetPeerState.DISCONNECTING ->
                if (acknowledgedId == EnetProtocol.COMMAND_DISCONNECT) {
                    finish(events, data = 0, timedOut = false)
                }

            else -> Unit
        }
    }

    private fun handleVerifyConnect(command: EnetCommand.VerifyConnect, events: MutableList<EnetEvent>) {
        if (state != EnetPeerState.CONNECTING) return
        if (command.connectId != connectId) {
            ProtocolLog.w(
                EnetControlConstants.TAG,
                "VERIFY_CONNECT for a connect id we did not send; ignoring",
            )
            return
        }
        if (command.channelCount < EnetProtocol.MINIMUM_CHANNEL_COUNT) return

        // VERIFY_CONNECT is itself the acknowledgement of our CONNECT, which always carries
        // peer-level sequence number 1 on the system channel.
        removeSentReliable(EnetProtocol.CHANNEL_ID_SYSTEM, 1)

        outgoingPeerId = command.outgoingPeerId and EnetProtocol.MAXIMUM_PEER_ID
        incomingSessionId = command.incomingSessionId and EnetProtocol.MAXIMUM_SESSION_ID
        outgoingSessionId = command.outgoingSessionId and EnetProtocol.MAXIMUM_SESSION_ID
        negotiate(command.mtu, command.windowSize, command.channelCount)

        state = EnetPeerState.CONNECTED
        events.add(EnetEvent.Connected(this))
    }

    private fun handleDisconnect(command: EnetCommand.Disconnect) {
        if (state == EnetPeerState.DISCONNECTED) return
        disconnectData = command.data
        resetQueues()
        // The acknowledgement this command asks for is queued by handleCommand; service() finishes
        // the teardown once that acknowledgement has actually gone out (spec §9.7 step 3).
        state = if (command.header.requiresAcknowledgement) {
            EnetPeerState.ACKNOWLEDGING_DISCONNECT
        } else {
            EnetPeerState.DISCONNECTED
        }
    }

    private fun handleSendReliable(command: EnetCommand.SendReliable, events: MutableList<EnetEvent>) {
        val channel = channels.getOrNull(command.header.channelId) ?: return
        val delivered = ArrayList<ByteArray>()
        channel.receiveReliable(command.header.reliableSequenceNumber, command.payload, delivered)
        for (payload in delivered) events.add(EnetEvent.Received(command.header.channelId, payload))
    }

    private fun handleSendFragment(command: EnetCommand.SendFragment, events: MutableList<EnetEvent>) {
        val channel = channels.getOrNull(command.header.channelId) ?: return
        if (command.unreliable) {
            // Nothing in spec §9 sends one, but a host that did would otherwise look like silence.
            ProtocolLog.w(EnetControlConstants.TAG, "unreliable fragment received; not reassembled")
            return
        }
        val delivered = ArrayList<ByteArray>()
        channel.receiveFragment(command, config.maximumPacketSize, delivered)
        for (payload in delivered) events.add(EnetEvent.Received(command.header.channelId, payload))
    }

    private fun handleSendUnreliable(command: EnetCommand.SendUnreliable, events: MutableList<EnetEvent>) {
        val channel = channels.getOrNull(command.header.channelId) ?: return
        val payload = channel.receiveUnreliable(
            command.header.reliableSequenceNumber,
            command.unreliableSequenceNumber,
            command.payload,
        ) ?: return
        events.add(EnetEvent.Received(command.header.channelId, payload))
    }

    // ---- Service -------------------------------------------------------------------------------

    /**
     * Runs the peer's timers and produces the datagrams to put on the wire.
     *
     * Called once per pass of [EnetHost]'s service loop and again after every received datagram, so
     * that an acknowledgement rides out with whatever else is queued rather than costing a datagram
     * of its own.
     *
     * @param events receives connection, disconnection and delivery events.
     * @return zero or more encoded datagrams, each at most [mtu] bytes, to send to [address].
     */
    fun service(nowMs: Int, events: MutableList<EnetEvent>): List<ByteArray> {
        if (state == EnetPeerState.DISCONNECTED) return emptyList()

        if (state == EnetPeerState.CONNECTING && timeDifference(nowMs, connectStartedMs) >= config.connectTimeoutMs) {
            ProtocolLog.w(
                EnetControlConstants.TAG,
                "ENet handshake timed out after ${config.connectTimeoutMs} ms (spec 01 §9.1)",
            )
            state = EnetPeerState.DISCONNECTED
            resetQueues()
            if (!terminated) {
                terminated = true
                events.add(EnetEvent.ConnectFailed("no VERIFY_CONNECT within ${config.connectTimeoutMs} ms"))
            }
            return emptyList()
        }

        if (checkTimeouts(nowMs, events)) return emptyList()

        if (state == EnetPeerState.CONNECTED && !timeLess(nowMs, nextPingMs)) {
            nextPingMs = nowMs + config.pingIntervalMs
            queue(
                EnetCommand.Ping(systemHeader(EnetProtocol.COMMAND_PING, acknowledged = true)),
                reliable = true,
            )
        }

        val datagrams = buildDatagrams(nowMs)

        if (state == EnetPeerState.ACKNOWLEDGING_DISCONNECT && pendingAcks.isEmpty()) {
            // The acknowledgement the remote asked for is on the wire; we are done.
            finish(events, disconnectData, timedOut = false)
        }
        return datagrams
    }

    /**
     * Retransmits overdue reliable commands, or declares the peer dead.
     *
     * ENet's ladder, unchanged: the first retransmission timeout is `rtt + 4 * variance`, it doubles
     * on every retransmission, and the peer is given up on once the oldest overdue command has been
     * outstanding for [EnetConfig.timeoutMaximumMs], or for [EnetConfig.timeoutMinimumMs] with the
     * timeout already at its ceiling. Doubling without a ceiling is how a lossy link turns into a
     * connection that is neither alive nor reported dead.
     *
     * @return true when the peer was declared dead and nothing more should be sent.
     */
    private fun checkTimeouts(nowMs: Int, events: MutableList<EnetEvent>): Boolean {
        if (sentReliable.isEmpty()) return false
        val expired = ArrayList<OutgoingCommand>()
        val iterator = sentReliable.iterator()
        while (iterator.hasNext()) {
            val sent = iterator.next()
            if (timeDifference(nowMs, sent.sentTimeMs) < sent.roundTripTimeoutMs) continue

            if (!hasEarliestTimeout || timeLess(sent.sentTimeMs, earliestTimeoutMs)) {
                earliestTimeoutMs = sent.sentTimeMs
                hasEarliestTimeout = true
            }
            val outstanding = timeDifference(nowMs, earliestTimeoutMs)
            val exhausted = sent.roundTripTimeoutMs >= sent.roundTripTimeoutLimitMs
            if (outstanding >= config.timeoutMaximumMs || (exhausted && outstanding >= config.timeoutMinimumMs)) {
                ProtocolLog.w(
                    EnetControlConstants.TAG,
                    "peer stopped acknowledging after $outstanding ms; declaring the link dead",
                )
                finish(events, data = 0, timedOut = true)
                return true
            }

            sent.roundTripTimeoutMs *= 2
            iterator.remove()
            expired.add(sent)
        }
        if (expired.isNotEmpty()) {
            // Back to the front of the queue, keeping their relative order: a retransmission that
            // overtakes a command sent after it would stall the receiver's reorder buffer.
            outgoing.addAll(0, expired)
        }
        return false
    }

    /** Packs acknowledgements and queued commands into datagrams of at most [mtu] bytes. */
    private fun buildDatagrams(nowMs: Int): List<ByteArray> {
        if (pendingAcks.isEmpty() && outgoing.isEmpty()) return emptyList()

        val datagrams = ArrayList<ByteArray>()
        val header = EnetProtocolHeader(
            peerId = outgoingPeerId,
            sessionId = outgoingSessionId,
            flags = EnetProtocol.HEADER_FLAG_SENT_TIME,
            sentTime = nowMs and EnetProtocol.SEQUENCE_MASK,
        )

        while (pendingAcks.isNotEmpty() || outgoing.isNotEmpty()) {
            val commands = ArrayList<EnetCommand>()
            var size = header.encodedSize

            while (pendingAcks.isNotEmpty() && commands.size < EnetProtocol.MAXIMUM_PACKET_COMMANDS) {
                if (size + EnetProtocol.ACKNOWLEDGE_SIZE > mtu) break
                val ack = pendingAcks.removeAt(0)
                commands.add(
                    EnetCommand.Acknowledge(
                        header = EnetCommandHeader(
                            command = EnetProtocol.COMMAND_ACKNOWLEDGE,
                            channelId = ack.channelId,
                            reliableSequenceNumber = ack.sequenceNumber,
                        ),
                        receivedReliableSequenceNumber = ack.sequenceNumber,
                        receivedSentTime = ack.sentTime,
                    ),
                )
                size += EnetProtocol.ACKNOWLEDGE_SIZE
            }

            while (outgoing.isNotEmpty() && commands.size < EnetProtocol.MAXIMUM_PACKET_COMMANDS) {
                val next = outgoing[0]
                val commandSize = next.command.encodedSize
                // An empty datagram takes the command whatever its size: refusing would spin.
                if (commands.isNotEmpty() && size + commandSize > mtu) break
                outgoing.removeAt(0)
                commands.add(next.command)
                size += commandSize
                if (next.reliable) {
                    if (next.sendAttempts == 0) {
                        next.roundTripTimeoutMs = roundTripTimeMs + 4 * roundTripTimeVarianceMs
                        next.roundTripTimeoutLimitMs = config.timeoutLimit * next.roundTripTimeoutMs
                    }
                    next.sentTimeMs = nowMs
                    next.sendAttempts++
                    sentReliable.add(next)
                } else {
                    queuedBytes -= next.payloadSize
                }
            }

            if (commands.isEmpty()) break
            datagrams.add(EnetPacketCodec.encode(header, commands))
        }
        return datagrams
    }

    // ---- Internals -----------------------------------------------------------------------------

    /** Splits [payload] into MTU-sized reliable fragments, all queued back to back. */
    private fun queueFragmented(channel: EnetChannel, payload: ByteArray) {
        val length = fragmentLength
        val fragmentCount = (payload.size + length - 1) / length
        val startSequenceNumber = EnetProtocol.nextSequenceNumber(channel.outgoingReliableSequenceNumber)
        var offset = 0
        var fragmentNumber = 0
        while (offset < payload.size) {
            val size = minOf(length, payload.size - offset)
            queue(
                EnetCommand.SendFragment(
                    header = EnetCommandHeader(
                        command = EnetProtocol.COMMAND_SEND_FRAGMENT or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                        channelId = channel.id,
                        reliableSequenceNumber = channel.nextOutgoingReliableSequenceNumber(),
                    ),
                    startSequenceNumber = startSequenceNumber,
                    fragmentCount = fragmentCount,
                    fragmentNumber = fragmentNumber,
                    totalLength = payload.size,
                    fragmentOffset = offset,
                    payload = payload.copyOfRange(offset, offset + size),
                ),
                reliable = true,
            )
            offset += size
            fragmentNumber++
        }
    }

    /** Appends a command to the send queue and accounts for its payload. */
    private fun queue(command: EnetCommand, reliable: Boolean) {
        val entry = OutgoingCommand(command, reliable)
        queuedBytes += entry.payloadSize
        outgoing.add(entry)
    }

    /** Builds a header for one of the peer-level commands, taking the next peer-level sequence number. */
    private fun systemHeader(commandId: Int, acknowledged: Boolean): EnetCommandHeader {
        outgoingReliableSequenceNumber = EnetProtocol.nextSequenceNumber(outgoingReliableSequenceNumber)
        val flags = if (acknowledged) EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE else 0
        return EnetCommandHeader(
            command = commandId or flags,
            channelId = EnetProtocol.CHANNEL_ID_SYSTEM,
            reliableSequenceNumber = outgoingReliableSequenceNumber,
        )
    }

    /**
     * Removes the in-flight reliable command an acknowledgement refers to.
     *
     * @return the command, or `null` when nothing matches — a duplicate acknowledgement, which is
     *   ordinary on a lossy link and means only that our retransmission raced the original ACK.
     */
    private fun removeSentReliable(channelId: Int, sequenceNumber: Int): EnetCommand? {
        for (index in sentReliable.indices) {
            val candidate = sentReliable[index]
            if (candidate.command.header.channelId != channelId) continue
            if (candidate.command.header.reliableSequenceNumber != sequenceNumber) continue
            sentReliable.removeAt(index)
            queuedBytes -= candidate.payloadSize
            return candidate.command
        }
        return null
    }

    /**
     * Rebuilds a full 32-bit send time from the 16 bits an acknowledgement echoes.
     *
     * The high half comes from the current clock; when the low half is in the *later* half of the
     * range than the clock's, the send happened before the last 16-bit wrap and a whole 0x10000 has
     * to come off. Getting this wrong yields a 65-second round-trip estimate roughly once a minute.
     */
    private fun reconstructSentTime(receivedSentTime: Int, nowMs: Int): Int {
        var sent = (nowMs and 0xFFFF.inv()) or (receivedSentTime and 0xFFFF)
        if ((sent and 0x8000) > (nowMs and 0x8000)) sent -= 0x10000
        return sent
    }

    /** ENet's round-trip estimator: 1/8 gain on the mean, 1/4 on the variance. */
    private fun updateRoundTripTime(sentAtMs: Int, nowMs: Int) {
        if (timeLess(nowMs, sentAtMs)) return
        val sample = timeDifference(nowMs, sentAtMs)
        roundTripTimeVarianceMs -= roundTripTimeVarianceMs / 4
        if (sample >= roundTripTimeMs) {
            roundTripTimeMs += (sample - roundTripTimeMs) / 8
            roundTripTimeVarianceMs += (sample - roundTripTimeMs) / 4
        } else {
            roundTripTimeMs -= (roundTripTimeMs - sample) / 8
            roundTripTimeVarianceMs += (roundTripTimeMs - sample) / 4
        }
        if (roundTripTimeMs < 1) roundTripTimeMs = 1
    }

    /** Applies the smaller of the two sides' MTU, window and channel count. */
    private fun negotiate(remoteMtu: Int, remoteWindowSize: Int, remoteChannelCount: Int) {
        val clampedMtu = remoteMtu.coerceIn(EnetProtocol.MINIMUM_MTU, EnetProtocol.MAXIMUM_MTU)
        if (clampedMtu < mtu) mtu = clampedMtu

        val clampedWindow =
            remoteWindowSize.coerceIn(EnetProtocol.MINIMUM_WINDOW_SIZE, EnetProtocol.MAXIMUM_WINDOW_SIZE)
        if (clampedWindow < windowSize) windowSize = clampedWindow

        if (remoteChannelCount in EnetProtocol.MINIMUM_CHANNEL_COUNT until channelCount) {
            channelCount = remoteChannelCount
        }
    }

    /**
     * ENet's session-id advance: take the peer's proposal (or our own value when it says "unset"),
     * step it by one, and step again if that would collide with the id already in use.
     */
    private fun advanceSessionId(proposed: Int, fallback: Int, avoid: Int): Int {
        val base = if (proposed == EnetProtocol.SESSION_ID_UNSET) fallback else proposed
        var next = (base + 1) and EnetProtocol.MAXIMUM_SESSION_ID
        if (next == avoid) next = (next + 1) and EnetProtocol.MAXIMUM_SESSION_ID
        return next
    }

    /** Moves to [EnetPeerState.DISCONNECTED] and reports it once. */
    private fun finish(events: MutableList<EnetEvent>, data: Int, timedOut: Boolean) {
        state = EnetPeerState.DISCONNECTED
        resetQueues()
        pendingAcks.clear()
        if (terminated) return
        terminated = true
        events.add(EnetEvent.Disconnected(this, data, timedOut))
    }

    /** Drops everything queued, in flight, and held for reordering. */
    private fun resetQueues() {
        outgoing.clear()
        sentReliable.clear()
        queuedBytes = 0
        for (channel in channels) channel.reset()
    }

    /**
     * True when [a] precedes [b] on a wrapping 32-bit millisecond clock.
     *
     * Kotlin's `Int` wraps in two's complement exactly as ENet's `enet_uint32` does, so the
     * difference of two times taken less than 2^31 ms (25 days) apart has the right sign.
     */
    private fun timeLess(a: Int, b: Int): Boolean = (a - b) < 0

    /** Absolute distance between two times on the same wrapping clock. */
    private fun timeDifference(a: Int, b: Int): Int {
        val delta = a - b
        return if (delta < 0) -delta else delta
    }

    /**
     * A command waiting to go out, and the retransmission bookkeeping that goes with it.
     *
     * @property reliable whether it must be retransmitted until acknowledged.
     */
    private class OutgoingCommand(
        val command: EnetCommand,
        val reliable: Boolean,
    ) {
        /** When it was last handed to the socket. */
        var sentTimeMs: Int = 0

        /** Current retransmission timeout; doubles on every retransmission. */
        var roundTripTimeoutMs: Int = 0

        /** Ceiling past which the peer is a candidate for being declared dead. */
        var roundTripTimeoutLimitMs: Int = 0

        /** How many times it has been put on the wire. Zero means "not yet". */
        var sendAttempts: Int = 0

        /** Payload bytes it holds, for the queue accounting. */
        val payloadSize: Int
            get() = when (command) {
                is EnetCommand.SendReliable -> command.payload.size
                is EnetCommand.SendUnreliable -> command.payload.size
                is EnetCommand.SendUnsequenced -> command.payload.size
                is EnetCommand.SendFragment -> command.payload.size
                else -> 0
            }
    }

    /** An acknowledgement owed to the remote. */
    private class PendingAck(
        val channelId: Int,
        val sequenceNumber: Int,
        val sentTime: Int,
    )
}
