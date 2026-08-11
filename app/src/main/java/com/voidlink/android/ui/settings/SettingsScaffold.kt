package com.voidlink.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.ui.theme.VoidLinkShapeTokens
import com.voidlink.android.ui.theme.VoidLinkTheme

/**
 * Screens at least this wide put the sidebar beside the content instead of over it.
 *
 * 840dp is the expanded window-size class from the UI spec (§1.10): below it a 340dp panel plus a
 * usable host grid do not both fit, so the panel overlays with a scrim instead of splitting.
 */
private val SplitLayoutMinWidth = 840.dp

/**
 * Hosts a screen's content alongside the settings sidebar, choosing the right presentation for the
 * available width.
 *
 * On a tablet or an unfolded device the sidebar splits the screen: content narrows, nothing is
 * covered. On a phone it slides in over the content as a drawer with a dismissing scrim. Callers do
 * not need to know which is happening — they pass content and a toggle flag.
 *
 * @param sidebarOpen whether the panel is showing.
 * @param onDismissSidebar invoked when the user closes the panel (button or scrim tap).
 * @param settings the settings to render in the panel.
 * @param onUpdate invoked with a transform producing the new settings.
 * @param onResetDefaults invoked from the panel's overflow menu.
 * @param modifier layout modifier.
 * @param overrideHostName name of the host whose overrides are being edited, or `null` for global.
 * @param onEditGlobal leaves an override scope and returns to the global settings.
 * @param onToggleFavorite stars or unstars a settings row.
 * @param content the screen behind or beside the panel.
 */
@Composable
fun SettingsScaffold(
    sidebarOpen: Boolean,
    onDismissSidebar: () -> Unit,
    settings: StreamSettings,
    onUpdate: ((StreamSettings) -> StreamSettings) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    overrideHostName: String? = null,
    onEditGlobal: () -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = VoidLinkTheme.colors

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val useSplitLayout = maxWidth >= SplitLayoutMinWidth

        if (useSplitLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = sidebarOpen) {
                    Row {
                        SettingsSidebar(
                            settings = settings,
                            onUpdate = onUpdate,
                            onClose = onDismissSidebar,
                            onResetDefaults = onResetDefaults,
                            overrideHostName = overrideHostName,
                            onEditGlobal = onEditGlobal,
                            onToggleFavorite = onToggleFavorite,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(VoidLinkShapeTokens.Hairline)
                                .background(colors.separator),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    content()
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                AnimatedVisibility(
                    visible = sidebarOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    val scrimInteractionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.scrim)
                            .clickable(
                                interactionSource = scrimInteractionSource,
                                indication = null,
                                onClick = onDismissSidebar,
                            ),
                    )
                }

                AnimatedVisibility(
                    visible = sidebarOpen,
                    enter = slideInHorizontally { fullWidth -> -fullWidth },
                    exit = slideOutHorizontally { fullWidth -> -fullWidth },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Surface(
                        color = colors.card,
                        contentColor = colors.label,
                        shadowElevation = 12.dp,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        SettingsSidebar(
                            settings = settings,
                            onUpdate = onUpdate,
                            onClose = onDismissSidebar,
                            onResetDefaults = onResetDefaults,
                            // On a phone narrower than the panel, take the whole screen rather
                            // than leaving an unusable strip of scrim beside it.
                            width = minOf(SettingsSidebarWidth, maxWidth),
                            overrideHostName = overrideHostName,
                            onEditGlobal = onEditGlobal,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
            }
        }
    }
}
