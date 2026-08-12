package com.voidlink.android.media

/**
 * One decoder the platform reports, evaluated against a specific [VideoFormatRequest].
 *
 * The capability questions are answered *at probe time* rather than being carried as a live
 * `MediaCodecInfo`, which is what makes selection a pure function and therefore testable without
 * a device: [DecoderProbe] does all the Android talking, [DecoderSelector] does all the deciding.
 *
 * @property name the codec's platform name, e.g. `c2.qti.hevc.decoder`. This is what
 *   `MediaCodec.createByCodecName` is called with; picking by name rather than by mime type is
 *   what lets us reject a software decoder the platform would otherwise hand us.
 * @property codec which codec family this decoder handles.
 * @property hardwareAccelerated whether the platform reports hardware acceleration (API 29+), or
 *   — below that — whether the name is not one of the known software prefixes. **The single most
 *   important field here.** `MediaCodecList` cheerfully returns `c2.android.av1-decoder`
 *   (libgav1, pure software) on devices with no AV1 decode block at all, and software AV1 at
 *   1080p60 on a handheld is a latency disaster that a user reads as a bad network.
 * @property supportsRequestedSize whether the requested width × height is within the decoder's
 *   advertised range. A decoder that fails this is unusable and is never selected.
 * @property supportsRequestedFrameRate whether the requested size *and* rate are supported
 *   together. A decoder that fails only this is still selectable, with a warning note.
 * @property supportsTenBit whether a 10-bit profile (HEVC Main10, AV1 Main10) is advertised. HDR
 *   needs this, and it is checked per profile rather than per codec: a decoder that handles 8-bit
 *   AV1 but not `AV1ProfileMain10` is not HDR-capable and must not be reported as such.
 * @property maxWidth largest width the decoder advertises, or 0 when unknown.
 * @property maxHeight largest height the decoder advertises, or 0 when unknown.
 * @property maxFrameRate highest frame rate the decoder advertises anywhere in its range, or 0
 *   when unknown. Achievable only at low resolutions on most parts.
 * @property maxFrameRateAtRequestedSize highest frame rate advertised **at the requested
 *   resolution**, or 0 when unknown or the size is unsupported. This is the number that actually
 *   answers "can this device run my stream".
 * @property maxSupportedInstances how many concurrent instances the platform allows; used only to
 *   break ties between otherwise equal decoders.
 * @property supportedVendorParameters vendor parameter names the codec advertises (API 31+).
 * @property vendorParametersKnown whether [supportedVendorParameters] was actually populated. On
 *   API 30 and below the platform cannot tell us, and "empty" must not be read as "none" — see
 *   [LowLatencyKeys.vendorKeysFor].
 */
data class DecoderCandidate(
    val name: String,
    val codec: VideoCodecType,
    val hardwareAccelerated: Boolean,
    val supportsRequestedSize: Boolean,
    val supportsRequestedFrameRate: Boolean,
    val supportsTenBit: Boolean = false,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFrameRate: Int = 0,
    val maxFrameRateAtRequestedSize: Int = 0,
    val maxSupportedInstances: Int = 1,
    val supportedVendorParameters: List<String> = emptyList(),
    val vendorParametersKnown: Boolean = false,
) {
    /** One-line description used in the "no decoder" failure screen's detail block. */
    fun describe(): String = buildString {
        append(name)
        append(" (")
        append(codec.label)
        append(if (hardwareAccelerated) ", hardware" else ", SOFTWARE")
        if (maxWidth > 0 && maxHeight > 0) {
            append(", up to ")
            append(maxWidth)
            append('×')
            append(maxHeight)
        }
        if (maxFrameRateAtRequestedSize > 0) {
            append(", ")
            append(maxFrameRateAtRequestedSize)
            append(" fps at the requested size")
        } else if (!supportsRequestedSize) {
            append(", requested size unsupported")
        }
        append(if (supportsTenBit) ", 10-bit" else ", 8-bit only")
        append(')')
    }
}

/**
 * What this device can do with one codec, aggregated from every decoder it reports for it.
 *
 * This is the answer to "can this device actually do AV1", and it is deliberately reported for
 * every codec rather than only for the one we picked: a user choosing a codec in Settings is
 * guessing unless they can see which of the three the hardware can genuinely decode.
 *
 * @property codec the codec this describes.
 * @property decoderName the decoder that would be used — the hardware one when there is one.
 * @property available whether any decoder at all exists for the codec.
 * @property hardwareAccelerated whether a **hardware** decoder exists. When this is false and
 *   [available] is true, the platform only offers software decode, which for real-time streaming
 *   is not usable.
 * @property maxWidth largest advertised width across the representative decoders.
 * @property maxHeight largest advertised height.
 * @property maxFrameRate highest advertised frame rate anywhere in the range.
 * @property supportsRequestedSize whether the requested resolution is supported.
 * @property supportsRequestedFrameRate whether the requested resolution and rate are supported.
 * @property maxFrameRateAtRequestedSize highest advertised rate at the requested resolution.
 * @property supportsTenBit whether a 10-bit profile is advertised, which is what HDR requires.
 */
