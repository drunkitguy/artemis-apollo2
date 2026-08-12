package com.voidlink.android.protocol.rtp

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Reed-Solomon over GF(2^8) (`docs/01-PROTOCOL.md` §7.7, §8.4; `04-ROADMAP.md` phase 11).
 *
 * Spec §7.7 calls the exact RS variant "the single riskiest detail in the whole document", and the
 * reason is that a wrong matrix does not fail — it silently produces corrupt frames. So this file
 * checks two genuinely different things, because either alone would be misleading:
 *
 * 1. **Self-consistency.** Our `encode` followed by our `decode` reconstructs the originals byte
 *    for byte, for every erasure pattern we can survive — exhaustively for small blocks, randomised
 *    for large ones. This proves the code is a correct erasure code. It proves nothing at all about
 *    interoperability: a code built on the wrong matrix passes this perfectly.
 * 2. **Known-answer vectors.** Committed parity bytes, and the generator-matrix rows themselves,
 *    checked against an independent GF(2^8) implementation written inside this test from the
 *    primitive polynomial upward. This is what pins the implementation to a *specific, documented*
 *    matrix rather than merely a self-consistent one — and interoperability depends on exactly
 *    that.
 *
 * What no test here can prove is that the **host** uses the same matrix. That is why FEC recovery
 * ships disabled ([UnverifiedRtpVideoConstants.FEC_RECOVERY_ENABLED_BY_DEFAULT]) and why the
 * variant is a named, swappable enum.
 */
class ReedSolomonTest {

    // ---- An independent GF(2^8), built here from the polynomial ----------------------------

    private val referenceExp = IntArray(512)
    private val referenceLog = IntArray(256)

    init {
        var value = 1
        for (power in 0 until 255) {
            referenceExp[power] = value
            referenceLog[value] = power
            value = value shl 1
            if ((value and 0x100) != 0) value = value xor 0x11D
        }
        for (power in 255 until 512) referenceExp[power] = referenceExp[power - 255]
    }

    private fun refMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else referenceExp[referenceLog[a] + referenceLog[b]]

    private fun refDiv(a: Int, b: Int): Int =
        if (a == 0) 0 else referenceExp[(referenceLog[a] - referenceLog[b] + 255) % 255]

    /** Gauss-Jordan inversion, written here so it shares nothing with the implementation. */
    private fun refInvert(matrix: Array<IntArray>): Array<IntArray> {
        val size = matrix.size
        val work = Array(size) { row ->
            IntArray(size * 2) { column ->
                if (column < size) matrix[row][column] else if (column - size == row) 1 else 0
            }
        }
        for (column in 0 until size) {
            var pivot = column
            while (work[pivot][column] == 0) pivot++
            val swap = work[pivot]
            work[pivot] = work[column]
            work[column] = swap
            val scale = refDiv(1, work[column][column])
            for (index in 0 until size * 2) {
                work[column][index] = refMul(work[column][index], scale)
            }
            for (row in 0 until size) {
                if (row == column) continue
                val factor = work[row][column]
                if (factor == 0) continue
                for (index in 0 until size * 2) {
                    work[row][index] = work[row][index] xor refMul(factor, work[column][index])
                }
            }
        }
        return Array(size) { row -> IntArray(size) { column -> work[row][size + column] } }
    }

    /**
     * The systematic generator matrix, computed independently.
     *
     * `ALPHA_POWER_ROWS`: row 0 is `e0`, row `r` is `alpha^((r-1)*c)`.
     * `INTEGER_POWER_ROWS`: row `r` is `r^c`.
     */
    private fun refMatrix(
        dataShards: Int,
        parityShards: Int,
        variant: ReedSolomonMatrix,
    ): Array<IntArray> {
        val total = dataShards + parityShards
        val vandermonde = Array(total) { IntArray(dataShards) }
        if (variant == ReedSolomonMatrix.ALPHA_POWER_ROWS) {
            vandermonde[0][0] = 1
            for (row in 1 until total) {
                for (column in 0 until dataShards) {
                    vandermonde[row][column] = referenceExp[((row - 1) * column) % 255]
                }
            }
        } else {
            for (row in 0 until total) {
                for (column in 0 until dataShards) {
                    var power = 1
                    for (step in 0 until column) power = refMul(power, row)
                    vandermonde[row][column] = power
                }
            }
        }
        val inverse = refInvert(Array(dataShards) { vandermonde[it].copyOf() })
        return Array(total) { row ->
            IntArray(dataShards) { column ->
                var sum = 0
                for (term in 0 until dataShards) {
                    sum = sum xor refMul(vandermonde[row][term], inverse[term][column])
                }
                sum
            }
        }
    }

