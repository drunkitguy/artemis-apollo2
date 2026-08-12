package com.voidlink.android.media.audio

import com.voidlink.android.media.MediaClock

/**
 * A [MediaClock] the test moves by hand.
 *
 * A local one rather than `com.voidlink.android.media.FakeClock` so that the audio tests carry no
 * dependency on the video test fixtures — the two trees are owned by different workstreams and
 * neither should be able to break the other's suite by renaming a helper.
 *
 * @property micros the current time, freely assignable.
 */
class FakeAudioClock(var micros: Long = 0L) : MediaClock {
    override fun nowMicros(): Long = micros

    /** Advances the clock by [delta] microseconds. */
    fun advance(delta: Long) {
        micros += delta
    }
}

/**
 * An [AudioCodecDriver] that records what it was asked to do and lets the test play the platform's
 * side.
 *
 * This is the boundary that makes [AudioDecoderCore] testable at all: `MediaCodec` and `AudioTrack`
 * cannot run in CI, so the fake stands in for both and the test drives the playback head, the
 * underrun counter and decode failures directly.
 *
 * The default behaviour is a perfect device: every packet decodes to exactly one packet's worth of
 * frames, and every written frame is accepted. [playbackHeadFrames] stays where the test puts it,
 * which is what makes a backlog — a device that plays nothing while we keep writing.
 */
class FakeAudioCodecDriver(
    override val name: String = "fake.opus.decoder",
    private val framesPerPacket: Int = 240,
) : AudioCodecDriver {

    /** One recorded call to [decode]. */
    data class Decoded(
        val length: Int,
        val concealment: Boolean,
        val presentationTimeUs: Long,
        val played: Boolean,
    )

    /** Every packet handed to the codec, in order — played or not. */
    val decoded: MutableList<Decoded> = mutableListOf()

    /** Set to make [start] throw, standing in for a device with no Opus decoder. */
    var failToStart: Boolean = false

    /** Set to make [decode] return a failure. */
    var failDecode: Boolean = false

    /** Set to make [decode] throw, standing in for a vendor codec in an unexpected state. */
    var throwOnDecode: Boolean = false

    /** How many frames the output device accepts per write. Fewer than a packet models a full buffer. */
    var framesAcceptedPerWrite: Int = framesPerPacket

    /** What [playbackPositionFrames] reports. The test moves this to simulate playback. */
    var playbackHeadFrames: Long = 0L

    /** What [underrunCount] reports. `-1` models a platform that cannot say. */
    var underruns: Int = 0

    var started: Boolean = false
        private set

    var released: Boolean = false
        private set

    var flushes: Int = 0
        private set

    override fun start() {
        if (failToStart) throw IllegalStateException("no audio/opus decoder on this device")
        started = true
    }

    override fun decode(
        packet: ByteArray?,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        play: Boolean,
    ): Int {
        if (throwOnDecode) throw IllegalStateException("codec is in an error state")
        decoded += Decoded(
            length = if (packet == null) 0 else length,
            concealment = packet == null,
            presentationTimeUs = presentationTimeUs,
            played = play,
        )
        if (failDecode) return -1
        return if (play) framesAcceptedPerWrite else 0
    }

    override fun playbackPositionFrames(): Long = playbackHeadFrames

    override fun underrunCount(): Int = underruns

    override fun flush() {
        flushes++
    }

    override fun release() {
        released = true
    }

    /** Packets whose PCM actually reached the output device. */
    fun playedCount(): Int = decoded.count { it.played }

    /** Packets that were decoded and thrown away to stop latency accumulating. */
    fun droppedCount(): Int = decoded.count { !it.played }
}
