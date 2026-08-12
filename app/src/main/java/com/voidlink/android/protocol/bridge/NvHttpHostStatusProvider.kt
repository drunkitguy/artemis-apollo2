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
import java.util.concurrent.ConcurrentHashMap

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

    /** When each host last confirmed, over client-certificate TLS, that it still trusts us. */
    private val lastSecureConfirm = ConcurrentHashMap<String, Long>()

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
        //
        // But it is *rationed*. A Sunshine-family host does not reclaim the socket each secure
        // request costs — they accumulate in CLOSE_WAIT until its process is restarted — so a
        // background timer that opens one per host every cycle is a slow leak we are inflicting on
        // the user's PC. The answer also changes very rarely, so we ask when we do not know it, and
        // otherwise at a walking pace.
        val secureResult = if (trustStore.isTrusted(host.uuid) && shouldConfirmSecurely(host)) {
            client.serverInfoSecure(
                hostKey = host.uuid,
                address = resolved.address,
                httpsPort = plainInfo.httpsPort,
                timeoutMs = timeoutMs,
            ).also { if (it.isSuccess) lastSecureConfirm[host.uuid] = System.currentTimeMillis() }
        } else {
            null
        }

        // A slow network is not evidence of anything. Only a handshake the host actually refused
        // means it has forgotten us; a timeout leaves the last known answer standing, because
        // demoting a paired host makes its card offer "Pair with PIN" and makes the PC put up a
        // PIN prompt the user never asked for.
        val paired = when (secureResult) {
            // Not asked this cycle. The stored answer stands: it was established by a successful
            // secure request and nothing has happened to contradict it.
            null -> if (trustStore.isTrusted(host.uuid)) host.paired else false
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
            //
            // Rationed like the probe's own secure request, and for the same reason: this ran on
            // every discovery sweep, and each one costs the host a connection it never reclaims —
            // for a MAC address that does not change. Once we have it, we stop asking.
            val needsMac = plainInfo.macAddress == null &&
                shouldConfirmSecurely(KnownHost(uuid = plainInfo.uniqueId, name = "", paired = true))
            val secureInfo = if (needsMac && trustStore.isTrusted(plainInfo.uniqueId)) {
                client.serverInfoSecure(
                    hostKey = plainInfo.uniqueId,
                    address = address,
                    httpsPort = plainInfo.httpsPort,
                    timeoutMs = ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS,
                ).also { if (it.isSuccess) lastSecureConfirm[plainInfo.uniqueId] = System.currentTimeMillis() }
                    .valueOrNull()
            } else {
                null
            }

            toDiscoveredHost(secureInfo ?: plainInfo, address, service.serviceName)
        }

    /**
     * Whether this probe should spend a secure request on [host].
     *
     * Always when we do not yet know the answer — a record that says unpaired while a pin exists is
     * exactly the state the pairing recovery needs confirmed, and it must not wait five minutes.
     * Otherwise only occasionally, because the answer almost never changes and each ask costs the
     * host a socket it will not give back.
     */
    private fun shouldConfirmSecurely(host: KnownHost): Boolean {
        if (!host.paired) return true
        val last = lastSecureConfirm[host.uuid] ?: return true
        return System.currentTimeMillis() - last >= SECURE_CONFIRM_INTERVAL_MS
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

    private companion object {
        /**
         * How often a already-confirmed pairing is re-confirmed over TLS.
         *
         * Generous because the fact almost never changes, and because each check costs the host a
         * connection it does not reclaim. A host that genuinely forgets us is still caught — on the
         * next check, or immediately by any real secure request the user triggers.
         */
        const val SECURE_CONFIRM_INTERVAL_MS = 5 * 60 * 1000L
    }
}