data class CodecSupport(
    val codec: VideoCodecType,
    val decoderName: String?,
    val available: Boolean,
    val hardwareAccelerated: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Int,
    val supportsRequestedSize: Boolean,
    val supportsRequestedFrameRate: Boolean,
    val maxFrameRateAtRequestedSize: Int,
    val supportsTenBit: Boolean,
) {
    /**
     * Whether this codec is usable for real-time streaming, which means a hardware decoder that
     * covers the requested resolution. Software decode does not qualify.
     */
    val usableForRealTime: Boolean get() = available && hardwareAccelerated && supportsRequestedSize

    /** A compact line for the device-capability report shown on the failure screen. */
    fun describe(): String = buildString {
        append(codec.label.padEnd(6))
        append(' ')
        when {
            !available -> append("no decoder")
            !hardwareAccelerated -> append("software only — not usable for streaming")
            else -> {
                append("hardware")
                if (maxWidth > 0 && maxHeight > 0) {
                    append(" up to ")
                    append(maxWidth)
                    append('×')
                    append(maxHeight)
                }
                if (maxFrameRate > 0) {
                    append(" @ ")
                    append(maxFrameRate)
                    append(" fps")
                }
                append(if (supportsTenBit) ", 10-bit/HDR" else ", 8-bit only")
                if (!supportsRequestedSize) {
                    append(" — requested size unsupported")
                } else if (!supportsRequestedFrameRate) {
                    append(" — requested frame rate not advertised")
                }
            }
        }
        if (decoderName != null) {
            append("  [")
            append(decoderName)
            append(']')
        }
    }
}

/**
 * Rolls a probe's raw candidate list up into one [CodecSupport] per codec.
 *
 * Pure, so the report the UI shows is unit-tested rather than assembled ad hoc at the call site.
 */
object DecoderInventory {

    /**
     * Builds the per-codec report, in [VideoCodecType.ordered] order (AV1, HEVC, H.264).
     *
     * When a codec has both hardware and software decoders, only the hardware ones contribute to
     * the reported maxima: a software decoder's generous advertised limits would otherwise make a
     * codec look far more capable than the device can actually deliver in real time.
     */
    fun from(candidates: List<DecoderCandidate>): List<CodecSupport> =
        VideoCodecType.ordered.map { codec -> supportFor(codec, candidates) }

    private fun supportFor(
        codec: VideoCodecType,
        candidates: List<DecoderCandidate>,
    ): CodecSupport {
        val forCodec = candidates.filter { it.codec == codec }
        if (forCodec.isEmpty()) {
            return CodecSupport(
                codec = codec,
                decoderName = null,
                available = false,
                hardwareAccelerated = false,
                maxWidth = 0,
                maxHeight = 0,
                maxFrameRate = 0,
                supportsRequestedSize = false,
                supportsRequestedFrameRate = false,
                maxFrameRateAtRequestedSize = 0,
                supportsTenBit = false,
            )
        }

        val hardware = forCodec.filter { it.hardwareAccelerated }
        val representatives = if (hardware.isNotEmpty()) hardware else forCodec

        return CodecSupport(
            codec = codec,
            decoderName = representatives.first().name,
            available = true,
            hardwareAccelerated = hardware.isNotEmpty(),
            maxWidth = representatives.maxOf { it.maxWidth },
            maxHeight = representatives.maxOf { it.maxHeight },
            maxFrameRate = representatives.maxOf { it.maxFrameRate },
            supportsRequestedSize = representatives.any { it.supportsRequestedSize },
            supportsRequestedFrameRate = representatives.any { it.supportsRequestedFrameRate },
            maxFrameRateAtRequestedSize = representatives.maxOf { it.maxFrameRateAtRequestedSize },
            supportsTenBit = representatives.any { it.supportsTenBit },
        )
    }
}

/**
 * The decoder that will be used, together with the format it will be configured with.
 *
 * @property candidate the chosen decoder.
 * @property format the format actually being decoded. May differ from what the user asked for —
 *   most often with [VideoStreamFormat.hdr] cleared, or on a different codec.
 * @property notes plain-language explanations of every way [format] departs from the request.
 *   The stream screen shows these, which is how a silent downgrade becomes a visible one.
 * @property inventory what the device reported for all three codecs, for the capability report.
 */
data class DecoderChoice(
    val candidate: DecoderCandidate,
    val format: VideoStreamFormat,
    val notes: List<String> = emptyList(),
    val inventory: List<CodecSupport> = emptyList(),
)

/**
 * The outcome of [DecoderSelector.select].
 *
 * There are exactly two: a decoder was chosen, or none can decode this stream. There is no third
 * "maybe" state, because the caller's only two possible behaviours are "stream" and "explain why
 * we cannot".
 */
sealed interface DecoderSelectionResult {

    /** A decoder was found. */
    data class Selected(val choice: DecoderChoice) : DecoderSelectionResult

    /**
     * Nothing on this device can decode the requested stream.
     *
     * @property summary a single sentence naming the cause, suitable as the failure screen's body.
     * @property inspected every decoder that was considered, for the detail block. Empty when the
     *   platform reported no video decoders at all.
     * @property inventory the per-codec capability report, so the failure screen can show what the
     *   device *can* do alongside what it cannot.
     */
    data class NoDecoder(
        val summary: String,
        val inspected: List<DecoderCandidate> = emptyList(),
        val inventory: List<CodecSupport> = emptyList(),
    ) : DecoderSelectionResult
}

/**
 * Enumerates the device's video decoders.
 *
 * Declared as an interface so that the stream screen can be driven from a fake in tests and
 * previews, and so that the one class that touches `MediaCodecList` ([MediaCodecProbe]) is the
 * only thing in this package that cannot run on the JVM.
 */
interface DecoderProbe {
    /**
     * Returns every decoder the platform reports for the codecs in [VideoCodecType], each already
     * evaluated against [request].
     *
     * Never throws: a platform that fails to enumerate returns an empty list, which the selector
     * turns into an honest [DecoderSelectionResult.NoDecoder].
     */
    fun probe(request: VideoFormatRequest): List<DecoderCandidate>
}
