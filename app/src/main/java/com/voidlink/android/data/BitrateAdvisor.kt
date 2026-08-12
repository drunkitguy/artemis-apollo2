package com.voidlink.android.data

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What ended up deciding the recommended bitrate. */
enum class BitrateLimit(val label: String) {
    /** The codec and resolution asked for this much and the link had room to spare. */
    CODEC_BASELINE("Your settings"),

    /** The measured link could not carry the picture the settings asked for. */
    MEASURED_LINK("Measured throughput"),

    /** Jitter, loss or a link that degraded while we watched forced the number down. */
    LINK_QUALITY("Link stability"),

    /** The app's own 150 Mbps ceiling. */
    CLIENT_CEILING("Client limit"),
}

/**
 * A recommended bitrate and, just as importantly, why.
 *
 * The number on its own is a magic constant the user has no way to argue with. The explanation is
 * what lets them decide the recommendation is wrong for their case — which it sometimes will be.
 *
 * @property recommendedKbps what to write into [StreamSettings.bitrateKbps].
 * @property targetKbps what the chosen resolution, frame rate and codec asked for before any link
 *   evidence was applied.
 * @property wireKbps what [recommendedKbps] actually costs on the network once error correction,
 *   audio and packet headers are added.
 * @property limitedBy which constraint produced the final figure.
 * @property headline one sentence naming the recommendation and its limit.
 * @property reasons the derivation, in the order it was applied.
 * @property confident false when no throughput was measured, so the number rests on the codec
 *   baseline alone and the UI should say so.
 */
data class BitrateAdvice(
    val recommendedKbps: Int,
    val targetKbps: Int,
    val wireKbps: Int,
    val limitedBy: BitrateLimit,
    val headline: String,
    val reasons: List<String>,
    val confident: Boolean,
)

/**
 * Turns measurements plus the user's chosen video settings into a bitrate to use.
 *
 * Pure arithmetic over plain values — no Android, no coroutines, no I/O — so every rule below is
 * covered by ordinary JVM unit tests.
 *
 * ### The rule that matters
 *
 * **Never recommend more than [HEADROOM_FRACTION] of what the link was measured to carry, and
 * measure that against the *wire* cost rather than the video bitrate.** Users overshoot this
 * constantly, and overshooting is not a gentle degradation: the moment the stream needs more than
 * the path can deliver, packets are dropped, the decoder starts requesting keyframes, and latency
 * spikes into the hundreds of milliseconds. A stream at 20 Mbps that never drops a frame is
 * unambiguously better than one at 40 Mbps that hitches every few seconds, and the second one is
 * what "just set it to the maximum" produces.
 */
object BitrateAdvisor {

    /**
     * How much of the measured link a recommendation is allowed to use.
     *
     * Not a superstition: a home network's capacity is not a constant. Wi-Fi rate-adapts, other
     * devices come and go, and a wired link shares a switch with whatever else is on it. A number
     * measured once is the *best* case, and sizing a real-time stream to the best case guarantees
     * it breaks the first time anything else happens.
     */
    const val HEADROOM_FRACTION: Double = 0.55

    /**
     * Multiplier from the bitrate the user sets to the bytes that actually cross the network.
     *
     * The bitrate setting governs the **video** payload only. On top of it the stack adds forward
     * error correction — 20% by default in this protocol family, inherited from GeForce Experience
     * — plus an audio stream and per-packet RTP/UDP/IP headers. A 100 Mbps setting therefore puts
     * roughly 120–125 Mbps on the wire.
     *
     * Applying the headroom rule to the slider figure instead of this one would make every
     * recommendation about 25% optimistic, in exactly the direction that causes packet loss.
     *
     * **This is an assumption, not a fact about the user's system:** the FEC percentage is a
     * host-side setting they may have changed, and a host configured for heavier FEC costs more
     * than this. It is deliberately at the top of the 20–25% range for that reason.
     */
    const val WIRE_OVERHEAD: Double = 1.25

