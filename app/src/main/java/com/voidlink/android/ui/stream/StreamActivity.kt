package com.voidlink.android.ui.stream

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.voidlink.android.di.ServiceLocator
import com.voidlink.android.media.VideoPipeline
import com.voidlink.android.ui.navigation.StreamLaunchContract

/**
 * The streaming session's Activity.
 *
 * Separate from `MainActivity` because the stream needs window flags, an orientation policy and a
 * surface lifetime that would be actively harmful in a navigation graph (architecture §8).
 *
 * What this class owns, and only this class:
 *
 * * **Window policy** — sticky immersive, cutout-aware, screen kept on, sustained performance.
 * * **The display size**, which is what `Native` resolution resolves against.
 * * **The session's lifetime**, delegated to a [StreamController] bound to `lifecycleScope`.
 *
 * Everything else — decoder selection, the surface, the failure text — lives in [StreamController]
 * and [StreamScreen]. The manifest already declares `configChanges` covering orientation and size,
 * so rotation does not recreate this Activity and therefore cannot restart the session, which is
 * exactly what UI spec §5.7 requires.
 *
 * **There is no state in which this Activity shows a black window with nothing on it.** If a
 * session cannot start, or no decoder can handle the stream, [StreamScreen] draws the reason.
 */
class StreamActivity : ComponentActivity() {

    private lateinit var controller: StreamController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureWindow()

        controller = StreamController(
            scope = lifecycleScope,
            settingsRepository = ServiceLocator.settingsRepository,
            hostRepository = ServiceLocator.hostRepository,
            probe = VideoPipeline.decoderProbe,
            videoSourceFactory = VideoPipeline.videoSourceFactory,
        )

        val bounds = displayBounds()
        controller.start(
            StreamStartRequest(
                hostId = intent?.getStringExtra(StreamLaunchContract.EXTRA_HOST_ID),
                appId = intent?.getStringExtra(StreamLaunchContract.EXTRA_APP_ID),
                appName = intent?.getStringExtra(StreamLaunchContract.EXTRA_APP_NAME),
                displayWidth = bounds.width(),
                displayHeight = bounds.height(),
            ),
        )

        setContent {
            // Deliberately not wrapped in VoidLinkTheme: every colour on this screen is fixed
            // black and white by UI spec §5.1–5.2, because a themed surround around a game reads
            // as a rendering fault rather than as a design.
            val state by controller.state.collectAsStateWithLifecycle()

            StreamScreen(
                state = state,
                onSurfaceAvailable = { surface -> controller.onSurfaceAvailable(surface) },
                onSurfaceDestroyed = { controller.onSurfaceDestroyed() },
                onDismissStats = { controller.setStatsOverlayVisible(false) },
                onExit = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // The system bars come back after a swipe, a dialog, or returning from the background;
        // re-hiding them here is what makes immersive mode stick.
        applyImmersiveMode()
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    /**
     * Window flags for a low-latency fullscreen stream.
     *
     * `FLAG_KEEP_SCREEN_ON` and sustained performance mode are spec §12.6; drawing into the
     * display cutout is what makes a notched phone show the full letterboxed frame instead of a
     * black bar beside the notch.
     */
    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Reassigning is required: mutating the object the getter returned does not re-apply.
            window.attributes = attributes
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { window.setSustainedPerformanceMode(true) }
        }

        applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        val controllerCompat = WindowInsetsControllerCompat(window, window.decorView)
        controllerCompat.hide(WindowInsetsCompat.Type.systemBars())
        controllerCompat.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * The window's size in pixels, which is what `Native` resolution means.
     *
     * `WindowMetrics` (API 30+) reports the real window including the area behind the system bars,
     * which is what we draw into; below that `DisplayMetrics` is the only option and is close
     * enough, since this window is fullscreen anyway.
     */
    private fun displayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = runCatching { windowManager.currentWindowMetrics }.getOrNull()
            if (metrics != null) {
                return Rect(metrics.bounds)
            }
        }
        val displayMetrics = resources.displayMetrics
        return Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
    }
}
