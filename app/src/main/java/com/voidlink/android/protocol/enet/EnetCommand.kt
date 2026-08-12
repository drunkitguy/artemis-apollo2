package com.voidlink.android.protocol.enet

/**
 * The four-byte header every ENet command starts with (`docs/01-PROTOCOL.md` §9.1).
 *
 * ```
 * offset 0 : uint8  command                 // command id in the low 4 bits, flags in 0x80 / 0x40
 * offset 1 : uint8  channelId               // 0xFF for the connection-management commands
 * offset 2 : uint16 reliableSequenceNumber  BIG-ENDIAN
 * ```
 *
 * @property command the raw command byte, flags included.
 * @property channelId the channel this command belongs to, or [EnetProtocol.CHANNEL_ID_SYSTEM].
 * @property reliableSequenceNumber the sender's per-channel (or per-peer) reliable counter. Zero
 *   for commands that are not reliable.
 */
data class EnetCommandHeader(
    val command: Int,
    val channelId: Int,
    val reliableSequenceNumber: Int,
) {
    /** The command id with the flag bits removed — one of `EnetProtocol.COMMAND_*`. */
    val commandId: Int get() = command and EnetProtocol.COMMAND_MASK

    /** True when the receiver must answer with an [EnetCommand.Acknowledge]. */
    val requiresAcknowledgement: Boolean
        get() = (command and EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE) != 0

    /** True when this command carries no sequencing guarantee. */
    val isUnsequenced: Boolean
        get() = (command and EnetProtocol.COMMAND_FLAG_UNSEQUENCED) != 0
}

/**
 * One ENet protocol command, as it appears inside a datagram (`docs/01-PROTOCOL.md` §9.1).
 *
 * A datagram is a protocol header followed by one or more of these packed back-to-back with no
 * padding (spec §0.2). Every field is big-endian; see [EnetBytes] for why that is stated in the
 * accessor names rather than left to a `ByteBuffer` default.
 *
 * Encoding and decoding are pure functions over [ByteArray], which is what makes the hex-fixture
 * tests in `EnetCommandCodecTest` possible without a socket.
 */
sealed class EnetCommand {

    /** The command header, shared by every command type. */
    abstract val header: EnetCommandHeader

    /** Total encoded length in bytes, including the header and any trailing payload. */
    abstract val encodedSize: Int

    /** Writes this command at [offset] in [dst]. The caller guarantees `encodedSize` bytes fit. */
    abstract fun encodeInto(dst: ByteArray, offset: Int)

    /** Convenience wrapper around [encodeInto] that allocates a right-sized array. */
    fun encode(): ByteArray {
        val out = ByteArray(encodedSize)
        encodeInto(out, 0)
        return out
    }

    /** Writes the four-byte command header at [offset]. */
    protected fun encodeHeaderInto(dst: ByteArray, offset: Int) {
        EnetBytes.putU8(dst, offset, header.command)
        EnetBytes.putU8(dst, offset + 1, header.channelId)
        EnetBytes.putU16Be(dst, offset + 2, header.reliableSequenceNumber)
    }

