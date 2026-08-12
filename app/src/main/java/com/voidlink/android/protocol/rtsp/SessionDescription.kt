package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.ProtocolLog

/**
 * One `a=` line of an SDP document.
 *
 * @property name the attribute name — the text between `a=` and the first `:`.
 * @property value everything after that first `:`, or `null` for a bare flag attribute.
 */
class SdpAttribute(val name: String, val value: String?) {
    override fun toString(): String = if (value == null) "a=$name" else "a=$name:$value"
}

/**
 * A parsed SDP document — in practice the host's answer to DESCRIBE (spec §6.3).
 *
 * **Unknown attributes are data, not errors.** Spec §6.3 says outright that the complete set
 * Sunshine emits is unverified and instructs us to log the whole body on first connect so we can
 * learn from it. So every line is retained in [lines] for logging, every `a=` line is retained in
 * [attributes] whether or not we recognise it, and a line that is not `key=value` at all is kept in
 * [lines] and otherwise ignored. Nothing in a DESCRIBE body can fail this parser.
 *
 * @property lines every non-blank line, in order, exactly as received.
 * @property attributes every `a=` line, in order.
 */
class SessionDescription(
    val lines: List<String>,
    val attributes: List<SdpAttribute>,
) {

    /** First value of the attribute called [name], or `null`. */
    fun attribute(name: String): String? =
        attributes.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /** Every value of the attribute called [name], in order. */
    fun attributeValues(name: String): List<String> =
        attributes.filter { it.name.equals(name, ignoreCase = true) }.mapNotNull { it.value }

    /**
     * `sprop-parameter-sets` for H.264, when the host supplied it (spec §6.3).
     *
     * Exposed only so it can be logged. We never use it: SPS/PPS arrive in-band on the video
     * stream, and feeding `MediaCodec` a second copy out of band is a way to desynchronise it.
     */
    val spropParameterSets: String?
        get() {
            for (attribute in attributes) {
                val value = attribute.value ?: continue
                val index = value.indexOf(RtspConstants.SPROP_PARAMETER_SETS)
                if (index < 0) continue
                val rest = value.substring(index + RtspConstants.SPROP_PARAMETER_SETS.length)
                return rest.removePrefix("=").substringBefore(';').trim().takeIf { it.isNotEmpty() }
            }
            return null
        }

    override fun toString(): String = "SessionDescription(${lines.size} lines, ${attributes.size} attributes)"

    companion object {

        /**
         * Parses an SDP body. Never fails: an empty or nonsensical body yields an empty document,
         * and it is the *caller* that decides whether the thing it needed was missing.
         */
        fun parse(text: String): SessionDescription {
            val lines = ArrayList<String>()
            val attributes = ArrayList<SdpAttribute>()
            for (rawLine in text.split('\n')) {
                val line = rawLine.trimEnd('\r').trim()
                if (line.isEmpty()) continue
                lines.add(line)
                if (!line.startsWith("a=")) continue
                val rest = line.substring(2)
                val colon = rest.indexOf(':')
                if (colon < 0) {
                    attributes.add(SdpAttribute(rest.trim(), null))
                } else {
                    attributes.add(SdpAttribute(rest.substring(0, colon).trim(), rest.substring(colon + 1)))
                }
            }
            return SessionDescription(lines, attributes)
        }
    }
}

/**
 * The Opus multistream configuration the audio decoder needs (spec §8.3).
 *
 * @property channelCount 2, 6 or 8.
 * @property streams number of Opus streams.
 * @property coupledStreams number of those streams that are coupled (stereo) pairs.
 * @property mapping channel mapping table, [channelCount] entries long, already corrected to the
 *   `FL FR C LFE RL RR SL SR` order decoders expect.
 * @property sampleRateHz always 48000; carried so the audio layer has no magic number of its own.
 */
