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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voidlink.android.data.EmulatedControllerType
import com.voidlink.android.data.ExitGesture
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
import com.voidlink.android.ui.components.FavoriteToggle
import com.voidlink.android.ui.components.HairlineDivider
import com.voidlink.android.ui.components.NavigationRow
import com.voidlink.android.ui.components.PickerRow
import com.voidlink.android.ui.components.SegmentedRow
import com.voidlink.android.ui.components.SettingsSection
import com.voidlink.android.ui.components.SliderRow
import com.voidlink.android.ui.components.StepperRow
import com.voidlink.android.ui.components.ToggleRow
import com.voidlink.android.ui.components.VoidLinkIcons
import com.voidlink.android.ui.theme.VoidLinkShapeTokens
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
 * The panel edits either the global settings or one host's overrides; which one is decided entirely
 * by [overrideHostName] being non-null, and by what [onUpdate] happens to write to.
 *
 * @param settings the settings to render — already resolved, so an override scope passes the
 *   host's effective values rather than the global ones.
 * @param onUpdate invoked with a transform producing the new settings.
 * @param onClose invoked when the user dismisses the panel.
 * @param onResetDefaults invoked from the overflow menu; resets whatever scope is showing.
 * @param modifier layout modifier.
 * @param width how wide the panel should be. Callers on a narrow phone pass the screen width so the
 *   drawer does not leave a useless sliver of scrim down one side.
 * @param overrideHostName name of the host whose overrides are being edited, or `null` for the
 *   global settings.
 * @param onEditGlobal leaves an override scope and returns to the global settings.
 * @param onToggleFavorite stars or unstars a row. Favourites are a property of the panel rather
 *   than of a host, so this always writes the global settings.
 */
@Composable
fun SettingsSidebar(
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    onClose: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = SettingsSidebarWidth,
    overrideHostName: String? = null,
    onEditGlobal: () -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    var videoExpanded by rememberSaveable { mutableStateOf(true) }
    var audioExpanded by rememberSaveable { mutableStateOf(false) }
    var touchExpanded by rememberSaveable { mutableStateOf(false) }
    var gesturesExpanded by rememberSaveable { mutableStateOf(false) }
    var peripheralsExpanded by rememberSaveable { mutableStateOf(false) }
    var favoritesExpanded by rememberSaveable { mutableStateOf(true) }
    var choosingFavorites by rememberSaveable { mutableStateOf(false) }

    val rows = RowContext(
        settings = settings,
        onUpdate = onUpdate,
        include = { true },
        choosingFavorites = choosingFavorites,
        onToggleFavorite = onToggleFavorite,
    )
    val favoriteRows = rows.copy(
        include = { id -> id in settings.favoriteRowIds },
        // Stars are not offered twice: the Favorites section mirrors rows, it does not curate them.
        choosingFavorites = false,
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(colors.card),
    ) {
        SidebarHeader(
            onClose = onClose,
            onResetDefaults = onResetDefaults,
            overrideHostName = overrideHostName,
            onEditGlobal = onEditGlobal,
            choosingFavorites = choosingFavorites,
            onToggleChoosingFavorites = { choosingFavorites = !choosingFavorites },
        )
        HairlineDivider()

        if (overrideHostName != null) {
            OverrideBanner(hostName = overrideHostName)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (settings.favoriteRowIds.isNotEmpty()) {
                SettingsSection(
                    title = "Favorites",
                    icon = VoidLinkIcons.Favorite,
                    expanded = favoritesExpanded,
                    onToggle = { favoritesExpanded = !favoritesExpanded },
                ) {
                    VideoRows(favoriteRows)
                    AudioRows(favoriteRows)
                    TouchRows(favoriteRows)
                    GestureRows(favoriteRows)
                    PeripheralRows(favoriteRows)
                }
            }

            SettingsSection(
                title = "Video",
                icon = VoidLinkIcons.Video,
                expanded = videoExpanded,
                onToggle = { videoExpanded = !videoExpanded },
            ) {
                VideoRows(rows)
            }
            SettingsSection(
                title = "Audio",
                icon = VoidLinkIcons.Audio,
                expanded = audioExpanded,
                onToggle = { audioExpanded = !audioExpanded },
            ) {
                AudioRows(rows)
            }
            SettingsSection(
                title = "Touch & Controller",
                icon = VoidLinkIcons.Touch,
                expanded = touchExpanded,
                onToggle = { touchExpanded = !touchExpanded },
            ) {
                TouchRows(rows)
            }
            SettingsSection(
                title = "Gestures",
                icon = VoidLinkIcons.Gestures,
                expanded = gesturesExpanded,
                onToggle = { gesturesExpanded = !gesturesExpanded },
            ) {
                GestureRows(rows)
            }
            SettingsSection(
                title = "Peripherals",
                icon = VoidLinkIcons.Peripherals,
                expanded = peripheralsExpanded,
                onToggle = { peripheralsExpanded = !peripheralsExpanded },
            ) {
                PeripheralRows(rows)
            }
            Box(modifier = Modifier.height(spacing.xxl))
        }
    }
}

