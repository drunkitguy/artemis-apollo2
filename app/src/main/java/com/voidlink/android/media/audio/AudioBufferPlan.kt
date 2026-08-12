package com.voidlink.android.media.audio

/**
 * How large the output buffer is, and when audio is thrown away rather than queued (spec §8.5).
 *
 * Both halves of this file exist because of the same property of audio, which has no video
 * equivalent: **audio latency never recovers on its own.** A video decoder that falls a frame behind
 * catches up the moment it decodes faster than real time, and dropping a frame costs one frame. An
 * audio track plays at exactly 48 000 samples per second and not one sample faster, so every sample
 * handed to it that it did not need *permanently* delays every sample behind it. Audio that has
 * drifted 200 ms behind video stays 200 ms behind video for the rest of the session.
 *
 * There is therefore only one correct response to a backlog, and it is to drop.
 */
object AudioBufferPlan {

    /**
     * How much audio the output track is allowed to hold, in milliseconds.
     *
     * Spec §8.5: "`max(AudioTrack.getMinBufferSize(...), bytesFor(30 ms))` — small, because latency
     * matters more than robustness here."
     */
    const val TARGET_BUFFER_MS: Int = 30

    /**
     * How far behind live playback may fall before packets start being discarded.
     *
     * Larger than [TARGET_BUFFER_MS] on purpose: the track's own buffer is expected to hold about
     * 30 ms, and treating that as an overrun would drop audio constantly. This is the point past
     * which the backlog is no longer the buffer doing its job but latency accumulating.
     */
    const val MAX_BACKLOG_MS: Int = 40

    private const val MILLIS_PER_SECOND: Int = 1_000

    /** PCM sample frames in [durationMs] at [sampleRateHz], rounded down. */
    fun framesFor(durationMs: Int, sampleRateHz: Int): Int =
        sampleRateHz * durationMs / MILLIS_PER_SECOND

    /** Bytes of 16-bit PCM in [durationMs] of [format]. */
    fun bytesFor(durationMs: Int, format: AudioStreamFormat): Int =
        framesFor(durationMs, format.sampleRateHz) * format.bytesPerPcmFrame

    /**
     * The `AudioTrack` buffer size, in bytes (spec §8.5).
     *
     * `max(minBufferBytes, bytesFor(30 ms))`, rounded **up** to a whole PCM sample frame — a buffer
     * size that is not a multiple of the frame size is rejected by some devices and silently rounded
     * by others, and a track that fails to construct is a session with no audio.
     *
     * @param minBufferBytes what `AudioTrack.getMinBufferSize` returned. A non-positive value means
     *   the platform reported an error for this format, in which case the 30 ms figure stands alone
     *   — the caller has already decided to attempt the track anyway, and a floor of our own is a
     *   better guess than zero.
     * @param format the stream being played.
     * @param targetMs the latency target; [TARGET_BUFFER_MS] unless a test says otherwise.
     */
    fun trackBufferBytes(
        minBufferBytes: Int,
        format: AudioStreamFormat,
        targetMs: Int = TARGET_BUFFER_MS,
    ): Int {
        val target = bytesFor(targetMs, format)
        val floor = if (minBufferBytes > 0) maxOf(minBufferBytes, target) else target
        return roundUpToFrame(floor, format.bytesPerPcmFrame)
    }

    /** Rounds [bytes] up to the next whole PCM sample frame. */
    fun roundUpToFrame(bytes: Int, bytesPerFrame: Int): Int {
        if (bytesPerFrame <= 0) return bytes
        val remainder = bytes % bytesPerFrame
        return if (remainder == 0) bytes else bytes + (bytesPerFrame - remainder)
    }
}

/**
 * The drift policy: how far behind is too far, and what to do about it.
 *
 * Separated from [AudioBufferPlan] so that the *decision* is a value a test can drive directly, and
 * so that [AudioDecoderCore] contains no arithmetic of its own about latency.
 *
 * @property maxBacklogMs the threshold past which a packet is decoded but not played. See
 *   [AudioBufferPlan.MAX_BACKLOG_MS].
 */
class AudioLatencyPolicy(
    val maxBacklogMs: Int = AudioBufferPlan.MAX_BACKLOG_MS,
) {

    init {
        require(maxBacklogMs > 0) { "maxBacklogMs must be positive, was $maxBacklogMs" }
    }

    /**
     * How much audio has been handed to the output device but not yet played, in milliseconds.
     *
     * @param framesWritten sample frames accepted by the output device since it started.
     * @param framesPlayed sample frames it reports having played.
     * @return the backlog, never negative. A negative raw difference means the platform's playback
     *   head ran ahead of what we wrote — which happens across an underrun, when the device
     *   advances its position through silence it inserted itself — and reporting that as "we are
     *   ahead" would then suppress the very drops the underrun made necessary.
     */
    fun backlogMs(framesWritten: Long, framesPlayed: Long, sampleRateHz: Int): Int {
        if (sampleRateHz <= 0) return 0
        val backlog = framesWritten - framesPlayed
        if (backlog <= 0L) return 0
        return ((backlog * MILLIS_PER_SECOND) / sampleRateHz).toInt()
    }

    /**
     * Whether a packet decoded now should be played or discarded.
     *
     * Discarding is not a failure and is not rare — it is how a stream that fell behind during a
     * loading screen becomes a stream that is in sync again, in a fraction of a second, without
     * anything else in the pipeline having to know it happened.
     */
    fun shouldPlay(backlogMs: Int): Boolean = backlogMs <= maxBacklogMs

    private companion object {
        const val MILLIS_PER_SECOND: Long = 1_000L
    }
}
