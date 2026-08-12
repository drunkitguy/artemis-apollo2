package com.voidlink.android.protocol.crypto

import com.voidlink.android.protocol.Hex
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Conversions between the three certificate encodings the protocol touches.
 *
 * Spec §2 is emphatic about one detail that is easy to get wrong: **the certificate sent during
 * pairing is the PEM *text*, hex-encoded — not the DER bytes hex-encoded.** So the pipeline is
 *
 * ```
 * X509Certificate --toPem--> "-----BEGIN…"  --US-ASCII bytes--> Hex.encode --> clientcert=
 * ```
 *
 * and the host's `plaincert` comes back the same way. Both directions live here so there is one
 * place to look when a host rejects our certificate.
 *
 * Pure JVM: [Base64] is `java.util.Base64`, available from API 26 which is our `minSdk`, and
 * `CertificateFactory` is a platform API present on both Android and the JVM. That keeps this file
 * unit-testable without an emulator.
 */
object CertificateCodec {

    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"

    /** OpenSSL wraps PEM base64 at 64 characters; hosts parse with OpenSSL, so we match it. */
    private const val PEM_LINE_LENGTH = 64

    /**
     * Renders [certificate] as PEM text.
     *
     * Line endings are explicitly `\n` rather than the platform separator: this string becomes
     * bytes on the wire, so it must not vary with where the code runs.
     */
    fun toPem(certificate: X509Certificate): String = derToPem(certificate.encoded)

    /**
     * Renders DER-encoded certificate bytes as PEM text.
     */
    fun derToPem(der: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(der)
        val body = StringBuilder(base64.length + base64.length / PEM_LINE_LENGTH + 2)
        var index = 0
        while (index < base64.length) {
            val end = minOf(index + PEM_LINE_LENGTH, base64.length)
            body.append(base64, index, end).append('\n')
            index = end
        }
        return "$PEM_BEGIN\n$body$PEM_END\n"
    }

    /**
     * The exact bytes that go on the wire for `clientcert=` / arrive in `plaincert`.
     *
     * US-ASCII rather than UTF-8 so that a non-ASCII byte sneaking into the PEM text is an error
     * here rather than a silently multi-byte-encoded character the host cannot parse.
     */
    fun pemBytes(certificate: X509Certificate): ByteArray =
        toPem(certificate).toByteArray(Charsets.US_ASCII)

    /**
     * Parses a certificate from PEM text or raw DER.
     *
     * `CertificateFactory.generateCertificate` accepts both encodings, which is what lets the same
     * function read the host's hex-decoded `plaincert` (PEM) and our own stored `client.crt` (DER).
     *
     * @return the certificate, or `null` when the bytes are not a parseable X.509 certificate.
     *   Callers are always handling remote input, so a parse failure is a protocol error to report
     *   rather than an exception to propagate.
     */
    fun parseOrNull(encoded: ByteArray?): X509Certificate? {
        if (encoded == null || encoded.isEmpty()) return null
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            ByteArrayInputStream(encoded).use { stream ->
                factory.generateCertificate(stream) as? X509Certificate
            }
        } catch (t: Throwable) {
            // CertificateException, ClassCastException, and on some platforms
            // IllegalArgumentException for malformed base64 inside the PEM.
            null
        }
    }

    /**
     * True when [text] looks like PEM, used to decide whether stored bytes need conversion.
     */
    fun looksLikePem(text: String): Boolean = text.contains(PEM_BEGIN)

    /**
     * The SHA-256 fingerprint of [certificate]'s DER encoding, as lowercase hex.
     *
     * This is the same number `openssl x509 -fingerprint -sha256` prints (minus the colons), so a
     * logcat line and the host's own view of a certificate can be compared directly. It exists
     * because "the host does not recognise our certificate" and "the host never got far enough to
     * look at it" are indistinguishable from a read timeout, and the only way to settle it after
     * the fact is to have written down *which* certificate each side was holding.
     */
    fun fingerprint(certificate: X509Certificate): String = fingerprintOf(certificate.encoded)

    /** The SHA-256 fingerprint of arbitrary bytes, as lowercase hex. */
    fun fingerprintOf(bytes: ByteArray): String =
        Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * A one-line identification of [certificate] for a log.
     *
     * Serial, subject and fingerprint together: the serial and subject are what a host's client
     * list shows a user, and the fingerprint is what actually decides whether a presented
     * certificate is the pinned one.
     */
    fun describe(certificate: X509Certificate): String =
        "subject=${certificate.subjectX500Principal.name} " +
            "serial=${certificate.serialNumber.toString(SERIAL_RADIX)} " +
            "sha256=${fingerprint(certificate)}"

    /** Serials are conventionally shown in hex, which is what `openssl x509 -text` prints. */
    private const val SERIAL_RADIX = 16
}
