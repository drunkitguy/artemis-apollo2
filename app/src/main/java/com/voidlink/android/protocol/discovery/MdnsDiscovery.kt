package com.voidlink.android.protocol.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.UnverifiedProtocolConstants
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import kotlin.coroutines.resume

/**
 * A `_nvstream._tcp` advertisement that has been resolved to an address (spec §1.1).
 *
 * The advertised instance name is carried for logging only. The spec is explicit that it must not
 * be trusted as the host's identity — the caller follows up with `/serverinfo` and uses
 * `<hostname>` and `<uniqueid>` from there.
 *
 * @property serviceName the mDNS instance name.
 * @property address the resolved IP literal, with any IPv6 scope suffix removed.
 * @property port the advertised port, which for this service is the NVHTTP plaintext port.
 */
class MdnsService(val serviceName: String, val address: String, val port: Int) {
    override fun toString(): String = "MdnsService($serviceName at $address:$port)"
}

/**
 * Local-network discovery of GameStream hosts over mDNS (spec §1.1).
 *
 * Three Android-specific hazards the spec calls out, and how each is handled here:
 *
 * 1. **Concurrent resolves fail.** `NsdManager.resolveService` is effectively single-flight on
 *    many devices and ROMs; issuing a second one while the first is outstanding fails both. Found
 *    services are therefore queued onto an unbounded channel and drained by a single worker that
 *    resolves strictly one at a time.
 * 2. **Multicast may be filtered.** A `WifiManager.MulticastLock` is held for the lifetime of the
 *    discovery and released when the flow is cancelled.
 * 3. **Resolves can silently never call back.** Each resolve is bounded by
 *    [RESOLVE_TIMEOUT_MS] so one wedged advertisement cannot stall the queue for the rest of the
 *    session.
 *
 * mDNS is never treated as a required path: routers with client isolation drop it entirely, which
 * is precisely why manual address entry exists.
 *
 * @param context any context; the application context is taken from it.
 */
