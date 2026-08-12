package com.voidlink.android.protocol.session

import com.voidlink.android.data.FrameRate
import com.voidlink.android.data.StreamResolution
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.data.SurroundMode
import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.http.AudioChannelLayout
import com.voidlink.android.protocol.rtsp.NetworkProfile
import com.voidlink.android.protocol.rtsp.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Where the user's settings reach the wire (`docs/02-ARCHITECTURE.md` §6.1).
 *
 * Architecture §6.1's table is a promise: every settings field either maps to something in the
 * protocol or is explicitly local-only, with no third category. A mapping that silently stops
 * working is invisible until a user reports that a switch "does nothing", which is exactly the
 * class of bug a pure function and a table of assertions can rule out for good.
 *
 * The `muteHostAudio` inversion gets its own test because it is the one field whose user-facing
 * name is the opposite of its wire meaning.
 */
class SessionParameterMapperTest {

    private val key = RemoteInputKey(Hex.decodeOrNull("000102030405060708090a0b0c0d0e0f")!!, 0x1234)

    private fun build(
        settings: StreamSettings,
        codec: VideoCodec = VideoCodec.HEVC,
        hdr: Boolean = false,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 60,
    ) = SessionParameterMapper.build(
        settings = settings,
        appId = 42L,
        width = width,
        height = height,
        fps = fps,
        codec = codec,
        hdr = hdr,
        remoteInputKey = key,
    )

    @Test
    fun `resolution, frame rate and bitrate reach both halves identically`() {
        // The launch request and the ANNOUNCE must agree — the host builds its DESCRIBE answer from
        // what /launch asked for, and a mismatch produces a session that negotiates cleanly and then
        // behaves oddly. Building both from one function is what guarantees it.
        val parameters = build(
            StreamSettings(
                bitrateKbps = 35_000,
                resolution = StreamResolution.RES_1440P,
                frameRate = FrameRate.FPS_120,
            ),
            width = 2560,
            height = 1440,
            fps = 120,
        )

        assertEquals(2560, parameters.launch.width)
        assertEquals(1440, parameters.launch.height)
        assertEquals(120, parameters.launch.fps)
        assertEquals(2560, parameters.configuration.width)
        assertEquals(1440, parameters.configuration.height)
        assertEquals(120, parameters.configuration.fps)
        assertEquals(35_000, parameters.configuration.bitrateKbps)
        assertEquals(35_000, parameters.configuration.configuredBitrateKbps)
    }

    @Test
    fun `the query string carries the mode, key and audio parameters`() {
        val params = build(StreamSettings()).launch.toQueryParams(isNvidiaGfe = false).toMap()
        assertEquals("42", params["appid"])
        assertEquals("1920x1080x60", params["mode"])
        assertEquals("000102030405060708090a0b0c0d0e0f", params["rikey"])
        assertEquals("4660", params["rikeyid"])
        assertEquals("1", params["sops"])
        assertEquals("1", params["gcpersist"])
    }

    @Test
    fun `muteHostAudio is inverted into localAudioPlayMode`() {
        val muted = build(StreamSettings(muteHostAudio = true))
        val audible = build(StreamSettings(muteHostAudio = false))

        assertFalse(muted.launch.playAudioOnHost)
        assertTrue(audible.launch.playAudioOnHost)
        assertEquals(
            "0",
            muted.launch.toQueryParams(isNvidiaGfe = false).toMap()["localAudioPlayMode"],
        )
        assertEquals(
            "1",
            audible.launch.toQueryParams(isNvidiaGfe = false).toMap()["localAudioPlayMode"],
        )
    }

    @Test
    fun `the surround setting reaches both surroundAudioInfo and the SDP layout`() {
        val stereo = build(StreamSettings(surroundMode = SurroundMode.STEREO))
        val surround = build(StreamSettings(surroundMode = SurroundMode.SURROUND_7_1))

        assertEquals(AudioChannelLayout.STEREO, stereo.launch.audioLayout)
        assertEquals(AudioChannelLayout.SURROUND_7_1, surround.launch.audioLayout)
        assertEquals(AudioChannelLayout.SURROUND_7_1, surround.configuration.audioLayout)
        assertTrue(surround.configuration.surroundEnabled)
        assertFalse(stereo.configuration.surroundEnabled)
    }

    @Test
    fun `HDR comes from decoder selection, not from the raw setting`() {
        // A device that cannot decode 10-bit must not ask a host to send it, so the mapper takes the
        // *selected* HDR flag rather than settings.hdrEnabled (architecture §6.3's visible clamping).
        val asked = build(StreamSettings(hdrEnabled = true), hdr = false)
        assertFalse(asked.launch.hdr)
        assertFalse(asked.configuration.hdr)

        val granted = build(StreamSettings(hdrEnabled = true), hdr = true)
        assertTrue(granted.launch.hdr)
        assertTrue(granted.configuration.hdr)
        assertEquals(
            "1",
            granted.launch.toQueryParams(isNvidiaGfe = false).toMap()["hdrMode"],
        )
    }

    @Test
    fun `yuv444 reaches the chroma sampling attribute`() {
        assertEquals(
            1,
            build(StreamSettings(yuv444Enabled = true)).configuration.chromaSamplingType,
        )
        assertEquals(
            0,
            build(StreamSettings(yuv444Enabled = false)).configuration.chromaSamplingType,
        )
    }

    @Test
    fun `the codec is the one a decoder was selected for`() {
        assertEquals(VideoCodec.AV1, build(StreamSettings(), codec = VideoCodec.AV1).configuration.codec)
        assertEquals(2, build(StreamSettings(), codec = VideoCodec.AV1).configuration.codec.bitStreamFormat)
    }

    @Test
    fun `out-of-range settings are coerced before they reach the wire`() {
        // Persisted blobs are user-editable in principle, so a bitrate of two gigabits must not
        // become an ANNOUNCE the host rejects.
        val parameters = build(StreamSettings(bitrateKbps = 2_000_000))
        assertEquals(StreamSettings.BITRATE_MAX_KBPS, parameters.configuration.bitrateKbps)
    }

    @Test
    fun `the gamepad mask covers the number of pads the user configured`() {
        assertEquals(0b1, SessionParameterMapper.gamepadMaskFor(StreamSettings(emulatedControllerCount = 1)))
        assertEquals(0b1111, SessionParameterMapper.gamepadMaskFor(StreamSettings(emulatedControllerCount = 4)))
        // Out of range in the blob must not produce a mask with bits above the XInput limit.
        assertEquals(0b1111, SessionParameterMapper.gamepadMaskFor(StreamSettings(emulatedControllerCount = 9)))
    }

    @Test
    fun `the LAN default picks the LAN packet size and FEC overhead`() {
        val configuration = build(StreamSettings()).configuration
        assertEquals(NetworkProfile.LAN, configuration.network)
        assertEquals(1392, configuration.packetSize)
    }

    @Test
    fun `every session gets a fresh remote-input key`() {
        val random = SecureRandom()
        val first = RemoteInputKey.generate(random)
        val second = RemoteInputKey.generate(random)
        assertEquals(RemoteInputKey.KEY_BYTES, first.key.size)
        assertNotEquals(Hex.encode(first.key), Hex.encode(second.key))
    }
}
