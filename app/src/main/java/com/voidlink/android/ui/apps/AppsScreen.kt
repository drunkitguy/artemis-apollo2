package com.voidlink.android.ui.apps

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voidlink.android.data.HostApp
import com.voidlink.android.data.KnownHost
import com.voidlink.android.ui.components.EmptyState
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

/** Share of a tile's height covered by the title scrim. */
private const val TITLE_SCRIM_FRACTION = 0.44f

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
 * @param onDismissMessage clears the transient notice; without it a message set once would sit on
 *   the screen for the rest of the session.
 * @param onRefresh re-reads the host's state and library.
 * @param loadBoxArt fetches one app's box art, called per tile as it comes on screen.
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
    onDismissMessage: () -> Unit,
    onRefresh: () -> Unit,
    loadBoxArt: suspend (HostApp) -> ByteArray?,
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
                        imageVector = VoidLinkIcons.Back,
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
                modifier = Modifier
                    .clickable(onClick = onDismissMessage)
                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
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
            EmptyLibraryState(
                reason = state.emptyReason,
                hostName = state.host?.name,
                detail = state.emptyDetail,
                onRetry = onRefresh,
                onBack = onBack,
            )
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
                        loadBoxArt = loadBoxArt,
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
 * @param loadBoxArt fetches this app's art, called while the tile is on screen.
 * @param modifier layout modifier.
 */
