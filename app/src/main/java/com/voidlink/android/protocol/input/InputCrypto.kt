package com.voidlink.android.protocol.input

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.ProtocolLog
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * How the 16-byte input IV evolves from one packet to the next (`docs/01-PROTOCOL.md` §10.1).
 *
 * Spec §10.1 is unusually direct about why this is an interface rather than four lines inside the
 * encryptor: *"the precise IV chaining rule is the least well-documented part of the input path, and
 * getting it wrong means the host silently discards all our input (no error, just an unresponsive
 * game)"*, and its implementation plan says to "put this behind a strategy interface with a second
 * implementation … selectable by a debug setting".
 *
 * A strategy owns the whole IV lifecycle: the initial value and the update after each packet.
 * Implementations are **not** thread-safe; one belongs to one [InputEncryptor], which belongs to one
 * session and is used from one sender.
 */
interface InputIvStrategy {

    /** Which mode this strategy implements, for logs and for detecting a runtime switch. */
    val mode: InputIvMode

    /** The IV for the next packet. Always [InputConstants.IV_BYTES] long. */
    fun current(): ByteArray

    /**
     * Folds the packet just produced into the IV state.
     *
     * @param sealed the encrypted blob exactly as it goes on the wire — `tag || ciphertext` for
     *   GCM — **without** the big-endian length prefix. The reference client chains from the last
     *   16 bytes of this blob, and the tag being part of it is not an accident of implementation
     *   but the thing the host mirrors.
     */
    fun advance(sealed: ByteArray)
}

/**
 * The IV derivations of spec §10.1, chosen by [InputIvMode].
 *
 * All four share the same start — `riKeyId` big-endian in bytes 0..3, the rest zero — because that
 * part is not in dispute; they differ only in what happens after a packet is sent.
 */
class ConfigurableIvStrategy(
    private val keyId: Int,
    override val mode: InputIvMode,
) : InputIvStrategy {

    private val iv: ByteArray = initialIv(keyId)

    private var counter: Int = 0

    override fun current(): ByteArray = iv.copyOf()

    override fun advance(sealed: ByteArray) {
        when (mode) {
            InputIvMode.STATIC -> Unit

            InputIvMode.COUNTER -> {
                counter++
                iv[COUNTER_OFFSET] = (counter ushr 24).toByte()
                iv[COUNTER_OFFSET + 1] = (counter ushr 16).toByte()
                iv[COUNTER_OFFSET + 2] = (counter ushr 8).toByte()
                iv[COUNTER_OFFSET + 3] = counter.toByte()
            }

            // The reference client's threshold: the whole encrypted blob, tag included, must reach
            // 32 bytes. With a 16-byte tag that means "the ciphertext is at least 16 bytes".
            InputIvMode.CHAINED_REFERENCE ->
                chainIfAtLeast(sealed, InputConstants.GCM_TAG_BYTES + InputConstants.IV_BYTES)

            // Spec §10.1's literal wording: the *ciphertext* must reach 32 bytes. Since the blob
            // carries a 16-byte tag in front, that is a 48-byte blob — a threshold no ordinary
            // input packet reaches, so in practice this mode behaves like STATIC for the mouse and
            // keyboard and chains only on the largest controller packets.
            InputIvMode.CHAINED_SPEC ->
                chainIfAtLeast(sealed, InputConstants.GCM_TAG_BYTES + SPEC_CHAIN_MIN_CIPHERTEXT)
        }
    }

    private fun chainIfAtLeast(sealed: ByteArray, minimumBlobSize: Int) {
        if (sealed.size < minimumBlobSize) return
        System.arraycopy(
            sealed,
            sealed.size - InputConstants.IV_BYTES,
            iv,
            0,
            InputConstants.IV_BYTES,
        )
    }

    companion object {

        /** Where the [InputIvMode.COUNTER] mode's big-endian message counter sits. */
        const val COUNTER_OFFSET: Int = 12

        /** Spec §10.1 step 2's threshold, expressed in ciphertext bytes. */
        const val SPEC_CHAIN_MIN_CIPHERTEXT: Int = 32

        /**
         * The IV every session starts from: `riKeyId` big-endian in bytes 0..3, zeros after.
         *
         * This is also literally what the reference client's Android front end puts in
         * `StreamConfig.remoteInputAesIv` — a `ByteBuffer.putInt(riKeyId)` into a 16-byte array —
         * so the two agree without either quoting the other.
         */
        fun initialIv(keyId: Int): ByteArray {
            val iv = ByteArray(InputConstants.IV_BYTES)
            iv[0] = (keyId ushr 24).toByte()
            iv[1] = (keyId ushr 16).toByte()
            iv[2] = (keyId ushr 8).toByte()
            iv[3] = keyId.toByte()
            return iv
        }
    }
}

