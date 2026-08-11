package com.voidlink.android.ui.hosts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voidlink.android.data.HostReachability
import com.voidlink.android.data.HostStatus
import com.voidlink.android.data.KnownHost
import com.voidlink.android.data.SettingsFormat
import com.voidlink.android.ui.components.GlyphTile
import com.voidlink.android.ui.components.HairlineDivider
import com.voidlink.android.ui.components.ScreenHeader
import com.voidlink.android.ui.components.StatusLine
import com.voidlink.android.ui.components.VoidLinkCard
import com.voidlink.android.ui.components.VoidLinkIcons
import com.voidlink.android.ui.theme.VoidLinkShapeTokens
import com.voidlink.android.ui.theme.VoidLinkTheme

/** Minimum width a host card needs before the grid adds another column. */
private val HostCardMinWidth = 320.dp

/**
 * The Hosts screen: a centred title over an adaptive grid of host cards.
 *
 * Purely presentational — every action is reported upward, so the same composable is used by the
 * live screen and by previews with canned data.
 *
 * @param state what to draw.
 * @param onToggleSidebar opens the settings panel.
 * @param onRefresh re-runs discovery.
 * @param onAddHost adds a manually entered host (address, optional name).
 * @param onCardAction the footer button of a card was tapped.
 * @param onRename rename was chosen from a card's context menu.
 * @param onDelete delete was chosen from a card's context menu.
 * @param onUnpair unpair was chosen from a card's context menu.
 * @param onWake wake was chosen from a card's context menu.
 * @param onHostSettings per-host settings was chosen from a card's context menu.
 * @param onDismissPairing closes the PIN sheet.
 * @param onMessageShown clears the transient message once displayed.
 * @param modifier layout modifier.
 */
@Composable
fun HostsScreen(
    state: HostsUiState,
    onToggleSidebar: () -> Unit,
    onRefresh: () -> Unit,
    onAddHost: (String, String?) -> Unit,
    onCardAction: (HostCardState) -> Unit,
    onRename: (KnownHost, String) -> Unit,
    onDelete: (KnownHost) -> Unit,
    onUnpair: (KnownHost) -> Unit,
    onWake: (KnownHost) -> Unit,
    onHostSettings: (KnownHost) -> Unit,
    onDismissPairing: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    var addDialogOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<KnownHost?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Hosts",
                centered = true,
                leading = {
                    IconButton(onClick = onToggleSidebar) {
                        Icon(
                            imageVector = VoidLinkIcons.Sidebar,
                            contentDescription = "Show settings",
                            tint = colors.accent,
                        )
                    }
                },
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = VoidLinkIcons.Refresh,
                            contentDescription = "Search for hosts",
                            tint = colors.accent,
                        )
                    }
                    IconButton(onClick = { addDialogOpen = true }) {
                        Icon(
                            imageVector = VoidLinkIcons.Add,
                            contentDescription = "Add host manually",
                            tint = colors.accent,
                        )
                    }
                },
            )

            // Discovery indicator: a hairline progress bar keeps the grid from jumping around the
            // way a spinner placed in the content would.
            Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                if (state.isDiscovering) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        trackColor = Color.Transparent,
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = HostCardMinWidth),
                modifier = Modifier.fillMaxSize(),
                // Extra room at the bottom so the last card clears the navigation bar inset.
                contentPadding = PaddingValues(
                    start = spacing.xl,
                    end = spacing.xl,
                    top = spacing.lg,
                    bottom = spacing.xxl * 2,
                ),
                horizontalArrangement = Arrangement.spacedBy(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                items(items = state.hosts, key = { it.host.uuid }) { card ->
                    HostCard(
                        card = card,
                        // Tapping the card body does whatever its footer button does: a paired,
                        // online host opens; an unpaired one starts pairing; an offline one wakes.
                        // Navigating into the app grid of a machine that is asleep would only ever
                        // show an empty screen.
                        onPrimaryAction = { onCardAction(card) },
                        onRename = { renameTarget = card.host },
                        onDelete = { onDelete(card.host) },
                        onUnpair = { onUnpair(card.host) },
                        onWake = { onWake(card.host) },
                        onSettings = { onHostSettings(card.host) },
                    )
                }
                item(key = ADD_TILE_KEY) {
                    AddHostCard(onClick = { addDialogOpen = true }, isEmptyState = state.isEmpty)
                }
            }
        }

        state.message?.let { message ->
            MessageBanner(
                message = message,
                onDismiss = onMessageShown,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(spacing.lg),
            )
        }
    }

    if (addDialogOpen) {
        AddHostDialog(
            onDismiss = { addDialogOpen = false },
            onConfirm = { address, name ->
                addDialogOpen = false
                onAddHost(address, name)
            },
        )
    }

    renameTarget?.let { host ->
        RenameHostDialog(
            host = host,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                onRename(host, newName)
            },
        )
    }

    if (state.pairingHost != null && state.pairingPin != null) {
        PairingDialog(
            hostName = state.pairingHost.name,
            pin = state.pairingPin,
            onDismiss = onDismissPairing,
        )
    }
}

