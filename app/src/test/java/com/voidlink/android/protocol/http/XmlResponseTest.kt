package com.voidlink.android.protocol.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the NVHTTP envelope rules of `docs/01-PROTOCOL.md` §3.2.
 *
 * These operate on hand-built [XmlNode] trees rather than on XML text. Tokenising needs
 * `android.util.Xml`, which is stubbed out in JVM unit tests — so the production code is split so
 * that everything deciding what a response *means* works on the tree, and that is the part with
 * the rules worth pinning down. The tokeniser itself is exercised on device.
 */
class XmlResponseTest {

    @Test
    fun `a well-formed 200 response is accepted`() {
        val root = node("root", attributes = mapOf("status_code" to "200"))

        val result = NvXml.checkEnvelope(root, "/serverinfo")

        assertTrue(result is XmlResponse.Ok)
        assertEquals(root, (result as XmlResponse.Ok).root)
    }

    @Test
    fun `a non-200 status is a host error carrying the message`() {
        val root = node(
            "root",
            attributes = mapOf("status_code" to "401", "status_message" to "Unauthorized"),
        )

        val result = NvXml.checkEnvelope(root, "/applist")

        assertTrue(result is XmlResponse.HostError)
        val error = result as XmlResponse.HostError
        assertEquals(401, error.statusCode)
        assertEquals("Unauthorized", error.statusMessage)
    }

    @Test
    fun `an absent status code is treated as an error, not as success`() {
        // Spec §3.2: anything other than 200 is an error, and "absent" is other than 200.
        val result = NvXml.checkEnvelope(node("root"), "/serverinfo")

        assertTrue(result is XmlResponse.HostError)
        assertEquals(-1, (result as XmlResponse.HostError).statusCode)
        assertNull(result.statusMessage)
    }

    @Test
    fun `an unparseable status code is treated as an error`() {
        val root = node("root", attributes = mapOf("status_code" to "two hundred"))

        val result = NvXml.checkEnvelope(root, "/serverinfo")

        assertEquals(-1, (result as XmlResponse.HostError).statusCode)
    }

    @Test
    fun `a blank status message collapses to null`() {
        val root = node(
            "root",
            attributes = mapOf("status_code" to "500", "status_message" to "   "),
        )

        assertNull((NvXml.checkEnvelope(root, "/launch") as XmlResponse.HostError).statusMessage)
    }

    @Test
    fun `a document whose root is not root is malformed`() {
        val result = NvXml.checkEnvelope(
            node("html", attributes = mapOf("status_code" to "200")),
            "/serverinfo",
        )

        assertTrue(result is XmlResponse.Malformed)
        assertTrue((result as XmlResponse.Malformed).reason.contains("html"))
    }

    @Test
    fun `child lookup finds elements by name`() {
        val root = node(
            "root",
            children = listOf(
                node("hostname", text = "BATTLESTATION"),
                node("appversion", text = "7.1.431.0"),
                node("App", text = ""),
                node("App", text = ""),
            ),
        )

        assertEquals("BATTLESTATION", root.textOf("hostname"))
        assertEquals("7.1.431.0", root.textOf("appversion"))
        assertEquals(2, root.childrenNamed("App").size)
        assertNull(root.child("missing"))
        assertTrue(root.childrenNamed("missing").isEmpty())
    }

    @Test
    fun `an empty or whitespace-only element reads as null`() {
        // Hosts emit <mac></mac> to mean "I have no MAC", and every call site wants that to behave
        // exactly like the element being absent.
        val root = node(
            "root",
            children = listOf(node("mac", text = ""), node("gputype", text = "   ")),
        )

        assertNull(root.textOf("mac"))
        assertNull(root.textOf("gputype"))
        assertNull(root.textOf("absent"))
    }

    @Test
    fun `numeric accessors parse or return null`() {
        val root = node(
            "root",
            children = listOf(
                node("HttpsPort", text = "47984"),
                node("MaxLumaPixelsHEVC", text = "1869449984"),
                node("ID", text = "4294967295"),
                node("bad", text = "not a number"),
            ),
        )

        assertEquals(47984, root.intOf("HttpsPort"))
        assertEquals(1869449984L, root.longOf("MaxLumaPixelsHEVC"))
        // Unsigned 32-bit app ids exceed Int.MAX_VALUE, which is why longOf exists (spec §3.4).
        assertEquals(4294967295L, root.longOf("ID"))
        assertNull(root.intOf("ID"))
        assertNull(root.intOf("bad"))
        assertNull(root.longOf("bad"))
        assertNull(root.intOf("absent"))
    }

    @Test
    fun `attribute lookup returns null for an absent attribute`() {
        val root = node("root", attributes = mapOf("status_code" to "200"))
        assertEquals("200", root.attribute("status_code"))
        assertNull(root.attribute("status_message"))
    }

    private fun node(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        text: String = "",
        children: List<XmlNode> = emptyList(),
    ) = XmlNode(name, attributes, text, children)
}
