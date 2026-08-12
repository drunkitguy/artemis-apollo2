package com.voidlink.android.protocol.rtp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * The seam between the blocking `video-rx` thread and the coroutine world (architecture §3).
 *
 * [onDatagram] is called from the socket-receive loop, parses and reassembles, and publishes whole
 * frames on [frames] and loss/status notices on [events]. **It never blocks and never suspends.**
 * That is not a nicety: the receive thread is the only thing draining the socket buffer, and a
 * moment spent waiting on a slow decoder is a moment the kernel spends discarding packets we will
 * then have to request an IDR for.
 *
 * So both queues are bounded and **drop the oldest** element when full:
 *
 * * [frames] holds [RtpVideoConstants.DECODE_QUEUE_CAPACITY] decode units — two, per architecture
 *   §3 rule 1. Dropping the oldest is right for video specifically: an old frame is worth less
 *   than a new one, and growing the queue "for smoothness" converts loss into the one thing this
 *   product cannot trade away, latency. Every eviction is counted in [framesDroppedByBackpressure]
 *   and reported on [events] as a [FrameDropReason.QUEUE_OVERFLOW] drop, which asks for an IDR.
 * * [events] is larger but bounded the same way, so a storm of loss notices cannot pin the
 *   receive thread against a control channel that is momentarily busy.
 *
 * The drop-oldest step is written as "try to send; if the buffer is full, take one out and try
 * again" rather than handed to `BufferOverflow.DROP_OLDEST` so that evictions can actually be
 * counted — a silently discarded frame is exactly the kind of loss this layer must not paper over.
 *
 * **Threading:** [onDatagram] is single-producer and must be called from one thread only.
 * [frames], [events] and the counters are safe to read from anywhere.
 */
class VideoFramePipeline(
    config: FrameAssemblerConfig = FrameAssemblerConfig(),
    frameCapacity: Int = RtpVideoConstants.DECODE_QUEUE_CAPACITY,
    eventCapacity: Int = RtpVideoConstants.EVENT_QUEUE_CAPACITY,
) {

    init {
        require(frameCapacity >= 1) { "frameCapacity must be at least 1, was $frameCapacity" }
        require(eventCapacity >= 1) { "eventCapacity must be at least 1, was $eventCapacity" }
    }

    private val assembler = FrameAssembler(config)
    private val frameChannel = Channel<VideoFrame>(frameCapacity)
    private val eventChannel = Channel<VideoStreamEvent>(eventCapacity)

    private val frameDrops = AtomicLong(0L)
    private val eventDrops = AtomicLong(0L)

    @Volatile
    private var latestStats: FrameAssemblerStats = assembler.stats()

    /** Complete, decodable frames, newest-biased under pressure. */
    val frames: ReceiveChannel<VideoFrame>
        get() = frameChannel

    /** Loss and status notices for the control channel and the session state machine. */
    val events: ReceiveChannel<VideoStreamEvent>
        get() = eventChannel

    /** Frames evicted because the decoder was not keeping up. */
    val framesDroppedByBackpressure: Long
        get() = frameDrops.get()

    /** Events evicted because nothing was consuming them. */
    val eventsDroppedByBackpressure: Long
        get() = eventDrops.get()

    /**
     * Handles one received datagram.
     *
     * @param datagram the receive buffer; not retained, so the caller may recycle it immediately.
     * @param length bytes actually received.
     * @return true when this datagram completed a frame. The receive loop uses it only for
     *   logging; nothing downstream depends on the return value.
     */
    fun onDatagram(datagram: ByteArray, length: Int = datagram.size): Boolean {
        val result = assembler.submit(datagram, length)
        for (event in result.events) {
            offerEvent(event)
        }
        val frame = result.frame
        if (frame != null) offerFrame(frame)
        latestStats = assembler.stats()
        return frame != null
    }

    /** The most recent counter snapshot, published after every datagram (spec §11.1, §11.2). */
    fun stats(): FrameAssemblerStats = latestStats

    /**
     * Closes both channels, ending any consumer's `for (frame in frames)` loop.
     *
     * @param cause optional failure to surface to consumers; `null` closes normally.
     */
    fun close(cause: Throwable? = null) {
        frameChannel.close(cause)
        eventChannel.close(cause)
    }

    private fun offerFrame(frame: VideoFrame) {
        var attempts = 0
        while (attempts < OFFER_ATTEMPT_LIMIT) {
            val sent = frameChannel.trySend(frame)
            if (sent.isSuccess) return
            if (sent.isClosed) return
            val evicted = frameChannel.tryReceive().getOrNull()
            if (evicted != null) {
                frameDrops.incrementAndGet()
                offerEvent(
                    VideoStreamEvent.FrameDropped(
                        evicted.frameIndex,
                        FrameDropReason.QUEUE_OVERFLOW,
                        0,
                    ),
                )
            }
            attempts++
        }
        // Unreachable in practice: one eviction frees one slot. Counted rather than looped forever,
        // because spinning here would stall the socket read.
        frameDrops.incrementAndGet()
    }

    private fun offerEvent(event: VideoStreamEvent) {
        var attempts = 0
        while (attempts < OFFER_ATTEMPT_LIMIT) {
            val sent = eventChannel.trySend(event)
            if (sent.isSuccess) return
            if (sent.isClosed) return
            if (eventChannel.tryReceive().getOrNull() != null) eventDrops.incrementAndGet()
            attempts++
        }
        eventDrops.incrementAndGet()
    }

    private companion object {
        /**
         * How many times an offer may evict-and-retry before giving up.
         *
         * One eviction frees one slot, so two attempts always suffice; the extra headroom covers a
         * consumer racing us, and the hard limit guarantees the receive thread cannot spin.
         */
        const val OFFER_ATTEMPT_LIMIT: Int = 4
    }
}
