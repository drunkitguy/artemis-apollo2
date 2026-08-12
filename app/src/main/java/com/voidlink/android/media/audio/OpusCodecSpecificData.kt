package com.voidlink.android.media.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The three codec-specific-data buffers `MediaCodec`'s Opus decoder requires (spec §8.5).
 *
 * ```
 * csd-0 = the OpusHead identification header      (19 bytes for mono/stereo)
 * csd-1 = pre-skip, in nanoseconds                (int64, LITTLE-endian)
 * csd-2 = seek pre-roll, in nanoseconds           (int64, LITTLE-endian, typically 80 000 000)
 * ```
 *
 * **Getting this wrong gives silence or noise, and no error.** The decoder configures happily, the
 * track plays happily, and nothing is audible or everything is static — which is why every byte
 * here is produced by a pure function over an [AudioStreamFormat] and pinned by unit tests, rather
 * than assembled inline at the one call site that needs it.
 *
 * The host does not send any of this; spec §8.5 gives the layout and we construct it:
 *
 * ```
 * "OpusHead"          8 bytes ASCII
 * version = 1         1 byte
 * channelCount        1 byte
 * preSkip             2 bytes LE
 * sampleRate          4 bytes LE
 * outputGain = 0      2 bytes LE
 * mappingFamily       1 byte      (0 for mono/stereo)
 * --- 19 bytes ---
 * streamCount         1 byte      \
 * coupledCount        1 byte       >  family 1 only (surround)
 * mapping[N]          N bytes     /
 * ```
 *
 * Every multi-byte field is **little-endian**, which is the one thing about this structure that is
 * not shared with the rest of the protocol: the RTP headers around it are big-endian (spec §0.1),
 * and OpusHead is defined by RFC 7845, not by GameStream.
 */
object OpusCodecSpecificData {

    /** The eight ASCII bytes an identification header starts with. */
    const val MAGIC: String = "OpusHead"

    /** The only OpusHead version there is. */
    const val VERSION: Int = 1

    /** Length of a mapping-family-0 (mono/stereo) identification header. */
    const val IDENTIFICATION_HEADER_SIZE: Int = 19

    /** Extra bytes a family-1 header carries before its mapping table. */
    const val MULTISTREAM_PREFIX_SIZE: Int = 2

    /** Mapping family 0 — mono or stereo, no mapping table (spec §8.5). */
    const val MAPPING_FAMILY_NONE: Int = 0

    /** Mapping family 1 — Vorbis channel order, with a mapping table (spec §8.5). */
    const val MAPPING_FAMILY_VORBIS: Int = 1

    /**
     * The pre-skip written into OpusHead, in samples.
     *
     * Spec §8.5: "`preSkip = 0` — 312 is the common default; 0 works because we never seek." Pre-skip
     * exists so a decoder can discard the encoder's start-up transient when a file is played from
     * the beginning or seeked into. A live stream has neither a beginning we care about nor a seek,
     * and asking the decoder to discard 312 samples of a stream that started mid-sentence would
     * remove 6.5 ms of real audio.
     *
     * [preSkipNanos] derives csd-1 from this same value, so the two can never disagree — a mismatch
     * between the header's pre-skip and the codec delay is exactly the kind of inconsistency that
     * produces silence on one vendor's decoder and works everywhere else.
     */
    const val PRE_SKIP_SAMPLES: Int = 0

    /**
     * The seek pre-roll, in nanoseconds (spec §8.5's "typically 80 000 000").
     *
     * 80 ms is the value RFC 7845 recommends and every Opus container writes. Nothing seeks here, so
     * it is inert, but `MediaCodec`'s Opus decoder requires csd-2 to be present and well-formed.
     */
    const val SEEK_PRE_ROLL_NANOS: Long = 80_000_000L

    private const val NANOS_PER_SECOND: Long = 1_000_000_000L
    private const val LONG_BYTES: Int = 8

    /**
     * Builds **csd-0**, the OpusHead identification header.
     *
     * For [AudioStreamFormat.isMultistream] formats this is the family-1 form: 19 bytes, then the
     * stream count, the coupled count, and one byte per channel of the mapping table — which is the
     * part spec §8.5 warns "MediaCodec implementations handle badly", and which is built correctly
     * here anyway so that the day a device is verified, nothing needs writing.
     *
     * The mapping is written in the order it is given, which must be **playback order**
     * (`FL FR C LFE RL RR SL SR`). See [com.voidlink.android.protocol.audio.OpusChannelOrder].
     */
    fun identificationHeader(format: AudioStreamFormat): ByteArray {
        val family = if (format.isMultistream) MAPPING_FAMILY_VORBIS else MAPPING_FAMILY_NONE
        val size = if (family == MAPPING_FAMILY_NONE) {
            IDENTIFICATION_HEADER_SIZE
        } else {
            IDENTIFICATION_HEADER_SIZE + MULTISTREAM_PREFIX_SIZE + format.channelCount
        }

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        for (character in MAGIC) {
            buffer.put(character.code.toByte())
        }
        buffer.put(VERSION.toByte())
        buffer.put(format.channelCount.toByte())
        buffer.putShort(PRE_SKIP_SAMPLES.toShort())
        buffer.putInt(format.sampleRateHz)
        buffer.putShort(OUTPUT_GAIN_Q7_8.toShort())
        buffer.put(family.toByte())

        if (family == MAPPING_FAMILY_VORBIS) {
            buffer.put(format.streams.toByte())
            buffer.put(format.coupledStreams.toByte())
            for (index in 0 until format.channelCount) {
                buffer.put(format.mapping[index].toByte())
            }
        }
        return buffer.array()
    }

    /**
     * Builds **csd-1**: the pre-skip as an int64 little-endian nanosecond count.
     *
     * Derived from [PRE_SKIP_SAMPLES] and the format's sample rate rather than hard-coded, so that
     * changing the pre-skip changes both buffers at once.
     */
    fun preSkipNanos(format: AudioStreamFormat): ByteArray =
        longLittleEndian(nanosForSamples(PRE_SKIP_SAMPLES, format.sampleRateHz))

    /** Builds **csd-2**: the seek pre-roll as an int64 little-endian nanosecond count. */
    fun seekPreRollNanos(): ByteArray = longLittleEndian(SEEK_PRE_ROLL_NANOS)

    /** Converts a sample count at [sampleRateHz] to nanoseconds, rounding down. */
    fun nanosForSamples(samples: Int, sampleRateHz: Int): Long =
        samples.toLong() * NANOS_PER_SECOND / sampleRateHz.toLong()

    /** An `int64` in little-endian byte order, which is what both csd-1 and csd-2 are. */
    fun longLittleEndian(value: Long): ByteArray =
        ByteBuffer.allocate(LONG_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    /**
     * `outputGain`, a Q7.8 fixed-point decibel adjustment.
     *
     * Zero, always. The host has already mixed and levelled the stream, and applying a gain here
     * would be a second, invisible volume control.
     */
    private const val OUTPUT_GAIN_Q7_8: Int = 0
}
