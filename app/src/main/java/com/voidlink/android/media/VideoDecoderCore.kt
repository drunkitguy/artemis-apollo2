package com.voidlink.android.media

/**
 * The decoder's brain: queueing, keyframe gating, drop accounting, timing and error recovery.
 *
 * Everything here is plain Kotlin over a [CodecDriver], with no Android types anywhere, because
 * `MediaCodec` cannot run in CI and this is the logic that most needs testing. The Android half
 * lives in [MediaCodecDriver]; [VideoDecoder] bolts the two together.
 *
 * ### What it guarantees
 *
 * * **The first frame submitted to the codec is always a keyframe** (spec §7.8). Frames arriving
 *   before the first IDR — and after any event that invalidates the codec's reference frames — are
 *   counted as dropped and discarded, not fed to the codec. Feeding a decoder a P-frame with no
 *   reference is how you get a session that runs, reports no errors, and shows green mush.
 * * **The queue is bounded at [queueCapacity] and drops the oldest** (architecture §3 rule 1).
 *   Since dropping any frame invalidates everything queued behind it, an overflow clears the whole
 *   queue and re-arms the keyframe gate rather than leaving a hole in the stream.
 * * **Every frame is released exactly once**, whether it was decoded or dropped, so a pooled
 *   receive buffer is never leaked and never recycled twice.
 * * **A recoverable codec error rebuilds the codec instead of ending the session**, up to
 *   [maxRecoveryAttempts] times without an intervening successfully rendered frame.
 *
 * ### Threading
 *
 * [submit] is called from the frame producer; every [CodecEventSink] method is called from the
 * codec callback thread. All mutable state is guarded by one lock, and events are dispatched
 * *after* the lock is released so a listener can call back in without deadlocking.
 *
 * @param driver the codec to drive.
 * @param format the format the driver was configured with; carried so [DecoderEvent.Started] can
 *   report it.
 * @param clock source of timestamps, injectable for tests.
 * @param queueCapacity how many complete frames may wait for an input buffer. Two, per
 *   architecture §3 — this queue exists to absorb scheduling jitter, not to add smoothing, and
 *   growing it trades latency for a property nobody asked for.
 * @param maxRecoveryAttempts consecutive codec rebuilds allowed before giving up.
 * @param onEvent listener for [DecoderEvent]s. Called outside the lock; must not block.
 */