    /**
     * Baseline video bitrate in kbps for each resolution at 60 fps, H.264, SDR, 4:2:0.
     *
     * Two things shaped these numbers. First, they are aimed at "looks right on a good LAN" rather
     * than "survives a bad one" — the link evidence, not the baseline, is what makes a
     * recommendation safe. Second, they do **not** scale with pixel count: compression gets more
     * efficient as resolution rises, so 4K is worth about three times 1080p rather than four, and
     * 1440p about one and a half.
     *
     * [StreamResolution.NATIVE] has no pixel count until launch time, so it is treated as 1080p and
     * the explanation says so.
     */
    fun baselineKbpsAt60(resolution: StreamResolution): Int = when (resolution) {
        StreamResolution.RES_720P -> 15_000
        StreamResolution.RES_1080P -> 30_000
        StreamResolution.RES_1440P -> 45_000
        StreamResolution.RES_2160P -> 90_000
        StreamResolution.NATIVE -> 30_000
    }

    /**
     * How the baseline scales with frame rate.
     *
     * Not linear: doubling the frame rate roughly halves the difference between consecutive frames,
     * so the encoder needs about half again as many bits rather than twice as many. Anchored so
     * that 60 fps is 1.0 and 120 fps is 1.5, which matches the ratio the reference clients use.
     */
    fun frameRateFactor(frameRate: FrameRate): Double = 0.5 + 0.5 * (frameRate.fps / 60.0)

    /**
     * How much less bitrate a codec needs for the same picture.
     *
     * HEVC and AV1 reach comparable quality at roughly 30–40% fewer bits than H.264.
     * [VideoCodec.AUTO] is deliberately pessimistic: it may well negotiate down to H.264 on a
     * device whose HEVC decoder the host does not like, and a recommendation that assumed the
     * saving would then be too high for the codec actually in use.
     */
    fun codecFactor(codec: VideoCodec): Double = when (codec) {
        VideoCodec.H264 -> 1.0
        VideoCodec.HEVC -> 0.65
        VideoCodec.AV1 -> 0.60
        VideoCodec.AUTO -> 0.85
    }

    /** 10-bit HDR carries more data per sample; 15% is the usual observed cost. */
    const val HDR_FACTOR: Double = 1.15

    /** 4:4:4 chroma sends full colour resolution instead of quarter, and it is not free. */
    const val YUV444_FACTOR: Double = 1.30

    /** Recommendations are rounded to the granularity the bitrate slider actually stores. */
    const val ROUNDING_KBPS: Int = 500

