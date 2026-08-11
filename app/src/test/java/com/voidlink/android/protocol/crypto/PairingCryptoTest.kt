package com.voidlink.android.protocol.crypto

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Known-answer tests for the pairing primitives of `docs/01-PROTOCOL.md` §4.
 *
 * ## How these vectors were derived
 *
 * Pairing is the one part of the protocol that can be verified without a host, because every step
 * is a deterministic function of its inputs. Each expected value below was computed independently
 * of this codebase by a short Java program driving the JDK's own JCA — `MessageDigest`,
 * `Cipher("AES/ECB/NoPadding")` — over fixed inputs, and then transcribed here. The production
 * code calls the same JCA algorithms through its own composition of them, so a mismatch means our
 * *composition* is wrong: a bad offset, the wrong digest, PKCS padding sneaking in.
 *
 * The fixed inputs are:
 *
 * ```
 * saltA        = 000102030405060708090a0b0c0d0e0f
 * saltB        = a1b2c3d4e5f60718293a4b5c6d7e8f90
 * pinA         = "0042"   (chosen for the leading zero)
 * pinB         = "1234"
 * challenge    = 0f0e0d0c0b0a09080706050403020100
 * ```
 *
 * Anyone can reproduce them: `sha256(salt || ascii(pin))`, truncated to 16 bytes, is the AES key;
 * AES-128-ECB with no padding over the zero-extended input is the cipher.
 */
class PairingCryptoTest {

    private val saltA = Hex.decodeOrNull("000102030405060708090a0b0c0d0e0f")!!
    private val saltB = Hex.decodeOrNull("a1b2c3d4e5f60718293a4b5c6d7e8f90")!!
    private val challenge = Hex.decodeOrNull("0f0e0d0c0b0a09080706050403020100")!!

    /** `deriveAesKey(saltA, "0042", SHA-256)`. */
    private val keyA = Hex.decodeOrNull("e1d989199aea068cffcf7c1999a0d93a")!!

    // ---- §4.1 key derivation -----------------------------------------------------------------

    @Test
    fun `salted pin appends the PIN as ASCII digit characters`() {
        // 16 salt bytes then "0042" as 0x30 0x30 0x34 0x32 — not the number 42.
        assertEquals(
            "000102030405060708090a0b0c0d0e0f30303432",
            Hex.encode(PairingCrypto.saltedPin(saltA, "0042")),
        )
        assertEquals(20, PairingCrypto.saltedPin(saltA, "0042").size)
    }

    @Test
    fun `sha256 key derivation matches the known answer`() {
        assertEquals("e1d989199aea068cffcf7c1999a0d93a", Hex.encode(keyOf(saltA, "0042", PairingHash.SHA256)))
        assertEquals("b00454e1dd0320d51434542bd86adc45", Hex.encode(keyOf(saltB, "1234", PairingHash.SHA256)))
    }

    @Test
    fun `sha1 key derivation matches the known answer`() {
        assertEquals("b9bb2efd99f649719719321213cd665e", Hex.encode(keyOf(saltA, "0042", PairingHash.SHA1)))
    }

    @Test
    fun `the key is the first sixteen bytes of the full digest, not a re-hash`() {
        val fullSha256 = "e1d989199aea068cffcf7c1999a0d93aa128abdc1b38e3b2fece02b924a8fcf3"
        val fullSha1 = "b9bb2efd99f649719719321213cd665e451dadda"

        assertEquals(fullSha256, Hex.encode(PairingHash.SHA256.digest(PairingCrypto.saltedPin(saltA, "0042"))))
        assertEquals(fullSha1, Hex.encode(PairingHash.SHA1.digest(PairingCrypto.saltedPin(saltA, "0042"))))
        assertTrue(fullSha256.startsWith(Hex.encode(keyOf(saltA, "0042", PairingHash.SHA256))))
        assertTrue(fullSha1.startsWith(Hex.encode(keyOf(saltA, "0042", PairingHash.SHA1))))
    }

    @Test
    fun `a different PIN produces a completely different key`() {
        val right = keyOf(saltA, "0042", PairingHash.SHA256)
        val wrong = keyOf(saltA, "0043", PairingHash.SHA256)
        assertFalse(right.contentEquals(wrong))
    }

