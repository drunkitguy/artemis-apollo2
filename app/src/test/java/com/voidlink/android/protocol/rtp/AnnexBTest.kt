package com.voidlink.android.protocol.rtp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyframe detection in an assembled frame (`docs/01-PROTOCOL.md` §7.8).
 *
 * Spec §7.8 requires two things of this: `BUFFER_FLAG_KEY_FRAME` must be right, and the *first*
 * frame submitted must be a keyframe. Both failures are asymmetric — a missed keyframe costs one
 * dropped frame and an IDR request, while a falsely claimed one hands the decoder a frame it
 * cannot start on and produces corruption that persists — so every ambiguous case here must answer
 * "no".
 */
class AnnexBTest {

    private fun bytes(vararg values: Int): ByteArray = VideoPacketFixtures.bytes(*values)

    @Test
    fun `h264 IDR and SPS NAL units are keyframes`() {
        // Four-byte start code, NAL type 5 (IDR).
        assertTrue(
            AnnexB.isKeyFrame(VideoBitstream.H264, bytes(0x00, 0x00, 0x00, 0x01, 0x65, 0xAA)),
        )
        // Three-byte start code, NAL type 7 (SPS).
        assertTrue(AnnexB.isKeyFrame(VideoBitstream.H264, bytes(0x00, 0x00, 0x01, 0x67, 0x42)))
    }

    @Test
    fun `an h264 frame of only non-IDR slices is not a keyframe`() {
        // NAL type 1 (non-IDR slice), twice.
        val frame = bytes(
            0x00, 0x00, 0x00, 0x01, 0x41, 0x9A,
            0x00, 0x00, 0x00, 0x01, 0x41, 0x9B,
        )
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.H264, frame))
    }

    @Test
    fun `an h264 keyframe is found even when it is not the first NAL unit`() {
        // A real IDR access unit is SPS, PPS, then the slice — but the scan must not assume order.
        val frame = bytes(
            0x00, 0x00, 0x00, 0x01, 0x09, 0x10,
            0x00, 0x00, 0x00, 0x01, 0x68, 0xCE,
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88,
        )
        assertTrue(AnnexB.isKeyFrame(VideoBitstream.H264, frame))
    }

    @Test
    fun `hevc IRAP and parameter-set NAL units are keyframes`() {
        // HEVC reads the type from bits 1..6, so the byte is the type shifted left once.
        for (type in 16..21) {
            val frame = bytes(0x00, 0x00, 0x00, 0x01, type shl 1, 0x01)
            assertTrue("type $type", AnnexB.isKeyFrame(VideoBitstream.HEVC, frame))
        }
        for (type in 32..34) {
            val frame = bytes(0x00, 0x00, 0x00, 0x01, type shl 1, 0x01)
            assertTrue("type $type", AnnexB.isKeyFrame(VideoBitstream.HEVC, frame))
        }
    }

    @Test
    fun `an hevc trailing picture is not a keyframe`() {
        // Types 0..9 are trailing/leading pictures; 40+ are unspecified.
        for (type in 0..9) {
            val frame = bytes(0x00, 0x00, 0x00, 0x01, type shl 1, 0x01)
            assertFalse("type $type", AnnexB.isKeyFrame(VideoBitstream.HEVC, frame))
        }
    }

    @Test
    fun `the h264 and hevc readings of the same byte differ`() {
        // 0x40 is HEVC type 32 (VPS) and H_264 type 0 (unspecified). Reading one as the other is
        // the single most likely mistake here, so it is pinned.
        val frame = bytes(0x00, 0x00, 0x00, 0x01, 0x40, 0x01)
        assertTrue(AnnexB.isKeyFrame(VideoBitstream.HEVC, frame))
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.H264, frame))
    }

    @Test
    fun `av1 finds a sequence header at the head of the OBU chain`() {
        // OBU header: type 1 (sequence header) at bits 3..6, has_size_field set.
        val frame = bytes(0x0A, 0x02, 0x00, 0x00)
        assertTrue(AnnexB.isKeyFrame(VideoBitstream.AV1, frame))
    }

    @Test
    fun `av1 walks past a temporal delimiter to find the sequence header`() {
        // Type 2 (temporal delimiter, size 0), then type 1 (sequence header, size 2).
        val frame = bytes(0x12, 0x00, 0x0A, 0x02, 0x11, 0x22)
        assertTrue(AnnexB.isKeyFrame(VideoBitstream.AV1, frame))
    }

    @Test
    fun `av1 reads multi-byte leb128 sizes`() {
        // Temporal delimiter with a two-byte leb128 size of 130, then a sequence header.
        val payload = IntArray(130) { 0 }
        val frame = bytes(0x12, 0x82, 0x01, *payload, 0x0A, 0x01, 0x00)
        assertTrue(AnnexB.isKeyFrame(VideoBitstream.AV1, frame))
    }

    @Test
    fun `av1 gives up rather than guessing when the chain cannot be walked`() {
        // No size field: the rest of the chain is unwalkable, so the answer must be "not known".
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.AV1, bytes(0x10, 0x00, 0x0A, 0x02)))
        // Forbidden bit set: not an OBU header at all.
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.AV1, bytes(0x8A, 0x02, 0x00, 0x00)))
        // Size runs past the end of the frame.
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.AV1, bytes(0x12, 0x40, 0x00)))
        // Truncated leb128.
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.AV1, bytes(0x12, 0x82)))
    }

    @Test
    fun `an av1 frame with no sequence header is not a keyframe`() {
        // Temporal delimiter then a frame OBU (type 6), no sequence header anywhere.
        val frame = bytes(0x12, 0x00, 0x32, 0x02, 0xAA, 0xBB)
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.AV1, frame))
    }

    @Test
    fun `empty and truncated frames are never keyframes`() {
        for (bitstream in VideoBitstream.values()) {
            assertFalse(bitstream.name, AnnexB.isKeyFrame(bitstream, ByteArray(0)))
            assertFalse(bitstream.name, AnnexB.isKeyFrame(bitstream, bytes(0x00, 0x00)))
        }
    }

    @Test
    fun `start codes are recognised in both lengths`() {
        assertTrue(AnnexB.startsWithStartCode(bytes(0x00, 0x00, 0x01, 0x65)))
        assertTrue(AnnexB.startsWithStartCode(bytes(0x00, 0x00, 0x00, 0x01, 0x65)))
        assertFalse(AnnexB.startsWithStartCode(bytes(0x00, 0x01, 0x00, 0x65)))
        assertFalse(AnnexB.startsWithStartCode(bytes(0x00, 0x00)))
    }

    @Test
    fun `a length shorter than the array is honoured`() {
        val frame = bytes(0x00, 0x00, 0x00, 0x01, 0x65)
        assertTrue(AnnexB.isKeyFrame(VideoBitstream.H264, frame, 5))
        // Cutting the frame before the NAL type byte must not read it anyway.
        assertFalse(AnnexB.isKeyFrame(VideoBitstream.H264, frame, 4))
    }
}
