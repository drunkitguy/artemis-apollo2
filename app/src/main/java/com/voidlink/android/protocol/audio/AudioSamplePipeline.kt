package com.voidlink.android.protocol.audio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * The seam between the blocking `audio-rx` thread and the coroutine world (architecture §3).
 *
 * The audio counterpart of [com.voidlink.android.protocol.rtp.VideoFramePipeline], and the same
 * contract: [onDatagram] is called from the socket-receive loop, depacketizes, and publishes Opus
 * packets on [samples] and notices on [events]. **It never blocks and never suspends** — the
 * receive thread is the only thing draining the socket buffer.
 *
 * Both queues are bounded and **drop the oldest** element when full, which for audio is not merely
 * a capacity policy but the whole latency policy. An audio packet that could not be handed over in
 * time is worth nothing: playing it later does not restore it, it only pushes every subsequent
 * packet later too, and audio that is late by a fixed amount stays late for the rest of the session
 * because nothing ever speeds it up again. So the oldest goes, the newest stays, and the eviction
 * is counted in [samplesDroppedByBackpressure] rather than hidden.
 *
 * The drop-oldest step is written as "try to send; if full, take one out and try again" rather than
 * handed to `BufferOverflow.DROP_OLDEST` for exactly the reason the video pipeline gives: a silently
 * discarded packet is the kind of loss this layer must not paper over.
 *
 * **Threading:** [onDatagram] and [onIdle] are single-producer and must be called from one thread
 * only. [samples], [events] and the counters are safe to read from anywhere.
 */
class AudioSamplePipeline(
    config: AudioDepacketizerConfig = AudioDepacketizerConfig(),
    sampleCapacity: Int = RtpAudioConstants.SAMPLE_QUEUE_CAPACITY,
    eventCapacity: Int = RtpAudioConstants.EVENT_QUEUE_CAPACITY,
    clock: () -> Long = { System.nanoTime() },
) {

    init {
        require(sampleCapacity >= 1) { "sampleCapacity must be at least 1, was $sampleCapacity" }
        require(eventCapacity >= 1) { "eventCapacity must be at least 1, was $eventCapacity" }
    }

    private val depacketizer = AudioDepacketizer(config, clock)
    private val sampleChannel = Channel<OpusSample>(sampleCapacity)
    private val eventChannel = Channel<AudioStreamEvent>(eventCapacity)

    private val sampleDrops = AtomicLong(0L)
    private val eventDrops = AtomicLong(0L)

    @Volatile
    private var latestStats: AudioStreamStats = AudioStreamStats.EMPTY

    /** Opus packets in stream order, newest-biased under pressure. */
    val samples: ReceiveChannel<OpusSample>
        get() = sampleChannel

    /** Loss and status notices. */
    val events: ReceiveChannel<AudioStreamEvent>
        get() = eventChannel

    /** Samples evicted because the decoder was not keeping up. */
    val samplesDroppedByBackpressure: Long
        get() = sampleDrops.get()

    /** Events evicted because nothing was consuming them. */
    val eventsDroppedByBackpressure: Long
        get() = eventDrops.get()

    /**
     * Handles one received datagram.
     *
     * @param datagram the receive buffer; not retained, so the caller may recycle it immediately.
     * @param length bytes actually received.
     * @return how many samples this datagram released. The receive loop uses it only for logging.
     */
    fun onDatagram(datagram: ByteArray, length: Int = datagram.size): Int {
        val result = depacketizer.submit(datagram, length)
        return publish(result)
    }

    /**
     * Called when the socket has been quiet. See [AudioDepacketizer.onIdle].
     *
     * @return how many samples the idle tick released.
     */
    fun onIdle(): Int = publish(depacketizer.onIdle())

    /** The most recent counter snapshot, published after every datagram. */
    fun stats(): AudioStreamStats = latestStats

    /**
     * Closes both channels, ending any consumer's `for (sample in samples)` loop.
     *
     * @param cause optional failure to surface to consumers; `null` closes normally.
     */
    fun close(cause: Throwable? = null) {
        sampleChannel.close(cause)
        eventChannel.close(cause)
    }

    private fun publish(result: AudioSubmitResult): Int {
        for (event in result.events) {
            offerEvent(event)
        }
        for (sample in result.samples) {
            offerSample(sample)
        }
        latestStats = depacketizer.stats()
        return result.samples.size
    }

    private fun offerSample(sample: OpusSample) {
        var attempts = 0
        while (attempts < OFFER_ATTEMPT_LIMIT) {
            val sent = sampleChannel.trySend(sample)
            if (sent.isSuccess) return
            if (sent.isClosed) return
            if (sampleChannel.tryReceive().getOrNull() != null) sampleDrops.incrementAndGet()
            attempts++
        }
        sampleDrops.incrementAndGet()
    }

    private fun offerEvent(event: AudioStreamEvent) {
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
