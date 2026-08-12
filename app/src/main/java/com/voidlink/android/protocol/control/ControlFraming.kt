package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.ProtocolLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Which header sits in front of a control payload (`docs/01-PROTOCOL.md` §9.2).
 *
 * @property headerSize bytes the header occupies.
 * @property label the spec's own name for it.
 */
enum class ControlHeaderVersion(val headerSize: Int, val label: String) {

    /** `{ uint16 type }`, little-endian. What the unencrypted ENet control stream carries. */
    V1(ControlConstants.HEADER_SIZE_V1, "V1"),

    /**
     * `{ uint16 type; uint16 payloadLength }`, little-endian.
     *
     * The plaintext header inside an `SS_ENC_CONTROL_V2` envelope, and — per spec §9.2's heading,
     * though not per the reference client — a possible unencrypted framing too. See
     * [UnverifiedControlConstants.UNENCRYPTED_HEADER].
     */
    V2(ControlConstants.HEADER_SIZE_V2, "V2"),
}

/**
 * One control message: a type and its payload, with no framing applied.
 *
 * Not a `data class`: [payload] is a `ByteArray`, whose generated `equals` compares identity and
 * would quietly mislead every test that used it (the same reason
 * [com.voidlink.android.protocol.rtp.VideoFrame] is not one either).
 *
 * @property type the wire type from spec §9.3's table, as an unsigned 16-bit value in an `Int`.
 * @property payload the bytes after the header; empty for a message that carries none.
 */
class ControlMessage(val type: Int, val payload: ByteArray) {

    /** The type as it appears in the spec and in logs: `0x0305`. */
    fun typeHex(): String = "0x" + type.toString(16).padStart(4, '0')

    override fun toString(): String =
        "ControlMessage(type=${typeHex()}, ${payload.size} bytes)"
}

/**
 * The `{ uint16 type; uint16 payloadLength }` little-endian framing above ENet (spec §9.2).
 *
 * Byte order is the easiest thing to get wrong in this protocol and the one thing that is fully
 * verifiable without a host, which is why every builder in this package has a hex-fixture test and
 * why nothing here ever relies on a `ByteBuffer` default: spec §0.1 says outright that the protocol
 * is inconsistent about endianness and that the order must be explicit at every call site.
 *
 * Pure functions over `ByteArray`. No sockets, no ENet, no state — [ControlStream] owns all three.
 */
object ControlFraming {

    /**
     * Frames a message for the wire.
     *
     * @param type the wire type; only the low 16 bits are written.
     * @param payload the payload bytes, copied into the result.
     * @param version which header to write. Defaults to the negotiated unencrypted framing.
     * @return `header || payload`, ready to hand to ENet as an opaque payload.
     */
    fun encode(
        type: Int,
        payload: ByteArray = EMPTY,
        version: ControlHeaderVersion = UnverifiedControlConstants.UNENCRYPTED_HEADER,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(version.headerSize + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(type.toShort())
        if (version == ControlHeaderVersion.V2) buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Parses a framed message.
     *
     * Forgiving in exactly one direction: a V2 packet whose `payloadLength` disagrees with the
     * bytes actually present is *truncated to what arrived* rather than rejected, because a host
     * that over-reports its own length still told us the type, and the type is what decides whether
     * the session is ending. A short read is logged; a runt packet with no complete header is
     * rejected outright, which is what the reference client does.
     *
     * @return the message, or `null` when [bytes] is too short to contain a header.
     */
    fun decode(
        bytes: ByteArray,
        version: ControlHeaderVersion = UnverifiedControlConstants.UNENCRYPTED_HEADER,
        length: Int = bytes.size,
    ): ControlMessage? {
        if (length < version.headerSize) {
            ProtocolLog.d(
                ControlConstants.TAG,
                "discarding a runt control packet: $length < ${version.headerSize} bytes",
            )
            return null
        }
        val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val type = buffer.short.toInt() and 0xFFFF
        val available = length - version.headerSize
        val declared = if (version == ControlHeaderVersion.V2) {
            buffer.short.toInt() and 0xFFFF
        } else {
            available
        }
        val payloadLength = if (declared <= available) {
            declared
        } else {
            ProtocolLog.w(
                ControlConstants.TAG,
                "control message 0x${type.toString(16)} declared $declared payload bytes but only " +
                    "$available arrived; using what arrived",
            )
            available
        }
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return ControlMessage(type, payload)
    }

    /**
     * A hex dump of a framed message, for the debug log spec §9.3 asks for on unknown types.
     *
     * Truncated, because a control message can carry a whole input packet and a log line that long
     * is unreadable and expensive to build on the ENet thread.
     */
    fun describe(bytes: ByteArray, limit: Int = HEX_DUMP_LIMIT): String {
        val shown = minOf(bytes.size, limit)
        val hex = Hex.encode(bytes, 0, shown)
        return if (shown == bytes.size) hex else "$hex… (${bytes.size} bytes)"
    }

    /** Shared empty payload, so a message with none allocates nothing. */
    val EMPTY: ByteArray = ByteArray(0)

    private const val HEX_DUMP_LIMIT: Int = 32
}
