package com.voidlink.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single spacing scale used across VoidLink.
 *
 * Everything in the UI is laid out from these six steps; hand-rolled magic numbers are what make
 * a settings panel look noisy, so screens should reach for [VoidLinkSpacing] instead.
 */
@Immutable
data class VoidLinkSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
)

/** Spacing lookup for composables; the default is the standard scale. */
val LocalVoidLinkSpacing = staticCompositionLocalOf { VoidLinkSpacing() }

/**
 * Shared corner radii and hairline metrics.
 *
 * These are plain constants rather than a CompositionLocal because they never vary by theme.
 */
object VoidLinkShapeTokens {
    /** Corner radius of the large content cards on the Hosts screen. */
    val CardRadius: Dp = 20.dp

    /** Corner radius of the rounded-square host icon tile and of box-art tiles. */
    val TileRadius: Dp = 16.dp

    /** Corner radius of the segmented-control track. */
    val SegmentTrackRadius: Dp = 9.dp

    /** Corner radius of an individual selected segment pill. */
    val SegmentPillRadius: Dp = 7.dp

    /** Corner radius of inline buttons and popovers. */
    val ButtonRadius: Dp = 12.dp

    /** Thickness of a hairline separator. */
    val Hairline: Dp = 1.dp

    /** Resting elevation of a content card in the light theme. */
    val CardElevation: Dp = 2.dp
}
