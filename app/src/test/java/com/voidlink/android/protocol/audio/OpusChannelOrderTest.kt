package com.voidlink.android.protocol.audio

import com.voidlink.android.protocol.rtsp.OpusMultistreamConfig
import com.voidlink.android.protocol.rtsp.SessionDescription
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The channel-order fix-up of `docs/01-PROTOCOL.md` §8.3, and the `AudioTrack` masks of §8.2.
 *
 * This is the test that matters most in the audio path and the one whose absence would be least
 * noticeable. Getting the mapping wrong produces audio that plays perfectly, at the right volume,
 * with no error anywhere, and puts centre-channel dialogue in a surround speaker — which a quick
 * listen on a stereo phone cannot detect at all.
 */
class OpusChannelOrderTest {

    @Test
    fun `the wire order and the playback order differ in exactly where LFE sits`() {
        // Spec §8.3: the host sends FL FR C RL RR SL SR LFE; decoders want FL FR C LFE RL RR SL SR.
        assertEquals(listOf("FL", "FR", "C", "RL", "RR", "SL", "SR", "LFE"), OpusChannelOrder.WIRE_ORDER)
        assertEquals(listOf("FL", "FR", "C", "LFE", "RL", "RR", "SL", "SR"), OpusChannelOrder.PLAYBACK_ORDER)
        assertEquals("LFE", OpusChannelOrder.PLAYBACK_ORDER[OpusChannelOrder.PLAYBACK_LFE_INDEX])
        assertEquals(
            OpusChannelOrder.WIRE_ORDER.toSet(),
            OpusChannelOrder.PLAYBACK_ORDER.toSet(),
        )
    }

    @Test
    fun `5_1 moves LFE from the end to index three and slides the rest up`() {
        // An identity wire mapping makes the transform itself visible: entry i of the result says
        // which decoded channel feeds playback speaker i.
        val wire = intArrayOf(0, 1, 2, 3, 4, 5)

        val playback = OpusChannelOrder.remapWireToPlayback(wire, 6)

        // FL FR C keep their sources; LFE (wire slot 5) moves to slot 3; RL and RR slide up.
        assertArrayEquals(intArrayOf(0, 1, 2, 5, 3, 4), playback)
    }

    @Test
    fun `7_1 moves LFE from the end to index three and slides four channels up`() {
        val wire = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

        val playback = OpusChannelOrder.remapWireToPlayback(wire, 8)

        // LFE (wire slot 7) moves to slot 3; RL RR SL SR all slide up one place.
        assertArrayEquals(intArrayOf(0, 1, 2, 7, 3, 4, 5, 6), playback)
    }

    @Test
    fun `a non-identity 5_1 mapping is permuted rather than sorted`() {
        // A host is free to hand us any permutation; the fix-up must move *positions*, not values.
        val wire = intArrayOf(4, 0, 5, 1, 2, 3)

        val playback = OpusChannelOrder.remapWireToPlayback(wire, 6)

        assertArrayEquals(intArrayOf(4, 0, 5, 3, 1, 2), playback)
    }

    @Test
    fun `stereo is left exactly as it is`() {
        // Spec §8.3 requires the fix-up "for 6 and 8 channels". Applying it to stereo would index
        // past the end of a two-entry mapping.
        assertArrayEquals(intArrayOf(0, 1), OpusChannelOrder.remapWireToPlayback(intArrayOf(0, 1), 2))
    }

    @Test
    fun `applying the fix-up twice does not restore the original`() {
        // The hazard the documentation warns about: NegotiatedSession.opusConfig has already been
        // through this once, so a second application silently breaks the layout.
        val wire = intArrayOf(0, 1, 2, 3, 4, 5)

        val once = OpusChannelOrder.remapWireToPlayback(wire, 6)
        val twice = OpusChannelOrder.remapWireToPlayback(once, 6)

        assertFalse(once.contentEquals(twice))
        assertArrayEquals(intArrayOf(0, 1, 2, 4, 5, 3), twice)
    }

    @Test
    fun `the RTSP layer applies exactly this transform when it parses the SDP`() {
        // The transform has one implementation on purpose. This pins the fact that the SDP parser
        // and this object cannot drift apart: they are the same code.
        val sdp = SessionDescription.parse("a=fmtp:97 surround-params=6420154323\r\n")

        val config = requireNotNull(OpusMultistreamConfig.parseSurround(sdp, 6))

        assertEquals(6, config.channelCount)
        assertEquals(4, config.streams)
        assertEquals(2, config.coupledStreams)
        // Digits after "6" are streams=4, coupled=2, then the wire mapping 0 1 5 4 3 2 3 -> the
        // parser reads six of them: 0,1,5,4,3,2. LFE (last) moves to index 3.
        assertArrayEquals(
            OpusChannelOrder.remapWireToPlayback(intArrayOf(0, 1, 5, 4, 3, 2), 6),
            config.mapping,
        )
    }

    @Test
    fun `each tabulated layout has the AudioTrack mask its speakers imply`() {
        assertEquals(0x0C, OpusChannelOrder.maskFor(2))
        assertEquals(0xFC, OpusChannelOrder.maskFor(6))
        assertEquals(0x18FC, OpusChannelOrder.maskFor(8))
    }

    @Test
    fun `7_1 is 5_1 plus the two side channels`() {
        val sideLeft = 0x800
        val sideRight = 0x1000

        assertEquals(
            OpusChannelOrder.CHANNEL_OUT_5POINT1 or sideLeft or sideRight,
            OpusChannelOrder.CHANNEL_OUT_7POINT1_SURROUND,
        )
    }

    @Test
    fun `a layout spec section 8_2 does not tabulate has no mask and is not supported`() {
        assertNull(OpusChannelOrder.maskFor(1))
        assertNull(OpusChannelOrder.maskFor(4))
        assertNull(OpusChannelOrder.maskFor(7))
        assertFalse(OpusChannelOrder.isSupportedLayout(4))
        assertTrue(OpusChannelOrder.isSupportedLayout(2))
        assertTrue(OpusChannelOrder.isSupportedLayout(6))
        assertTrue(OpusChannelOrder.isSupportedLayout(8))
    }

    @Test
    fun `the log line names the speaker each mapping entry feeds`() {
        val described = OpusChannelOrder.describePlaybackOrder(intArrayOf(0, 1, 2, 5, 3, 4))

        assertEquals("FL<-0 FR<-1 C<-2 LFE<-5 RL<-3 RR<-4", described)
    }
}