/** Header row: sidebar toggle, "Settings" title, overflow menu. */
@Composable
private fun SidebarHeader(
    onClose: () -> Unit,
    onResetDefaults: () -> Unit,
    overrideHostName: String?,
    onEditGlobal: () -> Unit,
    choosingFavorites: Boolean,
    onToggleChoosingFavorites: () -> Unit,
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
                    text = {
                        Text(
                            text = if (choosingFavorites) {
                                "Done choosing favorites"
                            } else {
                                "Add to favorites…"
                            },
                            style = VoidLinkTheme.body,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onToggleChoosingFavorites()
                    },
                )
                if (overrideHostName != null) {
                    DropdownMenuItem(
                        text = { Text("Edit global settings", style = VoidLinkTheme.body) },
                        onClick = {
                            overflowOpen = false
                            onEditGlobal()
                        },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (overrideHostName != null) {
                                "Reset this host's overrides"
                            } else {
                                "Reset all settings"
                            },
                            style = VoidLinkTheme.body,
                            color = VoidLinkTheme.colors.destructive,
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        onResetDefaults()
                    },
                )
            }
        }
    }
}

/** The chip that makes it unmistakable that edits here apply to one host only. */
@Composable
private fun OverrideBanner(hostName: String) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = colors.accentFill,
                    shape = RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius),
                )
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        ) {
            Text(
                text = "Overrides for $hostName",
                style = VoidLinkTheme.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Row plumbing
// ---------------------------------------------------------------------------------------------

/**
 * Everything a settings row needs in order to draw itself, bundled so that adding a row is one
 * call rather than six threaded parameters.
 *
 * [include] is what lets the same row functions render twice: once for their own section, and once
 * — filtered to the starred ids — inside the Favorites section at the top of the panel.
 */
@Immutable
private data class RowContext(
    val settings: StreamSettings,
    val onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    val include: (String) -> Boolean,
    val choosingFavorites: Boolean,
    val onToggleFavorite: (String) -> Unit,
)

/**
 * Emits one row, unless the current filter excludes it, adding the favourite star while the panel
 * is in "choose favourites" mode.
 */
@Composable
private fun RowContext.Slot(id: String, row: @Composable () -> Unit) {
    if (!include(id)) return
    if (!choosingFavorites) {
        row()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FavoriteToggle(
            favorite = id in settings.favoriteRowIds,
            onToggle = { onToggleFavorite(id) },
            modifier = Modifier.padding(start = VoidLinkTheme.spacing.sm),
        )
        Box(modifier = Modifier.weight(1f)) { row() }
    }
}

// ---------------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------------

/** Bitrate, resolution, frame rate, codec, HDR, chroma and the two host-side video switches. */
@Composable
private fun VideoRows(rows: RowContext) {
    val settings = rows.settings
    val onUpdate = rows.onUpdate

    rows.Slot(ROW_BITRATE) {
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
    }
    rows.Slot(ROW_RESOLUTION) {
        NavigationRow(
            label = "Resolution",
            options = StreamResolution.ordered.map { it.label },
            selectedIndex = StreamResolution.ordered.indexOf(settings.resolution),
            onSelect = { index -> onUpdate { it.copy(resolution = StreamResolution.ordered[index]) } },
            info = "The size of the desktop the host renders for you. Native matches this " +
                "device's own screen. NVIDIA hosts may clamp this to a size the GPU driver " +
                "supports when Optimize Game Settings is on.",
        )
    }
    rows.Slot(ROW_FRAME_RATE) {
        SegmentedRow(
            label = "Frame Rate",
            options = FrameRate.ordered.map { it.label },
            selectedIndex = FrameRate.ordered.indexOf(settings.frameRate),
            onSelect = { index -> onUpdate { it.copy(frameRate = FrameRate.ordered[index]) } },
            info = "Frames per second requested from the host. Values above this display's own " +
                "refresh rate cost bandwidth without ever being shown.",
        )
    }
    rows.Slot(ROW_CODEC) {
        SegmentedRow(
            label = "Preferred Codec",
            options = VideoCodec.ordered.map { it.label },
            selectedIndex = VideoCodec.ordered.indexOf(settings.codec),
            onSelect = { index -> onUpdate { it.copy(codec = VideoCodec.ordered[index]) } },
            info = "HEVC and AV1 look better at the same bitrate but need a capable decoder on " +
                "both ends. Auto negotiates the best codec both machines support. AV1 hardware " +
                "decoding is unreliable on many Android devices, so Auto avoids it unless asked.",
        )
    }
    rows.Slot(ROW_HDR) {
        ToggleRow(
            label = "HDR",
            checked = settings.hdrEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(hdrEnabled = enabled) } },
            enabled = settings.codec != VideoCodec.H264,
            info = "Streams high dynamic range when the host, the game and this display all " +
                "support it. Unavailable while the codec is fixed to H.264, which has no 10-bit " +
                "profile here.",
        )
    }
    rows.Slot(ROW_YUV444) {
        ToggleRow(
            label = "YUV 4:4:4",
            checked = settings.yuv444Enabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(yuv444Enabled = enabled) } },
            enabled = settings.codec != VideoCodec.H264,
            info = "Sends full colour resolution instead of the usual 4:2:0 subsampling. Text and " +
                "thin UI lines get much sharper, at a significant bitrate cost. Sunshine and " +
                "Apollo hosts only, and unavailable on H.264.",
        )
    }
    rows.Slot(ROW_SOPS) {
        ToggleRow(
            label = "Optimize Game Settings",
            checked = settings.optimizeGameSettings,
            onCheckedChange = { enabled -> onUpdate { it.copy(optimizeGameSettings = enabled) } },
            info = "Lets the host change a game's own resolution and quality settings to match " +
                "the stream. Turn it off if you would rather the game keep the settings you " +
                "chose on the PC itself.",
        )
    }
    rows.Slot(ROW_STATS) {
        ToggleRow(
            label = "Show Stats Overlay",
            checked = settings.showStatsOverlay,
            onCheckedChange = { enabled -> onUpdate { it.copy(showStatsOverlay = enabled) } },
            info = "Draws a small chip over the stream with the live resolution, frame rate, " +
                "bitrate, decode time and packet loss.",
        )
    }
}

