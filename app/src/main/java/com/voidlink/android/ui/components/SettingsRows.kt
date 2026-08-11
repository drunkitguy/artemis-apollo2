package com.voidlink.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
                style = VoidLinkTheme.cardTitle,
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
 * @param label row label.
 * @param value current value.
 * @param valueText the value rendered for display, e.g. `"23.0 Mbps"` or `"| 50% | 50% |"`.
 * @param range legal values.
 * @param onValueChange invoked continuously as the user drags.
 * @param modifier layout modifier.
 * @param steps number of discrete stops between the endpoints; `0` means continuous.
 * @param enabled whether the control accepts input.
 * @param info optional help text; the circled-i is rendered only when this is non-null.
 * @param onValueChangeFinished invoked once when the drag gesture ends — the right moment to
 *   persist, so a drag does not write to disk on every frame.
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    info: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
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
            if (info != null) {
                Spacer(modifier = Modifier.width(spacing.xs))
                InfoToggleGlyph(expanded = infoRevealed, onClick = { infoRevealed = !infoRevealed })
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
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
        )
        InlineInfoText(info = info, visible = infoRevealed)
    }
}

/**
 * The bare segmented control, without a label — useful inside denser layouts.
 *
 * @param options segment titles, left to right.
 * @param selectedIndex index of the active segment.
 * @param onSelect invoked with the tapped index.
 * @param modifier layout modifier.
 * @param enabled whether the control accepts input.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = VoidLinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentTrackRadius))
            .background(colors.fill)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                // A hairline sits between two unselected neighbours only; next to the blue pill it
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
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                    .background(if (selected) colors.accent else Color.Transparent)
                    .clickable(enabled = enabled && !selected) { onSelect(index) }
                    .heightIn(min = 30.dp)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = VoidLinkTheme.body.copy(fontSize = SEGMENT_FONT_SIZE),
                    color = if (selected) Color.White else colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A label plus a Material switch, tinted green when on.
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
                    checkedTrackColor = colors.online,
                    checkedBorderColor = colors.online,
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
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "More information",
            tint = if (expanded) colors.accent else colors.secondaryLabel,
            modifier = Modifier.size(19.dp),
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

/** Segment and picker text is a notch smaller than body text so long labels still fit. */
private val SEGMENT_FONT_SIZE = 14.sp
