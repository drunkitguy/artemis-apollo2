package com.voidlink.android.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.voidlink.android.protocol.ProtocolLog

/**
 * Enumerates this device's real video decoders.
 *
 * This is the *only* class in `media/` that talks to the Android media stack during selection, and
 * it deliberately does no deciding: it answers capability questions about a specific
 * [VideoFormatRequest] and hands the answers to [DecoderSelector], which is pure and tested.
 *
 * Spec §7.2 step 1 asks for `MediaCodecList(REGULAR_CODECS)` and for hardware decoders to be
 * distinguishable from software ones. `MediaCodecInfo.isHardwareAccelerated` exists from API 29;
 * below that the documented fallback is to treat the `OMX.google.` and `c2.android.` name prefixes
 * as software, which is exactly what [isLikelyHardware] does.
 *
 * Nothing here throws. A device whose media stack misbehaves during enumeration yields an empty
 * or partial list, which the selector turns into a sentence the user can read — a far better
 * outcome than an exception on the way into a fullscreen black window.
 */
object MediaCodecProbe : DecoderProbe {

    /** Log tag for the decode path, matching architecture §9's per-subsystem tags. */
    const val TAG: String = "VL.Video"

    override fun probe(request: VideoFormatRequest): List<DecoderCandidate> {
        val infos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        } catch (error: Throwable) {
            ProtocolLog.e(TAG, "Could not enumerate media codecs", error)
            return emptyList()
        }

        val candidates = ArrayList<DecoderCandidate>()
        for (info in infos) {
            if (info == null) continue
            val isEncoder = try {
                info.isEncoder
            } catch (error: Throwable) {
                true
            }
            if (isEncoder) continue

            val supportedTypes: List<String> = try {
                info.supportedTypes?.toList() ?: emptyList()
            } catch (error: Throwable) {
                ProtocolLog.w(TAG, "Codec ${info.name} would not report its types", error)
                emptyList()
            }

            for (mimeType in supportedTypes) {
                val codec = VideoCodecType.fromMimeType(mimeType) ?: continue
                val candidate = evaluate(info, mimeType, codec, request) ?: continue
                candidates += candidate
            }
        }

        val summary = if (candidates.isEmpty()) {
            "nothing usable found"
        } else {
            candidates.joinToString(separator = "; ") { it.describe() }
        }
        ProtocolLog.i(TAG, "Decoder probe for ${request.describe()}: $summary")

        for (support in DecoderInventory.from(candidates)) {
            ProtocolLog.i(TAG, "  ${support.describe()}")
        }

        // Worth its own line in the log: a software-only AV1 decoder is the single most common way
        // for a device to look AV1-capable and stream terribly.
        val av1 = DecoderInventory.from(candidates).firstOrNull { it.codec == VideoCodecType.AV1 }
        if (av1 != null && av1.available && !av1.hardwareAccelerated) {
            ProtocolLog.w(
                TAG,
                "AV1 is present only as a software decoder (${av1.decoderName}); it will not be used.",
            )
        }

