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
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

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
    ): NvHttpResult<ServerInfo> = secureRequest(
        hostKey = hostKey,
        address = address,
        httpsPort = httpsPort,
        path = ProtocolConstants.PATH_SERVER_INFO,
        endpointParams = emptyList(),
        connectTimeoutMs = timeoutMs,
        readTimeoutMs = timeoutMs,
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
                tls = TlsSetup(identity, serverCertificate),
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
     * @param readTimeoutMs `0` for phase 1, where the host blocks until the user types the PIN.
     * @return the response `<root>` element on success, for the caller to pull phase values from.
     */
    suspend fun pairPlain(
        address: HostAddress,
        phaseParams: List<Pair<String, String>>,
        readTimeoutMs: Int = ProtocolConstants.PAIRING_PHASE_TIMEOUT_MS,
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
            endpoint = ProtocolConstants.PATH_PAIR,
            connectTimeoutMs = ProtocolConstants.PAIRING_CONNECT_TIMEOUT_MS,
            readTimeoutMs = readTimeoutMs,
            tls = null,
        )
    }

    /**
     * The phase-5 `phrase=pairchallenge` call over pinned HTTPS (spec §4.7).
     *
     * The host does not consider us paired until one client-certificate HTTPS request succeeds, so
     * this call is what actually completes pairing rather than merely confirming it.
     */
    suspend fun pairChallengeSecure(
        hostKey: String,
        address: HostAddress,
        httpsPort: Int,
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
        return requestXml(
            url = url,
            endpoint = "pairchallenge",
            connectTimeoutMs = ProtocolConstants.PAIRING_CONNECT_TIMEOUT_MS,
            readTimeoutMs = ProtocolConstants.PAIRING_PHASE_TIMEOUT_MS,
            tls = TlsSetup(identity, serverCertificate),
        )
    }

    /**
     * `/unpair` over plaintext HTTP (spec §3.9).
     *
     * Also the mandatory cleanup after any failed or cancelled pairing attempt — leaving a
     * half-finished pairing on the host wedges every subsequent try.
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
            tls = TlsSetup(identity, serverCertificate),
        )
    }

    private suspend fun requestXml(
        url: String,
        endpoint: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        tls: TlsSetup?,
    ): NvHttpResult<XmlNode> =
        when (val raw = execute(url, endpoint, connectTimeoutMs, readTimeoutMs, tls)) {
            is NvHttpResult.Success -> {
                val text = raw.value.body.toString(Charsets.UTF_8)
                when (val parsed = NvXml.parseResponse(text, endpoint)) {
                    is XmlResponse.Ok -> NvHttpResult.Success(parsed.root)
                    is XmlResponse.HostError ->
                        NvHttpResult.HostError(parsed.statusCode, parsed.statusMessage)
                    is XmlResponse.Malformed -> {
                        if (raw.value.code != HTTP_OK) {
                            NvHttpResult.TransportError(
                                "$endpoint: HTTP ${raw.value.code} with an unusable body",
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
    ): NvHttpResult<RawResponse> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var cancellationHandle: DisposableHandle? = null
        try {
            val opened = URL(url).openConnection() as HttpURLConnection
            connection = opened
            if (tls != null) {
                if (opened !is HttpsURLConnection) {
                    return@withContext NvHttpResult.TransportError(
                        "$endpoint: expected an HTTPS connection",
                    )
                }
                val sslContext = PinnedTls.context(tls.identity, tls.serverCertificate)
                opened.sslSocketFactory = PinnedTls.socketFactory(sslContext)
                opened.hostnameVerifier = PinnedTls.AnyHostnameVerifier
            }
            opened.requestMethod = "GET"
            opened.connectTimeout = connectTimeoutMs
            opened.readTimeout = readTimeoutMs
            opened.useCaches = false
            opened.instanceFollowRedirects = false
            opened.doInput = true

            cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) runCatching { opened.disconnect() }
            }

            val code = opened.responseCode
            val stream: InputStream? = if (code in 200..299) {
                runCatching { opened.inputStream }.getOrNull()
            } else {
                runCatching { opened.errorStream }.getOrNull()
            }
            val body = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) } ?: ByteArray(0)
            ensureActive()
            ProtocolLog.d(ProtocolLog.TAG_HTTP, "$endpoint -> HTTP $code, ${body.size} bytes")
            NvHttpResult.Success(RawResponse(code, body))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // Everything reachable here — connect refused, DNS failure, SSLHandshakeException,
            // SocketTimeoutException, the socket being closed by our own cancellation handler — is
            // an ordinary "could not reach this PC" for the caller.
            ProtocolLog.d(ProtocolLog.TAG_HTTP, "$endpoint failed: ${t.javaClass.simpleName}: ${t.message}")
            NvHttpResult.TransportError(t.message ?: t.javaClass.simpleName, t)
        } finally {
            cancellationHandle?.dispose()
            runCatching { connection?.disconnect() }
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
    )

    private class RawResponse(val code: Int, val body: ByteArray)

    companion object {
        private const val SCHEME_HTTP = "http"
        private const val SCHEME_HTTPS = "https"
        private const val HTTP_OK = 200

        /** Box art is the largest legitimate body; 8 MB is far above any real one. */
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

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
    NvHttpResult.NotPaired -> NvHttpResult.NotPaired
}
