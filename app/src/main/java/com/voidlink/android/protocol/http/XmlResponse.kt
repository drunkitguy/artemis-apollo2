package com.voidlink.android.protocol.http

import android.util.Xml
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

/**
 * A parsed XML element.
 *
 * NVHTTP responses are a handful of flat elements under `<root>`, so a tiny immutable tree beats
 * pulling a document model in. Splitting tokenising (which needs `android.util.Xml`) from
 * interpretation (which does not) is deliberate: everything that decides what a response *means*
 * operates on [XmlNode] and is therefore unit-testable on the JVM without an emulator.
 *
 * @property name the element's tag name.
 * @property attributes its attributes, in document order.
 * @property text its direct character content, trimmed.
 * @property children its child elements, in document order.
 */
class XmlNode(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val text: String = "",
    val children: List<XmlNode> = emptyList(),
) {
    /** The first child named [childName], or `null`. */
    fun child(childName: String): XmlNode? = children.firstOrNull { it.name == childName }

    /** Every child named [childName], in document order. */
    fun childrenNamed(childName: String): List<XmlNode> = children.filter { it.name == childName }

    /**
     * Text of the first child named [childName], or `null` when the element is missing or blank.
     *
     * Blank collapses to `null` on purpose: hosts emit `<mac></mac>` for "I have no MAC", and every
     * call site wants that to behave identically to the element being absent.
     */
    fun textOf(childName: String): String? = child(childName)?.text?.trim()?.takeIf { it.isNotEmpty() }

    /** [textOf] parsed as an `Int`, or `null` when missing or unparseable. */
    fun intOf(childName: String): Int? = textOf(childName)?.toIntOrNull()

    /**
     * [textOf] parsed as a `Long`.
     *
     * `Long` rather than `Int` because app ids and luma-pixel counts are unsigned 32-bit values in
     * a string and can exceed `Int.MAX_VALUE` (spec §3.4).
     */
    fun longOf(childName: String): Long? = textOf(childName)?.toLongOrNull()

    /** An attribute value, or `null`. */
    fun attribute(attributeName: String): String? = attributes[attributeName]

    override fun toString(): String = "XmlNode($name, ${children.size} children)"
}

/**
 * The outcome of parsing an NVHTTP response body (spec §3.2).
 *
 * The envelope is checked before any element is read, because a host that is refusing us still
 * returns a well-formed document — with a non-200 `status_code` and a `status_message` worth
 * showing the user.
 */
sealed interface XmlResponse {

    /** A well-formed document whose `<root status_code>` is 200. */
    class Ok(val root: XmlNode) : XmlResponse

    /**
     * A well-formed document the host used to report a failure.
     *
     * @property statusCode the `status_code` attribute, or `-1` when it was absent or unparseable.
     * @property statusMessage the host's own explanation, when it supplied one.
     */
    class HostError(val statusCode: Int, val statusMessage: String?, val root: XmlNode) : XmlResponse

    /**
     * The body was not a usable XML document.
     *
     * Spec §3.2 warns that a failing host may emit a truncated document with an unterminated root
     * element, so this covers both "not XML at all" and "XML that stopped mid-way".
     */
    class Malformed(val reason: String) : XmlResponse
}

/**
 * Tokenises and envelope-checks NVHTTP XML.
 */
object NvXml {

    /**
     * Parses a response body into a status-checked [XmlResponse].
     *
     * @param body the raw response text.
     * @param endpoint endpoint name, used only for log context.
     */
    fun parseResponse(body: String, endpoint: String): XmlResponse {
        val root = parseTree(body)
            ?: return XmlResponse.Malformed("$endpoint: body is not a complete XML document")
        return checkEnvelope(root, endpoint)
    }

    /**
     * Applies the `status_code` rules of spec §3.2 to an already-parsed tree.
     *
     * Separate from [parseResponse] so tests can exercise the envelope logic against hand-built
     * trees, with no XML tokeniser in the picture.
     */
    fun checkEnvelope(root: XmlNode, endpoint: String): XmlResponse {
        if (root.name != ProtocolConstants.ELEMENT_ROOT) {
            return XmlResponse.Malformed(
                "$endpoint: expected <${ProtocolConstants.ELEMENT_ROOT}>, got <${root.name}>",
            )
        }
        val rawStatus = root.attribute(ProtocolConstants.ATTR_STATUS_CODE)
        val statusCode = rawStatus?.trim()?.toIntOrNull() ?: -1
        if (statusCode != ProtocolConstants.STATUS_CODE_OK) {
            val message = root.attribute(ProtocolConstants.ATTR_STATUS_MESSAGE)?.trim()
            ProtocolLog.w(
                ProtocolLog.TAG_HTTP,
                "$endpoint returned status_code=${rawStatus ?: "<absent>"} ${message.orEmpty()}",
            )
            return XmlResponse.HostError(statusCode, message?.takeIf { it.isNotEmpty() }, root)
        }
        return XmlResponse.Ok(root)
    }

    /**
     * Builds an [XmlNode] tree from XML text.
     *
     * @return the root element, or `null` when the document is malformed **or truncated** — the
     *   tree is only returned once the root's closing tag has actually been seen, which is the
     *   verification spec §3.2 rule 3 demands.
     */
    fun parseTree(xml: String): XmlNode? = try {
        val parser: XmlPullParser? = Xml.newPullParser()
        if (parser == null) {
            null
        } else {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next()
            }
            if (event == XmlPullParser.START_TAG) readElement(parser) else null
        }
    } catch (e: XmlPullParserException) {
        ProtocolLog.w(ProtocolLog.TAG_HTTP, "XML parse failed: ${e.message}")
        null
    } catch (t: Throwable) {
        // IOException from the reader, or anything the platform parser decides to throw on
        // pathological input. Either way this is "the host sent us rubbish", not a crash.
        ProtocolLog.w(ProtocolLog.TAG_HTTP, "XML parse failed", t)
        null
    }

    /**
     * Reads one element and its subtree; the parser must be positioned on its `START_TAG` and is
     * left on the matching `END_TAG`.
     *
     * Hitting `END_DOCUMENT` before that `END_TAG` is exactly the truncated-response case, and it
     * throws so [parseTree] can report the whole document as unusable.
     */
    private fun readElement(parser: XmlPullParser): XmlNode {
        val name = parser.name
        val attributeCount = parser.attributeCount
        val attributes = LinkedHashMap<String, String>(if (attributeCount > 0) attributeCount else 1)
        for (index in 0 until attributeCount) {
            val attributeName = parser.getAttributeName(index)
            val attributeValue = parser.getAttributeValue(index)
            if (attributeName != null && attributeValue != null) {
                attributes[attributeName] = attributeValue
            }
        }
        val text = StringBuilder()
        val children = ArrayList<XmlNode>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> children.add(readElement(parser))
                XmlPullParser.TEXT -> parser.text?.let { text.append(it) }
                XmlPullParser.END_TAG -> return XmlNode(
                    name = name,
                    attributes = attributes,
                    text = text.toString().trim(),
                    children = children,
                )
                XmlPullParser.END_DOCUMENT ->
                    throw XmlPullParserException("document ended inside <$name>")
            }
        }
    }
}
