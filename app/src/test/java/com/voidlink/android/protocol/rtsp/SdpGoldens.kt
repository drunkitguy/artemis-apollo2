package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.http.AppVersion
import com.voidlink.android.protocol.http.AudioChannelLayout
import com.voidlink.android.protocol.http.ServerKind

/**
 * Byte-exact expected ANNOUNCE payloads for a representative set of configurations
 * (`docs/01-PROTOCOL.md` §6.4).
 *
 * These are written out line by line rather than generated, which is the entire point: the
 * generator builds this document with loops and conditionals, and a golden that shares those loops
 * proves nothing. Every line here was read off the attribute tables of spec §6.4 by hand, so a diff
 * against one of them is a diff against the spec.
 *
 * Without a host, the exact bytes of this document are the single highest-value thing about the
 * RTSP layer that can be verified at all.
 */
object SdpGoldens {

    /** The host address used by every golden; it appears in the SDP `o=` line. */
    const val HOST: String = "192.168.1.50"

    /** The video port every golden's tail references — the spec's own default (§0.4). */
    const val VIDEO_PORT: Int = 47998

    /** A modern Sunshine/Apollo host: Gen 7, `appversion >= 7.1.431`. */
    fun sunshineGen7(): RtspHostProfile =
        RtspHostProfile(AppVersion(listOf(7, 1, 431, 0)), ServerKind.SUNSHINE_FAMILY)

    /** An old NVIDIA GameStream host: Gen 5, below `7.1.431`, not Sunshine. */
    fun nvidiaGen5(): RtspHostProfile =
        RtspHostProfile(AppVersion(listOf(5, 0, 0, 0)), ServerKind.NVIDIA_GFE)

    // ---- Configurations ------------------------------------------------------------------------

    /** 1080p60, H.264, stereo, LAN — the default a first-time user gets. */
    fun config1080p60H264Stereo(): StreamConfiguration = StreamConfiguration(
        width = 1920,
        height = 1080,
        fps = 60,
        bitrateKbps = 20_000,
        codec = VideoCodec.H264,
        audioLayout = AudioChannelLayout.STEREO,
        network = NetworkProfile.LAN,
    )

    /** 1440p120, HEVC, stereo, LAN — a high-refresh handheld. */
    fun config1440p120Hevc(): StreamConfiguration = StreamConfiguration(
        width = 2560,
        height = 1440,
        fps = 120,
        bitrateKbps = 60_000,
        codec = VideoCodec.HEVC,
        audioLayout = AudioChannelLayout.STEREO,
        network = NetworkProfile.LAN,
    )

    /** 4K60 HDR, HEVC, 5.1 surround, LAN — the case that exercises HDR and surround together. */
    fun config4K60HdrSurround(): StreamConfiguration = StreamConfiguration(
        width = 3840,
        height = 2160,
        fps = 60,
        bitrateKbps = 80_000,
        codec = VideoCodec.HEVC,
        hdr = true,
        audioLayout = AudioChannelLayout.SURROUND_5_1,
        network = NetworkProfile.LAN,
    )

    /** 1080p60, H.264, 7.1 surround, WAN — the whole WAN branch in one document. */
    fun config1080p60WanSurround71(): StreamConfiguration = StreamConfiguration(
        width = 1920,
        height = 1080,
        fps = 60,
        bitrateKbps = 10_000,
        codec = VideoCodec.H264,
        audioLayout = AudioChannelLayout.SURROUND_7_1,
        network = NetworkProfile.WAN,
    )

    // ---- Expected documents --------------------------------------------------------------------

