package com.voidlink.android.protocol.enet

/**
 * The ENet 1.3 wire constants the control channel needs (`docs/01-PROTOCOL.md` §9.1).
 *
 * The spec tells us *that* the control stream is ENet over UDP 47999 and which ENet features we
 * must cover — connect handshake, reliable ordered delivery per channel, unreliable and unsequenced
 * flags, fragmentation, ping/timeout — but it does not restate ENet's own byte layout, because ENet
 * is an existing protocol rather than a GameStream invention. These values are therefore
 * transcribed from the ENet 1.3 protocol definition, not guessed, and every one of them is pinned
 * by a hex fixture in `EnetCommandCodecTest`.
 *
 * Two things are deliberately *not* here:
 * * **Checksums.** ENet only appends a 32-bit checksum after the protocol header when the
 *   application installs a checksum callback. GameStream hosts run stock ENet with none, so a
 *   packet's commands begin immediately after the header. Nothing in spec §9 asks for one.
 * * **Compression.** [HEADER_FLAG_COMPRESSED] is recognised so that a compressed datagram is
 *   rejected loudly instead of parsed as garbage; no compressor is implemented.
 *
 * @see EnetCommand for the command structs these ids select.
 */
object EnetProtocol {

    // ---- Protocol header -----------------------------------------------------------------------

    /**
     * Size of the protocol header when the sent-time field is present.
     *
     * `{ uint16 peerId; uint16 sentTime; }`, both big-endian.
     */
    const val PROTOCOL_HEADER_SIZE_WITH_SENT_TIME: Int = 4

    /** Size of the protocol header when the sent-time field is omitted — just `{ uint16 peerId; }`. */
    const val PROTOCOL_HEADER_SIZE_MINIMAL: Int = 2

    /** Set in the `peerId` word when the datagram body is compressed. We never set it. */
    const val HEADER_FLAG_COMPRESSED: Int = 0x4000

    /** Set in the `peerId` word when the 16-bit `sentTime` field follows. */
    const val HEADER_FLAG_SENT_TIME: Int = 0x8000

    /** Both header flags. Masked off before the peer id is read. */
    const val HEADER_FLAG_MASK: Int = HEADER_FLAG_COMPRESSED or HEADER_FLAG_SENT_TIME

    /** Bits 12–13 of the `peerId` word carry the two-bit session id. */
    const val HEADER_SESSION_MASK: Int = 0x3000

    /** Shift that moves the session id into the low bits. */
    const val HEADER_SESSION_SHIFT: Int = 12

    /** Largest representable peer id; also the "I have no peer id yet" value used by CONNECT. */
    const val MAXIMUM_PEER_ID: Int = 0x0FFF

    /** Largest session id that fits [HEADER_SESSION_MASK]. */
    const val MAXIMUM_SESSION_ID: Int = HEADER_SESSION_MASK ushr HEADER_SESSION_SHIFT

    /** Session id meaning "unassigned", sent in the CONNECT body before either side has chosen. */
    const val SESSION_ID_UNSET: Int = 0xFF

    // ---- Command ids ---------------------------------------------------------------------------

    /** Never sent; a zero command byte terminates parsing. */
    const val COMMAND_NONE: Int = 0

    /** Acknowledges one reliable command, echoing its sequence number and send time. */
    const val COMMAND_ACKNOWLEDGE: Int = 1

    /** Client → host connection request, carrying the 32-bit connect data of spec §6.3. */
    const val COMMAND_CONNECT: Int = 2

    /** Host → client acceptance of a [COMMAND_CONNECT], echoing the connect id. */
    const val COMMAND_VERIFY_CONNECT: Int = 3

    /** Either direction; ends the session (spec §9.7 step 3). */
    const val COMMAND_DISCONNECT: Int = 4

    /** ENet-level keep-alive; carries no payload. */
    const val COMMAND_PING: Int = 5

    /** Reliable ordered payload on a channel. */
    const val COMMAND_SEND_RELIABLE: Int = 6

    /** Unreliable sequenced payload on a channel. */
    const val COMMAND_SEND_UNRELIABLE: Int = 7

