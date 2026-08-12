package com.voidlink.android.media.audio

/**
 * A snapshot of audio playback, in the spirit of [com.voidlink.android.media.DecoderStats].
 *
 * Counters are lifetime totals for the session; [backlogMs] and [underrunsThisInterval] describe
 * right now, which is what makes them useful on an overlay rather than a post-mortem.
 *
 * @property packetsDecoded Opus packets handed to the codec, concealment included.
 * @property packetsPlayed packets whose PCM reached the output device.
 * @property packetsDroppedForLatency packets decoded but deliberately not played, because playing
 *   them would have added permanent delay. **Not an error**: this is the drift policy working.
 * @property packetsConcealed packets synthesised for slots nothing arrived for (spec §8.5).
 * @property decodeErrors decode calls that failed.
 * @property underruns times the output device ran dry, lifetime.
 * @property underrunsThisInterval underruns since the previous snapshot.
 * @property backlogMs how much audio is queued for playback but not yet played.
 * @property framesWritten PCM sample frames accepted by the output device.
 */
data class AudioPlaybackStats(
    val packetsDecoded: Long = 0L,
    val packetsPlayed: Long = 0L,
    val packetsDroppedForLatency: Long = 0L,
    val packetsConcealed: Long = 0L,
    val decodeErrors: Long = 0L,
    val underruns: Long = 0L,
    val underrunsThisInterval: Long = 0L,
    val backlogMs: Int = 0,
    val framesWritten: Long = 0L,
) {
    /** One line for the log and the stats overlay. */
    fun describe(): String =
        "decoded=$packetsDecoded played=$packetsPlayed dropped=$packetsDroppedForLatency " +
            "concealed=$packetsConcealed errors=$decodeErrors underruns=$underruns " +
            "backlog=${backlogMs}ms"

    companion object {
        /** The all-zero snapshot, used before anything has been decoded. */
        val EMPTY: AudioPlaybackStats = AudioPlaybackStats()
    }
}

/** Something the audio playback path wants the session and the log to know about. */
sealed interface AudioPlaybackEvent {

    /** The codec and output device configured and started. */
    data class Started(val decoderName: String, val format: AudioStreamFormat) : AudioPlaybackEvent

    /** The first packet reached the speaker. Ends the "negotiated but silent" ambiguity. */
    data object FirstPacketPlayed : AudioPlaybackEvent

    /**
     * Playback fell far enough behind that packets are being discarded to catch up.
     *
     * Emitted when the drop policy engages, not for every dropped packet — a burst of a hundred
     * drops over half a second is one event.
     */
    data class BacklogTrimmed(val backlogMs: Int, val dropped: Long) : AudioPlaybackEvent

    /** The output device ran dry. Streaming continues; this is a symptom, not a failure. */
    data class Underrun(val count: Long) : AudioPlaybackEvent

    /**
     * Audio has stopped and will not restart.
     *
     * **The session must not end because of this.** Spec-level rule for this layer: a stream with
     * no audio is far better than no stream. The caller logs it, records the reason, and carries
     * on.
     */
    data class Stopped(val failure: AudioCodecFailure) : AudioPlaybackEvent
}
