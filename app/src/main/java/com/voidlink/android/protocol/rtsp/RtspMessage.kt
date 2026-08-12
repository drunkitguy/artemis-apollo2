package com.voidlink.android.protocol.rtsp

/**
 * One RTSP request, in the exact shape spec §6.2 describes.
 *
 * Header order is preserved because it is part of what the golden tests pin: a request is compared
 * byte for byte, and "the same headers in a different order" is a change worth noticing.
 *
 * @property method one of [RtspConstants.METHOD_OPTIONS] … [RtspConstants.METHOD_PLAY].
 * @property target the request target — `rtsp://host:port` or `rtsp://host:port/streamid=…`.
 * @property headers headers in the order they are written, `CSeq` first.
 * @property body the payload, currently only ever an SDP document, or `null`.
 */
class RtspRequest(
    val method: String,
    val target: String,
    val headers: List<Pair<String, String>>,
    val body: String? = null,
) {

    /** The `CSeq` this request carries, or `null` if it somehow has none. */
    val cseq: Int? get() = headers.firstOrNull { it.first.equals(RtspConstants.HEADER_CSEQ, true) }
        ?.second?.trim()?.toIntOrNull()

    /** The wire form (spec §6.2): request line, headers, blank line, optional payload. */
    fun encodeToString(): String {
        val builder = StringBuilder(256)
        builder.append(method).append(' ').append(target).append(' ')
            .append(RtspConstants.PROTOCOL_VERSION).append(RtspConstants.CRLF)
        for ((name, value) in headers) {
            builder.append(name).append(": ").append(value).append(RtspConstants.CRLF)
        }
        builder.append(RtspConstants.CRLF)
        if (body != null) builder.append(body)
        return builder.toString()
    }

    /** [encodeToString] as bytes, which is what actually goes on the socket. */
    fun encode(): ByteArray = encodeToString().toByteArray(Charsets.UTF_8)

    override fun toString(): String = "$method $target (CSeq ${cseq ?: "?"})"
}

/**
 * One parsed RTSP response (spec §6.2).
 *
 * @property statusCode the numeric status; [RtspConstants.STATUS_OK] is the only success.
 * @property reasonPhrase the text after the status code, possibly empty.
 * @property headers headers in arrival order, values untrimmed of nothing but surrounding spaces.
 * @property body the payload, empty when the response carried none.
 */
class RtspResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    val headers: List<Pair<String, String>>,
    val body: String,
) {

    /** True when the host answered `200`. Every step of the handshake requires this. */
    val isOk: Boolean get() = statusCode == RtspConstants.STATUS_OK

    /**
     * First value of [name], matched case-insensitively.
     *
     * Case-insensitive because hosts genuinely disagree: the spec itself writes `Content-length`
     * where HTTP writes `Content-Length`, and both appear in the wild.
     */
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** The `CSeq` echoed by the host, or `null` — which spec §6.2 says we must tolerate. */
    val cseq: Int? get() = header(RtspConstants.HEADER_CSEQ)?.trim()?.toIntOrNull()

    override fun toString(): String = "RTSP $statusCode $reasonPhrase (${headers.size} headers)"
}

/**
 * Framing and parsing for RTSP messages (spec §6.2).
 *
 * Deliberately forgiving, because the spec warns that some GFE builds emit a malformed status line
 * or a mis-ordered `CSeq`:
 *
 * * the header/body split accepts `\r\n\r\n` **and** a bare `\n\n`;
 * * the status code is the first three-digit token on the status line, wherever it sits;
 * * an absent `CSeq` is not an error;
 * * an unknown header is kept verbatim and otherwise ignored.
 *
 * What it refuses to guess at is the *body length*: spec §6.2 states that `Content-length` gives
 * it, so an absent header means a zero-length body rather than "read until something looks right".
 * Guessing there would silently glue two responses together.
 */
object RtspMessageCodec {

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A

    /** [completeMessageLength] returns this when the buffer does not yet hold a whole message. */
    const val INCOMPLETE: Int = -1

