package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hex fixtures for every control-stream packet this client builds
 * (`docs/01-PROTOCOL.md` §9.2, §9.4, §9.5, §9.7).
 *
 * Spec §0.1 opens with "endianness — the number-one bug source" and then proves it: within this one
 * stream the header is little-endian, the loss-stats payload is little-endian, the per-frame FEC
 * status is **big**-endian, and the termination code we read back is big-endian too. A wrong order
 * produces a packet a host silently ignores, which shows up as "the session connects and then
 * nothing happens" — the single hardest symptom in this protocol to trace back to its cause.
 *
 * Byte order is also the one thing about the control stream that is *fully* verifiable without a
 * host, which is why every builder is pinned here rather than tested for round-tripping against our
 * own parser.
 */
class ControlPacketFixtureTest {

    private fun hex(bytes: ByteArray) = Hex.encode(bytes)

    // ---- Framing (spec §9.2) --------------------------------------------------------------------

    @Test
    fun `the V1 header is the little-endian type and nothing else`() {
        // Start A on Gen 7 is type 0x0305 with the payload {0, 0}: on the wire the type reads
        // "0503" because it is little-endian, and getting that backwards is a message the host
        // parses as type 0x0503 and drops.
        val framed = ControlFraming.encode(0x0305, byteArrayOf(0, 0), ControlHeaderVersion.V1)
        assertEquals("0503" + "0000", hex(framed))
        assertEquals(ControlConstants.HEADER_SIZE_V1 + 2, framed.size)
    }

    @Test
    fun `the V2 header adds a little-endian payload length`() {
        val framed = ControlFraming.encode(0x0305, byteArrayOf(1, 2, 3), ControlHeaderVersion.V2)
        assertEquals("0503" + "0300" + "010203", hex(framed))
    }

    @Test
    fun `a message with no payload is header-sized`() {
        assertEquals("0001", hex(ControlFraming.encode(0x0100, version = ControlHeaderVersion.V1)))
        assertEquals(
            "0001" + "0000",
            hex(ControlFraming.encode(0x0100, version = ControlHeaderVersion.V2)),
        )
    }

    @Test
    fun `decoding reverses encoding for both header versions`() {
        for (version in ControlHeaderVersion.entries) {
            val encoded = ControlFraming.encode(0x010b, byteArrayOf(9, 8, 7), version)
            val decoded = requireNotNull(ControlFraming.decode(encoded, version))
            assertEquals(0x010b, decoded.type)
            assertArrayEquals(byteArrayOf(9, 8, 7), decoded.payload)
        }
    }

    @Test
    fun `a runt packet is rejected rather than half-read`() {
        assertNull(ControlFraming.decode(byteArrayOf(0x05), ControlHeaderVersion.V1))
        assertNull(ControlFraming.decode(byteArrayOf(0x05, 0x03, 0x01), ControlHeaderVersion.V2))
    }

    @Test
    fun `a V2 packet claiming more payload than arrived is truncated, not dropped`() {
        // The type is what decides whether the session is ending, so an over-reported length must
        // not cost us the message.
        val packet = Hex.decodeOrNull("0001" + "ff00" + "abcd")!!
        val decoded = requireNotNull(ControlFraming.decode(packet, ControlHeaderVersion.V2))
        assertEquals(0x0100, decoded.type)
        assertArrayEquals(Hex.decodeOrNull("abcd"), decoded.payload)
    }

    // ---- Session start (spec §9.4) --------------------------------------------------------------

    @Test
    fun `Start A and Start B carry the payloads the spec prescribes per generation`() {
        assertEquals("0000", hex(ControlPayloads.startA(7)))
        assertEquals("0000", hex(ControlPayloads.startA(5)))
        assertEquals("00", hex(ControlPayloads.startA(4)))

        assertEquals("00", hex(ControlPayloads.startB(7)))
        assertEquals("00", hex(ControlPayloads.startB(5)))
        assertEquals("00", hex(ControlPayloads.startB(4)))
        // Gen 3: the four little-endian ints 0, 0, 0, 0x0a.
        assertEquals("00000000" + "00000000" + "00000000" + "0a000000", hex(ControlPayloads.startB(3)))
    }

    @Test
    fun `the whole Start A packet for a Gen 7 host is four bytes`() {
        val packet = ControlFraming.encode(
            requireNotNull(ControlMessageTable.GEN7.typeOf(ControlMessageIndex.START_A)),
            ControlPayloads.startA(7),
            ControlHeaderVersion.V1,
        )
        assertEquals("05030000", hex(packet))
    }

    // ---- Periodic ping (spec §9.5) ---------------------------------------------------------------

    @Test
    fun `the periodic ping is eight little-endian bytes opening with the value four`() {
        // uint16 4 ("length of payload"), uint32 0 (timestamp placeholder), two trailing zeroes.
        assertEquals("0400" + "00000000" + "0000", hex(ControlPayloads.periodicPing()))
        assertEquals(
            ControlConstants.PERIODIC_PING_PAYLOAD_SIZE,
            ControlPayloads.periodicPing().size,
        )
    }

    @Test
    fun `the framed periodic ping is type 0x0200 little-endian`() {
        val packet = ControlFraming.encode(
            ControlConstants.TYPE_PERIODIC_PING,
            ControlPayloads.periodicPing(),
            ControlHeaderVersion.V1,
        )
        assertEquals("0002" + "0400000000000000", hex(packet))
    }

