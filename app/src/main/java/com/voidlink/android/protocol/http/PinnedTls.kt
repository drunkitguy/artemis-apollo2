package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.UnverifiedProtocolConstants
import com.voidlink.android.protocol.crypto.CertificateCodec
import com.voidlink.android.protocol.crypto.ClientIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stores the certificate each host presented during pairing.
 *
 * This is the TLS pin: spec §3.1 requires that HTTPS to a host trusts **exactly** the certificate
 * learned when we paired with it and nothing else, so the certificate has to outlive the pairing
 * exchange. The persisted `KnownHost` model has nowhere to put it, so the protocol layer keeps its
 * own small store under `filesDir/hosts/` rather than reaching into the data layer.
 *
 * Certificates are written as PEM, which is both what the host sent (spec §4.3) and readable when
 * debugging with `openssl x509 -text`.
 *
 * @param baseDir the application's `filesDir`.
 */
class HostTrustStore(baseDir: File) {

    private val directory = File(baseDir, DIRECTORY_NAME)
    private val mutex = Mutex()
    private val cache = ConcurrentHashMap<String, X509Certificate>()

    /** Working TLS versions per host; an empty list means "asked, and there is no override". */
    private val tlsCache = ConcurrentHashMap<String, List<String>>()

    /**
     * The pinned certificate for [hostKey], or `null` when we have never paired with it.
     *
     * @param hostKey a stable per-host identifier; callers use `KnownHost.uuid`.
     */
    suspend fun certificate(hostKey: String): X509Certificate? {
        cache[hostKey]?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = fileFor(hostKey)
            if (!file.isFile) {
                null
            } else {
                val parsed = CertificateCodec.parseOrNull(runCatching { file.readBytes() }.getOrNull())
                if (parsed == null) {
                    ProtocolLog.w(ProtocolLog.TAG_TLS, "Pinned certificate for $hostKey is unreadable")
                    runCatching { file.delete() }
                } else {
                    cache[hostKey] = parsed
                }
                parsed
            }
        }
    }

    /** True when [hostKey] has a pinned certificate, i.e. we have completed pairing with it. */
    suspend fun isTrusted(hostKey: String): Boolean = certificate(hostKey) != null

    /**
     * Pins [certificate] for [hostKey], replacing any previous one.
     *
     * Re-pairing a host legitimately replaces the certificate — Sunshine regenerates its identity
     * when its config is reset — so overwriting is correct, not a security hole: the user has just
     * proved possession of the host by typing a PIN into it.
     */
    suspend fun store(hostKey: String, certificate: X509Certificate) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    ProtocolLog.e(ProtocolLog.TAG_TLS, "Cannot create ${directory.absolutePath}")
                    return@withContext
                }
                val file = fileFor(hostKey)
                val temp = File(directory, "${file.name}.tmp")
                runCatching {
                    temp.writeBytes(CertificateCodec.toPem(certificate).toByteArray(Charsets.US_ASCII))
                    if (!temp.renameTo(file)) {
                        file.delete()
                        temp.renameTo(file)
                    }
                }.onFailure {
                    temp.delete()
                    ProtocolLog.e(ProtocolLog.TAG_TLS, "Cannot pin certificate for $hostKey", it)
                }
                cache[hostKey] = certificate
            }
        }
    }

    /**
     * Moves the pin from [oldKey] to [newKey], so a host that turns out to already be known under
     * its real `uniqueid` keeps the certificate it was paired with.
     *
     * Without this, re-filing a manually added host under the identity a probe revealed would
     * orphan its pin: the record would still claim to be paired while every HTTPS call failed, and
     * the user would have to pair again for no visible reason. Does nothing when there is no pin
     * to move, or when [newKey] already has one — the existing pin is the one HTTPS is currently
     * succeeding with, so it wins.
     *
     * @return true when a certificate was actually moved.
     */
    suspend fun rekey(oldKey: String, newKey: String): Boolean {
        if (oldKey == newKey || newKey.isBlank()) return false
        val existing = certificate(oldKey) ?: return false
        if (certificate(newKey) != null) {
            remove(oldKey)
            return false
        }
        store(newKey, existing)
        remove(oldKey)
        return true
    }

    /**
     * The TLS versions previously found to work with [hostKey], or `null` for the default list.
     *
     * Persisted rather than held in memory, and that is the point. When a host only completes a
     * client-authenticated handshake under a narrower configuration, forgetting that on the next
     * app launch means the first secure request stalls all over again — which reads to the user as
     * "it worked yesterday and now it doesn't". The self-test that discovers it only runs during
     * pairing, so there is nothing to re-learn it from afterwards.
     */
    suspend fun tlsProtocols(hostKey: String): List<String>? {
        tlsCache[hostKey]?.let { return it.ifEmpty { null } }
        return withContext(Dispatchers.IO) {
            val file = tlsFileFor(hostKey)
            val stored = if (!file.isFile) {
                emptyList()
            } else {
                runCatching { file.readText(Charsets.US_ASCII) }
                    .getOrNull()
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            }
            tlsCache[hostKey] = stored
            stored.ifEmpty { null }
        }
    }

    /** Records the TLS versions that actually work with [hostKey]. */
    suspend fun storeTlsProtocols(hostKey: String, protocols: List<String>) {
        if (protocols.isEmpty()) return
        mutex.withLock {
            tlsCache[hostKey] = protocols
            withContext(Dispatchers.IO) {
                if (!directory.isDirectory && !directory.mkdirs()) return@withContext
                runCatching { tlsFileFor(hostKey).writeText(protocols.joinToString(","), Charsets.US_ASCII) }
                    .onFailure {
                        ProtocolLog.w(ProtocolLog.TAG_TLS, "Cannot record TLS versions for $hostKey", it)
                    }
            }
        }
    }

    /** Forgets the pin for [hostKey]; called after `/unpair` and on a failed pairing attempt. */
    suspend fun remove(hostKey: String) {
        mutex.withLock {
            cache.remove(hostKey)
            tlsCache.remove(hostKey)
            withContext(Dispatchers.IO) {
                runCatching { fileFor(hostKey).delete() }
                runCatching { tlsFileFor(hostKey).delete() }
            }
        }
    }

    /**
     * Host keys are hex-encoded before becoming filenames.
     *
     * A host key ultimately derives from a value a remote machine chose, so it must never be able
     * to contain a path separator or `..`.
     */
    private fun fileFor(hostKey: String): File =
        File(directory, Hex.encode(hostKey.toByteArray(Charsets.UTF_8)) + FILE_SUFFIX)

    /** Sibling of [fileFor] holding the working TLS versions, under the same safe filename rule. */
    private fun tlsFileFor(hostKey: String): File =
        File(directory, Hex.encode(hostKey.toByteArray(Charsets.UTF_8)) + TLS_SUFFIX)

    private companion object {
        const val DIRECTORY_NAME = "hosts"
        const val FILE_SUFFIX = ".pem"
        const val TLS_SUFFIX = ".tls"
    }
}

