package com.voidlink.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voidlink.android.data.HostApp
import com.voidlink.android.data.KnownHost
import com.voidlink.android.ui.apps.AppsScreen
import com.voidlink.android.ui.apps.AppsViewModel
import com.voidlink.android.ui.hosts.HostAction
import com.voidlink.android.ui.hosts.HostsScreen
import com.voidlink.android.ui.hosts.HostsViewModel
import com.voidlink.android.ui.settings.SettingsScaffold
import com.voidlink.android.ui.settings.SettingsViewModel
import com.voidlink.android.ui.theme.VoidLinkTheme

/**
 * The whole non-streaming app: a navigation graph wrapped in the adaptive settings scaffold.
 *
 * The sidebar lives above the graph rather than inside a screen, so its open/closed state and the
 * settings view model survive navigation between Hosts and Apps.
 *
 * @param onLaunchStream invoked when the user picks an app to stream; the host id and the chosen
 *   application are handed to the caller, which owns the streaming session.
 * @param modifier layout modifier.
 */
@Composable
fun VoidLinkApp(
    onLaunchStream: (hostId: String, app: HostApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val hosts by settingsViewModel.hosts.collectAsStateWithLifecycle()
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }

    // Which settings the panel is editing: null is the global set, otherwise one host's overrides.
    // Held here rather than in the panel so it survives navigating between Hosts and Apps.
    var overrideHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val overrideHost = hosts.firstOrNull { it.uuid == overrideHostId }

    // A host with no override yet is shown the global values; the first edit seeds an override
    // from them, so the panel never lies about what the host will actually stream with.
    //
    // Which rows are starred is deliberately taken from the global settings even inside an
    // override, because favourites belong to the person using the app, not to the PC. Without
    // this the star would appear not to respond while a host scope was open.
    val shownSettings = overrideHost?.settingsOverride
        ?.copy(favoriteRowIds = settings.favoriteRowIds)
        ?: settings

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VoidLinkTheme.colors.background)
            .systemBarsPadding(),
    ) {
        SettingsScaffold(
            sidebarOpen = sidebarOpen,
            onDismissSidebar = { sidebarOpen = false },
            settings = shownSettings,
            onUpdate = { transform ->
                val host = overrideHost
                if (host == null) {
                    settingsViewModel.update(transform)
                } else {
                    settingsViewModel.updateHostOverride(host.uuid, transform)
                }
            },
            onResetDefaults = {
                val host = overrideHost
                if (host == null) {
                    settingsViewModel.resetToDefaults()
                } else {
                    settingsViewModel.clearHostOverride(host.uuid)
                }
            },
            overrideHostName = overrideHost?.name,
            onEditGlobal = { overrideHostId = null },
            onToggleFavorite = settingsViewModel::toggleFavoriteRow,
        ) {
            NavHost(
                navController = navController,
                startDestination = VoidLinkRoutes.HOSTS,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(route = VoidLinkRoutes.HOSTS) {
                    HostsRoute(
                        onToggleSidebar = {
                            // The plain toggle always shows the global settings; only the card's
                            // "Host settings…" menu entry scopes the panel to a host.
                            if (!sidebarOpen) overrideHostId = null
                            sidebarOpen = !sidebarOpen
                        },
                        onOpenHost = { host ->
                            navController.navigate(VoidLinkRoutes.apps(host.uuid))
                        },
                        onHostSettings = { host ->
                            overrideHostId = host.uuid
                            sidebarOpen = true
                        },
                    )
                }
                composable(
                    route = VoidLinkRoutes.APPS_PATTERN,
                    arguments = listOf(
                        navArgument(VoidLinkRoutes.ARG_HOST_ID) { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    val hostId = backStackEntry.arguments
                        ?.getString(VoidLinkRoutes.ARG_HOST_ID)
                        .orEmpty()
                    AppsRoute(
                        hostId = hostId,
                        onToggleSidebar = {
                            // Opening the panel from a host's library scopes it to that host:
                            // while you are looking at one PC's games, "settings" means that PC's
                            // (spec §4.9). The Hosts screen, which is about all of them, does not.
                            if (!sidebarOpen) {
                                overrideHostId = hostId
                            }
                            sidebarOpen = !sidebarOpen
                        },
                        onBack = { navController.popBackStack() },
                        onLaunchStream = { app -> onLaunchStream(hostId, app) },
                    )
                }
            }
        }
    }
}

/**
 * Binds [HostsViewModel] to [HostsScreen].
 *
 * @param onToggleSidebar shows or hides the settings panel.
 * @param onOpenHost the user wants to browse a host's library.
 * @param onHostSettings the user wants the panel scoped to one host's overrides.
 */
@Composable
private fun HostsRoute(
    onToggleSidebar: () -> Unit,
    onOpenHost: (KnownHost) -> Unit,
    onHostSettings: (KnownHost) -> Unit,
) {
    val viewModel: HostsViewModel = viewModel(factory = HostsViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HostsScreen(
        state = state,
        onToggleSidebar = onToggleSidebar,
        onRefresh = viewModel::refresh,
        onAddHost = viewModel::addManualHost,
        onCardAction = { card ->
            when (card.primaryAction) {
                HostAction.PAIR -> viewModel.beginPairing(card.host)
                HostAction.WAKE -> viewModel.wake(card.host)
                HostAction.CONNECT -> onOpenHost(card.host)
                // Nothing to do until the first probe answers; the card says so itself.
                HostAction.CHECKING -> Unit
            }
        },
        onRename = { host, newName -> viewModel.rename(host.uuid, newName) },
        onDelete = { host -> viewModel.delete(host.uuid) },
        onUnpair = { host -> viewModel.unpair(host.uuid) },
        onWake = viewModel::wake,
        onHostSettings = onHostSettings,
        onDismissPairing = viewModel::cancelPairing,
        onRetryPairing = viewModel::retryPairing,
        onMessageShown = viewModel::consumeMessage,
    )
}

/**
 * Binds [AppsViewModel] to [AppsScreen].
 *
 * @param hostId the host whose library is shown.
 * @param onToggleSidebar shows or hides the settings panel.
 * @param onBack returns to the host list.
 * @param onLaunchStream the user picked an app to stream.
 */
@Composable
private fun AppsRoute(
    hostId: String,
    onToggleSidebar: () -> Unit,
    onBack: () -> Unit,
    onLaunchStream: (HostApp) -> Unit,
) {
    val viewModel: AppsViewModel = viewModel(factory = AppsViewModel.factory(hostId))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AppsScreen(
        state = state,
        onToggleSidebar = onToggleSidebar,
        onBack = onBack,
        onLaunch = onLaunchStream,
        onQuitRunning = viewModel::quitRunningApp,
        // The external-display choice lives in the settings panel, so surface it rather than
        // duplicating the control here.
        onExternalDisplay = onToggleSidebar,
        onDismissMessage = viewModel::consumeMessage,
    )
}
