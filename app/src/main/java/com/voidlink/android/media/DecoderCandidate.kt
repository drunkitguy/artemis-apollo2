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
 *   — below that — whether the name is not one of the known software prefixes.
 * @property supportsRequestedSize whether the requested width × height is within the decoder's
 *   advertised range. A decoder that fails this is unusable and is never selected.
 * @property supportsRequestedFrameRate whether the requested size *and* rate are supported
 *   together. A decoder that fails only this is still selected, with a warning note.
 * @property supportsTenBit whether a 10-bit profile (Main10 / AV1 Main10) is advertised. HDR
 *   needs this.
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
    val maxSupportedInstances: Int = 1,
    val supportedVendorParameters: List<String> = emptyList(),
    val vendorParametersKnown: Boolean = false,
) {
    /** One-line description used in the "no decoder" failure screen's detail block. */
    fun describe(): String = buildString {
        append(name)
        append(" (")
        append(codec.label)
        append(if (hardwareAccelerated) ", hardware" else ", software")
        if (!supportsRequestedSize) {
            append(", size unsupported")
        } else if (!supportsRequestedFrameRate) {
            append(", frame rate unsupported")
        }
        if (supportsTenBit) append(", 10-bit")
        append(')')
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
 */
data class DecoderChoice(
    val candidate: DecoderCandidate,
    val format: VideoStreamFormat,
    val notes: List<String> = emptyList(),
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
     */
    data class NoDecoder(
        val summary: String,
        val inspected: List<DecoderCandidate> = emptyList(),
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
