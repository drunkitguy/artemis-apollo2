package com.voidlink.android.protocol.bridge

import com.voidlink.android.data.AppCatalogFailure
import com.voidlink.android.data.AppCatalogProvider
import com.voidlink.android.data.AppCatalogResult
import com.voidlink.android.data.HostApp
import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.http.AppListEntry
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
     * Never throws for an ordinary network failure, but every failure now names itself: the old
     * contract returned an empty list for unreachable, unpaired, refused and malformed alike, and
     * the screen rendered all four as "Nothing to stream" — indistinguishable from a PC with no
     * games on it, both to the user and to us reading their bug report.
     */
    override suspend fun listApps(host: KnownHost): AppCatalogResult {
        val resolved = resolver.resolve(host, ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS)
        if (resolved == null) {
            ProtocolLog.w(ProtocolLog.TAG_HTTP, "Cannot list apps: ${host.name} is unreachable")
            return AppCatalogResult.Failure(
                AppCatalogFailure.UNREACHABLE,
                "no saved address for ${host.name} answered",
            )
        }
        ProtocolLog.i(
            ProtocolLog.TAG_HTTP,
            "/applist for ${host.name}: hostKey=${host.uuid}, " +
                "address=${resolved.address.canonical()}, httpsPort=${resolved.serverInfo.httpsPort}",
        )
        val listResult = client.appList(
            hostKey = host.uuid,
            address = resolved.address,
            httpsPort = resolved.serverInfo.httpsPort,
        )
        val entries = when (listResult) {
            is NvHttpResult.Success -> listResult.value
            else -> {
                val detail = listResult.errorDescription() ?: "unknown error"
                ProtocolLog.w(ProtocolLog.TAG_HTTP, "/applist for ${host.name} failed: $detail")
                return AppCatalogResult.Failure(failureFor(listResult), detail)
            }
        }

        if (AppListEntry.isPermissionDeniedPlaceholder(entries)) {
            // Apollo answers 200 with a single placeholder entry rather than an error when a paired
            // client has not been granted permission to list applications. Rendering it as a game,
            // or filtering it and showing an empty grid, both hide the one fact that matters: the
            // fix is on the PC, not here.
            ProtocolLog.w(
                ProtocolLog.TAG_HTTP,
                "/applist for ${host.name} returned the host's permission-denied placeholder; " +
                    "this device is paired but not allowed to list applications",
            )
            return AppCatalogResult.Failure(
                AppCatalogFailure.PERMISSION_DENIED,
                "${host.name} is paired with this device but has not granted it permission to " +
                    "list applications",
            )
        }

        val apps = entries
            .map { entry ->
                HostApp(
                    id = entry.id.toString(),
                    name = entry.title,
                    isDesktop = entry.isDesktop,
                    supportsHdr = entry.hdrSupported,
                    sortKey = entry.sortKey,
                )
            }
            .sortedWith(HostApp.displayOrder)
        ProtocolLog.i(
            ProtocolLog.TAG_HTTP,
            "/applist for ${host.name}: ${apps.size} applications" +
                (if (apps.isEmpty()) " — the host answered 200 but listed nothing" else ""),
        )
        return AppCatalogResult.Success(apps)
    }

    /**
     * Maps a transport outcome onto the reason the user is shown.
     *
     * Exhaustive rather than an `else`, so a new [NvHttpResult] case cannot quietly inherit
     * somebody else's error message.
     */
    private fun failureFor(result: NvHttpResult<List<AppListEntry>>): AppCatalogFailure = when (result) {
        // Unreachable — success is handled by the caller — but named so the `when` stays exhaustive
        // and a future result type cannot be silently absorbed by an `else`.
        is NvHttpResult.Success -> AppCatalogFailure.TRANSPORT
        is NvHttpResult.HostError -> AppCatalogFailure.HOST_REFUSED
        is NvHttpResult.TransportError -> AppCatalogFailure.TRANSPORT
        is NvHttpResult.TlsRejected -> AppCatalogFailure.TLS
        is NvHttpResult.Malformed -> AppCatalogFailure.UNREADABLE
        NvHttpResult.NotPaired -> AppCatalogFailure.NOT_PAIRED
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
