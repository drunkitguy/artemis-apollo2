package com.voidlink.android.protocol.enet

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hex fixtures for every ENet command (`docs/01-PROTOCOL.md` §9.1, §0.1, §0.2).
 *
 * `02-ARCHITECTURE.md` §10 calls these non-negotiable: "JVM unit tests round-tripping against
 * hand-written hex fixtures — this is where byte-order bugs die". Every expected string below was
 * written out by hand from the struct definitions, not captured from this implementation, so a
 * swapped `putU16Be` fails here rather than in a silent handshake against a real host.
 *
 * Two properties are checked for each command: the exact bytes it encodes to, and that decoding
 * those bytes reproduces every field. Encode-only would miss a decoder that reads the right bytes
 * into the wrong field; decode-only would miss the same bug mirrored.
 */
class EnetCommandCodecTest {

    // ---- Acknowledge ---------------------------------------------------------------------------

    @Test
    fun `acknowledge encodes to its documented bytes`() {
        val command = EnetCommand.Acknowledge(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_ACKNOWLEDGE,
                channelId = EnetProtocol.CHANNEL_ID_SYSTEM,
                reliableSequenceNumber = 1,
            ),
            receivedReliableSequenceNumber = 1,
            receivedSentTime = 0x1234,
        )

        //           cmd  ch   relSeq  recvSeq  sentTime
        assertEquals("01" + "ff" + "0001" + "0001" + "1234", Hex.encode(command.encode()))
        assertEquals(EnetProtocol.ACKNOWLEDGE_SIZE, command.encodedSize)
    }

    @Test
    fun `acknowledge decodes every field`() {
        val decoded = decodeOne("01ff000100011234")
        assertTrue(decoded is EnetCommand.Acknowledge)
        decoded as EnetCommand.Acknowledge
        assertEquals(EnetProtocol.COMMAND_ACKNOWLEDGE, decoded.header.commandId)
        assertEquals(EnetProtocol.CHANNEL_ID_SYSTEM, decoded.header.channelId)
        assertEquals(1, decoded.header.reliableSequenceNumber)
        assertEquals(1, decoded.receivedReliableSequenceNumber)
        assertEquals(0x1234, decoded.receivedSentTime)
        assertFalse(decoded.header.requiresAcknowledgement)
    }

    // ---- Connect -------------------------------------------------------------------------------

    /** The exact CONNECT our client sends: 3 channels, MTU 1400, session ids unset (spec §9.1). */
    private val connectHex: String =
        "82" + "ff" + "0001" + // command CONNECT|ACK, channel 0xFF, reliable sequence 1
            "0000" + // outgoingPeerId — we are peer 0
            "ff" + "ff" + // incoming/outgoing session id: unset
            "00000578" + // mtu 1400
            "00010000" + // windowSize 65536
            "00000003" + // channelCount 3
            "00000000" + "00000000" + // no bandwidth limits
            "00001388" + "00000002" + "00000002" + // throttle 5000 / 2 / 2
            "1a2b3c4d" + // connectId
            "deadbeef" // connect data from X-SS-Connect-Data (spec §6.3)

    @Test
    fun `connect encodes to its documented bytes`() {
        val command = EnetCommand.Connect(
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
            data = 0xDEADBEEF.toInt(),
        )

        assertEquals(connectHex, Hex.encode(command.encode()))
        assertEquals(EnetProtocol.CONNECT_SIZE, command.encodedSize)
        assertEquals(48, command.encodedSize)
    }

    @Test
    fun `connect decodes every field`() {
        val decoded = decodeOne(connectHex)
        assertTrue(decoded is EnetCommand.Connect)
        decoded as EnetCommand.Connect
        assertEquals(EnetProtocol.COMMAND_CONNECT, decoded.header.commandId)
        assertTrue(decoded.header.requiresAcknowledgement)
        assertEquals(EnetProtocol.CHANNEL_ID_SYSTEM, decoded.header.channelId)
        assertEquals(1, decoded.header.reliableSequenceNumber)
        assertEquals(0, decoded.outgoingPeerId)
        assertEquals(EnetProtocol.SESSION_ID_UNSET, decoded.incomingSessionId)
        assertEquals(EnetProtocol.SESSION_ID_UNSET, decoded.outgoingSessionId)
        assertEquals(1400, decoded.mtu)
        assertEquals(65536, decoded.windowSize)
        assertEquals(3, decoded.channelCount)
        assertEquals(0, decoded.incomingBandwidth)
        assertEquals(0, decoded.outgoingBandwidth)
        assertEquals(5000, decoded.packetThrottleInterval)
        assertEquals(2, decoded.packetThrottleAcceleration)
        assertEquals(2, decoded.packetThrottleDeceleration)
        assertEquals(0x1A2B3C4D, decoded.connectId)
        assertEquals(0xDEADBEEF.toInt(), decoded.data)
    }

    @Test
    fun `connect data survives a value with the high bit set`() {
        // X-SS-Connect-Data is parsed with auto-base detection (spec §6.3) and can be any 32-bit
        // value; a signed Int must carry 0xFFFFFFFF back out unchanged.
        val command = EnetCommand.Connect(
            header = EnetCommandHeader(EnetProtocol.COMMAND_CONNECT, EnetProtocol.CHANNEL_ID_SYSTEM, 1),
            outgoingPeerId = 0,
            incomingSessionId = 0,
            outgoingSessionId = 0,
            mtu = EnetProtocol.DEFAULT_MTU,
            windowSize = EnetProtocol.MAXIMUM_WINDOW_SIZE,
            channelCount = 3,
            incomingBandwidth = 0,
            outgoingBandwidth = 0,
            packetThrottleInterval = 0,
            packetThrottleAcceleration = 0,
            packetThrottleDeceleration = 0,
            connectId = -1,
            data = -1,
        )
        val decoded = EnetCommand.decode(command.encode(), 0, command.encodedSize) as EnetCommand.Connect
        assertEquals(-1, decoded.data)
        assertEquals(-1, decoded.connectId)
    }

    // ---- VerifyConnect -------------------------------------------------------------------------

    private val verifyConnectHex: String =
        "83" + "ff" + "0001" +
            "0000" +
            "01" + "01" + // session ids assigned by the handshake
            "00000578" +
            "00010000" +
            "00000003" +
            "00000000" + "00000000" +
            "00001388" + "00000002" + "00000002" +
            "1a2b3c4d"

    @Test
    fun `verify connect encodes to its documented bytes`() {
        val command = EnetCommand.VerifyConnect(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_VERIFY_CONNECT or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = EnetProtocol.CHANNEL_ID_SYSTEM,
                reliableSequenceNumber = 1,
            ),
            outgoingPeerId = 0,
            incomingSessionId = 1,
            outgoingSessionId = 1,
            mtu = EnetProtocol.DEFAULT_MTU,
            windowSize = EnetProtocol.MAXIMUM_WINDOW_SIZE,
            channelCount = 3,
            incomingBandwidth = 0,
            outgoingBandwidth = 0,
            packetThrottleInterval = EnetProtocol.PACKET_THROTTLE_INTERVAL_MS,
            packetThrottleAcceleration = EnetProtocol.PACKET_THROTTLE_ACCELERATION,
            packetThrottleDeceleration = EnetProtocol.PACKET_THROTTLE_DECELERATION,
            connectId = 0x1A2B3C4D,
        )

        assertEquals(verifyConnectHex, Hex.encode(command.encode()))
        assertEquals(44, command.encodedSize)
    }

    @Test
    fun `verify connect decodes every field`() {
        val decoded = decodeOne(verifyConnectHex)
        assertTrue(decoded is EnetCommand.VerifyConnect)
        decoded as EnetCommand.VerifyConnect
        assertEquals(EnetProtocol.COMMAND_VERIFY_CONNECT, decoded.header.commandId)
        assertEquals(0, decoded.outgoingPeerId)
        assertEquals(1, decoded.incomingSessionId)
        assertEquals(1, decoded.outgoingSessionId)
        assertEquals(1400, decoded.mtu)
        assertEquals(65536, decoded.windowSize)
        assertEquals(3, decoded.channelCount)
        assertEquals(5000, decoded.packetThrottleInterval)
        assertEquals(0x1A2B3C4D, decoded.connectId)
    }

    // ---- Disconnect and Ping -------------------------------------------------------------------

    @Test
    fun `disconnect encodes to its documented bytes`() {
        val command = EnetCommand.Disconnect(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_DISCONNECT or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = EnetProtocol.CHANNEL_ID_SYSTEM,
                reliableSequenceNumber = 2,
            ),
            data = 0,
        )

        assertEquals("84" + "ff" + "0002" + "00000000", Hex.encode(command.encode()))

        val decoded = decodeOne("84ff000200000000")
        assertTrue(decoded is EnetCommand.Disconnect)
        decoded as EnetCommand.Disconnect
        assertEquals(EnetProtocol.COMMAND_DISCONNECT, decoded.header.commandId)
        assertTrue(decoded.header.requiresAcknowledgement)
        assertEquals(0, decoded.data)
    }

    @Test
    fun `ping encodes to a bare command header`() {
        val command = EnetCommand.Ping(
            EnetCommandHeader(
                command = EnetProtocol.COMMAND_PING or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = EnetProtocol.CHANNEL_ID_SYSTEM,
                reliableSequenceNumber = 3,
            ),
        )

        assertEquals("85" + "ff" + "0003", Hex.encode(command.encode()))
        assertEquals(EnetProtocol.COMMAND_HEADER_SIZE, command.encodedSize)
        assertTrue(decodeOne("85ff0003") is EnetCommand.Ping)
    }

    // ---- Data-carrying commands ----------------------------------------------------------------

    /**
     * The GameStream periodic ping of spec §9.5, as it actually travels: control message type
     * `0x0200` little-endian, payload length 8 little-endian, then the eight payload bytes.
     *
     * Used as the fixture payload because it makes the two byte orders of spec §0.1 sit side by
     * side in one string — the ENet command around it is big-endian, the message inside it is not.
     */
    private val periodicPingMessageHex = "0002" + "0800" + "0400" + "00000000" + "0000"

    @Test
    fun `send reliable encodes its length and payload`() {
        val payload = Hex.decodeOrNull(periodicPingMessageHex)!!
        assertEquals(12, payload.size)
        val command = EnetCommand.SendReliable(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_SEND_RELIABLE or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = EnetUnverifiedConstants.CHANNEL_GENERIC,
                reliableSequenceNumber = 7,
            ),
            payload = payload,
        )

        //           cmd  ch   relSeq  dataLength  payload
        assertEquals("86" + "00" + "0007" + "000c" + periodicPingMessageHex, Hex.encode(command.encode()))
        assertEquals(EnetProtocol.SEND_RELIABLE_SIZE + 12, command.encodedSize)

        val decoded = decodeOne(Hex.encode(command.encode()))
        assertTrue(decoded is EnetCommand.SendReliable)
        decoded as EnetCommand.SendReliable
        assertEquals(7, decoded.header.reliableSequenceNumber)
        assertEquals(EnetUnverifiedConstants.CHANNEL_GENERIC, decoded.header.channelId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `send unreliable encodes both sequence numbers`() {
        val command = EnetCommand.SendUnreliable(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_SEND_UNRELIABLE,
                channelId = EnetUnverifiedConstants.CHANNEL_GENERIC,
                reliableSequenceNumber = 5,
            ),
            unreliableSequenceNumber = 9,
            payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte()),
        )

        //           cmd  ch   relSeq  unrelSeq  dataLength  payload
        assertEquals("07" + "00" + "0005" + "0009" + "0002" + "aabb", Hex.encode(command.encode()))

        val decoded = decodeOne("0700000500090002aabb")
        assertTrue(decoded is EnetCommand.SendUnreliable)
        decoded as EnetCommand.SendUnreliable
        assertEquals(5, decoded.header.reliableSequenceNumber)
        assertEquals(9, decoded.unreliableSequenceNumber)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), decoded.payload)
    }

    @Test
    fun `send unsequenced carries the unsequenced flag and its group`() {
        val command = EnetCommand.SendUnsequenced(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_SEND_UNSEQUENCED or EnetProtocol.COMMAND_FLAG_UNSEQUENCED,
                channelId = EnetUnverifiedConstants.CHANNEL_GENERIC,
                reliableSequenceNumber = 0,
            ),
            unsequencedGroup = 3,
            payload = byteArrayOf(0x11, 0x22),
        )

        assertEquals("49" + "00" + "0000" + "0003" + "0002" + "1122", Hex.encode(command.encode()))

        val decoded = decodeOne("49000000000300021122")
        assertTrue(decoded is EnetCommand.SendUnsequenced)
        decoded as EnetCommand.SendUnsequenced
        assertTrue(decoded.header.isUnsequenced)
        assertEquals(3, decoded.unsequencedGroup)
    }

    @Test
    fun `send fragment encodes the whole fragment descriptor`() {
        val command = EnetCommand.SendFragment(
            header = EnetCommandHeader(
                command = EnetProtocol.COMMAND_SEND_FRAGMENT or EnetProtocol.COMMAND_FLAG_ACKNOWLEDGE,
                channelId = EnetUnverifiedConstants.CHANNEL_URGENT,
                reliableSequenceNumber = 4,
            ),
            startSequenceNumber = 4,
            fragmentCount = 2,
            fragmentNumber = 0,
            totalLength = 5,
            fragmentOffset = 0,
            payload = byteArrayOf(1, 2, 3),
        )

        assertEquals(
            "88" + "01" + "0004" + // command, channel, reliable sequence
                "0004" + "0003" + // startSequenceNumber, dataLength
                "00000002" + "00000000" + // fragmentCount, fragmentNumber
                "00000005" + "00000000" + // totalLength, fragmentOffset
                "010203",
            Hex.encode(command.encode()),
        )
        assertEquals(EnetProtocol.SEND_FRAGMENT_SIZE + 3, command.encodedSize)

        val decoded = decodeOne(Hex.encode(command.encode()))
        assertTrue(decoded is EnetCommand.SendFragment)
        decoded as EnetCommand.SendFragment
        assertEquals(4, decoded.startSequenceNumber)
        assertEquals(2, decoded.fragmentCount)
        assertEquals(0, decoded.fragmentNumber)
        assertEquals(5, decoded.totalLength)
        assertEquals(0, decoded.fragmentOffset)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.payload)
        assertFalse(decoded.unreliable)
    }

    @Test
    fun `an unreliable fragment decodes with the same layout and is flagged`() {
        val decoded = decodeOne(
            "8c" + "01" + "0004" + "0004" + "0003" +
                "00000002" + "00000001" + "00000005" + "00000003" + "040506",
        )
        assertTrue(decoded is EnetCommand.SendFragment)
        decoded as EnetCommand.SendFragment
        assertTrue(decoded.unreliable)
        assertEquals(1, decoded.fragmentNumber)
        assertEquals(3, decoded.fragmentOffset)
    }

    // ---- Commands we parse but never send ------------------------------------------------------

    @Test
    fun `bandwidth limit and throttle configure decode so the datagram stays in sync`() {
        val bandwidth = decodeOne("0a" + "ff" + "0004" + "00010000" + "00020000")
        assertTrue(bandwidth is EnetCommand.BandwidthLimit)
        bandwidth as EnetCommand.BandwidthLimit
        assertEquals(0x00010000, bandwidth.incomingBandwidth)
        assertEquals(0x00020000, bandwidth.outgoingBandwidth)
        assertEquals(EnetProtocol.BANDWIDTH_LIMIT_SIZE, bandwidth.encodedSize)

        val throttle = decodeOne("0b" + "ff" + "0005" + "00001388" + "00000002" + "00000002")
        assertTrue(throttle is EnetCommand.ThrottleConfigure)
        throttle as EnetCommand.ThrottleConfigure
        assertEquals(5000, throttle.packetThrottleInterval)
        assertEquals(2, throttle.packetThrottleAcceleration)
        assertEquals(2, throttle.packetThrottleDeceleration)
        assertEquals(EnetProtocol.THROTTLE_CONFIGURE_SIZE, throttle.encodedSize)
    }

    // ---- Malformed input -----------------------------------------------------------------------

    @Test
    fun `a truncated command decodes to null rather than throwing`() {
        // Anyone can send us a UDP datagram; a short one is a protocol event, not a bug.
        assertNull(EnetCommand.decode(Hex.decodeOrNull("01ff")!!, 0, 2))
        assertNull(EnetCommand.decode(Hex.decodeOrNull("01ff0001")!!, 0, 4))
        assertNull(EnetCommand.decode(Hex.decodeOrNull("82ff00010000ffff")!!, 0, 8))
    }

    @Test
    fun `an unknown command id decodes to null`() {
        assertNull(EnetCommand.decode(Hex.decodeOrNull("0dff0001")!!, 0, 4))
        assertNull(EnetCommand.decode(Hex.decodeOrNull("00ff0001")!!, 0, 4))
    }

    @Test
    fun `a payload length pointing past the datagram decodes to null`() {
        // dataLength says 0x00ff but only two payload bytes follow.
        assertNull(EnetCommand.decode(Hex.decodeOrNull("8600000700ffaabb")!!, 0, 8))
    }

    @Test
    fun `the command size table matches every struct`() {
        // The table is how a receiver steps over a command it does not handle; a wrong entry
        // desynchronises the whole datagram rather than losing one command.
        assertEquals(EnetProtocol.COMMAND_COUNT, EnetProtocol.COMMAND_SIZES.size)
        assertEquals(0, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_NONE])
        assertEquals(8, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_ACKNOWLEDGE])
        assertEquals(48, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_CONNECT])
        assertEquals(44, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_VERIFY_CONNECT])
        assertEquals(8, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_DISCONNECT])
        assertEquals(4, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_PING])
        assertEquals(6, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_SEND_RELIABLE])
        assertEquals(8, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_SEND_UNRELIABLE])
        assertEquals(24, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_SEND_FRAGMENT])
        assertEquals(8, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_SEND_UNSEQUENCED])
        assertEquals(12, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_BANDWIDTH_LIMIT])
        assertEquals(16, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_THROTTLE_CONFIGURE])
        assertEquals(24, EnetProtocol.COMMAND_SIZES[EnetProtocol.COMMAND_SEND_UNRELIABLE_FRAGMENT])
    }

    /** Decodes exactly one command from a hex string, failing the test if it does not parse. */
    private fun decodeOne(hex: String): EnetCommand {
        val bytes = requireNotNull(Hex.decodeOrNull(hex)) { "fixture is not valid hex: $hex" }
        return requireNotNull(EnetCommand.decode(bytes, 0, bytes.size)) { "fixture did not decode: $hex" }
    }
}
