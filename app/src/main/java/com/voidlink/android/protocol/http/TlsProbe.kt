package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.crypto.ClientIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * What a raw byte-level poke at the HTTPS port found (see [TlsProbe]).
 *
 * The point of the distinction is that these four look identical from an `HttpsURLConnection` read
 * timeout, and only one of them is a pairing problem.
 */
enum class PortBehaviour {
    /** Nothing is listening: the connection was refused outright. */
    REFUSED,

    /** The connection could not even be established in time. */
    UNREACHABLE,

    /** The port answered a plaintext HTTP request with an HTTP response — it is not a TLS port. */
    PLAINTEXT_HTTP,

    /** The port sent a TLS alert in reply to junk, which is exactly what a healthy TLS server does. */
    TLS_SPEAKING,

    /** The port accepted the connection and then sent nothing at all before we gave up. */
    ACCEPTED_THEN_SILENT,

    /** The port sent something, but nothing we recognise. */
    UNRECOGNISED,
}

/**
 * One attempted handshake and how it went.
 *
 * @property label how the attempt was configured, for the report.
 * @property clientCertificateRequested whether the server ever asked us for a client certificate.
 *   `false` together with a timeout means the server never reached its `CertificateRequest`, which
 *   rules our own key manager out as the cause.
 * @property negotiatedProtocol the TLS version agreed, when the handshake completed.
 * @property failure a description of what went wrong, or `null` on success.
 */
class TlsAttempt(
    val label: String,
    val clientCertificateRequested: Boolean,
    val negotiatedProtocol: String? = null,
    val negotiatedCipher: String? = null,
    val protocols: List<String> = emptyList(),
    val failure: String? = null,
) {
    /** True when the handshake completed. */
    val succeeded: Boolean get() = failure == null

    override fun toString(): String = if (succeeded) {
        "$label: OK ($negotiatedProtocol / $negotiatedCipher)"
    } else {
        "$label: $failure"
    }
}

/**
 * The result of the self-test, and — more usefully — its plain-English conclusion.
 *
 * @property port the port that was tested, so a wrong one is visible at a glance.
 * @property behaviour what the raw byte-level poke found.
 * @property attempts every handshake configuration that was tried, in order.
 * @property workingProtocols the narrowest protocol list that produced a complete handshake **with**
 *   our client certificate, or `null` when none did. When this is non-null and differs from the
 *   default, the caller should adopt it for this host.
 */
class TlsProbeReport(
    val port: Int,
    val behaviour: PortBehaviour,
    val attempts: List<TlsAttempt>,
    val workingProtocols: List<String>?,
) {
    /** True when at least one client-authenticated handshake completed. */
    val tlsWorks: Boolean get() = workingProtocols != null

    /** Whether the server ever asked for a client certificate on any attempt. */
    val clientCertificateEverRequested: Boolean
        get() = attempts.any { it.clientCertificateRequested }

    /**
     * A sentence the user can act on.
     *
     * Deliberately not phrased as a pairing failure: when the host never completes a handshake, the
     * pairing is not what is broken and telling the user to inspect their client list wastes their
     * time on the wrong thing.
     */
    fun conclusion(): String = when {
        behaviour == PortBehaviour.REFUSED ->
            "nothing is listening on port $port — the PC reported that as its HTTPS port, but its " +
                "streaming service is not accepting connections there"
        behaviour == PortBehaviour.UNREACHABLE ->
            "port $port could not be reached, even though the plaintext port answers"
        behaviour == PortBehaviour.PLAINTEXT_HTTP ->
            "port $port is a plaintext HTTP server, not an HTTPS one — the PC reported the wrong " +
                "HTTPS port, so every secure request talks past it"
        tlsWorks -> "TLS to port $port works ($workingProtocols); the failure is above the " +
            "transport, not in it"
        behaviour == PortBehaviour.ACCEPTED_THEN_SILENT && !clientCertificateEverRequested ->
            "the PC accepts connections on port $port but never starts a TLS handshake — it never " +
                "even asked for a client certificate. Its HTTPS service is wedged; restart " +
                "Sunshine/Apollo on the PC, or check that nothing else has taken port $port"
        !clientCertificateEverRequested ->
            "the PC never asked for a client certificate on port $port, so the handshake stalls " +
                "before authentication is reached — this is not a pairing problem"
        else ->
            "the PC asks for a client certificate on port $port but the handshake never completes"
    }

    /** The whole report on one greppable line. */
    fun summary(): String =
        "port=$port behaviour=$behaviour attempts=[${attempts.joinToString("; ")}]"
}

/**
 * A byte-level reachability self-test for a host's HTTPS port.
 *
 * Exists because a `SocketTimeoutException` from `HttpsURLConnection` is almost information-free: a
 * wrong port, a wedged service, a stalled handshake and a rejected client certificate all produce
 * the same three words. This walks the ladder deliberately — TCP, then raw bytes, then handshakes
 * with progressively narrower configurations — so the failure names itself.
 *
 * Everything here is plain JSSE and `java.net`, no Android APIs, and it never sends an NVHTTP
 * request: it only establishes whether a request *could* be sent.
 */
