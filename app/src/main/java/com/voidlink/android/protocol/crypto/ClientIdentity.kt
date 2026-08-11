package com.voidlink.android.protocol.crypto

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.UnverifiedProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar

/**
 * This installation's cryptographic identity: one RSA-2048 key pair, one self-signed X.509
 * certificate, and one persistent client id (spec §2).
 *
 * Generated once and reused for every host — a host pins this exact certificate when it pairs, so
 * regenerating it would silently unpair every PC the user owns.
 *
 * Deliberately **not** a data class: `toString()` on a value holding a private key is a way to leak
 * it into a log.
 *
 * @property uniqueId the `uniqueid=` value sent on every NVHTTP request.
 * @property privateKey our RSA private key, used for the phase-4 pairing signature and for TLS
 *   client authentication.
 * @property certificate our self-signed certificate; its `getSignature()` bytes take part in the
 *   phase-3 hash (spec §4.5).
 * @property certificatePem the certificate as PEM text, cached because pairing hex-encodes it.
 */
class ClientIdentity(
    val uniqueId: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val certificatePem: String,
) {
    /**
     * The value of the `clientcert=` query parameter: hex of the PEM text's ASCII bytes.
     *
     * Spec §2 calls this out explicitly — it is *not* hex of the DER.
     */
    val certificatePemHex: String by lazy {
        Hex.encode(certificatePem.toByteArray(Charsets.US_ASCII))
    }

    /** The raw signature value of our own certificate, an input to the phase-3 hash (spec §4.5). */
    val certificateSignature: ByteArray get() = certificate.signature

    override fun toString(): String =
        "ClientIdentity(uniqueId=$uniqueId, subject=${certificate.subjectX500Principal.name})"
}

/**
 * Loads, or on first use creates, the persistent [ClientIdentity].
 *
 * Files live in `filesDir/identity/` exactly as architecture §7 specifies. `filesDir` is already
 * private to the application on Android, which is what protects the key at rest.
 *
 * Creation is guarded by a [Mutex] so that two concurrent pairing attempts cannot race and mint two
 * identities — the second host would then be paired against a certificate we had thrown away.
 *
 * @param baseDir the application's `filesDir`.
 */
class IdentityStore(baseDir: File) {

    private val directory = File(baseDir, DIRECTORY_NAME)
    private val keyFile = File(directory, FILE_KEY)
    private val certFile = File(directory, FILE_CERT)
    private val pemFile = File(directory, FILE_PEM)
    private val uniqueIdFile = File(directory, FILE_UNIQUE_ID)

    private val mutex = Mutex()

    @Volatile
    private var cached: ClientIdentity? = null

