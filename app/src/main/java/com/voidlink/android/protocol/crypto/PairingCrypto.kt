package com.voidlink.android.protocol.crypto

import com.voidlink.android.protocol.ProtocolConstants
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Which digest the pairing handshake uses, selected from the host's `appversion` (spec §4.0).
 *
 * @property jcaName the JCA algorithm name.
 * @property digestLength the digest size in bytes, which is also the offset at which the server's
 *   16-byte challenge begins inside the decrypted phase-2 response (spec §4.4).
 */
enum class PairingHash(val jcaName: String, val digestLength: Int) {
    /** Generations below 7. */
    SHA1("SHA-1", ProtocolConstants.SHA1_DIGEST_BYTES),

    /** Generation 7 and above — every modern host. */
    SHA256("SHA-256", ProtocolConstants.SHA256_DIGEST_BYTES),
    ;

    /**
     * Digests the concatenation of [parts] without materialising the joined array.
     */
    fun digest(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance(jcaName)
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    companion object {
        /**
         * Picks the hash for a host generation.
         *
         * @param generation the first component of `appversion` (spec §0.3).
         */
        fun forGeneration(generation: Int): PairingHash =
            if (generation >= ProtocolConstants.PAIRING_SHA256_MIN_GENERATION) SHA256 else SHA1
    }
}

/**
 * The cryptographic primitives of the PIN pairing handshake (spec §4.1–§4.6).
 *
 * Every function here is pure with respect to the JCA — no sockets, no Android — which is why this
 * is the one part of the protocol covered by real known-answer tests
 * (`app/src/test/.../PairingCryptoTest.kt`).
 *
 * The single most important detail, from spec §4.2: pairing uses **AES-128 in ECB mode with no
 * padding**, over an input the caller has **zero-extended** to a multiple of 16. Using
 * PKCS#5/PKCS#7 instead appends a whole extra block and the host silently rejects the value.
 */
object PairingCrypto {

    /** AES block size in bytes; the padding granularity of spec §4.2. */
    const val AES_BLOCK_BYTES: Int = 16

    private const val AES_TRANSFORMATION = "AES/ECB/NoPadding"
    private const val AES_KEY_ALGORITHM = "AES"

    /**
     * `salt || UTF8(pin)` — the 20-byte pre-image of the pairing key (spec §4.1).
     *
     * The PIN contributes its **digit characters**, not its numeric value, which is why a PIN of
     * `0042` is four bytes and not two.
     */
    fun saltedPin(salt: ByteArray, pin: String): ByteArray {
        val pinBytes = pin.toByteArray(Charsets.UTF_8)
        val out = ByteArray(salt.size + pinBytes.size)
        System.arraycopy(salt, 0, out, 0, salt.size)
        System.arraycopy(pinBytes, 0, out, salt.size, pinBytes.size)
        return out
    }

    /**
     * Derives the 16-byte AES key: the first 16 bytes of `hash(salt || pin)` (spec §4.1).
     *
     * For SHA-256 that is the first 16 of 32 bytes; for SHA-1 the first 16 of 20.
     */
    fun deriveAesKey(salt: ByteArray, pin: String, hash: PairingHash): ByteArray =
        hash.digest(saltedPin(salt, pin)).copyOf(ProtocolConstants.PAIRING_AES_KEY_BYTES)

    /**
     * Zero-extends [input] to a whole number of AES blocks (spec §4.2).
     *
     * Returns [input] itself when it is already block-aligned and non-empty, so the common
     * 16-byte-challenge case does not copy.
     */
    fun zeroPad(input: ByteArray): ByteArray {
        val remainder = input.size % AES_BLOCK_BYTES
        if (remainder == 0 && input.isNotEmpty()) return input
        val paddedLength = input.size + (AES_BLOCK_BYTES - remainder)
        return input.copyOf(paddedLength)
    }

    /**
     * AES-128-ECB encrypts [input], zero-padded to a block multiple (spec §4.2).
     *
     * @param key the 16-byte key from [deriveAesKey].
     */
    fun encrypt(input: ByteArray, key: ByteArray): ByteArray =
        transform(Cipher.ENCRYPT_MODE, input, key)

    /**
     * AES-128-ECB decrypts [input] (spec §4.2).
     *
     * The plaintext is returned in full; callers slice out the prefix they need and ignore the
     * trailing zero bytes that block rounding introduced.
     */
    fun decrypt(input: ByteArray, key: ByteArray): ByteArray =
        transform(Cipher.DECRYPT_MODE, input, key)

    private fun transform(mode: Int, input: ByteArray, key: ByteArray): ByteArray {
        require(key.size == ProtocolConstants.PAIRING_AES_KEY_BYTES) {
            "pairing AES key must be ${ProtocolConstants.PAIRING_AES_KEY_BYTES} bytes, was ${key.size}"
        }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(mode, SecretKeySpec(key, AES_KEY_ALGORITHM))
        // ECB has no chaining, so processing the padded buffer in one call is byte-identical to the
        // block-by-block loop the spec describes.
        return cipher.doFinal(zeroPad(input))
    }