class OpusMultistreamConfig(
    val channelCount: Int,
    val streams: Int,
    val coupledStreams: Int,
    val mapping: IntArray,
    val sampleRateHz: Int = RtspConstants.OPUS_SAMPLE_RATE_HZ,
) {
    override fun toString(): String =
        "OpusMultistreamConfig(channels=$channelCount, streams=$streams, " +
            "coupled=$coupledStreams, mapping=${mapping.joinToString(",")})"

    companion object {

        /** Stereo needs no negotiation at all (spec §8.3). */
        fun stereo(): OpusMultistreamConfig = OpusMultistreamConfig(
            channelCount = 2,
            streams = RtspConstants.STEREO_STREAMS,
            coupledStreams = RtspConstants.STEREO_COUPLED_STREAMS,
            mapping = intArrayOf(0, 1),
        )

        /**
         * Reads the surround configuration for [channelCount] out of the host's DESCRIBE SDP
         * (spec §8.3).
         *
         * The value is a run of single ASCII digits with no separators, immediately after the
         * literal `surround-params=<channelCount>`: `<streams><coupledStreams><mapping…>`. A body
         * may carry several such runs — one per channel count the host can do — so the one whose
         * leading digit matches what we asked for is the one that applies.
         *
         * The parsed mapping is then reordered: the host's normal-quality mapping runs
         * `FL FR C RL RR SL SR LFE`, while decoders and `AudioTrack` expect
         * `FL FR C LFE RL RR SL SR`. Skipping that step produces audio that plays perfectly and
         * comes out of the wrong speakers.
         *
         * The high-quality surround variant uses a *different* SDP key and must **not** be
         * reordered — which is precisely why
         * [UnverifiedRtspConstants.REQUEST_HIGH_QUALITY_SURROUND] is `false` and this function only
         * ever sees the normal-quality form.
         *
         * @return the configuration, or `null` when the body carries no usable run for
         *   [channelCount] — a host that cannot do the layout we asked for, which the caller
         *   handles by falling back to stereo rather than by failing the session.
         */
        fun parseSurround(sdp: SessionDescription, channelCount: Int): OpusMultistreamConfig? {
            if (channelCount <= 2) return stereo()
            val expectedDigits = 1 + 2 + channelCount
            for (line in sdp.lines) {
                var searchFrom = 0
                while (true) {
                    val index = line.indexOf(RtspConstants.SURROUND_PARAMS_PREFIX, searchFrom)
                    if (index < 0) break
                    searchFrom = index + RtspConstants.SURROUND_PARAMS_PREFIX.length
                    val digits = digitsAt(line, searchFrom, expectedDigits) ?: continue
                    if (digits[0] != channelCount) continue
                    val mapping = IntArray(channelCount) { digits[3 + it] }
                    return OpusMultistreamConfig(
                        channelCount = channelCount,
                        streams = digits[1],
                        coupledStreams = digits[2],
                        mapping = reorderForDecoder(mapping, channelCount),
                    )
                }
            }
            return null
        }

        /**
         * Applies the channel-order fix-up of spec §8.3: LFE moves from the end to index 3 and
         * everything it displaced slides up one place.
         */
        fun reorderForDecoder(mapping: IntArray, channelCount: Int): IntArray {
            if (channelCount <= 2 || mapping.size < channelCount) return mapping
            val original = mapping.copyOf()
            val fixed = mapping.copyOf()
            fixed[3] = original[channelCount - 1]
            for (index in 3 until channelCount - 1) {
                fixed[index + 1] = original[index]
            }
            return fixed
        }

        /**
         * Reads exactly [count] single-digit characters starting at [start].
         *
         * @return the digit values, or `null` when the run is short or contains a non-digit —
         *   either of which means this is not the parameter run we are looking for.
         */
        private fun digitsAt(line: String, start: Int, count: Int): IntArray? {
            if (start + count > line.length) return null
            val values = IntArray(count)
            for (offset in 0 until count) {
                val character = line[start + offset]
                if (character < '0' || character > '9') return null
                values[offset] = character - '0'
            }
            return values
        }
    }
}

