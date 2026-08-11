package com.voidlink.android.protocol

/**
 * A host address plus the NVHTTP plaintext port to reach it on.
 *
 * The persisted [com.voidlink.android.data.KnownHost] model stores addresses as plain strings with
 * no port field, so the port travels inside the string in the standard `host:port` form. This type
 * is the single place that parses and re-renders that convention, which keeps the data layer
 * unchanged while still supporting Sunshine's configurable base port (spec §0.4).
 *
 * Accepts every form spec §1.2 requires: a hostname, an IPv4 literal, a bracketed IPv6 literal, a
 * bare IPv6 literal, and any of those with an explicit `:port` suffix.
 *
 * @property host the bare host, with no brackets even when it is an IPv6 literal.
 * @property port the NVHTTP plaintext port.
 */
data class HostAddress(
    val host: String,
    val port: Int = ProtocolConstants.DEFAULT_HTTP_PORT,
) {
    /** True when [host] is an IPv6 literal and therefore needs brackets inside a URL. */
    val isIpv6Literal: Boolean get() = host.contains(':')

    /**
     * The `host:port` authority for a URL, bracketing an IPv6 literal.
     *
     * @param overridePort use this port instead of [port] — how the HTTPS port is applied.
     */
    fun authority(overridePort: Int = port): String =
        if (isIpv6Literal) "[$host]:$overridePort" else "$host:$overridePort"

    /**
     * The form written back into `KnownHost.addresses`.
     *
     * The port is omitted when it is the default, so the common case stores a clean `192.168.1.24`
     * and only an unusual Sunshine base port produces `192.168.1.24:47999`.
     */
    fun canonical(): String = when {
        port == ProtocolConstants.DEFAULT_HTTP_PORT && isIpv6Literal -> "[$host]"
        port == ProtocolConstants.DEFAULT_HTTP_PORT -> host
        isIpv6Literal -> "[$host]:$port"
        else -> "$host:$port"
    }

    companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        /**
         * Parses a stored or user-entered address.
         *
         * @param raw the address text.
         * @param defaultPort port to assume when [raw] carries none.
         * @return the parsed address, or `null` when [raw] is blank or the port is unusable.
         *   A malformed port is a `null` rather than a silent fallback: a user who typed
         *   `192.168.1.5:8O80` wants to be told, not connected somewhere else.
         */
        fun parse(
            raw: String?,
            defaultPort: Int = ProtocolConstants.DEFAULT_HTTP_PORT,
        ): HostAddress? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close <= 1) return null
                val host = text.substring(1, close).trim()
                if (host.isEmpty()) return null
                val rest = text.substring(close + 1)
                if (rest.isEmpty()) return HostAddress(host, defaultPort)
                if (!rest.startsWith(":")) return null
                val port = portOrNull(rest.substring(1)) ?: return null
                return HostAddress(host, port)
            }

            val lastColon = text.lastIndexOf(':')
            val firstColon = text.indexOf(':')
            // More than one colon and no brackets means a bare IPv6 literal, which has no port.
            if (firstColon >= 0 && firstColon != lastColon) {
                return HostAddress(text, defaultPort)
            }
            if (lastColon < 0) {
                return HostAddress(text, defaultPort)
            }
            val host = text.substring(0, lastColon).trim()
            if (host.isEmpty()) return null
            val port = portOrNull(text.substring(lastColon + 1)) ?: return null
            return HostAddress(host, port)
        }

        private fun portOrNull(text: String): Int? {
            val port = text.trim().toIntOrNull() ?: return null
            return if (port in MIN_PORT..MAX_PORT) port else null
        }
    }
}
