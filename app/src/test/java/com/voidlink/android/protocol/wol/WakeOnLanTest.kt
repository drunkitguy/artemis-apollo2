package com.voidlink.android.protocol.wol

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.UnverifiedProtocolConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the magic-packet layout and MAC parsing of `docs/01-PROTOCOL.md` §1.4.
 *
 * The packet layout is the only part of Wake-on-LAN that can be verified without a sleeping PC on
 * the other end, which is exactly why it is built by a pure function.
 */
class WakeOnLanTest {

    private val mac = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
    )

    @Test
    fun `a magic packet is 102 bytes`() {
        assertEquals(102, ProtocolConstants.WOL_PACKET_BYTES)
        assertEquals(102, WakeOnLan.magicPacket(mac).size)
    }

    @Test
    fun `a magic packet is six 0xFF bytes then the MAC sixteen times`() {
        val packet = WakeOnLan.magicPacket(mac)

        for (index in 0 until 6) {
            assertEquals("byte $index", 0xFF.toByte(), packet[index])
        }
        for (repeat in 0 until 16) {
            val offset = 6 + repeat * 6
            assertArrayEquals(
                "repeat $repeat",
                mac,
                packet.copyOfRange(offset, offset + 6),
            )
        }
    }

    @Test
    fun `the whole packet matches its hex representation exactly`() {
        val expected = "ffffffffffff" + "aabbccddeeff".repeat(16)
        assertEquals(expected, Hex.encode(WakeOnLan.magicPacket(mac)))
    }

    @Test
    fun `a MAC of the wrong length is rejected`() {
        assertTrue(runCatching { WakeOnLan.magicPacket(ByteArray(5)) }.isFailure)
        assertTrue(runCatching { WakeOnLan.magicPacket(ByteArray(7)) }.isFailure)
        assertTrue(runCatching { WakeOnLan.magicPacket(ByteArray(0)) }.isFailure)
    }

    @Test
    fun `MAC parsing accepts colon, dash, dot and bare forms`() {
        assertArrayEquals(mac, WakeOnLan.parseMac("aa:bb:cc:dd:ee:ff"))
        assertArrayEquals(mac, WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF"))
        assertArrayEquals(mac, WakeOnLan.parseMac("aa-bb-cc-dd-ee-ff"))
        assertArrayEquals(mac, WakeOnLan.parseMac("aabb.ccdd.eeff"))
        assertArrayEquals(mac, WakeOnLan.parseMac("aabbccddeeff"))
        assertArrayEquals(mac, WakeOnLan.parseMac("  aa:bb:cc:dd:ee:ff  "))
    }

    @Test
    fun `the all-zero placeholder is treated as no MAC at all`() {
        // Sunshine returns this over plaintext HTTP to mean "not telling you" (spec §1.4); waking
        // the broadcast address would be pointless and confusing.
        assertNull(WakeOnLan.parseMac("00:00:00:00:00:00"))
        assertNull(WakeOnLan.parseMac("000000000000"))
    }

    @Test
    fun `malformed MACs are rejected`() {
        assertNull(WakeOnLan.parseMac(null))
        assertNull(WakeOnLan.parseMac(""))
        assertNull(WakeOnLan.parseMac("aa:bb:cc:dd:ee"))
        assertNull(WakeOnLan.parseMac("aa:bb:cc:dd:ee:ff:00"))
        assertNull(WakeOnLan.parseMac("zz:bb:cc:dd:ee:ff"))
        assertNull(WakeOnLan.parseMac("not a mac at all"))
    }

    @Test
    fun `packets are aimed at both candidate ports`() {
        // UNVERIFIED (spec §1.4): whether hosts listen anywhere but 9 and 7. Sending to both is
        // the common practice, and this pins the decision so a change is deliberate.
        assertEquals(listOf(9, 7), UnverifiedProtocolConstants.WOL_PORTS)
    }
}
