package com.voidlink.android.media

/**
 * A [MediaClock] the test moves by hand.
 *
 * @property micros the current time, freely assignable.
 */
class FakeClock(var micros: Long = 0L) : MediaClock {
    override fun nowMicros(): Long = micros

    /** Advances the clock by [delta] microseconds. */
    fun advance(delta: Long) {
        micros += delta
    }
}

/**
 * A [CodecDriver] that records what it was asked to do and lets the test play the codec's side.
 *
 * This is the boundary that makes [VideoDecoderCore] testable at all: `MediaCodec` cannot run in
 * CI, so the fake stands in for it and the test drives input-buffer availability, output
 * completion and codec errors directly.
 */
class FakeCodecDriver(override val name: String = "fake.hw.decoder") : CodecDriver {

    /** One recorded call to [submit]. */
    data class Submission(
        val bufferIndex: Int,
        val frameNumber: Int,
        val keyFrame: Boolean,
        val presentationTimeUs: Long,
        val length: Int,
    )

    private var sink: CodecEventSink? = null

    /** Every frame handed to the codec, in order. */
    val submissions: MutableList<Submission> = mutableListOf()

    /** Output buffer indices released to the surface. */
    val rendered: MutableList<Int> = mutableListOf()

    /** Output buffer indices released without rendering. */
    val discarded: MutableList<Int> = mutableListOf()

    var startCount: Int = 0
        private set
    var flushCount: Int = 0
        private set
    var restartCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    /** When true, [start] throws — the "no configuration was accepted" path. */
    var failOnStart: Boolean = false

    /** When true, [restart] throws — recovery itself failing. */
    var failOnRestart: Boolean = false

    /** When true, [submit] returns false — the "frame larger than the input buffer" path. */
    var rejectSubmissions: Boolean = false

    /** When true, [submit] throws instead of returning. */
    var throwOnSubmit: Boolean = false

    override fun start(sink: CodecEventSink) {
        if (failOnStart) throw IllegalStateException("no configuration was accepted")
        this.sink = sink
        startCount++
    }

    override fun submit(bufferIndex: Int, frame: VideoFrame, presentationTimeUs: Long): Boolean {
        if (throwOnSubmit) throw IllegalStateException("codec is not running")
        if (rejectSubmissions) return false
        submissions += Submission(
            bufferIndex = bufferIndex,
            frameNumber = frame.frameNumber,
            keyFrame = frame.keyFrame,
            presentationTimeUs = presentationTimeUs,
            length = frame.length,
        )
        return true
    }

    override fun render(bufferIndex: Int) {
        rendered += bufferIndex
    }

    override fun discard(bufferIndex: Int) {
        discarded += bufferIndex
    }

    override fun flush() {
        flushCount++
    }

    override fun restart() {
        if (failOnRestart) throw IllegalStateException("codec could not be rebuilt")
        restartCount++
    }

    override fun release() {
        releaseCount++
    }

    // ---- the codec's side, driven by the test ------------------------------------------------

    /** Pretends the codec freed input buffer [index]. */
    fun offerInputBuffer(index: Int) {
        sink?.onInputBufferAvailable(index)
    }

    /** Pretends the codec produced the frame tagged [presentationTimeUs]. */
    fun completeOutput(presentationTimeUs: Long, index: Int = 0) {
        sink?.onOutputBufferAvailable(index, presentationTimeUs, false)
    }

    /** Pretends the codec reported [failure]. */
    fun reportFailure(failure: CodecFailure) {
        sink?.onCodecFailure(failure)
    }

    /** Pretends the codec settled on a new output format. */
    fun reportFormat(width: Int, height: Int) {
        sink?.onOutputFormatChanged(width, height, "fake format")
    }
}
