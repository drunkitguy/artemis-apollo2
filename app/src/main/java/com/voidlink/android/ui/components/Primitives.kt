package com.voidlink.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voidlink.android.ui.theme.VoidLinkShapeTokens
import com.voidlink.android.ui.theme.VoidLinkTheme

/**
 * A hairline separator in the theme's separator color.
 *
 * Material's own `HorizontalDivider` is 1dp of `outlineVariant`, which is heavier than the iOS-like
 * hairline this design wants, so VoidLink draws its own.
 *
 * @param modifier layout modifier, typically carrying horizontal padding for an inset rule.
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(VoidLinkShapeTokens.Hairline)
            .background(VoidLinkTheme.colors.separator),
    )
}

/**
 * The app's standard content card: pure white (or near-black in dark mode), 20dp corners, and a
 * soft 2dp shadow in light mode only.
 *
 * @param modifier layout modifier.
 * @param content card body, laid out in a [Column].
 */
@Composable
fun VoidLinkCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = VoidLinkTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(VoidLinkShapeTokens.CardRadius),
        color = colors.card,
        contentColor = colors.label,
        // A shadow under a black card is invisible and only costs a render pass; the card is
        // separated from the background by a hairline outline instead.
        shadowElevation = if (colors.isDark) 0.dp else VoidLinkShapeTokens.CardElevation,
        tonalElevation = 0.dp,
        border = if (colors.isDark) {
            BorderStroke(VoidLinkShapeTokens.Hairline, colors.separator)
        } else {
            null
        },
    ) {
        Column(content = content)
    }
}

/**
 * A rounded-square glyph tile — the icon container used for hosts, section headers and empty
 * states.
 *
 * @param icon the glyph to centre in the tile.
 * @param contentDescription accessibility label, or `null` when the tile is decorative.
 * @param modifier layout modifier.
 * @param size edge length of the square.
 * @param cornerRadius corner rounding.
 * @param backgroundColor tile fill; defaults to the tinted accent fill.
 * @param iconColor glyph tint; defaults to the accent color.
 * @param iconSize glyph size; defaults to roughly half the tile.
 */
@Composable
fun GlyphTile(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    cornerRadius: Dp = VoidLinkShapeTokens.TileRadius,
    backgroundColor: Color = VoidLinkTheme.colors.accentFill,
    iconColor: Color = VoidLinkTheme.colors.accent,
    iconSize: Dp = size / 2,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * A single line of status text preceded by a small colored glyph — "Online" / "Offline".
 *
 * @param icon status glyph.
 * @param text status text.
 * @param tint color applied to both glyph and text.
 * @param modifier layout modifier.
 */
@Composable
fun StatusLine(
    icon: ImageVector,
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs + 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            // The reference sets the status line noticeably smaller than the host name; body size
            // here would compete with the 22sp title directly above it.
            text = text,
            style = VoidLinkTheme.footnote.copy(fontWeight = FontWeight.Medium),
            color = tint,
        )
    }
}

/**
 * The [StatusLine] variant used while a probe is still in flight.
 *
 * A host whose reachability is simply not known yet must not be drawn as offline — that is the
 * first thing a user sees on launch, and a grid of "Offline" cards that turn green a second later
 * reads as a broken app rather than a working one.
 *
 * Marked with a neutral dot rather than a spinner: the dot is drawn from primitives whose API is
 * beyond question, and in a line of 13sp text a 14dp spinner is barely legible motion anyway.
 *
 * @param text status text, e.g. "Checking…".
 * @param tint colour applied to both dot and text.
 * @param modifier layout modifier.
 */
@Composable
fun PendingStatusLine(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs + 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(tint),
        )
        Text(
            text = text,
            style = VoidLinkTheme.footnote.copy(fontWeight = FontWeight.Medium),
            color = tint,
        )
    }
}

/**
 * The full-screen "there is nothing here, and here is why" state.
 *
 * A single line of grey text tells the user that something is wrong but not what to do about it,
 * and the same line for three different causes tells them nothing at all. Every empty state in the
 * app therefore names its cause and offers the action that resolves it.
 *
 * @param icon a large glyph for the situation.
 * @param title one short line naming what happened.
 * @param body a sentence explaining the likely cause.
 * @param modifier layout modifier.
 * @param primaryActionLabel label for the main action, or `null` for none.
 * @param onPrimaryAction invoked by the main action.
 * @param secondaryActionLabel label for a second, lesser action, or `null` for none.
 * @param onSecondaryAction invoked by the second action.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryActionLabel: String? = null,
    onPrimaryAction: () -> Unit = {},
    secondaryActionLabel: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.xl, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.tertiaryLabel,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        Text(
            text = title,
            style = VoidLinkTheme.cardTitle,
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = body,
            style = VoidLinkTheme.body,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        if (primaryActionLabel != null) {
            Spacer(modifier = Modifier.height(spacing.lg))
            Text(
                text = primaryActionLabel,
                style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius))
                    .background(colors.accentFill)
                    .clickable(onClick = onPrimaryAction)
                    .padding(horizontal = spacing.xl, vertical = spacing.md),
            )
        }
        if (secondaryActionLabel != null) {
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = secondaryActionLabel,
                style = VoidLinkTheme.body,
                color = colors.secondaryLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius))
                    .clickable(onClick = onSecondaryAction)
                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
            )
        }
    }
}

/**
 * Space kept clear on both sides of a centred title so it can never sit on top of an accessory.
 *
 * Two 48dp icon buttons is the widest accessory group any screen uses; reserving the same amount on
 * both sides is what keeps the title *optically* centred rather than centred in the leftover space.
 */
private val CenteredTitleGutter: Dp = 96.dp

/**
 * The large centred (or leading) screen title used at the top of Hosts and Apps, with optional
 * leading and trailing accessory slots.
 *
 * When [centered] is true the title is centred **in the header**, not in the space left over
 * between the accessories — the reference centres it regardless of how many buttons flank it, and
 * a title that shifts as buttons appear reads as a bug.
 *
 * @param title the title text.
 * @param modifier layout modifier.
 * @param centered when true the title is centred in the header, as on the Hosts screen.
 * @param leading optional leading accessory, e.g. the sidebar toggle.
 * @param trailing optional trailing accessory, e.g. a refresh or display button.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
    leading: @Composable (RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val spacing = VoidLinkTheme.spacing
    if (centered) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = VoidLinkTheme.largeTitle,
                color = VoidLinkTheme.colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = CenteredTitleGutter),
            )
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                leading?.invoke(this)
            }
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                trailing?.invoke(this)
            }
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            leading?.invoke(this)
        }
        Text(
            // The un-centred variant is a nav-bar title (the app grid's host name), not a screen
            // title: at display size it would dwarf the grid it sits above.
            text = title,
            style = VoidLinkTheme.cardTitle,
            color = VoidLinkTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.sm),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            trailing?.invoke(this)
        }
    }
}