    /** [config1080p60H264Stereo] on [sunshineGen7]. */
    val SDP_1080P60_H264_STEREO_SUNSHINE: String = sdp(
        "v=0",
        "o=android 0 14 IN IP4 192.168.1.50",
        "s=NVIDIA Streaming Client",
        "a=x-nv-video[0].clientViewportWd:1920",
        "a=x-nv-video[0].clientViewportHt:1080",
        "a=x-nv-video[0].maxFPS:60",
        "a=x-nv-video[0].packetSize:1392",
        "a=x-nv-video[0].rateControlMode:4",
        "a=x-nv-video[0].timeoutLengthMs:7000",
        "a=x-nv-video[0].framesWithInvalidRefThreshold:0",
        "a=x-nv-video[0].videoEncoderSlicesPerFrame:1",
        "a=x-nv-video[0].clientRefreshRateX100:6000",
        "a=x-nv-video[0].initialBitrateKbps:20000",
        "a=x-nv-video[0].initialPeakBitrateKbps:20000",
        "a=x-nv-vqos[0].bw.minimumBitrateKbps:20000",
        "a=x-nv-vqos[0].bw.maximumBitrateKbps:20000",
        "a=x-ml-video.configuredBitrateKbps:20000",
        "a=x-nv-vqos[0].fec.enable:1",
        "a=x-nv-vqos[0].fec.repairPercent:20",
        "a=x-nv-vqos[0].fec.minRequiredFecPackets:2",
        "a=x-nv-vqos[0].bllFec.enable:0",
        "a=x-nv-vqos[0].videoQualityScoreUpdateTime:5000",
        "a=x-nv-vqos[0].qosTrafficType:5",
        "a=x-nv-aqos.qosTrafficType:4",
        "a=x-nv-vqos[0].drc.enable:0",
        "a=x-nv-vqos[0].drc.tableType:2",
        "a=x-nv-general.enableRecoveryMode:0",
        "a=x-nv-general.useReliableUdp:13",
        "a=x-nv-ri.useControlChannel:1",
        "a=x-nv-vqos[0].bitStreamFormat:0",
        "a=x-nv-clientSupportHevc:0",
        "a=x-nv-video[0].dynamicRangeMode:0",
        "a=x-nv-video[0].maxNumReferenceFrames:0",
        "a=x-nv-video[0].encoderFeatureSetting:0",
        "a=x-nv-video[0].encoderCscMode:2",
        "a=x-nv-audio.surround.numChannels:2",
        "a=x-nv-audio.surround.channelMask:3",
        "a=x-nv-audio.surround.enable:0",
        "a=x-nv-audio.surround.AudioQuality:0",
        "a=x-nv-aqos.packetDuration:5",
        "a=x-ml-general.featureFlags:3",
        "a=x-ss-general.encryptionEnabled:0",
        "a=x-ss-video[0].chromaSamplingType:0",
        "t=0 0",
        "m=video 47998  ",
    )

    /** [config1440p120Hevc] on [sunshineGen7]. */
    val SDP_1440P120_HEVC_SUNSHINE: String = sdp(
        "v=0",
        "o=android 0 14 IN IP4 192.168.1.50",
        "s=NVIDIA Streaming Client",
        "a=x-nv-video[0].clientViewportWd:2560",
        "a=x-nv-video[0].clientViewportHt:1440",
        "a=x-nv-video[0].maxFPS:120",
        "a=x-nv-video[0].packetSize:1392",
        "a=x-nv-video[0].rateControlMode:4",
        "a=x-nv-video[0].timeoutLengthMs:7000",
        "a=x-nv-video[0].framesWithInvalidRefThreshold:0",
        "a=x-nv-video[0].videoEncoderSlicesPerFrame:1",
        "a=x-nv-video[0].clientRefreshRateX100:12000",
        "a=x-nv-video[0].initialBitrateKbps:60000",
        "a=x-nv-video[0].initialPeakBitrateKbps:60000",
        "a=x-nv-vqos[0].bw.minimumBitrateKbps:60000",
        "a=x-nv-vqos[0].bw.maximumBitrateKbps:60000",
        "a=x-ml-video.configuredBitrateKbps:60000",
        "a=x-nv-vqos[0].fec.enable:1",
        "a=x-nv-vqos[0].fec.repairPercent:20",
        "a=x-nv-vqos[0].fec.minRequiredFecPackets:2",
        "a=x-nv-vqos[0].bllFec.enable:0",
        "a=x-nv-vqos[0].videoQualityScoreUpdateTime:5000",
        "a=x-nv-vqos[0].qosTrafficType:5",
        "a=x-nv-aqos.qosTrafficType:4",
        "a=x-nv-vqos[0].drc.enable:0",
        "a=x-nv-vqos[0].drc.tableType:2",
        "a=x-nv-general.enableRecoveryMode:0",
        "a=x-nv-general.useReliableUdp:13",
        "a=x-nv-ri.useControlChannel:1",
        "a=x-nv-vqos[0].bitStreamFormat:1",
        "a=x-nv-clientSupportHevc:1",
        "a=x-nv-video[0].dynamicRangeMode:0",
        "a=x-nv-video[0].maxNumReferenceFrames:0",
        "a=x-nv-video[0].encoderFeatureSetting:0",
        "a=x-nv-video[0].encoderCscMode:2",
        "a=x-nv-audio.surround.numChannels:2",
        "a=x-nv-audio.surround.channelMask:3",
        "a=x-nv-audio.surround.enable:0",
        "a=x-nv-audio.surround.AudioQuality:0",
        "a=x-nv-aqos.packetDuration:5",
        "a=x-ml-general.featureFlags:3",
        "a=x-ss-general.encryptionEnabled:0",
        "a=x-ss-video[0].chromaSamplingType:0",
        "t=0 0",
        "m=video 47998  ",
    )

