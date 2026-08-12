package com.voidlink.android.protocol.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modular arithmetic on 16-bit RTP sequence numbers (`docs/01-PROTOCOL.md` §7.3, §7.4).
 *
 * Every one of these cases is a wraparound case, because wraparound is the only thing that makes
 * sequence-number comparison non-obvious — and at any real bitrate it happens every few seconds, so
 * "we'll deal with it later" means "we'll ship a bug that fires constantly".
 */
class SequenceNumbersTest {

    @Test
    fun `ordinary distances are plain subtraction`() {
        assertEquals(1, SequenceNumbers.difference(11, 10))
        assertEquals(-1, SequenceNumbers.difference(10, 11))
        assertEquals(0, SequenceNumbers.difference(10, 10))
        assertEquals(100, SequenceNumbers.difference(1100, 1000))
    }

    @Test
    fun `distances are correct across the wrap point`() {
        assertEquals(1, SequenceNumbers.difference(0, 65535))
        assertEquals(-1, SequenceNumbers.difference(65535, 0))
        assertEquals(2, SequenceNumbers.difference(1, 65535))
        assertEquals(5, SequenceNumbers.difference(4, 65535))
        assertEquals(-5, SequenceNumbers.difference(65535, 4))
    }

    @Test
    fun `ordering is correct across the wrap point`() {
        assertTrue(SequenceNumbers.isAfter(0, 65535))
        assertFalse(SequenceNumbers.isAfter(65535, 0))
        assertTrue(SequenceNumbers.isAfter(65535, 65534))
        assertFalse(SequenceNumbers.isAfter(10, 10))
    }

    @Test
    fun `the half-way point is the boundary between ahead and behind`() {
        assertEquals(32767, SequenceNumbers.difference(32767, 0))
        assertEquals(-32768, SequenceNumbers.difference(32768, 0))
        assertTrue(SequenceNumbers.isAfter(32767, 0))
        assertFalse(SequenceNumbers.isAfter(32768, 0))
    }

    @Test
    fun `advancing wraps in both directions`() {
        assertEquals(0, SequenceNumbers.advance(65535, 1))
        assertEquals(4, SequenceNumbers.advance(65535, 5))
        assertEquals(65533, SequenceNumbers.advance(2, -5))
        assertEquals(65535, SequenceNumbers.advance(0, -1))
    }

    @Test
    fun `normalize keeps values inside sixteen bits`() {
        assertEquals(0, SequenceNumbers.normalize(65536))
        assertEquals(65535, SequenceNumbers.normalize(-1))
        assertEquals(1, SequenceNumbers.normalize(65537))
    }

    @Test
    fun `difference and advance are inverses everywhere on the circle`() {
        // An exhaustive-enough sweep: every offset from a handful of bases, including the wrap.
        val bases = intArrayOf(0, 1, 100, 32767, 32768, 65534, 65535)
        for (base in bases) {
            for (delta in -300..300) {
                val moved = SequenceNumbers.advance(base, delta)
                assertEquals("base $base delta $delta", delta, SequenceNumbers.difference(moved, base))
            }
        }
    }
}