    /**
     * Returns the identity, generating and persisting it on first use.
     *
     * Runs on [Dispatchers.IO]: key generation is seconds of CPU and the reads touch disk, neither
     * of which may happen on the main thread.
     *
     * @throws IdentityException when the identity can neither be loaded nor created; that is a
     *   genuinely fatal condition — without it we can never pair with anything.
     */
    suspend fun identity(): ClientIdentity {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) { load() ?: create() }
            cached = loaded
            loaded
        }
    }

    /**
     * Discards the persisted identity.
     *
     * Only for a "reset this installation" affordance — every existing pairing dies with it.
     */
    suspend fun reset() {
        mutex.withLock {
            cached = null
            withContext(Dispatchers.IO) {
                listOf(keyFile, certFile, pemFile, uniqueIdFile).forEach { runCatching { it.delete() } }
            }
        }
    }

    private fun load(): ClientIdentity? {
        if (!keyFile.isFile || !certFile.isFile || !uniqueIdFile.isFile) return null
        return try {
            val keyBytes = keyFile.readBytes()
            val certBytes = certFile.readBytes()
            val uniqueId = uniqueIdFile.readText(Charsets.US_ASCII).trim()
            if (keyBytes.isEmpty() || certBytes.isEmpty() || uniqueId.isEmpty()) return null

            val privateKey = KeyFactory.getInstance(ProtocolConstants.CLIENT_KEY_ALGORITHM)
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val certificate = CertificateCodec.parseOrNull(certBytes) ?: return null
            val pem = if (pemFile.isFile) {
                pemFile.readText(Charsets.US_ASCII)
            } else {
                CertificateCodec.toPem(certificate)
            }
            ProtocolLog.i(ProtocolLog.TAG_IDENTITY, "Loaded existing client identity $uniqueId")
            ClientIdentity(uniqueId, privateKey, certificate, pem)
        } catch (t: Throwable) {
            // A corrupt identity is recoverable by regenerating; it only costs the user a re-pair,
            // which is far better than an app that can never pair again.
            ProtocolLog.w(ProtocolLog.TAG_IDENTITY, "Stored identity unreadable; regenerating", t)
            null
        }
    }

    private fun create(): ClientIdentity {
        try {
            val random = SecureRandom()
            val generator = KeyPairGenerator.getInstance(ProtocolConstants.CLIENT_KEY_ALGORITHM)
            generator.initialize(ProtocolConstants.CLIENT_KEY_BITS, random)
            val keyPair = generator.generateKeyPair()

            val subject = X500Name(ProtocolConstants.CLIENT_CERT_SUBJECT)
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, -ProtocolConstants.CERT_BACKDATE_DAYS.toInt())
            val notBefore = calendar.time
            calendar.add(Calendar.YEAR, ProtocolConstants.CERT_VALIDITY_YEARS.toInt())
            val notAfter = calendar.time

            var serial = BigInteger(SERIAL_BITS, random)
            if (serial.signum() <= 0) serial = BigInteger.ONE

            val builder = JcaX509v3CertificateBuilder(
                subject,
                serial,
                notBefore,
                notAfter,
                subject,
                keyPair.public,
            )
            // No explicit provider: the platform's own SHA256withRSA implementation signs a key the
            // platform just generated, which is the combination least likely to surprise us on a
            // random OEM device. BouncyCastle only builds the certificate structure.
            val signer = JcaContentSignerBuilder(ProtocolConstants.CLIENT_SIGNATURE_ALGORITHM)
                .build(keyPair.private)
            val holder = builder.build(signer)
            val certificate = JcaX509CertificateConverter().getCertificate(holder)

            val uniqueId = generateUniqueId(random)
            val pem = CertificateCodec.toPem(certificate)

            persist(keyPair.private, certificate, pem, uniqueId)
            ProtocolLog.i(ProtocolLog.TAG_IDENTITY, "Generated new client identity $uniqueId")
            return ClientIdentity(uniqueId, keyPair.private, certificate, pem)
        } catch (t: Throwable) {
            throw IdentityException("Unable to create a client identity", t)
        }
    }

    private fun persist(
        privateKey: PrivateKey,
        certificate: X509Certificate,
        pem: String,
        uniqueId: String,
    ) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IdentityException("Cannot create ${directory.absolutePath}", null)
        }
        writeAtomically(keyFile, privateKey.encoded)
        writeAtomically(certFile, certificate.encoded)
        writeAtomically(pemFile, pem.toByteArray(Charsets.US_ASCII))
        writeAtomically(uniqueIdFile, uniqueId.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Writes via a temporary file and a rename so that a crash mid-write cannot leave a truncated
     * private key behind — which would look exactly like "unreadable identity" and silently unpair
     * every host.
     */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            // renameTo can fail if the destination exists on some filesystems.
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IdentityException("Cannot write ${target.absolutePath}", null)
            }
        }
    }

    private fun generateUniqueId(random: SecureRandom): String {
        // UNVERIFIED(spec 01 §2, item 21): the required format of `uniqueid`. Hosts appear to treat
        // it as opaque; we send a fixed-length lowercase hex string like the reference clients.
        ProtocolLog.unverified(
            ProtocolLog.TAG_IDENTITY,
            "uniqueid-format",
            "sending a ${UnverifiedProtocolConstants.UNIQUE_ID_HEX_CHARS}-char hex client id; " +
                "no host is known to validate the format (spec 01 §2)",
        )
        val bytes = ByteArray(UnverifiedProtocolConstants.UNIQUE_ID_HEX_CHARS / 2)
        random.nextBytes(bytes)
        return Hex.encode(bytes)
    }

    private companion object {
        const val DIRECTORY_NAME = "identity"
        const val FILE_KEY = "client.key"
        const val FILE_CERT = "client.crt"
        const val FILE_PEM = "client.pem"
        const val FILE_UNIQUE_ID = "client.id"

        /** Certificate serial width. Any positive value works; 128 bits avoids collisions. */
        const val SERIAL_BITS = 128
    }
}

/** Raised when the client identity can neither be loaded nor generated. */
class IdentityException(message: String, cause: Throwable?) : Exception(message, cause)
