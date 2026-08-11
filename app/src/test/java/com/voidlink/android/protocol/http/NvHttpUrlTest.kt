package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.HostAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers URL composition and the box-art sniff of `docs/01-PROTOCOL.md` §3.1 and §3.5.
 *
 * Every NVHTTP call is a GET whose entire meaning lives in its query string, so a wrong separator
 * or an unbracketed IPv6 literal is a whole endpoint silently failing.
 */
class NvHttpUrlTest {

    @Test
    fun `a plaintext URL uses the address port`() {
        val url = NvHttpClient.buildUrl(
            scheme = "http",
            address = HostAddress("192.168.1.24", 47989),
            port = 47989,
            path = "serverinfo",
            params = listOf("uniqueid" to "0123456789abcdef", "uuid" to "abc-123"),
        )

        assertEquals(
            "http://192.168.1.24:47989/serverinfo?uniqueid=0123456789abcdef&uuid=abc-123",
            url,
        )
    }

    @Test
    fun `an HTTPS URL takes the port override rather than the address port`() {
        val url = NvHttpClient.buildUrl(
            scheme = "https",
            address = HostAddress("192.168.1.24", 47989),
            port = 47984,
            path = "applist",
            params = listOf("uniqueid" to "x", "uuid" to "y"),
        )

        assertTrue(url.startsWith("https://192.168.1.24:47984/applist?"))
    }

    @Test
    fun `an IPv6 host is bracketed`() {
        val url = NvHttpClient.buildUrl(
            scheme = "https",
            address = HostAddress("fe80::1", 47989),
            port = 47984,
            path = "serverinfo",
            params = listOf("uniqueid" to "x"),
        )

        assertEquals("https://[fe80::1]:47984/serverinfo?uniqueid=x", url)
    }

    @Test
    fun `parameters are joined with ampersands in the order given`() {
        val url = NvHttpClient.buildUrl(
            scheme = "http",
            address = HostAddress("h", 1),
            port = 1,
            path = "pair",
            params = listOf(
                "devicename" to "roth",
                "updateState" to "1",
                "phrase" to "getservercert",
                "uniqueid" to "id",
            ),
        )

        assertEquals(
            "http://h:1/pair?devicename=roth&updateState=1&phrase=getservercert&uniqueid=id",
            url,
        )
    }

    @Test
    fun `parameter values are percent-encoded so none can smuggle extra fields`() {
        val url = NvHttpClient.buildUrl(
            scheme = "http",
            address = HostAddress("h", 1),
            port = 1,
            path = "launch",
            params = listOf("evil" to "a&b=c", "space" to "a b", "slash" to "a/b"),
        )

        assertTrue(url.contains("evil=a%26b%3Dc"))
        assertTrue(url.contains("space=a+b"))
        assertTrue(url.contains("slash=a%2Fb"))
        // Exactly two separators: the ones we put there.
        assertEquals(2, url.count { it == '&' })
    }

    @Test
    fun `hex parameter values survive encoding unchanged`() {
        // Certificate hex and rikey hex are the values that actually travel; they must not be
        // mangled by the encoder.
        val hex = "2d2d2d2d2d424547494e0011223344556677"
        val url = NvHttpClient.buildUrl(
            scheme = "http",
            address = HostAddress("h", 1),
            port = 1,
            path = "pair",
            params = listOf("clientcert" to hex),
        )

        assertEquals("http://h:1/pair?clientcert=$hex", url)
    }

    @Test
    fun `PNG detection recognises the signature`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        assertTrue(NvHttpClient.looksLikePng(png))
    }

    @Test
    fun `PNG detection rejects everything else`() {
        // Spec §3.5: a host with no art may answer with a 404, an empty body, or XML. All three
        // must fall through to the generated tile rather than being handed to an image decoder.
        assertFalse(NvHttpClient.looksLikePng(ByteArray(0)))
        assertFalse(NvHttpClient.looksLikePng(byteArrayOf(0x89.toByte(), 0x50)))
        assertFalse(NvHttpClient.looksLikePng("<?xml version=\"1.0\"?>".toByteArray()))
        assertFalse(NvHttpClient.looksLikePng(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }
}