/**
 * Builds the `SSLContext` for NVHTTP over port 47984 (spec §3.1).
 *
 * Two halves, both mandatory:
 *
 * * **Client authentication** — we present our own certificate and key, because that is what the
 *   host uses to recognise us as a paired client.
 * * **Exact certificate pinning** — the host's certificate is self-signed and validates against no
 *   CA, so the trust decision is byte-equality with the certificate we learned during pairing.
 *   A blanket trust-all manager would make the pairing MITM check pointless, and is never used.
 *
 * Hostname verification is replaced rather than merely relaxed: the host certificate carries no
 * matching SAN, and pinning the exact certificate is what provides the security instead.
 */
object PinnedTls {

    /**
     * Accepts any hostname.
     *
     * Safe **only** in combination with [pinnedTrustManager]: identity is established by the
     * certificate pin, not by the name.
     */
    val AnyHostnameVerifier: HostnameVerifier = HostnameVerifier { _, _ -> true }

    /**
     * Builds a client-authenticated, certificate-pinned context.
     *
     * @param identity our client certificate and key.
     * @param serverCertificate the certificate pinned for this host during pairing.
     * @param clientCertificateRequested set to `true` the moment the TLS stack asks us for a client
     *   certificate — i.e. the moment the server's `CertificateRequest` arrives. Whether this ever
     *   flips is the single most diagnostic fact about a stalled handshake: if it never does, the
     *   server never got far enough to ask, so nothing about *our* certificate can be the cause.
     */
    fun context(
        identity: ClientIdentity,
        serverCertificate: X509Certificate,
        clientCertificateRequested: AtomicBoolean? = null,
    ): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf(
                SingleIdentityKeyManager(
                    identity.certificate,
                    identity.privateKey,
                    clientCertificateRequested,
                ),
            ),
            arrayOf(pinnedTrustManager(serverCertificate)),
            SecureRandom(),
        )
        return context
    }

    /**
     * Builds a certificate-pinned context that offers **no** client certificate.
     *
     * Only for [TlsProbe]: comparing this against [context] is what separates "the host stalls when
     * we present a client certificate" from "the host does not speak TLS on this port at all". It
     * is never used for a real request, because an NVHTTP host recognises us by that certificate.
     */
    fun anonymousContext(serverCertificate: X509Certificate): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(pinnedTrustManager(serverCertificate)), SecureRandom())
        return context
    }

    /**
     * A socket factory for an NVHTTP connection.
     *
     * @param protocols TLS versions to enable. **Empty means "leave the platform default alone"**,
     *   which is the default and what the reference client does — moonlight-android never calls
     *   `setEnabledProtocols`, and it is the client this host family is actually tested against.
     *   Narrowing the list is reserved for a host [TlsProbe] has shown needs it; imposing a list on
     *   every host is a divergence from the thing that demonstrably works, for no evidence.
     */
    fun socketFactory(
        context: SSLContext,
        protocols: List<String> = emptyList(),
    ): SSLSocketFactory =
        ProtocolConstrainingSocketFactory(
            delegate = context.socketFactory,
            desiredProtocols = protocols,
        )

    /**
     * A trust manager that accepts exactly one certificate, compared byte for byte.
     */
    fun pinnedTrustManager(pinned: X509Certificate): X509TrustManager = PinnedTrustManager(pinned)
}