/**
 * Pulls the values the handshake needs out of RTSP response headers (spec §6.3).
 *
 * Collected as one object so each rule is stated once and tested once — every one of them is a
 * "the host may or may not send this, and the fallback is X" rule, which is exactly the kind that
 * gets re-derived slightly differently at each call site.
 */
object RtspHeaderParser {

    /**
     * `server_port=<p>[-<p2>]` from a SETUP response `Transport` header (spec §6.3).
     *
     * @return the first port, or `null` when the header is absent or carries no usable number, in
     *   which case the caller uses the documented per-stream default from spec §0.4.
     */
    fun serverPort(transportHeader: String?): Int? {
        val header = transportHeader ?: return null
        val index = header.indexOf(RtspConstants.TRANSPORT_SERVER_PORT_TOKEN, ignoreCase = true)
        if (index < 0) return null
        var cursor = index + RtspConstants.TRANSPORT_SERVER_PORT_TOKEN.length
        val digits = StringBuilder(5)
        while (cursor < header.length && header[cursor].isDigit()) {
            digits.append(header[cursor])
            cursor++
        }
        val port = digits.toString().toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    /**
     * The RTSP session id, truncated at the first `;` (spec §6.3).
     *
     * A `Session` header reads `DEADBEEFCAFE;timeout=90`, and echoing the whole thing back earns a
     * `454 Session Not Found` from a strict server. Truncating is not tidiness, it is the protocol.
     *
     * @return the bare id, or `null` when the header is absent or empty.
     */
    fun sessionId(sessionHeader: String?): String? =
        sessionHeader?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The Sunshine `X-SS-Ping-Payload` to echo in UDP keep-alives (spec §6.3, §7.5).
     *
     * Length is validated rather than trusted: a payload of any length other than
     * [RtspConstants.SS_PING_PAYLOAD_CHARS] means we misread the header, and the keep-alive is what
     * opens the host's NAT pinhole — get it wrong and the session negotiates perfectly and then
     * delivers no media at all. An absent or wrong-length header means the legacy 4-byte `PING`.
     */
    fun pingPayload(header: String?): String? {
        val payload = header?.trim() ?: return null
        if (payload.isEmpty()) return null
        if (payload.length != RtspConstants.SS_PING_PAYLOAD_CHARS) {
            ProtocolLog.w(
                RtspConstants.TAG,
                "ignoring ${RtspConstants.HEADER_SS_PING_PAYLOAD} of ${payload.length} chars; " +
                    "spec §6.3 says ${RtspConstants.SS_PING_PAYLOAD_CHARS}. Falling back to the " +
                    "legacy PING keep-alive.",
            )
            return null
        }
        return payload
    }

    /**
     * The Sunshine `X-SS-Connect-Data`, an unsigned 32-bit ENet connect datum (spec §6.3, §9.1).
     *
     * Base is auto-detected because the header may be `0x`-prefixed. The result is returned as the
     * 32-bit pattern in an `Int`, which is what ENet puts on the wire; values above
     * `Int.MAX_VALUE` are therefore negative when read as a signed `Int`, and that is correct.
     *
     * @return the connect data, or `0` when the header is absent or unparseable (spec §6.3).
     */
    fun connectData(header: String?): Int {
        val text = header?.trim().orEmpty()
        if (text.isEmpty()) return 0
        val negative = text.startsWith("-")
        val unsigned = text.removePrefix("-").removePrefix("+")
        val radix = if (unsigned.startsWith("0x", ignoreCase = true)) 16 else 10
        val magnitude = unsigned.removePrefix("0x").removePrefix("0X").toLongOrNull(radix)
        if (magnitude == null || magnitude > 0xFFFF_FFFFL) {
            ProtocolLog.w(
                RtspConstants.TAG,
                "unusable ${RtspConstants.HEADER_SS_CONNECT_DATA}=\"$text\"; using 0",
            )
            return 0
        }
        return (if (negative) -magnitude else magnitude).toInt()
    }
}