    /** One fragment of a reliable payload larger than the MTU allows. */
    const val COMMAND_SEND_FRAGMENT: Int = 8

    /** Unreliable unsequenced payload — delivered as it arrives, in any order. */
    const val COMMAND_SEND_UNSEQUENCED: Int = 9

    /** Bandwidth throttling; parsed and ignored. */
    const val COMMAND_BANDWIDTH_LIMIT: Int = 10

    /** Throttle reconfiguration; parsed and ignored. */
    const val COMMAND_THROTTLE_CONFIGURE: Int = 11

    /** One fragment of an unreliable payload. Parsed on receive; never sent (see [EnetPeer.send]). */
    const val COMMAND_SEND_UNRELIABLE_FRAGMENT: Int = 12

    /** One past the highest defined command id. */
    const val COMMAND_COUNT: Int = 13

    /** Isolates the command id from the flag bits of the command byte. */
    const val COMMAND_MASK: Int = 0x0F

    /** Command-byte flag: the receiver must acknowledge this command. */
    const val COMMAND_FLAG_ACKNOWLEDGE: Int = 0x80

    /** Command-byte flag: this command carries no sequencing guarantee. */
    const val COMMAND_FLAG_UNSEQUENCED: Int = 0x40

    // ---- Command struct sizes ------------------------------------------------------------------

    /** `{ uint8 command; uint8 channelId; uint16 reliableSequenceNumber; }`. */
    const val COMMAND_HEADER_SIZE: Int = 4

    /** Command header + `{ uint16 receivedReliableSequenceNumber; uint16 receivedSentTime; }`. */
    const val ACKNOWLEDGE_SIZE: Int = 8

    /** Command header + peer/session ids + ten 32-bit parameters. */
    const val CONNECT_SIZE: Int = 48

    /** [CONNECT_SIZE] without the trailing 32-bit connect data word. */
    const val VERIFY_CONNECT_SIZE: Int = 44

    /** Command header + `{ uint32 data; }`. */
    const val DISCONNECT_SIZE: Int = 8

    /** Command header only. */
    const val PING_SIZE: Int = 4

    /** Command header + `{ uint16 dataLength; }`, followed by the payload. */
    const val SEND_RELIABLE_SIZE: Int = 6

    /** Command header + `{ uint16 unreliableSequenceNumber; uint16 dataLength; }` + payload. */
    const val SEND_UNRELIABLE_SIZE: Int = 8

    /** Command header + `{ uint16 unsequencedGroup; uint16 dataLength; }` + payload. */
    const val SEND_UNSEQUENCED_SIZE: Int = 8

    /**
     * Command header + `{ uint16 startSequenceNumber; uint16 dataLength; uint32 fragmentCount;
     * uint32 fragmentNumber; uint32 totalLength; uint32 fragmentOffset; }` + payload.
     */
    const val SEND_FRAGMENT_SIZE: Int = 24

    /** Command header + `{ uint32 incomingBandwidth; uint32 outgoingBandwidth; }`. */
    const val BANDWIDTH_LIMIT_SIZE: Int = 12

    /** Command header + three 32-bit throttle parameters. */
    const val THROTTLE_CONFIGURE_SIZE: Int = 16

    /**
     * Fixed size of each command struct, indexed by command id.
     *
     * A receiver must be able to step over a command it does not care about, which is exactly what
     * this table is for; ENet itself keys parsing off the same table, so a mismatch here desyncs
     * the whole datagram rather than losing one command.
     */
    val COMMAND_SIZES: IntArray = intArrayOf(
        0,
        ACKNOWLEDGE_SIZE,
        CONNECT_SIZE,
        VERIFY_CONNECT_SIZE,
        DISCONNECT_SIZE,
        PING_SIZE,
        SEND_RELIABLE_SIZE,
        SEND_UNRELIABLE_SIZE,
        SEND_FRAGMENT_SIZE,
        SEND_UNSEQUENCED_SIZE,
        BANDWIDTH_LIMIT_SIZE,
        THROTTLE_CONFIGURE_SIZE,
        SEND_FRAGMENT_SIZE,
    )

