package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.http.ServerInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * A host that answered, together with the address it answered on.
 *
 * @property address the address that worked, so the caller can promote it.
 * @property serverInfo the parsed `/serverinfo` document.
 */
class ResolvedHost(val address: HostAddress, val serverInfo: ServerInfo)

/**
 * Finds which of a saved host's addresses is currently reachable, and remembers its HTTPS port.
 *
 * A host commonly has several addresses — a LAN v4 literal, an IPv6 one, a manually typed name —
 * and only one of them works at any moment. Every protocol operation needs the same answer, so
 * resolving is done in one place and the port learned from `<HttpsPort>` is cached: otherwise
 * every `/applist` would need a preceding `/serverinfo` just to discover a non-default Sunshine
 * port (spec §0.4).
 *
 * @param client the NVHTTP transport.
 */
class HostEndpointResolver(private val client: NvHttpClient) {

    private val httpsPorts = ConcurrentHashMap<String, Int>()

    /**
     * The last successful resolution per host, reused for a few seconds.
     *
     * Every call used to issue its own plaintext `/serverinfo`, and the callers stack up: opening
     * the Apps screen resolved once to probe, again to list, and once more per box-art tile. A
     * Sunshine-family host does not reclaim the sockets those connections cost — they accumulate in
     * `CLOSE_WAIT` until its process restarts — so a redundant round trip is not merely wasteful,
     * it is a permanent cost to the machine we are trying to talk to.
     *
     * Short-lived on purpose: this is de-duplication of one burst of activity, not a cache of where
     * a host lives.
     */
    private val recent = ConcurrentHashMap<String, CachedResolution>()

    private class CachedResolution(val resolved: ResolvedHost, val atMillis: Long)

    /**
     * Probes each of the host's addresses in order and returns the first that answers.
     *
     * Plaintext, because it is the only transport guaranteed to work whether or not we are paired
     * and it is what tells us the HTTPS port to use for everything else (spec §3.1).
     *
     * @param host the saved host.
     * @param timeoutMs per-address connect and read timeout.
     * @return the reachable endpoint, or `null` when no address answered.
     */
    suspend fun resolve(host: KnownHost, timeoutMs: Int): ResolvedHost? {
        recent[host.uuid]?.let { cached ->
            if (System.currentTimeMillis() - cached.atMillis < RESOLVE_CACHE_MS) {
                ProtocolLog.d(
                    ProtocolLog.TAG_HTTP,
                    "Reusing the ${System.currentTimeMillis() - cached.atMillis}ms-old resolution " +
                        "of ${host.name}; a fresh /serverinfo per call is a connection the host " +
                        "does not reclaim",
                )
                return cached.resolved
            }
        }
        val addresses = host.addresses.mapNotNull { HostAddress.parse(it) }
        if (addresses.isEmpty()) {
            ProtocolLog.d(ProtocolLog.TAG_HTTP, "Host ${host.name} has no usable address")
            return null
        }
        for (address in addresses) {
            when (val result = client.serverInfoPlain(address, timeoutMs)) {
                is NvHttpResult.Success -> {
                    httpsPorts[host.uuid] = result.value.httpsPort
                    val resolved = ResolvedHost(address, result.value)
                    recent[host.uuid] = CachedResolution(resolved, System.currentTimeMillis())
                    return resolved
                }
                else -> ProtocolLog.d(
                    ProtocolLog.TAG_HTTP,
                    "${host.name} did not answer at ${address.canonical()}: ${result.errorDescription()}",
                )
            }
        }
        recent.remove(host.uuid)
        return null
    }

    /** Drops any cached resolution for [hostKey], so the next call really asks the host. */
    fun invalidate(hostKey: String) {
        recent.remove(hostKey)
    }

    /**
     * Chooses the timeout for a probe of [host].
     *
     * A host we saw recently gets a generous timeout; one we believe is off gets a short one, so a
     * single dead machine cannot stall the whole Hosts screen (spec §1.3).
     *
     * @param nowEpochMillis current time, injectable for tests.
     */
    fun timeoutFor(host: KnownHost, nowEpochMillis: Long = System.currentTimeMillis()): Int {
        val age = nowEpochMillis - host.lastSeenEpochMillis
        val believedOnline = host.lastSeenEpochMillis > 0L &&
            age in 0..ProtocolConstants.BELIEVED_ONLINE_WINDOW_MS
        return if (believedOnline) {
            ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS
        } else {
            ProtocolConstants.PROBE_TIMEOUT_OFFLINE_MS
        }
    }

    /** The HTTPS port last learned for [hostKey], or the 47984 default. */
    fun httpsPort(hostKey: String): Int =
        httpsPorts[hostKey] ?: ProtocolConstants.DEFAULT_HTTPS_PORT

    /** Records a HTTPS port learned elsewhere, such as during pairing. */
    fun rememberHttpsPort(hostKey: String, port: Int) {
        if (port in 1..65535) httpsPorts[hostKey] = port
    }

    private companion object {
        /**
         * How long a successful resolution is reused for.
         *
         * Long enough to cover one screen-open and the burst of box-art requests that follows it,
         * short enough that a host which moves or goes down is noticed on the next probe cycle.
         */
        const val RESOLVE_CACHE_MS = 15_000L
    }
}