        return candidates
    }

    /**
     * Turns one `(codec, mime type)` pair into a [DecoderCandidate], or `null` when the codec
     * cannot be interrogated at all.
     */
    private fun evaluate(
        info: MediaCodecInfo,
        mimeType: String,
        codec: VideoCodecType,
        request: VideoFormatRequest,
    ): DecoderCandidate? {
        val capabilities = try {
            info.getCapabilitiesForType(mimeType)
        } catch (error: Throwable) {
            // Some devices list a type they then refuse to describe. Skipping is correct: we
            // cannot configure what we cannot measure.
            ProtocolLog.w(TAG, "Codec ${info.name} would not describe $mimeType", error)
            return null
        } ?: return null

        val videoCapabilities = capabilities.videoCapabilities ?: return null

        val sizeSupported = try {
            videoCapabilities.isSizeSupported(request.width, request.height)
        } catch (error: Throwable) {
            false
        }
        val rateSupported = if (!sizeSupported) {
            false
        } else {
            try {
                videoCapabilities.areSizeAndRateSupported(
                    request.width,
                    request.height,
                    request.frameRate.toDouble(),
                )
            } catch (error: Throwable) {
                false
            }
        }

        val instances = try {
            capabilities.maxSupportedInstances
        } catch (error: Throwable) {
            1
        }

        val maxWidth = try {
            videoCapabilities.supportedWidths.upper
        } catch (error: Throwable) {
            0
        }
        val maxHeight = try {
            videoCapabilities.supportedHeights.upper
        } catch (error: Throwable) {
            0
        }
        val maxFrameRate = try {
            videoCapabilities.supportedFrameRates.upper
        } catch (error: Throwable) {
            0
        }
        val maxFrameRateHere = if (!sizeSupported) {
            0
        } else {
            try {
                videoCapabilities
                    .getSupportedFrameRatesFor(request.width, request.height)
                    .upper
                    .toInt()
            } catch (error: Throwable) {
                0
            }
        }

        val vendorParameters = supportedVendorParameters(capabilities)

        return DecoderCandidate(
            name = info.name,
            codec = codec,
            hardwareAccelerated = isLikelyHardware(info),
            supportsRequestedSize = sizeSupported,
            supportsRequestedFrameRate = rateSupported,
            supportsTenBit = supportsTenBit(capabilities, codec),
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxFrameRate = maxFrameRate,
            maxFrameRateAtRequestedSize = maxFrameRateHere,
            maxSupportedInstances = if (instances > 0) instances else 1,
            supportedVendorParameters = vendorParameters ?: emptyList(),
            vendorParametersKnown = vendorParameters != null,
        )
    }

    /**
     * Whether the codec runs on dedicated hardware.
     *
     * On API 29+ the platform answers directly. Below that we use the name-prefix rule from spec
     * §7.2: `OMX.google.` and `c2.android.` are Google's reference software codecs, and everything
     * else is assumed to be a vendor implementation. The assumption errs towards "hardware", which
     * is the right way round — a mislabelled software decoder still streams, just slowly, whereas
     * mislabelling hardware as software would push a capable device onto a warning it does not
     * need.
     */
    private fun isLikelyHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val reported = try {
                info.isHardwareAccelerated
            } catch (error: Throwable) {
                null
            }
            if (reported != null) return reported
        }
        val name = info.name ?: return false
        return !name.startsWith("OMX.google.", ignoreCase = true) &&
            !name.startsWith("c2.android.", ignoreCase = true)
    }

    /**
     * Whether a 10-bit profile is advertised, which is the device-side half of the HDR gate
     * (spec §12.2).
     *
     * H.264 has no 10-bit profile we negotiate, so it is answered `false` without inspecting
     * anything. `AV1Profile*` constants are API 29, so AV1 is answered `false` below that — which
     * is academic, since AV1 decoders essentially do not exist on older releases.
     */
    private fun supportsTenBit(
        capabilities: MediaCodecInfo.CodecCapabilities,
        codec: VideoCodecType,
    ): Boolean {
        if (!codec.tenBitCapable) return false
        val profileLevels = try {
            capabilities.profileLevels
        } catch (error: Throwable) {
            null
        } ?: return false

        val tenBitProfiles: Set<Int> = when (codec) {
            VideoCodecType.HEVC -> setOf(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            )
            VideoCodecType.AV1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setOf(
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                    MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                )
            } else {
                emptySet()
            }
            VideoCodecType.H264 -> emptySet()
        }
        if (tenBitProfiles.isEmpty()) return false

        return profileLevels.any { level -> level != null && level.profile in tenBitProfiles }
    }

    /**
     * The vendor parameters the codec admits to supporting, or `null` when the platform cannot be
     * asked.
     *
     * The null-versus-empty distinction matters and is preserved all the way into
     * [LowLatencyKeys.vendorKeysFor]: below API 31 "we do not know" must not become "supports
     * none", or low-latency mode quietly switches itself off on most devices in use.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun supportedVendorParameters(
        capabilities: MediaCodecInfo.CodecCapabilities,
    ): List<String>? {
        // The vendor-parameter list is exposed by MediaCodec.getSupportedVendorParameters() on
        // API 31+, not by CodecCapabilities, so it cannot be read from a static probe without
        // instantiating the codec — which is far too expensive to do for every decoder on the
        // device just to enumerate capabilities.
        //
        // Returning null is the correct answer rather than a limitation to work around: it means
        // "not asked", which [LowLatencyKeys.vendorKeysFor] treats as "try the keys and let the
        // codec reject what it does not know". Returning an empty list would mean "supports none"
        // and would quietly disable low-latency mode on most devices.
        return null
    }
}
