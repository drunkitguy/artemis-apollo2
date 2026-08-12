package com.voidlink.android.protocol.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the byte-level classification the TLS self-test rests on.
 *
 * A real device reported `SocketTimeoutException: Read timed out` for *every* secure request to a
 * host whose plaintext port answered perfectly. That message cannot distinguish a wrong port from a
 * wedged HTTPS service from a stalled handshake, and guessing wrong sent the user to re-pair a PC
 * that had already paired with them. These few bytes are what tell the three apart.
 */
class TlsProbeTest {

    @Test
    fun `no bytes at all means the port accepted us and then went silent`() {
        // The reported failure. A TLS server answers junk; a wedged one answers nothing.
        assertEquals(PortBehaviour.ACCEPTED_THEN_SILENT, TlsProbe.classify(ByteArray(0)))
    }

    @Test
    fun `a TLS alert record means the port really is speaking TLS`() {
        // 0x15 = alert, 0x0303 = TLS 1.2 record version. This is a healthy server rejecting junk.
        val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x0A)
        assertEquals(PortBehaviour.TLS_SPEAKING, TlsProbe.classify(alert))
    }

    @Test
    fun `a handshake record also means the port is speaking TLS`() {
        val serverHello = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x50)
        assertEquals(PortBehaviour.TLS_SPEAKING, TlsProbe.classify(serverHello))
    }

    @Test
    fun `an HTTP response means we are pointed at the plaintext port`() {
        val http = "HTTP/1.1 404 NOT FOUND\r\n".toByteArray(Charsets.US_ASCII)
        assertEquals(PortBehaviour.PLAINTEXT_HTTP, TlsProbe.classify(http))
    }

    @Test
    fun `anything else is reported as unrecognised rather than guessed at`() {
        assertEquals(PortBehaviour.UNRECOGNISED, TlsProbe.classify(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun `a silent port yields a conclusion that names the service, not the pairing`() {
        val report = TlsProbeReport(
            port = 47984,
            behaviour = PortBehaviour.ACCEPTED_THEN_SILENT,
            attempts = emptyList(),
            workingProtocols = null,
        )

        val conclusion = report.conclusion()

        assertFalse(report.tlsWorks)
        assertTrue(conclusion.contains("47984"))
        assertTrue(conclusion.contains("never starts a TLS handshake"))
        // The user must not be sent to re-pair, which cannot possibly help.
        assertFalse(conclusion.contains("pair again"))
    }

    @Test
    fun `a plaintext port yields a conclusion that names the port as the problem`() {
        val report = TlsProbeReport(
            port = 47989,
            behaviour = PortBehaviour.PLAINTEXT_HTTP,
            attempts = emptyList(),
            workingProtocols = null,
        )

        assertTrue(report.conclusion().contains("plaintext HTTP server"))
        assertTrue(report.conclusion().contains("47989"))
    }

    @Test
    fun `a working handshake is reported as such, with the version that worked`() {
        val report = TlsProbeReport(
            port = 47984,
            behaviour = PortBehaviour.TLS_SPEAKING,
            attempts = listOf(
                TlsAttempt(
                    label = "handshake with client certificate over [TLSv1.2]",
                    clientCertificateRequested = true,
                    negotiatedProtocol = "TLSv1.2",
                    negotiatedCipher = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    protocols = listOf("TLSv1.2"),
                ),
            ),
            workingProtocols = listOf("TLSv1.2"),
        )

        assertTrue(report.tlsWorks)
        assertTrue(report.clientCertificateEverRequested)
        assertTrue(report.conclusion().contains("works"))
        assertTrue(report.summary().contains("TLSv1.2"))
    }

    @Test
    fun `a handshake that never reached client authentication says so`() {
        // The distinction that matters: if the host never asked for a certificate, nothing about
        // our key manager or our certificate can be the cause of the stall.
        val report = TlsProbeReport(
            port = 47984,
            behaviour = PortBehaviour.UNRECOGNISED,
            attempts = listOf(
                TlsAttempt(
                    label = "handshake with client certificate over [TLSv1.2, TLSv1.3]",
                    clientCertificateRequested = false,
                    protocols = listOf("TLSv1.2", "TLSv1.3"),
                    failure = "SocketTimeoutException: Read timed out",
                ),
            ),
            workingProtocols = null,
        )

        assertFalse(report.clientCertificateEverRequested)
        assertTrue(report.conclusion().contains("never asked for a client certificate"))
        assertTrue(report.conclusion().contains("not a pairing problem"))
    }
}
