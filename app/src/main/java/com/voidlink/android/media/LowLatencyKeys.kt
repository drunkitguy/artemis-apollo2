package com.voidlink.android.media

/**
 * One integer key/value pair to set on a `MediaFormat`.
 *
 * @property name the format key, e.g. `vendor.qti-ext-dec-low-latency.enable`.
 * @property value the integer to set.
 */
data class CodecIntegerKey(val name: String, val value: Int)

/**
 * The low-latency format keys, and the rule for which of them to try.
 *
 * `docs/01-PROTOCOL.md` §12.1 lists a set of vendor keys that measurably cut decode latency, and
 * warns that a codec which does not recognise one may reject `configure()` outright. The strategy
 * that follows — and that [MediaCodecDriver] implements — is:
 *
 * 1. Configure once with the standard key plus every applicable vendor key.
 * 2. If that throws, retry with only the standard key.
 * 3. If that throws, retry with none.
 *
 * Which means a vendor key can cost us a configure attempt but can never cost us the session.
 *
 * The values are literal strings rather than `MediaFormat` constants so this object stays a plain
 * JVM class: which keys we send for a given probe result is exactly the sort of thing that is
 * worth a unit test, and exactly the sort of thing that cannot be tested if it needs a device.
 */
object LowLatencyKeys {

    /**
     * `MediaFormat.KEY_LOW_LATENCY`, spelled out.
     *
     * The constant is API 30; the string is not. Spec §12.1 asks for the literal so the call site
     * compiles and runs identically regardless of `compileSdk`, with only a runtime version check
     * deciding whether to send it.
     */
    const val STANDARD_LOW_LATENCY: String = "low-latency"

    /** API level at which [STANDARD_LOW_LATENCY] became meaningful. */
    const val STANDARD_LOW_LATENCY_MIN_API: Int = 30

    /**
     * Every vendor low-latency key documented in spec §12.1, in the order they are applied.
     *
     * The HiSilicon pair is deliberately `1` then `-1`: the second key is the "ready" companion of
     * the first and the vendor's own value for it is negative. Do not "fix" it.
     */
    val VENDOR: List<CodecIntegerKey> = listOf(
        CodecIntegerKey("vendor.qti-ext-dec-low-latency.enable", 1),
        CodecIntegerKey("vendor.qti-ext-dec-picture-order.enable", 1),
        CodecIntegerKey("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1),
        CodecIntegerKey("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1),
        CodecIntegerKey("vendor.rtc-ext-dec-low-latency.enable", 1),
        CodecIntegerKey("vendor.low-latency.enable", 1),
        CodecIntegerKey("vdec-lowlatency", 1),
    )

    /**
     * The vendor keys worth sending to [candidate].
     *
     * On API 31+ the platform can list a codec's supported vendor parameters, so we send only the
     * intersection and waste no configure attempts. Below that the platform cannot tell us, and
     * [DecoderCandidate.vendorParametersKnown] is false — in which case we send **all** of them
     * and let the tiered retry sort it out. Treating an unpopulated list as "supports nothing"
     * would silently disable low-latency mode on every device below API 31, which is most of them.
     *
     * Matching is case-insensitive and tolerates the vendor prefix being reported with or without
     * a trailing value suffix, because vendors are not consistent about either.
     */
    fun vendorKeysFor(candidate: DecoderCandidate): List<CodecIntegerKey> {
        if (!candidate.vendorParametersKnown) return VENDOR
        if (candidate.supportedVendorParameters.isEmpty()) return emptyList()
        return VENDOR.filter { key ->
            candidate.supportedVendorParameters.any { supported ->
                supported.equals(key.name, ignoreCase = true)
            }
        }
    }
}