/**
 * The input AES envelope of spec §10 and §10.1.
 *
 * Produces the payload that goes inside a control-stream message of type index 5:
 *
 * ```
 * [ uint32 encryptedLength  BIG-ENDIAN ]   // length of everything after it
 * [ tag(16) || ciphertext ]                // Gen >= 7, AES-128-GCM — note the tag comes FIRST
 * ```
 *
 * or, on Gen 5/6, `[ length ][ AES-128-CBC ciphertext, PKCS#7 padded ]`.
 *
 * Three things about this class exist because of spec §10.1's UNVERIFIED warning, and are the whole
 * reason it is separate from [InputSender]:
 *
 * 1. **The IV strategy is pluggable and switched at runtime.** [UnverifiedInputConstants.ivMode] is
 *    re-read before every packet; changing it mid-session rebuilds the strategy from the initial IV,
 *    so a user can bisect the rule from the in-stream settings without restarting the stream.
 * 2. **The first [UnverifiedInputConstants.HEX_LOG_PACKET_COUNT] packets are logged as hex at
 *    WARN** — plaintext, IV and sealed blob. That is what turns "the cursor does not move" from an
 *    unfalsifiable complaint into a five-minute comparison against a packet capture.
 * 3. **It reuses [com.voidlink.android.protocol.control.ControlCrypto]'s approach** — same JCE
 *    transformation, same "JCE appends the tag, the wire puts it first" correction, same
 *    fail-closed handling of a `GeneralSecurityException` — rather than inventing a third AES
 *    envelope in this codebase.
 *
 * **Not thread-safe.** Sealing advances the IV, so all calls must come from the one sender thread.
 *
 * @param key the 16-byte `riKey` from `/launch?rikey=` (spec §5).
 * @param keyId the 32-bit `riKeyId`, which seeds the IV.
 * @param profile decides GCM versus CBC, and whether input is encrypted here at all.
 */
