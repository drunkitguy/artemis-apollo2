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
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.voidlink.android.data.HostReachability
import com.voidlink.android.data.HostStatus
import com.voidlink.android.data.KnownHost
import com.voidlink.android.data.SettingsFormat
import com.voidlink.android.ui.components.EmptyState
import com.voidlink.android.ui.components.GlyphTile
import com.voidlink.android.ui.components.HairlineDivider
import com.voidlink.android.ui.components.PendingStatusLine
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
 * @param onDismissPairing abandons pairing; must cancel the handshake, not just hide the dialog.
 * @param onRetryPairing starts a fresh attempt after a failed one.
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
    onRetryPairing: () -> Unit,
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
                if (state.isEmpty) {
                    // A whole-width explanation rather than a lone tile: with nothing found, the
                    // user's next question is "why", and the answer is almost always the network.
                    item(key = EMPTY_STATE_KEY, span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = VoidLinkIcons.Offline,
                            title = if (state.isDiscovering) {
                                "Looking for PCs…"
                            } else {
                                "No PCs found"
                            },
                            body = "Make sure your PC is switched on, on the same Wi-Fi network as " +
                                "this device, and running Sunshine, Apollo or GeForce Experience.",
                            primaryActionLabel = "Add manually",
                            onPrimaryAction = { addDialogOpen = true },
                            secondaryActionLabel = "Search again",
                            onSecondaryAction = onRefresh,
                        )
                    }
                } else {
                    item(key = ADD_TILE_KEY) {
                        AddHostCard(onClick = { addDialogOpen = true })
                    }
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

    state.pairing?.let { pairing ->
        PairingDialog(
            state = pairing,
            onCancel = onDismissPairing,
            onRetry = onRetryPairing,
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
                    when {
                        card.isChecking -> PendingStatusLine(
                            text = "Checking…",
                            tint = colors.tertiaryLabel,
                        )
                        online -> StatusLine(
                            icon = VoidLinkIcons.Online,
                            text = card.statusLabel,
                            tint = colors.online,
                        )
                        else -> StatusLine(
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

            HostFooterButton(
                action = card.primaryAction,
                enabled = card.isActionable,
                onClick = onPrimaryAction,
            )
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

/**
 * The full-width action button along the bottom of a host card.
 *
 * When [enabled] is false the button is drawn muted **and stops consuming taps**, so the press
 * falls through to the card body — which answers with the reason the action is unavailable. A
 * silently dead button teaches the user nothing.
 */
@Composable
private fun HostFooterButton(
    action: HostAction,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    val label: String
    val icon: ImageVector?
    val contentColor: Color
    val fill: Color
    when (action) {
        HostAction.CHECKING -> {
            label = "Checking…"
            icon = null
            contentColor = colors.tertiaryLabel
            fill = Color.Transparent
        }
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
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_FOOTER_ALPHA)
            .padding(vertical = spacing.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(spacing.sm))
        }
        Text(
            text = label,
            style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
    }
}

/** How far a footer fades when its action cannot be carried out. */
private const val DISABLED_FOOTER_ALPHA = 0.55f

/**
 * The "Add host manually" tile that closes the grid.
 *
 * @param onClick opens the add dialog.
 * Shown alongside real hosts; when the grid is empty the fuller [EmptyState] takes over instead.
 * @param modifier layout modifier.
 */
@Composable
fun AddHostCard(
    onClick: () -> Unit,
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

/**
 * The pairing dialog, which swaps its content as the handshake progresses.
 *
 * Three states, per spec §2.5: the PIN to type on the PC, the working phases, and the outcome.
 * While the attempt is live the dialog **cannot** be dismissed by tapping outside or by the back
 * gesture — only the explicit Cancel button — because dismissing has to run the protocol's
 * `/unpair` cleanup, and a stray outside-tap that skipped it would leave the host stuck showing a
 * PIN prompt and refusing every later attempt.
 *
 * @param state the live pairing state.
 * @param onCancel abandon the attempt; cancels the handshake and runs its cleanup.
 * @param onRetry start a fresh attempt with the same host.
 */
@Composable
private fun PairingDialog(
    state: PairingUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = VoidLinkTheme.colors
    val outcome = state.outcome

    AlertDialog(
        onDismissRequest = { if (state.isFinished) onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = state.isFinished,
            dismissOnClickOutside = state.isFinished,
        ),
        title = {
            Text(
                text = when (outcome) {
                    null -> if (state.isAwaitingPin) {
                        "Enter this PIN on your PC"
                    } else {
                        "Pairing…"
                    }
                    PairingOutcome.PAIRED -> "Paired"
                    PairingOutcome.PIN_WRONG -> "Wrong PIN"
                    PairingOutcome.ALREADY_IN_PROGRESS -> "Another device is pairing"
                    PairingOutcome.FAILED -> "Pairing failed"
                    PairingOutcome.CANCELLED -> "Pairing cancelled"
                },
                style = VoidLinkTheme.cardTitle,
                color = colors.label,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    outcome != null -> PairingResultBody(state = state)
                    state.isAwaitingPin -> PairingPinBody(hostName = state.host.name, pin = state.pin.orEmpty())
                    else -> PairingWorkingBody(phase = state.phase)
                }
            }
        },
        confirmButton = {
            when (outcome) {
                null -> TextButton(onClick = onCancel) {
                    Text(text = "Cancel", color = colors.secondaryLabel)
                }
                PairingOutcome.PAIRED -> TextButton(onClick = onCancel) { Text("Done") }
                else -> TextButton(onClick = onRetry) {
                    Text(text = "Try again", color = colors.accent)
                }
            }
        },
        // A plain slot that emits nothing when there is no second action, rather than a nullable
        // composable lambda — same result, none of the inference sharp edges.
        dismissButton = {
            if (outcome != null && outcome != PairingOutcome.PAIRED) {
                TextButton(onClick = onCancel) {
                    Text(text = "Cancel", color = colors.secondaryLabel)
                }
            }
        },
    )
}

/** Phase 1: the PIN, one digit per box, with the instruction underneath. */
@Composable
private fun PairingPinBody(hostName: String, pin: String) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        pin.forEach { digit ->
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 60.dp)
                    .clip(RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius))
                    .background(colors.fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = digit.toString(),
                    style = VoidLinkTheme.largeTitle,
                    color = colors.accent,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(spacing.lg))
    Text(
        text = "A prompt should have appeared on $hostName. Type this PIN there to finish pairing.",
        style = VoidLinkTheme.footnote,
        color = colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
}

/** Phases 2 onward: the handshake is running and needs nothing from the user. */
@Composable
private fun PairingWorkingBody(phase: Int) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    // The same indeterminate bar the rest of the app uses to mean "something is happening", rather
    // than a second, differently-shaped progress idiom in a dialog.
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = colors.accent,
        trackColor = colors.fill,
    )
    Spacer(modifier = Modifier.height(spacing.md))
    Text(
        text = "Step ${phase.coerceIn(1, PAIRING_PHASE_COUNT)} of $PAIRING_PHASE_COUNT",
        style = VoidLinkTheme.footnote,
        color = colors.secondaryLabel,
    )
}

/** The terminal state: what happened, and what the user can do about it. */
@Composable
private fun PairingResultBody(state: PairingUiState) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val paired = state.outcome == PairingOutcome.PAIRED

    Icon(
        imageVector = if (paired) VoidLinkIcons.Paired else VoidLinkIcons.Alert,
        contentDescription = null,
        tint = if (paired) colors.online else colors.destructive,
        modifier = Modifier.size(44.dp),
    )
    Spacer(modifier = Modifier.height(spacing.md))
    Text(
        text = when (state.outcome) {
            PairingOutcome.PAIRED -> "${state.host.name} now trusts this device."
            PairingOutcome.PIN_WRONG -> "The PIN didn't match. Try again."
            PairingOutcome.ALREADY_IN_PROGRESS ->
                "Wait for the other device to finish, then try again."
            PairingOutcome.CANCELLED -> "The attempt was stopped."
            else -> "The handshake did not complete."
        },
        style = VoidLinkTheme.body,
        color = colors.secondaryLabel,
        textAlign = TextAlign.Center,
    )
    // The protocol's own reason, when it supplied one — far more use than a generic apology.
    state.detail?.takeIf { it.isNotBlank() && !paired }?.let { detail ->
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = detail,
            style = VoidLinkTheme.footnote,
            color = colors.tertiaryLabel,
            textAlign = TextAlign.Center,
        )
    }
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

/** Stable key for the full-width empty state. */
private const val EMPTY_STATE_KEY = "voidlink.hosts-empty-state"

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
                // Offline with no MAC: the footer is shown but muted, and a tap falls through to
                // the card, which explains why nothing can be sent.
                HostCardState(
                    host = KnownHost(
                        uuid = "4",
                        name = "Attic Server",
                        addresses = listOf("192.168.1.9"),
                        paired = true,
                    ),
                    status = HostStatus.Offline,
                ),
                // Not probed yet — the state every card is in for the first second after launch.
                HostCardState(
                    host = KnownHost(
                        uuid = "5",
                        name = "Workshop",
                        addresses = listOf("192.168.1.12"),
                        paired = true,
                    ),
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
        onRetryPairing = {},
        onMessageShown = {},
    )
}
