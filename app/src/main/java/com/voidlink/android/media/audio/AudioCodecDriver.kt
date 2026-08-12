package com.voidlink.android.media.audio

/**
 * An audio codec or output failure, classified the way the caller has to act on it.
 *
 * @property message a short description for logs and the unavailability sentence.
 * @property recoverable the codec can be used again after being flushed or rebuilt.
 * @property diagnosticInfo the vendor's diagnostic string, when there is one.
 */
data class AudioCodecFailure(
    val message: String,
    val recoverable: Boolean = false,
    val diagnosticInfo: String? = null,
) {
    /** The sentence a user sees, with the vendor code appended when there is one. */
    fun describeForUser(): String =
        if (diagnosticInfo.isNullOrBlank()) message else "$message ($diagnosticInfo)"
}

/**
 * Everything [AudioDecoderCore] needs from the platform, and nothing more.
 *
 * This interface is the boundary that makes the audio path testable, and it is drawn in the same
 * place and for the same reason as [com.voidlink.android.media.CodecDriver]: `MediaCodec` and
 * `AudioTrack` cannot run in CI — there is no emulator here — so the queueing, the concealment, the
 * drift policy and the drop accounting all live in [AudioDecoderCore] against this interface, and
 * the parts that genuinely need Android live in [MediaCodecAudioDriver] behind it.
 *
 * The one design decision worth spelling out is the `play` parameter of [decode]. The obvious
 * interface would be "decode, and let the caller decide what to do with the PCM", but that would
 * mean copying every decoded buffer out of the codec so the caller could look at it. The obvious
 * alternative would be "the driver decides whether to play", which would put the drift policy on
 * the untestable side of the boundary. So the *decision* is the caller's and the *bytes* never
 * leave the driver: the core computes `play` from the backlog, and the driver either writes the
 * decoded PCM or releases it.
 *
 * A packet must still be **decoded** when it is not played. Opus is a predictive codec: skipping a
 * packet entirely leaves the decoder's internal state referring to audio it never saw, and the next
 * packet that *is* played arrives with an audible artefact. Dropping after decode costs a few
 * hundred microseconds of CPU and sounds like nothing at all.
 *
 * Threading: every method is called from one thread — the audio decode pump — except [release],
 * which may be called from another while a decode is in flight, and which must therefore leave the
 * in-flight call returning quietly rather than throwing.
 */
interface AudioCodecDriver {

    /** The platform name of the underlying codec, for logs and the stats overlay. */
    val name: String

    /**
     * Creates, configures and starts the codec and the output device.
     *
     * @throws Exception when either cannot be created or configured. The caller turns that into an
     *   explained unavailability and **continues the session without audio** — a stream with no
     *   audio is far better than no stream.
     */
    fun start()

    /**
     * Decodes one Opus packet and, if [play], writes the resulting PCM to the output device.
     *
     * @param packet the Opus packet, or `null` for packet-loss concealment. Spec §8.5: `MediaCodec`
     *   has no explicit PLC API, so the concealment substitute is
     *   [AudioStreamFormat.packetDurationMs] of silence — exactly one packet's worth, so the
     *   timeline stays aligned.
     * @param offset first byte of the packet.
     * @param length bytes of packet.
     * @param presentationTimeUs a monotonic microsecond value for the codec's own bookkeeping.
     *   Nothing schedules on it; the output device's clock is what paces playback.
     * @param play whether the decoded PCM should reach the speaker.
     * @return sample frames **accepted by the output device**, which is zero when [play] is false
     *   and may be less than a full packet when the device's buffer was partly full. A negative
     *   return means the decode failed; the caller counts it and carries on.
     */
    fun decode(
        packet: ByteArray?,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        play: Boolean,
    ): Int

    /**
     * Sample frames the output device reports having played since it started.
     *
     * This, minus what we have written, is the backlog the drift policy acts on. Implementations
     * should widen the platform's 32-bit counter rather than sign-extending it.
     */
    fun playbackPositionFrames(): Long

    /**
     * How many times the output device has run dry, or `-1` when the platform cannot say.
     *
     * A lifetime count, not a delta — [AudioDecoderCore] differences it. Underruns are the symptom
     * of the opposite problem from drift: too little buffered rather than too much.
     */
    fun underrunCount(): Int

    /** Drops everything in flight in both the codec and the output device. */
    fun flush()

    /** Releases the codec and the output device. Idempotent; safe to call from any state. */
    fun release()
}
