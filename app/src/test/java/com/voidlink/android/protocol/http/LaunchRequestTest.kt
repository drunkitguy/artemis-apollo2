package com.voidlink.android.protocol.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `/launch` query string of `docs/01-PROTOCOL.md` §3.6, the two host-specific
 * corrections it carries, and the `sessionUrl0` parsing of the reply.
 *
 * The corrections are the reason this is worth testing without a host: a SOPS clamp that only
 * fires for one vendor at one range of resolutions is exactly the kind of conditional that rots
 * unnoticed.
 */
class LaunchRequestTest {

    // ---- Audio configuration (spec §8.2) -----------------------------------------------------

    @Test
    fun `surroundAudioInfo packs the mask above the channel count`() {
        // (channelMask shl 16) or channelCount — and notably *not* the composite
        // MAKE_AUDIO_CONFIGURATION value, which carries an extra 0xCA marker and never goes on
        // the wire.
        assertEquals(196610, AudioChannelLayout.STEREO.surroundAudioInfo)
        assertEquals(4128774, AudioChannelLayout.SURROUND_5_1.surroundAudioInfo)
        assertEquals(104792072, AudioChannelLayout.SURROUND_7_1.surroundAudioInfo)

        assertEquals(2, AudioChannelLayout.STEREO.channelCount)
        assertEquals(0x3, AudioChannelLayout.STEREO.channelMask)
        assertEquals(6, AudioChannelLayout.SURROUND_5_1.channelCount)
        assertEquals(0x3F, AudioChannelLayout.SURROUND_5_1.channelMask)
        assertEquals(8, AudioChannelLayout.SURROUND_7_1.channelCount)
        assertEquals(0x63F, AudioChannelLayout.SURROUND_7_1.channelMask)
    }

    // ---- Query string ------------------------------------------------------------------------

    @Test
    fun `the parameter set and order match the reference implementation`() {
        val params = request().toQueryParams(isNvidiaGfe = false)

        assertEquals(
            listOf(
                "appid", "mode", "additionalStates", "sops", "rikey", "rikeyid",
                "localAudioPlayMode", "surroundAudioInfo", "remoteControllersBitmap",
                "gcmap", "gcpersist",
            ),
            params.map { it.first },
        )
    }

    @Test
    fun `values are rendered in the forms the host expects`() {
        val params = request().toQueryParams(isNvidiaGfe = false).toMap()

        assertEquals("881448767", params["appid"])
        assertEquals("1920x1080x60", params["mode"])
        assertEquals("1", params["additionalStates"])
        assertEquals("0", params["sops"])
        // rikey is hex; rikeyid is decimal.
        assertEquals("000102030405060708090a0b0c0d0e0f", params["rikey"])
        assertEquals("-559038737", params["rikeyid"])
        assertEquals("0", params["localAudioPlayMode"])
        assertEquals("196610", params["surroundAudioInfo"])
        assertEquals("3", params["remoteControllersBitmap"])
        assertEquals("3", params["gcmap"])
        assertEquals("0", params["gcpersist"])
    }

    @Test
    fun `HDR parameters appear only when HDR is requested`() {
        val without = request(hdr = false).toQueryParams(isNvidiaGfe = false).toMap()
        assertFalse(without.containsKey("hdrMode"))
        assertFalse(without.containsKey("clientHdrCapVersion"))

        val with = request(hdr = true).toQueryParams(isNvidiaGfe = false).toMap()
        assertEquals("1", with["hdrMode"])
        assertEquals("0", with["clientHdrCapVersion"])
        assertEquals("0", with["clientHdrCapSupportedFlagsInUint32"])
        assertEquals("NV_STATIC_METADATA_TYPE_1", with["clientHdrCapMetaDataId"])
        assertEquals("0x0x0x0x0x0x0x0x0x0x0", with["clientHdrCapDisplayData"])
    }

    @Test
    fun `gcmap always mirrors remoteControllersBitmap`() {
        val params = request(gamepads = 0b1011).toQueryParams(isNvidiaGfe = false).toMap()
        assertEquals(params["remoteControllersBitmap"], params["gcmap"])
        assertEquals("11", params["gcmap"])
    }

    // ---- SOPS clamp (spec §3.6) --------------------------------------------------------------

    @Test
    fun `SOPS is untouched on a Sunshine host at any resolution`() {
        assertTrue(request(sops = true, width = 2560, height = 1440).resolveSops(isNvidiaGfe = false))
        assertTrue(request(sops = true, width = 1920, height = 1080).resolveSops(isNvidiaGfe = false))
        assertFalse(request(sops = false, width = 1920, height = 1080).resolveSops(isNvidiaGfe = false))
    }

    @Test
    fun `SOPS survives on GFE at or below 720p`() {
        assertTrue(request(sops = true, width = 1280, height = 720).resolveSops(isNvidiaGfe = true))
        assertTrue(request(sops = true, width = 854, height = 480).resolveSops(isNvidiaGfe = true))
    }

    @Test
    fun `SOPS survives on GFE at exactly 1080p and 4K`() {
        assertTrue(request(sops = true, width = 1920, height = 1080).resolveSops(isNvidiaGfe = true))
        assertTrue(request(sops = true, width = 3840, height = 2160).resolveSops(isNvidiaGfe = true))
    }

    @Test
    fun `SOPS is forced off on GFE for a non-standard resolution above 720p`() {
        // GFE would otherwise clamp the whole session to 720p60.
        assertFalse(request(sops = true, width = 2560, height = 1440).resolveSops(isNvidiaGfe = true))
        assertFalse(request(sops = true, width = 1920, height = 1200).resolveSops(isNvidiaGfe = true))
        assertFalse(request(sops = true, width = 3440, height = 1440).resolveSops(isNvidiaGfe = true))

        val params = request(sops = true, width = 2560, height = 1440)
            .toQueryParams(isNvidiaGfe = true)
            .toMap()
        assertEquals("0", params["sops"])
    }

