package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.ProtocolLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The two encrypted-control variants `docs/01-PROTOCOL.md` §9.2 describes.
 *
 * They differ only in how the IV is derived from the sequence number, and the difference is not
 * cosmetic: a 16-byte IV where the host expects 12 produces a GCM tag mismatch on every packet, so
 * the whole stream fails closed rather than degrading.
 *
 * @property ivBytes length of the IV this variant builds.
 */
enum class ControlEncryptionVariant(val ivBytes: Int) {

    /**
     * `SS_ENC_CONTROL_V2` (Sunshine): a 12-byte IV whose first four bytes are the little-endian
     * sequence number, plus the two origin markers of [ControlConstants.IV_MARKER_CLIENT].
     */
    CONTROL_V2(ControlConstants.CONTROL_V2_IV_BYTES),

    /**
     * The older NVIDIA path: a 16-byte IV whose first byte is the **truncated** sequence number.
     *
     * Spec §9.2: "The older, non-v2 encrypted control path uses a shorter derivation with only
     * `iv[0] = seq`." That is a one-byte truncation of a 32-bit counter, which is exactly as
     * IV-reusing as it sounds — it is reproduced because interoperating requires it, not because it
     * is sound.
     */
    LEGACY(ControlConstants.LEGACY_IV_BYTES),
}

/**
 * The `SS_ENC_CONTROL_V2` envelope of spec §9.2.
 *
 * ```
 * offset 0 : uint16 encryptedHeaderType   // always 0x0001, LITTLE-ENDIAN
 * offset 2 : uint16 length                // 4 (seq) + 16 (tag) + plaintext length, LITTLE-ENDIAN
 * offset 4 : uint32 seq                   // monotonically increasing, LITTLE-ENDIAN
 * offset 8 : uint8[16] tag                // AES-GCM tag
 * offset 24: ciphertext of { V2 header + payload }
 * ```
 *
 * Two details make this worth its own file rather than a branch inside [ControlFraming]:
 *
 * 1. **The tag precedes the ciphertext on the wire**, while JCE's GCM cipher appends it. Every
 *    `seal`/`open` therefore has to move 16 bytes, and getting that backwards produces a stream
 *    that authenticates nothing while looking exactly right.
 * 2. **The plaintext is always the V2 header** (`{type, payloadLength}`) regardless of what the
 *    unencrypted stream uses — see [UnverifiedControlConstants.UNENCRYPTED_HEADER].
 *
 * **Not reached in v1.** Spec §6.5's v1 decision is `x-ss-general.encryptionEnabled=0`, so
 * [ControlStream] never constructs one of these. It exists because the framing is fully specified
 * and therefore fully testable now, and because turning encryption on later must not require
 * rediscovering where the tag goes.
 *
 * Instances are **not thread-safe**: [seal] advances the sequence counter and reuses one `Cipher`.
 * One instance belongs to one control stream, used from the control coroutine only.
 *
 * @param key the 16-byte remote-input AES key from `/launch?rikey=` (spec §5), which this stream
 *   reuses.
 * @param variant which IV derivation to use.
 * @param firstSequenceNumber the counter's starting value; zero except in tests.
 */