class VideoDecoderCore(
    private val driver: CodecDriver,
    private val format: VideoStreamFormat,
    private val clock: MediaClock = MediaClock.SYSTEM,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val maxRecoveryAttempts: Int = DEFAULT_MAX_RECOVERY_ATTEMPTS,
    private val onEvent: (DecoderEvent) -> Unit = {},
) : CodecEventSink, VideoFrameSink {

    private val lock = Any()

    private var phaseInternal: DecoderPhase = DecoderPhase.IDLE
    private val freeInputBuffers = ArrayDeque<Int>()
    private val pending = ArrayDeque<VideoFrame>()
    private val submitTimesMicros = HashMap<Long, Long>()
    private val statsAccumulator = DecoderStatsAccumulator(clock.nowMicros() / 1000L)
    private var awaitingKeyFrame = true
    private var firstFrameRendered = false
    private var lastPresentationTimeUs = 0L
    private var recoveryAttempts = 0

    /** The current phase. */
    val phase: DecoderPhase
        get() = synchronized(lock) { phaseInternal }

    /** True while the codec is running and frames are worth sending. */
    val isRunning: Boolean
        get() = synchronized(lock) {
            phaseInternal == DecoderPhase.RUNNING || phaseInternal == DecoderPhase.RECOVERING
        }

    /**
     * Configures and starts the codec.
     *
     * @return true when the codec is running. A false has already emitted
     *   [DecoderEvent.FatalError]; the caller's job is to tear the session down and show it.
     */
    fun start(): Boolean {
        val events = mutableListOf<DecoderEvent>()
        var started = false
        synchronized(lock) {
            if (phaseInternal != DecoderPhase.IDLE) {
                started = phaseInternal == DecoderPhase.RUNNING
            } else {
                try {
                    driver.start(this)
                    phaseInternal = DecoderPhase.RUNNING
                    statsAccumulator.reset(nowMillis())
                    events += DecoderEvent.Started(driver.name, format)
                    started = true
                } catch (error: Throwable) {
                    phaseInternal = DecoderPhase.FAILED
                    events += DecoderEvent.FatalError(
                        message = "The video decoder ${driver.name} could not be started: " +
                            (error.message ?: error.javaClass.simpleName),
                        cause = error,
                    )
                }
            }
        }
        dispatch(events)
        return started
    }

    /**
     * Offers one complete frame.
     *
     * See [VideoFrameSink.submit] for the ownership contract. In short: this never blocks, always
     * takes ownership of [frame], and a `false` means "capacity was exceeded, ask the host for an
     * IDR" rather than "try again".
     */
    override fun submit(frame: VideoFrame): Boolean {
        val events = mutableListOf<DecoderEvent>()
        val accepted = synchronized(lock) { submitLocked(frame, events) }
        dispatch(events)
        return accepted
    }

    private fun submitLocked(frame: VideoFrame, events: MutableList<DecoderEvent>): Boolean {
        if (phaseInternal != DecoderPhase.RUNNING && phaseInternal != DecoderPhase.RECOVERING) {
            statsAccumulator.onFramesDropped(nowMillis())
            frame.release()
            return false
        }

        var overflowed = false
        if (pending.size >= queueCapacity) {
            discardPendingLocked("decode queue overflow", events)
            overflowed = true
        }

        if (awaitingKeyFrame && !frame.keyFrame) {
            statsAccumulator.onFramesDropped(nowMillis())
            frame.release()
            return !overflowed
        }
        awaitingKeyFrame = false

        pending.addLast(frame)
        pumpLocked(events)
        return !overflowed
    }

    /** Moves as many queued frames into free input buffers as both sides allow. */
    private fun pumpLocked(events: MutableList<DecoderEvent>) {
        while (freeInputBuffers.isNotEmpty() && pending.isNotEmpty()) {
            val bufferIndex = freeInputBuffers.removeFirst()
            val frame = pending.removeFirst()
            val presentationTimeUs = nextPresentationTimeUs()

            var queued = false
            try {
                queued = driver.submit(bufferIndex, frame, presentationTimeUs)
            } catch (error: Throwable) {
                events += DecoderEvent.TransientError(
                    "Submitting a frame failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }

            val now = nowMillis()
            if (queued) {
                statsAccumulator.onFrameSubmitted(now, frame.length)
                if (submitTimesMicros.size > MAX_TRACKED_SUBMISSIONS) submitTimesMicros.clear()
                submitTimesMicros[presentationTimeUs] = clock.nowMicros()
            } else {
                statsAccumulator.onFramesDropped(now)
                awaitingKeyFrame = true
                events += DecoderEvent.KeyFrameRequested("the codec rejected an input buffer")
            }
            frame.release()
        }
    }

    /**
     * Throws away everything queued and re-arms the keyframe gate.
     *
     * Used on overflow and on every error path: once one frame is missing, the frames behind it
     * reference a picture the decoder does not have, so decoding them produces corruption rather
     * than a partial picture.
     */
    private fun discardPendingLocked(reason: String, events: MutableList<DecoderEvent>) {
        val dropped = pending.size
        while (pending.isNotEmpty()) {
            pending.removeFirst().release()
        }
        if (dropped > 0) statsAccumulator.onFramesDropped(nowMillis(), dropped)
        awaitingKeyFrame = true
        events += DecoderEvent.KeyFrameRequested(reason)
    }

    /**
     * Presentation timestamps, strictly increasing.
     *
     * Spec §7.8: we render immediately and never schedule on the timestamp, so its only job is to
     * correlate an input buffer with its output callback. Strict monotonicity is what keeps that
     * correlation unambiguous when two frames are submitted inside the same microsecond.
     */
    private fun nextPresentationTimeUs(): Long {
        val now = clock.nowMicros()
        val next = if (now > lastPresentationTimeUs) now else lastPresentationTimeUs + 1L
        lastPresentationTimeUs = next
        return next
    }

    override fun onInputBufferAvailable(index: Int) {
        val events = mutableListOf<DecoderEvent>()
        synchronized(lock) {
            if (phaseInternal != DecoderPhase.RELEASED && phaseInternal != DecoderPhase.FAILED) {
                freeInputBuffers.addLast(index)
                pumpLocked(events)
            }
        }
        dispatch(events)
    }

    override fun onOutputBufferAvailable(
        index: Int,
        presentationTimeUs: Long,
        endOfStream: Boolean,
    ) {
        val events = mutableListOf<DecoderEvent>()
        synchronized(lock) {
            if (phaseInternal == DecoderPhase.RELEASED || phaseInternal == DecoderPhase.FAILED) {
                runCatching { driver.discard(index) }
            } else {
                driver.render(index)

                val submittedAt = submitTimesMicros.remove(presentationTimeUs)
                val decodeMicros = if (submittedAt == null) 0L else clock.nowMicros() - submittedAt
                statsAccumulator.onFrameDecoded(nowMillis(), decodeMicros)

                // A frame reaching the surface proves the codec is healthy, so the rebuild budget
                // is restored; otherwise three glitches spread over an hour would end a session.
                recoveryAttempts = 0

                if (!firstFrameRendered) {
                    firstFrameRendered = true
                    events += DecoderEvent.FirstFrameRendered
                }
            }
        }
        dispatch(events)
    }

    override fun onOutputFormatChanged(width: Int, height: Int, description: String) {
        dispatch(listOf(DecoderEvent.FormatChanged(width, height, description)))
    }

    override fun onCodecFailure(failure: CodecFailure) {
        val events = mutableListOf<DecoderEvent>()
        synchronized(lock) {
            if (phaseInternal != DecoderPhase.RELEASED && phaseInternal != DecoderPhase.FAILED) {
                handleFailureLocked(failure, events)
            }
        }
        dispatch(events)
    }

    private fun handleFailureLocked(failure: CodecFailure, events: MutableList<DecoderEvent>) {
        if (failure.transient) {
            resetStreamStateLocked("a transient decoder error", events)
            runCatching { driver.flush() }
            events += DecoderEvent.TransientError(failure.message)
            return
        }

        if (failure.recoverable && recoveryAttempts < maxRecoveryAttempts) {
            recoveryAttempts++
            phaseInternal = DecoderPhase.RECOVERING
            resetStreamStateLocked("a recoverable decoder error", events)
            try {
                driver.restart()
                phaseInternal = DecoderPhase.RUNNING
                events += DecoderEvent.Recovered(
                    "Recovered from a decoder error (${failure.message}); " +
                        "attempt $recoveryAttempts of $maxRecoveryAttempts.",
                )
            } catch (error: Throwable) {
                phaseInternal = DecoderPhase.FAILED
                events += DecoderEvent.FatalError(
                    message = "The video decoder could not be restarted after an error: " +
                        (error.message ?: error.javaClass.simpleName),
                    cause = error,
                )
            }
            return
        }

        phaseInternal = DecoderPhase.FAILED
        events += DecoderEvent.FatalError(failure.describeForUser())
    }

    /**
     * Drops everything in flight after an error.
     *
     * The free-buffer list is cleared as well as the queue: after a flush or a rebuild the indices
     * we were holding belong to a codec generation that no longer exists, and handing one of them
     * back is an `IllegalStateException` at best.
     */
    private fun resetStreamStateLocked(reason: String, events: MutableList<DecoderEvent>) {
        discardPendingLocked(reason, events)
        freeInputBuffers.clear()
        submitTimesMicros.clear()
    }

    /**
     * Drops everything in flight and asks for a keyframe, without treating it as an error.
     *
     * The session layer calls this after a reassembly gap, where the codec is fine but the stream
     * is not.
     */
    fun flush() {
        val events = mutableListOf<DecoderEvent>()
        synchronized(lock) {
            if (phaseInternal == DecoderPhase.RUNNING) {
                resetStreamStateLocked("an explicit flush", events)
                runCatching { driver.flush() }
            }
        }
        dispatch(events)
    }

    /**
     * Releases the codec. Idempotent, and safe from any phase.
     *
     * The surface must outlive this call: [VideoDecoder] guarantees that by calling it
     * synchronously from `surfaceDestroyed`.
     */
    fun release() {
        val events = mutableListOf<DecoderEvent>()
        synchronized(lock) {
            if (phaseInternal != DecoderPhase.RELEASED) {
                phaseInternal = DecoderPhase.RELEASED
                while (pending.isNotEmpty()) {
                    pending.removeFirst().release()
                }
                freeInputBuffers.clear()
                submitTimesMicros.clear()
                runCatching { driver.release() }
                events += DecoderEvent.Released
            }
        }
        dispatch(events)
    }

    /** Current metrics, advancing the rate window. See [DecoderStatsAccumulator.snapshot]. */
    fun stats(): DecoderStats = synchronized(lock) { statsAccumulator.snapshot(nowMillis()) }

    private fun nowMillis(): Long = clock.nowMicros() / 1000L

    private fun dispatch(events: List<DecoderEvent>) {
        for (event in events) {
            onEvent(event)
        }
    }

    companion object {
        /** Decode units allowed to wait for an input buffer (architecture §3, rule 1). */
        const val DEFAULT_QUEUE_CAPACITY: Int = 2

        /** Codec rebuilds allowed between successfully rendered frames. */
        const val DEFAULT_MAX_RECOVERY_ATTEMPTS: Int = 3

        /**
         * Cap on outstanding submit timestamps.
         *
         * Only ever reached if the codec stops producing output while still consuming input, in
         * which case the map is cleared and decode times are briefly unavailable — a much better
         * failure than an unbounded map in the hot path.
         */
        private const val MAX_TRACKED_SUBMISSIONS: Int = 64
    }
}

/**
 * The sentence shown to the user for a fatal codec failure.
 *
 * The vendor diagnostic is appended in parentheses when present: it is meaningless to a user but
 * it is the only thing that makes a bug report from an unfamiliar device actionable.
 */
internal fun CodecFailure.describeForUser(): String {
    val diagnostic = diagnosticInfo
    return if (diagnostic.isNullOrBlank()) {
        "The video decoder failed: $message"
    } else {
        "The video decoder failed: $message ($diagnostic)"
    }
}
