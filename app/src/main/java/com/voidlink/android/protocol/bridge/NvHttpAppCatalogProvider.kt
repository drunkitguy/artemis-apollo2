package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.AppCatalogProvider
import com.voidlink.android.data.HostApp
import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.http.AppListEntry
import com.voidlink.android.protocol.http.BoxArtCache
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.http.NvHttpResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The real [AppCatalogProvider]: `/applist` plus `/appasset` box art, over pinned HTTPS.
 *
 * Box art is fetched for the whole list up front because the UI model carries the bytes inline,
 * but with two guards that keep that from being a mistake: a disk cache, since art essentially
 * never changes, and bounded parallelism, since each fetch is its own TLS handshake and a
 * fifty-game library would otherwise open fifty connections at once.
 *
 * @param client the NVHTTP transport.
 * @param resolver picks the reachable address and the HTTPS port.
 * @param boxArtCache read-through disk cache for `/appasset` responses.
 */
class NvHttpAppCatalogProvider(
    private val client: NvHttpClient,
    private val resolver: HostEndpointResolver,
    private val boxArtCache: BoxArtCache,
) : AppCatalogProvider {

    /**
     * Fetches [host]'s library.
     *
     * Returns an empty list rather than throwing on any failure — unreachable, unpaired, or a
     * malformed reply — because the Apps screen renders that as its empty state, which is the
     * right thing for the user to see in all three cases.
     */
    override suspend fun listApps(host: KnownHost): List<HostApp> {
        val resolved = resolver.resolve(host, ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS)
        if (resolved == null) {
            ProtocolLog.w(ProtocolLog.TAG_HTTP, "Cannot list apps: ${host.name} is unreachable")
            return emptyList()
        }
        val listResult = client.appList(
            hostKey = host.uuid,
            address = resolved.address,
            httpsPort = resolved.serverInfo.httpsPort,
        )
        val entries = when (listResult) {
            is NvHttpResult.Success -> listResult.value
            else -> {
                ProtocolLog.w(
                    ProtocolLog.TAG_HTTP,
                    "/applist for ${host.name} failed: ${listResult.errorDescription()}",
                )
                return emptyList()
            }
        }

        val apps = coroutineScope {
            val gate = Semaphore(BOX_ART_PARALLELISM)
            entries
                .map { entry ->
                    async {
                        val art = gate.withPermit {
                            fetchBoxArt(host.uuid, resolved, entry)
                        }
                        HostApp(
                            id = entry.id.toString(),
                            name = entry.title,
                            isDesktop = entry.isDesktop,
                            supportsHdr = entry.hdrSupported,
                            boxArt = art,
                        )
                    }
                }
                .awaitAll()
        }
        return apps.sortedWith(HostApp.displayOrder)
    }

    /**
     * Asks the host to quit whatever it is running (spec §3.8).
     *
     * @return whether the host confirmed. A `false` most often means another client owns the
     *   session, which is a legitimate refusal rather than an error.
     */
    override suspend fun quitRunningApp(host: KnownHost): Boolean {
        val resolved = resolver.resolve(host, ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS)
            ?: return false
        val result = client.cancel(
            hostKey = host.uuid,
            address = resolved.address,
            httpsPort = resolved.serverInfo.httpsPort,
        )
        if (!result.isSuccess) {
            ProtocolLog.w(ProtocolLog.TAG_HTTP, "/cancel failed: ${result.errorDescription()}")
        }
        return result.valueOrNull() == true
    }

    private suspend fun fetchBoxArt(
        hostKey: String,
        resolved: ResolvedHost,
        entry: AppListEntry,
    ): ByteArray? {
        boxArtCache.get(hostKey, entry.id)?.let { return it }
        val fetched = client.boxArt(
            hostKey = hostKey,
            address = resolved.address,
            appId = entry.id,
            httpsPort = resolved.serverInfo.httpsPort,
        ).valueOrNull()
        // A host with no art for a title is normal; the UI draws its generated tile instead.
        if (fetched != null) boxArtCache.put(hostKey, entry.id, fetched)
        return fetched
    }

    private companion object {
        /**
         * How many box-art fetches may be in flight at once.
         *
         * Each is a separate pinned-TLS handshake; four keeps a large library responsive without
         * making the host's little HTTP server the bottleneck.
         */
        const val BOX_ART_PARALLELISM = 4
    }
}
