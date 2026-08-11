package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.UnverifiedProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `/serverinfo` and `/applist` element contracts of `docs/01-PROTOCOL.md` §3.3 and
 * §3.4, including every "this value means unknown" special case the spec calls out.
 */
class ServerInfoTest {

    // ---- AppVersion (spec §0.3) --------------------------------------------------------------

    @Test
    fun `app version parses the quad and exposes the generation`() {
        val version = AppVersion.parse("7.1.431.0")!!
        assertEquals(listOf(7, 1, 431, 0), version.components)
        assertEquals(7, version.generation)
        assertEquals("7.1.431.0", version.toString())
    }

    @Test
    fun `app version tolerates a short or suffixed string`() {
        assertEquals(7, AppVersion.parse("7")!!.generation)
        assertEquals(listOf(7, 1), AppVersion.parse("7.1")!!.components)
        // A host reporting a build suffix still yields a usable generation.
        assertEquals(7, AppVersion.parse("7.1.431.0-beta")!!.generation)
        assertEquals(listOf(7, 1, 431), AppVersion.parse("7.1.431.0-beta")!!.components)
    }

    @Test
    fun `app version rejects text with no leading integer`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("   "))
        assertNull(AppVersion.parse("unknown"))
    }

    @Test
    fun `atLeast compares the first three components lexicographically`() {
        val version = AppVersion.parse("7.1.431.0")!!

        assertTrue(version.atLeast(7, 1, 431))
        assertTrue(version.atLeast(7, 1, 430))
        assertTrue(version.atLeast(7, 0, 999))
        assertTrue(version.atLeast(6, 9, 9))
        assertFalse(version.atLeast(7, 1, 432))
        assertFalse(version.atLeast(7, 2, 0))
        assertFalse(version.atLeast(8, 0, 0))
    }

    // ---- ServerInfo --------------------------------------------------------------------------

    @Test
    fun `a full Sunshine response maps onto every field`() {
        val info = ServerInfo.fromXml(sunshineRoot())!!

        assertEquals("BATTLESTATION", info.hostname)
        assertEquals(7, info.appVersion.generation)
        assertEquals("host-uuid-1", info.uniqueId)
        assertEquals(47984, info.httpsPort)
        assertEquals(1869449984L, info.maxLumaPixelsHevc)
        assertTrue(info.supportsHevc)
        assertEquals(ServerKind.SUNSHINE_FAMILY, info.serverKind)
        assertFalse(info.isNvidiaGfe)
        assertFalse(info.isBusy)
        assertTrue(info.pairStatus)
        assertNull(info.currentGameId)
    }

    @Test
    fun `appversion and uniqueid are the only required elements`() {
        assertNull(ServerInfo.fromXml(root(node("uniqueid", text = "x"))))
        assertNull(ServerInfo.fromXml(root(node("appversion", text = "7.1.431.0"))))
        assertNotNull(
            ServerInfo.fromXml(
                root(node("appversion", text = "7.1.431.0"), node("uniqueid", text = "x")),
            ),
        )
    }

    @Test
    fun `the placeholder MAC is reported as unknown`() {
        // Sunshine returns all zeros over plaintext HTTP; that means "not telling you", not a
        // real address, and Wake-on-LAN must not offer to use it (spec §1.4).
        val info = ServerInfo.fromXml(baseRoot(node("mac", text = "00:00:00:00:00:00")))!!
        assertNull(info.macAddress)

        val real = ServerInfo.fromXml(baseRoot(node("mac", text = "AA:BB:CC:DD:EE:FF")))!!
        assertEquals("AA:BB:CC:DD:EE:FF", real.macAddress)
    }

    @Test
    fun `the loopback LocalIP Sunshine returns for IPv6 requests is ignored`() {
        assertNull(ServerInfo.fromXml(baseRoot(node("LocalIP", text = "127.0.0.1")))!!.localIp)
        assertEquals(
            "192.168.1.24",
            ServerInfo.fromXml(baseRoot(node("LocalIP", text = "192.168.1.24")))!!.localIp,
        )
    }

    @Test
    fun `an absent or nonsensical HttpsPort falls back to the default`() {
        assertEquals(47984, ServerInfo.fromXml(baseRoot())!!.httpsPort)
        assertEquals(47984, ServerInfo.fromXml(baseRoot(node("HttpsPort", text = "0")))!!.httpsPort)
        assertEquals(
            47984,
            ServerInfo.fromXml(baseRoot(node("HttpsPort", text = "not a port")))!!.httpsPort,
        )
        assertEquals(1234, ServerInfo.fromXml(baseRoot(node("HttpsPort", text = "1234")))!!.httpsPort)
    }

    @Test
    fun `currentgame zero means idle`() {
        assertNull(ServerInfo.fromXml(baseRoot(node("currentgame", text = "0")))!!.currentGameId)
        assertNull(ServerInfo.fromXml(baseRoot())!!.currentGameId)
        assertEquals(
            "881448767",
            ServerInfo.fromXml(baseRoot(node("currentgame", text = "881448767")))!!.currentGameId,
        )
    }

    @Test
    fun `busy is derived from either currentgame or the state suffix`() {
        assertTrue(
            ServerInfo.fromXml(baseRoot(node("currentgame", text = "42")))!!.isBusy,
        )
        assertTrue(
            ServerInfo.fromXml(baseRoot(node("state", text = "SUNSHINE_SERVER_BUSY")))!!.isBusy,
        )
        assertFalse(
            ServerInfo.fromXml(baseRoot(node("state", text = "SUNSHINE_SERVER_FREE")))!!.isBusy,
        )
    }

    @Test
    fun `server kind is discriminated by the state marker`() {
        assertEquals(
            ServerKind.NVIDIA_GFE,
            ServerInfo.fromXml(baseRoot(node("state", text = "MJOLNIR_SERVER_FREE")))!!.serverKind,
        )
        assertEquals(
            ServerKind.NVIDIA_GFE,
            ServerInfo.fromXml(baseRoot(node("state", text = "MJOLNIR_SERVER_BUSY")))!!.serverKind,
        )
        assertEquals(
            ServerKind.SUNSHINE_FAMILY,
            ServerInfo.fromXml(baseRoot(node("state", text = "SUNSHINE_SERVER_FREE")))!!.serverKind,
        )
        assertEquals(ServerKind.UNKNOWN, ServerInfo.fromXml(baseRoot())!!.serverKind)
        assertEquals(
            ServerKind.UNKNOWN,
            ServerInfo.fromXml(baseRoot(node("state", text = "SOMETHING_ELSE")))!!.serverKind,
        )
    }

    @Test
    fun `HEVC support comes from the luma pixel count, which is the reliable signal`() {
        assertFalse(ServerInfo.fromXml(baseRoot())!!.supportsHevc)
        assertFalse(
            ServerInfo.fromXml(baseRoot(node("MaxLumaPixelsHEVC", text = "0")))!!.supportsHevc,
        )
        assertTrue(
            ServerInfo.fromXml(
                baseRoot(node("MaxLumaPixelsHEVC", text = "1869449984")),
            )!!.supportsHevc,
        )
    }

    @Test
    fun `the codec bitfield is exposed only as a hint`() {
        val info = ServerInfo.fromXml(
            baseRoot(node("ServerCodecModeSupport", text = "${0x0001 or 0x0100 or 0x0200}")),
        )!!

        assertTrue(info.codecHint(UnverifiedProtocolConstants.CODEC_FLAG_H264))
        assertTrue(info.codecHint(UnverifiedProtocolConstants.CODEC_FLAG_HEVC))
        assertTrue(info.codecHint(UnverifiedProtocolConstants.CODEC_FLAG_HEVC_MAIN10))
        assertFalse(info.codecHint(UnverifiedProtocolConstants.CODEC_FLAG_AV1_MAIN8))

        // Absent field: every hint is false and nothing throws.
        assertFalse(ServerInfo.fromXml(baseRoot())!!.codecHint(UnverifiedProtocolConstants.CODEC_FLAG_H264))
    }

    @Test
    fun `display modes are parsed, and malformed ones dropped`() {
        val modes = ServerInfo.fromXml(
            baseRoot(
                XmlNode(
                    name = "SupportedDisplayMode",
                    children = listOf(
                        displayMode("1920", "1080", "60"),
                        displayMode("3840", "2160", "120"),
                        displayMode("0", "1080", "60"),
                        displayMode("1280", "720", "not a rate"),
                        XmlNode("DisplayMode"),
                    ),
                ),
            ),
        )!!.displayModes

        assertEquals(2, modes.size)
        assertEquals("1920x1080x60", modes[0].toString())
        assertEquals("3840x2160x120", modes[1].toString())
        // Suggestions only, never a restriction (spec §3.3).
        assertFalse(UnverifiedProtocolConstants.DISPLAY_MODES_ARE_AUTHORITATIVE)
    }

    @Test
    fun `an absent display mode container yields an empty list`() {
        assertTrue(ServerInfo.fromXml(baseRoot())!!.displayModes.isEmpty())
    }

    // ---- AppListEntry (spec §3.4) ------------------------------------------------------------

    @Test
    fun `applist entries parse and sort Desktop first then alphabetically`() {
        val apps = AppListEntry.listFromXml(
            root(
                app("881448767", "Steam Big Picture", hdr = "0"),
                app("1", "Desktop", hdr = "1"),
                app("2", "hades II", hdr = "1"),
                app("3", "Factorio", hdr = "0"),
            ),
        )

        assertEquals(listOf("Desktop", "Factorio", "hades II", "Steam Big Picture"), apps.map { it.title })
        assertTrue(apps[0].isDesktop)
        assertTrue(apps[0].hdrSupported)
        assertFalse(apps[1].isDesktop)
    }

    @Test
    fun `applist entries missing an id or a title are discarded`() {
        val apps = AppListEntry.listFromXml(
            root(
                app("1", "Good"),
                XmlNode("App", children = listOf(node("AppTitle", text = "No ID"))),
                XmlNode("App", children = listOf(node("ID", text = "2"))),
                XmlNode("App"),
            ),
        )

        assertEquals(1, apps.size)
        assertEquals("Good", apps[0].title)
    }

    @Test
    fun `an app id beyond Int MAX is preserved`() {
        // GFE ids are unsigned 32-bit values in a string (spec §3.4).
        val apps = AppListEntry.listFromXml(root(app("4294967295", "Big Id")))
        assertEquals(4294967295L, apps[0].id)
    }

    @Test
    fun `no Desktop entry is synthesised when the host offers none`() {
        // On Sunshine a Desktop entry may genuinely not exist, and inventing one would launch
        // nothing (spec §3.4).
        val apps = AppListEntry.listFromXml(root(app("1", "Factorio")))
        assertEquals(1, apps.size)
        assertFalse(apps[0].isDesktop)
    }

    @Test
    fun `an empty applist is an empty list, not an error`() {
        assertTrue(AppListEntry.listFromXml(root()).isEmpty())
    }

    // ---- Fixtures ----------------------------------------------------------------------------

    private fun node(name: String, text: String = "") = XmlNode(name = name, text = text)

    private fun root(vararg children: XmlNode) = XmlNode(
        name = "root",
        attributes = mapOf("status_code" to "200"),
        children = children.toList(),
    )

    /** A `/serverinfo` root carrying only the two required elements, plus [extra]. */
    private fun baseRoot(vararg extra: XmlNode) = root(
        node("appversion", "7.1.431.0"),
        node("uniqueid", "host-uuid-1"),
        *extra,
    )

    private fun sunshineRoot() = root(
        node("hostname", "BATTLESTATION"),
        node("appversion", "7.1.431.0"),
        node("GfeVersion", "3.23.0.74"),
        node("uniqueid", "host-uuid-1"),
        node("HttpsPort", "47984"),
        node("ExternalPort", "47989"),
        node("mac", "00:00:00:00:00:00"),
        node("LocalIP", "192.168.1.24"),
        node("MaxLumaPixelsHEVC", "1869449984"),
        node("MaxLumaPixelsH264", "2073600"),
        node("ServerCodecModeSupport", "259"),
        node("PairStatus", "1"),
        node("currentgame", "0"),
        node("state", "SUNSHINE_SERVER_FREE"),
        node("gputype", "NVIDIA GeForce RTX 4070"),
    )

    private fun displayMode(width: String, height: String, refresh: String) = XmlNode(
        name = "DisplayMode",
        children = listOf(
            node("Width", width),
            node("Height", height),
            node("RefreshRate", refresh),
        ),
    )

    private fun app(id: String, title: String, hdr: String = "0") = XmlNode(
        name = "App",
        children = listOf(
            node("IsHdrSupported", hdr),
            node("AppTitle", title),
            node("ID", id),
        ),
    )
}
