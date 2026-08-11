package com.voidlink.android.protocol.pairing

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.crypto.CertificateCodec
import com.voidlink.android.protocol.crypto.IdentityStore
import com.voidlink.android.protocol.crypto.PairingCrypto
import com.voidlink.android.protocol.crypto.PairingHash
import com.voidlink.android.protocol.http.HostTrustStore
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.http.ServerInfo
import com.voidlink.android.protocol.http.XmlNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** The terminal outcome of a pairing attempt (spec §4.8). */
enum class PairResult {
    /** The host now trusts this client. */
    PAIRED,

    /** The PIN check failed — the user mistyped it. Shown differently from a generic failure. */
    PIN_WRONG,

    /** Another client is already mid-pairing with this host; try again shortly. */
    ALREADY_IN_PROGRESS,

    /** The handshake failed, including a signature mismatch that may indicate a MITM. */
    FAILED,

    /** The user backed out. */
    CANCELLED,
}

/**
 * Progress emitted while pairing runs.
 *
 * Modelled as a flow rather than a single suspend result because the UI must display the PIN the
 * instant it exists — phase 1 then blocks for as long as the user takes to type it into the host.
 */
sealed interface PairProgress {

    /** The PIN to show the user, emitted before the blocking phase-1 call starts. */
    class PinReady(val pin: String) : PairProgress

    /**
     * A phase has begun.
     *
     * @property phase 1..5, matching the section numbering of spec §4.3–§4.7.
     */
    class Phase(val phase: Int) : PairProgress

    /**
     * The attempt finished.
     *
     * @property result the outcome.
     * @property detail a short explanation for the failure paths, or `null`.
     */
    class Done(val result: PairResult, val detail: String? = null) : PairProgress
}

/**
 * Drives the five-phase PIN pairing handshake (spec §4).
 *
 * The shape of the exchange, and why each step exists:
 *
 * 1. `getservercert` — we send a salt and our certificate; the host blocks until the user types
 *    the PIN, then returns its own certificate. Both sides can now derive the same AES key from
 *    `hash(salt || pin)`, but neither has proved it.
 * 2. `clientchallenge` — we prove we can encrypt with that key, and receive the host's challenge
 *    plus a hash we will only be able to check once phase 3 reveals its secret.
 * 3. `serverchallengeresp` — we answer the host's challenge and receive its secret and a
 *    signature over that secret. **Two checks happen here and both are mandatory:** the signature
 *    must verify against the certificate from phase 1 (anti-MITM), and the phase-2 hash must
 *    match one we recompute from the now-revealed secret (this is the PIN check).
 * 4. `clientpairingsecret` — we reveal our own secret, signed with our private key.
 * 5. An HTTPS `pairchallenge` using our client certificate — the host does not actually consider
 *    us paired until one client-certificate TLS request succeeds.
 *
 * Every failure and cancellation path calls `/unpair`, without exception: a half-finished pairing
 * left on the host wedges every subsequent attempt (spec §4.0).
 *
 * @param httpClient the NVHTTP transport.
 * @param identityStore supplies our certificate and key.
 * @param trustStore where the host's certificate is pinned on success.
 * @param secureRandom randomness source; injectable so a test can make an attempt deterministic.
 */
