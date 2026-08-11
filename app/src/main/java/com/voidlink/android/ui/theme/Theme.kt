package com.voidlink.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Root theme for every VoidLink surface.
 *
 * Wraps [MaterialTheme] so that stock Material 3 controls (`Slider`, `Switch`, `DropdownMenu`)
 * inherit the right accent colors, and simultaneously publishes the app's own semantic palette and
 * spacing scale through [LocalVoidLinkColors] / [LocalVoidLinkSpacing]. Read app tokens via the
 * [VoidLinkTheme] accessor object:
 *
 * ```
 * val colors = VoidLinkTheme.colors
 * val spacing = VoidLinkTheme.spacing
 * ```
 *
 * @param darkTheme when true the dark palette is used; defaults to the system setting.
 * @param content the themed content.
 */
@Composable
fun VoidLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) VoidLinkDarkColors else VoidLinkLightColors
    val spacing = remember { VoidLinkSpacing() }

    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            primaryContainer = colors.accentFill,
            onPrimaryContainer = colors.accent,
            secondary = colors.accent,
            onSecondary = Color.White,
            tertiary = colors.online,
            onTertiary = Color.White,
            background = colors.background,
            onBackground = colors.label,
            surface = colors.card,
            onSurface = colors.label,
            surfaceVariant = colors.fill,
            onSurfaceVariant = colors.secondaryLabel,
            outline = colors.separator,
            outlineVariant = colors.separator,
            error = colors.destructive,
            onError = Color.White,
            scrim = Color.Black,
            // Material's elevation overlay would tint every raised white surface blue.
            surfaceTint = Color.Transparent,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            primaryContainer = colors.accentFill,
            onPrimaryContainer = colors.accent,
            secondary = colors.accent,
            onSecondary = Color.White,
            tertiary = colors.online,
            onTertiary = Color.White,
            background = colors.background,
            onBackground = colors.label,
            surface = colors.card,
            onSurface = colors.label,
            surfaceVariant = colors.fill,
            onSurfaceVariant = colors.secondaryLabel,
            outline = colors.separator,
            outlineVariant = colors.separator,
            error = colors.destructive,
            onError = Color.White,
            scrim = Color.Black,
            // Material's elevation overlay would tint every raised white surface blue.
            surfaceTint = Color.Transparent,
        )
    }

    CompositionLocalProvider(
        LocalVoidLinkColors provides colors,
        LocalVoidLinkSpacing provides spacing,
    ) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = VoidLinkTypography,
            shapes = VoidLinkShapes,
            content = content,
        )
    }
}

/** Material 3 shape set aligned with [VoidLinkShapeTokens]. */
private val VoidLinkShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius),
    large = RoundedCornerShape(VoidLinkShapeTokens.TileRadius),
    extraLarge = RoundedCornerShape(VoidLinkShapeTokens.CardRadius),
)

/**
 * Ambient accessor for VoidLink design tokens.
 *
 * Mirrors the `MaterialTheme` object convention so call sites read naturally.
 */
object VoidLinkTheme {
    /** The active semantic palette. */
    val colors: VoidLinkColors
        @Composable @ReadOnlyComposable get() = LocalVoidLinkColors.current

    /** The active spacing scale. */
    val spacing: VoidLinkSpacing
        @Composable @ReadOnlyComposable get() = LocalVoidLinkSpacing.current

    /** Convenience alias for the large-title style (34sp bold). */
    val largeTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineLarge

    /** Convenience alias for the card-title style (22sp bold). */
    val cardTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleLarge

    /** Convenience alias for the body style (17sp). */
    val body: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyLarge

    /** Convenience alias for the footnote style (13sp). */
    val footnote: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall
}
