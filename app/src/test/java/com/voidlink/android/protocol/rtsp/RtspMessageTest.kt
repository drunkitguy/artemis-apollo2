package com.voidlink.android.protocol.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the RTSP message format of `docs/01-PROTOCOL.md` §6.2 — serialisation, framing and the
 * deliberately forgiving response parser.
 *
 * The spec warns that some GFE builds emit a malformed status line or a mis-ordered `CSeq`. Being
 * strict here would mean abandoning a working session over a cosmetic defect, so most of these
 * tests are about what the parser *survives* rather than what it rejects.
 */
class RtspMessageTest {

    // ---- Requests -------------------------------------------------------------------------------

    @Test
    fun `a request serialises to the exact wire form`() {
        val request = RtspRequest(
            method = RtspConstants.METHOD_DESCRIBE,
            target = "rtsp://192.168.1.50:48010",
            headers = listOf(
                RtspConstants.HEADER_CSEQ to "2",
                RtspConstants.HEADER_CLIENT_VERSION to "14",
                RtspConstants.HEADER_ACCEPT to RtspConstants.MIME_SDP,
                RtspConstants.HEADER_IF_MODIFIED_SINCE to RtspConstants.IF_MODIFIED_SINCE_VALUE,
            ),
        )

        assertEquals(
            "DESCRIBE rtsp://192.168.1.50:48010 RTSP/1.0\r\n" +
                "CSeq: 2\r\n" +
                "X-GS-ClientVersion: 14\r\n" +
                "Accept: application/sdp\r\n" +
                "If-Modified-Since: Thu, 01 Jan 1970 00:00:00 GMT\r\n" +
                "\r\n",
            request.encodeToString(),
        )
        assertEquals(2, request.cseq)
    }

    @Test
    fun `a request with a payload puts the blank line before it and nothing after`() {
        val request = RtspRequest(
            method = RtspConstants.METHOD_ANNOUNCE,
            target = "rtsp://h:48010/streamid=control/13/0",
            headers = listOf(
                RtspConstants.HEADER_CSEQ to "6",
                RtspConstants.HEADER_CONTENT_LENGTH to "5",
            ),
            body = "v=0\r\n",
        )
        assertTrue(request.encodeToString().endsWith("Content-length: 5\r\n\r\nv=0\r\n"))
        assertEquals(request.encodeToString().toByteArray(Charsets.UTF_8).size, request.encode().size)
    }

    // ---- Responses ------------------------------------------------------------------------------

    @Test
    fun `a well formed response parses into status, headers and body`() {
        val response = RtspMessageCodec.parseResponse(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: 4\r\n" +
                "Session: DEADBEEFCAFE;timeout=90\r\n" +
                "Transport: server_port=48000\r\n" +
                "Content-length: 6\r\n" +
                "\r\n" +
                "v=0\r\n\r\n",
        )

        assertNotNull(response)
        assertEquals(200, response!!.statusCode)
        assertEquals("OK", response.reasonPhrase)
        assertTrue(response.isOk)
        assertEquals(4, response.cseq)
        assertEquals("DEADBEEFCAFE;timeout=90", response.header("Session"))
        assertEquals("v=0\r\n\r", response.body)
    }

    @Test
    fun `header lookup is case insensitive because hosts disagree about capitalisation`() {
        val response = RtspMessageCodec.parseResponse(
            "RTSP/1.0 200 OK\r\nContent-Length: 0\r\nX-SS-Ping-Payload: 0123456789abcdef\r\n\r\n",
        )
        assertNotNull(response)
        assertEquals("0123456789abcdef", response!!.header("x-ss-ping-payload"))
        assertEquals("0", response.header("content-length"))
    }

    @Test
    fun `a mangled status line still yields the status code`() {
        // Spec §6.2 explicitly warns about this; failing here would abandon a live session.
        val mangled = RtspMessageCodec.parseResponse("RTSP/1.0  200  OK\r\nCSeq: 1\r\n\r\n")
        assertNotNull(mangled)
        assertEquals(200, mangled!!.statusCode)
        assertEquals("OK", mangled.reasonPhrase)

        val noProtocol = RtspMessageCodec.parseResponse("200 OK\r\n\r\n")
        assertNotNull(noProtocol)
        assertEquals(200, noProtocol!!.statusCode)
    }