class MdnsDiscovery(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Emits every host found on the local network for as long as the flow is collected.
     *
     * Cold: collection starts discovery and cancellation stops it, releasing the multicast lock
     * and unregistering the listener.
     *
     * A given host is emitted once per discovery session; if the advertisement is withdrawn and
     * re-seen — the PC rebooted, or moved to a new address — it is emitted again.
     */
    fun discover(): Flow<MdnsService> = callbackFlow {
        // UNVERIFIED(spec 01 §1.1, item 18): which TXT keys hosts publish. We deliberately read
        // none of them and treat an advertisement as nothing more than an address and a port.
        ProtocolLog.unverified(
            ProtocolLog.TAG_DISCOVERY,
            "mdns-txt-records",
            "ignoring all mDNS TXT records; discovery yields only an address and port " +
                "(spec 01 §1.1, item 18). trustTxt=" +
                UnverifiedProtocolConstants.TRUST_MDNS_TXT_RECORDS,
        )

        val nsdManager = runCatching {
            appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull()
        if (nsdManager == null) {
            ProtocolLog.w(ProtocolLog.TAG_DISCOVERY, "NsdManager unavailable; discovery disabled")
            close()
            // callbackFlow requires awaitClose on every path out of the block, including this one.
            awaitClose { }
            return@callbackFlow
        }

        val multicastLock = acquireMulticastLock()
        val pending = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val seen = Collections.synchronizedSet(HashSet<String>())

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                ProtocolLog.w(
                    ProtocolLog.TAG_DISCOVERY,
                    "discoverServices failed for $serviceType (error $errorCode)",
                )
                pending.close()
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                ProtocolLog.w(
                    ProtocolLog.TAG_DISCOVERY,
                    "stopServiceDiscovery failed for $serviceType (error $errorCode)",
                )
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                ProtocolLog.i(ProtocolLog.TAG_DISCOVERY, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                ProtocolLog.i(ProtocolLog.TAG_DISCOVERY, "Discovery stopped for $serviceType")
                pending.close()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val info = serviceInfo ?: return
                val name = info.serviceName ?: return
                if (!seen.add(name)) return
                ProtocolLog.d(ProtocolLog.TAG_DISCOVERY, "Found $name; queued for resolution")
                pending.trySend(info)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val name = serviceInfo?.serviceName ?: return
                // Allow a re-appearance to be resolved again: the host may come back at a new
                // address, and that is exactly the case we want to notice.
                seen.remove(name)
                ProtocolLog.d(ProtocolLog.TAG_DISCOVERY, "Lost $name")
            }
        }

        // One worker, one outstanding resolve — the whole point of the queue.
        val resolver = launch {
            for (serviceInfo in pending) {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    resolveOnce(nsdManager, serviceInfo)
                }
                if (resolved == null) {
                    ProtocolLog.w(
                        ProtocolLog.TAG_DISCOVERY,
                        "Could not resolve ${serviceInfo.serviceName}",
                    )
                    // Let a later advertisement retry rather than writing the host off forever.
                    serviceInfo.serviceName?.let { seen.remove(it) }
                    continue
                }
                ProtocolLog.i(ProtocolLog.TAG_DISCOVERY, "Resolved $resolved")
                send(resolved)
            }
        }

        val started = runCatching {
            nsdManager.discoverServices(
                ProtocolConstants.MDNS_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.isSuccess
        if (!started) {
            ProtocolLog.w(ProtocolLog.TAG_DISCOVERY, "discoverServices threw; discovery disabled")
            pending.close()
            close()
        }

        awaitClose {
            resolver.cancel()
            pending.close()
            if (started) {
                runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                    .onFailure {
                        ProtocolLog.d(ProtocolLog.TAG_DISCOVERY, "stopServiceDiscovery: ${it.message}")
                    }
            }
            releaseMulticastLock(multicastLock)
        }
    }

    /**
     * Resolves one advertisement.
     *
     * A fresh listener per call, deliberately: reusing one across resolves is what produces
     * `FAILURE_ALREADY_ACTIVE`. A listener whose resolve timed out may still fire later, which is
     * harmless because the continuation is checked for liveness first.
     *
     * @return the resolved service, or `null` when the platform reported a failure.
     */
    private suspend fun resolveOnce(
        nsdManager: NsdManager,
        serviceInfo: NsdServiceInfo,
    ): MdnsService? = suspendCancellableCoroutine { continuation ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                ProtocolLog.d(
                    ProtocolLog.TAG_DISCOVERY,
                    "Resolve failed for ${info?.serviceName} (error $errorCode)",
                )
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onServiceResolved(info: NsdServiceInfo?) {
                if (!continuation.isActive) return
                continuation.resume(toService(info))
            }
        }
        try {
            @Suppress("DEPRECATION")
            // resolveService is deprecated at API 34 in favour of registerServiceInfoCallback, but
            // minSdk is 26 and this is the only resolution API available across that range.
            nsdManager.resolveService(serviceInfo, listener)
        } catch (t: Throwable) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun toService(info: NsdServiceInfo?): MdnsService? {
        if (info == null) return null
        @Suppress("DEPRECATION")
        val host = info.host ?: return null
        val port = info.port
        if (port !in 1..65535) return null
        // An IPv6 literal from the platform carries a "%wlan0" scope suffix that no URL parser
        // accepts; the address without it is what we can actually connect to.
        val address = host.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() } ?: return null
        return MdnsService(
            serviceName = info.serviceName ?: address,
            address = address,
            port = port,
        )
    }

    /**
     * Acquires a multicast lock, which some devices and ROMs require before mDNS packets are
     * delivered to the app at all (spec §1.1).
     *
     * Returns `null` when the lock cannot be taken; discovery is still attempted, because on most
     * modern devices it works without one.
     */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@runCatching null
        wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }.onFailure {
        ProtocolLog.w(ProtocolLog.TAG_DISCOVERY, "Could not acquire a multicast lock", it)
    }.getOrNull()

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock == null) return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { ProtocolLog.d(ProtocolLog.TAG_DISCOVERY, "Multicast lock release: ${it.message}") }
    }

    private companion object {
        const val MULTICAST_LOCK_TAG = "voidlink-mdns"

        /**
         * How long one resolve may take before the queue moves on.
         *
         * Generous, because a busy Wi-Fi network can take a couple of seconds, but bounded because
         * `NsdManager` sometimes never calls back at all.
         */
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
