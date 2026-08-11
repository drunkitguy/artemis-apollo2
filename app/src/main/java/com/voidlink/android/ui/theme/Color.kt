package com.voidlink.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The VoidLink palette.
 *
 * VoidLink deliberately does not look like stock Material: it borrows the calm, high-contrast,
 * mostly-white look of the iOS original. Rather than fight Material 3's tonal palettes for every
 * surface, the app carries its own small, explicit set of semantic colors and reads them through
 * [LocalVoidLinkColors]. [MaterialTheme][androidx.compose.material3.MaterialTheme] is still
 * populated (so stock components such as `Slider` and `Switch` look right by default), but screen
 * code should prefer these tokens.
 */
@Immutable
data class VoidLinkColors(
    /** Page background behind every card. */
    val background: Color,
    /** Card / grouped-row surface. */
    val card: Color,
    /** Slightly recessed surface used for segmented-control tracks and inert fills. */
    val fill: Color,
    /** Tinted fill used behind accent icons and tinted buttons. */
    val accentFill: Color,
    /** Primary accent (links, selected segments, live values). */
    val accent: Color,
    /** Primary text. */
    val label: Color,
    /** Secondary / explanatory text. */
    val secondaryLabel: Color,
    /** Disabled or very low-emphasis text. */
    val tertiaryLabel: Color,
    /** Hairline separator. */
    val separator: Color,
    /** "Online" / success. */
    val online: Color,
    /** "Offline" / warning. */
    val offline: Color,
    /** Destructive actions. */
    val destructive: Color,
    /** Scrim drawn behind the sidebar when it overlays content on phones. */
    val scrim: Color,
    /** True when this palette is the dark one; useful for elevation decisions. */
    val isDark: Boolean,
)

/** Light palette — the primary look of the app. */
val VoidLinkLightColors: VoidLinkColors = VoidLinkColors(
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    fill = Color(0xFFE5E5EA),
    accentFill = Color(0xFFE8F1FE),
    accent = Color(0xFF0A84FF),
    label = Color(0xFF1C1C1E),
    secondaryLabel = Color(0xFF6E6E73),
    tertiaryLabel = Color(0xFFA1A1A6),
    separator = Color(0xFFE5E5EA),
    online = Color(0xFF30D158),
    offline = Color(0xFF8E8E93),
    destructive = Color(0xFFFF3B30),
    scrim = Color(0x66000000),
    isDark = false,
)

/** Dark palette — mirrors the light one token for token. */
val VoidLinkDarkColors: VoidLinkColors = VoidLinkColors(
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    fill = Color(0xFF2C2C2E),
    accentFill = Color(0xFF10294A),
    accent = Color(0xFF0A84FF),
    label = Color(0xFFF2F2F7),
    secondaryLabel = Color(0xFF98989F),
    tertiaryLabel = Color(0xFF68686E),
    separator = Color(0xFF38383A),
    online = Color(0xFF32D74B),
    offline = Color(0xFF8E8E93),
    destructive = Color(0xFFFF453A),
    scrim = Color(0x99000000),
    isDark = true,
)

/**
 * Palette lookup for composables. Defaults to the light palette so that a preview or a stray
 * composable rendered outside [VoidLinkTheme] still draws something sensible.
 */
val LocalVoidLinkColors = staticCompositionLocalOf { VoidLinkLightColors }