    /**
     * Generates the four-digit PIN the user types on the host (spec §4.0).
     *
     * Built one character at a time so a leading zero survives — formatting an `Int` would turn
     * `0042` into `42` and every such pairing would fail.
     */
    fun generatePin(random: SecureRandom): String {
        val builder = StringBuilder(ProtocolConstants.PAIRING_PIN_DIGITS)
        repeat(ProtocolConstants.PAIRING_PIN_DIGITS) {
            builder.append('0' + random.nextInt(10))
        }
        return builder.toString()
    }

    /**
     * Splits the decrypted phase-2 `challengeresponse` (spec §4.4).
     *
     * @return the server's response hash and its 16-byte challenge, or `null` when the buffer is
     *   too short to contain both — a malformed host reply, not a bug.
     */
    fun splitChallengeResponse(decrypted: ByteArray, hash: PairingHash): ServerChallenge? {
        val needed = hash.digestLength + ProtocolConstants.PAIRING_CHALLENGE_BYTES
        if (decrypted.size < needed) return null
        return ServerChallenge(
            serverResponse = decrypted.copyOfRange(0, hash.digestLength),
            serverChallenge = decrypted.copyOfRange(hash.digestLength, needed),
        )
    }

    /**
     * Splits the phase-3 `pairingsecret` into the server's secret and its signature (spec §4.5).
     *
     * @return the split, or `null` when there is no room for both parts.
     */
    fun splitPairingSecret(raw: ByteArray): ServerPairingSecret? {
        val secretLength = ProtocolConstants.PAIRING_CHALLENGE_BYTES
        if (raw.size <= secretLength) return null
        return ServerPairingSecret(
            secret = raw.copyOfRange(0, secretLength),
            signature = raw.copyOfRange(secretLength, raw.size),
        )
    }

    /**
     * The phase-3 hash we send: `hash(serverChallenge || ourCertSignature || clientSecret)`
     * (spec §4.5).
     */
    fun clientChallengeResponseHash(
        serverChallenge: ByteArray,
        clientCertSignature: ByteArray,
        clientSecret: ByteArray,
        hash: PairingHash,
    ): ByteArray = hash.digest(serverChallenge, clientCertSignature, clientSecret)

    /**
     * The hash we expect the server to have produced in phase 2:
     * `hash(clientChallenge || serverCertSignature || serverSecret)` (spec §4.5).
     *
     * This is the mirror image of [clientChallengeResponseHash], and comparing it against the
     * server's phase-2 response is what proves the user typed the right PIN.
     */
    fun expectedServerResponseHash(
        clientChallenge: ByteArray,
        serverCertSignature: ByteArray,
        serverSecret: ByteArray,
        hash: PairingHash,
    ): ByteArray = hash.digest(clientChallenge, serverCertSignature, serverSecret)

    /**
     * Verifies the server's signature over its own secret using the pinned server certificate
     * (spec §4.5, check 1 — the MITM check).
     *
     * The algorithm follows the server key type: EC keys are verified with SHA256withECDSA, all
     * others with SHA256withRSA.
     *
     * @return true only when the signature verifies. Any exception — an unsupported algorithm, a
     *   malformed signature — is a verification failure, never a crash.
     */
    fun verifyServerSignature(
        serverCertificate: X509Certificate,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = try {
        val publicKey = serverCertificate.publicKey
        val algorithm = if (publicKey.algorithm.equals("EC", ignoreCase = true)) {
            ProtocolConstants.SERVER_EC_SIGNATURE_ALGORITHM
        } else {
            ProtocolConstants.CLIENT_SIGNATURE_ALGORITHM
        }
        Signature.getInstance(algorithm).run {
            initVerify(publicKey)
            update(data)
            verify(signature)
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * Signs [data] with our private key for the phase-4 `clientpairingsecret` (spec §4.6).
     */
    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(ProtocolConstants.CLIENT_SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    /**
     * `clientSecret || sign(clientSecret)` — the phase-4 payload (spec §4.6).
     */
    fun clientPairingSecret(clientSecret: ByteArray, privateKey: PrivateKey): ByteArray {
        val signature = sign(privateKey, clientSecret)
        val out = ByteArray(clientSecret.size + signature.size)
        System.arraycopy(clientSecret, 0, out, 0, clientSecret.size)
        System.arraycopy(signature, 0, out, clientSecret.size, signature.size)
        return out
    }

    /**
     * Constant-time comparison, used for the PIN-correctness check.
     *
     * `MessageDigest.isEqual` is the platform's constant-time comparator on both Android and the
     * JVM; a plain `contentEquals` would leak how many leading bytes matched.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/**
 * The two values carried inside the decrypted phase-2 `challengeresponse` (spec §4.4).
 *
 * @property serverResponse the server's hash, checked against [PairingCrypto.expectedServerResponseHash]
 *   once phase 3 reveals the server secret.
 * @property serverChallenge the server's 16-byte challenge, hashed back in phase 3.
 */
class ServerChallenge(
    val serverResponse: ByteArray,
    val serverChallenge: ByteArray,
)

/**
 * The two values carried inside the phase-3 `pairingsecret` (spec §4.5).
 *
 * @property secret the server's 16-byte secret.
 * @property signature the server's signature over [secret], verified with its certificate.
 */
class ServerPairingSecret(
    val secret: ByteArray,
    val signature: ByteArray,
)