    // ---- Limits and defaults -------------------------------------------------------------------

    /**
     * Channel id used by the connection-management commands.
     *
     * CONNECT, VERIFY_CONNECT, DISCONNECT and PING are peer-level rather than channel-level: they
     * draw their reliable sequence numbers from a counter on the peer, not from a channel, and
     * they are never delivered to the application.
     */
    const val CHANNEL_ID_SYSTEM: Int = 0xFF

    /** Smallest MTU either side may negotiate. */
    const val MINIMUM_MTU: Int = 576

    /** Largest MTU either side may negotiate; also the receive buffer size we allocate. */
    const val MAXIMUM_MTU: Int = 4096

    /** ENet's default MTU, and what we offer in CONNECT. */
    const val DEFAULT_MTU: Int = 1400

    /** Smallest window size either side may negotiate. */
    const val MINIMUM_WINDOW_SIZE: Int = 4096

    /** Largest window size either side may negotiate; what we offer, since we do not throttle. */
    const val MAXIMUM_WINDOW_SIZE: Int = 65536

    /** ENet refuses a channel count below this. */
    const val MINIMUM_CHANNEL_COUNT: Int = 1

    /** ENet refuses a channel count above this. */
    const val MAXIMUM_CHANNEL_COUNT: Int = 255

    /** Most commands ENet will pack into a single datagram. */
    const val MAXIMUM_PACKET_COMMANDS: Int = 32

    /** Sanity bound on a fragmented packet's fragment count. */
    const val MAXIMUM_FRAGMENT_COUNT: Int = 1024 * 1024

    /**
     * How far ahead of the next expected sequence number a reliable command may arrive.
     *
     * ENet expresses this as sixteen windows of 0x1000 with eight kept free; one window is the
     * conservative reading and is far more than a 47999 control channel can ever have in flight.
     */
    const val RELIABLE_WINDOW_SIZE: Int = 0x1000

    /** Sequence numbers are 16-bit and wrap. */
    const val SEQUENCE_MASK: Int = 0xFFFF

    /** Assumed round-trip time before the first acknowledgement measures a real one. */
    const val DEFAULT_ROUND_TRIP_TIME_MS: Int = 500

    /** ENet's keep-alive interval — one PING per peer per half second while connected. */
    const val PING_INTERVAL_MS: Int = 500

    /** Multiplier that turns the initial retransmission timeout into a give-up ceiling. */
    const val TIMEOUT_LIMIT: Int = 32

    /** A peer is never declared dead sooner than this, however fast the RTO ladder climbs. */
    const val TIMEOUT_MINIMUM_MS: Int = 5_000

    /** A peer is always declared dead by this point, however slow the RTO ladder climbs. */
    const val TIMEOUT_MAXIMUM_MS: Int = 30_000

    /** Throttle parameters, echoed back in VERIFY_CONNECT and compared field-for-field. */
    const val PACKET_THROTTLE_INTERVAL_MS: Int = 5_000

    /** @see PACKET_THROTTLE_INTERVAL_MS */
    const val PACKET_THROTTLE_ACCELERATION: Int = 2

    /** @see PACKET_THROTTLE_INTERVAL_MS */
    const val PACKET_THROTTLE_DECELERATION: Int = 2

    /**
     * Advances a 16-bit sequence number, wrapping at 0xFFFF.
     *
     * ENet's counters are `uint16` and pre-incremented, so the first command a peer or channel
     * sends carries sequence number 1, not 0.
     */
    fun nextSequenceNumber(sequenceNumber: Int): Int = (sequenceNumber + 1) and SEQUENCE_MASK

    /**
     * Forward distance from [from] to [to] in 16-bit wrapping sequence space.
     *
     * Returns 0 when the two are equal, a small number when [to] is ahead, and a number close to
     * 65536 when [to] is behind — which is how duplicate and stale commands are recognised without
     * ever comparing sequence numbers with `<`.
     */
    fun sequenceDistance(from: Int, to: Int): Int = (to - from) and SEQUENCE_MASK
}