    /**
     * Length in bytes of the complete message at the front of [buffer], or [INCOMPLETE].
     *
     * @param buffer accumulated bytes; only `[0, length)` is examined.
     * @param length number of valid bytes in [buffer].
     */
    fun completeMessageLength(buffer: ByteArray, length: Int): Int {
        val separator = findHeaderSeparator(buffer, length) ?: return INCOMPLETE
        val headerEnd = separator.first + separator.second
        val headerText = String(buffer, 0, separator.first, Charsets.ISO_8859_1)
        val contentLength = contentLengthOf(headerText)
        val total = headerEnd + contentLength
        return if (length >= total) total else INCOMPLETE
    }

    /**
     * Parses exactly one complete message occupying `[0, length)` of [buffer].
     *
     * @return the response, or `null` when no status code could be recovered from the first line —
     *   the one shape of brokenness that is not survivable, because it is the field the whole
     *   handshake branches on.
     */
    fun parseResponse(buffer: ByteArray, length: Int): RtspResponse? {
        val separator = findHeaderSeparator(buffer, length) ?: return null
        val headerEnd = separator.first + separator.second
        val headerText = String(buffer, 0, separator.first, Charsets.ISO_8859_1)
        val lines = headerText.split('\n').map { it.trimEnd('\r') }
        if (lines.isEmpty()) return null

        val status = parseStatusLine(lines[0]) ?: return null

        val headers = ArrayList<Pair<String, String>>(lines.size)
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
        }

        // Honour Content-length when the host declared one, so that a buffer holding more than one
        // message still yields exactly the first. Without a declaration, take what is there.
        val available = (length - headerEnd).coerceAtLeast(0)
        val declared = contentLengthOf(headerText)
        val bodyLength = if (declared > 0) minOf(declared, available) else available
        val body = if (bodyLength == 0) "" else String(buffer, headerEnd, bodyLength, Charsets.UTF_8)
        return RtspResponse(status.first, status.second, headers, body)
    }

    /** Convenience for tests and logs: parse a whole message held as text. */
    fun parseResponse(text: String): RtspResponse? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return parseResponse(bytes, bytes.size)
    }

    /**
     * Splits a status line into code and reason phrase.
     *
     * The first token that is a bare three-digit number wins, so `RTSP/1.0 200 OK`,
     * `RTSP/1.0  200  OK` and a build that drops or mangles the protocol token all parse the same.
     *
     * @return code and reason phrase, or `null` when the line holds no three-digit token.
     */
    fun parseStatusLine(line: String): Pair<Int, String>? {
        val tokens = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token.length != 3) continue
            val code = token.toIntOrNull() ?: continue
            val reason = tokens.drop(index + 1).joinToString(" ")
            return code to reason
        }
        return null
    }

    /**
     * Reads `Content-length` out of an already-decoded header block.
     *
     * @return the declared body length, or `0` when the header is absent or unparseable.
     */
    fun contentLengthOf(headerText: String): Int {
        for (rawLine in headerText.split('\n')) {
            val line = rawLine.trimEnd('\r')
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (!line.substring(0, colon).trim()
                    .equals(RtspConstants.HEADER_CONTENT_LENGTH, ignoreCase = true)
            ) {
                continue
            }
            val declared = line.substring(colon + 1).trim().toIntOrNull() ?: return 0
            return if (declared > 0) declared else 0
        }
        return 0
    }

    /**
     * Locates the header/body separator.
     *
     * @return its start index and its length (4 for `\r\n\r\n`, 2 for a bare `\n\n`), or `null`.
     */
    private fun findHeaderSeparator(buffer: ByteArray, length: Int): Pair<Int, Int>? {
        var index = 0
        while (index + 1 < length) {
            if (buffer[index] == LF && buffer[index + 1] == LF) return index to 2
            if (index + 3 < length &&
                buffer[index] == CR &&
                buffer[index + 1] == LF &&
                buffer[index + 2] == CR &&
                buffer[index + 3] == LF
            ) {
                return index to 4
            }
            index++
        }
        return null
    }
}
