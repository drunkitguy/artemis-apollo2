package com.voidlink.android.protocol.rtp

/**
 * The elementary-stream format a negotiated session carries (spec §7.1).
 *
 * Deliberately *not* `com.voidlink.android.data.VideoCodec`: that is the user's preference and
 * includes `AUTO`, which is not a bitstream. By the time bytes are on the wire the codec has been
 * decided, and the assembler needs the decided value.
 */
enum class VideoBitstream {
    H264,
    HEVC,
    AV1,
}

/**
 * Annex-B / NAL inspection of an assembled frame (spec §7.8).
 *
 * Spec §7.8 is emphatic that a reassembled frame is fed to `MediaCodec` **verbatim** — we never
 * re-frame or rewrite NAL units. The only question we ask of the bytes is "can decoding start
 * here?", because spec §7.8 also requires that the first frame we submit be a keyframe and that
 * `BUFFER_FLAG_KEY_FRAME` be set correctly.
 *
 * Everything here is read-only and allocation-free.
 */
object AnnexB {

    /**
     * True when [data] begins with a three- or four-byte Annex-B start code.
     *
     * Used only as a diagnostic: a frame that does not start with one is either AV1 (which is not
     * start-code framed) or evidence that shard ordering went wrong.
     */
    fun startsWithStartCode(data: ByteArray, length: Int = data.size): Boolean {
        if (length < 3) return false
        if (data[0].toInt() != 0 || data[1].toInt() != 0) return false
        if (data[2].toInt() == 1) return true
        return length >= 4 && data[2].toInt() == 0 && data[3].toInt() == 1
    }

    /**
     * True when [data] contains a NAL unit (or OBU) that lets a decoder start.
     *
     * * **H.264** — an IDR slice (type 5) or an SPS (type 7).
     * * **HEVC** — an IRAP picture (types 16–21) or VPS/SPS/PPS (types 32–34).
     * * **AV1** — a sequence header OBU.
     *
     * Conservative by construction: when the bytes cannot be walked confidently the answer is
     * `false`, i.e. "not known to be a keyframe". A false negative costs one dropped frame and one
     * IDR request; a false positive hands the decoder a frame it cannot start on and produces
     * persistent corruption, which is exactly the failure this layer exists to prevent.
     */
    fun isKeyFrame(
        bitstream: VideoBitstream,
        data: ByteArray,
        length: Int = data.size,
    ): Boolean {
        val limit = if (length > data.size) data.size else length
        if (limit <= 0) return false
        return when (bitstream) {
            VideoBitstream.H264 -> h264HasKeyNal(data, limit)
            VideoBitstream.HEVC -> hevcHasKeyNal(data, limit)
            VideoBitstream.AV1 -> av1HasSequenceHeader(data, limit)
        }
    }

    /**
     * H.264: scan for `00 00 01` and read `nal_unit_type` from the low five bits of the next byte.
     *
     * A four-byte start code `00 00 00 01` contains the three-byte form at its second byte, so one
     * pattern finds both.
     */
    private fun h264HasKeyNal(data: ByteArray, length: Int): Boolean {
        var index = 0
        while (index + 3 < length) {
            if (data[index].toInt() == 0 &&
                data[index + 1].toInt() == 0 &&
                data[index + 2].toInt() == 1
            ) {
                val type = data[index + 3].toInt() and 0x1F
                if (type == RtpVideoConstants.NAL_H264_IDR ||
                    type == RtpVideoConstants.NAL_H264_SPS
                ) {
                    return true
                }
                index += 3
            } else {
                index++
            }
        }
        return false
    }

    /** HEVC: two-byte NAL header, `nal_unit_type` is bits 1–6 of the first byte. */
    private fun hevcHasKeyNal(data: ByteArray, length: Int): Boolean {
        var index = 0
        while (index + 3 < length) {
            if (data[index].toInt() == 0 &&
                data[index + 1].toInt() == 0 &&
                data[index + 2].toInt() == 1
            ) {
                val type = ((data[index + 3].toInt() and 0xFF) shr 1) and 0x3F
                val isIrap = type >= RtpVideoConstants.NAL_HEVC_IRAP_FIRST &&
                    type <= RtpVideoConstants.NAL_HEVC_IRAP_LAST
                val isParameterSet = type >= RtpVideoConstants.NAL_HEVC_PARAMETER_SET_FIRST &&
                    type <= RtpVideoConstants.NAL_HEVC_PARAMETER_SET_LAST
                if (isIrap || isParameterSet) return true
                index += 3
            } else {
                index++
            }
        }
        return false
    }

    /**
     * AV1: walk the OBU chain looking for a sequence header.
     *
     * AV1 is not start-code framed, so this is a real walk rather than a scan: read the OBU header
     * byte, skip the optional extension byte, read the `leb128` size when `obu_has_size_field` is
     * set, and step on. The walk stops — returning `false` — the moment anything does not add up:
     * a set `obu_forbidden_bit`, a missing size field (which makes the remaining OBUs
     * unwalkable), a size that runs past the end, or a step that fails to advance.
     */
    private fun av1HasSequenceHeader(data: ByteArray, length: Int): Boolean {
        var index = 0
        var scanned = 0
        while (index < length && scanned < RtpVideoConstants.MAX_OBUS_SCANNED) {
            scanned++
            val header = data[index].toInt() and 0xFF
            if ((header and 0x80) != 0) return false
            val type = (header shr 3) and 0x0F
            if (type == RtpVideoConstants.OBU_SEQUENCE_HEADER) return true

            val hasExtension = (header and 0x04) != 0
            val hasSizeField = (header and 0x02) != 0
            var cursor = index + 1
            if (hasExtension) cursor++
            if (!hasSizeField) return false

            var size = 0L
            var shift = 0
            while (true) {
                if (cursor >= length || shift > 56) return false
                val byte = data[cursor].toInt() and 0xFF
                cursor++
                size = size or ((byte and 0x7F).toLong() shl shift)
                if ((byte and 0x80) == 0) break
                shift += 7
            }

            val next = cursor.toLong() + size
            if (next <= index.toLong() || next > length.toLong()) return false
            index = next.toInt()
        }
        return false
    }
}