    @Test
    fun `a user who disabled SOPS is never overridden into enabling it`() {
        assertFalse(request(sops = false, width = 1920, height = 1080).resolveSops(isNvidiaGfe = true))
    }

    // ---- Frame-rate workaround (spec §3.6) ---------------------------------------------------

    @Test
    fun `the mode frame rate is sent verbatim for a Sunshine host`() {
        assertEquals(120, request(fps = 120).resolveModeFps(isNvidiaGfe = false))
        assertEquals(
            "1920x1080x120",
            request(fps = 120).toQueryParams(isNvidiaGfe = false).toMap()["mode"],
        )
    }

    @Test
    fun `the mode frame rate is sent verbatim for GFE at or below 60`() {
        assertEquals(60, request(fps = 60).resolveModeFps(isNvidiaGfe = true))
        assertEquals(30, request(fps = 30).resolveModeFps(isNvidiaGfe = true))
    }

    @Test
    fun `GFE above 60 fps gets the zero workaround so RTSP negotiates the real rate`() {
        assertEquals(0, request(fps = 120).resolveModeFps(isNvidiaGfe = true))
        assertEquals(0, request(fps = 90).resolveModeFps(isNvidiaGfe = true))
        assertEquals(
            "1920x1080x0",
            request(fps = 120).toQueryParams(isNvidiaGfe = true).toMap()["mode"],
        )
    }

    // ---- sessionUrl0 (spec §3.6, §6.1) -------------------------------------------------------

    @Test
    fun `a launch response reports success from gamesession and parses the RTSP port`() {
        val root = XmlNode(
            name = "root",
            attributes = mapOf("status_code" to "200"),
            children = listOf(
                XmlNode("gamesession", text = "1"),
                XmlNode("sessionUrl0", text = "rtsp://192.168.1.50:48010"),
            ),
        )

        val response = LaunchResponse.fromXml(root, "gamesession")

        assertTrue(response.started)
        assertEquals(48010, response.rtspPort)
        assertFalse(response.rtspOverEnet)
        assertEquals("rtsp://192.168.1.50:48010", response.sessionUrl)
    }

    @Test
    fun `a resume response reports success from the resume element instead`() {
        val root = XmlNode(
            name = "root",
            children = listOf(XmlNode("resume", text = "1"), XmlNode("gamesession", text = "0")),
        )

        assertTrue(LaunchResponse.fromXml(root, "resume").started)
        assertFalse(LaunchResponse.fromXml(root, "gamesession").started)
    }

    @Test
    fun `a zero or absent success element means failure`() {
        assertFalse(
            LaunchResponse.fromXml(
                XmlNode("root", children = listOf(XmlNode("gamesession", text = "0"))),
                "gamesession",
            ).started,
        )
        assertFalse(LaunchResponse.fromXml(XmlNode("root"), "gamesession").started)
    }

    @Test
    fun `an absent sessionUrl0 leaves the port null so the caller falls back to the default`() {
        val response = LaunchResponse.fromXml(
            XmlNode("root", children = listOf(XmlNode("gamesession", text = "1"))),
            "gamesession",
        )
        assertTrue(response.started)
        assertNull(response.sessionUrl)
        assertNull(response.rtspPort)
    }

    @Test
    fun `the rtspru scheme is detected without being confused for rtsp`() {
        assertEquals(true to true, parsed("rtspru://192.168.1.50:48010"))
        assertEquals(true to false, parsed("rtsp://192.168.1.50:48010"))
        assertEquals(true to false, parsed("RTSP://192.168.1.50:48010"))
        assertEquals(true to true, parsed("RTSPRU://192.168.1.50:48010"))
    }

    @Test
    fun `a bracketed IPv6 session URL yields its port`() {
        assertEquals(48010, LaunchResponse.parseSessionUrl("rtsp://[fe80::1]:48010")!!.first)
        assertNull(LaunchResponse.parseSessionUrl("rtsp://[fe80::1]")!!.first)
    }

    @Test
    fun `a malformed session URL never throws`() {
        assertNull(LaunchResponse.parseSessionUrl(null))
        assertNull(LaunchResponse.parseSessionUrl(""))
        assertNull(LaunchResponse.parseSessionUrl("192.168.1.50:48010"))
        assertNull(LaunchResponse.parseSessionUrl("rtsp://192.168.1.50")!!.first)
        assertNull(LaunchResponse.parseSessionUrl("rtsp://192.168.1.50:notaport")!!.first)
        assertNull(LaunchResponse.parseSessionUrl("rtsp://192.168.1.50:99999")!!.first)
    }

    /** Whether a port was found, paired with whether the scheme was `rtspru`. */
    private fun parsed(url: String): Pair<Boolean, Boolean> {
        val result = LaunchResponse.parseSessionUrl(url)!!
        return (result.first != null) to result.second
    }

    private fun request(
        appId: Long = 881448767L,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60,
        sops: Boolean = false,
        hdr: Boolean = false,
        gamepads: Int = 3,
    ) = LaunchRequest(
        appId = appId,
        width = width,
        height = height,
        fps = fps,
        remoteInputKey = ByteArray(16) { it.toByte() },
        remoteInputKeyId = -559038737, // 0xDEADBEEF as a signed Int
        optimizeGameSettings = sops,
        hdr = hdr,
        playAudioOnHost = false,
        audioLayout = AudioChannelLayout.STEREO,
        attachedGamepadMask = gamepads,
        persistGamepadsAfterDisconnect = false,
    )
}
