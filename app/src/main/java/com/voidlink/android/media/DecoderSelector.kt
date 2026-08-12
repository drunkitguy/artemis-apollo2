package com.voidlink.android.media

import com.voidlink.android.data.VideoCodec

/**
 * Picks the decoder to use, given what the device reported and what the user asked for.
 *
 * A pure function over [DecoderCandidate] values — no Android, no I/O — which is the whole point:
 * codec selection is the part of the decode path most likely to be wrong on a device we do not
 * own, and it is the part CI can actually test.
 *
 * The rules implement `docs/01-PROTOCOL.md` §7.2 steps 1–6:
 *
 * 1. A decoder that cannot handle the requested **size** is never selected. Nothing good happens
 *    if we configure a 4K stream on a decoder that tops out at 1080p; failing here produces a
 *    sentence the user can act on ("lower the resolution") instead of a codec error at frame one.
 * 2. A decoder that handles the size but not the **frame rate** *is* selected, with a note. The
 *    frame rate is fixed at `/launch` time and cannot be renegotiated, and a decoder that merely
 *    fails to advertise 120 fps usually still decodes at 120 fps, just without a guarantee.
 * 3. An explicit codec preference is honoured, then relaxed down the ladder rather than failing.
 * 4. `Auto` prefers AV1 only when it probes clean **and** is hardware — spec §7.2 step 5 is
 *    explicit that AV1 low-latency decode is spotty, so the default ladder is HEVC → H.264.
 * 5. HDR requires a 10-bit-capable codec whose decoder advertises a 10-bit profile; when it does
 *    not, HDR is cleared and said out loud rather than silently ignored.
 */
object DecoderSelector {

    /**
     * Chooses a decoder for [request] from [candidates].
     *
     * @param candidates every decoder the platform reported, as produced by [DecoderProbe.probe]
     *   for this same request. Passing candidates probed against a *different* request produces
     *   wrong answers, because the capability booleans are request-relative.
     * @param request what the user asked for.
     * @return [DecoderSelectionResult.Selected] with the decoder and the format it will really
     *   decode, or [DecoderSelectionResult.NoDecoder] with a sentence naming the cause.
     */
    fun select(
        candidates: List<DecoderCandidate>,
        request: VideoFormatRequest,
    ): DecoderSelectionResult {
        if (request.width <= 0 || request.height <= 0 || request.frameRate <= 0) {
            return DecoderSelectionResult.NoDecoder(
                summary = "The requested stream format (${request.describe()}) is not a valid video size.",
                inspected = candidates,
            )
        }
        if (candidates.isEmpty()) {
            return DecoderSelectionResult.NoDecoder(
                summary = "This device reports no video decoders for H.264, HEVC or AV1.",
            )
        }

        val usable = candidates.filter { it.supportsRequestedSize }
        if (usable.isEmpty()) {
            return DecoderSelectionResult.NoDecoder(
                summary = "No decoder on this device supports ${request.width}×${request.height}. " +
                    "Lower the resolution in Settings and try again.",
                inspected = candidates,
            )
        }

        val ladder = ladderFor(request.preferredCodec, usable)
        val wantTenBit = request.hdr
        val chosen = ladder.firstNotNullOfOrNull { codec -> best(usable, codec, wantTenBit) }
            ?: return DecoderSelectionResult.NoDecoder(
                summary = "No usable decoder was found for ${request.describe()}.",
                inspected = candidates,
            )

        val notes = mutableListOf<String>()

        val preferred = VideoCodecType.fromPreference(request.preferredCodec)
        if (preferred != null && preferred != chosen.codec) {
            notes += "${preferred.label} is not available on this device; using ${chosen.codec.label}."
        }
        if (!chosen.hardwareAccelerated) {
            notes += "Only a software ${chosen.codec.label} decoder was found. Expect higher " +
                "latency and dropped frames; lowering the resolution helps most."
        }
        if (!chosen.supportsRequestedFrameRate) {
            notes += "This device does not advertise ${request.width}×${request.height} at " +
                "${request.frameRate} fps. The stream will run, but frames may be dropped."
        }

        val hdr = request.hdr && chosen.codec.tenBitCapable && chosen.supportsTenBit
        if (request.hdr && !hdr) {
            notes += "HDR needs a 10-bit decoder, which ${chosen.name} does not report. " +
                "Streaming in SDR."
        }

        return DecoderSelectionResult.Selected(
            DecoderChoice(
                candidate = chosen,
                format = VideoStreamFormat(
                    codec = chosen.codec,
                    width = request.width,
                    height = request.height,
                    frameRate = request.frameRate,
                    hdr = hdr,
                ),
                notes = notes.toList(),
            ),
        )
    }

    /**
     * The order codecs are tried in.
     *
     * An explicit preference leads, then everything below it, so a user who asked for AV1 on a
     * device without it lands on HEVC rather than on an error. `H.264` is the one preference that
     * does *not* relax: it is the "my device is struggling, force the safe codec" escape hatch and
     * silently upgrading it would defeat the setting.
     */
    private fun ladderFor(
        preference: VideoCodec,
        usable: List<DecoderCandidate>,
    ): List<VideoCodecType> = when (preference) {
        VideoCodec.H264 -> listOf(VideoCodecType.H264)
        VideoCodec.HEVC -> listOf(VideoCodecType.HEVC, VideoCodecType.H264, VideoCodecType.AV1)
        VideoCodec.AV1 -> listOf(VideoCodecType.AV1, VideoCodecType.HEVC, VideoCodecType.H264)
        VideoCodec.AUTO -> autoLadder(usable)
    }

    /**
     * `Auto` promotes AV1 to the front only when a hardware AV1 decoder handles the full requested
     * size *and* rate. Anything less and AV1 drops to last resort.
     */
    private fun autoLadder(usable: List<DecoderCandidate>): List<VideoCodecType> {
        val av1ProbesClean = usable.any {
            it.codec == VideoCodecType.AV1 && it.hardwareAccelerated && it.supportsRequestedFrameRate
        }
        return if (av1ProbesClean) {
            listOf(VideoCodecType.AV1, VideoCodecType.HEVC, VideoCodecType.H264)
        } else {
            listOf(VideoCodecType.HEVC, VideoCodecType.H264, VideoCodecType.AV1)
        }
    }

    /**
     * The best decoder for one codec: hardware first, then full rate support, then 10-bit when HDR
     * was asked for, then the most concurrent instances as an arbitrary but stable tie-break.
     */
    private fun best(
        usable: List<DecoderCandidate>,
        codec: VideoCodecType,
        wantTenBit: Boolean,
    ): DecoderCandidate? = usable
        .filter { it.codec == codec }
        .sortedWith(
            compareByDescending<DecoderCandidate> { it.hardwareAccelerated }
                .thenByDescending { it.supportsRequestedFrameRate }
                .thenByDescending { wantTenBit && it.supportsTenBit }
                .thenByDescending { it.maxSupportedInstances }
                .thenBy { it.name },
        )
        .firstOrNull()
}
