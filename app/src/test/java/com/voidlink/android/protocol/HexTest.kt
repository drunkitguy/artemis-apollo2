package com.voidlink.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the hex codec every `/pair` value travels through (spec §4).
 */
class HexTest {

    @Test
    fun `encoding produces lowercase hex`() {
        assertEquals("00ff107f80", Hex.encode(byteArrayOf(0, -1, 0x10, 0x7F, -128)))
        assertEquals("", Hex.encode(ByteArray(0)))
        assertEquals("deadbeef", Hex.encode(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
    }

    @Test
    fun `encoding honours an offset and length`() {
        val bytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        assertEquals("2233", Hex.encode(bytes, offset = 1, length = 2))
        assertEquals("", Hex.encode(bytes, offset = 4, length = 0))
        assertEquals("11223344", Hex.encode(bytes, offset = 0))
    }

    @Test
    fun `encoding rejects a range outside the array`() {
        val bytes = byteArrayOf(1, 2, 3)
        val failure = runCatching { Hex.encode(bytes, offset = 2, length = 5) }
        assertEquals(true, failure.isFailure)
    }

    @Test
    fun `decoding accepts either case`() {
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), Hex.decodeOrNull("abcd"))
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), Hex.decodeOrNull("ABCD"))
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), Hex.decodeOrNull("AbCd"))
    }

    @Test
    fun `decoding tolerates surrounding whitespace, which hosts sometimes emit`() {
        assertArrayEquals(byteArrayOf(0x01, 0x02), Hex.decodeOrNull("  0102\n"))
    }

    @Test
    fun `decoding rejects malformed input by returning null`() {
        // A malformed field from a remote host is an ordinary protocol error, not an exception.
        assertNull(Hex.decodeOrNull(null))
        assertNull(Hex.decodeOrNull(""))
        assertNull(Hex.decodeOrNull("   "))
        assertNull(Hex.decodeOrNull("abc"))
        assertNull(Hex.decodeOrNull("zz"))
        assertNull(Hex.decodeOrNull("0x1234"))
        assertNull(Hex.decodeOrNull("12 34"))
    }

    @Test
    fun `encode and decode round-trip every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Hex.decodeOrNull(Hex.encode(all)))
    }
}
