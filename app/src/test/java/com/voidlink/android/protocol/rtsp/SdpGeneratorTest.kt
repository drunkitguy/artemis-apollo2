package com.voidlink.android.protocol.rtsp

import com.voidlink.android.protocol.http.AppVersion
import com.voidlink.android.protocol.http.AudioChannelLayout
import com.voidlink.android.protocol.http.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ANNOUNCE SDP of `docs/01-PROTOCOL.md` §6.4 byte for byte.
 *
 * This is the highest-value thing that can be verified about the RTSP layer without a host: every
 * decision about the session — geometry, bitrate policy, FEC, QoS, codec, colour, audio layout —
 * is a line in this document, sent once, before PLAY, and never revisited. A wrong or missing line
 * is not a crash; it is a session that comes up looking fine and behaves subtly wrong.
 */
class SdpGeneratorTest {

    // ---- Golden documents ---------------------------------------------------------------------

    @Test
    fun `1080p60 H264 stereo on a Sunshine host matches the golden byte for byte`() {
        assertEquals(
            SdpGoldens.SDP_1080P60_H264_STEREO_SUNSHINE,
            SdpGenerator.generate(
                SdpGoldens.config1080p60H264Stereo(),
                SdpGoldens.sunshineGen7(),
                SdpGoldens.HOST,
                SdpGoldens.VIDEO_PORT,
            ),
        )
    }

    @Test
    fun `1440p120 HEVC matches the golden byte for byte`() {
        assertEquals(
            SdpGoldens.SDP_1440P120_HEVC_SUNSHINE,
            SdpGenerator.generate(
                SdpGoldens.config1440p120Hevc(),
                SdpGoldens.sunshineGen7(),
                SdpGoldens.HOST,
                SdpGoldens.VIDEO_PORT,
            ),
        )
    }

    @Test
    fun `4K60 HDR HEVC with 5 point 1 surround matches the golden byte for byte`() {
        assertEquals(
            SdpGoldens.SDP_4K60_HDR_HEVC_SURROUND51_SUNSHINE,
            SdpGenerator.generate(
                SdpGoldens.config4K60HdrSurround(),
                SdpGoldens.sunshineGen7(),
                SdpGoldens.HOST,
                SdpGoldens.VIDEO_PORT,
            ),
        )
    }

    @Test
    fun `1080p60 over WAN with 7 point 1 surround matches the golden byte for byte`() {
        assertEquals(
            SdpGoldens.SDP_1080P60_WAN_SURROUND71_SUNSHINE,
            SdpGenerator.generate(
                SdpGoldens.config1080p60WanSurround71(),
                SdpGoldens.sunshineGen7(),
                SdpGoldens.HOST,
                SdpGoldens.VIDEO_PORT,
            ),
        )
    }

    @Test
    fun `a Gen 5 GFE host gets the legacy attribute spellings and none of the extensions`() {
        assertEquals(
            SdpGoldens.SDP_1080P60_H264_STEREO_GEN5_GFE,
            SdpGenerator.generate(
                SdpGoldens.config1080p60H264Stereo(),
                SdpGoldens.nvidiaGen5(),
                SdpGoldens.HOST,
                SdpGoldens.VIDEO_PORT,
            ),
        )
    }

    // ---- The differences the goldens are there to protect --------------------------------------

    @Test
    fun `HDR and SDR differ only in dynamic range and colour space`() {
        val sdr = attributeMap(SdpGoldens.config4K60HdrSurround().copy(hdr = false))
        val hdr = attributeMap(SdpGoldens.config4K60HdrSurround())

        assertEquals("0", sdr["x-nv-video[0].dynamicRangeMode"])
        assertEquals("1", hdr["x-nv-video[0].dynamicRangeMode"])
        // (colorSpace shl 1) or colorRange: BT.709 limited = 2, BT.2020 limited = 4 (spec §6.4).
        assertEquals("2", sdr["x-nv-video[0].encoderCscMode"])
        assertEquals("4", hdr["x-nv-video[0].encoderCscMode"])
    }

