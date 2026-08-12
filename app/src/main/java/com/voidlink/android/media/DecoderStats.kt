package com.voidlink.android.media

/**
 * A snapshot of decode performance, as the stats overlay shows it.
 *
 * Counters are lifetime totals for the session; rates and averages cover the interval since the
 * previous snapshot, which is what makes "59.8 fps" mean "right now" rather than "on average since
 * you connected".
 *
 * @property framesSubmitted frames handed to the codec.
 * @property framesDecoded frames the codec produced and we rendered.
 * @property framesDropped frames discarded before reaching the codec — queue overflow, a rejected
 *   input buffer, or bytes arriving before the first keyframe.
 * @property averageDecodeTimeMs mean codec latency over the interval, from `queueInputBuffer` to
 *   the matching output callback.
 * @property peakDecodeTimeMs worst codec latency over the interval.
 * @property renderedFps frames rendered per second over the interval.
 * @property submittedFps frames submitted per second over the interval.
 * @property bitrateMbps decoded bitrate over the interval, in megabits per second.
 */
data class DecoderStats(
    val framesSubmitted: Long = 0L,
    val framesDecoded: Long = 0L,
    val framesDropped: Long = 0L,
    val averageDecodeTimeMs: Float = 0f,
    val peakDecodeTimeMs: Float = 0f,
    val renderedFps: Float = 0f,
    val submittedFps: Float = 0f,
    val bitrateMbps: Float = 0f,
) {
    companion object {
        /** The all-zero snapshot, used before anything has been decoded. */
        val EMPTY: DecoderStats = DecoderStats()
    }
}

/**
 * Accumulates decode events into a [DecoderStats] snapshot.
 *
 * Rates are computed **between snapshots** rather than over a sliding window of samples: the
 * overlay polls at 2 Hz (UI spec §5.2), so the interval between polls is already the window, and
 * this way there is no buffer of timestamps to grow, no allocation in the decode path, and no
 * ambiguity in a test about which samples fell inside the window.
 *
 * Not thread-safe by itself. [VideoDecoderCore] owns the only instance and mutates it under its
 * own lock; nothing else should touch one concurrently.
 *
 * @param startMillis the time the session started, in the same base as the `nowMillis` arguments.
 */
class DecoderStatsAccumulator(startMillis: Long = 0L) {

    private var totalSubmitted = 0L
    private var totalDecoded = 0L
    private var totalDropped = 0L

    private var intervalStartMillis = startMillis
    private var intervalSubmitted = 0L
    private var intervalDecoded = 0L
    private var intervalBytes = 0L
    private var intervalDecodeMicrosSum = 0L
    private var intervalDecodeSamples = 0L
    private var intervalPeakMicros = 0L

    /**
     * Records that a frame of [byteCount] bytes went into a codec input buffer.
     *
     * @param nowMillis current time; only used to keep the interval clock honest if no snapshot is
     *   ever taken.
     */
    fun onFrameSubmitted(nowMillis: Long, byteCount: Int) {
        totalSubmitted++
        intervalSubmitted++
        if (byteCount > 0) intervalBytes += byteCount.toLong()
        if (intervalStartMillis > nowMillis) intervalStartMillis = nowMillis
    }

    /**
     * Records that the codec produced a frame.
     *
     * @param decodeTimeMicros time from submission to output for that frame, or a non-positive
     *   value when it could not be matched to a submission (which happens for the first frames
     *   after a flush, and must not be allowed to skew the average).
     */
    fun onFrameDecoded(nowMillis: Long, decodeTimeMicros: Long) {
        totalDecoded++
        intervalDecoded++
        if (decodeTimeMicros > 0L) {
            intervalDecodeMicrosSum += decodeTimeMicros
            intervalDecodeSamples++
            if (decodeTimeMicros > intervalPeakMicros) intervalPeakMicros = decodeTimeMicros
        }
        if (intervalStartMillis > nowMillis) intervalStartMillis = nowMillis
    }

    /** Records [count] frames dropped before they reached the codec. */
    fun onFramesDropped(nowMillis: Long, count: Int = 1) {
        if (count <= 0) return
        totalDropped += count.toLong()
        if (intervalStartMillis > nowMillis) intervalStartMillis = nowMillis
    }

    /**
     * Returns the current snapshot and starts a new interval at [nowMillis].
     *
     * Calling this is what advances the rate window, so it must be called on a regular cadence —
     * the stream screen does it every 500 ms.
     */
    fun snapshot(nowMillis: Long): DecoderStats {
        val elapsedMillis = (nowMillis - intervalStartMillis).coerceAtLeast(0L)
        val perSecond: (Long) -> Float = { count ->
            if (elapsedMillis <= 0L) 0f else count.toFloat() * 1000f / elapsedMillis.toFloat()
        }

        val stats = DecoderStats(
            framesSubmitted = totalSubmitted,
            framesDecoded = totalDecoded,
            framesDropped = totalDropped,
            averageDecodeTimeMs = if (intervalDecodeSamples <= 0L) {
                0f
            } else {
                intervalDecodeMicrosSum.toFloat() / intervalDecodeSamples.toFloat() / 1000f
            },
            peakDecodeTimeMs = intervalPeakMicros.toFloat() / 1000f,
            renderedFps = perSecond(intervalDecoded),
            submittedFps = perSecond(intervalSubmitted),
            bitrateMbps = if (elapsedMillis <= 0L) {
                0f
            } else {
                intervalBytes.toFloat() * 8f / 1000f / elapsedMillis.toFloat()
            },
        )

        intervalStartMillis = nowMillis
        intervalSubmitted = 0L
        intervalDecoded = 0L
        intervalBytes = 0L
        intervalDecodeMicrosSum = 0L
        intervalDecodeSamples = 0L
        intervalPeakMicros = 0L

        return stats
    }

    /** Clears every counter, lifetime totals included, and restarts the interval at [nowMillis]. */
    fun reset(nowMillis: Long = 0L) {
        totalSubmitted = 0L
        totalDecoded = 0L
        totalDropped = 0L
        intervalStartMillis = nowMillis
        intervalSubmitted = 0L
        intervalDecoded = 0L
        intervalBytes = 0L
        intervalDecodeMicrosSum = 0L
        intervalDecodeSamples = 0L
        intervalPeakMicros = 0L
    }
}
