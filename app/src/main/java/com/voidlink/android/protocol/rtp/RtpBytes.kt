package com.voidlink.android.protocol.rtp

/**
 * Unsigned reads out of a datagram, in both endiannesses (spec §0.1).
 *
 * The video header mixes them — RTP is big-endian, the NV header that follows it is
 * little-endian — and spec §0.1 calls that "the number-one bug source". Naming the endianness in
 * every call site is the cheapest defence available.
 *
 * Internal to the RTP package: nothing outside it should be reading raw datagram offsets.
 */
internal object RtpBytes {

    /** Reads one byte as an unsigned 0..255 value. */
    fun u8(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF

    /** Reads a big-endian unsigned 16-bit value. */
    fun beU16(data: ByteArray, index: Int): Int =
        (u8(data, index) shl 8) or u8(data, index + 1)

    /**
     * Reads a big-endian 32-bit value into an `Int`.
     *
     * The result carries all 32 bits; callers that need unsigned semantics widen to `Long` with
     * `and 0xFFFFFFFFL` themselves, because most of these fields (ssrc, timestamp) are compared
     * for equality rather than ordered.
     */
    fun beI32(data: ByteArray, index: Int): Int =
        (u8(data, index) shl 24) or
            (u8(data, index + 1) shl 16) or
            (u8(data, index + 2) shl 8) or
            u8(data, index + 3)

    /** Reads a little-endian 32-bit value into an `Int`. See [beI32] on signedness. */
    fun leI32(data: ByteArray, index: Int): Int =
        u8(data, index) or
            (u8(data, index + 1) shl 8) or
            (u8(data, index + 2) shl 16) or
            (u8(data, index + 3) shl 24)
}