/**
 * One host card: icon tile with an optional lock badge, name, status line, and a full-width footer
 * button whose meaning depends on whether the host is online and paired.
 *
 * @param card the host and its status.
 * @param onPrimaryAction the footer button, or the card body, was tapped.
 * @param onRename context menu: rename.
 * @param onDelete context menu: delete.
 * @param onUnpair context menu: unpair.
 * @param onWake context menu: wake.
 * @param onSettings context menu: per-host settings.
 * @param modifier layout modifier.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostCard(
    card: HostCardState,
    onPrimaryAction: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onUnpair: () -> Unit,
    onWake: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    var menuOpen by remember { mutableStateOf(false) }
    val online = card.isOnline

    Box(modifier = modifier) {
        VoidLinkCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onPrimaryAction,
                    onLongClick = { menuOpen = true },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    GlyphTile(
                        icon = VoidLinkIcons.Host,
                        contentDescription = null,
                        size = 72.dp,
                        backgroundColor = if (online) colors.accentFill else colors.fill,
                        iconColor = if (online) colors.accent else colors.offline,
                    )
                    if (card.needsPairing) {
                        Box(
                            // Nudged past the tile's corner so the badge reads as cut out of it,
                            // the way the reference draws it.
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 6.dp, y = 6.dp)
                                .size(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(colors.card)
                                .padding(3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = VoidLinkIcons.Locked,
                                contentDescription = "Not paired",
                                tint = colors.secondaryLabel,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(spacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.host.name,
                        style = VoidLinkTheme.cardTitle,
                        color = colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(spacing.xs))
                    if (online) {
                        StatusLine(
                            icon = VoidLinkIcons.Online,
                            text = "Online",
                            tint = colors.online,
                        )
                    } else {
                        StatusLine(
                            icon = VoidLinkIcons.Offline,
                            text = "Offline",
                            tint = colors.offline,
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text = card.host.primaryAddress
                            ?: SettingsFormat.lastSeen(
                                card.host.lastSeenEpochMillis,
                                System.currentTimeMillis(),
                            ),
                        style = VoidLinkTheme.footnote,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HairlineDivider()

            HostFooterButton(action = card.primaryAction, onClick = onPrimaryAction)
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            HostMenuItem("Wake", VoidLinkIcons.Power) { menuOpen = false; onWake() }
            HostMenuItem("Unpair", VoidLinkIcons.Locked) { menuOpen = false; onUnpair() }
            HostMenuItem("Rename", VoidLinkIcons.Rename) { menuOpen = false; onRename() }
            HostMenuItem("Settings", VoidLinkIcons.Settings) { menuOpen = false; onSettings() }
            HostMenuItem("Delete", VoidLinkIcons.Delete, destructive = true) {
                menuOpen = false
                onDelete()
            }
        }
    }
}

/** A single entry in a host card's long-press menu. */
@Composable
private fun HostMenuItem(
    label: String,
    icon: ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = VoidLinkTheme.colors
    val tint = if (destructive) colors.destructive else colors.label
    DropdownMenuItem(
        text = { Text(text = label, style = VoidLinkTheme.body, color = tint) },
        onClick = onClick,
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
        },
    )
}

