package com.voidlink.android.protocol.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field underneath every Reed-Solomon variant (`docs/01-PROTOCOL.md` §7.7, §8.4).
 *
 * The field is the one part of the FEC story that is *not* in doubt — both implementation families
 * the host side could descend from use GF(2^8) modulo `x^8 + x^4 + x^3 + x^2 + 1`. So it is worth
 * pinning hard: if the field is wrong, nothing built on it can be right, and every higher-level
 * test would be checking a self-consistent fiction.
 *
 * The reference values here are computed inside the test from the polynomial itself, by repeated
 * shift-and-reduce — no table lookups, nothing shared with the implementation.
 */
class GaloisField256Test {

    /** Multiplication from first principles: shift-and-add with polynomial reduction. */
    private fun referenceMultiply(a: Int, b: Int): Int {
        var result = 0
        var left = a and 0xFF
        var right = b and 0xFF
        while (right != 0) {
            if ((right and 1) != 0) result = result xor left
            right = right shr 1
            left = left shl 1
            if ((left and 0x100) != 0) left = left xor 0x11D
        }
        return result
    }

    @Test
    fun `the primitive polynomial is the one the reference implementations use`() {
        assertEquals(0x11D, GaloisField256.PRIMITIVE_POLYNOMIAL)
    }

    @Test
    fun `multiplication agrees with shift-and-reduce for every pair`() {
        for (a in 0..255) {
            for (b in 0..255) {
                assertEquals("$a * $b", referenceMultiply(a, b), GaloisField256.multiply(a, b))
            }
        }
    }

    @Test
    fun `alpha powers are the doubling sequence with reduction`() {
        // 1, 2, 4, ... 128, then 0x1D once the shift overflows eight bits.
        val expected = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 0x1D, 0x3A)
        for (power in expected.indices) {
            assertEquals("alpha^$power", expected[power], GaloisField256.alphaPower(power))
        }
    }

    @Test
    fun `alpha has order 255`() {
        assertEquals(1, GaloisField256.alphaPower(0))
        assertEquals(1, GaloisField256.alphaPower(255))
        // Every non-zero element is some power of alpha, exactly once.
        val seen = BooleanArray(256)
        for (power in 0 until 255) {
            val value = GaloisField256.alphaPower(power)
            assertTrue("alpha^$power = $value repeats", !seen[value])
            seen[value] = true
        }
        assertTrue(!seen[0])
    }

    @Test
    fun `division inverts multiplication for every non-zero divisor`() {
        for (a in 0..255) {
            for (b in 1..255) {
                assertEquals("$a / $b * $b", a, GaloisField256.multiply(GaloisField256.divide(a, b), b))
            }
        }
    }

    @Test
    fun `every non-zero element has an inverse`() {
        for (a in 1..255) {
            assertEquals("inverse of $a", 1, GaloisField256.multiply(a, GaloisField256.inverse(a)))
        }
    }

    @Test
    fun `exponentiation matches repeated multiplication and treats zero to the zero as one`() {
        assertEquals(1, GaloisField256.power(0, 0))
        assertEquals(0, GaloisField256.power(0, 1))
        assertEquals(1, GaloisField256.power(7, 0))
        for (base in 0..255) {
            var expected = 1
            for (exponent in 0..8) {
                assertEquals("$base^$exponent", expected, GaloisField256.power(base, exponent))
                expected = referenceMultiply(expected, base)
            }
        }
    }

    @Test
    fun `zero annihilates and one is the identity`() {
        for (a in 0..255) {
            assertEquals(0, GaloisField256.multiply(a, 0))
            assertEquals(0, GaloisField256.multiply(0, a))
            assertEquals(a, GaloisField256.multiply(a, 1))
        }
    }
}