object TlsProbe {

    /** Budget for the raw TCP connect of each step. */
    const val CONNECT_TIMEOUT_MS: Int = 3_000

    /** Budget for each handshake, and for the byte-level poke. */
    const val HANDSHAKE_TIMEOUT_MS: Int = 4_000

    /** TLS record type 21: an alert. A healthy TLS server answers junk with one of these. */
    private const val TLS_RECORD_ALERT = 0x15

    /** TLS record type 22: a handshake record, i.e. a ServerHello. */
    private const val TLS_RECORD_HANDSHAKE = 0x16

    /**
     * Runs the ladder against [address] on [port].
     *
     * @param identity our client certificate and key.
     * @param serverCertificate the certificate pinned for this host, used as the trust anchor so
     *   the probe never has to fall back on trusting everything.
     */
    suspend fun diagnose(
        address: HostAddress,
        port: Int,
        identity: ClientIdentity,
        serverCertificate: X509Certificate,
    ): TlsProbeReport = withContext(Dispatchers.IO) {
        ProtocolLog.i(
            ProtocolLog.TAG_TLS,
            "TLS self-test starting against ${address.host}:$port",
        )
        val behaviour = probeRawPort(address.host, port)
        ProtocolLog.i(ProtocolLog.TAG_TLS, "TLS self-test: port behaviour is $behaviour")

        val attempts = ArrayList<TlsAttempt>(3)
        var working: List<String>? = null

        // Handshakes are only worth attempting against a port that answered *something*. When the
        // raw poke already showed the port refusing, silent, or speaking plain HTTP, the conclusion
        // is settled and three more four-second stalls would only make the user wait for it.
        if (behaviour == PortBehaviour.TLS_SPEAKING || behaviour == PortBehaviour.UNRECOGNISED) {
            for (candidate in PROTOCOL_LADDER) {
                val attempt = handshake(
                    label = "handshake with client certificate over ${describe(candidate)}",
                    host = address.host,
                    port = port,
                    protocols = candidate,
                    identity = identity,
                    serverCertificate = serverCertificate,
                )
                attempts.add(attempt)
                ProtocolLog.i(ProtocolLog.TAG_TLS, "TLS self-test: $attempt")
                if (attempt.succeeded) {
                    working = candidate
                    break
                }
            }
            if (working == null) {
                // Nothing authenticated worked. One anonymous attempt separates "the host chokes on
                // our client certificate" from "the host is not doing TLS here at all".
                val anonymous = handshake(
                    label = "handshake WITHOUT a client certificate",
                    host = address.host,
                    port = port,
                    protocols = emptyList(),
                    identity = null,
                    serverCertificate = serverCertificate,
                )
                attempts.add(anonymous)
                ProtocolLog.i(ProtocolLog.TAG_TLS, "TLS self-test: $anonymous")
            }
        }

        val report = TlsProbeReport(port, behaviour, attempts, working)
        ProtocolLog.i(ProtocolLog.TAG_TLS, "TLS self-test finished: ${report.summary()}")
        ProtocolLog.i(ProtocolLog.TAG_TLS, "TLS self-test conclusion: ${report.conclusion()}")
        report
    }

