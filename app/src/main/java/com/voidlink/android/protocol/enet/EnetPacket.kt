package com.voidlink.android.protocol.enet

/**
 * The protocol header that opens every ENet datagram (`docs/01-PROTOCOL.md` §9.1).
 *
 * ```
 * offset 0 : uint16 peerId    BIG-ENDIAN   // flags 0xC000 | session 0x3000 | peer id 0x0FFF
 * offset 2 : uint16 sentTime  BIG-ENDIAN   // present only when HEADER_FLAG_SENT_TIME is set
 * ```
 *
 * The three fields share one 16-bit word, which is the single subtlety of the whole framing: a peer
 * id of [EnetProtocol.MAXIMUM_PEER_ID] with both flag bits set reads back as `0xFFFF`, and a
 * receiver would then take the compression flag at face value and drop the datagram. We therefore
 * always send a session id in 0..3 (see [EnetPeer]) and never set the compression flag.
 *
 * @property peerId the id the *receiver* assigned to us, so it can find its peer record.
 * @property sessionId two bits distinguishing this connection from an earlier one on the same
 *   address and port.
 * @property sentTime low 16 bits of the sender's millisecond clock, echoed back in acknowledgements
 *   to measure the round trip. Meaningful only when [hasSentTime] is true.
 */
data class EnetProtocolHeader(
    val peerId: Int,
    val sessionId: Int,
    val flags: Int,
    val sentTime: Int,
) {
    /** True when the datagram carries the 16-bit send time, making it four bytes rather than two. */
    val hasSentTime: Boolean get() = (flags and EnetProtocol.HEADER_FLAG_SENT_TIME) != 0

    /** True when the sender claims the body is compressed. We have no compressor; such a datagram is dropped. */
    val isCompressed: Boolean get() = (flags and EnetProtocol.HEADER_FLAG_COMPRESSED) != 0

    /** Encoded size of this header — 4 bytes with a send time, 2 without. */
    val encodedSize: Int
        get() = if (hasSentTime) {
            EnetProtocol.PROTOCOL_HEADER_SIZE_WITH_SENT_TIME
        } else {
            EnetProtocol.PROTOCOL_HEADER_SIZE_MINIMAL
        }
}

/**
 * A decoded datagram: one protocol header and the commands packed behind it.
 *
 * @property header the datagram's protocol header.
 * @property commands the commands in wire order. ENet packs up to
 *   [EnetProtocol.MAXIMUM_PACKET_COMMANDS] of them into one datagram, so acknowledgements ride
 *   along with data instead of costing a datagram each.
 */
class EnetIncomingDatagram(
    val header: EnetProtocolHeader,
    val commands: List<EnetCommand>,
)

/**
 * Serialises and parses whole ENet datagrams (`docs/01-PROTOCOL.md` §9.1).
 *
 * Kept separate from the socket so that every byte of the framing is exercised by hex fixtures in
 * `EnetPacketCodecTest` — spec §0.1 makes byte order the one thing worth over-testing, and a
 * datagram builder that only runs inside a service loop is a datagram builder nobody has checked.
 */
object EnetPacketCodec {

    /**
     * Encodes [header] followed by [commands].
     *
     * @return a freshly allocated array holding exactly the datagram.
     */
    fun encode(header: EnetProtocolHeader, commands: List<EnetCommand>): ByteArray {
        var size = header.encodedSize
        for (command in commands) size += command.encodedSize
        val out = ByteArray(size)
        var offset = encodeHeaderInto(out, 0, header)
        for (command in commands) {
            command.encodeInto(out, offset)
            offset += command.encodedSize
        }
        return out
    }

    /**
     * Writes [header] at [offset] in [dst].
     *
     * @return the offset just past the header.
     */
    fun encodeHeaderInto(dst: ByteArray, offset: Int, header: EnetProtocolHeader): Int {
        val word = (header.peerId and EnetProtocol.MAXIMUM_PEER_ID) or
            ((header.sessionId shl EnetProtocol.HEADER_SESSION_SHIFT) and EnetProtocol.HEADER_SESSION_MASK) or
            (header.flags and EnetProtocol.HEADER_FLAG_MASK)
        EnetBytes.putU16Be(dst, offset, word)
        if (!header.hasSentTime) return offset + EnetProtocol.PROTOCOL_HEADER_SIZE_MINIMAL
        EnetBytes.putU16Be(dst, offset + 2, header.sentTime)
        return offset + EnetProtocol.PROTOCOL_HEADER_SIZE_WITH_SENT_TIME
    }

    /**
     * Parses the first [length] bytes of [data].
     *
     * Parsing stops at the first command that does not decode and keeps whatever came before it,
     * which is what ENet does: a truncated tail costs the commands in it, not the datagram.
     *
     * @return the datagram, or `null` when even the header is unusable — too short, or claiming a
     *   compression we cannot undo.
     */
    fun decode(data: ByteArray, length: Int = data.size): EnetIncomingDatagram? {
        if (length < EnetProtocol.PROTOCOL_HEADER_SIZE_MINIMAL || length > data.size) return null

        val word = EnetBytes.getU16Be(data, 0)
        val header = EnetProtocolHeader(
            peerId = word and EnetProtocol.MAXIMUM_PEER_ID,
            sessionId = (word and EnetProtocol.HEADER_SESSION_MASK) ushr EnetProtocol.HEADER_SESSION_SHIFT,
            flags = word and EnetProtocol.HEADER_FLAG_MASK,
            sentTime = if ((word and EnetProtocol.HEADER_FLAG_SENT_TIME) != 0 &&
                length >= EnetProtocol.PROTOCOL_HEADER_SIZE_WITH_SENT_TIME
            ) {
                EnetBytes.getU16Be(data, 2)
            } else {
                0
            },
        )
        if (header.isCompressed) return null
        if (length < header.encodedSize) return null

        val commands = ArrayList<EnetCommand>()
        var offset = header.encodedSize
        while (offset < length && commands.size < EnetProtocol.MAXIMUM_PACKET_COMMANDS) {
            val command = EnetCommand.decode(data, offset, length) ?: break
            commands.add(command)
            offset += command.encodedSize
        }
        return EnetIncomingDatagram(header, commands)
    }
}
