package com.voidlink.android.media

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One complete decode unit: an Annex-B elementary-stream fragment ready to hand to a decoder.
 *
 * **This is the seam between the RTP receive path and the decoder.** The reassembly layer produces
 * these; [VideoDecoder] consumes them. Everything the decoder needs is here and nothing else is —
 * no RTP sequence numbers, no FEC bookkeeping, no sockets.
 *
 * Contract the producer must honour:
 *
 * * [data] holds `length` bytes starting at [offset], already concatenated in ascending shard
 *   order per spec §7.8. Start codes are included; do **not** strip or re-frame NAL units.
 * * [keyFrame] is true when the fragment begins an IRAP/IDR. The decoder discards everything
 *   until the first frame with this flag set, so an incorrect `false` on the first IDR means a
 *   permanently blank picture.
 * * [onReleased] is invoked exactly once, on whichever thread finished with the frame, after the
 *   bytes have been copied into a codec input buffer *or* after the frame was dropped. It exists
 *   so the receive path can recycle a pooled buffer (architecture §3 rule 2) without the decoder
 *   needing to know the pool. The decoder never touches [data] after calling it.
 *
 * The class is deliberately not a `data class`: [data] is a `ByteArray`, whose `equals` is
 * identity, so a generated `equals`/`hashCode` would be actively misleading.
 *
 * @property data buffer holding the frame; may be larger than the frame itself.
 * @property offset index of the first frame byte in [data].
 * @property length number of frame bytes.
 * @property frameNumber the host's monotonically increasing `frameIndex` (spec §7.4). Used for
 *   logging and gap detection only; the decoder does not order by it.
 * @property keyFrame whether this fragment starts a keyframe.
 * @property receivedAtMicros when reassembly completed, in the same base as
 *   [MediaClock.nowMicros]. Zero when the producer does not track it.
 * @property onReleased optional recycle hook, called at most once.
 */
class VideoFrame(
    val data: ByteArray,
    val offset: Int = 0,
    val length: Int = data.size - offset,
    val frameNumber: Int = 0,
    val keyFrame: Boolean = false,
    val receivedAtMicros: Long = 0L,
    private val onReleased: (() -> Unit)? = null,
) {
    private val released = AtomicBoolean(false)

    /**
     * Hands the backing buffer back to the producer.
     *
     * Idempotent: the second and later calls do nothing, so a frame that is both dropped and
     * released by a teardown path cannot double-recycle a pooled buffer.
     */
    fun release() {
        if (released.compareAndSet(false, true)) {
            onReleased?.invoke()
        }
    }

    /** True once [release] has run. Exposed for tests and assertions, not for control flow. */
    val isReleased: Boolean get() = released.get()

    override fun toString(): String =
        "VideoFrame(frame=$frameNumber, bytes=$length, key=$keyFrame)"
}

/**
 * Where complete frames go.
 *
 * [VideoDecoder] implements this, so the RTP layer can hold a `VideoFrameSink` and never see the
 * decoder itself. The alternative wiring — handing the decoder a
 * `kotlinx.coroutines.channels.ReceiveChannel<VideoFrame>` — is supported by
 * [VideoDecoder.consume]; use whichever fits the producer better.
 */
interface VideoFrameSink {
    /**
     * Offers one complete frame.
     *
     * Never blocks. The frame is either queued, or dropped internally with the drop counted and a
     * keyframe requested; either way ownership of [frame] passes to the sink, which calls
     * [VideoFrame.release] when done.
     *
     * @return `false` when the frame could not be queued because the decoder had no capacity or
     *   was not running. A `false` is the caller's cue to send an IDR request upstream (spec
     *   §9.5); it is *not* a signal to retry, and the frame must not be submitted again.
     */
    fun submit(frame: VideoFrame): Boolean
}

/**
 * Monotonic microsecond clock.
 *
 * Injected everywhere a timestamp is taken so the decoder's timing logic can be driven
 * deterministically from a unit test.
 */
fun interface MediaClock {
    /** Microseconds since an arbitrary fixed origin; monotonic, never wall-clock. */
    fun nowMicros(): Long

    companion object {
        /** The production clock: `System.nanoTime()` in microseconds, as spec §7.8 prescribes. */
        val SYSTEM: MediaClock = MediaClock { System.nanoTime() / 1_000L }
    }
}