class InputEncryptor(
    key: ByteArray,
    private val keyId: Int,
    private val profile: InputProfile,
) {

    init {
        require(key.size == InputConstants.KEY_BYTES) {
            "the remote-input key must be ${InputConstants.KEY_BYTES} bytes, was ${key.size}"
        }
    }

    private val secretKey = SecretKeySpec(key, InputConstants.KEY_ALGORITHM)

    private var strategy: InputIvStrategy =
        ConfigurableIvStrategy(keyId, UnverifiedInputConstants.ivMode)

    private var loggedPackets: Int = 0

    /** How many packets have been sealed. Diagnostics and tests only. */
    var packetsSealed: Long = 0L
        private set

    /** The IV the next packet will use. Exposed because it is the most testable thing here. */
    fun currentIv(): ByteArray = strategy.current()

    /** Which chaining rule is in force right now. */
    val ivMode: InputIvMode get() = strategy.mode

    /**
     * Encrypts one input packet into the control payload described above.
     *
     * @param packet a complete input packet from [InputPackets], header included.
     * @return the payload to hand to the control stream, or `null` when the platform refused the
     *   operation — which means AES is unavailable and every subsequent packet will fail the same
     *   way, so the caller should stop rather than retry.
     */
    fun seal(packet: ByteArray): ByteArray? {
        adoptRuntimeIvMode()

        if (profile.controlStreamEncrypted) {
            // The control stream is doing the encryption for us (spec §10.1's Gen 7.1.431+ case,
            // which v1 never negotiates). The input packet travels as plaintext with no length
            // prefix, exactly as the reference client sends it.
            logPacket(packet, null, packet)
            packetsSealed++
            return packet
        }

        val iv = strategy.current()
        val sealed = try {
            if (profile.usesGcm) sealGcm(packet, iv) else sealCbc(packet, iv)
        } catch (failure: GeneralSecurityException) {
            ProtocolLog.e(
                InputConstants.TAG,
                "AES encryption of a ${packet.size}-byte input packet failed; input is dead for " +
                    "this session",
                failure,
            )
            return null
        }

        logPacket(packet, iv, sealed)
        strategy.advance(sealed)
        packetsSealed++

        val payload = ByteArray(InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES + sealed.size)
        payload[0] = (sealed.size ushr 24).toByte()
        payload[1] = (sealed.size ushr 16).toByte()
        payload[2] = (sealed.size ushr 8).toByte()
        payload[3] = sealed.size.toByte()
        System.arraycopy(sealed, 0, payload, InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES, sealed.size)
        return payload
    }

    /**
     * AES-128-GCM, with the tag moved to the front (spec §10.1).
     *
     * JCE appends the tag to the ciphertext; the wire format puts it first. Getting that backwards
     * produces a packet of exactly the right length that authenticates nothing, which is the most
     * expensive possible way to be wrong.
     */
    private fun sealGcm(packet: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(InputConstants.GCM_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            GCMParameterSpec(InputConstants.GCM_TAG_BITS, gcmNonce(iv)),
        )
        val combined = cipher.doFinal(packet)
        val ciphertextLength = combined.size - InputConstants.GCM_TAG_BYTES

        val sealed = ByteArray(combined.size)
        System.arraycopy(combined, ciphertextLength, sealed, 0, InputConstants.GCM_TAG_BYTES)
        System.arraycopy(combined, 0, sealed, InputConstants.GCM_TAG_BYTES, ciphertextLength)
        return sealed
    }

    /**
     * AES-128-CBC with PKCS#7 padding, for Gen 5 and Gen 6 (spec §10.1).
     *
     * The IV never chains here: the reference client gates chaining on Gen 7, so every CBC message
     * uses the same `riKeyId`-derived IV. Padding to the block size is required rather than
     * optional — spec §10.1 says "each message padded to the 16-byte block size" — because an
     * unpadded final block would be held back inside the cipher until the *next* message arrived,
     * which for input means every keystroke landing one keystroke late.
     */
    private fun sealCbc(packet: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(InputConstants.CBC_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(packet)
    }

    /**
     * The 12 bytes of the protocol's 16-byte IV that become the GCM nonce (spec §10.1).
     *
     * See [UnverifiedInputConstants.useFirstTwelveIvBytes] — including the note that OpenSSL does
     * not truncate at all, which is the possibility neither branch here covers.
     */
    private fun gcmNonce(iv: ByteArray): ByteArray =
        if (UnverifiedInputConstants.useFirstTwelveIvBytes) {
            iv.copyOfRange(0, InputConstants.GCM_IV_BYTES)
        } else {
            iv.copyOfRange(iv.size - InputConstants.GCM_IV_BYTES, iv.size)
        }

    /**
     * Rebuilds the strategy when the debug setting changed under us.
     *
     * Restarting from the initial IV rather than keeping the current one is deliberate: after a
     * switch the host and client disagree anyway, and a known starting point is the only state from
     * which the new mode can possibly be right.
     */
    private fun adoptRuntimeIvMode() {
        val requested = UnverifiedInputConstants.ivMode
        if (requested == strategy.mode) return
        ProtocolLog.w(
            InputConstants.TAG,
            "input IV mode changed from ${strategy.mode} to $requested after $packetsSealed " +
                "packets; restarting the IV from riKeyId",
        )
        strategy = ConfigurableIvStrategy(keyId, requested)
        // Log the next few packets again: the whole point of a switch is comparing what came out.
        loggedPackets = 0
    }

    /**
     * Logs the first few packets as hex at WARN (spec §10.1's mitigation).
     *
     * WARN rather than DEBUG on purpose. A user reporting "input does nothing" will send a logcat
     * captured with default filters, and these lines have to be in it — they are the only artefact
     * that can settle the IV rule without a rebuild. The volume is bounded to
     * [UnverifiedInputConstants.HEX_LOG_PACKET_COUNT] packets per session, plus another burst after
     * a runtime mode switch.
     *
     * Nothing secret is printed: the key and the key id stay out of it, the IV is derived from a
     * per-session id that already travelled in a `/launch` query string, and the plaintext is a
     * mouse delta.
     */
    private fun logPacket(plaintext: ByteArray, iv: ByteArray?, sealed: ByteArray) {
        if (loggedPackets >= UnverifiedInputConstants.HEX_LOG_PACKET_COUNT) return
        loggedPackets++
        val magic = if (plaintext.size >= InputConstants.HEADER_SIZE) {
            Hex.encode(plaintext, InputConstants.SIZE_FIELD_BYTES, 4)
        } else {
            "short"
        }
        ProtocolLog.w(
            InputConstants.TAG,
            "input packet #$loggedPackets magicLE=$magic mode=${strategy.mode} " +
                "cipher=${cipherName()} iv=${iv?.let { Hex.encode(it) } ?: "none"} " +
                "plaintext=${Hex.encode(plaintext)} sealed=${Hex.encode(sealed)}",
        )
    }

    private fun cipherName(): String = when {
        profile.controlStreamEncrypted -> "none (control stream encrypts)"
        profile.usesGcm -> "AES-128-GCM"
        else -> "AES-128-CBC"
    }
}
