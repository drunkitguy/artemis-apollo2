package com.voidlink.android.protocol.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers what we read back out of the host: the DESCRIBE SDP of `docs/01-PROTOCOL.md` §6.3, the
 * Opus multistream configuration of §8.3, and the SETUP response headers of §6.3.
 *
 * The governing rule for all of it is that the host is allowed to surprise us. Spec §6.3 says
 * plainly that the full attribute set Sunshine emits is unknown, so an unrecognised attribute is
 * information to be logged, never a reason to abandon a session that is otherwise fine.
 */
class SessionDescriptionTest {

    // ---- Parsing a realistic DESCRIBE body ------------------------------------------------------

    @Test
    fun `a realistic Sunshine DESCRIBE body parses and keeps every attribute`() {
        val sdp = SessionDescription.parse(SUNSHINE_DESCRIBE)

        assertEquals("1", sdp.attribute("x-nv-general.featureFlags"))
        assertEquals("1869449984", sdp.attribute("x-nv-video[0].maxLumaPixelsHEVC"))
        // Unknown to us, kept anyway — that is the point.
        assertEquals("something-we-have-never-seen", sdp.attribute("x-ss-future.mysteryOption"))
        assertTrue(sdp.lines.contains("s=NVIDIA Streaming Server"))
    }

    @Test
    fun `an attribute we do not recognise is not fatal and does not displace one we do`() {
        val sdp = SessionDescription.parse(
            """
            v=0
            a=totally-unknown:1
            a=x-nv-video[0].maxFPS:120
            a=another-unknown
            """.trimIndent().replace("\n", "\r\n"),
        )
        assertEquals("120", sdp.attribute("x-nv-video[0].maxFPS"))
        assertEquals("1", sdp.attribute("totally-unknown"))
        // A bare flag attribute has no value, and that is different from being absent.
        assertNull(sdp.attribute("another-unknown"))
        assertTrue(sdp.attributes.any { it.name == "another-unknown" })
    }

    @Test
    fun `missing optional fields yield nulls rather than failures`() {
        val sdp = SessionDescription.parse("v=0\r\ns=NVIDIA Streaming Server\r\n")
        assertNull(sdp.attribute("x-nv-video[0].maxFPS"))
        assertNull(sdp.spropParameterSets)
        assertTrue(sdp.attributes.isEmpty())
        assertEquals(2, sdp.lines.size)
    }

    @Test
    fun `malformed input parses to something empty instead of throwing`() {
        for (body in listOf("", "   ", "\r\n\r\n", "not an sdp at all", "a=", "=:", "a=:value")) {
            val sdp = SessionDescription.parse(body)
            assertNotNull(sdp.attributes)
            assertNull(sdp.attribute("x-nv-video[0].maxFPS"))
        }
    }

    @Test
    fun `bare LF line endings parse the same as CRLF`() {
        val crlf = SessionDescription.parse("v=0\r\na=x-nv-video[0].maxFPS:60\r\n")
        val lf = SessionDescription.parse("v=0\na=x-nv-video[0].maxFPS:60\n")
        assertEquals(crlf.attribute("x-nv-video[0].maxFPS"), lf.attribute("x-nv-video[0].maxFPS"))
    }

    @Test
    fun `sprop parameter sets are surfaced for logging only`() {
        val sdp = SessionDescription.parse(
            "v=0\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0LAH9oBQBboQAAA;profile=1\r\n",
        )
        assertEquals("Z0LAH9oBQBboQAAA", sdp.spropParameterSets)
    }

    // ---- Opus multistream configuration (spec §8.3) --------------------------------------------

    @Test
    fun `stereo needs no negotiation`() {
        val config = OpusMultistreamConfig.parseSurround(SessionDescription.parse(""), 2)
        assertNotNull(config)
        assertEquals(2, config!!.channelCount)
        assertEquals(1, config.streams)
        assertEquals(1, config.coupledStreams)
        assertArrayEquals(intArrayOf(0, 1), config.mapping)
        assertEquals(48_000, config.sampleRateHz)
    }

    @Test
    fun `5 point 1 surround parameters are read digit by digit and reordered for the decoder`() {
        val sdp = SessionDescription.parse(SUNSHINE_DESCRIBE)
        val config = OpusMultistreamConfig.parseSurround(sdp, 6)

        assertNotNull(config)
        assertEquals(6, config!!.channelCount)
        assertEquals(4, config.streams)
        assertEquals(2, config.coupledStreams)
        // Host order FL FR C RL RR SL SR LFE becomes FL FR C LFE RL RR: the trailing LFE (5) moves
        // to index 3 and 3,4 slide up. Skipping this plays perfectly out of the wrong speakers.
        assertArrayEquals(intArrayOf(0, 1, 2, 5, 3, 4), config.mapping)
    }

    @Test
    fun `7 point 1 surround parameters pick the run whose channel count matches`() {
        val sdp = SessionDescription.parse(SUNSHINE_DESCRIBE)
        val config = OpusMultistreamConfig.parseSurround(sdp, 8)

        assertNotNull(config)
        assertEquals(8, config!!.channelCount)
        assertEquals(5, config.streams)
        assertEquals(3, config.coupledStreams)
        assertArrayEquals(intArrayOf(0, 1, 2, 7, 3, 4, 5, 6), config.mapping)
    }