    @Test
    fun `key derivation always yields sixteen bytes`() {
        assertEquals(16, keyOf(saltA, "0042", PairingHash.SHA256).size)
        assertEquals(16, keyOf(saltA, "0042", PairingHash.SHA1).size)
    }

    // ---- §4.0 hash selection -----------------------------------------------------------------

    @Test
    fun `generation seven and above uses sha256, below uses sha1`() {
        assertEquals(PairingHash.SHA1, PairingHash.forGeneration(3))
        assertEquals(PairingHash.SHA1, PairingHash.forGeneration(4))
        assertEquals(PairingHash.SHA1, PairingHash.forGeneration(5))
        assertEquals(PairingHash.SHA1, PairingHash.forGeneration(6))
        assertEquals(PairingHash.SHA256, PairingHash.forGeneration(7))
        assertEquals(PairingHash.SHA256, PairingHash.forGeneration(8))
    }

    @Test
    fun `digest lengths match the offsets the phase two split depends on`() {
        assertEquals(20, PairingHash.SHA1.digestLength)
        assertEquals(32, PairingHash.SHA256.digestLength)
        assertEquals(20, PairingHash.SHA1.digest(ByteArray(1)).size)
        assertEquals(32, PairingHash.SHA256.digest(ByteArray(1)).size)
    }

    // ---- §4.2 AES-128-ECB with zero padding --------------------------------------------------

    @Test
    fun `encrypting a sixteen byte challenge matches the known answer`() {
        assertEquals(
            "1e7b229e6c91ae670a5524dba7b0fd75",
            Hex.encode(PairingCrypto.encrypt(challenge, keyA)),
        )
    }

    @Test
    fun `encrypting a thirty-two byte hash matches the known answer`() {
        val hash = Hex.decodeOrNull(
            "00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f",
        )!!
        assertEquals(
            "befb66539876d1d519762951eeaa4c97eb5ba4f9a6ec9802c6649400fc928fe0",
            Hex.encode(PairingCrypto.encrypt(hash, keyA)),
        )
    }

    @Test
    fun `a twenty byte sha1 hash is zero-extended to two blocks, not PKCS padded`() {
        val sha1Hash = Hex.decodeOrNull("00112233445566778899aabbccddeeff10213243")!!

        assertEquals(
            "00112233445566778899aabbccddeeff10213243000000000000000000000000",
            Hex.encode(PairingCrypto.zeroPad(sha1Hash)),
        )
        // Exactly two blocks. PKCS#5 would have produced three.
        val encrypted = PairingCrypto.encrypt(sha1Hash, keyA)
        assertEquals(32, encrypted.size)
        assertEquals(
            "befb66539876d1d519762951eeaa4c977718f04df222757273bb950d7b984df3",
            Hex.encode(encrypted),
        )
    }

    @Test
    fun `ECB leaves identical leading blocks identical`() {
        // The 20-byte and 32-byte vectors above share their first 16 plaintext bytes, so in ECB
        // they must share their first ciphertext block. This is the property that would break
        // first if a chaining mode crept in.
        val short = PairingCrypto.encrypt(
            Hex.decodeOrNull("00112233445566778899aabbccddeeff10213243")!!,
            keyA,
        )
        val long = PairingCrypto.encrypt(
            Hex.decodeOrNull("00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f")!!,
            keyA,
        )
        assertArrayEquals(short.copyOf(16), long.copyOf(16))
    }

    @Test
    fun `a short input is zero-extended to one block`() {
        val input = Hex.decodeOrNull("0102030405")!!
        assertEquals("01020304050000000000000000000000", Hex.encode(PairingCrypto.zeroPad(input)))
        assertEquals("ac204910170750cb832ada66eab27cbc", Hex.encode(PairingCrypto.encrypt(input, keyA)))
    }

    @Test
    fun `zero padding does not copy an already aligned input`() {
        val aligned = ByteArray(32) { it.toByte() }
        assertSame(aligned, PairingCrypto.zeroPad(aligned))

        val unaligned = ByteArray(30) { it.toByte() }
        assertNotSame(unaligned, PairingCrypto.zeroPad(unaligned))
        assertEquals(32, PairingCrypto.zeroPad(unaligned).size)

        // An empty input still becomes one block, so the cipher never sees a zero-length buffer.
        assertEquals(16, PairingCrypto.zeroPad(ByteArray(0)).size)
    }