    /** [config4K60HdrSurround] on [sunshineGen7]. */
    val SDP_4K60_HDR_HEVC_SURROUND51_SUNSHINE: String = sdp(
        "v=0",
        "o=android 0 14 IN IP4 192.168.1.50",
        "s=NVIDIA Streaming Client",
        "a=x-nv-video[0].clientViewportWd:3840",
        "a=x-nv-video[0].clientViewportHt:2160",
        "a=x-nv-video[0].maxFPS:60",
        "a=x-nv-video[0].packetSize:1392",
        "a=x-nv-video[0].rateControlMode:4",
        "a=x-nv-video[0].timeoutLengthMs:7000",
        "a=x-nv-video[0].framesWithInvalidRefThreshold:0",
        "a=x-nv-video[0].videoEncoderSlicesPerFrame:1",
        "a=x-nv-video[0].clientRefreshRateX100:6000",
        "a=x-nv-video[0].initialBitrateKbps:80000",
        "a=x-nv-video[0].initialPeakBitrateKbps:80000",
        "a=x-nv-vqos[0].bw.minimumBitrateKbps:80000",
        "a=x-nv-vqos[0].bw.maximumBitrateKbps:80000",
        "a=x-ml-video.configuredBitrateKbps:80000",
        "a=x-nv-vqos[0].fec.enable:1",
        "a=x-nv-vqos[0].fec.repairPercent:20",
        "a=x-nv-vqos[0].fec.minRequiredFecPackets:2",
        "a=x-nv-vqos[0].bllFec.enable:0",
        "a=x-nv-vqos[0].videoQualityScoreUpdateTime:5000",
        "a=x-nv-vqos[0].qosTrafficType:5",
        "a=x-nv-aqos.qosTrafficType:4",
        "a=x-nv-vqos[0].drc.enable:0",
        "a=x-nv-vqos[0].drc.tableType:2",
        "a=x-nv-general.enableRecoveryMode:0",
        "a=x-nv-general.useReliableUdp:13",
        "a=x-nv-ri.useControlChannel:1",
        "a=x-nv-vqos[0].bitStreamFormat:1",
        "a=x-nv-clientSupportHevc:1",
        "a=x-nv-video[0].dynamicRangeMode:1",
        "a=x-nv-video[0].maxNumReferenceFrames:0",
        "a=x-nv-video[0].encoderFeatureSetting:0",
        "a=x-nv-video[0].encoderCscMode:4",
        "a=x-nv-audio.surround.numChannels:6",
        "a=x-nv-audio.surround.channelMask:63",
        "a=x-nv-audio.surround.enable:1",
        "a=x-nv-audio.surround.AudioQuality:0",
        "a=x-nv-aqos.packetDuration:5",
        "a=x-ml-general.featureFlags:3",
        "a=x-ss-general.encryptionEnabled:0",
        "a=x-ss-video[0].chromaSamplingType:0",
        "t=0 0",
        "m=video 47998  ",
    )