/** The full-width action button along the bottom of a host card. */
@Composable
private fun HostFooterButton(
    action: HostAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    val label: String
    val icon: ImageVector
    val contentColor: Color
    val fill: Color
    when (action) {
        HostAction.PAIR -> {
            label = "Pair with PIN"
            icon = VoidLinkIcons.Unlocked
            contentColor = colors.accent
            fill = colors.accentFill
        }
        HostAction.CONNECT -> {
            label = "Connect"
            icon = VoidLinkIcons.Connect
            contentColor = colors.accent
            fill = colors.accentFill
        }
        HostAction.WAKE -> {
            label = "Wake-on-LAN"
            icon = VoidLinkIcons.Power
            // Muted and untinted: waking is the fallback offer for a machine that is asleep, not
            // the confident blue call to action a reachable host gets.
            contentColor = colors.offline
            fill = Color.Transparent
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(fill)
            .clickable(onClick = onClick)
            .padding(vertical = spacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = label,
            style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
    }
}

/**
 * The "Add host manually" tile that closes the grid.
 *
 * @param onClick opens the add dialog.
 * @param isEmptyState true when it is the only thing in the grid, which earns a longer explanation.
 * @param modifier layout modifier.
 */
@Composable
fun AddHostCard(
    onClick: () -> Unit,
    isEmptyState: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VoidLinkShapeTokens.CardRadius))
            .border(
                width = VoidLinkShapeTokens.Hairline,
                color = colors.separator,
                shape = RoundedCornerShape(VoidLinkShapeTokens.CardRadius),
            )
            .clickable(onClick = onClick)
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphTile(
            icon = VoidLinkIcons.Add,
            contentDescription = null,
            size = 56.dp,
            backgroundColor = colors.accentFill,
            iconColor = colors.accent,
        )
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = "Add host manually",
            style = VoidLinkTheme.cardTitle,
            color = colors.accent,
        )
        if (isEmptyState) {
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = "No hosts found yet. Make sure your PC is awake and on the same network, " +
                    "or enter its address yourself.",
                style = VoidLinkTheme.footnote,
                color = colors.secondaryLabel,
            )
        }
    }
}

/** Dialog for typing in a host address by hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add host", style = VoidLinkTheme.cardTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address or hostname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(VoidLinkTheme.spacing.md))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(address.trim(), name.trim().ifEmpty { null }) },
                enabled = address.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Dialog for renaming an existing host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameHostDialog(
    host: KnownHost,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(host.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Rename host", style = VoidLinkTheme.cardTitle) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows the PIN the user must type on the host to complete pairing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingDialog(
    hostName: String,
    pin: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Pair with $hostName", style = VoidLinkTheme.cardTitle) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Enter this PIN on the host to finish pairing.",
                    style = VoidLinkTheme.body,
                    color = VoidLinkTheme.colors.secondaryLabel,
                )
                Spacer(modifier = Modifier.height(VoidLinkTheme.spacing.lg))
                Text(
                    text = pin,
                    style = VoidLinkTheme.largeTitle,
                    color = VoidLinkTheme.colors.accent,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** A transient one-line notice pinned to the bottom of the screen. */
@Composable
private fun MessageBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius))
            .background(colors.card)
            .clickable(onClick = onDismiss)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, style = VoidLinkTheme.body, color = colors.label)
        Spacer(modifier = Modifier.width(spacing.md))
        Icon(
            imageVector = VoidLinkIcons.Close,
            contentDescription = "Dismiss",
            tint = colors.secondaryLabel,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Stable key for the trailing "add host" tile in the grid. */
private const val ADD_TILE_KEY = "voidlink.add-host-tile"

@Preview(name = "Hosts", widthDp = 720, heightDp = 900)
@Composable
private fun HostsScreenPreview() {
    VoidLinkTheme(darkTheme = false) {
        HostsScreenPreviewContent()
    }
}

@Preview(name = "Hosts — dark", widthDp = 720, heightDp = 900)
@Composable
private fun HostsScreenDarkPreview() {
    VoidLinkTheme(darkTheme = true) {
        HostsScreenPreviewContent()
    }
}

/** The canned Hosts screen shared by the light and dark previews. */
@Composable
private fun HostsScreenPreviewContent() {
    HostsScreen(
        state = HostsUiState(
            hosts = listOf(
                HostCardState(
                    host = KnownHost(
                        uuid = "1",
                        name = "BATTLESTATION",
                        addresses = listOf("192.168.1.24"),
                        paired = false,
                    ),
                    status = HostStatus(reachability = HostReachability.ONLINE, paired = false),
                ),
                HostCardState(
                    host = KnownHost(
                        uuid = "2",
                        name = "Living Room PC",
                        addresses = listOf("192.168.1.31"),
                        paired = true,
                    ),
                    status = HostStatus(reachability = HostReachability.ONLINE, paired = true),
                ),
                HostCardState(
                    host = KnownHost(
                        uuid = "3",
                        name = "Studio",
                        addresses = listOf("192.168.1.44"),
                        macAddress = "aa:bb:cc:dd:ee:ff",
                        paired = true,
                    ),
                    status = HostStatus.Offline,
                ),
            ),
        ),
        onToggleSidebar = {},
        onRefresh = {},
        onAddHost = { _, _ -> },
        onCardAction = {},
        onRename = { _, _ -> },
        onDelete = {},
        onUnpair = {},
        onWake = {},
        onHostSettings = {},
        onDismissPairing = {},
        onMessageShown = {},
    )
}
