package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.UnverifiedProtocolConstants

/**
 * The host's `appversion`, `a.b.c.d` (spec §0.3).
 *
 * Everything in the protocol branches on this: which pairing hash to use, which RTSP stream-id
 * targets are valid, which control-message table applies.
 *
 * @property components the dot-separated integers, in order; may be shorter than four.
 */
class AppVersion(val components: List<Int>) {

    /** `AppVersionQuad[0]` — 3, 4, 5 or 7. Modern hosts all report 7. */
    val generation: Int get() = components.getOrElse(0) { 0 }

    /**
     * `APP_VERSION_AT_LEAST(a, b, c)` — lexicographic compare of the first three components.
     */
    fun atLeast(major: Int, minor: Int, patch: Int): Boolean {
        val a = components.getOrElse(0) { 0 }
        if (a != major) return a > major
        val b = components.getOrElse(1) { 0 }
        if (b != minor) return b > minor
        return components.getOrElse(2) { 0 } >= patch
    }

    override fun toString(): String = components.joinToString(".")

    companion object {
        /**
         * Parses `a.b.c.d`, tolerating extra or missing components.
         *
         * @return the parsed version, or `null` when [text] is absent or has no leading integer —
         *   which makes it unusable for generation detection and therefore a fatal `/serverinfo`.
         */
        fun parse(text: String?): AppVersion? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split('.').map { it.trim().toIntOrNull() }
            if (parts.isEmpty() || parts[0] == null) return null
            // Stop at the first non-numeric component rather than discarding the whole string, so a
            // host reporting "7.1.431.0-beta" still yields generation 7.
            val numeric = ArrayList<Int>(parts.size)
            for (part in parts) {
                if (part == null) break
                numeric.add(part)
            }
            return AppVersion(numeric)
        }
    }
}

/** Which family of host software we are talking to (spec §0.3). */
enum class ServerKind {
    /** Genuine NVIDIA GameStream — `<state>` contains `MJOLNIR`. */
    NVIDIA_GFE,

    /** Sunshine, Apollo, and forks — `<state>` starts with `SUNSHINE`. */
    SUNSHINE_FAMILY,

    /** Neither marker present; treat conservatively. */
    UNKNOWN,
}

/**
 * A display mode the host advertises under `<SupportedDisplayMode>` (spec §3.3).
 *
 * Used only to *suggest* resolutions. See
 * [UnverifiedProtocolConstants.DISPLAY_MODES_ARE_AUTHORITATIVE].
 */
class DisplayMode(val width: Int, val height: Int, val refreshRate: Int) {
    override fun toString(): String = "${width}x${height}x$refreshRate"
}

/**
 * The parsed `/serverinfo` document (spec §3.3).
 *
 * Every field is nullable or defaulted because hosts differ in what they emit and a missing
 * element must never be fatal on its own — [appVersion] and [uniqueId] are the only two the
 * caller genuinely requires, and [fromXml] returns `null` when either is absent.
 */
