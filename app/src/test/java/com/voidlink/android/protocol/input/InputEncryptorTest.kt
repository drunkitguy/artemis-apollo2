package com.voidlink.android.protocol.input

import com.voidlink.android.protocol.Hex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The input AES envelope and its IV rule (`docs/01-PROTOCOL.md` §10, §10.1).
 *
 * Spec §10.1 names this the least well-documented part of the input path and warns that a wrong IV
 * means the host discards everything **silently**. That makes two things worth testing hard, neither
 * of which needs a host:
 *
 * 1. **The envelope is exactly what spec §10 draws** — a big-endian length prefix, then `tag ||
 *    ciphertext` with the tag *first*, which is the opposite of what JCE produces. This test
 *    decrypts our own output with a plain `Cipher` to prove the tag was moved rather than merely
 *    counted.
 * 2. **Each IV mode does what it says**, including the difference between the reference client's
 *    chaining threshold and the spec's literal wording — the two disagree on every packet smaller
 *    than 48 bytes, i.e. on nearly all of them, and telling them apart in a debugging session is
 *    what the mode switch is for.
 */
class InputEncryptorTest {

    private val key = ByteArray(InputConstants.KEY_BYTES) { it.toByte() }
    private val keyId = 0x01020304

    @After
    fun restoreDefaults() {
        UnverifiedInputConstants.ivMode = InputIvMode.CHAINED_REFERENCE
        UnverifiedInputConstants.useFirstTwelveIvBytes = true
    }

    private fun encryptor(
        generation: Int = 7,
        sunshine: Boolean = true,
        controlEncrypted: Boolean = false,
    ) = InputEncryptor(key, keyId, InputProfile(generation, sunshine, controlEncrypted))

    // ---- The envelope (spec §10) ----------------------------------------------------------------

    @Test
    fun `the payload is a big-endian length prefix followed by tag then ciphertext`() {
        val packet = InputPackets.mouseButton(MouseButton.LEFT, pressed = true, gen5OrLater = true)
        val payload = requireNotNull(encryptor().seal(packet))

        val declared = ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
        assertEquals(payload.size - InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES, declared)
        // GCM does not expand: tag + plaintext.
        assertEquals(InputConstants.GCM_TAG_BYTES + packet.size, declared)
    }

