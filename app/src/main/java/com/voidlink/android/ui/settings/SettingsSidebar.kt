package com.voidlink.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voidlink.android.data.EmulatedControllerType
import com.voidlink.android.data.ExternalDisplayMode
import com.voidlink.android.data.FrameRate
import com.voidlink.android.data.GestureAction
import com.voidlink.android.data.GyroMode
import com.voidlink.android.data.OnScreenWidgetPreset
import com.voidlink.android.data.SettingsFormat
import com.voidlink.android.data.StreamResolution
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.data.SurroundMode
import com.voidlink.android.data.TouchMode
import com.voidlink.android.data.VideoCodec
import com.voidlink.android.ui.components.HairlineDivider
import com.voidlink.android.ui.components.PickerRow
import com.voidlink.android.ui.components.SegmentedRow
import com.voidlink.android.ui.components.SettingsSection
import com.voidlink.android.ui.components.SliderRow
import com.voidlink.android.ui.components.ToggleRow
import com.voidlink.android.ui.components.VoidLinkIcons
import com.voidlink.android.ui.theme.VoidLinkTheme
import kotlin.math.roundToInt

/** Width of the sidebar, both as a split pane and as a phone drawer. */
val SettingsSidebarWidth = 340.dp

/**
 * The settings panel.
 *
 * A single scrolling column of collapsible sections, each built from the shared row components so
 * that every control in the app looks and behaves the same. The panel is presentation-only: it
 * receives an immutable [StreamSettings] and reports edits through [onUpdate], leaving persistence
 * to [SettingsViewModel].
 *
 * @param settings the settings to render.
 * @param onUpdate invoked with a transform producing the new settings.
 * @param onClose invoked when the user dismisses the panel.
 * @param onResetDefaults invoked from the overflow menu.
 * @param modifier layout modifier.
 * @param width how wide the panel should be. Callers on a narrow phone pass the screen width so the
 *   drawer does not leave a useless sliver of scrim down one side.
 */
@Composable
fun SettingsSidebar(
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    onClose: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = SettingsSidebarWidth,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    var videoExpanded by rememberSaveable { mutableStateOf(true) }
    var touchExpanded by rememberSaveable { mutableStateOf(false) }
    var gesturesExpanded by rememberSaveable { mutableStateOf(false) }
    var peripheralsExpanded by rememberSaveable { mutableStateOf(false) }
    var audioExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(SettingsSidebarWidth)
            .background(colors.card),
    ) {
        SidebarHeader(onClose = onClose, onResetDefaults = onResetDefaults)
        HairlineDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            VideoSection(
                settings = settings,
                onUpdate = onUpdate,
                expanded = videoExpanded,
                onToggle = { videoExpanded = !videoExpanded },
            )
            TouchAndControllerSection(
                settings = settings,
                onUpdate = onUpdate,
                expanded = touchExpanded,
                onToggle = { touchExpanded = !touchExpanded },
            )
            GesturesSection(
                settings = settings,
                onUpdate = onUpdate,
                expanded = gesturesExpanded,
                onToggle = { gesturesExpanded = !gesturesExpanded },
            )
            PeripheralsSection(
                settings = settings,
                onUpdate = onUpdate,
                expanded = peripheralsExpanded,
                onToggle = { peripheralsExpanded = !peripheralsExpanded },
            )
            AudioSection(
                settings = settings,
                onUpdate = onUpdate,
                expanded = audioExpanded,
                onToggle = { audioExpanded = !audioExpanded },
            )
            Box(modifier = Modifier.height(spacing.xxl))
        }
    }
}

