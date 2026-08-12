package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.DiscoveredHost
import com.voidlink.android.data.HostReachability
import com.voidlink.android.data.HostStatus
import com.voidlink.android.data.HostStatusProvider
import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.discovery.MdnsDiscovery
import com.voidlink.android.protocol.http.HostTrustStore
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.http.ServerInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The real [HostStatusProvider], replacing the stub the UI shipped against.
 *
 * Two responsibilities, both from spec §1 and §3.3:
 *
 * * **Probe** — is this saved host reachable, and does it *genuinely* still trust us? Reachability
 *   comes from a plaintext `/serverinfo`; the pairing answer comes from repeating the request over
 *   pinned HTTPS, because `PairStatus` over plaintext is only weak identification and a host that
 *   has forgotten our certificate will still happily answer on port 47989.
 * * **Discover** — mDNS advertisements, each confirmed with a `/serverinfo` so the host's own
 *   `<uniqueid>` and `<hostname>` are used rather than the advertised instance name, which the
 *   spec says explicitly not to trust.
 *
 * @param client the NVHTTP transport.
 * @param trustStore tells us whether we hold a pinned certificate for a host.
 * @param resolver picks the reachable address and remembers the HTTPS port.
 * @param mdnsDiscovery local-network discovery.
 */
class NvHttpHostStatusProvider(
    private val client: NvHttpClient,
    private val trustStore: HostTrustStore,
    private val resolver: HostEndpointResolver,
    private val mdnsDiscovery: MdnsDiscovery,
) : HostStatusProvider {

    /**
     * Probes one saved host.
     *
     * Never throws for an ordinary network failure — an unreachable PC is simply
     * [HostStatus.Offline], which is the contract the Hosts screen is written against.
     */
    override suspend fun probe(host: KnownHost): HostStatus {
        val timeoutMs = resolver.timeoutFor(host)
        val resolved = resolver.resolve(host, timeoutMs) ?: return HostStatus.Offline
        val plainInfo = resolved.serverInfo

        // The authoritative pairing check: only a successful client-certificate TLS request proves
        // the host still trusts us (spec §3.3). It also returns the real MAC, which Sunshine
        // withholds over plaintext (spec §1.4).
        val secureResult = if (trustStore.isTrusted(host.uuid)) {
            client.serverInfoSecure(
                hostKey = host.uuid,
                address = resolved.address,
                httpsPort = plainInfo.httpsPort,
                timeoutMs = timeoutMs,
            )
        } else {
            null
        }

        // A slow network is not evidence of anything. Only a handshake the host actually refused
        // means it has forgotten us; a timeout leaves the last known answer standing, because
        // demoting a paired host makes its card offer "Pair with PIN" and makes the PC put up a
        // PIN prompt the user never asked for.
        val paired = when (secureResult) {
            null -> false
            is NvHttpResult.Success -> true
            is NvHttpResult.TlsRejected -> {
                ProtocolLog.w(
                    ProtocolLog.TAG_HTTP,
                    "${host.name} refused our certificate; treating as unpaired",
                )
                false
            }
            else -> {
                ProtocolLog.d(
                    ProtocolLog.TAG_HTTP,
                    "${host.name}: HTTPS probe inconclusive (${secureResult?.errorDescription()}); " +
                        "keeping the stored pairing state",
                )
                host.paired
            }
        }

        val best = secureResult?.valueOrNull() ?: plainInfo
        return HostStatus(
            reachability = HostReachability.ONLINE,
            paired = paired,
            runningAppId = best.currentGameId,
            // The running app's title needs /applist, which is a separate HTTPS round trip per
            // host; the Apps screen already resolves the name from the list it has loaded.
            runningAppName = null,
            hostName = best.hostname,
            // The host's own identity, which is what discovery files it under. Reporting it lets a
            // manually added record be reconciled onto the same PC instead of shadowing it.
            uniqueId = best.uniqueId,
            // Only the HTTPS response carries the real MAC, so this is null until the host
            // is paired. The repository treats null as "not seen this time" rather than as
            // a correction, so a later plaintext probe cannot erase a MAC we already learned.
            macAddress = best.macAddress,
        )
    }

    /**
     * Emits hosts found on the local network.
     *
     * Each advertisement is confirmed with a `/serverinfo` before being emitted, so a host that is
     * advertising but not actually serving never appears, and the identity we report is the host's
     * own rather than the mDNS instance name (spec §1.1).
     */
    override fun discover(): Flow<DiscoveredHost> =
        mdnsDiscovery.discover().mapNotNull { service ->
            val address = HostAddress(service.address, service.port)
            val plainInfo = client
                .serverInfoPlain(address, ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS)
                .valueOrNull()
            if (plainInfo == null) {
                ProtocolLog.d(
                    ProtocolLog.TAG_DISCOVERY,
                    "$service advertised but did not answer /serverinfo",
                )
                return@mapNotNull null
            }
            resolver.rememberHttpsPort(plainInfo.uniqueId, plainInfo.httpsPort)

            // For a host we have already paired with, ask again over HTTPS: that is the only way to
            // learn the real MAC from Sunshine, and the MAC is what makes Wake-on-LAN possible
            // later (spec §1.4).
            val secureInfo = if (trustStore.isTrusted(plainInfo.uniqueId)) {
                client.serverInfoSecure(
                    hostKey = plainInfo.uniqueId,
                    address = address,
                    httpsPort = plainInfo.httpsPort,
                    timeoutMs = ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS,
                ).valueOrNull()
            } else {
                null
            }

            toDiscoveredHost(secureInfo ?: plainInfo, address, service.serviceName)
        }

    private fun toDiscoveredHost(
        info: ServerInfo,
        address: HostAddress,
        fallbackName: String,
    ): DiscoveredHost = DiscoveredHost(
        uuid = info.uniqueId,
        name = info.hostname?.takeIf { it.isNotBlank() } ?: fallbackName,
        // canonical() keeps the port in the string only when it is non-default, so the common case
        // stores a clean IP and an unusual Sunshine base port survives a restart (spec §0.4).
        address = address.canonical(),
        macAddress = info.macAddress,
    )
}
