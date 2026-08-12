package com.voidlink.android.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the model logic and persisted shape of [KnownHost]. */
class KnownHostTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private fun host(
        uuid: String = "uuid-1",
        name: String = "BATTLESTATION",
        addresses: List<String> = listOf("192.168.1.24"),
        macAddress: String? = null,
        paired: Boolean = false,
        settingsOverride: StreamSettings? = null,
        lastSeenEpochMillis: Long = 0L,
    ) = KnownHost(
        uuid = uuid,
        name = name,
        addresses = addresses,
        macAddress = macAddress,
        paired = paired,
        settingsOverride = settingsOverride,
        lastSeenEpochMillis = lastSeenEpochMillis,
    )

    @Test
    fun `primary address is the first known address`() {
        assertEquals(
            "10.0.0.5",
            host(addresses = listOf("10.0.0.5", "192.168.1.24")).primaryAddress,
        )
    }

    @Test
    fun `primary address is null when no address is known`() {
        assertNull(host(addresses = emptyList()).primaryAddress)
    }

    @Test
    fun `wake on lan needs a mac address`() {
        assertFalse(host(macAddress = null).canWakeOnLan)
        assertFalse(host(macAddress = "   ").canWakeOnLan)
        assertTrue(host(macAddress = "aa:bb:cc:dd:ee:ff").canWakeOnLan)
    }

    @Test
    fun `effective settings fall back to the global ones`() {
        val global = StreamSettings(bitrateKbps = 30_000)

        assertSame(global, host(settingsOverride = null).effectiveSettings(global))
    }

    @Test
    fun `effective settings prefer a per-host override`() {
        val global = StreamSettings(bitrateKbps = 30_000)
        val override = StreamSettings(bitrateKbps = 5_000, resolution = StreamResolution.RES_720P)

        val effective = host(settingsOverride = override).effectiveSettings(global)

        assertEquals(override, effective)
        assertEquals(5_000, effective.bitrateKbps)
    }

    @Test
    fun `the first override is seeded from the global settings, not from the defaults`() {
        // The bug this guards against: changing one row for one PC quietly resetting every other
        // setting for that PC back to the factory value.
        val global = StreamSettings(bitrateKbps = 30_000, hdrEnabled = true)

        val updated = host().withOverride(global) { it.copy(bitrateKbps = 45_000) }

        assertEquals(45_000, updated.settingsOverride?.bitrateKbps)
        assertTrue(updated.settingsOverride?.hdrEnabled == true)
    }

    @Test
    fun `a later override builds on the override, not on the global settings`() {
        val global = StreamSettings(bitrateKbps = 30_000)
        val existing = StreamSettings(bitrateKbps = 45_000, yuv444Enabled = true)

        val updated = host(settingsOverride = existing).withOverride(global) {
            it.copy(hdrEnabled = true)
        }

        assertEquals(45_000, updated.settingsOverride?.bitrateKbps)
        assertTrue(updated.settingsOverride?.yuv444Enabled == true)
        assertTrue(updated.settingsOverride?.hdrEnabled == true)
    }

    @Test
    fun `an override is clamped on the way in`() {
        val updated = host().withOverride(StreamSettings()) { it.copy(bitrateKbps = 900_000) }

        assertEquals(StreamSettings.BITRATE_MAX_KBPS, updated.settingsOverride?.bitrateKbps)
    }

    @Test
    fun `writing an override leaves the rest of the host record alone`() {
        val original = host(macAddress = "aa:bb:cc:dd:ee:ff", paired = true)

        val updated = original.withOverride(StreamSettings()) { it.copy(hdrEnabled = true) }

        assertEquals(original.uuid, updated.uuid)
        assertEquals(original.name, updated.name)
        assertEquals(original.macAddress, updated.macAddress)
        assertTrue(updated.paired)
    }

    @Test
    fun `a manually added host with no twin is simply re-filed under its real identity`() {
        val manual = host(uuid = "local-1", name = "192.168.1.24").copy(manuallyAdded = true)

        val merged = manual.mergedOnto(existing = null, realUuid = "REAL-UUID")

        assertEquals("REAL-UUID", merged.uuid)
        assertEquals(manual.addresses, merged.addresses)
    }

    @Test
    fun `merging keeps a name the user typed but drops one that is just the address`() {
        val discovered = host(uuid = "REAL", name = "BATTLESTATION")
        val typedAddress = host(uuid = "local", name = "192.168.1.24", addresses = listOf("192.168.1.24"))
            .copy(manuallyAdded = true)
        val typedName = host(uuid = "local", name = "Den PC", addresses = listOf("192.168.1.24"))
            .copy(manuallyAdded = true)

        assertEquals("BATTLESTATION", typedAddress.mergedOnto(discovered, "REAL").name)
        assertEquals("Den PC", typedName.mergedOnto(discovered, "REAL").name)
    }

    @Test
    fun `merging unions the addresses so whichever one works survives`() {
        val discovered = host(uuid = "REAL", addresses = listOf("10.0.0.5"))
        val manual = host(uuid = "local", addresses = listOf("192.168.1.24", "10.0.0.5"))

        val merged = manual.mergedOnto(discovered, "REAL")

        assertEquals(listOf("10.0.0.5", "192.168.1.24"), merged.addresses)
    }

    @Test
    fun `merging never loses a pairing, a MAC or a settings override`() {
        val override = StreamSettings(bitrateKbps = 45_000)
        val discovered = host(uuid = "REAL", paired = false, macAddress = null)
        val manual = host(
            uuid = "local",
            paired = true,
            macAddress = "aa:bb:cc:dd:ee:ff",
            settingsOverride = override,
            lastSeenEpochMillis = 99L,
        )

        val merged = manual.mergedOnto(discovered, "REAL")

        assertTrue(merged.paired)
        assertEquals("aa:bb:cc:dd:ee:ff", merged.macAddress)
        assertEquals(override, merged.settingsOverride)
        assertEquals(99L, merged.lastSeenEpochMillis)
    }

    @Test
    fun `merging prefers the existing record's own MAC and override`() {
        val discovered = host(
            uuid = "REAL",
            macAddress = "11:22:33:44:55:66",
            settingsOverride = StreamSettings(bitrateKbps = 10_000),
        )
        val manual = host(
            uuid = "local",
            macAddress = "aa:bb:cc:dd:ee:ff",
            settingsOverride = StreamSettings(bitrateKbps = 45_000),
        )

        val merged = manual.mergedOnto(discovered, "REAL")

        assertEquals("11:22:33:44:55:66", merged.macAddress)
        assertEquals(10_000, merged.settingsOverride?.bitrateKbps)
    }

    @Test
    fun `a probe that learns a MAC records it`() {
        // The case that makes Wake-on-LAN reachable at all for a manually added host: Sunshine
        // hands the real MAC back only over HTTPS, once we are paired.
        val updated = host(macAddress = null).withLearnedMac("aa:bb:cc:dd:ee:ff")

        assertEquals("aa:bb:cc:dd:ee:ff", updated.macAddress)
        assertTrue(updated.canWakeOnLan)
    }

    @Test
    fun `a probe that learns no MAC never erases the one already stored`() {
        // Every plaintext probe returns no MAC. Treating that as a correction would delete a MAC
        // learned earlier and silently break waking for exactly the hosts that had it working.
        val known = host(macAddress = "aa:bb:cc:dd:ee:ff")

        assertEquals(known, known.withLearnedMac(null))
        assertEquals(known, known.withLearnedMac(""))
        assertEquals(known, known.withLearnedMac("   "))
    }

    @Test
    fun `a MAC read over HTTPS replaces one advertised by discovery`() {
        val discovered = host(macAddress = "00:00:00:00:00:00")

        assertEquals(
            "aa:bb:cc:dd:ee:ff",
            discovered.withLearnedMac("aa:bb:cc:dd:ee:ff").macAddress,
        )
    }

    @Test
    fun `learning a MAC changes nothing else about the host`() {
        val original = host(paired = true, lastSeenEpochMillis = 42L)

        val updated = original.withLearnedMac("aa:bb:cc:dd:ee:ff")

        assertEquals(original.copy(macAddress = "aa:bb:cc:dd:ee:ff"), updated)
    }

    @Test
    fun `a status carries no MAC until a probe supplies one`() {
        assertNull(HostStatus.Unknown.macAddress)
        assertNull(HostStatus.Offline.macAddress)
        assertEquals(
            "aa:bb:cc:dd:ee:ff",
            HostStatus(
                reachability = HostReachability.ONLINE,
                macAddress = "aa:bb:cc:dd:ee:ff",
            ).macAddress,
        )
    }

    @Test
    fun `preferring an address moves it to the front without duplicating it`() {
        val updated = host(addresses = listOf("192.168.1.24", "10.0.0.5"))
            .withPreferredAddress("10.0.0.5")

        assertEquals(listOf("10.0.0.5", "192.168.1.24"), updated.addresses)
    }

    @Test
    fun `preferring an unknown address prepends it`() {
        val updated = host(addresses = listOf("192.168.1.24")).withPreferredAddress("172.16.0.9")

        assertEquals(listOf("172.16.0.9", "192.168.1.24"), updated.addresses)
    }

    @Test
    fun `preferring an address matches case insensitively`() {
        val updated = host(addresses = listOf("Battlestation.local", "10.0.0.5"))
            .withPreferredAddress("battlestation.local")

        assertEquals(listOf("battlestation.local", "10.0.0.5"), updated.addresses)
    }

    @Test
    fun `preferring a blank address is a no-op`() {
        val original = host(addresses = listOf("192.168.1.24"))

        assertEquals(original, original.withPreferredAddress("  "))
    }

    @Test
    fun `marking a host seen records the time and promotes the answering address`() {
        val seen = host(addresses = listOf("192.168.1.24", "10.0.0.5"))
            .markSeen(nowEpochMillis = 1_700_000_000_000L, atAddress = "10.0.0.5")

        assertEquals(1_700_000_000_000L, seen.lastSeenEpochMillis)
        assertEquals("10.0.0.5", seen.primaryAddress)
    }

    @Test
    fun `marking a host seen without an address leaves the address order alone`() {
        val original = host(addresses = listOf("192.168.1.24", "10.0.0.5"))

        val seen = original.markSeen(nowEpochMillis = 42L)

        assertEquals(original.addresses, seen.addresses)
        assertEquals(42L, seen.lastSeenEpochMillis)
    }

    @Test
    fun `a host survives a JSON round trip including its settings override`() {
        val original = host(
            macAddress = "aa:bb:cc:dd:ee:ff",
            paired = true,
            addresses = listOf("192.168.1.24", "fe80::1"),
            settingsOverride = StreamSettings(bitrateKbps = 7_500, hdrEnabled = true),
            lastSeenEpochMillis = 1_700_000_000_000L,
        )

        val restored = json.decodeFromString(
            KnownHost.serializer(),
            json.encodeToString(KnownHost.serializer(), original),
        )

        assertEquals(original, restored)
    }

    @Test
    fun `a list of hosts survives a JSON round trip`() {
        val serializer = ListSerializer(KnownHost.serializer())
        val original = listOf(
            host(uuid = "a", name = "Alpha"),
            host(uuid = "b", name = "Beta", paired = true),
        )

        val restored = json.decodeFromString(serializer, json.encodeToString(serializer, original))

        assertEquals(original, restored)
    }

    @Test
    fun `a stored host with only the required fields decodes with sane defaults`() {
        val minimal = """{"uuid":"abc","name":"Office PC"}"""

        val restored = json.decodeFromString(KnownHost.serializer(), minimal)

        assertEquals("abc", restored.uuid)
        assertEquals("Office PC", restored.name)
        assertTrue(restored.addresses.isEmpty())
        assertNull(restored.macAddress)
        assertNull(restored.settingsOverride)
        assertFalse(restored.paired)
        assertFalse(restored.manuallyAdded)
        assertEquals(0L, restored.lastSeenEpochMillis)
    }

    @Test
    fun `status helpers describe reachability`() {
        assertFalse(HostStatus.Unknown.isOnline)
        assertFalse(HostStatus.Offline.isOnline)
        assertTrue(HostStatus(reachability = HostReachability.ONLINE).isOnline)
        assertEquals(HostReachability.OFFLINE, HostStatus.Offline.reachability)
    }
}
