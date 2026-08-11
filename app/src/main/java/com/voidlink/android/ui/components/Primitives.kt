package com.voidlink.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
 * The [StatusLine] variant used while a probe is still in flight: a small spinner instead of a
 * status glyph.
 *
 * A host whose reachability is simply not known yet must not be drawn as offline — that is the
 * first thing a user sees on launch, and a grid of "Offline" cards that turn green a second later
 * reads as a broken app rather than a working one.
 *
 * @param text status text, e.g. "Checking…".
 * @param tint colour applied to both spinner and text.
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
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = tint,
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = VoidLinkTheme.footnote.copy(fontWeight = FontWeight.Medium),
            color = tint,
        )
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
