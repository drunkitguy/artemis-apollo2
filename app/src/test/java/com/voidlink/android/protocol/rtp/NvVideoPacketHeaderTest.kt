package com.voidlink.android.protocol.rtp

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor `NV_VIDEO_PACKET` header of `docs/01-PROTOCOL.md` §7.4, against committed hex.
 *
 * This header is little-endian while the RTP header immediately before it is big-endian (spec
 * §0.1), and the `fecInfo` word packs three fields at non-byte boundaries. Both are exactly the
 * kind of thing that produces a plausible-looking wrong answer, so the fixtures spell out the
 * bytes and the expectations spell out the decoded values.
 */
class NvVideoPacketHeaderTest {

    /**
     * streamPacketIndex=0x00000100, frameIndex=42, flags=0x05 (PIC_DATA|SOF), extraFlags=0x01
     * (LTR), multiFecFlags=0x00, multiFecBlocks=0x01, fecInfo=0x00C01140.
     */
    private val headerHex = "000100002a000000050100014011c000"

    private fun decode(hex: String): ByteArray = requireNotNull(Hex.decodeOrNull(hex))

    private fun parse(hex: String): NvVideoPacketHeader =
        requireNotNull(NvVideoPacketHeader.parse(decode(hex), 0))

    @Test
    fun `each field is read little-endian from its documented offset`() {
        val header = parse(headerHex)

        assertEquals(0x00000100, header.streamPacketIndex)
        assertEquals(42L, header.frameIndex)
        assertEquals(0x05, header.flags)
        assertEquals(0x01, header.extraFlags)
        assertEquals(0x00, header.multiFecFlags)
        assertEquals(0x01, header.multiFecBlocks)
        assertEquals(0x00C01140, header.fecInfo)
    }

    @Test
    fun `the flag bits decode to the meanings spec 7_4 gives them`() {
        val header = parse(headerHex)

        assertTrue(header.containsPictureData)
        assertTrue(header.isStartOfFrame)
        assertFalse(header.isEndOfFrame)
        assertTrue(header.isLongTermReferenceFrame)
    }

    @Test
    fun `fecInfo unpacks into fecIndex, fecPercentage and dataShards`() {
        // 0x00C01140 = dataShards 3 at bits 22+, fecIndex 1 at bits 12+, fecPercentage 20 at bits 4+.
        val header = parse(headerHex)

        assertEquals(3, header.dataShards)
        assertEquals(1, header.fecIndex)
        assertEquals(20, header.fecPercentage)
    }

    @Test
    fun `parityShards is the ceiling of dataShards times percentage over one hundred`() {
        // Spec §7.4: parityShards = (dataShards * fecPercentage + 99) / 100.
        assertEquals(1, header(dataShards = 3, fecPercentage = 20).parityShards)
        assertEquals(0, header(dataShards = 10, fecPercentage = 0).parityShards)
        assertEquals(1, header(dataShards = 10, fecPercentage = 1).parityShards)
        assertEquals(2, header(dataShards = 10, fecPercentage = 20).parityShards)
        assertEquals(3, header(dataShards = 10, fecPercentage = 21).parityShards)
        assertEquals(10, header(dataShards = 10, fecPercentage = 100).parityShards)
        assertEquals(4, header(dataShards = 4, fecPercentage = 100).parityShards)
    }

    @Test
    fun `dataShards uses all ten of its bits without sign extension`() {
        // The top field starts at bit 22, so a naive `fecInfo and 0xFFC00000` written as an Int
        // literal does not even compile in Kotlin — and written as a Long it sign-extends.
        val header = header(dataShards = 1023, fecPercentage = 0, fecIndex = 1022)
        assertEquals(1023, header.dataShards)
        assertEquals(1022, header.fecIndex)
        assertTrue(header.fecInfo < 0)
    }

    @Test
    fun `data and parity shards are told apart by index, not by payload type`() {
        val data = header(dataShards = 4, fecPercentage = 50, fecIndex = 3)
        val parity = header(dataShards = 4, fecPercentage = 50, fecIndex = 4)

        assertEquals(2, data.parityShards)
        assertEquals(6, data.totalShards)
        assertFalse(data.isParityShard)
        assertTrue(parity.isParityShard)
    }

    @Test
    fun `the meaningful part of streamPacketIndex is its top twenty-four bits`() {
        val header = header(dataShards = 1, streamPacketIndex = 0x12345678)
        assertEquals(0x123456, header.streamPacketIndexValue)
    }

    @Test
    fun `the multi-FEC block index comes from the low bits of multiFecFlags`() {
        // UNVERIFIED (spec §7.4, item 8). Pinning the mask here makes a change deliberate.
        assertEquals(0x3, UnverifiedRtpVideoConstants.MULTI_FEC_BLOCK_INDEX_MASK)
        assertEquals(2, header(dataShards = 1, multiFecFlags = 0xFE).multiFecBlockIndex)
        assertEquals(1, header(dataShards = 1, multiFecBlocks = 0).multiFecBlockCount)
        assertEquals(3, header(dataShards = 1, multiFecBlocks = 3).multiFecBlockCount)
    }

    @Test
    fun `implausible FEC geometry is recognised`() {
        // Zero data shards cannot describe a block.
        assertFalse(header(dataShards = 0).hasPlausibleFecGeometry)
        // A shard index outside its own block.
        assertFalse(header(dataShards = 2, fecPercentage = 0, fecIndex = 2).hasPlausibleFecGeometry)
        // More shards than GF(2^8) can address.
        assertFalse(header(dataShards = 200, fecPercentage = 100).hasPlausibleFecGeometry)
        // More FEC blocks than a two-bit index can address.
        assertFalse(header(dataShards = 2, multiFecBlocks = 8).hasPlausibleFecGeometry)
        // ... and a perfectly ordinary block is accepted.
        assertTrue(header(dataShards = 4, fecPercentage = 20, fecIndex = 3).hasPlausibleFecGeometry)
    }

    @Test
    fun `a truncated header is rejected rather than read past its end`() {
        val bytes = decode(headerHex)
        for (available in 0 until 16) {
            assertNull("available $available", NvVideoPacketHeader.parse(bytes, 0, available))
        }
        assertNull(NvVideoPacketHeader.parse(bytes, -1, 16))
    }

    @Test
    fun `frameIndex is unsigned across the whole thirty-two bit range`() {
        val header = header(dataShards = 1, frameIndex = 0xFFFFFFFFL)
        assertEquals(4294967295L, header.frameIndex)
    }

    private fun header(
        dataShards: Int,
        fecPercentage: Int = 0,
        fecIndex: Int = 0,
        multiFecFlags: Int = 0,
        multiFecBlocks: Int = 1,
        streamPacketIndex: Int = 0,
        frameIndex: Long = 1L,
    ): NvVideoPacketHeader {
        val datagram = VideoPacketFixtures.packet(
            sequenceNumber = 0,
            frameIndex = frameIndex,
            fecIndex = fecIndex,
            dataShards = dataShards,
            fecPercentage = fecPercentage,
            multiFecFlags = multiFecFlags,
            multiFecBlocks = multiFecBlocks,
            streamPacketIndex = streamPacketIndex,
        )
        return requireNotNull(NvVideoPacketHeader.parse(datagram, 12))
    }
}