    @Test
    fun `the sealed blob decrypts with the tag moved back to the end, proving it was moved`() {
        val packet = InputPackets.mouseMoveRelative(10, -20, gen5OrLater = true)
        val payload = requireNotNull(encryptor().seal(packet))
        val blob = payload.copyOfRange(InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES, payload.size)

        val tag = blob.copyOfRange(0, InputConstants.GCM_TAG_BYTES)
        val ciphertext = blob.copyOfRange(InputConstants.GCM_TAG_BYTES, blob.size)
        val jceOrder = ciphertext + tag

        val cipher = Cipher.getInstance(InputConstants.GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, InputConstants.KEY_ALGORITHM),
            GCMParameterSpec(
                InputConstants.GCM_TAG_BITS,
                ConfigurableIvStrategy.initialIv(keyId).copyOfRange(0, InputConstants.GCM_IV_BYTES),
            ),
        )
        assertArrayEquals(packet, cipher.doFinal(jceOrder))
    }

    @Test
    fun `the initial IV is the key id big-endian with the rest zero`() {
        assertEquals(
            "01020304" + "000000000000000000000000",
            Hex.encode(encryptor().currentIv()),
        )
    }

    @Test
    fun `a Gen 5 host gets AES-CBC padded to the block size, with a static IV`() {
        val encryptor = encryptor(generation = 5)
        val packet = InputPackets.mouseButton(MouseButton.LEFT, pressed = true, gen5OrLater = true)
        val payload = requireNotNull(encryptor.seal(packet))
        val blob = payload.copyOfRange(InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES, payload.size)

        // 9 plaintext bytes padded to one 16-byte block.
        assertEquals(16, blob.size)

        val cipher = Cipher.getInstance(InputConstants.CBC_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, InputConstants.KEY_ALGORITHM),
            IvParameterSpec(ConfigurableIvStrategy.initialIv(keyId)),
        )
        assertArrayEquals(packet, cipher.doFinal(blob))

        // CBC never chains: the reference client gates chaining on Gen 7.
        assertArrayEquals(ConfigurableIvStrategy.initialIv(keyId), encryptor.currentIv())
    }

    @Test
    fun `a host whose control stream encrypts for us gets the plaintext packet unwrapped`() {
        val packet = InputPackets.mouseMoveRelative(1, 1, gen5OrLater = true)
        val payload = requireNotNull(encryptor(controlEncrypted = true).seal(packet))
        // No length prefix and no ciphertext: exactly the bytes we were given (spec §10.1's
        // GFE 3.22 case, which v1 never negotiates).
        assertArrayEquals(packet, payload)
    }

    // ---- IV chaining (spec §10.1) ---------------------------------------------------------------

    @Test
    fun `the reference rule chains from the last 16 bytes of the sealed blob`() {
        val encryptor = encryptor()
        // A multi-controller packet is 34 bytes, so its blob is 50 — comfortably over the
        // reference's 32-byte threshold.
        val payload = requireNotNull(
            encryptor.seal(InputPackets.multiController(ControllerState(), 1, true, true)),
        )
        val blob = payload.copyOfRange(InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES, payload.size)
        assertArrayEquals(blob.copyOfRange(blob.size - InputConstants.IV_BYTES, blob.size), encryptor.currentIv())
    }

    @Test
    fun `the reference rule chains only once the sealed blob reaches 32 bytes`() {
        // A mouse button packet is 9 bytes of plaintext: blob = 16 + 9 = 25 bytes. Under the
        // reference threshold (blob >= 32) that does NOT chain; under a "ciphertext >= 16" reading
        // it would. This pins which side of the boundary we are on for the smallest packet, and the
        // relative-move case below pins the other side.
        val small = encryptor()
        small.seal(InputPackets.mouseButton(MouseButton.LEFT, true, true))
        assertArrayEquals(ConfigurableIvStrategy.initialIv(keyId), small.currentIv())

        // A keyboard packet is 14 bytes: blob = 30, still under 32.
        val medium = encryptor()
        medium.seal(InputPackets.keyboard(0x41, true, 0))
        assertArrayEquals(ConfigurableIvStrategy.initialIv(keyId), medium.currentIv())

        // An absolute mouse packet is 18 bytes: blob = 34, over the threshold.
        val large = encryptor()
        large.seal(InputPackets.mouseMoveAbsolute(1, 1, 1920, 1080))
        assertFalse(ConfigurableIvStrategy.initialIv(keyId).contentEquals(large.currentIv()))
    }

    @Test
    fun `the spec's literal threshold chains later than the reference's, and the difference is real`() {
        // The same packet under both modes: 18-byte plaintext, 34-byte blob. The reference chains
        // (blob >= 32); the spec's wording needs a 32-byte *ciphertext*, i.e. a 48-byte blob, so it
        // does not. From here the two IV chains never agree again — which is exactly why both modes
        // exist and why they are switchable at runtime.
        UnverifiedInputConstants.ivMode = InputIvMode.CHAINED_REFERENCE
        val reference = encryptor()
        reference.seal(InputPackets.mouseMoveAbsolute(1, 1, 1920, 1080))

        UnverifiedInputConstants.ivMode = InputIvMode.CHAINED_SPEC
        val spec = encryptor()
        spec.seal(InputPackets.mouseMoveAbsolute(1, 1, 1920, 1080))

        assertArrayEquals(ConfigurableIvStrategy.initialIv(keyId), spec.currentIv())
        assertFalse(reference.currentIv().contentEquals(spec.currentIv()))
    }

    @Test
    fun `the static mode never moves the IV`() {
        UnverifiedInputConstants.ivMode = InputIvMode.STATIC
        val encryptor = encryptor()
        repeat(5) { encryptor.seal(InputPackets.multiController(ControllerState(), 1, true, true)) }
        assertArrayEquals(ConfigurableIvStrategy.initialIv(keyId), encryptor.currentIv())
    }

    @Test
    fun `the counter mode advances only its trailing four bytes`() {
        UnverifiedInputConstants.ivMode = InputIvMode.COUNTER
        val encryptor = encryptor()
        repeat(3) { encryptor.seal(InputPackets.mouseButton(MouseButton.LEFT, true, true)) }
        assertEquals("01020304" + "00000000" + "00000000" + "00000003", Hex.encode(encryptor.currentIv()))
    }

    @Test
    fun `switching the IV mode mid-session restarts the chain from the key id`() {
        val encryptor = encryptor()
        encryptor.seal(InputPackets.multiController(ControllerState(), 1, true, true))
        assertFalse(ConfigurableIvStrategy.initialIv(keyId).contentEquals(encryptor.currentIv()))

        UnverifiedInputConstants.ivMode = InputIvMode.STATIC
        encryptor.seal(InputPackets.mouseButton(MouseButton.LEFT, true, true))

        assertEquals(InputIvMode.STATIC, encryptor.ivMode)
        assertArrayEquals(ConfigurableIvStrategy.initialIv(keyId), encryptor.currentIv())
    }

    @Test
    fun `the GCM nonce switch picks a different twelve bytes and therefore a different ciphertext`() {
        // Both truncations are guesses (spec §10.1); what matters is that flipping the switch
        // genuinely changes the wire bytes, so a user bisecting against a real host learns
        // something from the flip.
        val packet = InputPackets.mouseMoveRelative(3, 4, gen5OrLater = true)

        UnverifiedInputConstants.useFirstTwelveIvBytes = true
        val first = requireNotNull(encryptor().seal(packet))

        UnverifiedInputConstants.useFirstTwelveIvBytes = false
        val last = requireNotNull(encryptor().seal(packet))

        assertNotEquals(Hex.encode(first), Hex.encode(last))
    }

    @Test
    fun `two identical packets encrypt differently once the IV has moved`() {
        val encryptor = encryptor()
        val packet = InputPackets.multiController(ControllerState(), 1, true, true)
        val first = requireNotNull(encryptor.seal(packet))
        val second = requireNotNull(encryptor.seal(packet))
        assertNotEquals(Hex.encode(first), Hex.encode(second))
        assertEquals(2L, encryptor.packetsSealed)
    }

    @Test
    fun `a key of the wrong length is refused at construction rather than at the first packet`() {
        val failure = runCatching { InputEncryptor(ByteArray(8), keyId, InputProfile(7, true)) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }
}