    @Test
    fun `stereo and surround differ only in the audio block`() {
        val stereo = attributeMap(SdpGoldens.config1080p60H264Stereo())
        val surround = attributeMap(
            SdpGoldens.config1080p60H264Stereo().copy(audioLayout = AudioChannelLayout.SURROUND_5_1),
        )

        assertEquals("2", stereo["x-nv-audio.surround.numChannels"])
        assertEquals("3", stereo["x-nv-audio.surround.channelMask"])
        assertEquals("0", stereo["x-nv-audio.surround.enable"])

        assertEquals("6", surround["x-nv-audio.surround.numChannels"])
        // Decimal, not 0x3F — the spec is explicit that this mask goes on the wire as an integer.
        assertEquals("63", surround["x-nv-audio.surround.channelMask"])
        assertEquals("1", surround["x-nv-audio.surround.enable"])
    }

    @Test
    fun `high quality surround is never requested while the SDP key is unverified`() {
        val surround = attributeMap(
            SdpGoldens.config1080p60H264Stereo().copy(audioLayout = AudioChannelLayout.SURROUND_7_1),
        )
        assertFalse(UnverifiedRtspConstants.REQUEST_HIGH_QUALITY_SURROUND)
        assertEquals("0", surround["x-nv-audio.surround.AudioQuality"])
    }

    @Test
    fun `bitrate is pinned as both floor and ceiling so the host cannot adapt`() {
        val attributes = attributeMap(SdpGoldens.config1440p120Hevc())
        assertEquals("60000", attributes["x-nv-vqos[0].bw.minimumBitrateKbps"])
        assertEquals("60000", attributes["x-nv-vqos[0].bw.maximumBitrateKbps"])
        assertEquals("60000", attributes["x-nv-video[0].initialBitrateKbps"])
        assertEquals("60000", attributes["x-nv-video[0].initialPeakBitrateKbps"])
        assertEquals("60000", attributes["x-ml-video.configuredBitrateKbps"])
    }

    @Test
    fun `configuredBitrateKbps is present in every document and carries the raw user value`() {
        // The single highest-value assertion in this file. Apollo — the host our user actually runs
        // — falls back to maximumBitrateKbps when this attribute is absent, and then applies the
        // FEC/audio/overhead deduction to a figure that has already had it applied, encoding at
        // roughly 0.64x what was asked for. Nothing about that failure is visible: the stream comes
        // up, plays, and merely looks worse than it should. Dropping this attribute in a future
        // edit must break a test, because it will not break anything a human would notice.
        // (docs/05-DYNAMIC-BITRATE.md §1.3.)
        val configurations = listOf(
            SdpGoldens.config1080p60H264Stereo(),
            SdpGoldens.config1440p120Hevc(),
            SdpGoldens.config4K60HdrSurround(),
            SdpGoldens.config1080p60WanSurround71(),
        )
        val profiles = listOf(SdpGoldens.sunshineGen7(), SdpGoldens.nvidiaGen5())

        for (configuration in configurations) {
            for (profile in profiles) {
                val attribute = SdpGenerator.attributes(configuration, profile)
                    .firstOrNull { it.name == "x-ml-video.configuredBitrateKbps" }
                assertNotNull(
                    "x-ml-video.configuredBitrateKbps missing for $configuration on $profile",
                    attribute,
                )
                assertEquals(configuration.configuredBitrateKbps.toString(), attribute!!.value)
            }
        }
    }

    @Test
    fun `the raw and the negotiated bitrate are never conflated`() {
        // They are the same number today because v1 applies no client-side adjustment. If one is
        // ever scaled, the other must not follow it — which is the whole reason they are separate
        // fields rather than one.
        val adjusted = SdpGoldens.config1080p60H264Stereo()
            .copy(bitrateKbps = 16_000, configuredBitrateKbps = 20_000)
        val attributes = attributeMap(adjusted)

        assertEquals("20000", attributes["x-ml-video.configuredBitrateKbps"])
        assertEquals("16000", attributes["x-nv-vqos[0].bw.minimumBitrateKbps"])
        assertEquals("16000", attributes["x-nv-vqos[0].bw.maximumBitrateKbps"])
        assertEquals("16000", attributes["x-nv-video[0].initialBitrateKbps"])
        assertEquals("16000", attributes["x-nv-video[0].initialPeakBitrateKbps"])
    }

    @Test
    fun `no client side deduction is applied to the bitrate the user chose`() {
        // The number is a total wire budget covering FEC, audio and headers, and the host performs
        // that arithmetic itself. Pre-deducting here would have it deducted twice.
        val configuration = SdpGoldens.config1080p60H264Stereo()
        assertEquals(configuration.bitrateKbps, configuration.configuredBitrateKbps)

        val attributes = attributeMap(configuration)
        assertEquals("20000", attributes["x-ml-video.configuredBitrateKbps"])
        assertEquals("20000", attributes["x-nv-vqos[0].bw.maximumBitrateKbps"])
    }

