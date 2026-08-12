package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `SS_ENC_CONTROL_V2` envelope of `docs/01-PROTOCOL.md` §9.2.
 *
 * Not reached in v1 — spec §6.5 says to announce `encryptionEnabled=0` until the flag values can be
 * confirmed against a live Sunshine host — but the framing is fully specified, so it is fully
 * testable now, and the two things most likely to be wrong are pinned here:
 *
 * 1. **The tag precedes the ciphertext on the wire**, while JCE appends it. Getting that backwards
 *    produces a packet that looks right, authenticates nothing, and fails only on the host.
 * 2. **The IV is built from the sequence number before it is byte-swapped**, and carries two marker
 *    bytes that keep the client's IV space disjoint from the host's.
 */
class ControlCryptoTest {

    private val key = Hex.decodeOrNull("000102030405060708090a0b0c0d0e0f")!!

    @Test
    fun `the control-v2 IV is the little-endian sequence number plus the origin markers`() {
        val crypto = ControlCrypto(key)
        assertEquals(
            // seq (4, little-endian), six zero bytes, then the 'C','C' markers at offsets 10 and 11.
            "78563412" + "000000000000" + "4343",
            Hex.encode(crypto.initializationVector(0x12345678)),
        )
        assertEquals(ControlConstants.CONTROL_V2_IV_BYTES, crypto.initializationVector(0).size)
    }

    @Test
    fun `the legacy IV is sixteen bytes and truncates the sequence number to one`() {
        // Spec §9.2: "the older, non-v2 encrypted control path uses a shorter derivation with only
        // iv[0] = seq". A one-byte truncation of a 32-bit counter is as IV-reusing as it sounds; it
        // is reproduced because interoperating requires it.
        val crypto = ControlCrypto(key, ControlEncryptionVariant.LEGACY)
        val iv = crypto.initializationVector(0x12345678)
        assertEquals(ControlConstants.LEGACY_IV_BYTES, iv.size)
        assertEquals("78" + "00".repeat(15), Hex.encode(iv))
    }

    @Test
    fun `a sealed packet has the header, sequence and tag the spec lays out`() {
        val crypto = ControlCrypto(key)
        val packet = requireNotNull(crypto.seal(0x0305, byteArrayOf(0, 0)))

        // offset 0: encryptedHeaderType 0x0001 little-endian
        assertEquals("0100", Hex.encode(packet, 0, 2))
        // offset 2: length = 4 (seq) + 16 (tag) + 4 (V2 header) + 2 (payload) = 26 = 0x1a
        assertEquals("1a00", Hex.encode(packet, 2, 2))
        // offset 4: seq, little-endian, starting at zero
        assertEquals("00000000", Hex.encode(packet, 4, 4))
        // offset 8: the 16-byte tag, then the ciphertext of {V2 header + payload}
        assertEquals(
            ControlConstants.ENCRYPTED_HEADER_SIZE + ControlConstants.HEADER_SIZE_V2 + 2,
            packet.size,
        )
        assertEquals(1, crypto.nextSequenceNumber)
    }

    @Test
    fun `the sequence number advances per packet and changes the ciphertext`() {
        val crypto = ControlCrypto(key)
        val first = requireNotNull(crypto.seal(ControlConstants.TYPE_PERIODIC_PING, ControlPayloads.periodicPing()))
        val second = requireNotNull(crypto.seal(ControlConstants.TYPE_PERIODIC_PING, ControlPayloads.periodicPing()))

        assertEquals("00000000", Hex.encode(first, 4, 4))
        assertEquals("01000000", Hex.encode(second, 4, 4))
        assertNotEquals(Hex.encode(first), Hex.encode(second))
    }

    @Test
    fun `open reverses seal, V2 header and all`() {
        val sealer = ControlCrypto(key)
        val opener = ControlCrypto(key)
        val packet = requireNotNull(sealer.seal(0x010b, byteArrayOf(1, 2, 3, 4)))

        val message = requireNotNull(opener.open(packet))
        assertEquals(0x010b, message.type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), message.payload)
    }

    @Test
    fun `a tampered packet fails authentication instead of decoding to garbage`() {
        val crypto = ControlCrypto(key)
        val packet = requireNotNull(crypto.seal(0x0305, byteArrayOf(0, 0)))
        packet[packet.size - 1] = (packet[packet.size - 1].toInt() xor 0xFF).toByte()

        assertNull(ControlCrypto(key).open(packet))
    }

    @Test
    fun `a plaintext packet on an encrypted stream is refused`() {
        val plaintext = ControlFraming.encode(0x0305, byteArrayOf(0, 0), ControlHeaderVersion.V2)
        assertNull(ControlCrypto(key).open(plaintext))
    }

    @Test
    fun `a runt encrypted packet is refused`() {
        assertNull(ControlCrypto(key).open(Hex.decodeOrNull("0100" + "1a00")!!))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a key of the wrong size is refused at construction`() {
        ControlCrypto(ByteArray(8))
    }
}
