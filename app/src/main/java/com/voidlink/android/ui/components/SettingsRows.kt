package com.voidlink.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.voidlink.android.ui.theme.VoidLinkShapeTokens
import com.voidlink.android.ui.theme.VoidLinkTheme

/**
 * A collapsible group of settings rows.
 *
 * Renders as a header row — leading glyph, title, trailing chevron that rotates from "pointing
 * right" when collapsed to "pointing down" when expanded — over an animated body.
 *
 * @param title section name, e.g. "Video".
 * @param icon leading glyph for the section.
 * @param expanded whether the body is visible.
 * @param onToggle invoked when the header is tapped.
 * @param modifier layout modifier.
 * @param content the section's rows.
 */
@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "sectionChevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(spacing.md))
            Text(
                text = title,
                // Section headers are heavier than a row label but must not compete with the
                // panel's own "Settings" title, so weight rather than size carries the emphasis.
                style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.secondaryLabel,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
        HairlineDivider(modifier = Modifier.padding(start = spacing.lg))
    }
}

/**
 * A labelled slider: label on the left, the live value in accent blue on the right, the track
 * underneath.
 *
 * The value label tracks the finger continuously, but the setting is persisted **once, on drag
 * end**. Persisting on every change event would put a DataStore commit on every animation frame,
 * and because the committed value is what feeds [value] back in, the thumb would visibly chase the
 * round trip instead of following the finger.
 *
 * @param label row label.
 * @param value the currently persisted value.
 * @param range legal values.
 * @param format renders a value for display, e.g. `"23.0 Mbps"` or `"| 50% | 50% |"`. Called with
 *   the live drag value, so the label updates while dragging.
 * @param onCommit invoked once, at the end of a drag, with the value to persist.
 * @param modifier layout modifier.
 * @param quantize snaps a raw slider position to the granularity the setting stores; the default
 *   keeps the raw value. Applied during the drag so the label never shows a value that will not be
 *   the one saved.
 * @param enabled whether the control accepts input.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    quantize: (Float) -> Float = { it },
    enabled: Boolean = true,
    info: String? = null,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var infoRevealed by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf<Float?>(null) }

    val committed = value.coerceIn(range.start, range.endInclusive)
    val shown = dragValue ?: committed

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = VoidLinkTheme.body,
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = format(shown),
                style = VoidLinkTheme.body,
                color = colors.accent,
                maxLines = 1,
            )
            if (info != null) {
                Spacer(modifier = Modifier.width(spacing.xs))
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
            }
        }
        Slider(
            value = shown,
            onValueChange = { raw ->
                dragValue = quantize(raw).coerceIn(range.start, range.endInclusive)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            valueRange = range,
            onValueChangeFinished = {
                val pending = dragValue
                dragValue = null
                if (pending != null && pending != committed) onCommit(pending)
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.fill,
                disabledThumbColor = Color.White,
                disabledActiveTrackColor = colors.accent,
                disabledInactiveTrackColor = colors.fill,
            ),
        )
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/**
 * An iOS-style segmented control with a leading label.
 *
 * The track is a rounded grey pill; the selected segment is a solid accent pill with white text;
 * unselected segments carry the primary label color and are separated by hairlines that vanish
 * next to the selection. When [enabled] is false the whole row is visibly muted and inert.
 *
 * @param label row label.
 * @param options segment titles, left to right.
 * @param selectedIndex index of the active segment.
 * @param onSelect invoked with the tapped index.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 * @param disabledOptions indices the host or this device cannot offer. They stay visible and
 *   readable but inert — a capability the user cannot have should never simply vanish.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 */
