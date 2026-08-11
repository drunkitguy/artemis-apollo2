package com.voidlink.android.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the persisted shape of [StreamSettings]: its defaults, its JSON round-trip and the
 * clamping applied when a stored blob is out of range.
 */
class StreamSettingsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `defaults match the documented out-of-box configuration`() {
        val defaults = StreamSettings()

        assertEquals(20_000, defaults.bitrateKbps)
        assertEquals(VideoCodec.AUTO, defaults.codec)
        assertEquals(StreamResolution.RES_1080P, defaults.resolution)
        assertEquals(FrameRate.FPS_60, defaults.frameRate)
        assertEquals(TouchMode.NATIVE_TOUCH, defaults.touchMode)
        assertEquals(50, defaults.dividerPositionPercent)
        assertEquals(100, defaults.touchPointerVelocityPercent)
        assertEquals(100, defaults.gyroSensitivityPercent)
        assertEquals(EmulatedControllerType.XBOX_360, defaults.emulatedControllerType)
        assertEquals(GyroMode.OFF, defaults.gyroMode)
        assertEquals(ExternalDisplayMode.MIRROR, defaults.externalDisplayMode)
        assertEquals(SurroundMode.STEREO, defaults.surroundMode)
        assertFalse(defaults.hdrEnabled)
        assertFalse(defaults.yuv444Enabled)
        assertFalse(defaults.swapFaceButtons)
        assertFalse(defaults.muteHostAudio)
        assertTrue(defaults.onScreenWidgetEnabled)
        assertTrue(defaults.captureMouse)
        assertTrue(defaults.forwardKeyboard)
    }

    @Test
    fun `default settings survive a JSON round trip unchanged`() {
        val original = StreamSettings()

        val restored = json.decodeFromString(
            StreamSettings.serializer(),
            json.encodeToString(StreamSettings.serializer(), original),
        )

        assertEquals(original, restored)
    }

    @Test
    fun `a fully customised value survives a JSON round trip unchanged`() {
        val original = StreamSettings(
            bitrateKbps = 84_500,
            codec = VideoCodec.AV1,
            hdrEnabled = true,
            yuv444Enabled = true,
            resolution = StreamResolution.RES_2160P,
            frameRate = FrameRate.FPS_120,
            touchMode = TouchMode.ABSOLUTE_TOUCH,
            onScreenWidgetEnabled = false,
            dividerPositionPercent = 35,
            touchPointerVelocityPercent = 275,
            onScreenWidgets = OnScreenWidgetPreset.CUSTOM,
            swapFaceButtons = true,
            emulatedControllerType = EmulatedControllerType.BOTH,
            gyroMode = GyroMode.CONTROLLER,
            gyroSensitivityPercent = 60,
            threeFingerTapEnabled = false,
            threeFingerTapAction = GestureAction.DISCONNECT,
            edgeSwipeEnabled = false,
            edgeSwipeAction = GestureAction.NONE,
            externalDisplayMode = ExternalDisplayMode.SEPARATE,
            captureMouse = false,
            forwardKeyboard = false,
            surroundMode = SurroundMode.SURROUND_7_1,
            muteHostAudio = true,
        )

        val restored = json.decodeFromString(
            StreamSettings.serializer(),
            json.encodeToString(StreamSettings.serializer(), original),
        )

        assertEquals(original, restored)
        assertNotEquals(StreamSettings(), restored)
    }

    @Test
    fun `a blob written by an older build falls back to defaults for missing fields`() {
        val legacy = """{"bitrateKbps":45000,"codec":"HEVC"}"""

        val restored = json.decodeFromString(StreamSettings.serializer(), legacy)

        assertEquals(45_000, restored.bitrateKbps)
        assertEquals(VideoCodec.HEVC, restored.codec)
        // Everything absent from the document keeps its constructor default.
        assertEquals(StreamSettings().resolution, restored.resolution)
        assertEquals(StreamSettings().touchMode, restored.touchMode)
        assertEquals(StreamSettings().dividerPositionPercent, restored.dividerPositionPercent)
    }

    @Test
    fun `a blob written by a newer build ignores fields this build does not know`() {
        val futuristic = """{"bitrateKbps":30000,"quantumEntanglementMode":"on"}"""

        val restored = json.decodeFromString(StreamSettings.serializer(), futuristic)

        assertEquals(30_000, restored.bitrateKbps)
        assertEquals(StreamSettings().codec, restored.codec)
    }

    @Test
    fun `coerced clamps every numeric field into its legal range`() {
        val wild = StreamSettings(
            bitrateKbps = 900_000,
            dividerPositionPercent = 400,
            touchPointerVelocityPercent = 5,
            gyroSensitivityPercent = 9_000,
        ).coerced()

        assertEquals(StreamSettings.BITRATE_MAX_KBPS, wild.bitrateKbps)
        assertEquals(StreamSettings.DIVIDER_MAX_PERCENT, wild.dividerPositionPercent)
        assertEquals(StreamSettings.VELOCITY_MIN_PERCENT, wild.touchPointerVelocityPercent)
        assertEquals(StreamSettings.VELOCITY_MAX_PERCENT, wild.gyroSensitivityPercent)
    }

    @Test
    fun `coerced clamps values that are too small`() {
        val tiny = StreamSettings(bitrateKbps = 1, dividerPositionPercent = -20).coerced()

        assertEquals(StreamSettings.BITRATE_MIN_KBPS, tiny.bitrateKbps)
        assertEquals(StreamSettings.DIVIDER_MIN_PERCENT, tiny.dividerPositionPercent)
    }

    @Test
    fun `coerced leaves an already legal value alone`() {
        val legal = StreamSettings(bitrateKbps = 23_000, dividerPositionPercent = 50)

        assertEquals(legal, legal.coerced())
    }

    @Test
    fun `every segmented control lists each enum constant exactly once`() {
        assertEquals(VideoCodec.entries.toSet(), VideoCodec.ordered.toSet())
        assertEquals(VideoCodec.entries.size, VideoCodec.ordered.size)
        assertEquals(StreamResolution.entries.toSet(), StreamResolution.ordered.toSet())
        assertEquals(StreamResolution.entries.size, StreamResolution.ordered.size)
        assertEquals(FrameRate.entries.toSet(), FrameRate.ordered.toSet())
        assertEquals(TouchMode.entries.toSet(), TouchMode.ordered.toSet())
        assertEquals(OnScreenWidgetPreset.entries.toSet(), OnScreenWidgetPreset.ordered.toSet())
        assertEquals(EmulatedControllerType.entries.toSet(), EmulatedControllerType.ordered.toSet())
        assertEquals(GyroMode.entries.toSet(), GyroMode.ordered.toSet())
        assertEquals(GestureAction.entries.toSet(), GestureAction.ordered.toSet())
        assertEquals(ExternalDisplayMode.entries.toSet(), ExternalDisplayMode.ordered.toSet())
        assertEquals(SurroundMode.entries.toSet(), SurroundMode.ordered.toSet())
    }

    @Test
    fun `native resolution is the only one without concrete pixel dimensions`() {
        StreamResolution.entries.forEach { resolution ->
            if (resolution.isNative) {
                assertEquals(0, resolution.width)
                assertEquals(0, resolution.height)
            } else {
                assertTrue("${resolution.label} needs a width", resolution.width > 0)
                assertTrue("${resolution.label} needs a height", resolution.height > 0)
            }
        }
    }
}