    /**
     * Connects a plain socket and writes a plaintext HTTP request line at it.
     *
     * A TLS server answers that junk with an alert record (`0x15`); a plaintext HTTP server answers
     * with `HTTP/1.x`; a wedged service answers with nothing at all. That three-way split is the
     * single most useful fact the self-test produces, and it costs one socket.
     */
    private fun probeRawPort(host: String, port: Int): PortBehaviour {
        val socket = Socket()
        try {
            // Connecting and reading are separated deliberately: a *connect* timeout means the port
            // is unreachable, whereas a *read* timeout after a successful connect is the wedged
            // service we are hunting. Collapsing both into one catch would blur exactly the two
            // cases this function exists to tell apart.
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                ProtocolLog.w(
                    ProtocolLog.TAG_TLS,
                    "TLS self-test: could not connect to $host:$port — " +
                        NvHttpClient.describeFailure(t),
                )
                return if (t.javaClass.simpleName.contains("ConnectException")) {
                    PortBehaviour.REFUSED
                } else {
                    PortBehaviour.UNREACHABLE
                }
            }
            return try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val out = socket.getOutputStream()
                out.write(PLAINTEXT_POKE)
                out.flush()
                classify(readSome(socket.getInputStream()))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                ProtocolLog.w(
                    ProtocolLog.TAG_TLS,
                    "TLS self-test: $host:$port accepted the connection but the exchange failed — " +
                        NvHttpClient.describeFailure(t),
                )
                PortBehaviour.ACCEPTED_THEN_SILENT
            }
        } finally {
            val closed = runCatching { socket.close() }.isSuccess
            ProtocolLog.d(
                ProtocolLog.TAG_TLS,
                "TLS self-test: closed the raw poke socket to $host:$port (closed=$closed)",
            )
        }
    }

    /** Reads whatever is immediately available, bounded; an empty result means "nothing came back". */
    private fun readSome(stream: InputStream): ByteArray {
        val buffer = ByteArray(64)
        val read = try {
            stream.read(buffer)
        } catch (ignored: Throwable) {
            // A read timeout here is itself the answer: the port said nothing.
            return ByteArray(0)
        }
        return if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    }

    /** Deliberate junk: valid HTTP, invalid TLS, so both kinds of server reveal themselves. */
    private val PLAINTEXT_POKE: ByteArray =
        "GET / HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII)

    /** Interprets the first bytes a port sent back. Pure, so it is directly unit-testable. */
    fun classify(firstBytes: ByteArray): PortBehaviour {
        if (firstBytes.isEmpty()) return PortBehaviour.ACCEPTED_THEN_SILENT
        val first = firstBytes[0].toInt() and 0xFF
        if (first == TLS_RECORD_ALERT || first == TLS_RECORD_HANDSHAKE) return PortBehaviour.TLS_SPEAKING
        val text = firstBytes.toString(Charsets.US_ASCII)
        if (text.startsWith("HTTP/1.")) return PortBehaviour.PLAINTEXT_HTTP
        return PortBehaviour.UNRECOGNISED
    }

    /**
     * Attempts one handshake with an explicit configuration.
     *
     * The plain socket is connected first and its timeout set **before** the TLS socket is layered
     * over it, so the handshake itself cannot block past [HANDSHAKE_TIMEOUT_MS] — the failure mode
     * this whole file exists to characterise.
     *
     * @param identity `null` to offer no client certificate at all.
     */
    private fun handshake(
        label: String,
        host: String,
        port: Int,
        protocols: List<String>,
        identity: ClientIdentity?,
        serverCertificate: X509Certificate,
    ): TlsAttempt {
        val requested = AtomicBoolean(false)
        var plain: Socket? = null
        var tls: SSLSocket? = null
        return try {
            val context = if (identity == null) {
                PinnedTls.anonymousContext(serverCertificate)
            } else {
                PinnedTls.context(identity, serverCertificate, requested)
            }
            val opened = Socket()
            plain = opened
            opened.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            opened.soTimeout = HANDSHAKE_TIMEOUT_MS

            val layered = PinnedTls.socketFactory(context, protocols)
                .createSocket(opened, host, port, true) as SSLSocket
            tls = layered
            // Belt and braces: the layered socket carries its own timeout on some stacks.
            layered.soTimeout = HANDSHAKE_TIMEOUT_MS
            layered.startHandshake()
            val session = layered.session
            TlsAttempt(
                label = label,
                clientCertificateRequested = requested.get(),
                negotiatedProtocol = session.protocol,
                negotiatedCipher = session.cipherSuite,
                protocols = protocols,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            val pinMismatch = generateSequence(t) { it.cause }.any { it is CertificateException }
            val failure = if (pinMismatch) {
                "the host presented a different certificate from the pinned one " +
                    "(${NvHttpClient.describeFailure(t)})"
            } else {
                NvHttpClient.describeFailure(t)
            }
            TlsAttempt(
                label = label,
                clientCertificateRequested = requested.get(),
                protocols = protocols,
                failure = failure,
            )
        } finally {
            // Both sockets, on every path, including the deliberately-failing attempts. The probe
            // runs precisely when a host is already struggling, and a socket leaked here would make
            // the very problem it is diagnosing worse. Closing the TLS socket first sends a proper
            // close_notify; the plain close is belt and braces against a factory that never wrapped.
            val tlsClosed = runCatching { tls?.close() }.isSuccess
            val plainClosed = runCatching { plain?.close() }.isSuccess
            ProtocolLog.d(
                ProtocolLog.TAG_TLS,
                "TLS self-test: closed sockets for \"$label\" " +
                    "(tls=$tlsClosed, plain=$plainClosed)",
            )
        }
    }

    /** Names a protocol list for a log line; an empty list is the platform's own choice. */
    private fun describe(protocols: List<String>): String =
        if (protocols.isEmpty()) "the platform default" else protocols.toString()

    /**
     * Handshake configurations, narrowest-useful last.
     *
     * The default first, then TLS 1.2 alone. A server whose client-authentication path only works
     * under TLS 1.2 is a real and repeatedly-reported shape of failure, and trying it costs one
     * socket and a few seconds.
     */
    private val PROTOCOL_LADDER: List<List<String>> = listOf(
        emptyList(),
        listOf("TLSv1.2"),
    )
}