@Composable
fun SegmentedRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledOptions: Set<Int> = emptySet(),
    info: String? = null,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var infoRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = VoidLinkTheme.body,
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (info != null) {
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
            }
        }
        Spacer(modifier = Modifier.height(spacing.sm))
        SegmentedControl(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            enabled = enabled,
            disabledOptions = disabledOptions,
        )
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/**
 * The bare segmented control, without a label — useful inside denser layouts.
 *
 * Up to [MAX_EQUAL_SEGMENTS] options share the width equally and the selection is a single pill
 * that **slides** between them. Past that the labels stop fitting in a 340dp panel, so the control
 * becomes horizontally scrollable with content-sized segments; it never wraps to a second line.
 *
 * @param options segment titles, left to right.
 * @param selectedIndex index of the active segment.
 * @param onSelect invoked with the tapped index.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 * @param disabledOptions indices that are shown but not selectable.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledOptions: Set<Int> = emptySet(),
) {
    if (options.isEmpty()) return
    if (options.size > MAX_EQUAL_SEGMENTS) {
        ScrollingSegmentedControl(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = modifier,
            enabled = enabled,
            disabledOptions = disabledOptions,
        )
        return
    }

    val colors = VoidLinkTheme.colors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentTrackRadius))
            .background(colors.fill)
            .padding(SEGMENT_TRACK_INSET),
    ) {
        // Segments are given an explicit width rather than a weight so the sliding pill and the
        // labels are laid out from exactly the same arithmetic and cannot drift apart.
        val separatorCount = options.size - 1
        val trackWidth = maxWidth
        val segmentWidth =
            (trackWidth - VoidLinkShapeTokens.Hairline * separatorCount) / options.size
        val thumbOffset by animateDpAsState(
            targetValue = (segmentWidth + VoidLinkShapeTokens.Hairline) * selectedIndex,
            label = "segmentThumb",
        )

        if (selectedIndex in options.indices) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(segmentWidth)
                    .height(SEGMENT_HEIGHT)
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                    .background(colors.accent),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { index, option ->
                if (index > 0) {
                    // A hairline sits between two unselected neighbours only; next to the pill it
                    // would read as a stray line, so it is drawn transparent there.
                    val touchesSelection = index == selectedIndex || index - 1 == selectedIndex
                    Box(
                        modifier = Modifier
                            .width(VoidLinkShapeTokens.Hairline)
                            .height(18.dp)
                            .background(
                                if (touchesSelection) Color.Transparent else colors.separator,
                            ),
                    )
                }
                SegmentLabel(
                    text = option,
                    selected = index == selectedIndex,
                    enabled = enabled && index !in disabledOptions,
                    onClick = { onSelect(index) },
                    modifier = Modifier.width(segmentWidth),
                )
            }
        }
    }
}

/** The overflow form: content-sized segments on a horizontally scrollable track. */
@Composable
private fun ScrollingSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledOptions: Set<Int> = emptySet(),
) {
    val colors = VoidLinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentTrackRadius))
            .background(colors.fill)
            .horizontalScroll(rememberScrollState())
            .padding(SEGMENT_TRACK_INSET),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                    .background(if (selected) colors.accent else Color.Transparent),
            ) {
                SegmentLabel(
                    text = option,
                    selected = selected,
                    enabled = enabled && index !in disabledOptions,
                    onClick = { onSelect(index) },
                    modifier = Modifier.widthIn(min = 64.dp),
                )
            }
        }
    }
}