/** Channel layout and where the sound comes out. */
@Composable
private fun AudioRows(rows: RowContext) {
    val settings = rows.settings
    val onUpdate = rows.onUpdate

    rows.Slot(ROW_CHANNELS) {
        SegmentedRow(
            label = "Channels",
            options = SurroundMode.ordered.map { it.label },
            selectedIndex = SurroundMode.ordered.indexOf(settings.surroundMode),
            onSelect = { index -> onUpdate { it.copy(surroundMode = SurroundMode.ordered[index]) } },
            disabledOptions = SURROUND_DISABLED,
            info = "Requests a multi-channel mix from the host. 5.1 and 7.1 are greyed out " +
                "because this release decodes stereo only; surround decoding lands with the " +
                "audio work.",
        )
    }
    rows.Slot(ROW_PLAY_AUDIO_ON_PC) {
        ToggleRow(
            label = "Play Audio on PC",
            // Stored as "mute the host" because that is the direction the host protocol takes it;
            // shown the way the user thinks about it.
            checked = !settings.muteHostAudio,
            onCheckedChange = { enabled -> onUpdate { it.copy(muteHostAudio = !enabled) } },
            info = "Keeps the host's own speakers live as well as streaming the sound here. Off " +
                "by default, because a PC in another room playing the game you are streaming is " +
                "rarely what you want.",
        )
    }
}

