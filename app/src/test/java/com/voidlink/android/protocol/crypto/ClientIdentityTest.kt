package com.voidlink.android.protocol.crypto

import com.voidlink.android.protocol.Hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Covers the one property of the client identity that every pairing depends on: **the certificate
 * we present in TLS is byte-for-byte the certificate we sent as `clientcert=` when we paired, on
 * this launch and on every launch after it.**
 *
 * A host in the Sunshine family files that certificate at pairing time and recognises the device by
 * it afterwards. If the identity were regenerated on a restart, or if the PEM text sent at pairing
 * could drift from the DER presented in TLS, the host would hold certificate A while we present
 * certificate B — and every secure call would fail in a way that says nothing about why. These
 * tests exist so that failure mode cannot come back silently.
 */
class ClientIdentityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun identityIn(baseDir: File): ClientIdentity =
        runBlocking { IdentityStore(baseDir).identity() }

    @Test
    fun `a second IdentityStore over the same directory loads the same identity`() {
        val baseDir = folder.newFolder()

        // Two stores over one directory is what two app launches look like: nothing is shared in
        // memory, everything has to come back off disk.
        val first = identityIn(baseDir)
        val second = identityIn(baseDir)

        assertEquals(first.uniqueId, second.uniqueId)
        assertEquals(first.certificate.serialNumber, second.certificate.serialNumber)
        assertEquals(first.certificateFingerprint, second.certificateFingerprint)
        assertEquals(
            Hex.encode(first.certificate.encoded),
            Hex.encode(second.certificate.encoded),
        )
    }

    @Test
    fun `the bytes sent as clientcert are the bytes of the certificate presented in TLS`() {
        val identity = identityIn(folder.newFolder())

        // `clientcert=` is hex of the PEM *text* (spec §2), while TLS presents the DER. This is the
        // step where the two could diverge, so it is asserted on the real values rather than on a
        // re-derivation of one from the other.
        val sentBytes = Hex.decodeOrNull(identity.certificatePemHex)!!
        val reparsed = CertificateCodec.parseOrNull(sentBytes)!!

        assertEquals(
            identity.certificateFingerprint,
            CertificateCodec.fingerprint(reparsed),
        )
        assertEquals(identity.pairingCertificateFingerprint, sha256(sentBytes))
    }

    @Test
    fun `a stale client pem on disk never becomes the certificate we send`() {
        val baseDir = folder.newFolder()
        val identity = identityIn(baseDir)

        // Simulate the divergence directly: leave a different certificate in `client.pem` while
        // `client.crt` still holds ours. Reading the PEM back as the source of truth would send the
        // host a certificate we can never present, which is unrecoverable without a re-pair.
        File(File(baseDir, "identity"), "client.pem").writeText(
            CertificateFixture.PEM,
            Charsets.US_ASCII,
        )

        val reloaded = identityIn(baseDir)

        assertEquals(identity.certificateFingerprint, reloaded.certificateFingerprint)
        assertEquals(
            CertificateCodec.toPem(reloaded.certificate),
            reloaded.certificatePem,
        )
        assertNotEquals(CertificateFixture.PEM, reloaded.certificatePem)
    }

    @Test
    fun `the generated certificate is what a Sunshine-family host requires`() {
        val certificate = identityIn(folder.newFolder()).certificate

        // Apollo verifies a presented client certificate against the stored one with OpenSSL's
        // X509_verify_cert under X509_V_FLAG_PARTIAL_CHAIN, forgiving only expiry and
        // not-yet-validity. That leaves the signature algorithm, the key size and the self-signed
        // subject/issuer as the properties that actually have to hold.
        assertEquals("SHA256withRSA", certificate.sigAlgName)
        assertEquals("RSA", certificate.publicKey.algorithm)
        assertEquals(
            certificate.subjectX500Principal.name,
            certificate.issuerX500Principal.name,
        )
        assertTrue(certificate.serialNumber.signum() > 0)
        // Backdated, so a device whose clock has not yet synchronised does not present a
        // certificate that is not valid until tomorrow.
        assertTrue(certificate.notBefore.before(java.util.Date()))
        assertTrue(certificate.notAfter.after(java.util.Date()))
        certificate.verify(certificate.publicKey)
    }

    @Test
    fun `describe names the certificate without leaking the private key`() {
        val identity = identityIn(folder.newFolder())
        val described = identity.describe()

        // This line is the whole point of the diagnostic: it has to carry the fingerprint a host's
        // client list can be compared against, and nothing that would be dangerous in a bug report.
        assertTrue(described.contains(identity.uniqueId))
        assertTrue(described.contains(identity.certificateFingerprint))
        assertTrue(described.contains(identity.pairingCertificateFingerprint))
        assertTrue(described.contains(identity.certificate.serialNumber.toString(16)))
        assertFalseContains(described, "PRIVATE")
    }

    private fun assertFalseContains(haystack: String, needle: String) {
        assertTrue(
            "\"$needle\" must not appear in a log line",
            !haystack.contains(needle, ignoreCase = true),
        )
    }
}
