package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.AppCatalogProvider
import com.voidlink.android.data.HostApp
import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.http.BoxArtCache
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.http.NvHttpResult

/**
 * The real [AppCatalogProvider]: `/applist` plus `/appasset` box art, over pinned HTTPS.
 *
 * The list is returned as soon as `/applist` answers, and art is fetched one tile at a time as the
 * grid asks for it. Fetching the whole library's art first meant a fifty-game host showed an empty
 * screen for several seconds and then retained tens of megabytes of PNG for as long as the screen
 * was open. The disk cache carries the cost of re-fetching on scroll.
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

        return entries
            .map { entry ->
                HostApp(
                    id = entry.id.toString(),
                    name = entry.title,
                    isDesktop = entry.isDesktop,
                    supportsHdr = entry.hdrSupported,
                )
            }
            .sortedWith(HostApp.displayOrder)
    }

    /**
     * Fetches one tile's box art, cache first.
     *
     * A host with no art for a title answers with nothing, which is normal and not an error — the
     * UI draws its generated tile instead.
     */
    override suspend fun boxArt(host: KnownHost, appId: String): ByteArray? {
        val id = appId.toLongOrNull() ?: return null
        boxArtCache.get(host.uuid, id)?.let { return it }
        val resolved = resolver.resolve(host, ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS)
            ?: return null
        val fetched = client.boxArt(
            hostKey = host.uuid,
            address = resolved.address,
            appId = id,
            httpsPort = resolved.serverInfo.httpsPort,
        ).valueOrNull()
        if (fetched != null) boxArtCache.put(host.uuid, id, fetched)
        return fetched
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

}