class PairingEngine(
    private val httpClient: NvHttpClient,
    private val identityStore: IdentityStore,
    private val trustStore: HostTrustStore,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    /**
     * Runs the handshake, emitting progress as it goes.
     *
     * The returned flow is cold: collecting it starts the attempt, and cancelling the collection
     * cancels it — which closes the phase-1 socket and calls `/unpair` so the host dismisses its
     * PIN prompt (spec §4.8).
     *
     * `channelFlow` rather than `flow` because the cleanup paths need to report an outcome after
     * catching a failure, and emitting from inside a `catch` would violate a plain flow's
     * exception transparency.
     *
     * @param hostKey stable per-host identifier; the pinned certificate is filed under it.
     * @param address the host's plaintext address.
     * @param serverInfo the `/serverinfo` result, needed for hash selection and the HTTPS port.
     */
    fun pair(
        hostKey: String,
        address: HostAddress,
        serverInfo: ServerInfo,
    ): Flow<PairProgress> = channelFlow {
        try {
            val outcome = runHandshake(hostKey, address, serverInfo) { send(it) }
            if (outcome.result != PairResult.PAIRED) {
                withContext(NonCancellable) { cleanUp(hostKey, address, "outcome=${outcome.result}") }
            }
            send(PairProgress.Done(outcome.result, outcome.detail))
        } catch (cancellation: CancellationException) {
            // The coroutine is already cancelled, so the cleanup has to opt out of cancellation or
            // the host is left showing its PIN prompt forever.
            withContext(NonCancellable) { cleanUp(hostKey, address, "cancelled") }
            throw cancellation
        } catch (t: Throwable) {
            ProtocolLog.e(ProtocolLog.TAG_PAIR, "Pairing threw", t)
            withContext(NonCancellable) { cleanUp(hostKey, address, "exception") }
            send(PairProgress.Done(PairResult.FAILED, t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * Runs the handshake without the flow plumbing.
     *
     * Split out so the state machine reads as straight-line code: every early return is a terminal
     * outcome, and the caller owns the `/unpair` cleanup that must follow a non-[PairResult.PAIRED]
     * one.
     */
    private suspend fun runHandshake(
        hostKey: String,
        address: HostAddress,
        serverInfo: ServerInfo,
        emit: suspend (PairProgress) -> Unit,
    ): Outcome {
        val identity = try {
            identityStore.identity()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return Outcome(PairResult.FAILED, "no client identity: ${t.message}")
        }

        val hash = PairingHash.forGeneration(serverInfo.appVersion.generation)
        ProtocolLog.i(
            ProtocolLog.TAG_PAIR,
            "Pairing with ${serverInfo.hostname ?: hostKey}: appversion=${serverInfo.appVersion}, " +
                "hash=${hash.jcaName}",
        )

        val salt = randomBytes(ProtocolConstants.PAIRING_SALT_BYTES)
        val pin = PairingCrypto.generatePin(secureRandom)
        val aesKey = PairingCrypto.deriveAesKey(salt, pin, hash)

        // The user cannot proceed without seeing this, and phase 1 blocks until they have typed it.
        emit(PairProgress.PinReady(pin))

        // ---- Phase 1: getservercert (spec §4.3) ----------------------------------------------
        emit(PairProgress.Phase(1))
        val phase1 = httpClient.pairPlain(
            address = address,
            phaseParams = listOf(
                "phrase" to "getservercert",
                "salt" to Hex.encode(salt),
                "clientcert" to identity.certificatePemHex,
            ),
            readTimeoutMs = ProtocolConstants.PAIRING_PHASE1_READ_TIMEOUT_MS,
        )
        val phase1Root = phase1.valueOrNull() ?: return transportFailure(phase1, "phase 1")
        if (!isPaired(phase1Root)) {
            return Outcome(PairResult.FAILED, "phase 1: the host rejected the pairing request")
        }
        val plainCertHex = phase1Root.textOf("plaincert")
        if (plainCertHex.isNullOrBlank()) {
            // Spec §4.3: an absent or empty plaincert means another client already holds the host's
            // pairing slot. That is a distinct, retryable situation, not a failure.
            ProtocolLog.w(ProtocolLog.TAG_PAIR, "plaincert absent: another client is mid-pairing")
            return Outcome(PairResult.ALREADY_IN_PROGRESS, null)
        }
        val serverCertificate = CertificateCodec.parseOrNull(Hex.decodeOrNull(plainCertHex))
            ?: return Outcome(PairResult.FAILED, "phase 1: the host certificate could not be parsed")

        // ---- Phase 2: clientchallenge (spec §4.4) --------------------------------------------
        emit(PairProgress.Phase(2))
        val clientChallenge = randomBytes(ProtocolConstants.PAIRING_CHALLENGE_BYTES)
        val phase2 = httpClient.pairPlain(
            address = address,
            phaseParams = listOf(
                "clientchallenge" to Hex.encode(PairingCrypto.encrypt(clientChallenge, aesKey)),
            ),
        )
        val phase2Root = phase2.valueOrNull() ?: return transportFailure(phase2, "phase 2")
        if (!isPaired(phase2Root)) {
            return Outcome(PairResult.FAILED, "phase 2: the host rejected the client challenge")
        }
        val encryptedChallengeResponse = Hex.decodeOrNull(phase2Root.textOf("challengeresponse"))
            ?: return Outcome(PairResult.FAILED, "phase 2: challengeresponse was missing or not hex")
        val serverChallenge = PairingCrypto.splitChallengeResponse(
            PairingCrypto.decrypt(encryptedChallengeResponse, aesKey),
            hash,
        ) ?: return Outcome(PairResult.FAILED, "phase 2: challengeresponse was too short")

        // ---- Phase 3: serverchallengeresp (spec §4.5) ----------------------------------------
        emit(PairProgress.Phase(3))
        val clientSecret = randomBytes(ProtocolConstants.PAIRING_CHALLENGE_BYTES)
        val challengeRespHash = PairingCrypto.clientChallengeResponseHash(
            serverChallenge = serverChallenge.serverChallenge,
            clientCertSignature = identity.certificateSignature,
            clientSecret = clientSecret,
            hash = hash,
        )
        val phase3 = httpClient.pairPlain(
            address = address,
            phaseParams = listOf(
                "serverchallengeresp" to Hex.encode(PairingCrypto.encrypt(challengeRespHash, aesKey)),
            ),
        )
        val phase3Root = phase3.valueOrNull() ?: return transportFailure(phase3, "phase 3")
        if (!isPaired(phase3Root)) {
            return Outcome(PairResult.FAILED, "phase 3: the host rejected the challenge response")
        }
        val pairingSecretRaw = Hex.decodeOrNull(phase3Root.textOf("pairingsecret"))
            ?: return Outcome(PairResult.FAILED, "phase 3: pairingsecret was missing or not hex")
        val serverPairingSecret = PairingCrypto.splitPairingSecret(pairingSecretRaw)
            ?: return Outcome(PairResult.FAILED, "phase 3: pairingsecret was too short")

        // Check 1 — authenticity. The host must be able to sign with the key in the certificate it
        // handed us in phase 1. A failure here means something is sitting between us and the PC.
        if (!PairingCrypto.verifyServerSignature(
                serverCertificate,
                serverPairingSecret.secret,
                serverPairingSecret.signature,
            )
        ) {
            ProtocolLog.e(ProtocolLog.TAG_PAIR, "Server signature did not verify — possible MITM")
            return Outcome(PairResult.FAILED, "the host's signature did not verify (possible MITM)")
        }

        // Check 2 — PIN correctness. Only someone who derived the same AES key could have produced
        // the phase-2 hash we are about to reproduce, so a mismatch means a mistyped PIN.
        val expectedResponse = PairingCrypto.expectedServerResponseHash(
            clientChallenge = clientChallenge,
            serverCertSignature = serverCertificate.signature,
            serverSecret = serverPairingSecret.secret,
            hash = hash,
        )
        if (!PairingCrypto.constantTimeEquals(expectedResponse, serverChallenge.serverResponse)) {
            ProtocolLog.w(ProtocolLog.TAG_PAIR, "Server response hash mismatch — wrong PIN")
            return Outcome(PairResult.PIN_WRONG, null)
        }

        // ---- Phase 4: clientpairingsecret (spec §4.6) ----------------------------------------
        emit(PairProgress.Phase(4))
        val clientPairingSecret = PairingCrypto.clientPairingSecret(clientSecret, identity.privateKey)
        val phase4 = httpClient.pairPlain(
            address = address,
            phaseParams = listOf("clientpairingsecret" to Hex.encode(clientPairingSecret)),
        )
        val phase4Root = phase4.valueOrNull() ?: return transportFailure(phase4, "phase 4")
        if (!isPaired(phase4Root)) {
            return Outcome(PairResult.FAILED, "phase 4: the host rejected the client pairing secret")
        }

        // ---- Phase 5: HTTPS pairchallenge (spec §4.7) ----------------------------------------
        // The certificate has to be pinned before this call, because the call is what uses it.
        emit(PairProgress.Phase(5))
        trustStore.store(hostKey, serverCertificate)
        val phase5 = httpClient.pairChallengeSecure(hostKey, address, serverInfo.httpsPort)
        val phase5Root = phase5.valueOrNull()
        if (phase5Root == null) {
            trustStore.remove(hostKey)
            return transportFailure(phase5, "phase 5 (HTTPS pairchallenge)")
        }
        if (!isPaired(phase5Root)) {
            trustStore.remove(hostKey)
            return Outcome(PairResult.FAILED, "phase 5: the host did not confirm pairing over TLS")
        }

        ProtocolLog.i(ProtocolLog.TAG_PAIR, "Paired with ${serverInfo.hostname ?: hostKey}")
        return Outcome(PairResult.PAIRED, null)
    }

    /**
     * The mandatory `/unpair` cleanup (spec §4.0, §4.8).
     *
     * Best-effort and never throws: the attempt has already failed, and an unreachable host cannot
     * be tidied up anyway. The local pin is dropped too, so a half-finished attempt cannot leave us
     * believing we are paired.
     */
    private suspend fun cleanUp(hostKey: String, address: HostAddress, reason: String) {
        ProtocolLog.i(ProtocolLog.TAG_PAIR, "Cleaning up pairing attempt ($reason)")
        runCatching { trustStore.remove(hostKey) }
        runCatching { httpClient.unpairPlain(address) }
    }

    /**
     * Explicit unpair requested by the user, rather than as failure cleanup (spec §3.9).
     *
     * @return true when the host acknowledged. The local pin is dropped either way, because the
     *   user's intent does not depend on the PC being switched on.
     */
    suspend fun unpair(hostKey: String, address: HostAddress): Boolean {
        trustStore.remove(hostKey)
        return httpClient.unpairPlain(address).isSuccess
    }

    private fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also { secureRandom.nextBytes(it) }

    /** Every phase's response must carry `<paired>1</paired>` (spec §4.0). */
    private fun isPaired(root: XmlNode): Boolean =
        root.textOf("paired") == ProtocolConstants.PAIRED_OK

    private fun transportFailure(result: NvHttpResult<XmlNode>, phase: String): Outcome {
        val detail = result.errorDescription() ?: "unknown error"
        ProtocolLog.w(ProtocolLog.TAG_PAIR, "$phase failed: $detail")
        return Outcome(PairResult.FAILED, "$phase: $detail")
    }

    private class Outcome(val result: PairResult, val detail: String?)
}
