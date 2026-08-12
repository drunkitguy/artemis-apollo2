package com.voidlink.android.protocol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Opus TOC byte (RFC 6716 §3.1), which spec §8.5 depends on twice: to notice the byte changing
 * mid-stream, and to check the negotiated packet duration against what the stream actually carries.
 */
class OpusTocTest {

    @Test
    fun `the three fields are unpacked from the right bits`() {
        // 0b11101_1_10 = config 29, stereo, frame count code 2.
        val toc = OpusToc.parse(0xEE)

        assertEquals(29, toc.config)
        assertTrue(toc.stereo)
        assertEquals(2, toc.frameCountCode)
    }

    @Test
    fun `mono is the absence of the stereo bit, not a separate config`() {
        assertTrue(OpusToc.parse(0xEC).stereo)
        assertEquals(false, OpusToc.parse(0xE8).stereo)
    }

    @Test
    fun `configs 0 to 11 are SILK with the 10-20-40-60 frame table`() {
        assertEquals(OpusMode.SILK, OpusToc.parse(0 shl 3).mode)
        assertEquals(10_000, OpusToc.parse(0 shl 3).frameDurationMicros)
        assertEquals(20_000, OpusToc.parse(1 shl 3).frameDurationMicros)
        assertEquals(40_000, OpusToc.parse(2 shl 3).frameDurationMicros)
        assertEquals(60_000, OpusToc.parse(3 shl 3).frameDurationMicros)
        assertEquals(OpusMode.SILK, OpusToc.parse(11 shl 3).mode)
        assertEquals(60_000, OpusToc.parse(11 shl 3).frameDurationMicros)
    }

    @Test
    fun `configs 12 to 15 are hybrid with the 10-20 frame table`() {
        assertEquals(OpusMode.HYBRID, OpusToc.parse(12 shl 3).mode)
        assertEquals(10_000, OpusToc.parse(12 shl 3).frameDurationMicros)
        assertEquals(20_000, OpusToc.parse(13 shl 3).frameDurationMicros)
        assertEquals(OpusMode.HYBRID, OpusToc.parse(15 shl 3).mode)
        assertEquals(20_000, OpusToc.parse(15 shl 3).frameDurationMicros)
    }

    @Test
    fun `configs 16 to 31 are CELT with the 2_5-5-10-20 frame table`() {
        assertEquals(OpusMode.CELT, OpusToc.parse(16 shl 3).mode)
        assertEquals(2_500, OpusToc.parse(16 shl 3).frameDurationMicros)
        assertEquals(5_000, OpusToc.parse(17 shl 3).frameDurationMicros)
        assertEquals(10_000, OpusToc.parse(18 shl 3).frameDurationMicros)
        assertEquals(20_000, OpusToc.parse(19 shl 3).frameDurationMicros)
        assertEquals(OpusMode.CELT, OpusToc.parse(31 shl 3).mode)
        assertEquals(20_000, OpusToc.parse(31 shl 3).frameDurationMicros)
    }

    @Test
    fun `2_5 ms is expressible because durations are microseconds`() {
        // The whole reason frameDurationMicros is not frameDurationMillis: rounding CELT's shortest
        // frame to an integer millisecond is a 20% error on the value that prevents drift.
        assertEquals(2_500, OpusToc.parse(24 shl 3).frameDurationMicros)
    }

    @Test
    fun `the default GameStream packet is 5 ms of CELT full-band stereo`() {
        val duration = OpusToc.durationMicrosOf(
            byteArrayOf(AudioPacketFixtures.TOC_CELT_FB_5MS_STEREO.toByte(), 0x00),
            0,
            2,
        )

        assertEquals(RtpAudioConstants.DEFAULT_PACKET_DURATION_MS * 1_000, duration)
    }

    @Test
    fun `frame count code 0 is one frame and codes 1 and 2 are two`() {
        assertEquals(1, OpusToc.parse(0xEC).frameCount)
        assertEquals(2, OpusToc.parse(0xED).frameCount)
        assertEquals(2, OpusToc.parse(0xEE).frameCount)
        assertNull(OpusToc.parse(0xEF).frameCount)
    }

    @Test
    fun `code 3 reads its frame count from the low six bits of the next byte`() {
        // 0xEF is config 29 (5 ms CELT FB), stereo, code 3. The following byte's low six bits say
        // four frames, so the packet is 20 ms.
        val packet = byteArrayOf(0xEF.toByte(), 0xC4.toByte(), 0x00)

        assertEquals(20_000, OpusToc.durationMicrosOf(packet, 0, packet.size))
    }

    @Test
    fun `a code 3 packet truncated before its count byte has no duration rather than a wrong one`() {
        assertNull(OpusToc.durationMicrosOf(byteArrayOf(0xEF.toByte()), 0, 1))
    }

    @Test
    fun `an empty packet has no duration`() {
        assertNull(OpusToc.durationMicrosOf(ByteArray(0), 0, 0))
    }

    @Test
    fun `the duration is read at the offset it is given, not at zero`() {
        val datagram = ByteArray(20)
        datagram[12] = AudioPacketFixtures.TOC_CELT_FB_5MS_STEREO.toByte()

        assertEquals(5_000, OpusToc.durationMicrosOf(datagram, 12, 8))
    }
}
