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
    /**
     * What this app sorts by, which is not always what it is called.
     *
     * Apollo encodes the order configured on the PC into the title using invisible characters, so
     * that a client sorting alphabetically ends up reproducing that order. Keeping the raw value
     * here and the readable one in [name] honours the host's ordering without putting invisible
     * characters in front of the user. Defaults to [name], which is the Sunshine/GFE case.
     */
    val sortKey: String = name,
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
                .thenBy { it.sortKey.lowercase() }
                .thenBy { it.id }
    }
}

/**
 * Why a library could not be listed.
 *
 * Each of these needs something different from the user, and the previous contract — "return an
 * empty list on any failure" — collapsed all of them into the same grey "Nothing to stream". A user
 * whose PC refused the request, whose TLS failed, and whose library is genuinely empty all saw the
 * identical screen, and so did we in their bug report.
 */
enum class AppCatalogFailure {
    /** No address for the host answered. */
    UNREACHABLE,

    /** We hold no pinned certificate, so the secure request was never attempted. */
    NOT_PAIRED,

    /** The host answered with a non-200 `status_code` — it understood us and said no. */
    HOST_REFUSED,

    /** The host is paired but has not granted this device permission to list its applications. */
    PERMISSION_DENIED,

    /** The request did not complete: timeout, refused connection, socket closed. */
    TRANSPORT,

    /**
     * The host answered over plaintext moments earlier, but its secure channel did not answer at
     * all.
     *
     * Distinct from [TRANSPORT] because we hold proof the machine is awake and on this network — we
     * just spoke to it. Telling the user their PC might be asleep when we have a live answer from
     * it is not merely unhelpful, it sends them to check the one thing that is definitely fine.
     */
    SECURE_CHANNEL_SILENT,

    /** The TLS handshake failed — the host would not accept our client certificate. */
    TLS,

    /** The host answered, but the body was not a document we could read. */
    UNREADABLE,
}

/**
 * The outcome of asking a host for its library.
 *
 * Deliberately not `List<HostApp>`: an empty list is a legitimate answer ("this PC has no games
 * configured") and must be distinguishable from every way the question can fail.
 */
sealed interface AppCatalogResult {

    /**
     * The host answered. [apps] may legitimately be empty.
     *
     * @property runningAppId the app currently streaming, read from the same `/serverinfo` the
     *   lookup already needed. Carried here so the Apps screen does not have to make a second,
     *   separate request for it — every avoided request is a socket a Sunshine-family host does not
     *   have to leak.
     */
    class Success(val apps: List<HostApp>, val runningAppId: String? = null) : AppCatalogResult

    /**
     * The library could not be listed.
     *
     * @property reason which kind of failure, for choosing what to tell the user.
     * @property detail the specific underlying cause — HTTP status, exception type and message,
     *   or what was wrong with the document. Shown verbatim under the empty state.
     */
    class Failure(val reason: AppCatalogFailure, val detail: String) : AppCatalogResult

    /** The apps on success, or an empty list on failure. */
    fun appsOrEmpty(): List<HostApp> = when (this) {
        is Success -> apps
        is Failure -> emptyList()
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
     * draw immediately. Must not throw for ordinary network failures — implementations report them
     * as [AppCatalogResult.Failure] carrying the specific cause.
     *
     * Returning a bare list here was a real defect: it made "this PC lists no games" and "the
     * request failed" the same value, so the screen showed the same dead end for both and a bug
     * report could not tell them apart either.
     */
    suspend fun listApps(host: KnownHost): AppCatalogResult

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

    override suspend fun listApps(host: KnownHost): AppCatalogResult =
        AppCatalogResult.Success(listOf(desktopEntry))

    override suspend fun boxArt(host: KnownHost, appId: String): ByteArray? = null

    override suspend fun quitRunningApp(host: KnownHost): Boolean = false
}
