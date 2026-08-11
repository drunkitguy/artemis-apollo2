package com.voidlink.android.ui.apps

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voidlink.android.data.HostApp
import com.voidlink.android.data.KnownHost
import com.voidlink.android.ui.components.ScreenHeader
import com.voidlink.android.ui.components.VoidLinkIcons
import com.voidlink.android.ui.theme.VoidLinkShapeTokens
import com.voidlink.android.ui.theme.VoidLinkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimum width of a box-art tile before the grid adds another column. */
private val AppTileMinWidth = 160.dp

/** Box art is portrait 3:4, the shape hosts publish it in. */
private const val BOX_ART_ASPECT_RATIO = 3f / 4f

/**
 * The Apps screen: a host's library as a grid of portrait box-art tiles.
 *
 * Purely presentational; the caller supplies state and receives intents.
 *
 * @param state what to draw.
 * @param onToggleSidebar opens the settings panel.
 * @param onBack returns to the Hosts screen.
 * @param onLaunch the user tapped a tile — start streaming that app.
 * @param onQuitRunning the user asked to stop the app currently running on the host.
 * @param onExternalDisplay the display button in the header was tapped.
 * @param modifier layout modifier.
 */
@Composable
fun AppsScreen(
    state: AppsUiState,
    onToggleSidebar: () -> Unit,
    onBack: () -> Unit,
    onLaunch: (HostApp) -> Unit,
    onQuitRunning: () -> Unit,
    onExternalDisplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ScreenHeader(
            title = state.title,
            centered = false,
            leading = {
                IconButton(onClick = onToggleSidebar) {
                    Icon(
                        imageVector = VoidLinkIcons.Sidebar,
                        contentDescription = "Show settings",
                        tint = colors.accent,
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = VoidLinkIcons.Host,
                        contentDescription = "Back to hosts",
                        tint = colors.accent,
                    )
                }
            },
            trailing = {
                IconButton(onClick = onExternalDisplay) {
                    Icon(
                        imageVector = VoidLinkIcons.Display,
                        contentDescription = "Display options",
                        tint = colors.accent,
                    )
                }
            },
        )

        Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = Color.Transparent,
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = VoidLinkTheme.footnote,
                color = colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs),
            )
        }

        if (state.runningAppId != null) {
            RunningAppBanner(
                appName = state.apps.firstOrNull { it.id == state.runningAppId }?.name.orEmpty(),
                onQuit = onQuitRunning,
                modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
            )
        }

        if (state.isEmpty) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No apps to show yet.",
                    style = VoidLinkTheme.body,
                    color = colors.secondaryLabel,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = AppTileMinWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                items(items = state.apps, key = { it.id }) { app ->
                    AppTile(
                        app = app,
                        running = app.id == state.runningAppId,
                        onClick = { onLaunch(app) },
                        onQuit = onQuitRunning,
                    )
                }
            }
        }
    }
}

/**
 * A single portrait box-art tile with the app name over a translucent bottom scrim.
 *
 * When the host has no art for the app, a generated placeholder carries the app's initial so the
 * grid never shows an empty rectangle.
 *
 * @param app the app to draw.
 * @param running true when this app is the one currently streaming on the host.
 * @param onClick launch this app.
 * @param onQuit stop the running app; only reachable when [running] is true.
 * @param modifier layout modifier.
 */
@Composable
fun AppTile(
    app: HostApp,
    running: Boolean,
    onClick: () -> Unit,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val boxArt = rememberBoxArt(app.boxArt)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(BOX_ART_ASPECT_RATIO)
            .clip(RoundedCornerShape(VoidLinkShapeTokens.TileRadius))
            .background(colors.fill)
            .clickable(onClick = onClick),
    ) {
        if (boxArt != null) {
            Image(
                bitmap = boxArt,
                contentDescription = app.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PlaceholderArt(app = app)
        }

        // Name plate: a bottom scrim keeps light box art readable without dimming the whole tile.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .padding(horizontal = spacing.md, vertical = spacing.md),
        ) {
            Text(
                text = app.name,
                style = VoidLinkTheme.body,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (running) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.sm)
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                    .background(colors.online)
                    .clickable(onClick = onQuit)
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = VoidLinkIcons.Quit,
                    contentDescription = "Stop running app",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(text = "Running", style = VoidLinkTheme.footnote, color = Color.White)
            }
        }
    }
}

/** The generated tile shown when a host supplies no box art. */
@Composable
private fun PlaceholderArt(app: HostApp) {
    val colors = VoidLinkTheme.colors
    val initial = app.name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.accentFill, colors.fill),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (app.isDesktop) {
            Icon(
                imageVector = VoidLinkIcons.Host,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(56.dp),
            )
        } else {
            Text(
                text = initial,
                style = VoidLinkTheme.largeTitle,
                color = colors.accent,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A banner naming the app currently streaming on the host, with a quit action. */
@Composable
private fun RunningAppBanner(
    appName: String,
    onQuit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius))
            .background(colors.accentFill)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(colors.online),
        )
        Spacer(modifier = Modifier.width(spacing.md))
        Text(
            text = if (appName.isEmpty()) "An app is running on this host" else "$appName is running",
            style = VoidLinkTheme.body,
            color = colors.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                .clickable(onClick = onQuit)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = VoidLinkIcons.Quit,
                contentDescription = null,
                tint = colors.destructive,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            Text(text = "Quit", style = VoidLinkTheme.body, color = colors.destructive)
        }
    }
}

/**
 * Decodes encoded box-art bytes off the main thread and caches the result for the composition.
 *
 * Returns `null` while decoding, when there are no bytes, or when the bytes are not a decodable
 * image — callers fall back to the generated placeholder in all three cases.
 *
 * @param bytes the encoded image the host supplied, or `null`.
 */
@Composable
fun rememberBoxArt(bytes: ByteArray?): ImageBitmap? {
    val decoded = produceState<ImageBitmap?>(initialValue = null, key1 = bytes) {
        value = if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return decoded.value
}

@Preview(name = "Apps", widthDp = 720, heightDp = 900)
@Composable
private fun AppsScreenPreview() {
    VoidLinkTheme {
        AppsScreen(
            state = AppsUiState(
                host = KnownHost(uuid = "1", name = "BATTLESTATION"),
                apps = listOf(
                    HostApp(id = "desktop", name = "Desktop", isDesktop = true),
                    HostApp(id = "1", name = "Steam Big Picture"),
                    HostApp(id = "2", name = "Hades II"),
                    HostApp(id = "3", name = "Factorio"),
                ),
                runningAppId = "2",
            ),
            onToggleSidebar = {},
            onBack = {},
            onLaunch = {},
            onQuitRunning = {},
            onExternalDisplay = {},
        )
    }
}