    @Test
    fun `a host offering no run for the requested layout yields null so the caller can downgrade`() {
        val sdp = SessionDescription.parse("v=0\r\na=fmtp:97 surround-params=642012345\r\n")
        assertNull(OpusMultistreamConfig.parseSurround(sdp, 8))
        assertNotNull(OpusMultistreamConfig.parseSurround(sdp, 6))
    }

    @Test
    fun `a truncated or non numeric parameter run is rejected rather than half read`() {
        assertNull(
            OpusMultistreamConfig.parseSurround(
                SessionDescription.parse("a=fmtp:97 surround-params=6420\r\n"),
                6,
            ),
        )
        assertNull(
            OpusMultistreamConfig.parseSurround(
                SessionDescription.parse("a=fmtp:97 surround-params=64201x345\r\n"),
                6,
            ),
        )
    }

    @Test
    fun `the reorder is a no-op for stereo and idempotent in shape for surround`() {
        assertArrayEquals(
            intArrayOf(0, 1),
            OpusMultistreamConfig.reorderForDecoder(intArrayOf(0, 1), 2),
        )
        val reordered = OpusMultistreamConfig.reorderForDecoder(intArrayOf(0, 1, 2, 3, 4, 5), 6)
        assertArrayEquals(intArrayOf(0, 1, 2, 5, 3, 4), reordered)
        assertEquals(6, reordered.toSet().size)
    }

    // ---- SETUP response headers (spec §6.3) ----------------------------------------------------

    @Test
    fun `server_port is parsed from a Transport header in either form`() {
        assertEquals(48000, RtspHeaderParser.serverPort("server_port=48000"))
        assertEquals(47998, RtspHeaderParser.serverPort("unicast;server_port=47998-47999"))
        assertEquals(
            47999,
            RtspHeaderParser.serverPort("RTP/AVP/UDP;unicast;client_port=50000;server_port=47999;ssrc=0"),
        )
        assertEquals(48010, RtspHeaderParser.serverPort("SERVER_PORT=48010"))
    }

    @Test
    fun `an absent or unusable server_port yields null so the caller applies the documented default`() {
        assertNull(RtspHeaderParser.serverPort(null))
        assertNull(RtspHeaderParser.serverPort("unicast"))
        assertNull(RtspHeaderParser.serverPort("server_port="))
        assertNull(RtspHeaderParser.serverPort("server_port=abc"))
        assertNull(RtspHeaderParser.serverPort("server_port=0"))
        assertNull(RtspHeaderParser.serverPort("server_port=99999"))
    }

    @Test
    fun `the session id is truncated at the first semicolon`() {
        // Echoing the whole "DEADBEEFCAFE;timeout=90" back earns a 454 from a strict server.
        assertEquals("DEADBEEFCAFE", RtspHeaderParser.sessionId("DEADBEEFCAFE;timeout=90"))
        assertEquals("DEADBEEFCAFE", RtspHeaderParser.sessionId("  DEADBEEFCAFE  "))
        assertEquals("12345678", RtspHeaderParser.sessionId("12345678"))
        assertNull(RtspHeaderParser.sessionId(null))
        assertNull(RtspHeaderParser.sessionId(""))
        assertNull(RtspHeaderParser.sessionId(";timeout=90"))
    }

    @Test
    fun `a ping payload is accepted only at exactly sixteen characters`() {
        val payload = "0123456789abcdef"
        assertEquals(16, payload.length)
        assertEquals(payload, RtspHeaderParser.pingPayload(payload))
        assertNull(RtspHeaderParser.pingPayload(null))
        assertNull(RtspHeaderParser.pingPayload(""))
        assertNull(RtspHeaderParser.pingPayload("short"))
        assertNull(RtspHeaderParser.pingPayload(payload + "extra"))
    }

    @Test
    fun `connect data is parsed with base auto detection and defaults to zero`() {
        assertEquals(0, RtspHeaderParser.connectData(null))
        assertEquals(0, RtspHeaderParser.connectData(""))
        assertEquals(0, RtspHeaderParser.connectData("not a number"))
        assertEquals(1234, RtspHeaderParser.connectData("1234"))
        assertEquals(0x1234, RtspHeaderParser.connectData("0x1234"))
        assertEquals(0xABCDEF, RtspHeaderParser.connectData("0XABCDEF"))
        // Values above Int.MAX_VALUE are kept as the 32-bit pattern, which is what ENet sends.
        assertEquals(-1, RtspHeaderParser.connectData("4294967295"))
        assertEquals(-1, RtspHeaderParser.connectData("0xFFFFFFFF"))
        assertEquals(0, RtspHeaderParser.connectData("0x1FFFFFFFF"))
    }

    private companion object {
        /**
         * A DESCRIBE body shaped like a real Sunshine answer: several surround runs, attributes we
         * consume, and attributes we have never seen before.
         */
        val SUNSHINE_DESCRIBE: String = listOf(
            "v=0",
            "o=- 0 0 IN IP4 192.168.1.50",
            "s=NVIDIA Streaming Server",
            "a=x-nv-general.featureFlags:1",
            "a=x-nv-video[0].maxLumaPixelsHEVC:1869449984",
            "a=x-ss-future.mysteryOption:something-we-have-never-seen",
            "a=fmtp:97 surround-params=642012345",
            "a=fmtp:97 surround-params=85301234567",
            "a=fmtp:96 packetization-mode=1",
            "t=0 0",
            "m=video 47998  ",
        ).joinToString(separator = "") { it + "\r\n" }
    }
}
