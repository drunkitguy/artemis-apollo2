package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.ProtocolLog

/**
 * Builds the ANNOUNCE SDP payload — the client's complete stream configuration (spec §6.4).
 *
 * This is the single most consequential piece of text the client ever sends. Everything about the
 * session that is not a port number is decided here, once, before PLAY; there is no message
 * anywhere in the control protocol (§9.3–§9.5) that changes any of it afterwards.
 *
 * Two consequences worth stating out loud, because both surface in the UI:
 *
 * * **Bitrate is pinned.** `bw.minimumBitrateKbps == bw.maximumBitrateKbps == our number` disables
 *   the host's adaptive bitrate, which is what we want — quality is decided on the client. It also
 *   means the bitrate cannot change mid-session, which is why `03-UI-SPEC.md` §5.3 marks that row
 *   "Reconnect required" next to resolution and frame rate. Do not go looking for a "set bitrate"
 *   control message; there isn't one.
 * * **Dynamic resolution change is off.** Accepting it would mean reconfiguring `MediaCodec`
 *   mid-stream, which the video layer does not do.
 *
 * Attribute order follows the tables of spec §6.4 group by group. Hosts parse the attributes by
 * name and do not care, but a fixed order is what lets the golden tests compare whole documents
 * byte for byte, so a stray or missing line is a one-character diff rather than a set comparison
 * nobody reads.
 *
 * Gen-3-only options (`x-nv-general.serverAddress`, `x-nv-video[N].transferProtocol`,
 * `bw.flags=14083`, per-index `rateControlMode`) are documented in spec §6.4 and deliberately not
 * implemented: they are only reachable from a Gen 3 host, and none is expected to show up.
 */
object SdpGenerator {

    /**
     * The ordered `a=` attribute set for [config] on [profile] (spec §6.4).
     *
     * Exposed separately from [generate] so tests — and a future debugging session against a real
     * host — can inspect one attribute without diffing a whole document.
     */
    fun attributes(config: StreamConfiguration, profile: RtspHostProfile): List<SdpAttribute> {
        val attributes = ArrayList<SdpAttribute>(40)

        fun add(name: String, value: Int) {
            attributes.add(SdpAttribute(name, value.toString()))
        }

        // ---- Video geometry and rate ---------------------------------------------------------
        add("x-nv-video[0].clientViewportWd", config.width)
        add("x-nv-video[0].clientViewportHt", config.height)
        add("x-nv-video[0].maxFPS", config.fps)
        add("x-nv-video[0].packetSize", config.packetSize)
        add("x-nv-video[0].rateControlMode", RtspConstants.RATE_CONTROL_MODE)
        add("x-nv-video[0].timeoutLengthMs", RtspConstants.VIDEO_TIMEOUT_LENGTH_MS)
        add(
            "x-nv-video[0].framesWithInvalidRefThreshold",
            RtspConstants.FRAMES_WITH_INVALID_REF_THRESHOLD,
        )
        add("x-nv-video[0].videoEncoderSlicesPerFrame", config.videoEncoderSlicesPerFrame)
        if (profile.usesModernAttributes) {
            add("x-nv-video[0].clientRefreshRateX100", config.clientRefreshRateX100)
        }

        // ---- Bitrate -------------------------------------------------------------------------
        add("x-nv-video[0].initialBitrateKbps", config.bitrateKbps)
        add("x-nv-video[0].initialPeakBitrateKbps", config.bitrateKbps)
        if (profile.usesModernAttributes) {
            add("x-nv-vqos[0].bw.minimumBitrateKbps", config.bitrateKbps)
            add("x-nv-vqos[0].bw.maximumBitrateKbps", config.bitrateKbps)
        } else {
            add("x-nv-vqos[0].bw.minimumBitrate", config.bitrateKbps)
            add("x-nv-vqos[0].bw.maximumBitrate", config.bitrateKbps)
            add("x-nv-video[0].averageBitrate", RtspConstants.LEGACY_AVERAGE_BITRATE_SELECTOR)
            add("x-nv-video[0].peakBitrate", RtspConstants.LEGACY_PEAK_BITRATE_SELECTOR)
        }
        // Sent to every host, not only Sunshine: spec §6.4 lists it in the bitrate table rather
        // than in the Sunshine-only table, and a host that does not know the name ignores it.
        add("x-ml-video.configuredBitrateKbps", config.bitrateKbps)

        // ---- FEC and QoS ---------------------------------------------------------------------
        add("x-nv-vqos[0].fec.enable", RtspConstants.FEC_ENABLE)
        add("x-nv-vqos[0].fec.repairPercent", config.fecRepairPercent)
        add("x-nv-vqos[0].fec.minRequiredFecPackets", RtspConstants.FEC_MIN_REQUIRED_PACKETS)
        add("x-nv-vqos[0].bllFec.enable", RtspConstants.BLL_FEC_ENABLE)
        add(
            "x-nv-vqos[0].videoQualityScoreUpdateTime",
            RtspConstants.VIDEO_QUALITY_SCORE_UPDATE_TIME,
        )
        add("x-nv-vqos[0].qosTrafficType", config.videoQosTrafficType)
        add("x-nv-aqos.qosTrafficType", config.audioQosTrafficType)
        add("x-nv-vqos[0].drc.enable", RtspConstants.DRC_ENABLE)
        add("x-nv-vqos[0].drc.tableType", RtspConstants.DRC_TABLE_TYPE)
        add("x-nv-general.enableRecoveryMode", RtspConstants.ENABLE_RECOVERY_MODE)
        add("x-nv-general.useReliableUdp", profile.useReliableUdp)
        add("x-nv-ri.useControlChannel", RtspConstants.USE_CONTROL_CHANNEL)

        // ---- Codec selection -----------------------------------------------------------------
        add("x-nv-vqos[0].bitStreamFormat", config.codec.bitStreamFormat)
        add("x-nv-clientSupportHevc", config.codec.clientSupportHevc)
        add("x-nv-video[0].dynamicRangeMode", if (config.hdr) 1 else 0)
        add("x-nv-video[0].maxNumReferenceFrames", RtspConstants.MAX_NUM_REFERENCE_FRAMES)
        add("x-nv-video[0].encoderFeatureSetting", RtspConstants.ENCODER_FEATURE_SETTING)
        if (profile.usesModernAttributes) {
            add("x-nv-video[0].encoderCscMode", config.encoderCscMode)
        }

        // ---- Audio ---------------------------------------------------------------------------
        add("x-nv-audio.surround.numChannels", config.audioLayout.channelCount)
        // Decimal, explicitly, even though the spec writes the values as 0x3 / 0x3F / 0x63F.
        add("x-nv-audio.surround.channelMask", config.audioLayout.channelMask)
        add("x-nv-audio.surround.enable", if (config.surroundEnabled) 1 else 0)
        add(
            "x-nv-audio.surround.AudioQuality",
            if (UnverifiedRtspConstants.REQUEST_HIGH_QUALITY_SURROUND) 1 else 0,
        )
        if (profile.usesModernAttributes) {
            add("x-nv-aqos.packetDuration", config.audioPacketDurationMs)
        }

        // ---- Sunshine-only extensions --------------------------------------------------------
        if (profile.isSunshineish) {
            add("x-ml-general.featureFlags", RtspConstants.ML_FEATURE_FLAGS)
            add("x-ss-general.encryptionEnabled", config.encryptionFlags)
            add("x-ss-video[0].chromaSamplingType", config.chromaSamplingType)
            if (config.encryptionFlags != UnverifiedRtspConstants.ENCRYPTION_FLAGS_DEFAULT) {
                ProtocolLog.unverified(
                    RtspConstants.TAG,
                    "ss-encryption-flags",
                    "announcing x-ss-general.encryptionEnabled=${config.encryptionFlags} with " +
                        "inferred bit values (spec 01 §6.5, item 5); v1's tested value is " +
                        "${UnverifiedRtspConstants.ENCRYPTION_FLAGS_DEFAULT}, and enabling video " +
                        "encryption also requires the §7.6 ENC_VIDEO_HEADER handling",
                )
            }
        }

        return attributes
    }