    /** [config1080p60WanSurround71] on [sunshineGen7]. */
    val SDP_1080P60_WAN_SURROUND71_SUNSHINE: String = sdp(
        "v=0",
        "o=android 0 14 IN IP4 192.168.1.50",
        "s=NVIDIA Streaming Client",
        "a=x-nv-video[0].clientViewportWd:1920",
        "a=x-nv-video[0].clientViewportHt:1080",
        "a=x-nv-video[0].maxFPS:60",
        "a=x-nv-video[0].packetSize:1024",
        "a=x-nv-video[0].rateControlMode:4",
        "a=x-nv-video[0].timeoutLengthMs:7000",
        "a=x-nv-video[0].framesWithInvalidRefThreshold:0",
        "a=x-nv-video[0].videoEncoderSlicesPerFrame:1",
        "a=x-nv-video[0].clientRefreshRateX100:6000",
        "a=x-nv-video[0].initialBitrateKbps:10000",
        "a=x-nv-video[0].initialPeakBitrateKbps:10000",
        "a=x-nv-vqos[0].bw.minimumBitrateKbps:10000",
        "a=x-nv-vqos[0].bw.maximumBitrateKbps:10000",
        "a=x-ml-video.configuredBitrateKbps:10000",
        "a=x-nv-vqos[0].fec.enable:1",
        "a=x-nv-vqos[0].fec.repairPercent:5",
        "a=x-nv-vqos[0].fec.minRequiredFecPackets:2",
        "a=x-nv-vqos[0].bllFec.enable:0",
        "a=x-nv-vqos[0].videoQualityScoreUpdateTime:5000",
        "a=x-nv-vqos[0].qosTrafficType:0",
        "a=x-nv-aqos.qosTrafficType:0",
        "a=x-nv-vqos[0].drc.enable:0",
        "a=x-nv-vqos[0].drc.tableType:2",
        "a=x-nv-general.enableRecoveryMode:0",
        "a=x-nv-general.useReliableUdp:13",
        "a=x-nv-ri.useControlChannel:1",
        "a=x-nv-vqos[0].bitStreamFormat:0",
        "a=x-nv-clientSupportHevc:0",
        "a=x-nv-video[0].dynamicRangeMode:0",
        "a=x-nv-video[0].maxNumReferenceFrames:0",
        "a=x-nv-video[0].encoderFeatureSetting:0",
        "a=x-nv-video[0].encoderCscMode:2",
        "a=x-nv-audio.surround.numChannels:8",
        "a=x-nv-audio.surround.channelMask:1599",
        "a=x-nv-audio.surround.enable:1",
        "a=x-nv-audio.surround.AudioQuality:0",
        "a=x-nv-aqos.packetDuration:5",
        "a=x-ml-general.featureFlags:3",
        "a=x-ss-general.encryptionEnabled:0",
        "a=x-ss-video[0].chromaSamplingType:0",
        "t=0 0",
        "m=video 47998  ",
    )

    /**
     * [config1080p60H264Stereo] on [nvidiaGen5].
     *
     * The whole legacy branch in one document: the un-suffixed bitrate names plus the
     * `averageBitrate`/`peakBitrate` selectors, `useReliableUdp=1`, and none of the Gen-7-only or
     * Sunshine-only attributes.
     */
    val SDP_1080P60_H264_STEREO_GEN5_GFE: String = sdp(
        "v=0",
        "o=android 0 14 IN IP4 192.168.1.50",
        "s=NVIDIA Streaming Client",
        "a=x-nv-video[0].clientViewportWd:1920",
        "a=x-nv-video[0].clientViewportHt:1080",
        "a=x-nv-video[0].maxFPS:60",
        "a=x-nv-video[0].packetSize:1392",
        "a=x-nv-video[0].rateControlMode:4",
        "a=x-nv-video[0].timeoutLengthMs:7000",
        "a=x-nv-video[0].framesWithInvalidRefThreshold:0",
        "a=x-nv-video[0].videoEncoderSlicesPerFrame:1",
        "a=x-nv-video[0].initialBitrateKbps:20000",
        "a=x-nv-video[0].initialPeakBitrateKbps:20000",
        "a=x-nv-vqos[0].bw.minimumBitrate:20000",
        "a=x-nv-vqos[0].bw.maximumBitrate:20000",
        "a=x-nv-video[0].averageBitrate:4",
        "a=x-nv-video[0].peakBitrate:4",
        "a=x-ml-video.configuredBitrateKbps:20000",
        "a=x-nv-vqos[0].fec.enable:1",
        "a=x-nv-vqos[0].fec.repairPercent:20",
        "a=x-nv-vqos[0].fec.minRequiredFecPackets:2",
        "a=x-nv-vqos[0].bllFec.enable:0",
        "a=x-nv-vqos[0].videoQualityScoreUpdateTime:5000",
        "a=x-nv-vqos[0].qosTrafficType:5",
        "a=x-nv-aqos.qosTrafficType:4",
        "a=x-nv-vqos[0].drc.enable:0",
        "a=x-nv-vqos[0].drc.tableType:2",
        "a=x-nv-general.enableRecoveryMode:0",
        "a=x-nv-general.useReliableUdp:1",
        "a=x-nv-ri.useControlChannel:1",
        "a=x-nv-vqos[0].bitStreamFormat:0",
        "a=x-nv-clientSupportHevc:0",
        "a=x-nv-video[0].dynamicRangeMode:0",
        "a=x-nv-video[0].maxNumReferenceFrames:0",
        "a=x-nv-video[0].encoderFeatureSetting:0",
        "a=x-nv-audio.surround.numChannels:2",
        "a=x-nv-audio.surround.channelMask:3",
        "a=x-nv-audio.surround.enable:0",
        "a=x-nv-audio.surround.AudioQuality:0",
        "t=0 0",
        "m=video 47998  ",
    )

    /** Joins golden lines with the CRLF terminators the wire format requires. */
    private fun sdp(vararg lines: String): String =
        lines.joinToString(separator = "") { it + "\r\n" }
}