class ServerInfo(
    /** Friendly display name. */
    val hostname: String?,
    /** Generation source. Required. */
    val appVersion: AppVersion,
    /** Informational GFE version; Sunshine reports a synthetic value. */
    val gfeVersion: String?,
    /** The host's own unique id — the primary key for a saved host. Required. */
    val uniqueId: String,
    /** TLS port, defaulted to 47984 when absent or unparseable. */
    val httpsPort: Int,
    /** WAN port, when the host publishes one. */
    val externalPort: Int?,
    /** MAC for Wake-on-LAN, or `null` when the host withheld it (spec §1.4). */
    val macAddress: String?,
    /** The host's LAN address, or `null` when it reported the placeholder (spec §3.3). */
    val localIp: String?,
    /** The host's WAN address, when published. */
    val externalIp: String?,
    /** `0` means HEVC is definitely unavailable — the one reliable codec check (spec §3.3.1). */
    val maxLumaPixelsHevc: Long,
    /** Max H.264 luma pixels, used to sanity-check a requested resolution. */
    val maxLumaPixelsH264: Long,
    /** Raw codec bitfield; a hint only (spec §3.3.1). */
    val serverCodecModeSupport: Int?,
    /** Whether the host believes this client is paired. Weak over plaintext (spec §3.3). */
    val pairStatus: Boolean,
    /** App id currently streaming, or `null` when the host is idle. */
    val currentGameId: String?,
    /** Raw `<state>`; also the Sunshine-vs-GFE discriminator. */
    val state: String?,
    /** Informational GPU name. */
    val gpuType: String?,
    /** Advertised display modes; suggestions only. */
    val displayModes: List<DisplayMode>,
) {
    /** Which host family this is (spec §0.3). */
    val serverKind: ServerKind = when {
        state == null -> ServerKind.UNKNOWN
        state.contains(ProtocolConstants.STATE_MARKER_NVIDIA) -> ServerKind.NVIDIA_GFE
        state.startsWith(ProtocolConstants.STATE_PREFIX_SUNSHINE) -> ServerKind.SUNSHINE_FAMILY
        else -> ServerKind.UNKNOWN
    }

    /** True for genuine NVIDIA GameStream, which needs the SOPS and fps workarounds of spec §3.6. */
    val isNvidiaGfe: Boolean get() = serverKind == ServerKind.NVIDIA_GFE

    /** True when an app is currently streaming on the host. */
    val isBusy: Boolean
        get() = currentGameId != null ||
            state?.endsWith(ProtocolConstants.STATE_SUFFIX_BUSY) == true

    /**
     * Whether the host can encode HEVC.
     *
     * Uses only `MaxLumaPixelsHEVC`, which spec §3.3.1 says is the reliable signal; the codec
     * bitfield is explicitly not trusted for this.
     */
    val supportsHevc: Boolean get() = maxLumaPixelsHevc > 0L

    /**
     * Best-effort codec capability read from `ServerCodecModeSupport`.
     *
     * UNVERIFIED(spec 01 §3.3.1, item 10): the bit assignments are inferred, not documented. This
     * is exposed as a hint for the UI and logged so real hosts teach us the true values; nothing in
     * the protocol path gates on it.
     */
    fun codecHint(flag: Int): Boolean {
        val bits = serverCodecModeSupport ?: return false
        ProtocolLog.unverified(
            ProtocolLog.TAG_HTTP,
            "server-codec-mode-support",
            "reading ServerCodecModeSupport=$bits with inferred bit assignments (spec 01 §3.3.1); " +
                "used as a hint only",
        )
        return bits and flag != 0
    }

    override fun toString(): String =
        "ServerInfo(hostname=$hostname, uniqueId=$uniqueId, appVersion=$appVersion, " +
            "kind=$serverKind, httpsPort=$httpsPort, busy=$isBusy, paired=$pairStatus)"

    companion object {
        /**
         * Maps a `/serverinfo` `<root>` element onto [ServerInfo].
         *
         * Pure: no Android, no I/O, so the element-name contract is directly unit-testable.
         *
         * @return the parsed info, or `null` when `appversion` or `uniqueid` is missing — without
         *   those two we cannot pick a pairing hash or key the host, so the response is unusable.
         */
        fun fromXml(root: XmlNode): ServerInfo? {
            val appVersion = AppVersion.parse(root.textOf("appversion"))
            if (appVersion == null) {
                ProtocolLog.w(ProtocolLog.TAG_HTTP, "/serverinfo has no usable <appversion>")
                return null
            }
            val uniqueId = root.textOf("uniqueid")
            if (uniqueId == null) {
                ProtocolLog.w(ProtocolLog.TAG_HTTP, "/serverinfo has no <uniqueid>")
                return null
            }

            val rawMac = root.textOf("mac")
            val rawLocalIp = root.textOf("LocalIP")
            val rawCurrentGame = root.textOf("currentgame")

            return ServerInfo(
                hostname = root.textOf("hostname"),
                appVersion = appVersion,
                gfeVersion = root.textOf("GfeVersion"),
                uniqueId = uniqueId,
                httpsPort = readHttpsPort(root),
                externalPort = root.intOf("ExternalPort")?.takeIf { it in 1..65535 },
                macAddress = rawMac?.takeIf { !it.equals(ProtocolConstants.MAC_UNKNOWN, true) },
                localIp = rawLocalIp?.takeIf { it != ProtocolConstants.LOCAL_IP_IGNORED },
                externalIp = root.textOf("ExternalIP"),
                maxLumaPixelsHevc = root.longOf("MaxLumaPixelsHEVC") ?: 0L,
                maxLumaPixelsH264 = root.longOf("MaxLumaPixelsH264") ?: 0L,
                serverCodecModeSupport = root.intOf("ServerCodecModeSupport"),
                pairStatus = root.textOf("PairStatus") == "1",
                currentGameId = rawCurrentGame
                    ?.takeIf { it != ProtocolConstants.CURRENT_GAME_IDLE && it.toLongOrNull() != null },
                state = root.textOf("state"),
                gpuType = root.textOf("gputype"),
                displayModes = parseDisplayModes(root),
            )
        }

        /**
         * Reads `<HttpsPort>`, announcing the fallback rather than taking it silently.
         *
         * Every secure request in the app goes to whatever this returns, so a wrong or defaulted
         * value makes *all* HTTPS traffic vanish into a port that will never answer — which is
         * indistinguishable from a broken handshake unless the number is written down somewhere.
         * A silent `?:` was exactly that missing line.
         */
        fun readHttpsPort(root: XmlNode): Int {
            val raw = root.textOf("HttpsPort")
            val parsed = raw?.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT }
            if (parsed != null) return parsed
            ProtocolLog.w(
                ProtocolLog.TAG_HTTP,
                "/serverinfo gave no usable <HttpsPort> (raw=${raw ?: "<absent>"}); " +
                    "falling back to ${ProtocolConstants.DEFAULT_HTTPS_PORT}. Every HTTPS call " +
                    "will go there, so if the host actually listens elsewhere they will all time out.",
            )
            return ProtocolConstants.DEFAULT_HTTPS_PORT
        }

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        private fun parseDisplayModes(root: XmlNode): List<DisplayMode> {
            val container = root.child("SupportedDisplayMode") ?: return emptyList()
            return container.childrenNamed("DisplayMode").mapNotNull { node ->
                val width = node.intOf("Width") ?: return@mapNotNull null
                val height = node.intOf("Height") ?: return@mapNotNull null
                val refresh = node.intOf("RefreshRate") ?: return@mapNotNull null
                if (width <= 0 || height <= 0 || refresh <= 0) null
                else DisplayMode(width, height, refresh)
            }
        }
    }
}

