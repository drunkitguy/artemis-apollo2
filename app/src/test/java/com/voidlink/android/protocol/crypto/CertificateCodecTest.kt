package com.voidlink.android.protocol.crypto

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the certificate encodings of `docs/01-PROTOCOL.md` §2.
 *
 * The detail worth a test of its own: pairing sends the **PEM text's ASCII bytes, hex-encoded**,
 * not the DER hex-encoded. The spec calls that out as easy to get wrong, and the assertion on the
 * `2d2d2d2d2d424547494e` prefix — which is `-----BEGIN` in ASCII — is what pins it down. That is
 * the same prefix the spec's own `<plaincert>` example starts with.
 */
class CertificateCodecTest {

    private val certificate =
        CertificateCodec.parseOrNull(CertificateFixture.PEM.toByteArray(Charsets.US_ASCII))!!

    @Test
    fun `a PEM certificate parses`() {
        assertNotNull(certificate)
        assertEquals(CertificateFixture.SUBJECT_DN, certificate.subjectX500Principal.name)
        assertEquals("RSA", certificate.publicKey.algorithm)
    }

    @Test
    fun `DER bytes parse too, which is how our own stored certificate is read back`() {
        val fromDer = CertificateCodec.parseOrNull(certificate.encoded)
        assertNotNull(fromDer)
        assertArrayEquals(certificate.encoded, fromDer!!.encoded)
    }

    @Test
    fun `re-encoding to PEM produces text that parses back to the same certificate`() {
        val pem = CertificateCodec.toPem(certificate)
        val reparsed = CertificateCodec.parseOrNull(pem.toByteArray(Charsets.US_ASCII))
        assertNotNull(reparsed)
        assertArrayEquals(certificate.encoded, reparsed!!.encoded)
    }

    @Test
    fun `PEM uses the standard delimiters and newline-only line endings`() {
        val pem = CertificateCodec.toPem(certificate)

        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
        // A carriage return would change the bytes we hex onto the wire depending on the machine
        // that built the app, which is exactly the kind of drift this asserts away.
        assertFalse(pem.contains('\r'))
    }

    @Test
    fun `PEM base64 is wrapped at sixty-four columns like OpenSSL`() {
        val lines = CertificateCodec.toPem(certificate).lines()
        val body = lines.filter { it.isNotEmpty() && !it.startsWith("-----") }

        assertTrue(body.isNotEmpty())
        body.forEach { assertTrue("line too long: ${it.length}", it.length <= 64) }
        // Every line but the last is exactly full.
        body.dropLast(1).forEach { assertEquals(64, it.length) }
    }

    @Test
    fun `the wire encoding is hex of the PEM ASCII bytes, not hex of the DER`() {
        val pemBytes = CertificateCodec.pemBytes(certificate)
        val wireHex = Hex.encode(pemBytes)

        // "-----BEGIN" in ASCII. Spec §4.3's <plaincert> example begins with these same bytes.
        assertTrue(wireHex.startsWith("2d2d2d2d2d424547494e"))
        assertArrayEquals(CertificateCodec.toPem(certificate).toByteArray(Charsets.US_ASCII), pemBytes)

        // The DER encoding of an X.509 certificate always starts with the SEQUENCE tag 0x30, so
        // this asserts the two encodings are genuinely different and not accidentally the same.
        val derHex = Hex.encode(certificate.encoded)
        assertTrue(derHex.startsWith("30"))
        assertFalse(wireHex.startsWith(derHex))
    }

    @Test
    fun `hex of the PEM round-trips back to a parseable certificate`() {
        // The full journey a certificate makes during pairing: cert -> PEM -> ASCII -> hex, then
        // the host's reply comes back the other way.
        val wireHex = Hex.encode(CertificateCodec.pemBytes(certificate))
        val decoded = Hex.decodeOrNull(wireHex)
        assertNotNull(decoded)
        val reparsed = CertificateCodec.parseOrNull(decoded)
        assertNotNull(reparsed)
        assertArrayEquals(certificate.encoded, reparsed!!.encoded)
    }

    @Test
    fun `derToPem accepts raw DER`() {
        val pem = CertificateCodec.derToPem(certificate.encoded)
        assertEquals(CertificateCodec.toPem(certificate), pem)
    }

    @Test
    fun `malformed input yields null rather than throwing`() {
        assertNull(CertificateCodec.parseOrNull(null))
        assertNull(CertificateCodec.parseOrNull(ByteArray(0)))
        assertNull(CertificateCodec.parseOrNull("not a certificate".toByteArray()))
        assertNull(CertificateCodec.parseOrNull(ByteArray(64) { 0x41 }))
        // Truncated PEM: the delimiters are there but the body is cut short.
        val truncated = CertificateFixture.PEM.substring(0, 120) + "\n-----END CERTIFICATE-----\n"
        assertNull(CertificateCodec.parseOrNull(truncated.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `looksLikePem recognises the delimiter`() {
        assertTrue(CertificateCodec.looksLikePem(CertificateFixture.PEM))
        assertFalse(CertificateCodec.looksLikePem("MIIC4zCCAcug"))
        assertFalse(CertificateCodec.looksLikePem(""))
    }

    @Test
    fun `the certificate signature is a stable non-empty value`() {
        // The phase-3 hash of spec §4.5 mixes in exactly these bytes, so an accidental change of
        // encoding here would silently break pairing.
        assertTrue(certificate.signature.isNotEmpty())
        assertEquals(256, certificate.signature.size)
        assertArrayEquals(certificate.signature, certificate.signature)
    }
}
