package com.voidlink.android.media.audio

import com.voidlink.android.media.MediaClock

/** The phases an [AudioDecoderCore] moves through. Smaller than video's: audio never recovers. */
enum class AudioDecoderPhase {
    /** Created but not started. */
    IDLE,

    /** The codec is running and packets are worth sending. */
    RUNNING,

    /** The codec failed. Audio is over; **the session is not**. */
    FAILED,

    /** Released. Terminal. */
    RELEASED,
}

/**
 * The audio decoder's brain: concealment, drift, drop accounting and error tolerance.
 *
 * Everything here is plain Kotlin over an [AudioCodecDriver] with no Android types anywhere, for
 * the reason [com.voidlink.android.media.VideoDecoderCore] gives: this is the logic that most needs
 * testing and `MediaCodec` cannot run in CI.
 *
 * ### What it guarantees
 *
 * * **Latency never accumulates.** Before every packet, the backlog is measured from what the
 *   output device says it has played. Past [AudioLatencyPolicy.maxBacklogMs] the packet is decoded
 *   and thrown away rather than queued. This is the whole point of the class: a video decoder that
 *   falls behind catches up, and an audio track — which plays at exactly one second per second —
 *   never does.
 * * **A gap becomes exactly one packet of concealment.** Spec §8.5: `MediaCodec` has no PLC API, so
 *   a missing packet is filled with [AudioStreamFormat.packetDurationMs] of silence, which keeps
 *   the timeline aligned instead of shortening it. Concealment is subject to the same drift gate as
 *   real audio, so a burst of loss during a stall does not itself become a backlog.
 * * **A failing codec ends audio, not the session.** [maxConsecutiveErrors] decode failures in a
 *   row move the phase to [AudioDecoderPhase.FAILED] and emit [AudioPlaybackEvent.Stopped]. Nothing
 *   here ever throws at its caller.
 *
 * ### Threading
 *
 * [submit] is called from one thread — the audio decode pump. [release] may be called from another.
 * All mutable state is guarded by one lock, and events are dispatched *after* the lock is released
 * so a listener may call back in without deadlocking.
 *
 * @param driver the codec and output device to drive.
 * @param format the format the driver was configured with.
 * @param policy the drift policy. See [AudioLatencyPolicy].
 * @param clock timestamp source, injectable for tests.
 * @param maxConsecutiveErrors decode failures in a row before audio gives up.
 * @param onEvent listener for [AudioPlaybackEvent]s. Called outside the lock; must not block.
 */
