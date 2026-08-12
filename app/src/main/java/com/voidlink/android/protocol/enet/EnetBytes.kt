package com.voidlink.android.protocol.enet

/**
 * Byte access for the ENet wire format, with the byte order spelled out at every call site.
 *
 * `docs/01-PROTOCOL.md` §0.1 opens with the warning that byte order is "the number-one bug source"
 * and instructs that no code may rely on a default. ENet itself is uniformly **network byte order**
 * (big-endian) for every multi-byte field of its protocol header and command structs — the
 * little-endian fields in §0.1's table (`Control (ENet) packet type + length`) belong to the
 * GameStream control message that travels *inside* an ENet packet payload, not to ENet's own
 * framing. Those two layers are easy to confuse, so every accessor here carries the `Be` suffix and
 * there is deliberately no order-less variant to reach for.
 *
 * All structs are `#pragma pack(1)` (spec §0.2): fields are written back-to-back with no padding.
 */
object EnetBytes {

    /** Writes the low 8 bits of [value] at [offset]. */
    fun putU8(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
    }

    /** Reads one byte at [offset] as an unsigned 0..255 value. */
    fun getU8(src: ByteArray, offset: Int): Int = src[offset].toInt() and 0xFF

    /** Writes the low 16 bits of [value] at [offset], most significant byte first. */
    fun putU16Be(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 1] = (value and 0xFF).toByte()
    }

    /** Reads two big-endian bytes at [offset] as an unsigned 0..65535 value. */
    fun getU16Be(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 8) or (src[offset + 1].toInt() and 0xFF)

    /** Writes all 32 bits of [value] at [offset], most significant byte first. */
    fun putU32Be(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = ((value ushr 24) and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }

    /**
     * Reads four big-endian bytes at [offset].
     *
     * The result is a raw [Int]: ENet's 32-bit fields (MTU, window size, fragment counts) never
     * legitimately exceed 2^31, and the two that are genuinely opaque — `connectID` and the
     * CONNECT `data` word — are compared and echoed, never ordered. Use [getU32BeAsLong] where a
     * value must be treated as unsigned.
     */
    fun getU32Be(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 24) or
            ((src[offset + 1].toInt() and 0xFF) shl 16) or
            ((src[offset + 2].toInt() and 0xFF) shl 8) or
            (src[offset + 3].toInt() and 0xFF)

    /** Reads four big-endian bytes at [offset] as an unsigned 0..4294967295 value. */
    fun getU32BeAsLong(src: ByteArray, offset: Int): Long =
        getU32Be(src, offset).toLong() and 0xFFFFFFFFL
}
