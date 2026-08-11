package com.voidlink.android.protocol

/**
 * Hex encoding for the NVHTTP wire format.
 *
 * Almost every binary value in `/pair` travels as a hex string (spec §4), so this is on the hot
 * path of the one exchange we cannot afford to get wrong. Pure Kotlin, no Android dependency, so
 * it is directly unit-testable.
 *
 * Encoding is **lowercase** — matching the reference clients and the examples in spec §4.3 —
 * while decoding accepts either case, because hosts are not consistent about what they emit.
 */
object Hex {

    private const val LOWER = "0123456789abcdef"

    /**
     * Encodes [bytes] as a lowercase hex string.
     *
     * @param bytes the value to encode.
     * @param offset first byte to encode.
     * @param length number of bytes to encode.
     */
    fun encode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "encode range $offset..${offset + length} outside 0..${bytes.size}"
        }
        val out = StringBuilder(length * 2)
        for (i in offset until offset + length) {
            val v = bytes[i].toInt() and 0xFF
            out.append(LOWER[v ushr 4])
            out.append(LOWER[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * Decodes a hex string, tolerating either case and surrounding whitespace.
     *
     * @return the decoded bytes, or `null` when [text] is not valid hex. Returning `null` rather
     *   than throwing is deliberate: every caller is parsing a value a remote host sent us, and a
     *   malformed field is an ordinary protocol error, not a bug.
     */
    fun decodeOrNull(text: String?): ByteArray? {
        if (text == null) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length % 2 != 0) return null
        val out = ByteArray(trimmed.length / 2)
        for (i in out.indices) {
            val hi = digit(trimmed[i * 2])
            val lo = digit(trimmed[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