    /**
     * The complete SDP document for ANNOUNCE (spec §6.4).
     *
     * @param host the host address, unbracketed even for IPv6 — it goes in the `o=` line, whose
     *   address type is chosen from whether the literal contains a colon.
     * @param videoPort the video RTP port negotiated by the video SETUP, which the tail references.
     *   This is why ANNOUNCE comes after the SETUPs and not before.
     */
    fun generate(
        config: StreamConfiguration,
        profile: RtspHostProfile,
        host: String,
        videoPort: Int,
    ): String {
        val addressType = if (host.contains(':')) RtspConstants.SDP_ADDRESS_TYPE_IPV6
        else RtspConstants.SDP_ADDRESS_TYPE_IPV4

        val builder = StringBuilder(2048)
        builder.append(RtspConstants.SDP_VERSION_LINE).append(RtspConstants.CRLF)
        builder.append("o=").append(RtspConstants.SDP_ORIGIN_USER).append(' ')
            .append(RtspConstants.SDP_ORIGIN_SESSION_ID).append(' ')
            .append(RtspConstants.CLIENT_VERSION).append(' ')
            .append(RtspConstants.SDP_NETWORK_TYPE).append(' ')
            .append(addressType).append(' ')
            .append(host).append(RtspConstants.CRLF)
        builder.append("s=").append(RtspConstants.SDP_SESSION_NAME).append(RtspConstants.CRLF)

        for (attribute in attributes(config, profile)) {
            builder.append("a=").append(attribute.name).append(':')
                .append(attribute.value.orEmpty()).append(RtspConstants.CRLF)
        }

        ProtocolLog.unverified(
            RtspConstants.TAG,
            "announce-sdp-tail",
            "emitting the minimal ANNOUNCE SDP tail (t=0 0 / m=video <port>) from spec 01 §6.4, " +
                "item 13; if ANNOUNCE is rejected by a real host this is the first thing to adjust",
        )
        builder.append(UnverifiedRtspConstants.SDP_TAIL_TIMING_LINE).append(RtspConstants.CRLF)
        builder.append(UnverifiedRtspConstants.SDP_TAIL_MEDIA_PREFIX).append(videoPort)
            .append(UnverifiedRtspConstants.SDP_TAIL_MEDIA_SUFFIX).append(RtspConstants.CRLF)

        return builder.toString()
    }
}
