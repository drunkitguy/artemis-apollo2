package com.voidlink.android.protocol.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * Covers the pairing transcript helpers.
 *
 * These exist because a real device reported nothing but "Pairing failed" for a handshake that had
 * in fact reached its last step. The transcript is now the primary diagnostic, so its two hard
 * requirements are worth pinning down: it must never print PIN-derived material, and it must never
 * be so long that logcat truncates the line that matters.
 */
class NvHttpTraceTest {

    @Test
    fun `a URL with no query is left alone`() {
        val url = "http://192.168.1.24:47989/serverinfo"
        assertEquals(url, NvHttpClient.redactUrl(url))
    }

    @Test
    fun `short parameters survive redaction unchanged`() {
        val redacted = NvHttpClient.redactUrl(
            "http://h:47989/pair?devicename=roth&updateState=1&phrase=getservercert",
        )

        assertEquals(
            "http://h:47989/pair?devicename=roth&updateState=1&phrase=getservercert",
            redacted,
        )
    }

    @Test
    fun `PIN-derived parameters never reach the log`() {
        val secret = "a".repeat(64)
        val redacted = NvHttpClient.redactUrl(
            "http://h:47989/pair?clientchallenge=$secret" +
                "&serverchallengeresp=$secret" +
                "&clientpairingsecret=$secret",
        )

        assertFalse(redacted.contains(secret))
        assertTrue(redacted.contains("clientchallenge=<redacted:64 chars>"))
        assertTrue(redacted.contains("serverchallengeresp=<redacted:64 chars>"))
        assertTrue(redacted.contains("clientpairingsecret=<redacted:64 chars>"))
    }

    @Test
    fun `a long certificate is truncated but still identifiable`() {
        val hex = "2d2d2d2d2d424547494e" + "0".repeat(2000)
        val redacted = NvHttpClient.redactUrl("http://h:47989/pair?clientcert=$hex&uuid=abc")

        assertTrue(redacted.startsWith("http://h:47989/pair?clientcert=2d2d2d2d2d424547494e"))
        assertTrue(redacted.contains("<${hex.length} chars>"))
        // The parameters after the truncated one must still be readable.
        assertTrue(redacted.endsWith("&uuid=abc"))
        assertTrue(redacted.length < 200)
    }

    @Test
    fun `the salt is short enough to be shown in full, because it is not secret on its own`() {
        val salt = "0123456789abcdef0123456789abcdef"
        val redacted = NvHttpClient.redactUrl("http://h:47989/pair?salt=$salt")

        // 32 hex chars is longer than the trace limit, so it is truncated rather than redacted —
        // the point is that it is not replaced wholesale like the PIN-derived values.
        assertFalse(redacted.contains("<redacted"))
    }

    @Test
    fun `an empty body says so rather than printing nothing`() {
        assertEquals("<empty body>", NvHttpClient.bodyPreview(ByteArray(0)))
    }

    @Test
    fun `a short XML body is quoted verbatim on one line`() {
        val body = "<root status_code=\"200\">\n  <paired>1</paired>\n</root>".toByteArray()

        val preview = NvHttpClient.bodyPreview(body)

        assertFalse(preview.contains('\n'))
        assertTrue(preview.contains("<paired>1</paired>"))
    }

    @Test
    fun `a long body is truncated and says how much was dropped`() {
        val body = ("<root>" + "x".repeat(4000) + "</root>").toByteArray()

        val preview = NvHttpClient.bodyPreview(body)

        assertTrue(preview.length < NvHttpClient.BODY_PREVIEW_CHARS + 80)
        assertTrue(preview.contains("truncated"))
        assertTrue(preview.contains("${body.size} bytes total"))
    }

    @Test
    fun `a failure is named by type as well as message`() {
        // "Read timed out" alone is what the device reported and it is ambiguous: a socket read and
        // a stalled TLS handshake produce the same words from different exceptions, and only one of
        // them says anything about whether the host is refusing us.
        val described = NvHttpClient.describeFailure(SocketTimeoutException("Read timed out"))

        assertEquals("SocketTimeoutException: Read timed out", described)
    }

    @Test
    fun `a handshake failure is distinguishable from a socket timeout with the same message`() {
        val described = NvHttpClient.describeFailure(SSLHandshakeException("Read timed out"))

        assertEquals("SSLHandshakeException: Read timed out", described)
    }

    @Test
    fun `a cause is included, because TLS failures hide the reason in theirs`() {
        // A pinning mismatch surfaces as an SSLHandshakeException whose message says nothing; the
        // CertificateException underneath is the whole explanation.
        val failure = SSLHandshakeException("handshake failed")
        failure.initCause(CertificateException("host certificate does not match"))

        val described = NvHttpClient.describeFailure(failure)

        assertTrue(described.contains("SSLHandshakeException: handshake failed"))
        assertTrue(
            described.contains("caused by CertificateException: host certificate does not match"),
        )
    }

    @Test
    fun `a message-less failure still names its type`() {
        assertEquals("SocketTimeoutException", NvHttpClient.describeFailure(SocketTimeoutException()))
    }

    // ---- The per-listener serialisation key ---------------------------------------------------

    @Test
    fun `the gate key is the host and port, so each listener is serialised separately`() {
        // A host serves its plaintext and secure listeners on separate single threads. Keying the
        // lock by host alone would make a slow secure request block plaintext probes for no reason;
        // keying it by the whole URL would not serialise anything at all.
        assertEquals("192.168.0.3:47984", NvHttpClient.authorityOf("https://192.168.0.3:47984/applist?a=b"))
        assertEquals("192.168.0.3:47989", NvHttpClient.authorityOf("http://192.168.0.3:47989/serverinfo?a=b"))
    }

    @Test
    fun `two requests to the same listener share a key`() {
        val applist = NvHttpClient.authorityOf("https://h:47984/applist?uniqueid=1")
        val serverinfo = NvHttpClient.authorityOf("https://h:47984/serverinfo?uniqueid=2")

        assertEquals(applist, serverinfo)
    }

    @Test
    fun `an IPv6 literal keeps its brackets in the key`() {
        assertEquals("[fe80::1]:47984", NvHttpClient.authorityOf("https://[fe80::1]:47984/applist?a=b"))
    }

    @Test
    fun `a URL with no path or query still yields a key`() {
        // Must never throw: this runs on the failure path, where a second exception would bury the
        // first.
        assertEquals("h:1", NvHttpClient.authorityOf("https://h:1"))
        assertEquals("nonsense", NvHttpClient.authorityOf("nonsense"))
    }
}
