package com.voidlink.android.protocol.rtp

/**
 * Builds synthetic video datagrams straight from the byte layout of `docs/01-PROTOCOL.md` §7.3 and
 * §7.4.
 *
 * The whole point of this file is that it is written from the **spec**, not from the parser: it
 * lays bytes down with its own endianness helpers, so a test that round-trips through
 * [VideoPacketParser] is comparing two independent readings of the same document rather than
 * agreeing with itself.
 *
 * That is also why the packet layer can be verified this thoroughly without a host — every field
 * is defined in the spec and none of it depends on a running PC.
 */
object VideoPacketFixtures {

    const val DEFAULT_SSRC: Int = 0x11223344

    /** Builds one datagram. Every field defaults to something valid so tests name only what matters. */
    @Suppress("LongParameterList")
    fun packet(
        sequenceNumber: Int,
        frameIndex: Long = 1L,
        fecIndex: Int = 0,
        dataShards: Int = 1,
        fecPercentage: Int = 0,
        payload: ByteArray = ByteArray(0),
        flags: Int = RtpVideoConstants.FLAG_CONTAINS_PIC_DATA,
        extraFlags: Int = 0,
        multiFecFlags: Int = 0,
        multiFecBlocks: Int = 1,
        timestamp: Int = 0,
        rtpFlags: Int = 0x80,
        payloadType: Int = 0,
        streamPacketIndex: Int = 0,
        ssrc: Int = DEFAULT_SSRC,
        extension: ByteArray? = null,
    ): ByteArray {
        val rtpSize = if (extension == null) 12 else 16
        require(extension == null || extension.size == 4) { "an RTP extension is four bytes" }
        val out = ByteArray(rtpSize + 16 + payload.size)

        out[0] = rtpFlags.toByte()
        out[1] = payloadType.toByte()
        writeBeU16(out, 2, sequenceNumber)
        writeBeI32(out, 4, timestamp)
        writeBeI32(out, 8, ssrc)
        if (extension != null) System.arraycopy(extension, 0, out, 12, 4)

        val nv = rtpSize
        writeLeI32(out, nv + 0, streamPacketIndex)
        writeLeI32(out, nv + 4, (frameIndex and 0xFFFFFFFFL).toInt())
        out[nv + 8] = flags.toByte()
        out[nv + 9] = extraFlags.toByte()
        out[nv + 10] = multiFecFlags.toByte()
        out[nv + 11] = multiFecBlocks.toByte()
        writeLeI32(out, nv + 12, fecInfo(fecIndex, fecPercentage, dataShards))

        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, nv + 16, payload.size)
        return out
    }

    /**
     * Packs the `fecInfo` word exactly as the table in spec §7.4 describes it:
     * `dataShards` at bits 22–31, `fecIndex` at bits 12–21, `fecPercentage` at bits 4–11.
     */
    fun fecInfo(fecIndex: Int, fecPercentage: Int, dataShards: Int): Int =
        ((dataShards and 0x3FF) shl 22) or
            ((fecIndex and 0x3FF) shl 12) or
            ((fecPercentage and 0xFF) shl 4)

    /**
     * Builds a whole frame's worth of data-shard datagrams, in transmission order.
     *
     * `FLAG_SOF` is set on the first and `FLAG_EOF` on the last, as spec §7.4 describes; sequence
     * numbers run consecutively from [baseSequenceNumber], wrapping.
     */
    fun frame(
        frameIndex: Long,
        baseSequenceNumber: Int,
        payloads: List<ByteArray>,
        fecPercentage: Int = 0,
        timestamp: Int = 0,
        extraFlags: Int = 0,
        multiFecFlags: Int = 0,
        multiFecBlocks: Int = 1,
        carriesPictureData: Boolean = true,
    ): List<ByteArray> {
        val shards = payloads.size
        return payloads.mapIndexed { index, payload ->
            var flags = if (carriesPictureData) RtpVideoConstants.FLAG_CONTAINS_PIC_DATA else 0
            if (index == 0) flags = flags or RtpVideoConstants.FLAG_SOF
            if (index == shards - 1) flags = flags or RtpVideoConstants.FLAG_EOF
            packet(
                sequenceNumber = SequenceNumbers.advance(baseSequenceNumber, index),
                frameIndex = frameIndex,
                fecIndex = index,
                dataShards = shards,
                fecPercentage = fecPercentage,
                payload = payload,
                flags = flags,
                extraFlags = extraFlags,
                multiFecFlags = multiFecFlags,
                multiFecBlocks = multiFecBlocks,
                timestamp = timestamp,
            )
        }
    }

    /** A minimal H.264 Annex-B IDR fragment: a four-byte start code and NAL type 5. */
    fun h264IdrPayload(vararg trailing: Int): ByteArray {
        val head = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)
        if (trailing.isEmpty()) return head
        val tail = ByteArray(trailing.size) { trailing[it].toByte() }
        return head + tail
    }

    /** A minimal H.264 Annex-B non-IDR fragment: a four-byte start code and NAL type 1. */
    fun h264NonIdrPayload(vararg trailing: Int): ByteArray {
        val head = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41)
        if (trailing.isEmpty()) return head
        val tail = ByteArray(trailing.size) { trailing[it].toByte() }
        return head + tail
    }

    /** A payload spelled out byte by byte, so a test reads like the bytes it asserts. */
    fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun writeBeU16(out: ByteArray, index: Int, value: Int) {
        out[index] = ((value ushr 8) and 0xFF).toByte()
        out[index + 1] = (value and 0xFF).toByte()
    }

    private fun writeBeI32(out: ByteArray, index: Int, value: Int) {
        out[index] = ((value ushr 24) and 0xFF).toByte()
        out[index + 1] = ((value ushr 16) and 0xFF).toByte()
        out[index + 2] = ((value ushr 8) and 0xFF).toByte()
        out[index + 3] = (value and 0xFF).toByte()
    }

    private fun writeLeI32(out: ByteArray, index: Int, value: Int) {
        out[index] = (value and 0xFF).toByte()
        out[index + 1] = ((value ushr 8) and 0xFF).toByte()
        out[index + 2] = ((value ushr 16) and 0xFF).toByte()
        out[index + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
