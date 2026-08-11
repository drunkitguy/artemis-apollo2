package com.voidlink.android.data

/**
 * One launchable application (or the desktop session) exposed by a host.
 *
 * Box art is carried as raw encoded bytes because the protocol layer hands back whatever the host
 * sent — decoding to a bitmap is the UI layer's business, and only for tiles that are on screen.
 *
 * @property id host-assigned application id, used when launching.
 * @property name display name.
 * @property isDesktop true for the synthetic "Desktop" entry, which is always sorted first.
 * @property supportsHdr whether the host advertises HDR for this title.
 * @property boxArt encoded box-art image bytes, or `null` when the host has none.
 */
class HostApp(
    val id: String,
    val name: String,
    val isDesktop: Boolean = false,
    val supportsHdr: Boolean = false,
    val boxArt: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HostApp) return false
        // Identity is the host-assigned id; box-art bytes are incidental payload.
        return id == other.id &&
            name == other.name &&
            isDesktop == other.isDesktop &&
            supportsHdr == other.supportsHdr &&
            (boxArt == null) == (other.boxArt == null)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isDesktop.hashCode()
        result = 31 * result + supportsHdr.hashCode()
        result = 31 * result + (boxArt != null).hashCode()
        return result
    }

    override fun toString(): String = "HostApp(id=$id, name=$name, isDesktop=$isDesktop)"

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
     * Must not throw for ordinary network failures — implementations return an empty list, and the
     * caller renders the host's offline state instead.
     */
    suspend fun listApps(host: KnownHost): List<HostApp>

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

    override suspend fun quitRunningApp(host: KnownHost): Boolean = false
}