class ControlCrypto(
    key: ByteArray,
    private val variant: ControlEncryptionVariant = ControlEncryptionVariant.CONTROL_V2,
    firstSequenceNumber: Int = 0,
) {

    init {
        require(key.size == KEY_BYTES) { "control key must be $KEY_BYTES bytes, was ${key.size}" }
    }

    private val secretKey = SecretKeySpec(key, ControlConstants.GCM_KEY_ALGORITHM)

    private var sequenceNumber: Int = firstSequenceNumber

    /** The sequence number the next [seal] will stamp. Diagnostics and tests only. */
    val nextSequenceNumber: Int get() = sequenceNumber

    /**
     * Encrypts one control message into the envelope above.
     *
     * @param type the wire type from spec §9.3's table.
     * @param payload the message payload.
     * @return the whole ENet payload, or `null` when the platform refused the operation — which
     *   means AES-GCM is unavailable, not that the message was malformed, and is fatal for the
     *   session rather than for this packet.
     */
    fun seal(type: Int, payload: ByteArray = ControlFraming.EMPTY): ByteArray? {
        val seq = sequenceNumber
        val plaintext = ControlFraming.encode(type, payload, ControlHeaderVersion.V2)
        val sealed = try {
            cipher(Cipher.ENCRYPT_MODE, seq).doFinal(plaintext)
        } catch (failure: GeneralSecurityException) {
            ProtocolLog.e(
                ControlConstants.TAG,
                "AES-GCM encryption of control message 0x${type.toString(16)} failed",
                failure,
            )
            return null
        }
        sequenceNumber = seq + 1

        // JCE appends the tag; the wire format puts it in front of the ciphertext.
        val ciphertextLength = sealed.size - ControlConstants.GCM_TAG_BYTES
        val buffer = ByteBuffer
            .allocate(ControlConstants.ENCRYPTED_HEADER_SIZE + ciphertextLength)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(ControlConstants.ENCRYPTED_HEADER_TYPE.toShort())
        buffer.putShort((SEQ_BYTES + ControlConstants.GCM_TAG_BYTES + plaintext.size).toShort())
        buffer.putInt(seq)
        buffer.put(sealed, ciphertextLength, ControlConstants.GCM_TAG_BYTES)
        buffer.put(sealed, 0, ciphertextLength)
        return buffer.array()
    }

    /**
     * Decrypts an envelope produced by the host.
     *
     * @return the plaintext message, or `null` when the packet is not a well-formed envelope or
     *   fails authentication. Both are logged and neither is fatal: spec §9.3's v1 rule is to
     *   ignore what we cannot make sense of.
     */
    fun open(packet: ByteArray, length: Int = packet.size): ControlMessage? {
        if (length < ControlConstants.ENCRYPTED_HEADER_SIZE) {
            ProtocolLog.d(
                ControlConstants.TAG,
                "discarding a runt encrypted control packet: $length < " +
                    "${ControlConstants.ENCRYPTED_HEADER_SIZE} bytes",
            )
            return null
        }
        val buffer = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val headerType = buffer.short.toInt() and 0xFFFF
        if (headerType != ControlConstants.ENCRYPTED_HEADER_TYPE) {
            ProtocolLog.w(
                ControlConstants.TAG,
                "discarding an unencrypted packet on an encrypted control stream: " +
                    "0x${headerType.toString(16)}",
            )
            return null
        }
        buffer.short // declared length; the bytes actually present are what we trust
        val seq = buffer.int
        val tag = ByteArray(ControlConstants.GCM_TAG_BYTES)
        buffer.get(tag)
        val ciphertext = ByteArray(length - ControlConstants.ENCRYPTED_HEADER_SIZE)
        buffer.get(ciphertext)

        val combined = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

        val plaintext = try {
            cipher(Cipher.DECRYPT_MODE, seq).doFinal(combined)
        } catch (failure: GeneralSecurityException) {
            ProtocolLog.w(
                ControlConstants.TAG,
                "failed to authenticate an encrypted control packet (seq=$seq, ${length} bytes)",
                failure,
            )
            return null
        }
        return ControlFraming.decode(plaintext, ControlHeaderVersion.V2)
    }

    /**
     * The IV for [seq], built **before** the sequence number is byte-swapped onto the wire.
     *
     * Exposed rather than private because it is the single most testable thing in this file and the
     * single most likely thing to be wrong.
     */
    fun initializationVector(seq: Int): ByteArray {
        val iv = ByteArray(variant.ivBytes)
        when (variant) {
            ControlEncryptionVariant.CONTROL_V2 -> {
                iv[0] = (seq ushr 0).toByte()
                iv[1] = (seq ushr 8).toByte()
                iv[2] = (seq ushr 16).toByte()
                iv[3] = (seq ushr 24).toByte()
                iv[ControlConstants.IV_MARKER_ORIGIN_OFFSET] = ControlConstants.IV_MARKER_CLIENT
                iv[ControlConstants.IV_MARKER_STREAM_OFFSET] = ControlConstants.IV_MARKER_CLIENT
            }
            // A truncating cast, reproduced because the host does it (spec §9.2).
            ControlEncryptionVariant.LEGACY -> iv[0] = seq.toByte()
        }
        return iv
    }

    private fun cipher(mode: Int, seq: Int): Cipher {
        val cipher = Cipher.getInstance(ControlConstants.GCM_TRANSFORMATION)
        cipher.init(
            mode,
            secretKey,
            GCMParameterSpec(ControlConstants.GCM_TAG_BITS, initializationVector(seq)),
        )
        return cipher
    }

    private companion object {
        /** The remote-input key is 16 bytes (spec §5). */
        const val KEY_BYTES: Int = 16

        /** Width of the `seq` field counted by the envelope's `length`. */
        const val SEQ_BYTES: Int = 4
    }
}
