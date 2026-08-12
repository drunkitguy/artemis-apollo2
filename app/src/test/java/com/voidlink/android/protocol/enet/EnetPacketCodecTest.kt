package com.voidlink.android.protocol.enet

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hex fixtures for the ENet datagram framing (`docs/01-PROTOCOL.md` §9.1, §0.1).
 *
 * The protocol header packs a peer id, a session id and two flags into one 16-bit word, and getting
 * that packing wrong is the failure that looks like "the host never answers": a peer id of 0x0FFF
 * with both flag bits set reads back as a *compressed* datagram, which any ENet receiver drops
 * without a word. These tests pin the word and the sizes it implies.
 */
class EnetPacketCodecTest {

    @Test
    fun `a header with a send time is four bytes`() {
        val header = EnetProtocolHeader(
            peerId = 0,
            sessionId = 1,
            flags = EnetProtocol.HEADER_FLAG_SENT_TIME,
            sentTime = 0x1234,
        )
        // 0x8000 (sent time) | 0x1000 (session 1) | 0x0000 (peer 0) = 0x9000
        assertEquals("9000" + "1234", Hex.encode(EnetPacketCodec.encode(header, emptyList())))
        assertEquals(4, header.encodedSize)
    }

    @Test
    fun `a header without a send time is two bytes`() {
        val header = EnetProtocolHeader(peerId = 5, sessionId = 2, flags = 0, sentTime = 0)
        // 0x2000 (session 2) | 0x0005 (peer 5)
        assertEquals("2005", Hex.encode(EnetPacketCodec.encode(header, emptyList())))
        assertEquals(2, header.encodedSize)
    }

    @Test
    fun `the unconnected peer id does not collide with the header flags`() {
        // The value the very first CONNECT carries. Session id 0 is chosen precisely so that this
        // word cannot come out as 0xFFFF, which would set the compression flag and be dropped.
        val header = EnetProtocolHeader(
            peerId = EnetProtocol.MAXIMUM_PEER_ID,
            sessionId = 0,
            flags = EnetProtocol.HEADER_FLAG_SENT_TIME,
            sentTime = 0,
        )
        assertEquals("8fff" + "0000", Hex.encode(EnetPacketCodec.encode(header, emptyList())))

        val decoded = requireNotNull(EnetPacketCodec.decode(Hex.decodeOrNull("8fff0000")!!))
        assertEquals(EnetProtocol.MAXIMUM_PEER_ID, decoded.header.peerId)
        assertEquals(0, decoded.header.sessionId)
        assertTrue(decoded.header.hasSentTime)
        assertFalse(decoded.header.isCompressed)
    }

    @Test
    fun `header fields survive a round trip at their extremes`() {
        val header = EnetProtocolHeader(
            peerId = EnetProtocol.MAXIMUM_PEER_ID,
            sessionId = EnetProtocol.MAXIMUM_SESSION_ID,
            flags = EnetProtocol.HEADER_FLAG_SENT_TIME,
            sentTime = 0xFFFF,
        )
        val decoded = requireNotNull(EnetPacketCodec.decode(EnetPacketCodec.encode(header, emptyList())))
        assertEquals(EnetProtocol.MAXIMUM_PEER_ID, decoded.header.peerId)
        assertEquals(EnetProtocol.MAXIMUM_SESSION_ID, decoded.header.sessionId)
        assertEquals(0xFFFF, decoded.header.sentTime)
        assertFalse(decoded.header.isCompressed)
    }

    @Test
    fun `the complete connect datagram matches its hex representation`() {
        val header = EnetProtocolHeader(
            peerId = EnetProtocol.MAXIMUM_PEER_ID,
            sessionId = 0,
            flags = EnetProtocol.HEADER_FLAG_SENT_TIME,
            sentTime = 0x1234,
        )
        val connect = EnetCommand.Connect(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_CONNECT or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = EnetProtocol.CHANNEL_ID_SYSTEM,
                reliableSequenceNumber = 1,
            ),
            outgoingPeerId = 0,
            incomingSessionId = EnetProtocol.SESSION_ID_UNSET,
            outgoingSessionId = EnetProtocol.SESSION_ID_UNSET,
            mtu = EnetProtocol.DEFAULT_MTU,
            windowSize = EnetProtocol.MAXIMUM_WINDOW_SIZE,
            channelCount = EnetControlConstants.CHANNEL_COUNT,
            incomingBandwidth = 0,
            outgoingBandwidth = 0,
            packetThrottleInterval = EnetProtocol.PACKET_THROTTLE_INTERVAL_MS,
            packetThrottleAcceleration = EnetProtocol.PACKET_THROTTLE_ACCELERATION,
            packetThrottleDeceleration = EnetProtocol.PACKET_THROTTLE_DECELERATION,
            connectId = 0x1A2B3C4D,
            data = 0,
        )