    @Test
    fun `dynamic resolution change stays off because MediaCodec is not reconfigured mid-stream`() {
        assertEquals("0", attributeMap(SdpGoldens.config1080p60H264Stereo())["x-nv-vqos[0].drc.enable"])
    }

    @Test
    fun `reference frame invalidation is not claimed`() {
        val attributes = attributeMap(SdpGoldens.config1440p120Hevc())
        assertEquals("0", attributes["x-nv-video[0].maxNumReferenceFrames"])
        assertEquals("0", attributes["x-nv-video[0].encoderFeatureSetting"])
    }

    @Test
    fun `only HEVC claims client HEVC support`() {
        assertEquals("0", codecAttributes(VideoCodec.H264)["x-nv-clientSupportHevc"])
        assertEquals("1", codecAttributes(VideoCodec.HEVC)["x-nv-clientSupportHevc"])
        assertEquals("0", codecAttributes(VideoCodec.AV1)["x-nv-clientSupportHevc"])

        assertEquals("0", codecAttributes(VideoCodec.H264)["x-nv-vqos[0].bitStreamFormat"])
        assertEquals("1", codecAttributes(VideoCodec.HEVC)["x-nv-vqos[0].bitStreamFormat"])
        assertEquals("2", codecAttributes(VideoCodec.AV1)["x-nv-vqos[0].bitStreamFormat"])
    }

    @Test
    fun `the Sunshine only extensions are absent from a GFE document`() {
        val gfe = SdpGenerator.attributes(
            SdpGoldens.config1080p60H264Stereo(),
            SdpGoldens.nvidiaGen5(),
        ).associate { it.name to it.value }

        assertNull(gfe["x-ml-general.featureFlags"])
        assertNull(gfe["x-ss-general.encryptionEnabled"])
        assertNull(gfe["x-ss-video[0].chromaSamplingType"])
        // …but the x-ml bitrate hint is in the spec's bitrate table, not its Sunshine-only table.
        assertEquals("20000", gfe["x-ml-video.configuredBitrateKbps"])
    }

    @Test
    fun `a Gen 7 host that predates 7 point 1 point 431 still gets the modern attribute set`() {
        // usesModernAttributes keys off the generation; only the control stream id keys off 7.1.431.
        val profile = RtspHostProfile(AppVersion(listOf(7, 0, 0, 0)), ServerKind.SUNSHINE_FAMILY)
        val attributes = SdpGenerator.attributes(SdpGoldens.config1080p60H264Stereo(), profile)
            .associate { it.name to it.value }

        assertEquals("6000", attributes["x-nv-video[0].clientRefreshRateX100"])
        assertEquals("13", attributes["x-nv-general.useReliableUdp"])
        assertFalse(profile.usesModernControlStreamId)
        assertEquals(RtspConstants.STREAM_ID_CONTROL_LEGACY, profile.controlStreamId)
    }

    @Test
    fun `YUV 444 is announced only when it was asked for`() {
        val standard = attributeMap(SdpGoldens.config1440p120Hevc())
        val wide = attributeMap(SdpGoldens.config1440p120Hevc().copy(yuv444 = true))
        assertEquals("0", standard["x-ss-video[0].chromaSamplingType"])
        assertEquals("1", wide["x-ss-video[0].chromaSamplingType"])
    }

    @Test
    fun `the refresh rate can differ from the frame rate`() {
        val config = SdpGoldens.config1080p60H264Stereo().copy(displayRefreshRateHz = 90)
        assertEquals("9000", attributeMap(config)["x-nv-video[0].clientRefreshRateX100"])
        assertEquals("60", attributeMap(config)["x-nv-video[0].maxFPS"])
    }

    // ---- Packet size (spec §5, §6.4) -----------------------------------------------------------

