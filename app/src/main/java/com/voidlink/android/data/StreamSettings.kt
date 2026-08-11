package com.voidlink.android.data

import kotlinx.serialization.Serializable

/**
 * Video codec preference sent to the host during launch negotiation.
 *
 * [AUTO] lets the client pick the best codec the local decoder and the host agree on.
 */
@Serializable
enum class VideoCodec(val label: String) {
    H264("H.264"),
    HEVC("HEVC"),
    AV1("AV1"),
    AUTO("Auto"),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<VideoCodec> = listOf(H264, HEVC, AV1, AUTO)
    }
}

/**
 * Stream resolution preference.
 *
 * [NATIVE] means "use the device's own display size", resolved at launch time; its [width] and
 * [height] are therefore zero placeholders.
 */
@Serializable
enum class StreamResolution(val label: String, val width: Int, val height: Int) {
    RES_720P("720p", 1280, 720),
    RES_1080P("1080p", 1920, 1080),
    RES_1440P("1440p", 2560, 1440),
    RES_2160P("4K", 3840, 2160),
    NATIVE("Native", 0, 0),
    ;

    /** True when the concrete pixel size must be derived from the device display. */
    val isNative: Boolean get() = this == NATIVE

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<StreamResolution> = listOf(RES_720P, RES_1080P, RES_1440P, RES_2160P, NATIVE)
    }
}

/** Target frame rate for the stream. */
@Serializable
enum class FrameRate(val label: String, val fps: Int) {
    FPS_30("30", 30),
    FPS_60("60", 60),
    FPS_90("90", 90),
    FPS_120("120", 120),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<FrameRate> = listOf(FPS_30, FPS_60, FPS_90, FPS_120)
    }
}

/**
 * How raw touch input on the local screen is translated into host input.
 *
 * - [TOUCHPAD] — relative mouse movement, like a laptop trackpad.
 * - [NATIVE_TOUCH] — touch events forwarded to the host as real touch events.
 * - [ABSOLUTE_TOUCH] — the finger position maps 1:1 to the host cursor position.
 */
@Serializable
enum class TouchMode(val label: String) {
    TOUCHPAD("Touchpad"),
    NATIVE_TOUCH("Native Touch"),
    ABSOLUTE_TOUCH("Absolute Touch"),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<TouchMode> = listOf(TOUCHPAD, NATIVE_TOUCH, ABSOLUTE_TOUCH)
    }
}

/** Preset for the on-screen touch controller overlay. */
@Serializable
enum class OnScreenWidgetPreset(val label: String) {
    OFF("Off"),
    SIMPLE("Simple"),
    FULL("Full"),
    CUSTOM("Custom"),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<OnScreenWidgetPreset> = listOf(OFF, SIMPLE, FULL, CUSTOM)
    }
}

/** Which virtual gamepad the host is asked to expose. */
@Serializable
enum class EmulatedControllerType(val label: String) {
    XBOX_360("Xbox 360"),
    DUALSHOCK_4("DS4"),
    BOTH("Both"),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<EmulatedControllerType> = listOf(XBOX_360, DUALSHOCK_4, BOTH)
    }
}

/** Source of gyroscope motion data forwarded to the host. */
@Serializable
enum class GyroMode(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    BUILT_IN("Built-in"),
    CONTROLLER("Controller"),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<GyroMode> = listOf(OFF, AUTO, BUILT_IN, CONTROLLER)
    }
}

/** How many fingers the swipe that leaves a stream needs. */
@Serializable
enum class ExitGesture(val label: String, val fingerCount: Int) {
    THREE_FINGER("3-finger", 3),
    FOUR_FINGER("4-finger", 4),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<ExitGesture> = listOf(THREE_FINGER, FOUR_FINGER)
    }
}

/** Actions that a gesture can be bound to. */
@Serializable
enum class GestureAction(val label: String) {
    NONE("Nothing"),
    TOGGLE_KEYBOARD("Toggle Keyboard"),
    TOGGLE_SETTINGS("Toggle Settings"),
    TOGGLE_OVERLAY("Toggle Widgets"),
    DISCONNECT("Disconnect"),
    ;

    companion object {
        /** Options in the order they appear in the picker. */
        val ordered: List<GestureAction> =
            listOf(NONE, TOGGLE_KEYBOARD, TOGGLE_SETTINGS, TOGGLE_OVERLAY, DISCONNECT)
    }
}