/** Header row: sidebar toggle, "Settings" title, overflow menu. */
@Composable
private fun SidebarHeader(
    onClose: () -> Unit,
    onResetDefaults: () -> Unit,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var overflowOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = VoidLinkIcons.Sidebar,
                contentDescription = "Hide settings",
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            // A 340dp panel cannot carry the 34sp screen title without crowding both icon buttons.
            text = "Settings",
            style = VoidLinkTheme.cardTitle,
            color = colors.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Box {
            IconButton(onClick = { overflowOpen = true }) {
                Icon(
                    imageVector = VoidLinkIcons.Overflow,
                    contentDescription = "More options",
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Reset to defaults", style = VoidLinkTheme.body) },
                    onClick = {
                        overflowOpen = false
                        onResetDefaults()
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------------

/** Bitrate, codec, HDR, chroma, resolution and frame rate. */
@Composable
private fun VideoSection(
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SettingsSection(
        title = "Video",
        icon = VoidLinkIcons.Video,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        SliderRow(
            label = "Bitrate",
            value = settings.bitrateKbps.toFloat(),
            range = StreamSettings.BITRATE_MIN_KBPS.toFloat()..StreamSettings.BITRATE_MAX_KBPS.toFloat(),
            format = { raw -> SettingsFormat.bitrate(raw.roundToInt()) },
            quantize = { raw -> snapTo(raw, BITRATE_STEP_KBPS) },
            onCommit = { chosen ->
                onUpdate { current -> current.copy(bitrateKbps = chosen.roundToInt()) }
            },
            info = "How much data the host may spend on video each second. Raise it for sharper " +
                "detail, lower it if the stream stutters on a busy network. Above roughly " +
                "150 Mbps most hardware decoders stall rather than get sharper.",
        )
        SegmentedRow(
            label = "Preferred Codec",
            options = VideoCodec.ordered.map { it.label },
            selectedIndex = VideoCodec.ordered.indexOf(settings.codec),
            onSelect = { index -> onUpdate { it.copy(codec = VideoCodec.ordered[index]) } },
            info = "HEVC and AV1 look better at the same bitrate but need a capable decoder on " +
                "both ends. Auto negotiates the best codec both machines support.",
        )
        ToggleRow(
            label = "HDR",
            checked = settings.hdrEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(hdrEnabled = enabled) } },
            info = "Streams high dynamic range when the host, the game and this display all " +
                "support it. Requires HEVC or AV1.",
            enabled = settings.codec != VideoCodec.H264,
        )
        ToggleRow(
            label = "YUV 4:4:4",
            checked = settings.yuv444Enabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(yuv444Enabled = enabled) } },
            info = "Sends full colour resolution instead of the usual 4:2:0 subsampling. Text and " +
                "thin UI lines get much sharper, at a significant bitrate cost.",
            enabled = settings.codec != VideoCodec.H264,
        )
        SegmentedRow(
            label = "Resolution",
            options = StreamResolution.ordered.map { it.label },
            selectedIndex = StreamResolution.ordered.indexOf(settings.resolution),
            onSelect = { index -> onUpdate { it.copy(resolution = StreamResolution.ordered[index]) } },
            info = "The size of the desktop the host renders for you. Native matches this " +
                "device's own screen.",
        )
        SegmentedRow(
            label = "FPS",
            options = FrameRate.ordered.map { it.label },
            selectedIndex = FrameRate.ordered.indexOf(settings.frameRate),
            onSelect = { index -> onUpdate { it.copy(frameRate = FrameRate.ordered[index]) } },
            info = "Frames per second requested from the host. Values above this display's own " +
                "refresh rate will not be visible.",
        )
        ToggleRow(
            label = "Optimize Game Settings",
            checked = settings.optimizeGameSettings,
            onCheckedChange = { enabled -> onUpdate { it.copy(optimizeGameSettings = enabled) } },
            info = "Lets the host change a game's own resolution and quality settings to match " +
                "the stream. Turn it off if you would rather the game keep the settings you " +
                "chose on the PC itself.",
        )
        ToggleRow(
            label = "Show Stats Overlay",
            checked = settings.showStatsOverlay,
            onCheckedChange = { enabled -> onUpdate { it.copy(showStatsOverlay = enabled) } },
            info = "Draws a small chip over the stream with the live resolution, frame rate, " +
                "bitrate, decode time and packet loss.",
        )
    }
}

/** Touch translation, on-screen widgets, controller emulation and gyro. */
@Composable
private fun TouchAndControllerSection(
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SettingsSection(
        title = "Touch & Controller",
        icon = VoidLinkIcons.Touch,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        SegmentedRow(
            label = "Touch Mode",
            options = TouchMode.ordered.map { it.label },
            selectedIndex = TouchMode.ordered.indexOf(settings.touchMode),
            onSelect = { index -> onUpdate { it.copy(touchMode = TouchMode.ordered[index]) } },
            info = "Touchpad moves the host cursor relatively, like a laptop trackpad. Native " +
                "Touch forwards real touch events. Absolute Touch maps your finger straight to " +
                "the host cursor.",
        )
        ToggleRow(
            label = "Enable On-Screen Widget & Peripherals",
            checked = settings.onScreenWidgetEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(onScreenWidgetEnabled = enabled) } },
            info = "Master switch for the overlay that carries the on-screen buttons, the " +
                "keyboard toggle and the touch divider.",
        )
        SliderRow(
            label = "Divider Position",
            value = settings.dividerPositionPercent.toFloat(),
            range = StreamSettings.DIVIDER_MIN_PERCENT.toFloat()..StreamSettings.DIVIDER_MAX_PERCENT.toFloat(),
            format = { raw -> SettingsFormat.dividerPosition(raw.roundToInt()) },
            quantize = { raw -> raw.roundToInt().toFloat() },
            onCommit = { chosen ->
                onUpdate { current -> current.copy(dividerPositionPercent = chosen.roundToInt()) }
            },
            enabled = settings.onScreenWidgetEnabled,
            info = "Where the screen splits between the two touch halves, measured from the left " +
                "edge.",
        )
        SliderRow(
            label = "Touch Pointer Velocity",
            value = settings.touchPointerVelocityPercent.toFloat(),
            range = StreamSettings.VELOCITY_MIN_PERCENT.toFloat()..StreamSettings.VELOCITY_MAX_PERCENT.toFloat(),
            format = { raw -> SettingsFormat.percent(raw.roundToInt()) },
            quantize = { raw -> snapTo(raw, VELOCITY_STEP_PERCENT) },
            onCommit = { chosen ->
                onUpdate { current ->
                    current.copy(touchPointerVelocityPercent = chosen.roundToInt())
                }
            },
            enabled = settings.touchMode != TouchMode.ABSOLUTE_TOUCH,
            info = "How far the host cursor travels for a given finger movement. Absolute Touch " +
                "ignores this because the mapping is one to one.",
        )
        SegmentedRow(
            label = "On-Screen Widgets",
            options = OnScreenWidgetPreset.ordered.map { it.label },
            selectedIndex = OnScreenWidgetPreset.ordered.indexOf(settings.onScreenWidgets),
            onSelect = { index ->
                onUpdate { it.copy(onScreenWidgets = OnScreenWidgetPreset.ordered[index]) }
            },
            enabled = settings.onScreenWidgetEnabled,
            disabledOptions = CUSTOM_WIDGETS_DISABLED,
            info = "Simple shows a stick and the face buttons; Full adds triggers, bumpers and " +
                "the D-pad. Custom is greyed out because the layout editor is not in this " +
                "release yet.",
        )
        ToggleRow(
            label = "Swap A/B X/Y Buttons",
            checked = settings.swapFaceButtons,
            onCheckedChange = { enabled -> onUpdate { it.copy(swapFaceButtons = enabled) } },
            info = "Matches Nintendo-style pads, where the physical positions of A/B and X/Y are " +
                "the other way round.",
        )
        SegmentedRow(
            label = "Emulated Controller Type",
            options = EmulatedControllerType.ordered.map { it.label },
            selectedIndex = EmulatedControllerType.ordered.indexOf(settings.emulatedControllerType),
            onSelect = { index ->
                onUpdate { it.copy(emulatedControllerType = EmulatedControllerType.ordered[index]) }
            },
            info = "Which virtual gamepad the host presents to games. DS4 exposes a touchpad and " +
                "motion; Both is useful for titles that only detect one of them.",
        )
        SegmentedRow(
            label = "Gyro Mode",
            options = GyroMode.ordered.map { it.label },
            selectedIndex = GyroMode.ordered.indexOf(settings.gyroMode),
            onSelect = { index -> onUpdate { it.copy(gyroMode = GyroMode.ordered[index]) } },
            info = "Where motion data comes from. Built-in uses this device's own gyroscope; " +
                "Controller uses a connected pad's; Auto prefers the controller when present.",
        )
        SliderRow(
            label = "Gyro Sensitivity",
            value = settings.gyroSensitivityPercent.toFloat(),
            range = StreamSettings.VELOCITY_MIN_PERCENT.toFloat()..StreamSettings.VELOCITY_MAX_PERCENT.toFloat(),
            format = { raw -> SettingsFormat.percent(raw.roundToInt()) },
            quantize = { raw -> snapTo(raw, VELOCITY_STEP_PERCENT) },
            onCommit = { chosen ->
                onUpdate { current -> current.copy(gyroSensitivityPercent = chosen.roundToInt()) }
            },
            enabled = settings.gyroMode != GyroMode.OFF,
            info = "Scales how much aim movement a given amount of physical rotation produces. " +
                "Only applies once Gyro Mode is something other than Off.",
        )
        ToggleRow(
            label = "Rumble",
            checked = settings.rumbleEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(rumbleEnabled = enabled) } },
            info = "Passes the host's force-feedback events to a connected controller, or to this " +
                "device's own vibrator when the pad has no motors.",
        )
    }
}