/** Touch translation, on-screen widgets, controller emulation and gyro. */
@Composable
private fun TouchRows(rows: RowContext) {
    val settings = rows.settings
    val onUpdate = rows.onUpdate

    rows.Slot(ROW_TOUCH_MODE) {
        SegmentedRow(
            label = "Touch Mode",
            options = TouchMode.ordered.map { it.label },
            selectedIndex = TouchMode.ordered.indexOf(settings.touchMode),
            onSelect = { index -> onUpdate { it.copy(touchMode = TouchMode.ordered[index]) } },
            info = "Touchpad moves the host cursor relatively, like a laptop trackpad. Native " +
                "Touch forwards real touch events and needs a Sunshine or Apollo host. Absolute " +
                "Touch maps your finger straight to the host cursor.",
        )
    }
    rows.Slot(ROW_WIDGETS_ENABLED) {
        ToggleRow(
            label = "Enable On-Screen Widget & Peripherals",
            checked = settings.onScreenWidgetEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(onScreenWidgetEnabled = enabled) } },
            info = "Master switch for the overlay that carries the on-screen buttons, the " +
                "keyboard toggle and the touch divider.",
        )
    }
    rows.Slot(ROW_DIVIDER_POSITION) {
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
            info = "Where the screen splits into two independent touch regions, measured from " +
                "the left edge. Needs the on-screen widget layer, which is currently off.",
        )
    }
    rows.Slot(ROW_POINTER_VELOCITY) {
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
    }
    rows.Slot(ROW_WIDGET_PRESET) {
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
    }
    rows.Slot(ROW_SWAP_FACE_BUTTONS) {
        ToggleRow(
            label = "Swap A/B X/Y Buttons",
            checked = settings.swapFaceButtons,
            onCheckedChange = { enabled -> onUpdate { it.copy(swapFaceButtons = enabled) } },
            info = "Matches Nintendo-style pads, where the physical positions of A/B and X/Y are " +
                "the other way round.",
        )
    }
    rows.Slot(ROW_CONTROLLER_TYPE) {
        SegmentedRow(
            label = "Emulated Controller Type",
            options = EmulatedControllerType.ordered.map { it.label },
            selectedIndex = EmulatedControllerType.ordered.indexOf(settings.emulatedControllerType),
            onSelect = { index ->
                onUpdate { it.copy(emulatedControllerType = EmulatedControllerType.ordered[index]) }
            },
            info = "Which virtual gamepad the host presents to games. DS4 exposes a touchpad and " +
                "motion, which is what gyro aiming needs; Both is useful for titles that only " +
                "detect one of them. Sunshine and Apollo hosts only.",
        )
    }
    rows.Slot(ROW_CONTROLLER_COUNT) {
        StepperRow(
            label = "Emulated Controllers",
            value = settings.emulatedControllerCount,
            valueText = settings.emulatedControllerCount.toString(),
            onValueChange = { count -> onUpdate { it.copy(emulatedControllerCount = count) } },
            range = StreamSettings.CONTROLLERS_MIN..StreamSettings.CONTROLLERS_MAX,
            info = "How many pads the host exposes to the game. Raise it for local multiplayer; " +
                "four is the limit XInput itself imposes.",
        )
    }
    rows.Slot(ROW_GYRO_MODE) {
        SegmentedRow(
            label = "Gyro Mode",
            options = GyroMode.ordered.map { it.label },
            selectedIndex = GyroMode.ordered.indexOf(settings.gyroMode),
            onSelect = { index -> onUpdate { it.copy(gyroMode = GyroMode.ordered[index]) } },
            info = "Where motion data comes from. Built-in uses this device's own gyroscope; " +
                "Controller uses a connected pad's; Auto prefers the controller when present. " +
                "The host must be emulating a DS4 for a game to see it.",
        )
    }
    rows.Slot(ROW_GYRO_SENSITIVITY) {
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
    }
    rows.Slot(ROW_RUMBLE) {
        ToggleRow(
            label = "Rumble",
            checked = settings.rumbleEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(rumbleEnabled = enabled) } },
            info = "Passes the host's force-feedback events to a connected controller, or to this " +
                "device's own vibrator when the pad has no motors.",
        )
    }
}

