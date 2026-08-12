package com.voidlink.android.protocol.audio

/**
 * The Opus **table-of-contents byte** every packet begins with (RFC 6716 §3.1, spec §8.5).
 *
 * Spec §8.5 asks for two things that both need this byte read rather than assumed:
 *
 * * "The first byte of every Opus packet is the TOC byte and must stay constant for the whole
 *   stream. Log a warning if it changes."
 * * Packet-loss concealment must be "of exactly `packetDuration` ms to keep the timeline aligned",
 *   and `packetDuration` is a *negotiated* value. If the host is not honouring what it announced,
 *   every concealed packet drifts the timeline instead of holding it — and the TOC byte is the only
 *   place the stream itself says how long a packet is.
 *
 * ```
 * bit 7..3 : config       // bandwidth + mode + frame size, per the table below
 * bit 2    : stereo       // 0 = mono, 1 = stereo (per Opus stream, not per channel mapping)
 * bit 1..0 : frame count code
 * ```
 *
 * @property config the 5-bit configuration number, 0–31.
 * @property stereo the `s` bit.
 * @property frameCountCode the 2-bit `c` field.
 */
data class OpusToc(
    val config: Int,
    val stereo: Boolean,
    val frameCountCode: Int,
) {

    /** The mode the configuration selects, which decides the frame-size table used. */
    val mode: OpusMode
        get() = when {
            config < SILK_CONFIGS -> OpusMode.SILK
            config < SILK_CONFIGS + HYBRID_CONFIGS -> OpusMode.HYBRID
            else -> OpusMode.CELT
        }

    /**
     * The duration of **one** Opus frame, in microseconds.
     *
     * Microseconds rather than milliseconds because CELT's shortest frame is 2.5 ms, which has no
     * integer millisecond representation and which a millisecond-only API would silently round to
     * two — a 20 % error on the one path where the number exists to prevent drift.
     */
    val frameDurationMicros: Int
        get() = when (mode) {
            OpusMode.SILK -> SILK_FRAME_MICROS[config and 0x3]
            OpusMode.HYBRID -> HYBRID_FRAME_MICROS[config and 0x1]
            OpusMode.CELT -> CELT_FRAME_MICROS[config and 0x3]
        }

    /**
     * How many frames the packet carries, or `null` when the count is in the byte after the TOC.
     *
     * Codes 0–2 are self-describing; code 3 ("arbitrary") puts the count in the low six bits of the
     * following byte. [durationMicrosOf] handles both.
     */
    val frameCount: Int?
        get() = when (frameCountCode) {
            0 -> 1
            1, 2 -> 2
            else -> null
        }

    companion object {

        /** Configs 0–11 are SILK. */
        private const val SILK_CONFIGS = 12

        /** Configs 12–15 are hybrid. */
        private const val HYBRID_CONFIGS = 4

        /** SILK frame sizes, by `config and 0x3`: 10, 20, 40, 60 ms. */
        private val SILK_FRAME_MICROS = intArrayOf(10_000, 20_000, 40_000, 60_000)

        /** Hybrid frame sizes, by `config and 0x1`: 10, 20 ms. */
        private val HYBRID_FRAME_MICROS = intArrayOf(10_000, 20_000)

        /** CELT frame sizes, by `config and 0x3`: 2.5, 5, 10, 20 ms. */
        private val CELT_FRAME_MICROS = intArrayOf(2_500, 5_000, 10_000, 20_000)

        /** Mask for the frame count in the byte following a code-3 TOC. */
        private const val ARBITRARY_FRAME_COUNT_MASK = 0x3F

        /** Parses the TOC byte. Never fails: all 256 values are meaningful. */
        fun parse(tocByte: Int): OpusToc {
            val byte = tocByte and 0xFF
            return OpusToc(
                config = (byte shr 3) and 0x1F,
                stereo = (byte and 0x4) != 0,
                frameCountCode = byte and 0x3,
            )
        }

        /**
         * The total duration of the packet starting at [offset], in microseconds.
         *
         * @param data the packet bytes.
         * @param offset first byte of the Opus packet — its TOC byte.
         * @param length bytes available.
         * @return the duration, or `null` when the packet is empty or is a code-3 packet truncated
         *   before its frame-count byte.
         */
        fun durationMicrosOf(data: ByteArray, offset: Int, length: Int): Int? {
            if (length < 1 || offset < 0 || offset > data.size - length) return null
            val toc = parse(data[offset].toInt())
            val frames = toc.frameCount ?: run {
                if (length < 2) return null
                (data[offset + 1].toInt() and ARBITRARY_FRAME_COUNT_MASK)
            }
            if (frames <= 0) return null
            return toc.frameDurationMicros * frames
        }
    }
}

/** The three Opus operating modes, which is what decides a config's frame-size table. */
enum class OpusMode { SILK, HYBRID, CELT }
