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
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VoidLinkTheme.colors.background)
            .systemBarsPadding(),
    ) {
        SettingsScaffold(
            sidebarOpen = sidebarOpen,
            onDismissSidebar = { sidebarOpen = false },
            settings = settings,
            onUpdate = { transform -> settingsViewModel.update(transform) },
            onResetDefaults = { settingsViewModel.resetToDefaults() },
        ) {
            NavHost(
                navController = navController,
                startDestination = VoidLinkRoutes.HOSTS,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(route = VoidLinkRoutes.HOSTS) {
                    HostsRoute(
                        onToggleSidebar = { sidebarOpen = !sidebarOpen },
                        onOpenHost = { host ->
                            navController.navigate(VoidLinkRoutes.apps(host.uuid))
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
                        onToggleSidebar = { sidebarOpen = !sidebarOpen },
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
 */
@Composable
private fun HostsRoute(
    onToggleSidebar: () -> Unit,
    onOpenHost: (KnownHost) -> Unit,
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
            }
        },
        onRename = { host, newName -> viewModel.rename(host.uuid, newName) },
        onDelete = { host -> viewModel.delete(host.uuid) },
        onUnpair = { host -> viewModel.unpair(host.uuid) },
        onWake = viewModel::wake,
        // Per-host overrides are edited in the same panel as the global ones; opening it is the
        // right response until that editor gains a host-scoped mode.
        onHostSettings = { onToggleSidebar() },
        onDismissPairing = viewModel::cancelPairing,
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