    /**
     * Acknowledges a single reliable command.
     *
     * The acknowledged sequence number appears twice — once in the command header and once in the
     * body — which is ENet's own redundancy, faithfully reproduced. [receivedSentTime] echoes the
     * 16-bit send time from the acknowledged datagram's protocol header and is the only input to
     * the round-trip time estimate (spec §9.5: the periodic ping "must be sent reliably because
     * the RTT estimate is derived from the ACK").
     */
    class Acknowledge(
        override val header: EnetCommandHeader,
        val receivedReliableSequenceNumber: Int,
        val receivedSentTime: Int,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.ACKNOWLEDGE_SIZE

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU16Be(dst, offset + 4, receivedReliableSequenceNumber)
            EnetBytes.putU16Be(dst, offset + 6, receivedSentTime)
        }
    }

    /**
     * The connection request (spec §9.1: "`ENET_PROTOCOL_COMMAND_CONNECT` with our peer id, window
     * size, channel count, MTU, and the 32-bit connect data from `X-SS-Connect-Data`").
     *
     * @property outgoingPeerId the id we want the host to stamp on datagrams it sends us.
     * @property connectId an opaque 32-bit nonce the host echoes in [VerifyConnect]; it is the only
     *   thing that distinguishes our handshake from a stale one on the same address and port. ENet
     *   never byte-swaps it, so the encoding order here is arbitrary as long as it round-trips.
     * @property data the connect data from the RTSP `X-SS-Connect-Data` header (spec §6.3), or 0.
     */
    class Connect(
        override val header: EnetCommandHeader,
        val outgoingPeerId: Int,
        val incomingSessionId: Int,
        val outgoingSessionId: Int,
        val mtu: Int,
        val windowSize: Int,
        val channelCount: Int,
        val incomingBandwidth: Int,
        val outgoingBandwidth: Int,
        val packetThrottleInterval: Int,
        val packetThrottleAcceleration: Int,
        val packetThrottleDeceleration: Int,
        val connectId: Int,
        val data: Int,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.CONNECT_SIZE

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU16Be(dst, offset + 4, outgoingPeerId)
            EnetBytes.putU8(dst, offset + 6, incomingSessionId)
            EnetBytes.putU8(dst, offset + 7, outgoingSessionId)
            EnetBytes.putU32Be(dst, offset + 8, mtu)
            EnetBytes.putU32Be(dst, offset + 12, windowSize)
            EnetBytes.putU32Be(dst, offset + 16, channelCount)
            EnetBytes.putU32Be(dst, offset + 20, incomingBandwidth)
            EnetBytes.putU32Be(dst, offset + 24, outgoingBandwidth)
            EnetBytes.putU32Be(dst, offset + 28, packetThrottleInterval)
            EnetBytes.putU32Be(dst, offset + 32, packetThrottleAcceleration)
            EnetBytes.putU32Be(dst, offset + 36, packetThrottleDeceleration)
            EnetBytes.putU32Be(dst, offset + 40, connectId)
            EnetBytes.putU32Be(dst, offset + 44, data)
        }
    }

    /**
     * The host's acceptance of a [Connect].
     *
     * Identical to [Connect] minus the trailing connect-data word. The throttle parameters and
     * [connectId] are echoed and must match what we sent, which is how a reply to somebody else's
     * handshake is rejected.
     */
    class VerifyConnect(
        override val header: EnetCommandHeader,
        val outgoingPeerId: Int,
        val incomingSessionId: Int,
        val outgoingSessionId: Int,
        val mtu: Int,
        val windowSize: Int,
        val channelCount: Int,
        val incomingBandwidth: Int,
        val outgoingBandwidth: Int,
        val packetThrottleInterval: Int,
        val packetThrottleAcceleration: Int,
        val packetThrottleDeceleration: Int,
        val connectId: Int,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.VERIFY_CONNECT_SIZE

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU16Be(dst, offset + 4, outgoingPeerId)
            EnetBytes.putU8(dst, offset + 6, incomingSessionId)
            EnetBytes.putU8(dst, offset + 7, outgoingSessionId)
            EnetBytes.putU32Be(dst, offset + 8, mtu)
            EnetBytes.putU32Be(dst, offset + 12, windowSize)
            EnetBytes.putU32Be(dst, offset + 16, channelCount)
            EnetBytes.putU32Be(dst, offset + 20, incomingBandwidth)
            EnetBytes.putU32Be(dst, offset + 24, outgoingBandwidth)
            EnetBytes.putU32Be(dst, offset + 28, packetThrottleInterval)
            EnetBytes.putU32Be(dst, offset + 32, packetThrottleAcceleration)
            EnetBytes.putU32Be(dst, offset + 36, packetThrottleDeceleration)
            EnetBytes.putU32Be(dst, offset + 40, connectId)
        }
    }

    /**
     * Ends the session (spec §9.7 step 3).
     *
     * @property data an application-defined 32-bit word; GameStream does not use it, so we send 0.
     */
    class Disconnect(
        override val header: EnetCommandHeader,
        val data: Int,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.DISCONNECT_SIZE

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU32Be(dst, offset + 4, data)
        }
    }

    /**
     * ENet's own keep-alive, sent reliably so that its acknowledgement refreshes the RTT estimate.
     *
     * This is *not* the GameStream periodic ping of spec §9.5 (control message type `0x0200`),
     * which is a payload carried on a normal channel by the layer above this one. Both exist and
     * both are needed: this one keeps the ENet peer alive, that one keeps the host's session alive.
     */
    class Ping(
        override val header: EnetCommandHeader,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.PING_SIZE

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
        }
    }

    /** A reliable ordered payload on [EnetCommandHeader.channelId]. */
    class SendReliable(
        override val header: EnetCommandHeader,
        val payload: ByteArray,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.SEND_RELIABLE_SIZE + payload.size

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU16Be(dst, offset + 4, payload.size)
            payload.copyInto(dst, offset + EnetProtocol.SEND_RELIABLE_SIZE)
        }
    }

    /**
     * An unreliable sequenced payload.
     *
     * [reliableSequenceNumber][EnetCommandHeader.reliableSequenceNumber] carries the channel's
     * *current* reliable counter rather than a new one: it marks which reliable epoch this packet
     * belongs to, so that an unreliable packet stranded behind a reliable one is discarded instead
     * of delivered out of order.
     */
    class SendUnreliable(
        override val header: EnetCommandHeader,
        val unreliableSequenceNumber: Int,
        val payload: ByteArray,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.SEND_UNRELIABLE_SIZE + payload.size

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU16Be(dst, offset + 4, unreliableSequenceNumber)
            EnetBytes.putU16Be(dst, offset + 6, payload.size)
            payload.copyInto(dst, offset + EnetProtocol.SEND_UNRELIABLE_SIZE)
        }
    }

    /**
     * An unreliable unsequenced payload — delivered the moment it arrives, in whatever order.
     *
     * This is what spec §9.5 asks for when it says the Sunshine per-frame FEC status report
     * (`type 0x5502`) is "sent unsequenced/unreliable": a stale FEC report is worse than no report,
     * so there is nothing to gain from holding it behind anything.
     */
    class SendUnsequenced(
        override val header: EnetCommandHeader,
        val unsequencedGroup: Int,
        val payload: ByteArray,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.SEND_UNSEQUENCED_SIZE + payload.size

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU16Be(dst, offset + 4, unsequencedGroup)
            EnetBytes.putU16Be(dst, offset + 6, payload.size)
            payload.copyInto(dst, offset + EnetProtocol.SEND_UNSEQUENCED_SIZE)
        }
    }

    /**
     * One fragment of a payload too large for the MTU.
     *
     * Each fragment is an independently sequenced reliable command, so a fragmented packet consumes
     * [fragmentCount] consecutive sequence numbers beginning at [startSequenceNumber]. The receiver
     * reassembles by offset, not by arrival order, and the reassembled packet takes its place in
     * the ordered stream at [startSequenceNumber] — which is why a half-arrived fragment blocks
     * delivery of everything behind it rather than being skipped.
     *
     * @property unreliable true when this decodes an unreliable fragment
     *   ([EnetProtocol.COMMAND_SEND_UNRELIABLE_FRAGMENT]). We never send those; see [EnetPeer.send].
     */
    class SendFragment(
        override val header: EnetCommandHeader,
        val startSequenceNumber: Int,
        val fragmentCount: Int,
        val fragmentNumber: Int,
        val totalLength: Int,
        val fragmentOffset: Int,
        val payload: ByteArray,
        val unreliable: Boolean = false,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.SEND_FRAGMENT_SIZE + payload.size

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU16Be(dst, offset + 4, startSequenceNumber)
            EnetBytes.putU16Be(dst, offset + 6, payload.size)
            EnetBytes.putU32Be(dst, offset + 8, fragmentCount)
            EnetBytes.putU32Be(dst, offset + 12, fragmentNumber)
            EnetBytes.putU32Be(dst, offset + 16, totalLength)
            EnetBytes.putU32Be(dst, offset + 20, fragmentOffset)
            payload.copyInto(dst, offset + EnetProtocol.SEND_FRAGMENT_SIZE)
        }
    }

    /** Bandwidth limits announced by the peer. Decoded so the datagram stays in sync; ignored. */
    class BandwidthLimit(
        override val header: EnetCommandHeader,
        val incomingBandwidth: Int,
        val outgoingBandwidth: Int,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.BANDWIDTH_LIMIT_SIZE

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU32Be(dst, offset + 4, incomingBandwidth)
            EnetBytes.putU32Be(dst, offset + 8, outgoingBandwidth)
        }
    }

    /** Throttle parameters announced by the peer. Decoded so the datagram stays in sync; ignored. */
    class ThrottleConfigure(
        override val header: EnetCommandHeader,
        val packetThrottleInterval: Int,
        val packetThrottleAcceleration: Int,
        val packetThrottleDeceleration: Int,
    ) : EnetCommand() {
        override val encodedSize: Int get() = EnetProtocol.THROTTLE_CONFIGURE_SIZE

        override fun encodeInto(dst: ByteArray, offset: Int) {
            encodeHeaderInto(dst, offset)
            EnetBytes.putU32Be(dst, offset + 4, packetThrottleInterval)
            EnetBytes.putU32Be(dst, offset + 8, packetThrottleAcceleration)
            EnetBytes.putU32Be(dst, offset + 12, packetThrottleDeceleration)
        }
    }

    companion object {

        /**
         * Decodes one command from `src[offset until end]`.
         *
         * @return the command, or `null` when the bytes are truncated, the command id is unknown,
         *   or a length field points outside the datagram. A malformed datagram is an ordinary
         *   event on a UDP socket — anyone can send us one — so this reports rather than throws,
         *   and the caller abandons the remainder of the datagram.
         */
        fun decode(src: ByteArray, offset: Int, end: Int): EnetCommand? {
            if (offset < 0 || end > src.size || end - offset < EnetProtocol.COMMAND_HEADER_SIZE) {
                return null
            }
            val commandByte = EnetBytes.getU8(src, offset)
            val commandId = commandByte and EnetProtocol.COMMAND_MASK
            if (commandId <= EnetProtocol.COMMAND_NONE || commandId >= EnetProtocol.COMMAND_COUNT) {
                return null
            }
            val structSize = EnetProtocol.COMMAND_SIZES[commandId]
            if (structSize == 0 || end - offset < structSize) return null

            val header = EnetCommandHeader(
                command = commandByte,
                channelId = EnetBytes.getU8(src, offset + 1),
                reliableSequenceNumber = EnetBytes.getU16Be(src, offset + 2),
            )

            return when (commandId) {
                EnetProtocol.COMMAND_ACKNOWLEDGE -> Acknowledge(
                    header = header,
                    receivedReliableSequenceNumber = EnetBytes.getU16Be(src, offset + 4),
                    receivedSentTime = EnetBytes.getU16Be(src, offset + 6),
                )

                EnetProtocol.COMMAND_CONNECT -> Connect(
                    header = header,
                    outgoingPeerId = EnetBytes.getU16Be(src, offset + 4),
                    incomingSessionId = EnetBytes.getU8(src, offset + 6),
                    outgoingSessionId = EnetBytes.getU8(src, offset + 7),
                    mtu = EnetBytes.getU32Be(src, offset + 8),
                    windowSize = EnetBytes.getU32Be(src, offset + 12),
                    channelCount = EnetBytes.getU32Be(src, offset + 16),
                    incomingBandwidth = EnetBytes.getU32Be(src, offset + 20),
                    outgoingBandwidth = EnetBytes.getU32Be(src, offset + 24),
                    packetThrottleInterval = EnetBytes.getU32Be(src, offset + 28),
                    packetThrottleAcceleration = EnetBytes.getU32Be(src, offset + 32),
                    packetThrottleDeceleration = EnetBytes.getU32Be(src, offset + 36),
                    connectId = EnetBytes.getU32Be(src, offset + 40),
                    data = EnetBytes.getU32Be(src, offset + 44),
                )

                EnetProtocol.COMMAND_VERIFY_CONNECT -> VerifyConnect(
                    header = header,
                    outgoingPeerId = EnetBytes.getU16Be(src, offset + 4),
                    incomingSessionId = EnetBytes.getU8(src, offset + 6),
                    outgoingSessionId = EnetBytes.getU8(src, offset + 7),
                    mtu = EnetBytes.getU32Be(src, offset + 8),
                    windowSize = EnetBytes.getU32Be(src, offset + 12),
                    channelCount = EnetBytes.getU32Be(src, offset + 16),
                    incomingBandwidth = EnetBytes.getU32Be(src, offset + 20),
                    outgoingBandwidth = EnetBytes.getU32Be(src, offset + 24),
                    packetThrottleInterval = EnetBytes.getU32Be(src, offset + 28),
                    packetThrottleAcceleration = EnetBytes.getU32Be(src, offset + 32),
                    packetThrottleDeceleration = EnetBytes.getU32Be(src, offset + 36),
                    connectId = EnetBytes.getU32Be(src, offset + 40),
                )

                EnetProtocol.COMMAND_DISCONNECT -> Disconnect(
                    header = header,
                    data = EnetBytes.getU32Be(src, offset + 4),
                )

                EnetProtocol.COMMAND_PING -> Ping(header)

                EnetProtocol.COMMAND_SEND_RELIABLE -> {
                    val payload = readPayload(src, offset + structSize, end, EnetBytes.getU16Be(src, offset + 4))
                        ?: return null
                    SendReliable(header, payload)
                }

                EnetProtocol.COMMAND_SEND_UNRELIABLE -> {
                    val payload = readPayload(src, offset + structSize, end, EnetBytes.getU16Be(src, offset + 6))
                        ?: return null
                    SendUnreliable(header, EnetBytes.getU16Be(src, offset + 4), payload)
                }

                EnetProtocol.COMMAND_SEND_UNSEQUENCED -> {
                    val payload = readPayload(src, offset + structSize, end, EnetBytes.getU16Be(src, offset + 6))
                        ?: return null
                    SendUnsequenced(header, EnetBytes.getU16Be(src, offset + 4), payload)
                }

                EnetProtocol.COMMAND_SEND_FRAGMENT,
                EnetProtocol.COMMAND_SEND_UNRELIABLE_FRAGMENT,
                -> {
                    val payload = readPayload(src, offset + structSize, end, EnetBytes.getU16Be(src, offset + 6))
                        ?: return null
                    SendFragment(
                        header = header,
                        startSequenceNumber = EnetBytes.getU16Be(src, offset + 4),
                        fragmentCount = EnetBytes.getU32Be(src, offset + 8),
                        fragmentNumber = EnetBytes.getU32Be(src, offset + 12),
                        totalLength = EnetBytes.getU32Be(src, offset + 16),
                        fragmentOffset = EnetBytes.getU32Be(src, offset + 20),
                        payload = payload,
                        unreliable = commandId == EnetProtocol.COMMAND_SEND_UNRELIABLE_FRAGMENT,
                    )
                }

                EnetProtocol.COMMAND_BANDWIDTH_LIMIT -> BandwidthLimit(
                    header = header,
                    incomingBandwidth = EnetBytes.getU32Be(src, offset + 4),
                    outgoingBandwidth = EnetBytes.getU32Be(src, offset + 8),
                )

                EnetProtocol.COMMAND_THROTTLE_CONFIGURE -> ThrottleConfigure(
                    header = header,
                    packetThrottleInterval = EnetBytes.getU32Be(src, offset + 4),
                    packetThrottleAcceleration = EnetBytes.getU32Be(src, offset + 8),
                    packetThrottleDeceleration = EnetBytes.getU32Be(src, offset + 12),
                )

                else -> null
            }
        }

        /** Copies [length] payload bytes starting at [start], or `null` if they run past [end]. */
        private fun readPayload(src: ByteArray, start: Int, end: Int, length: Int): ByteArray? {
            if (length < 0 || start + length > end) return null
            return src.copyOfRange(start, start + length)
        }
    }
}