    private fun refEncode(
        data: List<ByteArray>,
        parityShards: Int,
        variant: ReedSolomonMatrix,
    ): List<ByteArray> {
        val matrix = refMatrix(data.size, parityShards, variant)
        val shardSize = data[0].size
        return (data.size until data.size + parityShards).map { row ->
            val out = ByteArray(shardSize)
            for (column in data.indices) {
                val factor = matrix[row][column]
                if (factor == 0) continue
                for (index in 0 until shardSize) {
                    val contribution = refMul(factor, data[column][index].toInt() and 0xFF)
                    out[index] = ((out[index].toInt() and 0xFF) xor contribution).toByte()
                }
            }
            out
        }
    }

    // ---- Known-answer vectors ---------------------------------------------------------------

    /**
     * `(dataShards, parityShards, data, expected parity)` for the default matrix.
     *
     * The parity bytes are committed literals. They were derived from the field arithmetic and the
     * matrix construction the spec's `fec.c`/`nanors` lineage documents, and every one of them is
     * re-derived by [refEncode] in the test below — so a bug would have to appear identically in
     * three independent places to slip through.
     */
    private val knownAnswers = listOf(
        KnownAnswer(
            data = listOf("0001020304050607", "1011121314151617", "2021222324252627", "3031323334353637"),
            parity = listOf("1a1b18191e1f1c1d", "9091929394959697"),
        ),
        KnownAnswer(
            data = listOf("ffffffff", "00000000", "a55aa55a"),
            parity = listOf("95ad95ad", "744f744f"),
        ),
        KnownAnswer(
            data = listOf("dead", "beef"),
            parity = listOf("1e29"),
        ),
        KnownAnswer(
            data = listOf(
                "000102030405060708090a0b0c0d0e0f",
                "101112131415161718191a1b1c1d1e1f",
                "202122232425262728292a2b2c2d2e2f",
                "303132333435363738393a3b3c3d3e3f",
                "404142434445464748494a4b4c4d4e4f",
            ),
            parity = listOf(
                "72737071767774757a7b78797e7f7c7d",
                "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf",
                "414043424544474649484b4a4d4c4f4e",
            ),
        ),
    )

    private class KnownAnswer(val data: List<String>, val parity: List<String>)

    private fun decode(hex: String): ByteArray = requireNotNull(Hex.decodeOrNull(hex))

    @Test
    fun `the default matrix variant is the one the spec's lineage documents`() {
        assertEquals(
            ReedSolomonMatrix.ALPHA_POWER_ROWS,
            UnverifiedRtpVideoConstants.FEC_MATRIX_VARIANT,
        )
    }

    @Test
    fun `encoded parity matches the committed known-answer vectors`() {
        for (vector in knownAnswers) {
            val data = vector.data.map { decode(it) }
            val codec = ReedSolomon.create(
                data.size,
                vector.parity.size,
                ReedSolomonMatrix.ALPHA_POWER_ROWS,
            )
            val shards = arrayOfNulls<ByteArray>(codec.totalShards)
            for (index in data.indices) shards[index] = data[index]
            codec.encodeParity(shards, data[0].size)

            for (index in vector.parity.indices) {
                assertEquals(
                    "k=${data.size} parity shard $index",
                    vector.parity[index],
                    Hex.encode(requireNotNull(shards[data.size + index])),
                )
            }
        }
    }

    @Test
    fun `the committed vectors are reproduced by an independent field implementation`() {
        for (vector in knownAnswers) {
            val data = vector.data.map { decode(it) }
            val parity = refEncode(data, vector.parity.size, ReedSolomonMatrix.ALPHA_POWER_ROWS)
            for (index in vector.parity.indices) {
                assertEquals(
                    "k=${data.size} parity shard $index",
                    vector.parity[index],
                    Hex.encode(parity[index]),
                )
            }
        }
    }