        val expected = "8fff" + "1234" +
            "82" + "ff" + "0001" +
            "0000" + "ff" + "ff" +
            "00000578" + "00010000" + "00000003" +
            "00000000" + "00000000" +
            "00001388" + "00000002" + "00000002" +
            "1a2b3c4d" + "00000000"

        val encoded = EnetPacketCodec.encode(header, listOf(connect))
        assertEquals(expected, Hex.encode(encoded))
        assertEquals(4 + 48, encoded.size)
    }

    @Test
    fun `several commands pack into one datagram and decode back in order`() {
        // This is the shape of a real service pass: acknowledgements first, then data.
        val header = EnetProtocolHeader(0, 1, EnetProtocol.HEADER_FLAG_SENT_TIME, 0x0064)
        val commands = listOf(
            EnetCommand.Acknowledge(
                EnetCommandHeader(EnetProtocol.COMMAND_ACKNOWLEDGE, EnetProtocol.CHANNEL_ID_SYSTEM, 1),
                receivedReliableSequenceNumber = 1,
                receivedSentTime = 0x0032,
            ),
            EnetCommand.Ping(
                EnetCommandHeader(
                    EnetProtocol.COMMAND_PING or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                    EnetProtocol.CHANNEL_ID_SYSTEM,
                    2,
                ),
            ),
            EnetCommand.SendReliable(
                EnetCommandHeader(
                    EnetProtocol.COMMAND_SEND_RELIABLE or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                    EnetUnverifiedConstants.CHANNEL_URGENT,
                    3,
                ),
                payload = byteArrayOf(0x0A, 0x0B),
            ),
        )

        val expected = "9000" + "0064" +
            "01" + "ff" + "0001" + "0001" + "0032" +
            "85" + "ff" + "0002" +
            "86" + "01" + "0003" + "0002" + "0a0b"
        val encoded = EnetPacketCodec.encode(header, commands)
        assertEquals(expected, Hex.encode(encoded))

        val decoded = requireNotNull(EnetPacketCodec.decode(encoded))
        assertEquals(3, decoded.commands.size)
        assertEquals(EnetProtocol.COMMAND_ACKNOWLEDGE, decoded.commands[0].header.commandId)
        assertEquals(EnetProtocol.COMMAND_PING, decoded.commands[1].header.commandId)
        assertEquals(EnetProtocol.COMMAND_SEND_RELIABLE, decoded.commands[2].header.commandId)
        assertEquals(0x0064, decoded.header.sentTime)
    }

    @Test
    fun `a compressed datagram is refused rather than parsed as garbage`() {
        // We install no compressor. Reading the body anyway would produce plausible nonsense.
        val compressed = Hex.decodeOrNull("c000" + "0000" + "85ff0001")!!
        assertNull(EnetPacketCodec.decode(compressed))
    }

    @Test
    fun `a datagram shorter than its header is refused`() {
        assertNull(EnetPacketCodec.decode(ByteArray(1)))
        assertNull(EnetPacketCodec.decode(ByteArray(0)))
        // Claims a send time but stops before it.
        assertNull(EnetPacketCodec.decode(Hex.decodeOrNull("8000")!!))
    }

    @Test
    fun `a truncated command costs its own bytes and not the datagram`() {
        // Header, one whole PING, then four bytes of a CONNECT that never finishes.
        val truncated = Hex.decodeOrNull("9000" + "0064" + "85ff0002" + "82ff0001")!!
        val decoded = requireNotNull(EnetPacketCodec.decode(truncated))
        assertEquals(1, decoded.commands.size)
        assertEquals(EnetProtocol.COMMAND_PING, decoded.commands[0].header.commandId)
    }

    @Test
    fun `decoding respects an explicit length shorter than the array`() {
        // The receive buffer is reused and longer than the datagram; trailing bytes are not ours.
        val buffer = Hex.decodeOrNull("9000" + "0064" + "85ff0002" + "ffffffffffffffff")!!
        val decoded = requireNotNull(EnetPacketCodec.decode(buffer, length = 8))
        assertEquals(1, decoded.commands.size)
        assertEquals(EnetProtocol.COMMAND_PING, decoded.commands[0].header.commandId)
    }

    @Test
    fun `sequence arithmetic wraps at sixteen bits`() {
        assertEquals(1, EnetProtocol.nextSequenceNumber(0))
        assertEquals(0, EnetProtocol.nextSequenceNumber(0xFFFF))
        assertEquals(1, EnetProtocol.sequenceDistance(0xFFFF, 0))
        assertEquals(2, EnetProtocol.sequenceDistance(0xFFFF, 1))
        assertEquals(0, EnetProtocol.sequenceDistance(7, 7))
        // Going backwards produces a distance near the top of the space, which is exactly how a
        // stale sequence number is recognised without ever comparing two of them with `<`.
        assertEquals(0xFFFF, EnetProtocol.sequenceDistance(1, 0))
    }
}