/**
 * One `<App>` from `/applist` (spec §3.4).
 *
 * @property id the host-assigned id. Stored as `Long` because it is an unsigned 32-bit value in a
 *   string and some GFE ids exceed `Int.MAX_VALUE`.
 * @property title the display name.
 * @property hdrSupported whether the host advertises HDR for this title.
 */
class AppListEntry(
    val id: Long,
    val title: String,
    val hdrSupported: Boolean,
    /**
     * The title exactly as the host sent it, including any invisible ordering prefix.
     *
     * Apollo encodes the host's configured order into `<AppTitle>` using zero-width code points, so
     * that a client sorting alphabetically reproduces that order. Sorting on this and displaying
     * [title] is what honours the host's intent without showing the user invisible junk.
     */
    val sortKey: String = title,
) {

    /** True for the host's Desktop entry, which sorts first (spec §3.4). */
    val isDesktop: Boolean get() = title.equals(ProtocolConstants.APP_TITLE_DESKTOP, ignoreCase = true)

    /** True for the placeholder Apollo returns instead of a library the client may not list. */
    val isPermissionDenied: Boolean
        get() = title.equals(ProtocolConstants.APP_TITLE_PERMISSION_DENIED, ignoreCase = true) ||
            id == ProtocolConstants.APP_ID_PERMISSION_DENIED

    override fun toString(): String = "AppListEntry($id, $title)"

    companion object {
        /**
         * Maps an `/applist` `<root>` onto its entries.
         *
         * Entries missing either `<ID>` or `<AppTitle>` are discarded per spec §3.4 — but now
         * loudly. A silently dropped entry and a host with no games produced the identical empty
         * grid, which is exactly the ambiguity that made "connects but shows nothing" impossible to
         * diagnose from a bug report.
         *
         * No `Desktop` entry is ever synthesised — on Sunshine it may genuinely be absent — but a
         * real one is sorted to the front.
         */
        fun listFromXml(root: XmlNode): List<AppListEntry> {
            val nodes = root.childrenNamed("App")
            val entries = ArrayList<AppListEntry>(nodes.size)
            var dropped = 0
            for (node in nodes) {
                val id = node.longOf("ID")
                val rawTitle = node.textOf("AppTitle")
                if (id == null || rawTitle == null) {
                    dropped++
                    ProtocolLog.w(
                        ProtocolLog.TAG_HTTP,
                        "/applist: dropping an <App> with " +
                            "ID=${node.textOf("ID") ?: "<absent>"} and " +
                            "AppTitle=${rawTitle ?: "<absent>"}; its children were " +
                            node.children.map { it.name },
                    )
                    continue
                }
                entries.add(
                    AppListEntry(
                        id = id,
                        title = displayTitle(rawTitle),
                        hdrSupported = node.textOf("IsHdrSupported") == "1",
                        sortKey = rawTitle,
                    ),
                )
            }
            ProtocolLog.i(
                ProtocolLog.TAG_HTTP,
                "/applist parsed: ${nodes.size} <App> elements, ${entries.size} usable" +
                    (if (dropped > 0) ", $dropped dropped" else ""),
            )
            if (nodes.isEmpty()) {
                ProtocolLog.w(
                    ProtocolLog.TAG_HTTP,
                    "/applist contained no <App> elements at all; <root> children were " +
                        root.children.map { it.name },
                )
            }
            return entries.sortedWith(
                compareByDescending<AppListEntry> { it.isDesktop }.thenBy { it.sortKey.lowercase() },
            )
        }

        /**
         * True when [entries] is Apollo's "you may not list applications" placeholder.
         *
         * Apollo answers `status_code=200` with exactly one entry rather than an error, so this is
         * the only way to tell it apart from a real one-game library.
         */
        fun isPermissionDeniedPlaceholder(entries: List<AppListEntry>): Boolean =
            entries.size == 1 && entries[0].isPermissionDenied

        /**
         * Strips Apollo's invisible ordering prefix for display.
         *
         * Falls back to the raw text when a title turns out to be *nothing but* padding: an empty
         * name would then be dropped by the caller, turning a host quirk into a missing game.
         */
        fun displayTitle(raw: String): String {
            val stripped = raw.trimStart(
                ProtocolConstants.APP_TITLE_ORDER_PAD_ZERO,
                ProtocolConstants.APP_TITLE_ORDER_PAD_ONE,
            )
            return if (stripped.isEmpty()) raw else stripped
        }
    }
}