/** Gesture bindings. */
@Composable
private fun GesturesSection(
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SettingsSection(
        title = "Gestures",
        icon = VoidLinkIcons.Gestures,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        ToggleRow(
            label = "Three-Finger Tap",
            checked = settings.threeFingerTapEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(threeFingerTapEnabled = enabled) } },
            info = "Recognise a simultaneous three-finger tap anywhere on the stream.",
        )
        PickerRow(
            label = "Three-Finger Tap Action",
            options = GestureAction.ordered.map { it.label },
            selectedIndex = GestureAction.ordered.indexOf(settings.threeFingerTapAction),
            onSelect = { index ->
                onUpdate { it.copy(threeFingerTapAction = GestureAction.ordered[index]) }
            },
            enabled = settings.threeFingerTapEnabled,
            info = "What the three-finger tap does while streaming.",
        )
        ToggleRow(
            label = "Edge Swipe",
            checked = settings.edgeSwipeEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(edgeSwipeEnabled = enabled) } },
            info = "Recognise a swipe that starts at the very edge of the screen. Turn this off " +
                "if a game uses edge swipes of its own.",
        )
        PickerRow(
            label = "Edge Swipe Action",
            options = GestureAction.ordered.map { it.label },
            selectedIndex = GestureAction.ordered.indexOf(settings.edgeSwipeAction),
            onSelect = { index ->
                onUpdate { it.copy(edgeSwipeAction = GestureAction.ordered[index]) }
            },
            enabled = settings.edgeSwipeEnabled,
            info = "What the edge swipe does while streaming.",
        )
    }
}