    @Test
    fun `decrypt reverses encrypt`() {
        val plaintext = Hex.decodeOrNull(
            "00112233445566778899aabbccddeeff102132435465768798a9bacbdcedfe0f",
        )!!
        val decrypted = PairingCrypto.decrypt(PairingCrypto.encrypt(plaintext, keyA), keyA)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `an AES key of the wrong length is rejected rather than silently truncated`() {
        val badKey = ByteArray(8)
        val failure = runCatching { PairingCrypto.encrypt(challenge, badKey) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    // ---- §4.4 phase two split ----------------------------------------------------------------

    @Test
    fun `phase two decryption splits into the server response hash and challenge`() {
        // A realistic 48-byte plaintext: a 32-byte SHA-256 response followed by a 16-byte
        // challenge, encrypted with keyA. The ciphertext is the independently computed vector.
        val ciphertext = Hex.decodeOrNull(
            "9b5b30d749263fac56926731dd66324e" +
                "67e2b0b233b198a9dcbb7f83bb6dda65" +
                "909bc0f8c67411139e2f44b20d168b97",
        )!!

        val decrypted = PairingCrypto.decrypt(ciphertext, keyA)
        val split = PairingCrypto.splitChallengeResponse(decrypted, PairingHash.SHA256)!!

        assertEquals(
            "73a5ee6354e87f3e9e66ccd22a782794e26aa3d989c599d055d2006d92932fe6",
            Hex.encode(split.serverResponse),
        )
        assertEquals("aabbccddeeff00112233445566778899", Hex.encode(split.serverChallenge))
    }

    @Test
    fun `phase two split uses the sha1 offset when the host is an older generation`() {
        val decrypted = ByteArray(48) { it.toByte() }
        val split = PairingCrypto.splitChallengeResponse(decrypted, PairingHash.SHA1)!!

        assertEquals(20, split.serverResponse.size)
        assertEquals(16, split.serverChallenge.size)
        // The challenge starts immediately after the 20-byte digest.
        assertEquals(20.toByte(), split.serverChallenge[0])
    }

    @Test
    fun `phase two split rejects a buffer too short to hold both values`() {
        assertNull(PairingCrypto.splitChallengeResponse(ByteArray(47), PairingHash.SHA256))
        assertNull(PairingCrypto.splitChallengeResponse(ByteArray(0), PairingHash.SHA256))
        assertNull(PairingCrypto.splitChallengeResponse(ByteArray(35), PairingHash.SHA1))
    }

    @Test
    fun `phase two split ignores the block-rounding tail`() {
        // 48 bytes of content in a 64-byte buffer: the trailing 16 bytes must be ignored.
        val padded = ByteArray(64) { 0x7F }
        val split = PairingCrypto.splitChallengeResponse(padded, PairingHash.SHA256)!!
        assertEquals(32, split.serverResponse.size)
        assertEquals(16, split.serverChallenge.size)
    }

    // ---- §4.5 phase three --------------------------------------------------------------------

    @Test
    fun `phase three hash concatenates challenge, certificate signature and secret`() {
        val serverChallenge = Hex.decodeOrNull("aabbccddeeff00112233445566778899")!!
        val certSignature = Hex.decodeOrNull("deadbeefcafebabe0011223344556677")!!
        val clientSecret = Hex.decodeOrNull("112233445566778899aabbccddeeff00")!!

        assertEquals(
            "6eba4a737d75543b390513e9f949a9a16a71d2410341f8ed7a664a517c4707bf",
            Hex.encode(
                PairingCrypto.clientChallengeResponseHash(
                    serverChallenge, certSignature, clientSecret, PairingHash.SHA256,
                ),
            ),
        )
        assertEquals(
            "c1a39c76d8362541921c0fd8c5cafc3ed8dbae32",
            Hex.encode(
                PairingCrypto.clientChallengeResponseHash(
                    serverChallenge, certSignature, clientSecret, PairingHash.SHA1,
                ),
            ),
        )
    }

    @Test
    fun `expected server response hash is the mirror image of the phase three hash`() {
        val clientChallenge = Hex.decodeOrNull("0f0e0d0c0b0a09080706050403020100")!!
        val serverCertSignature = Hex.decodeOrNull("00ff00ff00ff00ff00ff00ff00ff00ff")!!
        val serverSecret = Hex.decodeOrNull("fedcba98765432100123456789abcdef")!!

        assertEquals(
            "a682db873bb27e3a7fb59ef3f9bc60980438538ad1984bef01555ad2951e7b8f",
            Hex.encode(
                PairingCrypto.expectedServerResponseHash(
                    clientChallenge, serverCertSignature, serverSecret, PairingHash.SHA256,
                ),
            ),
        )
    }

    @Test
    fun `the two phase hashes differ even for the same inputs, because the order differs`() {
        val a = Hex.decodeOrNull("aabbccddeeff00112233445566778899")!!
        val b = Hex.decodeOrNull("00ff00ff00ff00ff00ff00ff00ff00ff")!!
        val c = Hex.decodeOrNull("fedcba98765432100123456789abcdef")!!

        val forward = PairingCrypto.clientChallengeResponseHash(a, b, c, PairingHash.SHA256)
        val mirror = PairingCrypto.expectedServerResponseHash(c, b, a, PairingHash.SHA256)
        assertFalse(forward.contentEquals(mirror))
    }

    // ---- §4.5 pairing secret split -----------------------------------------------------------

    @Test
    fun `pairing secret splits into a sixteen byte secret and the remaining signature`() {
        val raw = ByteArray(16 + 256) { it.toByte() }
        val split = PairingCrypto.splitPairingSecret(raw)!!

        assertEquals(16, split.secret.size)
        assertEquals(256, split.signature.size)
        assertArrayEquals(raw.copyOfRange(0, 16), split.secret)
        assertArrayEquals(raw.copyOfRange(16, raw.size), split.signature)
    }

    @Test
    fun `pairing secret with no room for a signature is rejected`() {
        assertNull(PairingCrypto.splitPairingSecret(ByteArray(16)))
        assertNull(PairingCrypto.splitPairingSecret(ByteArray(4)))
        assertNull(PairingCrypto.splitPairingSecret(ByteArray(0)))
    }

    // ---- §4.0 PIN generation -----------------------------------------------------------------

    @Test
    fun `a PIN keeps its leading zeros`() {
        val pin = PairingCrypto.generatePin(FixedRandom(intArrayOf(0, 0, 4, 2)))
        assertEquals("0042", pin)
    }

    @Test
    fun `a PIN is always four decimal digits`() {
        val random = SecureRandom()
        repeat(200) {
            val pin = PairingCrypto.generatePin(random)
            assertEquals(4, pin.length)
            assertTrue("unexpected PIN '$pin'", pin.all { it in '0'..'9' })
        }
    }

    @Test
    fun `an all-zero PIN is legal`() {
        assertEquals("0000", PairingCrypto.generatePin(FixedRandom(intArrayOf(0, 0, 0, 0))))
        assertEquals("9999", PairingCrypto.generatePin(FixedRandom(intArrayOf(9, 9, 9, 9))))
    }

    // ---- Comparison --------------------------------------------------------------------------

    @Test
    fun `constant time comparison behaves like equality`() {
        val a = Hex.decodeOrNull("00112233")!!
        val b = Hex.decodeOrNull("00112233")!!
        val c = Hex.decodeOrNull("00112234")!!

        assertTrue(PairingCrypto.constantTimeEquals(a, b))
        assertFalse(PairingCrypto.constantTimeEquals(a, c))
        assertFalse(PairingCrypto.constantTimeEquals(a, ByteArray(0)))
        assertFalse(PairingCrypto.constantTimeEquals(a, Hex.decodeOrNull("0011223344")!!))
    }

    @Test
    fun `signature verification of a malformed signature fails rather than throwing`() {
        val certificate = CertificateCodec.parseOrNull(
            CertificateFixture.PEM.toByteArray(Charsets.US_ASCII),
        )!!
        assertFalse(
            PairingCrypto.verifyServerSignature(certificate, ByteArray(16), ByteArray(4)),
        )
        assertFalse(
            PairingCrypto.verifyServerSignature(certificate, ByteArray(16), ByteArray(0)),
        )
    }

    private fun keyOf(salt: ByteArray, pin: String, hash: PairingHash): ByteArray =
        PairingCrypto.deriveAesKey(salt, pin, hash)

    /**
     * A [SecureRandom] with scripted output, so PIN generation can be asserted exactly.
     */
    private class FixedRandom(private val values: IntArray) : SecureRandom() {
        private var index = 0

        override fun nextInt(bound: Int): Int {
            val value = values[index % values.size]
            index++
            return value % bound
        }
    }
}
