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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * The PC accepted our pairing but we cannot open a TLS connection to it at all.
     *
     * Separated from [FAILED] because it is not a pairing problem and the remedy is different: the
     * host's HTTPS service is not answering, or is not where it said it was. Telling the user their
     * pairing failed sends them to re-pair, which cannot possibly help.
     */
    HOST_TLS_UNREACHABLE,

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
     * The host has accepted us and we are confirming it over client-certificate TLS.
     *
     * Emitted only after phase 4 has returned `<paired>1</paired>`, which is the point of no
     * return: the PC has recorded this device and nothing the user does from here will undo that.
     * The UI needs to know, because this stretch can take tens of seconds on a host whose
     * `pairchallenge` goes quiet, and a progress bar with no words behind it reads as a hang.
     *
     * @property attempt 1-based index of the confirmation attempt now running.
     * @property totalAttempts how many will be tried before giving up.
     */
    class Verifying(val attempt: Int, val totalAttempts: Int) : PairProgress

    /**
     * Every secure request to the host timed out, and we are self-testing the transport.
     *
     * Also strictly after the point of no return. Emitted so the dialog can say what it is doing
     * instead of showing a bar that has not moved for twenty seconds.
     */
    object Diagnosing : PairProgress

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
 * 5. An HTTPS `pairchallenge` using our client certificate — our own confirmation that
 *    client-certificate TLS to this host works.
 *
 * **Where the rollback boundary sits, and it is unconditional.** A failure or cancellation in
 * phases 1–4 calls `/unpair` and drops the pinned certificate: a half-finished pairing left on the
 * host wedges every subsequent attempt (spec §4.0). Once phase 4 has answered `<paired>1</paired>`,
 * nothing does — not a failure, not a thrown exception, not a cancellation, not the user dismissing
 * the dialog. That instant is when a Sunshine-family host adds our certificate to its client list
 * and writes it to disk, and from then on the two sides are paired whatever this code does next.
 * Rolling back there discards a pairing that genuinely exists, which is what a `pairchallenge` read
 * timeout used to do; the same hole reopens through Cancel if cancellation is treated as special,
 * because a user staring at a dialog that looks stuck will press it. The only thing that unpairs
 * after this point is an explicit "Unpair" from the host list.
 *
 * An inconclusive phase 5 is settled by [verifyWithHost], and a host that answers nothing at all
 * over TLS is characterised by [diagnoseTransport] rather than blamed on pairing.
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
     * Hosts whose `pairchallenge` leg went unanswered.
     *
     * A retry against such a host skips straight to the pinned-HTTPS confirmation, which proves the
     * same thing and answers in seconds instead of stalling for the full read timeout again. In
     * memory only: it describes how a host is behaving right now, and re-learning it costs one
     * timeout.
     */
    private val silentPairChallengeHosts = ConcurrentHashMap<String, Boolean>()

    /**
     * Runs the handshake, emitting progress as it goes.
     *
     * The returned flow is cold: collecting it starts the attempt, and cancelling the collection
     * cancels it — which closes the phase-1 socket and, **while the host has not yet accepted us**,
     * calls `/unpair` so the host dismisses its PIN prompt (spec §4.8). After phase 4 a
     * cancellation stops the waiting and nothing else: see the rollback boundary on the class.
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
        val tracker = PhaseTracker()
        try {
            val outcome = runHandshake(hostKey, address, serverInfo, tracker) { send(it) }
            if (outcome.result != PairResult.PAIRED && outcome.rollBack) {
                withContext(NonCancellable) { cleanUp(hostKey, address, "outcome=${outcome.result}") }
            } else if (outcome.result != PairResult.PAIRED) {
                ProtocolLog.w(
                    ProtocolLog.TAG_PAIR,
                    "Not rolling back: the host accepted our certificate in phase 4, so /unpair " +
                        "would destroy a pairing that exists. Keeping the pinned certificate.",
                )
            }
            send(PairProgress.Done(outcome.result, outcome.detail))
        } catch (cancellation: CancellationException) {
            val phase = tracker.phase
            if (phase < HOST_HAS_ACCEPTED_US_FROM_PHASE) {
                // The coroutine is already cancelled, so the cleanup has to opt out of cancellation
                // or the host is left showing its PIN prompt forever.
                withContext(NonCancellable) { cleanUp(hostKey, address, "cancelled in phase $phase") }
            } else {
                // Backing out of the *waiting* is not backing out of the pairing. The PC recorded
                // this device the moment phase 4 answered, and a user who taps Cancel because the
                // dialog looks stuck must not lose it. The pinned certificate stays exactly where
                // it is so the next probe can confirm the pairing and adopt it.
                ProtocolLog.w(
                    ProtocolLog.TAG_PAIR,
                    "Cancelled during phase $phase, after the host had already accepted us. " +
                        "Keeping the pairing and the pinned certificate — the probe will confirm it.",
                )
            }
            throw cancellation
        } catch (t: Throwable) {
            val phase = tracker.phase
            val where = if (phase == 0) "before phase 1" else "phase $phase"
            val description = NvHttpClient.describeFailure(t)
            ProtocolLog.e(ProtocolLog.TAG_PAIR, "Pairing threw in $where: $description", t)
            // Same rule as an ordinary failure: once phase 4 has returned <paired>1</paired> the
            // host has already filed our certificate, and tearing that down is worse than reporting
            // an unclear failure.
            if (phase < HOST_HAS_ACCEPTED_US_FROM_PHASE) {
                withContext(NonCancellable) { cleanUp(hostKey, address, "exception in $where") }
            } else {
                ProtocolLog.w(
                    ProtocolLog.TAG_PAIR,
                    "Not rolling back: the host accepted our certificate in phase 4.",
                )
            }
            send(PairProgress.Done(PairResult.FAILED, "$where threw: $description"))
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
        tracker: PhaseTracker,
        emit: suspend (PairProgress) -> Unit,
    ): Outcome {
        val identity = try {
            identityStore.identity()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return Outcome(
                PairResult.FAILED,
                "no client identity: ${NvHttpClient.describeFailure(t)}",
            )
        }

        val hash = PairingHash.forGeneration(serverInfo.appVersion.generation)
        ProtocolLog.i(
            ProtocolLog.TAG_PAIR,
            "Pairing with ${serverInfo.hostname ?: hostKey} at ${address.canonical()}: " +
                "appversion=${serverInfo.appVersion}, kind=${serverInfo.serverKind}, " +
                "hash=${hash.jcaName}, httpsPort=${serverInfo.httpsPort}, " +
                "uniqueid=${identity.uniqueId}",
        )

        val salt = randomBytes(ProtocolConstants.PAIRING_SALT_BYTES)
        val pin = PairingCrypto.generatePin(secureRandom)
        val aesKey = PairingCrypto.deriveAesKey(salt, pin, hash)

        // The user cannot proceed without seeing this, and phase 1 blocks until they have typed it.
        emit(PairProgress.PinReady(pin))

        // ---- Phase 1: getservercert (spec §4.3) ----------------------------------------------
        tracker.phase = 1
        emit(PairProgress.Phase(1))
        val phase1Params = listOf(
            "phrase" to "getservercert",
            "salt" to Hex.encode(salt),
            "clientcert" to identity.certificatePemHex,
        )
        // The certificate the host is about to file under this device. Logged in exactly the form
        // every later HTTPS connection logs it (`VL.Tls`, `clientCert=`), so "the host stored one
        // certificate and we now present another" is a one-line comparison rather than an
        // inference from a read timeout. `clientcert=` itself is only ever truncated in the URL
        // trace, so without this the bytes we sent are unrecoverable from a bug report.
        ProtocolLog.i(
            ProtocolLog.TAG_PAIR,
            "$PHASE_1_LABEL: sending clientcert=${identity.certificatePemHex.length} hex chars " +
                "for ${identity.describe()}",
        )
        var phase1 = httpClient.pairPlain(
            address = address,
            phaseLabel = PHASE_1_LABEL,
            phaseParams = phase1Params,
            readTimeoutMs = ProtocolConstants.PAIRING_PHASE1_READ_TIMEOUT_MS,
        )
        if (isStalePairingSession(phase1)) {
            // Sunshine and Apollo key their in-flight pairing state by `uniqueid` and refuse a
            // second `getservercert` for a session that is still open ("Out of order call to
            // getservercert"). Refusing it is also what *clears* it, so the immediate retry is the
            // one that works — otherwise every other attempt by this device fails for a reason the
            // user cannot act on.
            ProtocolLog.w(
                ProtocolLog.TAG_PAIR,
                "phase 1 hit a stale pairing session on the host (${phase1.errorDescription()}); " +
                    "the host has now cleared it, retrying once",
            )
            phase1 = httpClient.pairPlain(
                address = address,
                phaseLabel = "$PHASE_1_LABEL retry",
                phaseParams = phase1Params,
                readTimeoutMs = ProtocolConstants.PAIRING_PHASE1_READ_TIMEOUT_MS,
            )
        }
        val phase1Root = phase1.valueOrNull() ?: return transportFailure(phase1, PHASE_1_LABEL)
        if (!isPaired(phase1Root)) {
            return Outcome(
                PairResult.FAILED,
                "$PHASE_1_LABEL: the host rejected the pairing request " +
                    "(${pairedValue(phase1Root)})",
            )
        }
        val plainCertHex = phase1Root.textOf("plaincert")
        if (plainCertHex.isNullOrBlank()) {
            // Spec §4.3: an absent or empty plaincert means another client already holds the host's
            // pairing slot. That is a distinct, retryable situation, not a failure.
            ProtocolLog.w(ProtocolLog.TAG_PAIR, "plaincert absent: another client is mid-pairing")
            return Outcome(PairResult.ALREADY_IN_PROGRESS, null)
        }
        val serverCertificate = CertificateCodec.parseOrNull(Hex.decodeOrNull(plainCertHex))
            ?: return Outcome(
                PairResult.FAILED,
                "$PHASE_1_LABEL: the host certificate could not be parsed " +
                    "(plaincert was ${plainCertHex.length} hex chars)",
            )

        // ---- Phase 2: clientchallenge (spec §4.4) --------------------------------------------
        tracker.phase = 2
        emit(PairProgress.Phase(2))
        val clientChallenge = randomBytes(ProtocolConstants.PAIRING_CHALLENGE_BYTES)
        val phase2 = httpClient.pairPlain(
            address = address,
            phaseLabel = PHASE_2_LABEL,
            phaseParams = listOf(
                "clientchallenge" to Hex.encode(PairingCrypto.encrypt(clientChallenge, aesKey)),
            ),
            readTimeoutMs = ProtocolConstants.PAIRING_PHASE2_READ_TIMEOUT_MS,
        )
        val phase2Root = phase2.valueOrNull() ?: return transportFailure(phase2, PHASE_2_LABEL)
        if (!isPaired(phase2Root)) {
            return Outcome(
                PairResult.FAILED,
                "$PHASE_2_LABEL: the host rejected the client challenge (${pairedValue(phase2Root)})",
            )
        }
        val encryptedChallengeResponse = Hex.decodeOrNull(phase2Root.textOf("challengeresponse"))
            ?: return Outcome(
                PairResult.FAILED,
                "$PHASE_2_LABEL: challengeresponse was missing or not hex",
            )
        val serverChallenge = PairingCrypto.splitChallengeResponse(
            PairingCrypto.decrypt(encryptedChallengeResponse, aesKey),
            hash,
        ) ?: return Outcome(
            PairResult.FAILED,
            "$PHASE_2_LABEL: challengeresponse was too short " +
                "(${encryptedChallengeResponse.size} bytes for ${hash.jcaName})",
        )

        // ---- Phase 3: serverchallengeresp (spec §4.5) ----------------------------------------
        tracker.phase = 3
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
            phaseLabel = PHASE_3_LABEL,
            phaseParams = listOf(
                "serverchallengeresp" to Hex.encode(PairingCrypto.encrypt(challengeRespHash, aesKey)),
            ),
            readTimeoutMs = ProtocolConstants.PAIRING_PHASE3_READ_TIMEOUT_MS,
        )
        val phase3Root = phase3.valueOrNull() ?: return transportFailure(phase3, PHASE_3_LABEL)
        if (!isPaired(phase3Root)) {
            return Outcome(
                PairResult.FAILED,
                "$PHASE_3_LABEL: the host rejected the challenge response " +
                    "(${pairedValue(phase3Root)})",
            )
        }
        val pairingSecretRaw = Hex.decodeOrNull(phase3Root.textOf("pairingsecret"))
            ?: return Outcome(
                PairResult.FAILED,
                "$PHASE_3_LABEL: pairingsecret was missing or not hex",
            )
        val serverPairingSecret = PairingCrypto.splitPairingSecret(pairingSecretRaw)
            ?: return Outcome(
                PairResult.FAILED,
                "$PHASE_3_LABEL: pairingsecret was too short (${pairingSecretRaw.size} bytes)",
            )

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
        tracker.phase = 4
        emit(PairProgress.Phase(4))
        val clientPairingSecret = PairingCrypto.clientPairingSecret(clientSecret, identity.privateKey)
        val phase4 = httpClient.pairPlain(
            address = address,
            phaseLabel = PHASE_4_LABEL,
            phaseParams = listOf("clientpairingsecret" to Hex.encode(clientPairingSecret)),
            readTimeoutMs = ProtocolConstants.PAIRING_PHASE4_READ_TIMEOUT_MS,
        )
        val phase4Root = phase4.valueOrNull() ?: return transportFailure(phase4, PHASE_4_LABEL)
        if (!isPaired(phase4Root)) {
            return Outcome(
                PairResult.FAILED,
                "$PHASE_4_LABEL: the host rejected the client pairing secret " +
                    "(${pairedValue(phase4Root)})",
            )
        }

        // ---- The point of no return ----------------------------------------------------------
        //
        // Phase 4 answering `<paired>1</paired>` is the moment a Sunshine-family host adds our
        // certificate to its client list and writes it to disk. From here the host considers us
        // paired whatever we do next, so **nothing below may roll back** — not a failure, not an
        // exception, not a cancellation. Everything below therefore uses `rollBack = false`.
        //
        // The pin is stored first, before any further step can fail or be cancelled, because it is
        // what lets the probe-time recovery adopt this pairing later even if this dialog never gets
        // an answer.
        // NonCancellable because this is the one write that must not lose a race with the user's
        // finger: a cancellation landing mid-write would leave the host trusting a device that has
        // thrown away the certificate it needs to talk to it, with nothing left to recover from.
        withContext(NonCancellable) { trustStore.store(hostKey, serverCertificate) }
        if (trustStore.certificate(hostKey) == null) {
            return Outcome(
                PairResult.FAILED,
                "the host accepted this device, but its certificate could not be stored here, so " +
                    "no secure call can be made to it",
                rollBack = false,
            )
        }
        ProtocolLog.i(
            ProtocolLog.TAG_PAIR,
            "Host accepted us in phase 4 and its certificate is pinned. Nothing from here on will " +
                "unpair; the remaining steps only confirm that secure calls work.",
        )

        // ---- Phase 5: HTTPS pairchallenge, then confirmation (spec §4.7) ----------------------
        tracker.phase = 5
        emit(PairProgress.Phase(5))

        val verified = withTimeoutOrNull(ProtocolConstants.PAIRING_VERIFY_BUDGET_MS) {
            verifyWithHost(hostKey, address, serverInfo, emit)
        }
        if (verified != null && verified.result == PairResult.PAIRED) return verified
        if (verified == null) {
            ProtocolLog.w(
                ProtocolLog.TAG_PAIR,
                "Verification exceeded its ${ProtocolConstants.PAIRING_VERIFY_BUDGET_MS}ms budget; " +
                    "moving on to the transport self-test rather than waiting longer.",
            )
        }

        // Every secure call to this host has now failed. Before blaming pairing — which is intact —
        // find out whether we can speak TLS to it at all.
        emit(PairProgress.Diagnosing)
        val diagnosed = withTimeoutOrNull(ProtocolConstants.PAIRING_DIAGNOSE_BUDGET_MS) {
            diagnoseTransport(hostKey, address, serverInfo, verified?.detail)
        }
        return diagnosed ?: Outcome(
            PairResult.HOST_TLS_UNREACHABLE,
            "the PC accepted this device, but no secure connection to " +
                "${address.host}:${serverInfo.httpsPort} could be completed and the transport " +
                "self-test did not finish either. The pairing is kept — it will start working as " +
                "soon as the PC's secure service does.",
            rollBack = false,
        )
    }

    /**
     * Confirms, over client-certificate TLS, that the pairing the host just accepted actually works.
     *
     * Two routes, and which one applies is decided by *how* `pairchallenge` failed:
     *
     * * a **transport** failure (a bare read timeout) means TLS to this host is not working at all,
     *   and repeating the same request over a different endpoint would fail identically — a real
     *   device proved that by failing the confirmation with the exact same message. So we stop and
     *   let the caller run the self-test.
     * * any **other** failure means TLS is fine and the host merely answered oddly, so the
     *   confirmation `/serverinfo` is worth retrying: spec §3.3 makes a successful
     *   client-certificate request the definition of being paired.
     */
    private suspend fun verifyWithHost(
        hostKey: String,
        address: HostAddress,
        serverInfo: ServerInfo,
        emit: suspend (PairProgress) -> Unit,
    ): Outcome {
        val reason: String
        if (silentPairChallengeHosts.containsKey(hostKey)) {
            reason = "skipped — pairchallenge went unanswered on this host earlier in the session"
            ProtocolLog.i(
                ProtocolLog.TAG_PAIR,
                "$PHASE_5_LABEL $reason; going straight to the pinned-HTTPS confirmation, which " +
                    "proves the same thing and answers faster.",
            )
        } else {
            val phase5 = httpClient.pairChallengeSecure(hostKey, address, serverInfo.httpsPort)
            val phase5Root = phase5.valueOrNull()
            if (phase5Root != null && isPaired(phase5Root)) {
                ProtocolLog.i(ProtocolLog.TAG_PAIR, "Paired with ${serverInfo.hostname ?: hostKey}")
                return Outcome(PairResult.PAIRED, null, rollBack = false)
            }
            reason = if (phase5Root != null) {
                "the host answered without <paired>1</paired> (${pairedValue(phase5Root)})"
            } else {
                phase5.errorDescription() ?: "unknown error"
            }
            ProtocolLog.w(ProtocolLog.TAG_PAIR, "$PHASE_5_LABEL did not confirm ($reason)")
            if (phase5 is NvHttpResult.TransportError) {
                // Remember it, so a retry spends its budget on something that can still succeed.
                silentPairChallengeHosts[hostKey] = true
                return Outcome(
                    PairResult.FAILED,
                    "$PHASE_5_LABEL: $reason",
                    rollBack = false,
                )
            }
        }

        var lastError = "not attempted"
        repeat(ProtocolConstants.PAIRING_CONFIRM_ATTEMPTS) { attempt ->
            emit(PairProgress.Verifying(attempt + 1, ProtocolConstants.PAIRING_CONFIRM_ATTEMPTS))
            if (attempt > 0) delay(ProtocolConstants.PAIRING_CONFIRM_RETRY_DELAY_MS)
            val confirmation = httpClient.serverInfoSecure(
                hostKey = hostKey,
                address = address,
                httpsPort = serverInfo.httpsPort,
                timeoutMs = ProtocolConstants.PAIRING_CONFIRM_TIMEOUT_MS,
                trace = "${NvHttpClient.PHASE_5_CONFIRM_LABEL} ${attempt + 1}/" +
                    "${ProtocolConstants.PAIRING_CONFIRM_ATTEMPTS}",
            )
            if (confirmation.isSuccess) {
                ProtocolLog.i(
                    ProtocolLog.TAG_PAIR,
                    "Paired with ${serverInfo.hostname ?: hostKey}: pinned HTTPS /serverinfo " +
                        "succeeded on attempt ${attempt + 1}, which only a client the host trusts " +
                        "can do. The pairchallenge leg is unreliable on this host ($reason).",
                )
                return Outcome(PairResult.PAIRED, null, rollBack = false)
            }
            lastError = confirmation.errorDescription() ?: "unknown error"
            ProtocolLog.w(
                ProtocolLog.TAG_PAIR,
                "Pairing confirmation attempt ${attempt + 1} of " +
                    "${ProtocolConstants.PAIRING_CONFIRM_ATTEMPTS} failed: $lastError",
            )
        }
        return Outcome(
            PairResult.FAILED,
            "$PHASE_5_LABEL: $reason; the follow-up pinned-HTTPS /serverinfo also failed " +
                "($lastError)",
            rollBack = false,
        )
    }

    /**
     * Works out *why* no secure call to the host succeeds, and repairs it when that is possible.
     *
     * A read timeout from `HttpsURLConnection` cannot distinguish a wrong port, a wedged HTTPS
     * service, a stalled handshake, or a TLS version the host will not complete client
     * authentication under. [TlsProbe] separates them at the byte level. When it finds a
     * configuration that does work, the transport adopts it and the confirmation is retried once
     * with it — which turns a diagnosis into a fix.
     */
    private suspend fun diagnoseTransport(
        hostKey: String,
        address: HostAddress,
        serverInfo: ServerInfo,
        previousDetail: String?,
    ): Outcome {
        val report = try {
            httpClient.diagnoseTls(hostKey, address, serverInfo.httpsPort)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ProtocolLog.w(ProtocolLog.TAG_PAIR, "TLS self-test itself failed", t)
            null
        }

        if (report != null && report.tlsWorks) {
            // The transport has already adopted the configuration that worked; one more try.
            val retry = httpClient.serverInfoSecure(
                hostKey = hostKey,
                address = address,
                httpsPort = serverInfo.httpsPort,
                timeoutMs = ProtocolConstants.PAIRING_CONFIRM_TIMEOUT_MS,
                trace = "${NvHttpClient.PHASE_5_CONFIRM_LABEL} after self-test",
            )
            if (retry.isSuccess) {
                ProtocolLog.i(
                    ProtocolLog.TAG_PAIR,
                    "Paired with ${serverInfo.hostname ?: hostKey}: the self-test found " +
                        "${report.workingProtocols} works and the confirmation then succeeded.",
                )
                return Outcome(PairResult.PAIRED, null, rollBack = false)
            }
            return Outcome(
                PairResult.FAILED,
                "the PC accepted this device and TLS to ${address.host}:${serverInfo.httpsPort} " +
                    "works, but the confirming request still failed " +
                    "(${retry.errorDescription() ?: "unknown error"}). The pairing is kept.",
                rollBack = false,
            )
        }

        val conclusion = report?.conclusion()
            ?: previousDetail
            ?: "no secure connection to ${address.host}:${serverInfo.httpsPort} could be completed"
        ProtocolLog.e(
            ProtocolLog.TAG_PAIR,
            "Transport self-test says: $conclusion",
        )
        return Outcome(
            PairResult.HOST_TLS_UNREACHABLE,
            "$conclusion. This is not a pairing problem — the PC has accepted this device and the " +
                "pairing is kept. It will start working as soon as the PC's secure service does.",
            rollBack = false,
        )
    }

    /**
     * The `/unpair` cleanup for an attempt that failed **before the host accepted us** (spec §4.0,
     * §4.8).
     *
     * Best-effort and never throws: the attempt has already failed, and an unreachable host cannot
     * be tidied up anyway. The local pin is dropped too, so a half-finished attempt cannot leave us
     * believing we are paired.
     *
     * Callers must check the rollback boundary before calling this. It is the one place that can
     * destroy a pairing, and after phase 4 there is a real one to destroy.
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

    /** Tracks which phase is running, so a thrown exception can name it and decide about rollback. */
    private class PhaseTracker {
        @Volatile
        var phase: Int = 0
    }

    /**
     * A terminal outcome.
     *
     * @property result what to tell the user.
     * @property detail a specific, human-readable explanation for the failure paths.
     * @property rollBack whether to call `/unpair` and drop the pinned certificate. **False from
     *   phase 5 onwards**: by then the host has already accepted and persisted our certificate, so
     *   rolling back destroys a real pairing and leaves the two sides disagreeing.
     */
    private class Outcome(
        val result: PairResult,
        val detail: String?,
        val rollBack: Boolean = true,
    )

    private companion object {
        const val PHASE_1_LABEL = "phase 1 (getservercert)"
        const val PHASE_2_LABEL = "phase 2 (clientchallenge)"
        const val PHASE_3_LABEL = "phase 3 (serverchallengeresp)"
        const val PHASE_4_LABEL = "phase 4 (clientpairingsecret)"
        val PHASE_5_LABEL: String = NvHttpClient.PHASE_5_LABEL

        /**
         * The phase from which the host already holds our certificate.
         *
         * Phase 4 returning `<paired>1</paired>` is the point at which a Sunshine-family host adds
         * the client to its list and writes it to disk; nothing after that may roll it back.
         */
        const val HOST_HAS_ACCEPTED_US_FROM_PHASE = 5

        /** The status code a Sunshine-family host uses for every `/pair` refusal. */
        const val STATUS_BAD_REQUEST = 400

        /**
         * True when phase 1 was refused because the host still holds an in-flight pairing session
         * for this `uniqueid`.
         *
         * The host clears the session as it refuses, so the refusal is self-healing and an
         * immediate retry succeeds. Matched on the host's own wording as well as the status code,
         * because a 400 alone has other causes.
         */
        fun isStalePairingSession(result: NvHttpResult<XmlNode>): Boolean {
            if (result !is NvHttpResult.HostError) return false
            if (result.statusCode != STATUS_BAD_REQUEST) return false
            val message = result.statusMessage?.lowercase() ?: return false
            return message.contains("out of order") || message.contains("invalid uniqueid")
        }

        /** Renders what a response actually said in `<paired>`, for a failure detail. */
        fun pairedValue(root: XmlNode): String =
            "paired=${root.textOf("paired") ?: "<absent>"}"
    }
}
