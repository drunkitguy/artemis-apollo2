package com.voidlink.android.data

/**
 * One launchable application (or the desktop session) exposed by a host.
 *
 * Deliberately carries **no image bytes**. Box art is fetched per tile through
 * [AppCatalogProvider.boxArt] as it comes on screen: a fifty-game library is tens of megabytes of
 * PNG, and holding all of it in UI state — on top of the decoded bitmaps — is an out-of-memory
 * crash waiting for a device with less RAM than the developer's.
 *
 * @property id host-assigned application id, used when launching and when fetching art.
 * @property name display name.
 * @property isDesktop true for the synthetic "Desktop" entry, which is always sorted first.
 * @property supportsHdr whether the host advertises HDR for this title.
 */
data class HostApp(
    val id: String,
    val name: String,
    val isDesktop: Boolean = false,
    val supportsHdr: Boolean = false,
) {
    companion object {
        /**
         * The order a host's library is drawn in: Desktop first, then everything else
         * alphabetically and case-insensitively.
         *
         * Lives here rather than in the view model so the rule is one pure, tested thing — the
         * grid, any future search result and the in-stream switcher must not disagree about it.
         */
        val displayOrder: Comparator<HostApp> =
            compareByDescending<HostApp> { it.isDesktop }
                .thenBy { it.name.lowercase() }
                .thenBy { it.id }
    }
}

/**
 * Source of a host's application list.
 *
 * Implemented by the protocol layer in a later task; [StubAppCatalogProvider] keeps the Apps screen
 * navigable until then.
 */
interface AppCatalogProvider {
    /**
     * Fetches the applications [host] offers.
     *
     * Must return as soon as the list itself is known, without waiting on artwork, so the grid can
     * draw immediately. Must not throw for ordinary network failures — implementations return an
     * empty list, and the caller renders the host's offline state instead.
     */
    suspend fun listApps(host: KnownHost): List<HostApp>

    /**
     * Fetches one application's box art, or `null` when the host has none for it.
     *
     * Called per tile as it becomes visible, and expected to be cheap on repeat: implementations
     * cache to disk, because art essentially never changes.
     *
     * @param appId the [HostApp.id] to fetch art for.
     */
    suspend fun boxArt(host: KnownHost, appId: String): ByteArray?

    /**
     * Asks the host to quit whatever app is currently running.
     *
     * @return true when the host confirmed the quit.
     */
    suspend fun quitRunningApp(host: KnownHost): Boolean
}

/**
 * Placeholder [AppCatalogProvider] used before the protocol layer exists.
 *
 * Returns only the synthetic Desktop entry so the Apps screen has something real to lay out, and
 * reports quit requests as unsuccessful.
 */
object StubAppCatalogProvider : AppCatalogProvider {
    /** The synthetic entry every host implicitly offers. */
    val desktopEntry: HostApp = HostApp(id = "desktop", name = "Desktop", isDesktop = true)

    override suspend fun listApps(host: KnownHost): List<HostApp> = listOf(desktopEntry)

    override suspend fun boxArt(host: KnownHost, appId: String): ByteArray? = null

    override suspend fun quitRunningApp(host: KnownHost): Boolean = false
}