    @Test
    fun `packet size follows the network profile and the encryption adjustment`() {
        val lan = SdpGoldens.config1080p60H264Stereo()
        assertEquals(1392, lan.packetSize)
        assertEquals(1024, lan.copy(network = NetworkProfile.WAN).packetSize)

        val encrypted = lan.copy(encryptionFlags = UnverifiedRtspConstants.SS_ENC_VIDEO)
        assertEquals(1392 - 32, encrypted.packetSize)
        assertEquals(0, encrypted.packetSize % RtspConstants.PACKET_SIZE_ENCRYPTION_ALIGNMENT)

        val encryptedWan = encrypted.copy(network = NetworkProfile.WAN)
        assertEquals(1024 - 32, encryptedWan.packetSize)
        assertEquals(0, encryptedWan.packetSize % RtspConstants.PACKET_SIZE_ENCRYPTION_ALIGNMENT)

        // An override that is not a multiple of 16 must still leave an aligned result.
        val awkward = lan.copy(
            packetSizeOverride = 1100,
            encryptionFlags = UnverifiedRtspConstants.SS_ENC_VIDEO,
        )
        assertEquals(0, awkward.packetSize % RtspConstants.PACKET_SIZE_ENCRYPTION_ALIGNMENT)
        assertTrue(awkward.packetSize <= 1100 - 32)
    }

    @Test
    fun `v1 announces no encryption at all`() {
        assertEquals(0, UnverifiedRtspConstants.ENCRYPTION_FLAGS_DEFAULT)
        assertEquals("0", attributeMap(SdpGoldens.config1080p60H264Stereo())["x-ss-general.encryptionEnabled"])
    }

    // ---- Document framing -----------------------------------------------------------------------

    @Test
    fun `an IPv6 host produces an IP6 origin line and a bracket free address`() {
        val sdp = SdpGenerator.generate(
            SdpGoldens.config1080p60H264Stereo(),
            SdpGoldens.sunshineGen7(),
            "fe80::1c2e:aaff:fe12:3456",
            47998,
        )
        assertTrue(sdp.startsWith("v=0\r\no=android 0 14 IN IP6 fe80::1c2e:aaff:fe12:3456\r\n"))
    }

    @Test
    fun `the tail references the negotiated video port`() {
        val sdp = SdpGenerator.generate(
            SdpGoldens.config1080p60H264Stereo(),
            SdpGoldens.sunshineGen7(),
            SdpGoldens.HOST,
            50123,
        )
        assertTrue(sdp.endsWith("t=0 0\r\nm=video 50123  \r\n"))
    }

    @Test
    fun `every line ends with CRLF and none is left empty`() {
        val sdp = SdpGenerator.generate(
            SdpGoldens.config4K60HdrSurround(),
            SdpGoldens.sunshineGen7(),
            SdpGoldens.HOST,
            SdpGoldens.VIDEO_PORT,
        )
        assertFalse(sdp.contains("\n\n"))
        for (line in sdp.split("\r\n").dropLast(1)) {
            assertFalse("bare LF in \"$line\"", line.contains('\n'))
            assertTrue("empty SDP line", line.isNotEmpty())
        }
        assertTrue(sdp.endsWith("\r\n"))
    }

    @Test
    fun `the generated document round trips through the SDP parser`() {
        val sdp = SdpGenerator.generate(
            SdpGoldens.config4K60HdrSurround(),
            SdpGoldens.sunshineGen7(),
            SdpGoldens.HOST,
            SdpGoldens.VIDEO_PORT,
        )
        val parsed = SessionDescription.parse(sdp)
        assertEquals("3840", parsed.attribute("x-nv-video[0].clientViewportWd"))
        assertEquals("6", parsed.attribute("x-nv-audio.surround.numChannels"))
        assertEquals(
            SdpGenerator.attributes(SdpGoldens.config4K60HdrSurround(), SdpGoldens.sunshineGen7()).size,
            parsed.attributes.size,
        )
    }

    @Test
    fun `no attribute name or value is ever blank`() {
        val attributes = SdpGenerator.attributes(
            SdpGoldens.config4K60HdrSurround(),
            SdpGoldens.sunshineGen7(),
        )
        for (attribute in attributes) {
            assertTrue("blank attribute name", attribute.name.isNotBlank())
            assertTrue("blank value for ${attribute.name}", !attribute.value.isNullOrBlank())
        }
        assertEquals(attributes.size, attributes.map { it.name }.toSet().size)
    }

    private fun attributeMap(config: StreamConfiguration): Map<String, String?> =
        SdpGenerator.attributes(config, SdpGoldens.sunshineGen7()).associate { it.name to it.value }

    private fun codecAttributes(codec: VideoCodec): Map<String, String?> =
        attributeMap(SdpGoldens.config1080p60H264Stereo().copy(codec = codec))
}