    /**
     * Produces a bitrate recommendation.
     *
     * @param settings the video settings the recommendation is for; only the video fields are read.
     * @param link the Tier 1 latency/jitter/loss measurement, or `null` if it did not run.
     * @param throughput the Tier 2 measurement, or `null` when the user has not run one.
     */
    fun recommend(
        settings: StreamSettings,
        link: LinkQuality?,
        throughput: ThroughputEvidence?,
    ): BitrateAdvice {
        val reasons = ArrayList<String>()

        // ---- What the picture asks for -------------------------------------------------------
        val baseline = baselineKbpsAt60(settings.resolution)
        val fpsFactor = frameRateFactor(settings.frameRate)
        val codecFactor = codecFactor(settings.codec)
        val hdrFactor = if (settings.hdrEnabled) HDR_FACTOR else 1.0
        val chromaFactor = if (settings.yuv444Enabled) YUV444_FACTOR else 1.0
        val target = baseline * fpsFactor * codecFactor * hdrFactor * chromaFactor

        reasons += buildString {
            append(
                "${settings.resolution.label}${settings.frameRate.label} on ${settings.codec.label} " +
                    "wants about ${mbps(target)}",
            )
            if (settings.resolution == StreamResolution.NATIVE) {
                append(" — Native has no size until launch, so this assumes 1080p")
            }
            if (settings.hdrEnabled) append("; HDR adds 15%")
            if (settings.yuv444Enabled) append("; 4:4:4 chroma adds 30%")
            append(".")
        }

        // ---- What an unloaded link says about stability ---------------------------------------
        var qualityFactor = 1.0
        var qualityLimited = false
        if (link != null && link.isUsable) {
            if (link.jitterMs > SEVERE_JITTER_MS) {
                qualityFactor *= SEVERE_JITTER_PENALTY
                qualityLimited = true
                reasons += "Jitter of ${ms(link.jitterMs)} is high enough to drop frames on its own, " +
                    "so the target is cut by ${percentOff(SEVERE_JITTER_PENALTY)}. Variation in " +
                    "arrival time, not raw bandwidth, is what makes a stream stutter."
            } else if (link.jitterMs > NOTABLE_JITTER_MS) {
                qualityFactor *= NOTABLE_JITTER_PENALTY
                qualityLimited = true
                reasons += "Jitter of ${ms(link.jitterMs)} is more than a two-frame decode queue " +
                    "absorbs comfortably, so the target is cut by ${percentOff(NOTABLE_JITTER_PENALTY)}."
            }
            if (link.lossPercent > SEVERE_LOSS_PERCENT) {
                qualityFactor *= SEVERE_LOSS_PENALTY
                qualityLimited = true
                reasons += "${percent(link.lossPercent)} of test requests never came back. At that " +
                    "rate error correction cannot keep up, so the target is cut by " +
                    "${percentOff(SEVERE_LOSS_PENALTY)}."
            } else if (link.lossPercent > NOTABLE_LOSS_PERCENT) {
                qualityFactor *= NOTABLE_LOSS_PENALTY
                qualityLimited = true
                reasons += "${percent(link.lossPercent)} of test requests failed, so the target is " +
                    "cut by ${percentOff(NOTABLE_LOSS_PENALTY)}."
            }
            if (link.isDegrading) {
                qualityFactor *= DEGRADING_PENALTY
                qualityLimited = true
                reasons += "Latency climbed by ${ms(link.driftMs)} across the test window — the link " +
                    "gets worse the longer it is used — so the target is cut by a further " +
                    "${percentOff(DEGRADING_PENALTY)}."
            }
        } else if (link != null) {
            reasons += "The link test got only ${link.succeeded} answer(s) out of ${link.requested}, " +
                "which is not enough to measure anything, so it did not influence this number."
        }
        val qualityAdjusted = target * qualityFactor

        // ---- What the link was measured to carry -----------------------------------------------
        val capKbps = when (throughput) {
            null -> {
                reasons += "No throughput was measured, so this is the codec's own appetite rather " +
                    "than anything your network has been observed to do. Run the iperf3 test for a " +
                    "number with evidence behind it."
                null
            }

            is ThroughputEvidence.Sustained -> {
                val measuredKbps = throughput.megabitsPerSecond * 1_000.0
                val wireBudget = measuredKbps * HEADROOM_FRACTION
                reasons += "TCP measured ${mbps(measuredKbps)} sustained. A real-time stream may use " +
                    "at most ${percentOf(HEADROOM_FRACTION)} of that — ${mbps(wireBudget)} on the " +
                    "wire — because a link measured once is the best case, and sizing a stream to " +
                    "the best case guarantees it breaks the moment anything else uses the network."
                wireBudget / WIRE_OVERHEAD
            }

            is ThroughputEvidence.Loaded -> {
                val targetWire = throughput.targetMbps * 1_000.0
                val wireBudget = targetWire * loadedFactor(throughput.lossPercent)
                reasons += if (throughput.isClean) {
                    "UDP at ${mbps(targetWire)} arrived with ${percent(throughput.lossPercent)} loss " +
                        "and ${ms(throughput.jitterMs)} jitter — the link carried the intended rate " +
                        "cleanly, which is stronger evidence than a peak-bandwidth figure because it " +
                        "is the same traffic shape as the stream."
                } else {
                    "UDP at ${mbps(targetWire)} lost ${percent(throughput.lossPercent)} of its " +
                        "datagrams (${ms(throughput.jitterMs)} jitter), so that rate is above what " +
                        "this path will actually deliver; the budget is cut to ${mbps(wireBudget)} " +
                        "on the wire."
                }
                wireBudget / WIRE_OVERHEAD
            }
        }

        // ---- Resolve ---------------------------------------------------------------------------
        val beforeCeiling = if (capKbps == null) qualityAdjusted else min(qualityAdjusted, capKbps)
        val rounded = roundToStep(beforeCeiling)
        val recommended = rounded.coerceIn(
            StreamSettings.BITRATE_MIN_KBPS,
            StreamSettings.BITRATE_MAX_KBPS,
        )
        val wire = (recommended * WIRE_OVERHEAD).roundToInt()

        val limitedBy = when {
            rounded > StreamSettings.BITRATE_MAX_KBPS -> BitrateLimit.CLIENT_CEILING
            capKbps != null && capKbps < qualityAdjusted -> BitrateLimit.MEASURED_LINK
            qualityLimited -> BitrateLimit.LINK_QUALITY
            else -> BitrateLimit.CODEC_BASELINE
        }

        reasons += "Recommendation: ${mbps(recommended.toDouble())} of video, which is about " +
            "${mbps(wire.toDouble())} on the wire once ${percentOf(WIRE_OVERHEAD - 1.0)} for error " +
            "correction, audio and packet headers is added. The extra is an assumption — the error " +
            "correction rate is a setting on the PC, not something this app can read."

        if (limitedBy == BitrateLimit.CLIENT_CEILING) {
            reasons += "Capped at this app's 150 Mbps ceiling; above roughly that, hardware decoders " +
                "stall rather than get sharper."
        }

        return BitrateAdvice(
            recommendedKbps = recommended,
            targetKbps = roundToStep(target).coerceAtLeast(StreamSettings.BITRATE_MIN_KBPS),
            wireKbps = wire,
            limitedBy = limitedBy,
            headline = headlineFor(recommended, limitedBy),
            reasons = reasons,
            confident = throughput != null,
        )
    }

