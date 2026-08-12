package com.voidlink.android.media.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output buffer sizing of `docs/01-PROTOCOL.md` §8.5, and the drift policy that keeps audio
 * from falling permanently behind video.
 */
class AudioBufferPlanTest {

    private val stereo = AudioStreamFormat.stereo()

    @Test
    fun `a 48 kHz stereo frame is four bytes`() {
        assertEquals(4, stereo.bytesPerPcmFrame)
        assertEquals(12, AudioStreamFormat(channelCount = 6, mapping = IntArray(6)).bytesPerPcmFrame)
        assertEquals(16, AudioStreamFormat(channelCount = 8, mapping = IntArray(8)).bytesPerPcmFrame)
    }

    @Test
    fun `a 5 ms packet is 240 frames of 48 kHz audio`() {
        assertEquals(240, stereo.framesPerPacket)
        assertEquals(960, stereo.bytesPerPacket)
    }

    @Test
    fun `a slow decoder's 10 ms packet is twice as much`() {
        val slow = AudioStreamFormat.stereo(packetDurationMs = 10)

        assertEquals(480, slow.framesPerPacket)
        assertEquals(1_920, slow.bytesPerPacket)
    }

    @Test
    fun `the buffer is the larger of the platform minimum and 30 ms`() {
        // 30 ms of 48 kHz stereo is 1440 frames = 5760 bytes.
        assertEquals(5_760, AudioBufferPlan.bytesFor(30, stereo))

        // A platform minimum below the target does not shrink the buffer.
        assertEquals(5_760, AudioBufferPlan.trackBufferBytes(2_000, stereo))
        // A platform minimum above it wins, because writing below the minimum is not allowed.
        assertEquals(8_000, AudioBufferPlan.trackBufferBytes(8_000, stereo))
    }

    @Test
    fun `the buffer is rounded up to a whole PCM frame`() {
        // A size that is not a multiple of the frame size is rejected outright by some devices and
        // silently rounded by others, and a track that fails to build is a session with no audio.
        assertEquals(8_004, AudioBufferPlan.trackBufferBytes(8_001, stereo))
        assertEquals(8_004, AudioBufferPlan.trackBufferBytes(8_002, stereo))
        assertEquals(8_004, AudioBufferPlan.trackBufferBytes(8_004, stereo))
    }

    @Test
    fun `a platform that reports an error still gets the 30 ms target rather than zero`() {
        // AudioTrack.getMinBufferSize returns ERROR_BAD_VALUE (-2) or ERROR (-1) for a format it
        // cannot describe. Passing that through would build a zero-byte track.
        assertEquals(5_760, AudioBufferPlan.trackBufferBytes(-2, stereo))
        assertEquals(5_760, AudioBufferPlan.trackBufferBytes(0, stereo))
    }

    @Test
    fun `the target is never an invented constant, it is 30 ms of this format`() {
        val surround = AudioStreamFormat(channelCount = 6, mapping = IntArray(6))

        // Three times the channels, three times the bytes for the same 30 ms.
        assertEquals(17_280, AudioBufferPlan.trackBufferBytes(0, surround))
    }

    @Test
    fun `backlog is what we wrote minus what the device played`() {
        val policy = AudioLatencyPolicy()

        // 4800 frames at 48 kHz is 100 ms.
        assertEquals(100, policy.backlogMs(framesWritten = 4_800, framesPlayed = 0, sampleRateHz = 48_000))
        assertEquals(50, policy.backlogMs(framesWritten = 4_800, framesPlayed = 2_400, sampleRateHz = 48_000))
        assertEquals(0, policy.backlogMs(framesWritten = 4_800, framesPlayed = 4_800, sampleRateHz = 48_000))
    }

    @Test
    fun `a playback head that ran ahead reports no backlog rather than a negative one`() {
        // Across an underrun the device advances its position through silence it inserted itself.
        // Reporting that as "we are ahead" would suppress the very drops the underrun made
        // necessary, which is the opposite of what is wanted.
        val policy = AudioLatencyPolicy()

        assertEquals(0, policy.backlogMs(framesWritten = 100, framesPlayed = 9_999, sampleRateHz = 48_000))
    }

    @Test
    fun `audio is played up to the threshold and dropped past it`() {
        val policy = AudioLatencyPolicy(maxBacklogMs = 40)

        assertTrue(policy.shouldPlay(0))
        assertTrue(policy.shouldPlay(30))
        assertTrue(policy.shouldPlay(40))
        assertFalse(policy.shouldPlay(41))
        assertFalse(policy.shouldPlay(2_000))
    }

    @Test
    fun `the drop threshold sits above the buffer target, so a full buffer is not an overrun`() {
        // The track is expected to hold about TARGET_BUFFER_MS. Treating that as a backlog would
        // drop audio constantly for doing exactly what it was configured to do.
        assertTrue(AudioBufferPlan.MAX_BACKLOG_MS > AudioBufferPlan.TARGET_BUFFER_MS)
        assertTrue(AudioLatencyPolicy().shouldPlay(AudioBufferPlan.TARGET_BUFFER_MS))
    }

    @Test
    fun `a nonsense sample rate reports no backlog rather than dividing by zero`() {
        assertEquals(0, AudioLatencyPolicy().backlogMs(1_000, 0, 0))
    }

    @Test
    fun `the threshold must be positive`() {
        val rejected = runCatching { AudioLatencyPolicy(maxBacklogMs = 0) }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }
}