    @Test
    fun `the generator matrix is systematic - its top block is the identity`() {
        for (variant in ReedSolomonMatrix.values()) {
            val codec = ReedSolomon.create(6, 3, variant)
            for (row in 0 until 6) {
                val expected = IntArray(6) { if (it == row) 1 else 0 }
                assertArrayEquals("$variant row $row", expected, codec.matrixRow(row))
            }
        }
    }

    @Test
    fun `the alpha-power parity rows match the independent computation`() {
        val codec = ReedSolomon.create(4, 2, ReedSolomonMatrix.ALPHA_POWER_ROWS)
        // Committed literals, so a change to the matrix construction cannot pass silently.
        assertArrayEquals(intArrayOf(0x77, 0x40, 0x38, 0x0E), codec.matrixRow(4))
        assertArrayEquals(intArrayOf(0xC7, 0xA7, 0x0D, 0x6C), codec.matrixRow(5))

        val reference = refMatrix(4, 2, ReedSolomonMatrix.ALPHA_POWER_ROWS)
        assertArrayEquals(reference[4], codec.matrixRow(4))
        assertArrayEquals(reference[5], codec.matrixRow(5))
    }

    @Test
    fun `the integer-power parity rows match the published Backblaze matrix`() {
        // The only external reference point available without a host: the 4+2 matrix published by
        // the Backblaze/klauspost lineage is [27, 28, 18, 20] and [28, 27, 20, 18]. Reproducing it
        // proves the field, the inversion and the multiply are all correct — independently of which
        // variant we actually ship.
        val codec = ReedSolomon.create(4, 2, ReedSolomonMatrix.INTEGER_POWER_ROWS)
        assertArrayEquals(intArrayOf(27, 28, 18, 20), codec.matrixRow(4))
        assertArrayEquals(intArrayOf(28, 27, 20, 18), codec.matrixRow(5))
    }

    @Test
    fun `the two variants really are incompatible, which is the whole risk`() {
        val alpha = ReedSolomon.create(4, 2, ReedSolomonMatrix.ALPHA_POWER_ROWS)
        val integer = ReedSolomon.create(4, 2, ReedSolomonMatrix.INTEGER_POWER_ROWS)
        assertNotEquals(alpha.matrixRow(4).toList(), integer.matrixRow(4).toList())

        // Parity produced by one and "recovered" by the other is wrong, not rejected. This is the
        // silent corruption spec §7.7 warns about, demonstrated.
        val data = List(4) { index -> ByteArray(8) { (index * 8 + it).toByte() } }
        val encoded = arrayOfNulls<ByteArray>(6)
        for (index in data.indices) encoded[index] = data[index]
        alpha.encodeParity(encoded, 8)

        val mismatched = arrayOfNulls<ByteArray>(6)
        mismatched[0] = data[0]
        mismatched[1] = data[1]
        mismatched[4] = encoded[4]
        mismatched[5] = encoded[5]
        assertTrue(integer.decodeMissing(mismatched, 8))
        assertFalse(data[2].contentEquals(requireNotNull(mismatched[2])))
    }

    // ---- Self-consistency --------------------------------------------------------------------

    @Test
    fun `every survivable erasure pattern is recovered exactly, for both variants`() {
        for (variant in ReedSolomonMatrix.values()) {
            exhaustive(dataShards = 4, parityShards = 2, shardSize = 8, variant = variant)
            exhaustive(dataShards = 2, parityShards = 1, shardSize = 3, variant = variant)
            exhaustive(dataShards = 6, parityShards = 3, shardSize = 5, variant = variant)
            exhaustive(dataShards = 1, parityShards = 2, shardSize = 4, variant = variant)
        }
    }

    @Test
    fun `large blocks recover under randomised erasure`() {
        val random = Random(20260812L)
        for (trial in 0 until 200) {
            val dataShards = 8 + random.nextInt(40)
            val parityShards = 1 + random.nextInt(12)
            val shardSize = 1 + random.nextInt(64)
            val codec = ReedSolomon.create(
                dataShards,
                parityShards,
                UnverifiedRtpVideoConstants.FEC_MATRIX_VARIANT,
            )

            val original = List(dataShards) { ByteArray(shardSize).also { random.nextBytes(it) } }
            val shards = arrayOfNulls<ByteArray>(codec.totalShards)
            for (index in original.indices) shards[index] = original[index].copyOf()
            codec.encodeParity(shards, shardSize)

            val erasures = random.nextInt(parityShards + 1)
            val order = (0 until codec.totalShards).shuffled(kotlin.random.Random(random.nextLong()))
            for (index in 0 until erasures) shards[order[index]] = null

            assertTrue("trial $trial", codec.decodeMissing(shards, shardSize))
            for (index in 0 until dataShards) {
                assertArrayEquals(
                    "trial $trial shard $index",
                    original[index],
                    shards[index],
                )
            }
        }
    }