/** How a connected external display is used while streaming. */
@Serializable
enum class ExternalDisplayMode(val label: String) {
    MIRROR("Mirror"),
    SEPARATE("Separate Display"),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<ExternalDisplayMode> = listOf(MIRROR, SEPARATE)
    }
}

/** Requested audio channel layout. */
@Serializable
enum class SurroundMode(val label: String, val channelCount: Int) {
    STEREO("Stereo", 2),
    SURROUND_5_1("5.1", 6),
    SURROUND_7_1("7.1", 8),
    ;

    companion object {
        /** Options in the order they appear in the segmented control. */
        val ordered: List<SurroundMode> = listOf(STEREO, SURROUND_5_1, SURROUND_7_1)
    }
}

/**
 * Every user-tunable streaming preference, in one immutable value.
 *
 * The whole object is persisted as a single JSON blob (see
 * [SettingsRepository][com.voidlink.android.data.SettingsRepository]) so that adding a field is
 * always backward compatible: old blobs simply fall back to the default declared here.
 *
 * A [KnownHost] may carry an override of this type; [KnownHost.effectiveSettings] resolves which
 * one wins.
 */
@Serializable
data class StreamSettings(
    // ---- Video -------------------------------------------------------------------------------
    /** Requested video bitrate in kilobits per second. Clamped to [BITRATE_MIN_KBPS]..[BITRATE_MAX_KBPS]. */
    val bitrateKbps: Int = DEFAULT_BITRATE_KBPS,
    /** Preferred video codec. */
    val codec: VideoCodec = VideoCodec.AUTO,
    /** Request an HDR10 stream when both host and device support it. */
    val hdrEnabled: Boolean = false,
    /** Request 4:4:4 chroma sampling (sharper text, much higher bitrate cost). */
    val yuv444Enabled: Boolean = false,
    /** Requested stream resolution. */
    val resolution: StreamResolution = StreamResolution.RES_1080P,
    /** Requested frame rate. */
    val frameRate: FrameRate = FrameRate.FPS_60,
    /**
     * The host's `sops` flag: let the host rewrite in-game graphics settings to match the stream.
     *
     * On by default because a host that renders at its own desktop resolution and then downscales
     * wastes both GPU time and bitrate.
     */
    val optimizeGameSettings: Boolean = true,
    /** Draw the live bitrate/latency chip over the stream. */
    val showStatsOverlay: Boolean = false,

    // ---- Touch & Controller ------------------------------------------------------------------
    /** How touches are translated into host input. */
    val touchMode: TouchMode = TouchMode.NATIVE_TOUCH,
    /** Master switch for the on-screen widget/peripheral overlay. */
    val onScreenWidgetEnabled: Boolean = true,
    /** Position of the split between the two touch halves, as a percentage from the left edge. */
    val dividerPositionPercent: Int = 50,
    /** Pointer speed multiplier in percent (25..300). */
    val touchPointerVelocityPercent: Int = 100,
    /** Which on-screen widget layout is shown. */
    val onScreenWidgets: OnScreenWidgetPreset = OnScreenWidgetPreset.SIMPLE,
    /** Swap A/B and X/Y so Nintendo-style physical pads map correctly. */
    val swapFaceButtons: Boolean = false,
    /** Virtual gamepad type requested from the host. */
    val emulatedControllerType: EmulatedControllerType = EmulatedControllerType.XBOX_360,
    /** How many virtual pads the host is asked to expose (1..[CONTROLLERS_MAX]). */
    val emulatedControllerCount: Int = 1,
    /** Gyro source. */
    val gyroMode: GyroMode = GyroMode.OFF,
    /** Gyro sensitivity multiplier in percent (25..300). */
    val gyroSensitivityPercent: Int = 100,
    /** Route the host's force feedback to a controller, or to this device when it has no motors. */
    val rumbleEnabled: Boolean = true,

    // ---- Gestures ----------------------------------------------------------------------------
    /** Which multi-finger swipe leaves the stream. */
    val exitGesture: ExitGesture = ExitGesture.THREE_FINGER,
    /** How far the exit swipe must travel, in dp, before it counts. */
    val exitSwipeDistanceDp: Int = 160,
    /** Single-finger tap acts as a left click in Touchpad mode. */
    val tapToClick: Boolean = true,
    /** Two-finger tap acts as a right click. */
    val twoFingerTapRightClick: Boolean = true,
    /** Three-finger tap acts as a middle click. */
    val threeFingerTapMiddleClick: Boolean = false,
    /** Whether the three-finger tap gesture is recognised at all. */
    val threeFingerTapEnabled: Boolean = true,
    /** Action bound to a three-finger tap. */
    val threeFingerTapAction: GestureAction = GestureAction.TOGGLE_KEYBOARD,
    /** Whether the edge-swipe gesture is recognised at all. */
    val edgeSwipeEnabled: Boolean = true,
    /** Action bound to a swipe in from the screen edge. */
    val edgeSwipeAction: GestureAction = GestureAction.TOGGLE_SETTINGS,

    // ---- Peripherals -------------------------------------------------------------------------
    /** How an attached external display is used. */
    val externalDisplayMode: ExternalDisplayMode = ExternalDisplayMode.MIRROR,
    /** Capture a physical mouse for raw relative input while streaming. */
    val captureMouse: Boolean = true,
    /** Forward physical keyboard input (including modifier chords) to the host. */
    val forwardKeyboard: Boolean = true,

    // ---- Audio -------------------------------------------------------------------------------
    /** Requested channel layout. */
    val surroundMode: SurroundMode = SurroundMode.STEREO,
    /**
     * Ask the host to keep its own speakers silent while streaming.
     *
     * Defaults to true, which is what the "Play Audio on PC" row in the sidebar renders as *off*:
     * a PC in another room blaring the game you are streaming is nobody's idea of a default. The
     * field stays phrased as "mute" because that is the direction the host protocol takes it in.
     */
    val muteHostAudio: Boolean = true,

    // ---- Panel state -------------------------------------------------------------------------
    /**
     * Ids of the settings rows the user starred, duplicated into the panel's Favorites section.
     *
     * Stored with the settings rather than separately so a single blob still captures everything
     * the panel needs to redraw itself.
     */
    val favoriteRowIds: Set<String> = emptySet(),
) {
    /**
     * Returns a copy with every numeric field forced back into its legal range.
     *
     * Persisted blobs are user-editable in principle (and future versions may widen ranges), so
     * settings are sanitised on the way out of storage rather than trusted.
     */
    fun coerced(): StreamSettings = copy(
        bitrateKbps = bitrateKbps.coerceIn(BITRATE_MIN_KBPS, BITRATE_MAX_KBPS),
        dividerPositionPercent = dividerPositionPercent.coerceIn(DIVIDER_MIN_PERCENT, DIVIDER_MAX_PERCENT),
        touchPointerVelocityPercent =
            touchPointerVelocityPercent.coerceIn(VELOCITY_MIN_PERCENT, VELOCITY_MAX_PERCENT),
        gyroSensitivityPercent =
            gyroSensitivityPercent.coerceIn(VELOCITY_MIN_PERCENT, VELOCITY_MAX_PERCENT),
        emulatedControllerCount = emulatedControllerCount.coerceIn(CONTROLLERS_MIN, CONTROLLERS_MAX),
        exitSwipeDistanceDp = exitSwipeDistanceDp.coerceIn(EXIT_SWIPE_MIN_DP, EXIT_SWIPE_MAX_DP),
    )

    companion object {
        /** Lowest selectable bitrate (500 kbps). */
        const val BITRATE_MIN_KBPS: Int = 500

        /** Highest selectable bitrate (150 Mbps). */
        const val BITRATE_MAX_KBPS: Int = 150_000

        /** Default bitrate, 20 Mbps — a safe value on typical home Wi-Fi. */
        const val DEFAULT_BITRATE_KBPS: Int = 20_000

        /** Lowest selectable divider position. */
        const val DIVIDER_MIN_PERCENT: Int = 10

        /** Highest selectable divider position. */
        const val DIVIDER_MAX_PERCENT: Int = 90

        /** Lowest selectable velocity / sensitivity multiplier. */
        const val VELOCITY_MIN_PERCENT: Int = 25

        /** Highest selectable velocity / sensitivity multiplier. */
        const val VELOCITY_MAX_PERCENT: Int = 300

        /** Fewest virtual controllers the host can be asked for. */
        const val CONTROLLERS_MIN: Int = 1

        /** Most virtual controllers the host can be asked for — the XInput limit. */
        const val CONTROLLERS_MAX: Int = 4

        /** Shortest exit swipe, in dp. Below this an ordinary drag would end the session. */
        const val EXIT_SWIPE_MIN_DP: Int = 40

        /** Longest exit swipe, in dp. */
        const val EXIT_SWIPE_MAX_DP: Int = 400
    }
}