/**
 * Presents a single client certificate and key for every alias request.
 *
 * Implemented directly rather than through a `KeyStore` + `KeyManagerFactory`: we have exactly one
 * identity, and building an in-memory keystore introduces provider-dependent password handling for
 * no benefit.
 */
private class SingleIdentityKeyManager(
    private val certificate: X509Certificate,
    private val privateKey: PrivateKey,
    private val requested: AtomicBoolean? = null,
) : X509ExtendedKeyManager() {

    private val chain: Array<X509Certificate> = arrayOf(certificate)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        arrayOf(ALIAS)

    /**
     * Always offers our identity.
     *
     * A GameStream host's `CertificateRequest` advertises no acceptable issuers we could match
     * against — it recognises us by the exact certificate it pinned at pairing time — so filtering
     * on [issuers] here would mean never sending a certificate at all. The return type is
     * deliberately non-nullable so this can never silently degrade into "send no certificate",
     * which is a classic cause of a host that accepts the connection and then goes quiet.
     */
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = recordRequest(keyType, issuers, "socket")

    /**
     * The `SSLEngine` counterpart of [chooseClientAlias].
     *
     * `X509ExtendedKeyManager`'s default implementation of this returns null, so an engine-based
     * TLS stack would send no client certificate at all and the host would refuse us — a silent
     * failure that would look exactly like being unpaired. Android's Conscrypt uses the engine path
     * on modern releases, so this override is load-bearing, not defensive.
     */
    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String = recordRequest(keyType, issuers, "engine")

    /**
     * Notes that the server actually asked for a client certificate, and logs what it asked for.
     *
     * This line is the difference between "we failed to send a certificate" and "the server never
     * got far enough to want one" — two failures that look identical from a read timeout.
     */
    private fun recordRequest(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        via: String,
    ): String {
        requested?.set(true)
        ProtocolLog.i(
            ProtocolLog.TAG_TLS,
            "Server requested a client certificate via the $via path " +
                "(keyTypes=${keyType?.toList()}, acceptedIssuers=${issuers?.size ?: 0}); " +
                "offering ${CertificateCodec.describe(certificate)} regardless, as the host " +
                "recognises us by the exact certificate it pinned at pairing time",
        )
        return ALIAS
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain

    override fun getPrivateKey(alias: String?): PrivateKey = privateKey

    private companion object {
        const val ALIAS = "voidlink-client"
    }
}

/**
 * Trusts one certificate and nothing else (spec §3.1).
 */
private class PinnedTrustManager(private val pinned: X509Certificate) : X509TrustManager {

    private val pinnedEncoded: ByteArray = pinned.encoded

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // We are never a TLS server; refusing outright is the honest behaviour.
        throw CertificateException("VoidLink does not accept client certificates")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val presented = chain?.firstOrNull()
            ?: throw CertificateException("host presented no certificate")
        if (!MessageDigest.isEqual(presented.encoded, pinnedEncoded)) {
            // Logged as well as thrown: the exception is wrapped in an SSLHandshakeException by the
            // time anything sees it, and "which certificate did we actually get" is the question a
            // pinning failure always raises.
            ProtocolLog.w(
                ProtocolLog.TAG_TLS,
                "Pinned certificate mismatch: host presented " +
                    "${CertificateCodec.describe(presented)}, but we pinned " +
                    CertificateCodec.describe(pinned),
            )
            throw CertificateException(
                "host certificate does not match the one pinned during pairing; re-pair this host",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinned)
}

/**
 * Delegating factory that narrows every socket to the desired TLS versions.
 *
 * Any protocol the platform does not support is silently dropped from the request, and if none
 * survive the socket is left at the platform default — a too-aggressive pin must never be the
 * reason a host is unreachable.
 */
private class ProtocolConstrainingSocketFactory(
    private val delegate: SSLSocketFactory,
    private val desiredProtocols: List<String>,
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        configure(delegate.createSocket(socket, host, port, autoClose))

    override fun createSocket(host: String?, port: Int): Socket =
        configure(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        configure(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = configure(delegate.createSocket(address, port, localAddress, localPort))

    private fun configure(socket: Socket): Socket {
        if (socket is SSLSocket) {
            runCatching {
                // An empty list is the normal path, and the one the reference client takes: touch
                // nothing. The platform's own list is better informed than ours about what this
                // device and this Android version can actually negotiate.
                if (desiredProtocols.isNotEmpty()) {
                    val supported = socket.supportedProtocols.toSet()
                    val enabled = desiredProtocols.filter { it in supported }
                    if (enabled.isNotEmpty()) {
                        socket.enabledProtocols = enabled.toTypedArray()
                    } else {
                        // The degrade path: never let a too-aggressive pin be the reason a host is
                        // unreachable. Logged because it silently changes what we offer, and a host
                        // that then negotiates something unexpected is otherwise impossible to
                        // explain from a bug report.
                        ProtocolLog.w(
                            ProtocolLog.TAG_TLS,
                            "None of $desiredProtocols is supported here " +
                                "(platform offers ${socket.supportedProtocols.toList()}); " +
                                "leaving the socket at its default ${socket.enabledProtocols.toList()}",
                        )
                    }
                }
                ProtocolLog.d(
                    ProtocolLog.TAG_TLS,
                    "TLS offering ${socket.enabledProtocols.toList()} to " +
                        "${socket.inetAddress?.hostAddress}:${socket.port}",
                )
                // What was actually negotiated is the single most useful line in a TLS bug report,
                // and there is no way to read it back from `HttpsURLConnection` afterwards.
                socket.addHandshakeCompletedListener { event ->
                    ProtocolLog.i(
                        ProtocolLog.TAG_TLS,
                        "TLS handshake complete: ${event.session.protocol} / " +
                            "${event.session.cipherSuite} with " +
                            "${event.session.peerHost}:${event.session.peerPort}",
                    )
                }
            }.onFailure {
                ProtocolLog.w(ProtocolLog.TAG_TLS, "Could not constrain TLS versions", it)
            }
        }
        return socket
    }
}