@Composable
fun AppTile(
    app: HostApp,
    running: Boolean,
    onClick: () -> Unit,
    onQuit: () -> Unit,
    loadBoxArt: suspend (HostApp) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val boxArt = rememberBoxArt(app = app, load = loadBoxArt)

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

        // Name plate: a tall, soft bottom scrim keeps light box art readable without dimming the
        // whole tile. A scrim only as tall as the text reads as a black bar stuck to the bottom.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(TITLE_SCRIM_FRACTION)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                ),
        ) {
            Text(
                text = app.name,
                style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = spacing.md, vertical = spacing.md),
            )
        }

        if (running) {
            // A ring inside the rounded shape marks the tile that is actually streaming.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 3.dp,
                        color = colors.accent,
                        shape = RoundedCornerShape(VoidLinkShapeTokens.TileRadius),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(spacing.sm)
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
                    .background(colors.accent)
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

/**
 * The empty library, told apart by cause so the user knows what to do next.
 *
 * "Nothing to stream" used to be shown for every one of these, including outright request failures.
 * That is why a PC serving a perfectly good library looked identical to one with no games on it —
 * and why a bug report about it could not be acted on. Each cause now names itself, and [detail]
 * carries the underlying reason verbatim for exactly that reason.
 */
@Composable
private fun EmptyLibraryState(
    reason: EmptyLibraryReason?,
    hostName: String?,
    detail: String?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val host = hostName ?: "this PC"
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when (reason) {
                EmptyLibraryReason.UNPAIRED -> EmptyState(
                    icon = VoidLinkIcons.Locked,
                    title = "Not paired yet",
                    body = "$host answered, but it does not trust this device. Pair with it from " +
                        "the Hosts screen and its games will appear here.",
                    primaryActionLabel = "Back to hosts",
                    onPrimaryAction = onBack,
                )
                // The one case nothing in this app can fix. Apollo pairs new clients with default
                // permissions, and widening them is done on the PC's own clients page — so send the
                // user there instead of offering a Refresh that will keep saying the same thing.
                EmptyLibraryReason.PERMISSION_DENIED -> EmptyState(
                    icon = VoidLinkIcons.Locked,
                    title = "Not allowed to list games",
                    body = "$host is paired with this device but hasn't given it permission to " +
                        "see the app list. Open the host software's Clients page on the PC, find " +
                        "this device and allow it, then refresh.",
                    primaryActionLabel = "Refresh",
                    onPrimaryAction = onRetry,
                    secondaryActionLabel = "Back to hosts",
                    onSecondaryAction = onBack,
                )
                EmptyLibraryReason.HOST_REFUSED -> EmptyState(
                    icon = VoidLinkIcons.Locked,
                    title = "$host refused the request",
                    body = "It understood the request for its app list and turned it down.",
                    primaryActionLabel = "Try again",
                    onPrimaryAction = onRetry,
                    secondaryActionLabel = "Back to hosts",
                    onSecondaryAction = onBack,
                )
                EmptyLibraryReason.TLS_FAILURE -> EmptyState(
                    icon = VoidLinkIcons.Locked,
                    title = "Can't connect securely to $host",
                    body = "The secure connection this app needs was refused. If you re-installed " +
                        "or reset the host software, pair with it again.",
                    primaryActionLabel = "Back to hosts",
                    onPrimaryAction = onBack,
                    secondaryActionLabel = "Try again",
                    onSecondaryAction = onRetry,
                )
                EmptyLibraryReason.UNREADABLE_RESPONSE -> EmptyState(
                    icon = VoidLinkIcons.Host,
                    title = "Couldn't read the app list",
                    body = "$host answered, but not with something this app understands. This is " +
                        "worth reporting — the details below say what arrived.",
                    primaryActionLabel = "Try again",
                    onPrimaryAction = onRetry,
                )
                EmptyLibraryReason.NO_APPS -> EmptyState(
                    icon = VoidLinkIcons.Host,
                    title = "Nothing to stream",
                    body = "$host is paired and reachable but lists no applications. Add a game " +
                        "in the host software, then refresh.",
                    primaryActionLabel = "Refresh",
                    onPrimaryAction = onRetry,
                )
                // UNREACHABLE, TRANSPORT_FAILURE and the not-yet-loaded null all mean the same
                // thing to a user: the PC did not answer.
                else -> EmptyState(
                    icon = VoidLinkIcons.Offline,
                    title = "Can't reach $host",
                    body = "It may be asleep, or on a different network from this device.",
                    primaryActionLabel = "Try again",
                    onPrimaryAction = onRetry,
                    secondaryActionLabel = "Back to hosts",
                    onSecondaryAction = onBack,
                )
            }
            detail?.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(modifier = Modifier.height(spacing.md))
                Text(
                    text = text,
                    style = VoidLinkTheme.footnote,
                    color = colors.tertiaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }
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
 * Fetches and decodes one tile's box art, off the main thread, only while the tile is composed.
 *
 * Scoped to the tile on purpose: the coroutine is cancelled and the bitmap released when the tile
 * scrolls out of the grid, which is what keeps a large library from holding every image at once.
 * Returns `null` while loading, when the host has no art, or when the bytes will not decode —
 * callers fall back to the generated placeholder in all three cases.
 *
 * @param app the tile's app.
 * @param load fetches the encoded bytes; expected to hit a disk cache on repeat.
 */
@Composable
fun rememberBoxArt(app: HostApp, load: suspend (HostApp) -> ByteArray?): ImageBitmap? {
    val decoded = produceState<ImageBitmap?>(initialValue = null, key1 = app.id) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = load(app)
                if (bytes == null || bytes.isEmpty()) {
                    null
                } else {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
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
            onDismissMessage = {},
            onRefresh = {},
            loadBoxArt = { null },
        )
    }
}

/** The library of a PC that is switched off — the empty state users hit most often. */
@Preview(name = "Apps — unreachable", widthDp = 720, heightDp = 900)
@Composable
private fun AppsScreenUnreachablePreview() {
    VoidLinkTheme {
        AppsScreen(
            state = AppsUiState(
                host = KnownHost(uuid = "1", name = "BATTLESTATION"),
                emptyReason = EmptyLibraryReason.UNREACHABLE,
            ),
            onToggleSidebar = {},
            onBack = {},
            onLaunch = {},
            onQuitRunning = {},
            onExternalDisplay = {},
            onDismissMessage = {},
            onRefresh = {},
            loadBoxArt = { null },
        )
    }
}
