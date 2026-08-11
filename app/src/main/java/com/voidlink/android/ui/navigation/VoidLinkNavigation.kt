package com.voidlink.android.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voidlink.android.ui.stream.StreamActivity

/**
 * Destinations in the app's navigation graph.
 *
 * Routes are plain strings so the graph stays readable; [apps] is the only one that carries an
 * argument and it URL-encodes the id so an unusual host identifier cannot break the path.
 */
object VoidLinkRoutes {
    /** The host list — the start destination. */
    const val HOSTS: String = "hosts"

    /** Name of the host-id argument in [APPS_PATTERN]. */
    const val ARG_HOST_ID: String = "hostId"

    /** Route pattern for a host's app library. */
    const val APPS_PATTERN: String = "apps/{$ARG_HOST_ID}"

    /** Builds the concrete apps route for [hostId]. */
    fun apps(hostId: String): String = "apps/${Uri.encode(hostId)}"
}

/**
 * The hand-off between this task's UI and the streaming session built by a later task.
 *
 * The UI's only responsibility is to say *what* should be streamed; everything about *how* lives
 * behind [StreamActivity]. Keeping the extras named here means neither side has to guess.
 */
object StreamLaunchContract {
    /** [com.voidlink.android.data.KnownHost.uuid] of the host to stream from. */
    const val EXTRA_HOST_ID: String = "com.voidlink.android.extra.HOST_ID"

    /** Host-assigned id of the application to launch. */
    const val EXTRA_APP_ID: String = "com.voidlink.android.extra.APP_ID"

    /** Display name of the application, used for notifications and the loading screen. */
    const val EXTRA_APP_NAME: String = "com.voidlink.android.extra.APP_NAME"

    /**
     * Builds the intent that starts a streaming session.
     *
     * @param context any context; the intent targets [StreamActivity] explicitly.
     * @param hostId the host to stream from.
     * @param appId the application to launch on that host.
     * @param appName the application's display name.
     */
    fun intent(context: Context, hostId: String, appId: String, appName: String): Intent =
        Intent(context, StreamActivity::class.java)
            .putExtra(EXTRA_HOST_ID, hostId)
            .putExtra(EXTRA_APP_ID, appId)
            .putExtra(EXTRA_APP_NAME, appName)
}
