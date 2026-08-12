package com.voidlink.android.media.audio

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The three codec-specific-data buffers of `docs/01-PROTOCOL.md` §8.5, byte for byte.
 *
 * Byte-for-byte on purpose. Wrong CSD does not fail — it configures cleanly and then produces
 * silence or static, with no error anywhere to look at — so the only test worth having here is one
 * that compares against the literal bytes the spec writes out.
 */
class OpusCodecSpecificDataTest {

    private fun hex(bytes: ByteArray): String = Hex.encode(bytes)

    @Test
    fun `csd-0 for stereo is the 19-byte OpusHead spec section 8_5 writes out`() {
        val csd0 = OpusCodecSpecificData.identificationHeader(AudioStreamFormat.stereo())

        assertEquals(19, csd0.size)
        assertEquals(
            // "OpusHead"                version=1  channels=2  preSkip=0  sampleRate=48000 LE
            "4f70757348656164" + "01" + "02" + "0000" + "80bb0000" +
                // outputGain=0 LE  mappingFamily=0
                "0000" + "00",
            hex(csd0),
        )
    }

    @Test
    fun `the magic is ASCII OpusHead and nothing else`() {
        val csd0 = OpusCodecSpecificData.identificationHeader(AudioStreamFormat.stereo())

        assertEquals(
            OpusCodecSpecificData.MAGIC,
            String(csd0, 0, 8, StandardCharsets.US_ASCII),
        )
    }

    @Test
    fun `the sample rate is little-endian, unlike every RTP field around it`() {
        // 48000 = 0x0000BB80. Big-endian would be 0000bb80; little-endian is 80bb0000. Spec §0.1
        // calls endianness the number-one bug source, and OpusHead is RFC 7845, not GameStream.
        val csd0 = OpusCodecSpecificData.identificationHeader(AudioStreamFormat.stereo())

        assertEquals("80bb0000", hex(csd0.copyOfRange(12, 16)))
    }

    @Test
    fun `pre-skip is zero, because a live stream is never seeked`() {
        val csd0 = OpusCodecSpecificData.identificationHeader(AudioStreamFormat.stereo())

        assertEquals(0, OpusCodecSpecificData.PRE_SKIP_SAMPLES)
        assertEquals("0000", hex(csd0.copyOfRange(10, 12)))
    }

    @Test
    fun `csd-1 is the pre-skip in nanoseconds as a little-endian int64`() {
        val csd1 = OpusCodecSpecificData.preSkipNanos(AudioStreamFormat.stereo())

        assertEquals(8, csd1.size)
        assertEquals("0000000000000000", hex(csd1))
    }

    @Test
    fun `csd-1 is derived from csd-0's pre-skip, so the two cannot disagree`() {
        // A header saying "discard 312 samples" beside a codec delay of zero is the kind of
        // inconsistency that plays silently on one vendor's decoder and fine on every other.
        val format = AudioStreamFormat.stereo()
        val csd0 = OpusCodecSpecificData.identificationHeader(format)
        val preSkipFromHeader =
            (csd0[10].toInt() and 0xFF) or ((csd0[11].toInt() and 0xFF) shl 8)

        val expected = OpusCodecSpecificData.nanosForSamples(preSkipFromHeader, format.sampleRateHz)

        assertArrayEquals(
            OpusCodecSpecificData.longLittleEndian(expected),
            OpusCodecSpecificData.preSkipNanos(format),
        )
    }

    @Test
    fun `csd-2 is 80 ms of seek pre-roll as a little-endian int64`() {
        val csd2 = OpusCodecSpecificData.seekPreRollNanos()

        assertEquals(8, csd2.size)
        assertEquals(80_000_000L, OpusCodecSpecificData.SEEK_PRE_ROLL_NANOS)
        // 80 000 000 = 0x04C4B400, little-endian across eight bytes.
        assertEquals("00b4c40400000000", hex(csd2))
    }

    @Test
    fun `nanosForSamples converts at the stream's own sample rate`() {
        assertEquals(6_500_000L, OpusCodecSpecificData.nanosForSamples(312, 48_000))
        assertEquals(1_000_000_000L, OpusCodecSpecificData.nanosForSamples(48_000, 48_000))
        assertEquals(0L, OpusCodecSpecificData.nanosForSamples(0, 48_000))
    }

    @Test
    fun `5_1 uses mapping family 1 and appends the stream counts and the mapping table`() {
        val format = AudioStreamFormat(
            channelCount = 6,
            streams = 4,
            coupledStreams = 2,
            // Playback order: FL FR C LFE RL RR.
            mapping = intArrayOf(0, 4, 1, 5, 2, 3),
        )

        val csd0 = OpusCodecSpecificData.identificationHeader(format)

        assertEquals(19 + 2 + 6, csd0.size)
        assertEquals(
            "4f70757348656164" + "01" + "06" + "0000" + "80bb0000" + "0000" +
                // mappingFamily=1, streams=4, coupled=2, then the mapping table verbatim
                "01" + "04" + "02" + "000401050203",
            hex(csd0),
        )
    }

    @Test
    fun `7_1 appends eight mapping bytes`() {
        val format = AudioStreamFormat(
            channelCount = 8,
            streams = 5,
            coupledStreams = 3,
            mapping = intArrayOf(0, 1, 2, 7, 3, 4, 5, 6),
        )

        val csd0 = OpusCodecSpecificData.identificationHeader(format)

        assertEquals(19 + 2 + 8, csd0.size)
        assertEquals(OpusCodecSpecificData.MAPPING_FAMILY_VORBIS, csd0[18].toInt())
        assertEquals(5, csd0[19].toInt())
        assertEquals(3, csd0[20].toInt())
        assertArrayEquals(
            byteArrayOf(0, 1, 2, 7, 3, 4, 5, 6),
            csd0.copyOfRange(21, 29),
        )
    }

    @Test
    fun `the mapping table is written in the order it is given, not sorted`() {
        // The whole point of the channel-order fix-up is that positions carry meaning. A builder
        // that normalised the table would undo it silently.
        val format = AudioStreamFormat(
            channelCount = 6,
            streams = 4,
            coupledStreams = 2,
            mapping = intArrayOf(0, 1, 2, 5, 3, 4),
        )

        val csd0 = OpusCodecSpecificData.identificationHeader(format)

        assertArrayEquals(byteArrayOf(0, 1, 2, 5, 3, 4), csd0.copyOfRange(21, 27))
    }

    @Test
    fun `a stereo header carries no mapping table at all`() {
        val csd0 = OpusCodecSpecificData.identificationHeader(AudioStreamFormat.stereo())

        assertEquals(OpusCodecSpecificData.MAPPING_FAMILY_NONE, csd0[18].toInt())
        assertEquals(OpusCodecSpecificData.IDENTIFICATION_HEADER_SIZE, csd0.size)
    }
}
