package com.voidlink.android.protocol.rtp

/**
 * Modular arithmetic on 16-bit RTP sequence numbers (spec §7.3).
 *
 * Sequence numbers wrap every 65 536 packets, which at a real bitrate is a matter of seconds. Every
 * comparison in the receive path must therefore be a *modular* comparison: `65535 < 0` is true on
 * the wire and false in `Int`. Spec §7.4 depends on this too — `blockBaseSequenceNumber` is defined
 * as a "16-bit wrapping subtract".
 */
object SequenceNumbers {

    /** Reduces any integer to the 16-bit sequence-number range. */
    fun normalize(value: Int): Int = value and RtpVideoConstants.SEQUENCE_NUMBER_MASK

    /**
     * The signed shortest distance from [b] to [a], in `-32768..32767`.
     *
     * Positive means [a] is *after* [b]. This is the only correct way to order two sequence
     * numbers: it is right across the wrap point and wrong only for packets more than half the
     * sequence space apart, which is not reordering but a different stream.
     */
    fun difference(a: Int, b: Int): Int {
        val raw = (a - b) and RtpVideoConstants.SEQUENCE_NUMBER_MASK
        return if (raw >= RtpVideoConstants.SEQUENCE_NUMBER_MODULUS / 2) {
            raw - RtpVideoConstants.SEQUENCE_NUMBER_MODULUS
        } else {
            raw
        }
    }

    /** True when [a] is after [b] in modular order. */
    fun isAfter(a: Int, b: Int): Boolean = difference(a, b) > 0

    /** Adds [delta] to [value], wrapping. Negative deltas are fine. */
    fun advance(value: Int, delta: Int): Int = normalize(value + delta)
}