/** One segment's tappable label. The pill behind it is drawn by the caller. */
@Composable
private fun SegmentLabel(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    Box(
        modifier = modifier
            .height(SEGMENT_HEIGHT)
            .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = VoidLinkTheme.body.copy(fontSize = SEGMENT_FONT_SIZE),
            color = when {
                selected -> Color.White
                !enabled -> colors.tertiaryLabel
                else -> colors.secondaryLabel
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A label plus a Material switch, tinted with the app accent when on.
 *
 * @param label row label.
 * @param checked switch state.
 * @param onCheckedChange invoked with the new state.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    info: String? = null,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var infoRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = VoidLinkTheme.body,
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (info != null) {
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
                Spacer(modifier = Modifier.width(spacing.sm))
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.accent,
                    checkedBorderColor = colors.accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = colors.fill,
                    uncheckedBorderColor = colors.separator,
                ),
            )
        }
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/**
 * A row whose value is a button rather than a setting: it *does* something instead of storing
 * something.
 *
 * Kept to the same shape as every other row — label on the left, the action in accent blue on the
 * right — so a section can mix "change this" and "run this" without looking assembled from two
 * different kits.
 *
 * @param label row label.
 * @param actionLabel text on the button.
 * @param onClick invoked when the button is tapped.
 * @param modifier layout modifier.
 * @param enabled whether the button accepts input. A disabled action keeps its info button live,
 *   which is where the reason it is unavailable has to be written.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 */
@Composable
fun ActionRow(
    label: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    info: String? = null,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var infoRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = VoidLinkTheme.body,
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (info != null) {
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
                Spacer(modifier = Modifier.width(spacing.xs))
            }
            Text(
                text = actionLabel,
                style = VoidLinkTheme.body.copy(fontSize = SEGMENT_FONT_SIZE),
                color = colors.accent,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                    .background(colors.accentFill)
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/**
 * A label plus a dropdown picker — used where a segmented control would have too many options,
 * such as gesture action bindings.
 *
 * @param label row label.
 * @param options option titles.
 * @param selectedIndex index of the current option.
 * @param onSelect invoked with the chosen index.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 */
@Composable
fun PickerRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    info: String? = null,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var infoRevealed by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedLabel = options.getOrNull(selectedIndex).orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = VoidLinkTheme.body,
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (info != null) {
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
                Spacer(modifier = Modifier.width(spacing.xs))
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                        .background(colors.fill)
                        .clickable(enabled = enabled) { menuOpen = true }
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedLabel,
                        style = VoidLinkTheme.body.copy(fontSize = SEGMENT_FONT_SIZE),
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.secondaryLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    options.forEachIndexed { index, option ->
                        val chosen = index == selectedIndex
                        DropdownMenuItem(
                            text = { Text(text = option, style = VoidLinkTheme.body) },
                            onClick = {
                                menuOpen = false
                                onSelect(index)
                            },
                            trailingIcon = {
                                if (chosen) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = colors.accent,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/**
 * A label, the value in accent blue, and a −/+ pair for small integers.
 *
 * Used where a slider would be absurd — a count of virtual controllers has four legal values, and
 * dragging a 4-stop slider is worse than tapping a plus.
 *
 * @param label row label.
 * @param value current value.
 * @param valueText the value rendered for display.
 * @param onValueChange invoked with the new value; already clamped to [range].
 * @param range legal values.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 */
@Composable
fun StepperRow(
    label: String,
    value: Int,
    valueText: String,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    info: String? = null,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var infoRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = VoidLinkTheme.body,
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = valueText,
                style = VoidLinkTheme.body,
                color = colors.accent,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                    .background(colors.fill),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperButton(
                    icon = Icons.Filled.Remove,
                    description = "Decrease $label",
                    enabled = enabled && value > range.first,
                    onClick = { onValueChange((value - 1).coerceIn(range.first, range.last)) },
                )
                Box(
                    modifier = Modifier
                        .width(VoidLinkShapeTokens.Hairline)
                        .height(18.dp)
                        .background(colors.separator),
                )
                StepperButton(
                    icon = Icons.Filled.Add,
                    description = "Increase $label",
                    enabled = enabled && value < range.last,
                    onClick = { onValueChange((value + 1).coerceIn(range.first, range.last)) },
                )
            }
            if (info != null) {
                Spacer(modifier = Modifier.width(spacing.xs))
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
            }
        }
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/** One half of a [StepperRow]'s −/+ pill. */
@Composable
private fun StepperButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = VoidLinkTheme.colors
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 32.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) colors.accent else colors.tertiaryLabel,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * A row that discloses a longer list of choices than a segmented control can carry.
 *
 * The choices expand **inline** beneath the row rather than pushing a sub-screen: the panel is
 * already a drawer, and a drawer that pushes its own navigation stack is a maze.
 *
 * @param label row label.
 * @param options choice titles.
 * @param selectedIndex index of the current choice.
 * @param onSelect invoked with the chosen index.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 */
@Composable
fun NavigationRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    info: String? = null,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var infoRevealed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "navigationRowChevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(vertical = spacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = !expanded }
                .padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = VoidLinkTheme.body,
                color = colors.label,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = options.getOrNull(selectedIndex).orEmpty(),
                style = VoidLinkTheme.body,
                color = colors.accent,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier
                    .padding(start = spacing.xs)
                    .size(20.dp)
                    .rotate(chevronRotation),
            )
            if (info != null) {
                Spacer(modifier = Modifier.width(spacing.xs))
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    val chosen = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) {
                                expanded = false
                                if (!chosen) onSelect(index)
                            }
                            .padding(
                                start = spacing.xxl,
                                end = spacing.lg,
                                top = spacing.sm,
                                bottom = spacing.sm,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option,
                            style = VoidLinkTheme.body,
                            color = if (chosen) colors.accent else colors.label,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (chosen) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/**
 * The star a row grows while the panel is in "choose favourites" mode.
 *
 * @param favorite whether this row is already starred.
 * @param onToggle invoked when the star is tapped.
 * @param modifier layout modifier.
 */
@Composable
fun FavoriteToggle(
    favorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    Box(
        modifier = modifier
            .size(INFO_TOUCH_TARGET)
            .clip(RoundedCornerShape(INFO_TOUCH_TARGET / 2))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            // One glyph in two tints rather than a filled/outlined pair: only the filled star is
            // guaranteed present in every icon theme, and tint alone reads the state clearly.
            imageVector = Icons.Filled.Star,
            contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
            tint = if (favorite) colors.accent else colors.tertiaryLabel,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * A standalone circled-i that reveals [text] in a small popover when tapped.
 *
 * Rows normally reveal their help text inline via their own `info` parameter; this composable is
 * for the places that need an info affordance on its own, such as a screen header.
 *
 * @param text the help text to show.
 * @param modifier layout modifier.
 */
@Composable
fun InfoButton(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        InfoToggleGlyph(expanded = open, onClick = { open = !open })
        if (open) {
            Popup(
                alignment = Alignment.BottomEnd,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius),
                    color = colors.card,
                    contentColor = colors.label,
                    shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text(
                        text = text,
                        style = VoidLinkTheme.footnote,
                        color = colors.secondaryLabel,
                        modifier = Modifier.padding(spacing.md),
                    )
                }
            }
        }
    }
}

/**
 * The circled-i glyph itself, tinted when its help text is showing.
 *
 * @param expanded whether the associated help text is currently visible.
 * @param onClick tap handler.
 * @param modifier layout modifier.
 */
@Composable
fun InfoToggleGlyph(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    Box(
        // 44dp is the smallest target the design brief allows; the glyph itself stays 20dp.
        modifier = modifier
            .size(INFO_TOUCH_TARGET)
            .clip(RoundedCornerShape(INFO_TOUCH_TARGET / 2))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "More information",
            tint = if (expanded) colors.accent else colors.tertiaryLabel,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The inline footnote a row reveals when its circled-i is tapped.
 *
 * @param info the help text, or `null` when the row has none.
 * @param visible whether the text is currently revealed.
 */
@Composable
private fun InlineInfoText(info: String?, visible: Boolean) {
    if (info == null) return
    val spacing = VoidLinkTheme.spacing
    AnimatedVisibility(visible = visible) {
        Text(
            text = info,
            style = VoidLinkTheme.footnote,
            color = VoidLinkTheme.colors.secondaryLabel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.xs, bottom = spacing.xs),
        )
    }
}

/** Opacity applied to a row that is present but currently inapplicable. */
private const val DISABLED_ALPHA = 0.4f

/** Minimum touch target for the circled-i, per the accessibility rules in the UI spec. */
private val INFO_TOUCH_TARGET = 44.dp

/** Beyond this many options the labels stop fitting a 340dp panel and the track starts scrolling. */
private const val MAX_EQUAL_SEGMENTS = 4

/** Padding between the segmented track's edge and its pills. */
private val SEGMENT_TRACK_INSET = 2.dp

/** Height of a segment, and therefore of the sliding pill behind it. */
private val SEGMENT_HEIGHT = 32.dp

/** Segment and picker text is a notch smaller than body text so long labels still fit. */
private val SEGMENT_FONT_SIZE = 14.sp
