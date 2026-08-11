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
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap

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

    /** Forgets the pin for [hostKey]; called after `/unpair` and on a failed pairing attempt. */
    suspend fun remove(hostKey: String) {
        mutex.withLock {
            cache.remove(hostKey)
            withContext(Dispatchers.IO) { runCatching { fileFor(hostKey).delete() } }
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

    private companion object {
        const val DIRECTORY_NAME = "hosts"
        const val FILE_SUFFIX = ".pem"
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
     */
    fun context(identity: ClientIdentity, serverCertificate: X509Certificate): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf(SingleIdentityKeyManager(identity.certificate, identity.privateKey)),
            arrayOf(pinnedTrustManager(serverCertificate)),
            SecureRandom(),
        )
        return context
    }

    /**
     * A socket factory that additionally constrains the enabled TLS versions.
     *
     * UNVERIFIED(spec 01 §3.1): whether any still-in-use host requires a version below TLSv1.2.
     * The list lives in [UnverifiedProtocolConstants.TLS_PROTOCOLS] so that a failing handshake
     * against an ancient GFE is a one-line experiment rather than a code change.
     */
    fun socketFactory(context: SSLContext): SSLSocketFactory =
        ProtocolConstrainingSocketFactory(
            delegate = context.socketFactory,
            desiredProtocols = UnverifiedProtocolConstants.TLS_PROTOCOLS,
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
    certificate: X509Certificate,
    private val privateKey: PrivateKey,
) : X509ExtendedKeyManager() {

    private val chain: Array<X509Certificate> = arrayOf(certificate)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        arrayOf(ALIAS)

    /**
     * Always offers our identity.
     *
     * A GameStream host's `CertificateRequest` advertises no acceptable issuers we could match
     * against — it recognises us by the exact certificate it pinned at pairing time — so filtering
     * on [issuers] here would mean never sending a certificate at all.
     */
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String = ALIAS

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
                val supported = socket.supportedProtocols.toSet()
                val enabled = desiredProtocols.filter { it in supported }
                if (enabled.isNotEmpty()) socket.enabledProtocols = enabled.toTypedArray()
            }.onFailure {
                ProtocolLog.w(ProtocolLog.TAG_TLS, "Could not constrain TLS versions", it)
            }
        }
        return socket
    }
}
