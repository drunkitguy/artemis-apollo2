package com.voidlink.android.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the address forms spec §1.2 requires manual entry to accept, and the round trip through
 * the plain-string form the persisted host model stores.
 */
class HostAddressTest {

    @Test
    fun `a bare hostname takes the default NVHTTP port`() {
        val address = HostAddress.parse("battlestation.local")!!
        assertEquals("battlestation.local", address.host)
        assertEquals(ProtocolConstants.DEFAULT_HTTP_PORT, address.port)
        assertFalse(address.isIpv6Literal)
    }

    @Test
    fun `an IPv4 literal parses`() {
        val address = HostAddress.parse("192.168.1.24")!!
        assertEquals("192.168.1.24", address.host)
        assertEquals(47989, address.port)
    }

    @Test
    fun `an explicit port is honoured`() {
        val address = HostAddress.parse("192.168.1.24:47999")!!
        assertEquals("192.168.1.24", address.host)
        assertEquals(47999, address.port)
    }

    @Test
    fun `a bracketed IPv6 literal parses, with and without a port`() {
        val bare = HostAddress.parse("[fe80::1]")!!
        assertEquals("fe80::1", bare.host)
        assertEquals(47989, bare.port)
        assertTrue(bare.isIpv6Literal)

        val withPort = HostAddress.parse("[fe80::1]:47999")!!
        assertEquals("fe80::1", withPort.host)
        assertEquals(47999, withPort.port)
    }

    @Test
    fun `an unbracketed IPv6 literal is treated as a host with no port`() {
        // Multiple colons and no brackets can only be an address, never a host:port pair.
        val address = HostAddress.parse("2001:db8::42")!!
        assertEquals("2001:db8::42", address.host)
        assertEquals(47989, address.port)
        assertTrue(address.isIpv6Literal)
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("192.168.1.24", HostAddress.parse("  192.168.1.24  ")!!.host)
        assertEquals(47999, HostAddress.parse(" 192.168.1.24 : 47999 ")!!.port)
    }

    @Test
    fun `a custom default port applies only when none is given`() {
        assertEquals(47984, HostAddress.parse("host", defaultPort = 47984)!!.port)
        assertEquals(1234, HostAddress.parse("host:1234", defaultPort = 47984)!!.port)
    }

    @Test
    fun `unusable input yields null rather than a silent fallback`() {
        // A user who typed a broken port wants to be told, not connected somewhere else.
        assertNull(HostAddress.parse(null))
        assertNull(HostAddress.parse(""))
        assertNull(HostAddress.parse("   "))
        assertNull(HostAddress.parse("host:notaport"))
        assertNull(HostAddress.parse("host:0"))
        assertNull(HostAddress.parse("host:70000"))
        assertNull(HostAddress.parse("host:-1"))
        assertNull(HostAddress.parse(":47989"))
        assertNull(HostAddress.parse("[]"))
        assertNull(HostAddress.parse("[fe80::1]junk"))
    }

    @Test
    fun `authority brackets an IPv6 literal and applies a port override`() {
        assertEquals("192.168.1.24:47989", HostAddress.parse("192.168.1.24")!!.authority())
        assertEquals("192.168.1.24:47984", HostAddress.parse("192.168.1.24")!!.authority(47984))
        assertEquals("[fe80::1]:47984", HostAddress.parse("[fe80::1]")!!.authority(47984))
    }

    @Test
    fun `canonical omits the port when it is the default`() {
        assertEquals("192.168.1.24", HostAddress.parse("192.168.1.24")!!.canonical())
        assertEquals("192.168.1.24:47999", HostAddress.parse("192.168.1.24:47999")!!.canonical())
        assertEquals("[fe80::1]", HostAddress.parse("[fe80::1]")!!.canonical())
        assertEquals("[fe80::1]:47999", HostAddress.parse("[fe80::1]:47999")!!.canonical())
    }

    @Test
    fun `canonical output parses back to the same address`() {
        listOf(
            "192.168.1.24",
            "192.168.1.24:47999",
            "[fe80::1]",
            "[2001:db8::42]:47999",
            "battlestation.local",
        ).forEach { input ->
            val parsed = HostAddress.parse(input)!!
            val reparsed = HostAddress.parse(parsed.canonical())!!
            assertEquals("round trip failed for $input", parsed, reparsed)
        }
    }
}
