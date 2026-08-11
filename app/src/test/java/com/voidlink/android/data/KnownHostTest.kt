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
