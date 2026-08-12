package com.voidlink.android.media

import com.voidlink.android.data.VideoCodec

/**
 * Picks the decoder to use, given what the device reported and what the user asked for.
 *
 * A pure function over [DecoderCandidate] values — no Android, no I/O — which is the whole point:
 * codec selection is the part of the decode path most likely to be wrong on a device we do not
 * own, and it is the part CI can actually test.
 *
 * ### The rules
 *
 * 1. **A decoder that cannot handle the requested size is never selected.** Nothing good happens
 *    if we configure a 4K stream on a decoder that tops out at 1080p; failing here produces a
 *    sentence the user can act on ("lower the resolution") instead of a codec error at frame one.
 * 2. **Hardware decode is required for AV1, full stop.** `MediaCodecList` returns Google's
 *    software AV1 decoder (`c2.android.av1-decoder`, libgav1) on devices with no AV1 hardware at
 *    all, and software AV1 at 1080p60 on a handheld is a latency catastrophe that presents to the
 *    user as a network problem. Software AV1 is therefore never selected — AV1 is reported as
 *    unavailable and the reason is said out loud.
 * 3. **Hardware decode is strongly preferred for everything else.** A software H.264 or HEVC
 *    decoder is chosen only when no hardware decoder on the device can handle the stream at all,
 *    and never silently: the note says so in the plainest terms available.
 * 4. **H.264 is the last resort, never a default.** There is no 10-bit H.264 in this protocol
 *    (spec §7.1), so falling back to it forecloses HDR entirely. The `Auto` ladder therefore runs
 *    down decode efficiency — AV1, then HEVC, then H.264 — rather than up.
 * 5. **An explicit codec preference is honoured when hardware can satisfy it**, then relaxed with
 *    an explanation rather than failing.
 * 6. **HDR requires a 10-bit profile on the chosen decoder specifically**, not merely a 10-bit
 *    capable codec family. When it is missing, HDR is cleared and said out loud.
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
        val inventory = DecoderInventory.from(candidates)

        if (request.width <= 0 || request.height <= 0 || request.frameRate <= 0) {
            return DecoderSelectionResult.NoDecoder(
                summary = "The requested stream format (${request.describe()}) is not a valid video size.",
                inspected = candidates,
                inventory = inventory,
            )
        }
        if (candidates.isEmpty()) {
            return DecoderSelectionResult.NoDecoder(
                summary = "This device reports no video decoders for H.264, HEVC or AV1.",
                inventory = inventory,
            )
        }

        val usable = candidates.filter { it.supportsRequestedSize }
        if (usable.isEmpty()) {
            return DecoderSelectionResult.NoDecoder(
                summary = "No decoder on this device supports ${request.width}×${request.height}. " +
                    "Lower the resolution in Settings and try again.",
                inspected = candidates,
                inventory = inventory,
            )
        }

        // Rule 2: software AV1 is not a candidate for anything, ever.
        val eligible = usable.filter { it.codec != VideoCodecType.AV1 || it.hardwareAccelerated }
        val hardware = eligible.filter { it.hardwareAccelerated }
        val software = eligible.filter { !it.hardwareAccelerated }

        val ladder = ladderFor(request.preferredCodec, hardware)
        val wantTenBit = request.hdr

        // Rule 3: exhaust the hardware ladder before considering any software decoder.
        var usedSoftwareFallback = false
        var chosen = ladder.firstNotNullOfOrNull { codec -> best(hardware, codec, wantTenBit) }
        if (chosen == null) {
            chosen = ladder.firstNotNullOfOrNull { codec -> best(software, codec, wantTenBit) }
            usedSoftwareFallback = chosen != null
        }
        if (chosen == null) {
            return DecoderSelectionResult.NoDecoder(
                summary = noDecoderSummary(request, usable, inventory),
                inspected = candidates,
                inventory = inventory,
            )
        }

        val notes = buildNotes(
            request = request,
            chosen = chosen,
            usable = usable,
            usedSoftwareFallback = usedSoftwareFallback,
        )

        val hdr = request.hdr && chosen.codec.tenBitCapable && chosen.supportsTenBit

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
                notes = notes,
                inventory = inventory,
            ),
        )
    }

    /**
     * The order codecs are tried in.
     *
     * An explicit preference leads, then everything below it in efficiency order, so a user who
     * asked for AV1 on a device without AV1 hardware lands on HEVC rather than on an error.
     * `H.264` is the one preference that does *not* relax: it is the "my device is struggling,
     * force the safe codec" escape hatch, and silently upgrading it would defeat the setting.
     *
     * @param hardware the hardware-decodable candidates, used only to decide whether AV1 has
     *   earned the front of the `Auto` ladder.
     */
    private fun ladderFor(
        preference: VideoCodec,
        hardware: List<DecoderCandidate>,
    ): List<VideoCodecType> = when (preference) {
        VideoCodec.H264 -> listOf(VideoCodecType.H264)
        VideoCodec.HEVC -> listOf(VideoCodecType.HEVC, VideoCodecType.AV1, VideoCodecType.H264)
        VideoCodec.AV1 -> listOf(VideoCodecType.AV1, VideoCodecType.HEVC, VideoCodecType.H264)
        VideoCodec.AUTO -> autoLadder(hardware)
    }

    /**
     * The `Auto` ladder.
     *
     * AV1 leads when a hardware AV1 decoder covers the requested size **and** frame rate — spec
     * §7.2 step 5 warns that AV1 hardware decode at low latency is spotty, so "probes clean" is
     * the bar. When it does not, AV1 drops behind HEVC but stays ahead of H.264: H.264 is the last
     * resort in every ladder, because it is the only codec with no 10-bit profile in this protocol
     * and defaulting to it would quietly foreclose HDR.
     */
    private fun autoLadder(hardware: List<DecoderCandidate>): List<VideoCodecType> {
        val av1ProbesClean = hardware.any {
            it.codec == VideoCodecType.AV1 && it.supportsRequestedFrameRate
        }
        return if (av1ProbesClean) {
            listOf(VideoCodecType.AV1, VideoCodecType.HEVC, VideoCodecType.H264)
        } else {
            listOf(VideoCodecType.HEVC, VideoCodecType.AV1, VideoCodecType.H264)
        }
    }

    /**
     * Every plain-language note the stream screen shows.
     *
     * The AV1 notes are the important ones. "This device has no hardware AV1 decoder; using HEVC
     * instead" is worth more than the fallback it describes: without it, a user who asked for AV1
     * and got HEVC has no way to know the request was refused, and no way to know why.
     */
    private fun buildNotes(
        request: VideoFormatRequest,
        chosen: DecoderCandidate,
        usable: List<DecoderCandidate>,
        usedSoftwareFallback: Boolean,
    ): List<String> {
        val notes = mutableListOf<String>()
        val preferred = VideoCodecType.fromPreference(request.preferredCodec)

        val softwareAv1Only = usable.any { it.codec == VideoCodecType.AV1 && !it.hardwareAccelerated } &&
            usable.none { it.codec == VideoCodecType.AV1 && it.hardwareAccelerated }
        val noAv1AtAll = usable.none { it.codec == VideoCodecType.AV1 }

        if (chosen.codec != VideoCodecType.AV1) {
            when {
                softwareAv1Only -> notes += "This device has no hardware AV1 decoder — only a " +
                    "software one, which cannot keep up with a live stream. Using " +
                    "${chosen.codec.label} instead."
                preferred == VideoCodecType.AV1 && noAv1AtAll ->
                    notes += "This device has no AV1 decoder at all. Using ${chosen.codec.label} instead."
            }
        }

        if (preferred != null && preferred != chosen.codec && preferred != VideoCodecType.AV1) {
            notes += "${preferred.label} is not available on this device; using ${chosen.codec.label}."
        }

        if (usedSoftwareFallback) {
            notes += "Only a software ${chosen.codec.label} decoder was found. Expect high " +
                "latency and dropped frames; lowering the resolution helps most."
        }

        if (!chosen.supportsRequestedFrameRate) {
            val advertised = if (chosen.maxFrameRateAtRequestedSize > 0) {
                " (it advertises up to ${chosen.maxFrameRateAtRequestedSize} fps there)"
            } else {
                ""
            }
            notes += "This device does not advertise ${request.width}×${request.height} at " +
                "${request.frameRate} fps$advertised. The stream will run, but frames may be dropped."
        }

        if (request.hdr && !(chosen.codec.tenBitCapable && chosen.supportsTenBit)) {
            notes += if (chosen.codec == VideoCodecType.H264) {
                "HDR needs a 10-bit codec and there is no 10-bit H.264 in this protocol. " +
                    "Streaming in SDR."
            } else {
                "HDR needs a 10-bit profile, which ${chosen.name} does not report. Streaming in SDR."
            }
        }

        return notes.toList()
    }

    /** The sentence shown when the ladder found nothing at all. */
    private fun noDecoderSummary(
        request: VideoFormatRequest,
        usable: List<DecoderCandidate>,
        inventory: List<CodecSupport>,
    ): String {
        if (VideoCodecType.fromPreference(request.preferredCodec) == VideoCodecType.H264) {
            val h264 = inventory.firstOrNull { it.codec == VideoCodecType.H264 }
            if (h264 == null || !h264.usableForRealTime) {
                return "The Preferred Codec setting is fixed to H.264, and this device has no " +
                    "hardware H.264 decoder for ${request.width}×${request.height}. Set Preferred " +
                    "Codec to Auto in Settings."
            }
        }
        val onlySoftwareAv1 = usable.isNotEmpty() &&
            usable.all { it.codec == VideoCodecType.AV1 && !it.hardwareAccelerated }
        if (onlySoftwareAv1) {
            return "The only decoder this device offers for ${request.describe()} is a software " +
                "AV1 decoder, which cannot decode a live stream fast enough to be usable."
        }
        return "No usable decoder was found for ${request.describe()}."
    }

    /**
     * The best decoder for one codec: full rate support first, then 10-bit when HDR was asked for,
     * then the most concurrent instances as an arbitrary but stable tie-break.
     *
     * Hardware-versus-software does not appear here because the caller has already partitioned on
     * it — this is only ever called with a list that is entirely one or entirely the other.
     */
    private fun best(
        candidates: List<DecoderCandidate>,
        codec: VideoCodecType,
        wantTenBit: Boolean,
    ): DecoderCandidate? = candidates
        .filter { it.codec == codec }
        .sortedWith(
            compareByDescending<DecoderCandidate> { it.supportsRequestedFrameRate }
                .thenByDescending { wantTenBit && it.supportsTenBit }
                .thenByDescending { it.maxSupportedInstances }
                .thenBy { it.name },
        )
        .firstOrNull()
}