class AudioDecoderCore(
    private val driver: AudioCodecDriver,
    private val format: AudioStreamFormat,
    private val policy: AudioLatencyPolicy = AudioLatencyPolicy(),
    private val clock: MediaClock = MediaClock.SYSTEM,
    private val maxConsecutiveErrors: Int = DEFAULT_MAX_CONSECUTIVE_ERRORS,
    private val onEvent: (AudioPlaybackEvent) -> Unit = {},
) {

    private val lock = Any()

    private var phaseInternal: AudioDecoderPhase = AudioDecoderPhase.IDLE
    private var framesWritten = 0L
    private var lastUnderrunCount = 0
    private var consecutiveErrors = 0
    private var firstPacketPlayed = false
    private var trimming = false
    private var trimmedInBurst = 0L
    private var lastBacklogMs = 0
    private var lastPresentationTimeUs = 0L

    private var packetsDecoded = 0L
    private var packetsPlayed = 0L
    private var packetsDroppedForLatency = 0L
    private var packetsConcealed = 0L
    private var decodeErrors = 0L
    private var underruns = 0L
    private var underrunsThisInterval = 0L

    /** The current phase. */
    val phase: AudioDecoderPhase
        get() = synchronized(lock) { phaseInternal }

    /** True while the codec is running and packets are worth sending. */
    val isRunning: Boolean
        get() = synchronized(lock) { phaseInternal == AudioDecoderPhase.RUNNING }

    /**
     * Configures and starts the codec and output device.
     *
     * @return true when audio is running. A false has already emitted
     *   [AudioPlaybackEvent.Stopped]; the caller reports it and **continues the session**.
     */
    fun start(): Boolean {
        val events = mutableListOf<AudioPlaybackEvent>()
        var started = false
        synchronized(lock) {
            if (phaseInternal != AudioDecoderPhase.IDLE) {
                started = phaseInternal == AudioDecoderPhase.RUNNING
            } else {
                try {
                    driver.start()
                    phaseInternal = AudioDecoderPhase.RUNNING
                    lastUnderrunCount = driver.underrunCount().coerceAtLeast(0)
                    events += AudioPlaybackEvent.Started(driver.name, format)
                    started = true
                } catch (error: Throwable) {
                    phaseInternal = AudioDecoderPhase.FAILED
                    events += AudioPlaybackEvent.Stopped(
                        AudioCodecFailure(
                            message = "The Opus decoder ${driver.name} could not be started: " +
                                (error.message ?: error.javaClass.simpleName),
                        ),
                    )
                }
            }
        }
        dispatch(events)
        return started
    }

    /**
     * Offers one Opus packet, or one concealment fill.
     *
     * Never blocks for longer than one decode, never throws, and always makes a decision — there is
     * no "try again later" for audio, because later is exactly what we are trying to avoid.
     *
     * @param data the Opus packet. Ignored when [concealment] is true.
     * @param offset first byte of the packet.
     * @param length bytes of packet.
     * @param concealment true when nothing arrived for this slot and silence of exactly one packet
     *   duration should be produced instead (spec §8.5).
     * @return true when the packet reached the output device. A false means it was decoded and
     *   dropped to stop latency accumulating, or that the codec is not running — neither of which
     *   the caller can or should do anything about.
     */
    fun submit(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
        concealment: Boolean = false,
    ): Boolean {
        val events = mutableListOf<AudioPlaybackEvent>()
        val played = synchronized(lock) { submitLocked(data, offset, length, concealment, events) }
        dispatch(events)
        return played
    }

    private fun submitLocked(
        data: ByteArray,
        offset: Int,
        length: Int,
        concealment: Boolean,
        events: MutableList<AudioPlaybackEvent>,
    ): Boolean {
        if (phaseInternal != AudioDecoderPhase.RUNNING) return false

        val played = driver.playbackPositionFrames()
        val backlogMs = policy.backlogMs(framesWritten, played, format.sampleRateHz)
        lastBacklogMs = backlogMs
        val play = policy.shouldPlay(backlogMs)

        if (play && trimming) {
            // Caught up. Report the burst as one event rather than one per dropped packet.
            trimming = false
            events += AudioPlaybackEvent.BacklogTrimmed(backlogMs, trimmedInBurst)
            trimmedInBurst = 0L
        }

        val frames = try {
            driver.decode(
                packet = if (concealment) null else data,
                offset = offset,
                length = length,
                presentationTimeUs = nextPresentationTimeUs(),
                play = play,
            )
        } catch (error: Throwable) {
            // A driver is not supposed to throw, but a vendor codec in an unexpected state does.
            // Treating it as a decode error rather than letting it propagate is what keeps a codec
            // fault from reaching the session's failure path.
            recordErrorLocked(error.message ?: error.javaClass.simpleName, events)
            return false
        }

        packetsDecoded++
        if (concealment) packetsConcealed++

        if (frames < 0) {
            recordErrorLocked("the codec rejected a packet", events)
            return false
        }
        consecutiveErrors = 0

        if (!play) {
            packetsDroppedForLatency++
            trimmedInBurst++
            trimming = true
            return false
        }

        framesWritten += frames.toLong()
        packetsPlayed++
        if (!firstPacketPlayed && frames > 0) {
            firstPacketPlayed = true
            events += AudioPlaybackEvent.FirstPacketPlayed
        }
        pollUnderrunsLocked(events)
        return true
    }

    private fun recordErrorLocked(
        reason: String,
        events: MutableList<AudioPlaybackEvent>,
    ) {
        decodeErrors++
        consecutiveErrors++
        if (consecutiveErrors < maxConsecutiveErrors) return
        phaseInternal = AudioDecoderPhase.FAILED
        events += AudioPlaybackEvent.Stopped(
            AudioCodecFailure(
                message = "The Opus decoder failed $consecutiveErrors times in a row " +
                    "($reason); audio has stopped and the stream continues without it.",
            ),
        )
    }

    /**
     * Reads the output device's underrun counter and differences it.
     *
     * A lifetime counter is what the platform offers; a delta is what tells anyone anything. A
     * device that cannot report underruns returns `-1`, which is left as zero rather than treated
     * as a decreasing count.
     */
    private fun pollUnderrunsLocked(events: MutableList<AudioPlaybackEvent>) {
        val current = driver.underrunCount()
        if (current < 0) return
        val delta = current - lastUnderrunCount
        lastUnderrunCount = current
        if (delta <= 0) return
        underruns += delta.toLong()
        underrunsThisInterval += delta.toLong()
        events += AudioPlaybackEvent.Underrun(underruns)
    }

    /**
     * Presentation timestamps, strictly increasing.
     *
     * The output device's own clock paces playback; this value exists only for the codec's
     * bookkeeping, and strict monotonicity keeps it unambiguous when two packets are submitted
     * inside the same microsecond.
     */
    private fun nextPresentationTimeUs(): Long {
        val now = clock.nowMicros()
        val next = if (now > lastPresentationTimeUs) now else lastPresentationTimeUs + 1L
        lastPresentationTimeUs = next
        return next
    }

    /**
     * Drops everything in flight without treating it as an error.
     *
     * Resets the written-frame count with it: after a flush the output device's playback position
     * refers to audio that no longer exists, and a backlog computed across a flush would be
     * meaningless in whichever direction the two counters happened to disagree.
     */
    fun flush() {
        synchronized(lock) {
            if (phaseInternal != AudioDecoderPhase.RUNNING) return
            runCatching { driver.flush() }
            framesWritten = driver.playbackPositionFrames()
            trimming = false
            trimmedInBurst = 0L
        }
    }

    /** Current metrics, and the point at which the interval counters restart. */
    fun stats(): AudioPlaybackStats = synchronized(lock) {
        val snapshot = AudioPlaybackStats(
            packetsDecoded = packetsDecoded,
            packetsPlayed = packetsPlayed,
            packetsDroppedForLatency = packetsDroppedForLatency,
            packetsConcealed = packetsConcealed,
            decodeErrors = decodeErrors,
            underruns = underruns,
            underrunsThisInterval = underrunsThisInterval,
            backlogMs = lastBacklogMs,
            framesWritten = framesWritten,
        )
        underrunsThisInterval = 0L
        snapshot
    }

    /** Releases the codec and output device. Idempotent, and safe from any phase. */
    fun release() {
        synchronized(lock) {
            if (phaseInternal == AudioDecoderPhase.RELEASED) return
            phaseInternal = AudioDecoderPhase.RELEASED
            runCatching { driver.release() }
        }
    }

    private fun dispatch(events: List<AudioPlaybackEvent>) {
        for (event in events) {
            onEvent(event)
        }
    }

    companion object {
        /**
         * Consecutive decode failures tolerated before audio gives up.
         *
         * Higher than the video decoder's rebuild budget because the cost of being wrong is lower:
         * giving up on audio too early loses audio, while giving up on video too early loses the
         * session. Eight failures at two hundred packets a second is forty milliseconds of trying.
         */
        const val DEFAULT_MAX_CONSECUTIVE_ERRORS: Int = 8
    }
}