/** Which gestures are recognised and what they do. */
@Composable
private fun GestureRows(rows: RowContext) {
    val settings = rows.settings
    val onUpdate = rows.onUpdate

    rows.Slot(ROW_EXIT_GESTURE) {
        SegmentedRow(
            label = "Exit Gesture",
            options = ExitGesture.ordered.map { it.label },
            selectedIndex = ExitGesture.ordered.indexOf(settings.exitGesture),
            onSelect = { index -> onUpdate { it.copy(exitGesture = ExitGesture.ordered[index]) } },
            info = "How many fingers must swipe down together to leave the stream. Pick four if a " +
                "game you play uses three-finger gestures of its own.",
        )
    }
    rows.Slot(ROW_EXIT_SWIPE_DISTANCE) {
        SliderRow(
            label = "Exit Swipe Distance",
            value = settings.exitSwipeDistanceDp.toFloat(),
            range = StreamSettings.EXIT_SWIPE_MIN_DP.toFloat()..StreamSettings.EXIT_SWIPE_MAX_DP.toFloat(),
            format = { raw -> SettingsFormat.distanceDp(raw.roundToInt()) },
            quantize = { raw -> snapTo(raw, EXIT_SWIPE_STEP_DP) },
            onCommit = { chosen ->
                onUpdate { current -> current.copy(exitSwipeDistanceDp = chosen.roundToInt()) }
            },
            info = "How far the exit swipe has to travel before it counts. Short distances are " +
                "quicker but easier to trigger by accident in the middle of a game.",
        )
    }
    rows.Slot(ROW_TAP_TO_CLICK) {
        ToggleRow(
            label = "Tap to Click",
            checked = settings.tapToClick,
            onCheckedChange = { enabled -> onUpdate { it.copy(tapToClick = enabled) } },
            enabled = settings.touchMode == TouchMode.TOUCHPAD,
            info = "A single-finger tap sends a left click. Only Touchpad mode interprets taps; " +
                "the other touch modes forward them to the host untouched.",
        )
    }
    rows.Slot(ROW_TWO_FINGER_TAP) {
        ToggleRow(
            label = "Two-Finger Tap = Right Click",
            checked = settings.twoFingerTapRightClick,
            onCheckedChange = { enabled -> onUpdate { it.copy(twoFingerTapRightClick = enabled) } },
            enabled = settings.touchMode == TouchMode.TOUCHPAD,
            info = "The trackpad convention for a right click. Touchpad mode only.",
        )
    }
    rows.Slot(ROW_THREE_FINGER_TAP) {
        ToggleRow(
            label = "Three-Finger Tap = Middle Click",
            checked = settings.threeFingerTapMiddleClick,
            onCheckedChange = { enabled ->
                onUpdate { it.copy(threeFingerTapMiddleClick = enabled) }
            },
            enabled = settings.touchMode == TouchMode.TOUCHPAD && !settings.threeFingerTapEnabled,
            info = "Sends a middle click. Unavailable while the three-finger tap is bound to an " +
                "app action below — one gesture cannot do both.",
        )
    }
    rows.Slot(ROW_THREE_FINGER_ACTION_ENABLED) {
        ToggleRow(
            label = "Three-Finger Tap Opens an Action",
            checked = settings.threeFingerTapEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(threeFingerTapEnabled = enabled) } },
            info = "Recognise a simultaneous three-finger tap and use it for the app action " +
                "chosen below, instead of sending it to the host.",
        )
    }
    rows.Slot(ROW_THREE_FINGER_ACTION) {
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
    }
    rows.Slot(ROW_EDGE_SWIPE) {
        ToggleRow(
            label = "Edge Swipe Opens Settings",
            checked = settings.edgeSwipeEnabled,
            onCheckedChange = { enabled -> onUpdate { it.copy(edgeSwipeEnabled = enabled) } },
            info = "A swipe in from the very edge of the screen reveals the in-stream settings " +
                "drawer. Turn it off if a game uses edge swipes of its own.",
        )
    }
    rows.Slot(ROW_EDGE_SWIPE_ACTION) {
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
private fun PeripheralRows(rows: RowContext) {
    val settings = rows.settings
    val onUpdate = rows.onUpdate

    rows.Slot(ROW_EXTERNAL_DISPLAY) {
        SegmentedRow(
            label = "External Display Mode",
            options = ExternalDisplayMode.ordered.map { it.label },
            selectedIndex = ExternalDisplayMode.ordered.indexOf(settings.externalDisplayMode),
            onSelect = { index ->
                onUpdate { it.copy(externalDisplayMode = ExternalDisplayMode.ordered[index]) }
            },
            enabled = false,
            info = "Mirror shows the stream on both screens; Separate Display sends the stream to " +
                "the external screen and keeps the controls here. The whole row is disabled " +
                "because external-display streaming is not implemented in this release.",
        )
    }
    rows.Slot(ROW_CAPTURE_MOUSE) {
        ToggleRow(
            label = "Capture Mouse",
            checked = settings.captureMouse,
            onCheckedChange = { enabled -> onUpdate { it.copy(captureMouse = enabled) } },
            info = "Grabs a connected mouse so the host receives raw relative movement, which is " +
                "what first-person games expect.",
        )
    }
    rows.Slot(ROW_FORWARD_KEYBOARD) {
        ToggleRow(
            label = "Forward Keyboard",
            checked = settings.forwardKeyboard,
            onCheckedChange = { enabled -> onUpdate { it.copy(forwardKeyboard = enabled) } },
            info = "Sends physical keyboard input, including modifier chords, to the host instead " +
                "of handling it locally.",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Row identity
// ---------------------------------------------------------------------------------------------

// Stable ids, persisted in StreamSettings.favoriteRowIds. Never renamed: a change here silently
// unstars whatever the user had chosen.
private const val ROW_BITRATE = "video.bitrate"
private const val ROW_RESOLUTION = "video.resolution"
private const val ROW_FRAME_RATE = "video.frameRate"
private const val ROW_CODEC = "video.codec"
private const val ROW_HDR = "video.hdr"
private const val ROW_YUV444 = "video.yuv444"
private const val ROW_SOPS = "video.optimizeGameSettings"
private const val ROW_STATS = "video.showStats"
private const val ROW_CHANNELS = "audio.channels"
private const val ROW_PLAY_AUDIO_ON_PC = "audio.playOnHost"
private const val ROW_TOUCH_MODE = "touch.mode"
private const val ROW_WIDGETS_ENABLED = "touch.widgetsEnabled"
private const val ROW_DIVIDER_POSITION = "touch.dividerPosition"
private const val ROW_POINTER_VELOCITY = "touch.pointerVelocity"
private const val ROW_WIDGET_PRESET = "touch.widgetPreset"
private const val ROW_SWAP_FACE_BUTTONS = "touch.swapFaceButtons"
private const val ROW_CONTROLLER_TYPE = "touch.controllerType"
private const val ROW_CONTROLLER_COUNT = "touch.controllerCount"
private const val ROW_GYRO_MODE = "touch.gyroMode"
private const val ROW_GYRO_SENSITIVITY = "touch.gyroSensitivity"
private const val ROW_RUMBLE = "touch.rumble"
private const val ROW_EXIT_GESTURE = "gestures.exitGesture"
private const val ROW_EXIT_SWIPE_DISTANCE = "gestures.exitSwipeDistance"
private const val ROW_TAP_TO_CLICK = "gestures.tapToClick"
private const val ROW_TWO_FINGER_TAP = "gestures.twoFingerTap"
private const val ROW_THREE_FINGER_TAP = "gestures.threeFingerTap"
private const val ROW_THREE_FINGER_ACTION_ENABLED = "gestures.threeFingerActionEnabled"
private const val ROW_THREE_FINGER_ACTION = "gestures.threeFingerAction"
private const val ROW_EDGE_SWIPE = "gestures.edgeSwipe"
private const val ROW_EDGE_SWIPE_ACTION = "gestures.edgeSwipeAction"
private const val ROW_EXTERNAL_DISPLAY = "peripherals.externalDisplay"
private const val ROW_CAPTURE_MOUSE = "peripherals.captureMouse"
private const val ROW_FORWARD_KEYBOARD = "peripherals.forwardKeyboard"

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

/** The exit swipe snaps to 10dp stops; finer than that is not a distance anyone can feel. */
private const val EXIT_SWIPE_STEP_DP = 10

/** Snaps a raw slider position to the nearest multiple of [step]. */
private fun snapTo(raw: Float, step: Int): Float = ((raw / step).roundToInt() * step).toFloat()

@Preview(name = "Settings sidebar", widthDp = 340, heightDp = 900)
@Composable
private fun SettingsSidebarPreview() {
    VoidLinkTheme(darkTheme = false) {
        SettingsSidebar(
            settings = StreamSettings(bitrateKbps = 23_000),
            onUpdate = {},
            onClose = {},
            onResetDefaults = {},
        )
    }
}

@Preview(name = "Settings sidebar — dark", widthDp = 340, heightDp = 900)
@Composable
private fun SettingsSidebarDarkPreview() {
    VoidLinkTheme(darkTheme = true) {
        SettingsSidebar(
            settings = StreamSettings(bitrateKbps = 23_000),
            onUpdate = {},
            onClose = {},
            onResetDefaults = {},
        )
    }
}

/** Every row disabled, so the muted state is reviewable without driving the real settings there. */
@Preview(name = "Settings sidebar — disabled controls", widthDp = 340, heightDp = 900)
@Composable
private fun SettingsSidebarDisabledPreview() {
    VoidLinkTheme(darkTheme = false) {
        SettingsSidebar(
            settings = StreamSettings(
                codec = VideoCodec.H264,
                onScreenWidgetEnabled = false,
                touchMode = TouchMode.ABSOLUTE_TOUCH,
                gyroMode = GyroMode.OFF,
                threeFingerTapEnabled = false,
                edgeSwipeEnabled = false,
            ),
            onUpdate = {},
            onClose = {},
            onResetDefaults = {},
        )
    }
}

/** The per-host override scope, with its banner and its starred rows pinned to the top. */
@Preview(name = "Settings sidebar — host overrides", widthDp = 340, heightDp = 900)
@Composable
private fun SettingsSidebarOverridePreview() {
    VoidLinkTheme(darkTheme = false) {
        SettingsSidebar(
            settings = StreamSettings(
                bitrateKbps = 45_000,
                favoriteRowIds = setOf(ROW_BITRATE, ROW_TOUCH_MODE),
            ),
            onUpdate = {},
            onClose = {},
            onResetDefaults = {},
            overrideHostName = "BATTLESTATION",
        )
    }
}