    @Test
    fun `an absent CSeq is tolerated`() {
        val response = RtspMessageCodec.parseResponse("RTSP/1.0 200 OK\r\nSession: ABC\r\n\r\n")
        assertNotNull(response)
        assertNull(response!!.cseq)
        assertEquals("ABC", response.header("Session"))
    }

    @Test
    fun `an unknown header is kept and never fatal`() {
        val response = RtspMessageCodec.parseResponse(
            "RTSP/1.0 200 OK\r\nX-Something-New: 42\r\nnot-a-header-line\r\n\r\n",
        )
        assertNotNull(response)
        assertEquals("42", response!!.header("X-Something-New"))
        assertEquals(1, response.headers.size)
    }

    @Test
    fun `a response with no status code at all is malformed`() {
        assertNull(RtspMessageCodec.parseResponse("garbage\r\n\r\n"))
        assertNull(RtspMessageCodec.parseResponse("RTSP/1.0 OK\r\n\r\n"))
        assertNull(RtspMessageCodec.parseResponse("no separator at all"))
    }

    @Test
    fun `an error status parses as an error rather than as a failure to parse`() {
        val refused = RtspMessageCodec.parseResponse("RTSP/1.0 454 Session Not Found\r\nCSeq: 5\r\n\r\n")
        assertNotNull(refused)
        assertEquals(454, refused!!.statusCode)
        assertEquals("Session Not Found", refused.reasonPhrase)
        assertFalse(refused.isOk)
    }

    @Test
    fun `bare LF separated headers parse, because forgiving beats correct here`() {
        val response = RtspMessageCodec.parseResponse("RTSP/1.0 200 OK\nCSeq: 3\n\n")
        assertNotNull(response)
        assertEquals(200, response!!.statusCode)
        assertEquals(3, response.cseq)
    }

    // ---- Framing --------------------------------------------------------------------------------

    @Test
    fun `a message is incomplete until its headers and its declared body have arrived`() {
        val whole = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-length: 4\r\n\r\nabcd"
        val bytes = whole.toByteArray(Charsets.UTF_8)

        for (prefix in 1 until bytes.size) {
            assertEquals(
                "unexpectedly complete after $prefix of ${bytes.size} bytes",
                RtspMessageCodec.INCOMPLETE,
                RtspMessageCodec.completeMessageLength(bytes, prefix),
            )
        }
        assertEquals(bytes.size, RtspMessageCodec.completeMessageLength(bytes, bytes.size))
    }

    @Test
    fun `a second message queued behind the first is not swallowed`() {
        val first = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-length: 4\r\n\r\nabcd"
        val second = "RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"
        val bytes = (first + second).toByteArray(Charsets.UTF_8)

        val length = RtspMessageCodec.completeMessageLength(bytes, bytes.size)
        assertEquals(first.toByteArray(Charsets.UTF_8).size, length)

        val response = RtspMessageCodec.parseResponse(bytes, length)
        assertNotNull(response)
        assertEquals(1, response!!.cseq)
        assertEquals("abcd", response.body)
    }

    @Test
    fun `no Content-length means no body, not read until something looks right`() {
        val text = "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(bytes.size, RtspMessageCodec.completeMessageLength(bytes, bytes.size))
        assertEquals("", RtspMessageCodec.parseResponse(bytes, bytes.size)!!.body)
    }

    @Test
    fun `an unparseable Content-length is treated as zero`() {
        assertEquals(0, RtspMessageCodec.contentLengthOf("RTSP/1.0 200 OK\r\nContent-length: eight"))
        assertEquals(0, RtspMessageCodec.contentLengthOf("RTSP/1.0 200 OK\r\nContent-length: -4"))
        assertEquals(12, RtspMessageCodec.contentLengthOf("RTSP/1.0 200 OK\r\nCONTENT-LENGTH:  12 "))
    }

    @Test
    fun `status line parsing finds the first three digit token`() {
        assertEquals(200 to "OK", RtspMessageCodec.parseStatusLine("RTSP/1.0 200 OK"))
        assertEquals(500 to "", RtspMessageCodec.parseStatusLine("RTSP/1.0 500"))
        assertEquals(
            404 to "Not Found Here",
            RtspMessageCodec.parseStatusLine("RTSP/1.0 404 Not Found Here"),
        )
        assertNull(RtspMessageCodec.parseStatusLine("RTSP/1.0"))
        assertNull(RtspMessageCodec.parseStatusLine(""))
    }
}