/** External display, mouse capture and keyboard forwarding. */
@Composable
private fun PeripheralsSection(
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SettingsSection(
        title = "Peripherals",
        icon = VoidLinkIcons.Peripherals,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        SegmentedRow(
            label = "External Display Mode",
            options = ExternalDisplayMode.ordered.map { it.label },
            selectedIndex = ExternalDisplayMode.ordered.indexOf(settings.externalDisplayMode),
            onSelect = { index ->
                onUpdate { it.copy(externalDisplayMode = ExternalDisplayMode.ordered[index]) }
            },
            info = "Mirror shows the stream on both screens. Separate Display sends the stream to " +
                "the external screen and keeps the controls on this device.",
        )
        ToggleRow(
            label = "Capture Mouse",
            checked = settings.captureMouse,
            onCheckedChange = { enabled -> onUpdate { it.copy(captureMouse = enabled) } },
            info = "Grabs a connected mouse so the host receives raw relative movement, which is " +
                "what first-person games expect.",
        )
        ToggleRow(
            label = "Forward Keyboard",
            checked = settings.forwardKeyboard,
            onCheckedChange = { enabled -> onUpdate { it.copy(forwardKeyboard = enabled) } },
            info = "Sends physical keyboard input, including modifier chords, to the host instead " +
                "of handling it locally.",
        )
    }
}

/** Channel layout and host muting. */
@Composable
private fun AudioSection(
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SettingsSection(
        title = "Audio",
        icon = VoidLinkIcons.Audio,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        SegmentedRow(
            label = "Surround Sound",
            options = SurroundMode.ordered.map { it.label },
            selectedIndex = SurroundMode.ordered.indexOf(settings.surroundMode),
            onSelect = { index -> onUpdate { it.copy(surroundMode = SurroundMode.ordered[index]) } },
            disabledOptions = SURROUND_DISABLED,
            info = "Requests a multi-channel mix from the host. 5.1 and 7.1 are greyed out " +
                "because this release decodes stereo only; surround decoding lands with the " +
                "audio work.",
        )
        ToggleRow(
            label = "Mute Host Audio",
            checked = settings.muteHostAudio,
            onCheckedChange = { enabled -> onUpdate { it.copy(muteHostAudio = enabled) } },
            info = "Keeps the host's own speakers silent while you stream, so the machine stays " +
                "quiet in the other room.",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

/**
 * Segments that exist but cannot be chosen yet.
 *
 * They are shown greyed out rather than hidden: a control that silently loses an option leaves the
 * user hunting for a feature they know the app has.
 */
private val CUSTOM_WIDGETS_DISABLED: Set<Int> =
    setOf(OnScreenWidgetPreset.ordered.indexOf(OnScreenWidgetPreset.CUSTOM))

private val SURROUND_DISABLED: Set<Int> = setOf(
    SurroundMode.ordered.indexOf(SurroundMode.SURROUND_5_1),
    SurroundMode.ordered.indexOf(SurroundMode.SURROUND_7_1),
)

/** Bitrate snaps to half-megabit stops so the label never shows jittery values. */
private const val BITRATE_STEP_KBPS = 500

/** Velocity and sensitivity snap to 5% stops. */
private const val VELOCITY_STEP_PERCENT = 5

/** Snaps a raw slider position to the nearest multiple of [step]. */
private fun snapTo(raw: Float, step: Int): Float = ((raw / step).roundToInt() * step).toFloat()

@Preview(name = "Settings sidebar", widthDp = 340, heightDp = 900)
@Composable
private fun SettingsSidebarPreview() {
    VoidLinkTheme {
        SettingsSidebar(
            settings = StreamSettings(bitrateKbps = 23_000),
            onUpdate = {},
            onClose = {},
            onResetDefaults = {},
        )
    }
}