    @Test
    fun `more erasures than parity shards fails rather than inventing bytes`() {
        val codec = ReedSolomon.create(4, 2, UnverifiedRtpVideoConstants.FEC_MATRIX_VARIANT)
        val shards = arrayOfNulls<ByteArray>(6)
        for (index in 0 until 4) shards[index] = ByteArray(8) { (index + it).toByte() }
        codec.encodeParity(shards, 8)

        shards[0] = null
        shards[1] = null
        shards[2] = null
        assertFalse(codec.decodeMissing(shards, 8))
        assertNull(shards[0])
    }

    @Test
    fun `a shard of the wrong length is refused rather than read past its end`() {
        val codec = ReedSolomon.create(3, 2, UnverifiedRtpVideoConstants.FEC_MATRIX_VARIANT)
        val shards = arrayOfNulls<ByteArray>(5)
        for (index in 0 until 3) shards[index] = ByteArray(4) { (index + it).toByte() }
        codec.encodeParity(shards, 4)

        shards[0] = null
        shards[1] = ByteArray(3)
        assertFalse(codec.decodeMissing(shards, 4))
    }

    @Test
    fun `decoding a block with nothing missing is a no-op`() {
        val codec = ReedSolomon.create(3, 2, UnverifiedRtpVideoConstants.FEC_MATRIX_VARIANT)
        val shards = arrayOfNulls<ByteArray>(5)
        for (index in 0 until 3) shards[index] = ByteArray(4) { (index + it).toByte() }
        codec.encodeParity(shards, 4)
        val before = shards.map { it?.copyOf() }

        assertTrue(codec.decodeMissing(shards, 4))
        for (index in 0 until 5) {
            assertArrayEquals("shard $index", before[index], shards[index])
        }
    }

    @Test
    fun `impossible geometries are refused at construction`() {
        assertTrue(runCatching { ReedSolomon.create(0, 2) }.isFailure)
        assertTrue(runCatching { ReedSolomon.create(2, 0) }.isFailure)
        assertTrue(runCatching { ReedSolomon.create(200, 100) }.isFailure)
        assertTrue(runCatching { ReedSolomon.create(1, 1) }.isSuccess)
        assertTrue(runCatching { ReedSolomon.create(128, 127) }.isSuccess)
    }

    @Test
    fun `the cache returns one codec per geometry and refuses impossible ones`() {
        val cache = ReedSolomonCache()
        val first = cache.get(4, 2)
        val second = cache.get(4, 2)

        assertTrue(first === second)
        assertEquals(1, cache.size)
        cache.get(5, 2)
        assertEquals(2, cache.size)
        assertNull(cache.get(0, 2))
        assertNull(cache.get(200, 100))
        assertEquals(2, cache.size)
    }

    /** Erases every subset of at most `parityShards` shards and checks exact recovery. */
    private fun exhaustive(
        dataShards: Int,
        parityShards: Int,
        shardSize: Int,
        variant: ReedSolomonMatrix,
    ) {
        val codec = ReedSolomon.create(dataShards, parityShards, variant)
        val total = codec.totalShards
        val original = List(dataShards) { index ->
            ByteArray(shardSize) { ((index * 37 + it * 11 + 3) and 0xFF).toByte() }
        }
        val encoded = arrayOfNulls<ByteArray>(total)
        for (index in original.indices) encoded[index] = original[index].copyOf()
        codec.encodeParity(encoded, shardSize)

        for (mask in 0 until (1 shl total)) {
            if (Integer.bitCount(mask) > parityShards) continue
            val shards = arrayOfNulls<ByteArray>(total)
            for (index in 0 until total) {
                if ((mask shr index) and 1 == 0) shards[index] = requireNotNull(encoded[index]).copyOf()
            }
            val label = "$variant k=$dataShards p=$parityShards mask=$mask"
            assertTrue(label, codec.decodeMissing(shards, shardSize))
            for (index in 0 until dataShards) {
                assertArrayEquals("$label shard $index", original[index], shards[index])
            }
        }
    }
}
