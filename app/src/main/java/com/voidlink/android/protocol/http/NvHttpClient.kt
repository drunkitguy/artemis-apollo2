package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.UnverifiedProtocolConstants
import com.voidlink.android.protocol.crypto.ClientIdentity
import com.voidlink.android.protocol.crypto.IdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The NVHTTP control API (spec §3).
 *
 * Every request is `GET /<endpoint>?<params>` returning a small XML document, sent either over
 * plaintext port 47989 or over port 47984 with our client certificate and the host's certificate
 * pinned. Which transport each endpoint uses is fixed by spec §3.1 and encoded in the method
 * names here: `…Plain` versus `…Secure`.
 *
 * All calls suspend on [Dispatchers.IO] and are genuinely cancellable — cancelling the coroutine
 * disconnects the underlying socket, which is what lets the user back out of a pairing attempt
 * that is blocked waiting for them to type a PIN into the host (spec §4.8).
 *
 * @param identityStore supplies the client certificate, key and `uniqueid`.
 * @param trustStore supplies the pinned server certificate for HTTPS calls.
 */
class NvHttpClient(
    private val identityStore: IdentityStore,
    private val trustStore: HostTrustStore,
) {

    /**
     * TLS versions to offer a particular host, when the default list turned out not to work.
     *
     * A memory cache in front of [HostTrustStore.tlsProtocols], which persists it. Keeping this
     * only in memory was a real defect: the self-test that discovers a working configuration runs
     * during pairing and nowhere else, so after the process restarted the knowledge was gone and
     * the first secure request stalled again — "it worked, then it stopped".
     */
    private val tlsProtocolOverrides = ConcurrentHashMap<String, List<String>>()

    /**
     * One lock per `host:port` we make secure requests to.
     *
     * A GameStream host runs its HTTPS listener on a single thread. Two overlapping secure requests
     * therefore do not run concurrently on the host — the second waits, and if it waits longer than
     * our read timeout it fails with a bare `SocketTimeoutException` that looks exactly like a dead
     * host. A grid of box-art tiles, or the 20-second background probe landing on top of a
     * user-initiated `/applist`, produces precisely that. Issuing them one at a time is both
     * honest about what the host can do and strictly faster than timing out.
     */
    private val httpsGates = ConcurrentHashMap<String, Mutex>()

    /** Distinguishes one request from another in the log, so an unclosed connection is visible. */
    private val exchangeCounter = AtomicLong(0)

    /** Hosts the TLS self-test has already run against; see [diagnoseTls] for why it runs once. */
    private val diagnosedHosts = ConcurrentHashMap<String, Boolean>()

    private fun httpsGateFor(url: String): Mutex =
        httpsGates.getOrPut(authorityOf(url)) { Mutex() }

    /**
     * Adopts [protocols] for every future HTTPS call to [hostKey].
     *
     * @param protocols the working list, or `null` to go back to the default.
     */
    suspend fun rememberTlsProtocols(hostKey: String, protocols: List<String>?) {
        if (protocols.isNullOrEmpty()) {
            tlsProtocolOverrides.remove(hostKey)
            return
        }
        val previous = tlsProtocolOverrides.put(hostKey, protocols)
        // Persisted, not just cached: this must survive the process, or the next launch repeats the
        // stall that discovering it was meant to end.
        trustStore.storeTlsProtocols(hostKey, protocols)
        if (previous != protocols) {
            ProtocolLog.i(
                ProtocolLog.TAG_TLS,
                "Using $protocols for every HTTPS call to $hostKey from now on, instead of the " +
                    "platform's own default list",
            )
        }
    }

    /**
     * The TLS versions to offer [hostKey], memory cache first and persisted store behind it.
     */
    private suspend fun protocolsFor(hostKey: String): List<String> {
        tlsProtocolOverrides[hostKey]?.let { return it }
        // Empty means "leave the platform default alone", which is what the reference client does
        // and therefore our default too. A non-empty list only ever comes from a self-test that
        // found this particular host needs one.
        val stored = trustStore.tlsProtocols(hostKey) ?: return emptyList()
        tlsProtocolOverrides[hostKey] = stored
        ProtocolLog.i(
            ProtocolLog.TAG_TLS,
            "Restored a working TLS configuration for $hostKey from a previous session: $stored",
        )
        return stored
    }

    /**
     * Runs the TLS reachability self-test against a host's HTTPS port (see [TlsProbe]).
     *
     * Called when an HTTPS request fails in a way that says nothing — a bare read timeout — because
     * that is precisely when we cannot tell a wrong port from a wedged service from a stalled
     * handshake. If the self-test finds a narrower TLS configuration that does work, it is adopted
     * for this host immediately, so the very next request uses it.
     *
     * @return the report, or `null` when we hold no pinned certificate or no identity and therefore
     *   cannot form a meaningful handshake to test.
     */
    suspend fun diagnoseTls(
        hostKey: String,
        address: HostAddress,
        httpsPort: Int,
    ): TlsProbeReport? {
        // Strictly last resort, and at most once per host per process. The self-test opens several
        // connections — including deliberately failing ones — and a Sunshine-family host leaks a
        // socket per connection (they pile up in CLOSE_WAIT until its process restarts). Running it
        // on every failure would make the self-test a cause of the problem it exists to explain.
        if (diagnosedHosts.putIfAbsent(hostKey, true) != null) {
            ProtocolLog.i(
                ProtocolLog.TAG_TLS,
                "Skipping the TLS self-test for $hostKey: already run once this session, and each " +
                    "run costs the host several connections it does not reclaim",
            )
            return null
        }
        val identity = identityOrFail() ?: return null
        val serverCertificate = trustStore.certificate(hostKey) ?: return null
        val report = TlsProbe.diagnose(address, httpsPort, identity, serverCertificate)
        // Only worth remembering when it is actually narrower than the platform default; an empty
        // working list means the default was fine and there is nothing to override.
        if (report.tlsWorks && !report.workingProtocols.isNullOrEmpty()) {
            rememberTlsProtocols(hostKey, report.workingProtocols)
        }
        return report
    }

    // -- /serverinfo ---------------------------------------------------------------------------

    /**
     * `/serverinfo` over plaintext HTTP (spec §3.3).
     *
     * Always available, paired or not. `PairStatus` in the result reflects only weak
     * identification over this transport; [serverInfoSecure] is the authoritative pairing check.
     *
     * @param address the host and its plaintext port.
     * @param timeoutMs connect and read timeout; callers pass a short one for a host they believe
     *   is offline so a dead machine cannot stall the list (spec §1.3).
     */
    suspend fun serverInfoPlain(
        address: HostAddress,
        timeoutMs: Int = ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS,
    ): NvHttpResult<ServerInfo> {
        val identity = identityOrFail() ?: return identityUnavailable()
        val url = buildUrl(
            scheme = SCHEME_HTTP,
            address = address,
            port = address.port,
            path = ProtocolConstants.PATH_SERVER_INFO,
            params = universalParams(identity),
        )
        return requestXml(url, ProtocolConstants.PATH_SERVER_INFO, timeoutMs, timeoutMs, null)
            .mapCatchingRoot(ProtocolConstants.PATH_SERVER_INFO) { root ->
                ServerInfo.fromXml(root)
            }
    }

    /**
     * `/serverinfo` over pinned HTTPS (spec §3.3).
     *
     * Succeeding here is the definition of "genuinely paired": it proves the host still accepts
     * our client certificate. Returns [NvHttpResult.NotPaired] without touching the network when
     * we hold no pin for the host.
     *
     * @param hostKey stable per-host identifier used to look up the pinned certificate.
     * @param httpsPort the port from `<HttpsPort>`, or the 47984 default.
     */
    suspend fun serverInfoSecure(
        hostKey: String,
        address: HostAddress,
        httpsPort: Int = ProtocolConstants.DEFAULT_HTTPS_PORT,
        timeoutMs: Int = ProtocolConstants.PROBE_TIMEOUT_ONLINE_MS,
        trace: String? = null,
    ): NvHttpResult<ServerInfo> = secureRequest(
        hostKey = hostKey,
        address = address,
        httpsPort = httpsPort,
        path = ProtocolConstants.PATH_SERVER_INFO,
        endpointParams = emptyList(),
        connectTimeoutMs = timeoutMs,
        readTimeoutMs = timeoutMs,
        trace = trace,
    ).mapCatchingRoot(ProtocolConstants.PATH_SERVER_INFO) { root -> ServerInfo.fromXml(root) }

    // -- /applist, /appasset, /cancel ----------------------------------------------------------

    /**
     * `/applist` over pinned HTTPS (spec §3.4).
     *
     * Entries missing an id or a title are dropped, and a real `Desktop` entry sorts first — but
     * one is never synthesised, because on Sunshine it may genuinely not exist.
     */
    suspend fun appList(
        hostKey: String,
        address: HostAddress,
        httpsPort: Int = ProtocolConstants.DEFAULT_HTTPS_PORT,
    ): NvHttpResult<List<AppListEntry>> = secureRequest(
        hostKey = hostKey,
        address = address,
        httpsPort = httpsPort,
        path = ProtocolConstants.PATH_APP_LIST,
        endpointParams = emptyList(),
        connectTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
        readTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
        // Traced like a pairing phase: an empty grid is as opaque as a failed handshake was, and
        // the request URL, status and body are what separate "the host listed nothing" from
        // "we could not read what it listed".
        trace = APP_LIST_LABEL,
        traceTag = ProtocolLog.TAG_HTTP,
    ).mapCatchingRoot(ProtocolConstants.PATH_APP_LIST) { root -> AppListEntry.listFromXml(root) }

    /**
     * `/appasset` box art over pinned HTTPS (spec §3.5).
     *
     * The body is a PNG, not XML. A host with no art may answer 404, an empty body, or a
     * placeholder, and all three are reported as [NvHttpResult.Success] with a `null` value so the
     * caller simply falls back to a generated tile.
     */
    suspend fun boxArt(
        hostKey: String,
        address: HostAddress,
        appId: Long,
        httpsPort: Int = ProtocolConstants.DEFAULT_HTTPS_PORT,
    ): NvHttpResult<ByteArray?> {
        val identity = identityOrFail() ?: return identityUnavailable()
        val serverCertificate = trustStore.certificate(hostKey) ?: return NvHttpResult.NotPaired
        val url = buildUrl(
            scheme = SCHEME_HTTPS,
            address = address,
            port = httpsPort,
            path = ProtocolConstants.PATH_APP_ASSET,
            params = universalParams(identity) + listOf(
                "appid" to appId.toString(),
                "AssetType" to ProtocolConstants.ASSET_TYPE_BOX_ART.toString(),
                "AssetIdx" to ProtocolConstants.ASSET_INDEX_PRIMARY.toString(),
            ),
        )
        return when (
            val raw = execute(
                url = url,
                endpoint = ProtocolConstants.PATH_APP_ASSET,
                connectTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
                readTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
                tls = TlsSetup(identity, serverCertificate, protocolsFor(hostKey)),
            )
        ) {
            is NvHttpResult.Success -> {
                val body = raw.value.body
                if (raw.value.code == HTTP_OK && looksLikePng(body)) {
                    NvHttpResult.Success(body)
                } else {
                    ProtocolLog.d(
                        ProtocolLog.TAG_HTTP,
                        "No box art for app $appId (http ${raw.value.code}, ${body.size} bytes)",
                    )
                    NvHttpResult.Success(null)
                }
            }
            is NvHttpResult.HostError -> raw
            is NvHttpResult.Malformed -> raw
            is NvHttpResult.TransportError -> raw
            is NvHttpResult.TlsRejected -> raw
            NvHttpResult.NotPaired -> NvHttpResult.NotPaired
        }
    }

    /**
     * `/cancel` — quit the app currently running on the host (spec §3.8).
     *
     * @return whether the host confirmed the quit; `false` commonly means another client owns the
     *   session.
     */
    suspend fun cancel(
        hostKey: String,
        address: HostAddress,
        httpsPort: Int = ProtocolConstants.DEFAULT_HTTPS_PORT,
    ): NvHttpResult<Boolean> = secureRequest(
        hostKey = hostKey,
        address = address,
        httpsPort = httpsPort,
        path = ProtocolConstants.PATH_CANCEL,
        endpointParams = emptyList(),
        connectTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
        readTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
    ).mapCatchingRoot(ProtocolConstants.PATH_CANCEL) { root ->
        (root.textOf("cancel")?.toLongOrNull() ?: 0L) != 0L
    }

    // -- /launch and /resume -------------------------------------------------------------------

    /**
     * `/launch` over pinned HTTPS (spec §3.6).
     *
     * Uses the long [ProtocolConstants.LAUNCH_TIMEOUT_MS] read timeout: starting a game can take
     * tens of seconds and a short timeout would abandon a launch that is actually working.
     */
    suspend fun launch(
        hostKey: String,
        address: HostAddress,
        request: LaunchRequest,
        isNvidiaGfe: Boolean,
        httpsPort: Int = ProtocolConstants.DEFAULT_HTTPS_PORT,
    ): NvHttpResult<LaunchResponse> = secureRequest(
        hostKey = hostKey,
        address = address,
        httpsPort = httpsPort,
        path = ProtocolConstants.PATH_LAUNCH,
        endpointParams = request.toQueryParams(isNvidiaGfe),
        connectTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
        readTimeoutMs = ProtocolConstants.LAUNCH_TIMEOUT_MS,
    ).mapCatchingRoot(ProtocolConstants.PATH_LAUNCH) { root ->
        LaunchResponse.fromXml(root, "gamesession")
    }

    /**
     * `/resume` over pinned HTTPS (spec §3.7).
     *
     * Same parameter set as [launch]; success is reported in `<resume>` rather than
     * `<gamesession>`, and the caller must have regenerated `rikey`/`rikeyid` because a resume is
     * a brand new streaming session even though the game keeps running.
     */
    suspend fun resume(
        hostKey: String,
        address: HostAddress,
        request: LaunchRequest,
        isNvidiaGfe: Boolean,
        httpsPort: Int = ProtocolConstants.DEFAULT_HTTPS_PORT,
    ): NvHttpResult<LaunchResponse> = secureRequest(
        hostKey = hostKey,
        address = address,
        httpsPort = httpsPort,
        path = ProtocolConstants.PATH_RESUME,
        endpointParams = request.toQueryParams(isNvidiaGfe),
        connectTimeoutMs = ProtocolConstants.DEFAULT_REQUEST_TIMEOUT_MS,
        readTimeoutMs = ProtocolConstants.LAUNCH_TIMEOUT_MS,
    ).mapCatchingRoot(ProtocolConstants.PATH_RESUME) { root ->
        LaunchResponse.fromXml(root, "resume")
    }

    // -- /pair and /unpair ---------------------------------------------------------------------

    /**
     * One phase of `/pair` over plaintext HTTP (spec §4).
     *
     * The `devicename` / `updateState` prefix and the universal parameters are added here so no
     * caller can forget them; [phaseParams] carries only the phase-specific values.
     *
     * @param phaseLabel human-readable phase name; it prefixes every log line and every failure
     *   detail for this call, so a `VL.Pair` logcat filter reads as a transcript.
     * @param readTimeoutMs `0` for phase 1, where the host blocks until the user types the PIN.
     * @return the response `<root>` element on success, for the caller to pull phase values from.
     */
    suspend fun pairPlain(
        address: HostAddress,
        phaseLabel: String,
        phaseParams: List<Pair<String, String>>,
        readTimeoutMs: Int = ProtocolConstants.PAIRING_PHASE_TIMEOUT_MS,
        connectTimeoutMs: Int = ProtocolConstants.PAIRING_CONNECT_TIMEOUT_MS,
    ): NvHttpResult<XmlNode> {
        val identity = identityOrFail() ?: return identityUnavailable()
        val url = buildUrl(
            scheme = SCHEME_HTTP,
            address = address,
            port = address.port,
            path = ProtocolConstants.PATH_PAIR,
            params = pairingPrefixParams() + phaseParams + universalParams(identity),
        )
        return requestXml(
            url = url,
            endpoint = phaseLabel,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            tls = null,
            trace = phaseLabel,
        )
    }

    /**
     * The phase-5 `phrase=pairchallenge` call over pinned HTTPS (spec §4.7).
     *
     * The host does not consider us paired until one client-certificate HTTPS request succeeds, so
     * this call is what actually completes pairing rather than merely confirming it.
     *
     * Its timeouts are deliberately much longer than the plaintext phases': this is the first TLS
     * connection ever made to the host, and on `HttpURLConnection` the read timeout also covers the
     * handshake — during which the host walks its entire client list to verify our certificate.
     */
    suspend fun pairChallengeSecure(
        hostKey: String,
        address: HostAddress,
        httpsPort: Int,
        connectTimeoutMs: Int = ProtocolConstants.PAIRING_PHASE5_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = ProtocolConstants.PAIRING_PHASE5_READ_TIMEOUT_MS,
    ): NvHttpResult<XmlNode> {
        val identity = identityOrFail() ?: return identityUnavailable()
        val serverCertificate = trustStore.certificate(hostKey) ?: return NvHttpResult.NotPaired
        val url = buildUrl(
            scheme = SCHEME_HTTPS,
            address = address,
            port = httpsPort,
            path = ProtocolConstants.PATH_PAIR,
            params = pairingPrefixParams() +
                listOf("phrase" to "pairchallenge") +
                universalParams(identity),
        )
        ProtocolLog.i(
            ProtocolLog.TAG_PAIR,
            "phase 5 (pairchallenge): presenting client cert " +
                "subject=${identity.certificate.subjectX500Principal.name}, " +
                "pinned host cert subject=${serverCertificate.subjectX500Principal.name}, " +
                "connectTimeout=${connectTimeoutMs}ms readTimeout=${readTimeoutMs}ms",
        )
        return requestXml(
            url = url,
            endpoint = PHASE_5_LABEL,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            tls = TlsSetup(identity, serverCertificate, protocolsFor(hostKey)),
            trace = PHASE_5_LABEL,
        )
    }

    /**
     * `/unpair` over plaintext HTTP (spec §3.9).
     *
     * Also the cleanup after a pairing attempt that failed *before the host accepted us* — leaving a
     * half-finished pairing on the host wedges every subsequent try. It is deliberately **not**
     * called once the handshake has reached its final phase; see `PairingEngine`.
     *
     * Note that the Sunshine family serves only `/serverinfo` and `/pair` on the plaintext port, so
     * this answers 404 there and the call is a no-op. It still matters for GFE, and the 404 is
     * harmless, so it is left in place rather than being made host-kind-dependent.
     */
    suspend fun unpairPlain(address: HostAddress): NvHttpResult<Unit> {
        val identity = identityOrFail() ?: return identityUnavailable()
        val url = buildUrl(
            scheme = SCHEME_HTTP,
            address = address,
            port = address.port,
            path = ProtocolConstants.PATH_UNPAIR,
            params = universalParams(identity),
        )
        return when (
            val result = requestXml(
                url = url,
                endpoint = ProtocolConstants.PATH_UNPAIR,
                connectTimeoutMs = ProtocolConstants.PAIRING_CONNECT_TIMEOUT_MS,
                readTimeoutMs = ProtocolConstants.PAIRING_PHASE_TIMEOUT_MS,
                tls = null,
            )
        ) {
            is NvHttpResult.Success -> NvHttpResult.Success(Unit)
            is NvHttpResult.HostError -> result
            is NvHttpResult.Malformed -> result
            is NvHttpResult.TransportError -> result
            is NvHttpResult.TlsRejected -> result
            NvHttpResult.NotPaired -> NvHttpResult.NotPaired
        }
    }

    // -- Plumbing ------------------------------------------------------------------------------

    /**
     * Issues an HTTPS request with the universal parameters and the pinned certificate applied.
     */
    private suspend fun secureRequest(
        hostKey: String,
        address: HostAddress,
        httpsPort: Int,
        path: String,
        endpointParams: List<Pair<String, String>>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        trace: String? = null,
        traceTag: String = ProtocolLog.TAG_PAIR,
    ): NvHttpResult<XmlNode> {
        val identity = identityOrFail() ?: return identityUnavailable()
        val serverCertificate = trustStore.certificate(hostKey) ?: return NvHttpResult.NotPaired
        val url = buildUrl(
            scheme = SCHEME_HTTPS,
            address = address,
            port = httpsPort,
            path = path,
            params = universalParams(identity) + endpointParams,
        )
        return requestXml(
            url = url,
            endpoint = path,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            tls = TlsSetup(identity, serverCertificate, protocolsFor(hostKey)),
            trace = trace,
            traceTag = traceTag,
        )
    }

    private suspend fun requestXml(
        url: String,
        endpoint: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        tls: TlsSetup?,
        trace: String? = null,
        traceTag: String = ProtocolLog.TAG_PAIR,
    ): NvHttpResult<XmlNode> =
        when (val raw = execute(url, endpoint, connectTimeoutMs, readTimeoutMs, tls, trace, traceTag)) {
            is NvHttpResult.Success -> {
                val text = raw.value.body.toString(Charsets.UTF_8)
                when (val parsed = NvXml.parseResponse(text, endpoint)) {
                    is XmlResponse.Ok -> NvHttpResult.Success(parsed.root)
                    is XmlResponse.HostError -> {
                        if (trace != null) {
                            ProtocolLog.w(
                                traceTag,
                                "$trace: the host reported status_code=${parsed.statusCode} " +
                                    "\"${parsed.statusMessage.orEmpty()}\"",
                            )
                        }
                        NvHttpResult.HostError(parsed.statusCode, parsed.statusMessage)
                    }
                    is XmlResponse.Malformed -> {
                        if (trace != null) {
                            ProtocolLog.w(
                                traceTag,
                                "$trace: unusable body (HTTP ${raw.value.code}): ${parsed.reason}",
                            )
                        }
                        if (raw.value.code != HTTP_OK) {
                            NvHttpResult.TransportError(
                                "$endpoint: HTTP ${raw.value.code} with an unusable body " +
                                    "(${parsed.reason})",
                            )
                        } else {
                            NvHttpResult.Malformed(parsed.reason)
                        }
                    }
                }
            }
            is NvHttpResult.HostError -> raw
            is NvHttpResult.Malformed -> raw
            is NvHttpResult.TransportError -> raw
            is NvHttpResult.TlsRejected -> raw
            NvHttpResult.NotPaired -> NvHttpResult.NotPaired
        }

    /**
     * Performs the actual HTTP exchange.
     *
     * Cancellation is wired through a completion handler that disconnects the socket: a blocking
     * read on a connection with no read timeout — pairing phase 1 — cannot be interrupted any
     * other way, and leaving it running would keep the host's PIN prompt open after the user has
     * walked away.
     */
    private suspend fun execute(
        url: String,
        endpoint: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        tls: TlsSetup?,
        trace: String? = null,
        traceTag: String = ProtocolLog.TAG_PAIR,
    ): NvHttpResult<RawResponse> {
        // Requests to one listener are issued one at a time. A GameStream host serves each of its
        // two listeners on a single thread, so overlapping requests do not run concurrently over
        // there — the second waits, and if it waits past our read timeout it fails with a bare
        // `SocketTimeoutException` indistinguishable from a dead host. A grid of box-art tiles, or
        // the background probe landing on top of a user-initiated /applist, produces exactly that.
        //
        // The gate is keyed by `host:port`, so the plaintext and secure listeners get their own
        // locks rather than blocking each other.
        //
        // The one exemption is a request with no read timeout: that is pairing phase 1, which
        // blocks for as long as the user takes to type a PIN into the host. Holding a lock for
        // minutes would freeze every other operation against that machine, so it goes ungated.
        val gate = if (readTimeoutMs > 0) gateFor(url) else null
        if (gate == null) {
            return executeNow(url, endpoint, connectTimeoutMs, readTimeoutMs, tls, trace, traceTag)
        }
        val waitedFrom = System.currentTimeMillis()
        return gate.withLock {
            val waited = System.currentTimeMillis() - waitedFrom
            if (waited > GATE_WAIT_LOG_THRESHOLD_MS) {
                ProtocolLog.i(
                    ProtocolLog.TAG_HTTP,
                    "$endpoint waited ${waited}ms for the secure channel to ${authorityOf(url)} " +
                        "to free up; the host serves HTTPS on one thread, so requests to it are " +
                        "issued one at a time",
                )
            }
            executeNow(url, endpoint, connectTimeoutMs, readTimeoutMs, tls, trace, traceTag)
        }
    }

    private suspend fun executeNow(
        url: String,
        endpoint: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        tls: TlsSetup?,
        trace: String?,
        traceTag: String,
    ): NvHttpResult<RawResponse> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var cancellationHandle: DisposableHandle? = null
        val startedAt = System.currentTimeMillis()
        val exchange = exchangeCounter.incrementAndGet()
        if (trace != null) {
            ProtocolLog.i(traceTag, "$trace [#$exchange] -> GET ${redactUrl(url)}")
        }
        try {
            val opened = URL(url).openConnection() as HttpURLConnection
            connection = opened
            ProtocolLog.d(
                ProtocolLog.TAG_HTTP,
                "[#$exchange] opened a new ${if (tls != null) "TLS" else "plaintext"} connection " +
                    "to ${authorityOf(url)} for $endpoint",
            )
            if (tls != null) {
                if (opened !is HttpsURLConnection) {
                    return@withContext NvHttpResult.TransportError(
                        "$endpoint: expected an HTTPS connection",
                    )
                }
                val sslContext = PinnedTls.context(tls.identity, tls.serverCertificate)
                opened.sslSocketFactory = PinnedTls.socketFactory(sslContext, tls.protocols)
                opened.hostnameVerifier = PinnedTls.AnyHostnameVerifier
                if (trace != null) {
                    ProtocolLog.i(
                        ProtocolLog.TAG_TLS,
                        "$trace [#$exchange]: opening TLS to ${opened.url.host}:${opened.url.port} " +
                            "offering ${tls.protocols}",
                    )
                }
            }
            opened.requestMethod = "GET"
            opened.connectTimeout = connectTimeoutMs
            opened.readTimeout = readTimeoutMs
            opened.useCaches = false
            opened.instanceFollowRedirects = false
            opened.doInput = true
            // No `Connection: close`. The reference client — moonlight-android, which Artemis
            // forks and which Apollo is developed against — does not send it, and matching what
            // demonstrably works matters more than what looks tidy. It reaches the same end by
            // configuring OkHttp with `ConnectionPool(0, 1ms)`: connections are never *reused*, but
            // the server is never *told* to close either. Our `disconnect()` below is the exact
            // equivalent, so the bytes on the wire now match the working client's.

            cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) runCatching { opened.disconnect() }
            }

            val code = opened.responseCode
            // The body must be drained on *every* status, not just success: a response left unread
            // holds its socket open regardless of what disconnect() is asked to do afterwards.
            val stream: InputStream? = if (code in 200..299) {
                runCatching { opened.inputStream }.getOrNull()
                    ?: runCatching { opened.errorStream }.getOrNull()
            } else {
                runCatching { opened.errorStream }.getOrNull()
                    ?: runCatching { opened.inputStream }.getOrNull()
            }
            val body = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            ensureActive()
            ProtocolLog.d(ProtocolLog.TAG_HTTP, "$endpoint -> HTTP $code, ${body.size} bytes")
            if (trace != null) {
                ProtocolLog.i(
                    traceTag,
                    "$trace [#$exchange] <- HTTP $code, ${body.size} bytes in " +
                        "${System.currentTimeMillis() - startedAt}ms: ${bodyPreview(body)}",
                )
            }
            NvHttpResult.Success(RawResponse(code, body))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ProtocolLog.d(ProtocolLog.TAG_HTTP, "$endpoint failed: ${t.javaClass.simpleName}: ${t.message}")
            // Both the exception type and its message: "Read timed out" alone does not say whether
            // a socket read or a TLS handshake gave up, and that distinction is the difference
            // between "the host is refusing us" and "we did not wait long enough".
            val message = describeFailure(t)
            if (trace != null) {
                ProtocolLog.w(
                    traceTag,
                    "$trace [#$exchange] <- FAILED after ${System.currentTimeMillis() - startedAt}ms: $message",
                    t,
                )
            }
            // A failed handshake is reported separately from a failed connection. Only the former
            // is evidence about pairing: a host that has forgotten our certificate aborts the
            // handshake, whereas a timeout means the answer is simply unknown this time.
            if (t is SSLHandshakeException || t is SSLPeerUnverifiedException) {
                NvHttpResult.TlsRejected(message, t)
            } else {
                NvHttpResult.TransportError(message, t)
            }
        } finally {
            cancellationHandle?.dispose()
            // Unconditional, on every path including cancellation. A connection left open against a
            // single-threaded host is not a leak we pay for later — it is the next request timing
            // out. The log line is the pair to the "opened" one above, so a missing close is
            // visible in logcat rather than only in the symptom.
            val closed = runCatching { connection?.disconnect() }.isSuccess
            ProtocolLog.d(
                ProtocolLog.TAG_HTTP,
                "[#$exchange] disconnected from ${authorityOf(url)} after " +
                    "${System.currentTimeMillis() - startedAt}ms" +
                    (if (closed) "" else " (disconnect threw)"),
            )
        }
    }

    private suspend fun identityOrFail(): ClientIdentity? = try {
        identityStore.identity()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        ProtocolLog.e(ProtocolLog.TAG_HTTP, "Client identity unavailable", t)
        null
    }

    private fun <T> identityUnavailable(): NvHttpResult<T> =
        NvHttpResult.TransportError("client identity unavailable")

    /** `uniqueid` plus a fresh per-request nonce UUID (spec §3.1). */
    private fun universalParams(identity: ClientIdentity): List<Pair<String, String>> = listOf(
        ProtocolConstants.PARAM_UNIQUE_ID to identity.uniqueId,
        ProtocolConstants.PARAM_UUID to UUID.randomUUID().toString(),
    )

    /** The `devicename` / `updateState` prefix carried by every `/pair` call (spec §4.0). */
    private fun pairingPrefixParams(): List<Pair<String, String>> {
        ProtocolLog.unverified(
            ProtocolLog.TAG_PAIR,
            "pair-devicename",
            "sending devicename=${UnverifiedProtocolConstants.PAIRING_DEVICE_NAME}" +
                " verbatim; no host is known to validate it (spec 01 §4.0, item 19)",
        )
        return listOf(
            "devicename" to UnverifiedProtocolConstants.PAIRING_DEVICE_NAME,
            "updateState" to UnverifiedProtocolConstants.PAIRING_UPDATE_STATE,
        )
    }

    private class TlsSetup(
        val identity: ClientIdentity,
        val serverCertificate: X509Certificate,
        val protocols: List<String>,
    )

    private class RawResponse(val code: Int, val body: ByteArray)

    companion object {
        private const val SCHEME_HTTP = "http"
        private const val SCHEME_HTTPS = "https"
        private const val HTTP_OK = 200

        /** Trace label of the HTTPS `pairchallenge` leg; also the log prefix a bug report is grepped for. */
        const val PHASE_5_LABEL: String = "phase 5 (HTTPS pairchallenge)"

        /** Trace label of the confirmation `/serverinfo` that settles an inconclusive phase 5. */
        const val PHASE_5_CONFIRM_LABEL: String = "phase 5 confirm (pinned HTTPS /serverinfo)"

        /** Trace label of `/applist`; the log prefix to grep for an empty-library report. */
        const val APP_LIST_LABEL: String = "applist (pinned HTTPS)"

        /** Box art is the largest legitimate body; 8 MB is far above any real one. */
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        /** How much of a response body a trace line quotes. Every NVHTTP document fits easily. */
        const val BODY_PREVIEW_CHARS: Int = 512

        /** How much of a long query value a trace line quotes before eliding the rest. */
        const val TRACE_VALUE_CHARS: Int = 24

        /** Only log a queue wait when it is long enough to be worth explaining. */
        private const val GATE_WAIT_LOG_THRESHOLD_MS = 250L

        /**
         * The `host:port` a URL addresses, used as the key of the per-host secure-request lock.
         *
         * Parsed by hand rather than with [URL] so it cannot throw: this runs on the failure path
         * too, and a malformed URL must not turn into a second, more confusing exception.
         */
        fun authorityOf(url: String): String {
            val afterScheme = url.substringAfter("://", url)
            return afterScheme.substringBefore('/').substringBefore('?')
        }

        /**
         * Query parameters whose values are derived from the PIN and must never reach a log.
         *
         * Everything else in a `/pair` URL is either public (`devicename`, `phrase`) or a random
         * nonce that is useless on its own, and seeing it is the whole point of the transcript.
         */
        private val PIN_DERIVED_PARAMS = setOf(
            "clientchallenge",
            "serverchallengeresp",
            "clientpairingsecret",
        )

        /**
         * Renders [url] for a log line.
         *
         * PIN-derived values are replaced outright; merely long ones (the hex certificate) are
         * truncated so the line stays readable, while still showing enough to spot an empty or
         * obviously wrong value.
         */
        fun redactUrl(url: String): String {
            val split = url.indexOf('?')
            if (split < 0) return url
            val base = url.substring(0, split)
            val query = url.substring(split + 1)
            if (query.isEmpty()) return url
            val rendered = query.split('&').joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) return@joinToString pair
                val key = pair.substring(0, eq)
                val value = pair.substring(eq + 1)
                when {
                    key in PIN_DERIVED_PARAMS -> "$key=<redacted:${value.length} chars>"
                    value.length > TRACE_VALUE_CHARS ->
                        "$key=${value.take(TRACE_VALUE_CHARS)}…<${value.length} chars>"
                    else -> "$key=$value"
                }
            }
            return "$base?$rendered"
        }

        /**
         * A one-line, bounded rendering of a response body for a log.
         *
         * Newlines are collapsed so one response is one logcat line, which is what makes the
         * transcript greppable.
         */
        fun bodyPreview(body: ByteArray): String {
            if (body.isEmpty()) return "<empty body>"
            val text = body.toString(Charsets.UTF_8)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
            if (text.isEmpty()) return "<${body.size} bytes, no printable text>"
            return if (text.length <= BODY_PREVIEW_CHARS) {
                text
            } else {
                text.take(BODY_PREVIEW_CHARS) + "…<truncated, ${body.size} bytes total>"
            }
        }

        /**
         * Names a thrown failure in a way a user can paste into a bug report.
         *
         * The type matters as much as the message: `SocketTimeoutException: Read timed out` and
         * `SSLHandshakeException: Read timed out` mean completely different things about whether
         * the host is refusing us, and a bare message loses that.
         */
        fun describeFailure(t: Throwable): String {
            val head = nameAndMessage(t)
            val cause = t.cause
            return if (cause == null || cause === t) {
                head
            } else {
                "$head (caused by ${nameAndMessage(cause)})"
            }
        }

        private fun nameAndMessage(t: Throwable): String {
            val type = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
            val message = t.message
            return if (message.isNullOrBlank()) type else "$type: $message"
        }

        private val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        )

        /**
         * Composes a request URL.
         *
         * Values are percent-encoded: certificate hex is safe by construction, but a future
         * parameter with a reserved character must not be able to smuggle extra query fields.
         */
        fun buildUrl(
            scheme: String,
            address: HostAddress,
            port: Int,
            path: String,
            params: List<Pair<String, String>>,
        ): String {
            val query = params.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}"
            }
            return "$scheme://${address.authority(port)}/$path?$query"
        }

        /** True when [body] starts with the PNG signature. */
        fun looksLikePng(body: ByteArray): Boolean {
            if (body.size < PNG_MAGIC.size) return false
            for (index in PNG_MAGIC.indices) {
                if (body[index] != PNG_MAGIC[index]) return false
            }
            return true
        }

        /**
         * Reads at most [limit] bytes.
         *
         * A bounded read rather than `readBytes()`: the body is chosen by a machine we do not
         * control, and an unbounded read of a hostile or broken response is an out-of-memory
         * crash waiting to happen.
         */
        private fun readBounded(stream: InputStream, limit: Int): ByteArray {
            val buffer = ByteArray(8 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < limit) {
                val read = stream.read(buffer, 0, minOf(buffer.size, limit - total))
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
            return out.toByteArray()
        }
    }
}

/**
 * Maps the `<root>` of a successful response, treating a `null` mapping as a malformed document.
 *
 * Keeps every endpoint method to a single expression while still turning "the XML parsed but did
 * not contain what this endpoint must contain" into an explicit [NvHttpResult.Malformed].
 */
private inline fun <T : Any> NvHttpResult<XmlNode>.mapCatchingRoot(
    endpoint: String,
    transform: (XmlNode) -> T?,
): NvHttpResult<T> = when (this) {
    is NvHttpResult.Success -> transform(value)
        ?.let { NvHttpResult.Success(it) }
        ?: NvHttpResult.Malformed("$endpoint: response was missing required elements")
    is NvHttpResult.HostError -> this
    is NvHttpResult.Malformed -> this
    is NvHttpResult.TransportError -> this
    is NvHttpResult.TlsRejected -> this
    NvHttpResult.NotPaired -> NvHttpResult.NotPaired
}