    /**
     * How much of a paced UDP rate is safe to plan around, given the loss it produced.
     *
     * A clean run is taken at face value — the link demonstrably carried that rate — while any real
     * loss means the rate was already too high and the answer has to come down sharply rather than
     * gently, because loss in a video stream is not proportional damage. One lost packet costs a
     * whole frame, and the keyframe request that follows costs several more.
     */
    fun loadedFactor(lossPercent: Double): Double = when {
        lossPercent <= ThroughputEvidence.Loaded.CLEAN_LOSS_PERCENT -> 1.0
        lossPercent <= 2.0 -> 0.75
        lossPercent <= 10.0 -> 0.50
        else -> 0.30
    }

    private const val NOTABLE_JITTER_MS = 10.0
    private const val SEVERE_JITTER_MS = 25.0
    private const val NOTABLE_JITTER_PENALTY = 0.80
    private const val SEVERE_JITTER_PENALTY = 0.60
    private const val NOTABLE_LOSS_PERCENT = 2.0
    private const val SEVERE_LOSS_PERCENT = 10.0
    private const val NOTABLE_LOSS_PENALTY = 0.75
    private const val SEVERE_LOSS_PENALTY = 0.50
    private const val DEGRADING_PENALTY = 0.85

    private fun headlineFor(kbps: Int, limit: BitrateLimit): String = when (limit) {
        BitrateLimit.CODEC_BASELINE ->
            "${mbps(kbps.toDouble())} — your settings, with room to spare on this link."
        BitrateLimit.MEASURED_LINK ->
            "${mbps(kbps.toDouble())} — held back by what the network was measured to carry."
        BitrateLimit.LINK_QUALITY ->
            "${mbps(kbps.toDouble())} — lowered because this link is not steady enough for more."
        BitrateLimit.CLIENT_CEILING ->
            "${mbps(kbps.toDouble())} — the highest this app will stream."
    }

    /** Rounds to the slider's own granularity so the applied value matches what the slider shows. */
    fun roundToStep(kbps: Double): Int {
        if (kbps.isNaN()) return StreamSettings.BITRATE_MIN_KBPS
        val steps = max(1.0, kbps / ROUNDING_KBPS).roundToInt()
        return steps * ROUNDING_KBPS
    }

    private fun mbps(kbps: Double): String =
        String.format(Locale.US, "%.1f Mbps", kbps / 1_000.0)

    private fun ms(value: Double): String = String.format(Locale.US, "%.1f ms", value)

    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    private fun percentOf(fraction: Double): String =
        String.format(Locale.US, "%.0f%%", fraction * 100.0)

    private fun percentOff(factor: Double): String =
        String.format(Locale.US, "%.0f%%", (1.0 - factor) * 100.0)
}
