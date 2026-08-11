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
        // "Play Audio on PC" is off out of the box, which is stored as the host being muted.
        assertTrue(defaults.muteHostAudio)
        assertEquals(ExitGesture.THREE_FINGER, defaults.exitGesture)
        assertEquals(160, defaults.exitSwipeDistanceDp)
        assertEquals(1, defaults.emulatedControllerCount)
        assertTrue(defaults.tapToClick)
        assertTrue(defaults.twoFingerTapRightClick)
        assertFalse(defaults.threeFingerTapMiddleClick)
        assertTrue(defaults.favoriteRowIds.isEmpty())
        assertTrue(defaults.onScreenWidgetEnabled)
        assertTrue(defaults.captureMouse)
        assertTrue(defaults.forwardKeyboard)
        // sops on by default: a host rendering at its own desktop size and downscaling wastes both
        // GPU time and bitrate.
        assertTrue(defaults.optimizeGameSettings)
        assertTrue(defaults.rumbleEnabled)
        assertFalse(defaults.showStatsOverlay)
    }

    @Test
    fun `a blob predating the video toggles keeps their defaults rather than turning them off`() {
        // Regression guard for the whole single-blob storage strategy: a field added after a user
        // last saved must come back as its default, not as false.
        val legacy = """{"bitrateKbps":30000,"hdrEnabled":true}"""

        val restored = json.decodeFromString(StreamSettings.serializer(), legacy)

        assertTrue(restored.optimizeGameSettings)
        assertTrue(restored.rumbleEnabled)
        assertFalse(restored.showStatsOverlay)
    }

    @Test
    fun `a blob predating the gesture and controller rows restores their defaults`() {
        val legacy = """{"bitrateKbps":30000,"touchMode":"TOUCHPAD"}"""

        val restored = json.decodeFromString(StreamSettings.serializer(), legacy)

        assertEquals(ExitGesture.THREE_FINGER, restored.exitGesture)
        assertEquals(160, restored.exitSwipeDistanceDp)
        assertEquals(1, restored.emulatedControllerCount)
        assertTrue(restored.tapToClick)
        assertTrue(restored.twoFingerTapRightClick)
        assertFalse(restored.threeFingerTapMiddleClick)
        assertTrue(restored.favoriteRowIds.isEmpty())
    }

    @Test
    fun `starred row ids survive a round trip`() {
        val original = StreamSettings(favoriteRowIds = setOf("video.bitrate", "gestures.edgeSwipe"))

        val restored = json.decodeFromString(
            StreamSettings.serializer(),
            json.encodeToString(StreamSettings.serializer(), original),
        )

        assertEquals(setOf("video.bitrate", "gestures.edgeSwipe"), restored.favoriteRowIds)
    }

    @Test
    fun `coerced clamps the controller count and the exit swipe distance`() {
        val tooMany = StreamSettings(emulatedControllerCount = 9, exitSwipeDistanceDp = 5).coerced()
        val tooFew = StreamSettings(emulatedControllerCount = 0, exitSwipeDistanceDp = 9_000).coerced()

        assertEquals(StreamSettings.CONTROLLERS_MAX, tooMany.emulatedControllerCount)
        assertEquals(StreamSettings.EXIT_SWIPE_MIN_DP, tooMany.exitSwipeDistanceDp)
        assertEquals(StreamSettings.CONTROLLERS_MIN, tooFew.emulatedControllerCount)
        assertEquals(StreamSettings.EXIT_SWIPE_MAX_DP, tooFew.exitSwipeDistanceDp)
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
            muteHostAudio = false,
            optimizeGameSettings = false,
            showStatsOverlay = true,
            rumbleEnabled = false,
            exitGesture = ExitGesture.FOUR_FINGER,
            exitSwipeDistanceDp = 240,
            tapToClick = false,
            twoFingerTapRightClick = false,
            threeFingerTapMiddleClick = true,
            emulatedControllerCount = 4,
            favoriteRowIds = setOf("video.bitrate", "touch.mode"),
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
        assertEquals(ExitGesture.entries.toSet(), ExitGesture.ordered.toSet())
        assertEquals(ExitGesture.entries.size, ExitGesture.ordered.size)
    }

    @Test
    fun `no segmented control carries more options than its track can lay out equally`() {
        // Past four segments the control switches to a scrolling track; that is fine, but the
        // controls that are meant to stay equal-width must not silently cross the line.
        assertTrue(VideoCodec.ordered.size <= 4)
        assertTrue(FrameRate.ordered.size <= 4)
        assertTrue(TouchMode.ordered.size <= 4)
        assertTrue(OnScreenWidgetPreset.ordered.size <= 4)
        assertTrue(EmulatedControllerType.ordered.size <= 4)
        assertTrue(GyroMode.ordered.size <= 4)
        assertTrue(SurroundMode.ordered.size <= 4)
        assertTrue(ExitGesture.ordered.size <= 4)
        assertTrue(ExternalDisplayMode.ordered.size <= 4)
    }

    @Test
    fun `the exit gesture knows how many fingers it needs`() {
        assertEquals(3, ExitGesture.THREE_FINGER.fingerCount)
        assertEquals(4, ExitGesture.FOUR_FINGER.fingerCount)
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