    // ---- Loss statistics (spec §9.5) -------------------------------------------------------------

    @Test
    fun `loss stats are thirty-two little-endian bytes in the spec's field order`() {
        val payload = ControlPayloads.lossStats(intervalMs = 50, lastGoodFrameIndex = 0x1234L)
        assertEquals(ControlConstants.LOSS_STATS_PAYLOAD_SIZE, payload.size)
        assertEquals(
            "00000000" + // uint32 lostFrames — always zero, as the reference client sends it
                "32000000" + // uint32 LOSS_REPORT_INTERVAL_MS = 50
                "e8030000" + // uint32 1000
                "3412000000000000" + // uint64 lastGoodFrameIndex
                "00000000" + "00000000" + "14000000", // uint32 0, 0, 0x14
            hex(payload),
        )
    }

    // ---- IDR and reference-frame invalidation (spec §9.3, §9.5) ----------------------------------

    @Test
    fun `the IDR request payload is two zero bytes`() {
        assertEquals("0000", hex(ControlPayloads.requestIdrFrame()))
    }

    @Test
    fun `reference-frame invalidation is three little-endian int64s`() {
        val payload = ControlPayloads.invalidateReferenceFrames(0x20L, 0x40L)
        assertEquals(ControlConstants.INVALIDATE_REFERENCE_FRAMES_PAYLOAD_SIZE, payload.size)
        assertEquals(
            "2000000000000000" + "4000000000000000" + "0000000000000000",
            hex(payload),
        )
    }

    @Test
    fun `the IDR invalidation range reaches 0x20 frames back and never below zero`() {
        assertArrayEquals(longArrayOf(0x30L, 0x50L), ControlPayloads.idrInvalidationRange(0x50L))
        assertArrayEquals(longArrayOf(0L, 0x10L), ControlPayloads.idrInvalidationRange(0x10L))
        assertArrayEquals(longArrayOf(0L, 0L), ControlPayloads.idrInvalidationRange(-1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an inverted invalidation range is refused rather than sent`() {
        ControlPayloads.invalidateReferenceFrames(0x40L, 0x20L)
    }

    // ---- Long-term reference ack (spec §9.3) -----------------------------------------------------

    @Test
    fun `the LTR frame ack is one little-endian uint32`() {
        assertEquals("78563412", hex(ControlPayloads.longTermReferenceFrameAck(0x12345678L)))
    }

    // ---- Sunshine per-frame FEC status (spec §9.5) -----------------------------------------------

    @Test
    fun `the per-frame FEC status is twenty-one BIG-endian bytes`() {
        val payload = ControlPayloads.frameFecStatus(
            FrameFecStatus(
                frameIndex = 0x01020304L,
                highestReceivedSequenceNumber = 0x1122,
                nextContiguousSequenceNumber = 0x3344,
                missingPacketsBeforeHighestReceived = 2,
                totalDataPackets = 10,
                totalParityPackets = 3,
                receivedDataPackets = 8,
                receivedParityPackets = 3,
                fecPercentage = 20,
                multiFecBlockIndex = 1,
                multiFecBlockCount = 2,
            ),
        )
        // uint32 + 7 x uint16 + 3 x uint8, packed (spec §0.2).
        assertEquals(21, payload.size)
        assertEquals(ControlConstants.FRAME_FEC_STATUS_PAYLOAD_SIZE, payload.size)
        assertEquals(
            "01020304" + // frameIndex, big-endian — the opposite of every other payload here
                "1122" + "3344" + "0002" + "000a" + "0003" + "0008" + "0003" +
                "14" + "01" + "02",
            hex(payload),
        )
    }

    // ---- Termination (spec §9.6, §9.7) -----------------------------------------------------------

    @Test
    fun `the client's termination notice carries no payload`() {
        assertEquals(0, ControlPayloads.termination().size)
        assertEquals("0001", hex(ControlFraming.encode(0x0100, version = ControlHeaderVersion.V1)))
    }

    @Test
    fun `a termination error code is read BIG-endian and only when four bytes are present`() {
        val payload = Hex.decodeOrNull("800e9403")!!
        assertEquals(
            ControlConstants.TERMINATION_FRAME_CONVERSION,
            ControlPayloads.terminationErrorCode(payload),
        )
        assertNull(ControlPayloads.terminationErrorCode(Hex.decodeOrNull("800e94")!!))
        assertNull(ControlPayloads.terminationErrorCode(ByteArray(0)))
    }

    @Test
    fun `the known termination codes keep their documented values`() {
        // Spec §9.6's table. Two of the three are marked UNVERIFIED there, which is precisely why
        // the numbers must be pinned: if we ever change what we claim they mean, the change should
        // be deliberate and visible in a diff.
        assertEquals(-0x7ff16bfd, ControlConstants.TERMINATION_FRAME_CONVERSION) // 0x800e9403
        assertEquals(-0x7ffcffdd, ControlConstants.TERMINATION_GRACEFUL) // 0x80030023
        assertEquals(-0x7ff16cfe, ControlConstants.TERMINATION_PROTECTED_CONTENT) // 0x800e9302
        assertTrue(ControlConstants.TERMINATION_FRAME_CONVERSION.toLong() and 0xFFFFFFFFL == 0x800e9403L)
    }
}
