package com.voidlink.android.ui.stream

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The in-stream screen: video underneath, chrome on top, and never a bare black window.
 *
 * Layer order follows UI spec §5.1 — the `SurfaceView` at the bottom, overlay chrome above it.
 * Touch input, on-screen controls and the settings drawer (z1–z4) are other tasks' work and slot
 * in between; this file owns z0 and z3.
 *
 * The one rule the whole screen is built around: **every state draws something legible.** A
 * fullscreen black window with no text is indistinguishable from a crash, and shipping one is the
 * bug this screen exists to not repeat. So the connecting state names the step it is on, and the
 * failure state names the cause, offers what can be done about it, and keeps a way out.
 *
 * @param state everything to draw.
 * @param onSurfaceAvailable forwarded to [VideoSurface].
 * @param onSurfaceDestroyed forwarded to [VideoSurface]; must release the decoder synchronously.
 * @param onDismissStats called when the stats chip is tapped.
 * @param onExit called by the failure state's button.
 */
@Composable
fun StreamScreen(
    state: StreamUiState,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onDismissStats: () -> Unit,
    onExit: () -> Unit,
) {
    val format = state.surfaceFormat

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // z0 — the video. Mounted as soon as a decoder has been chosen, because the session
        // cannot start without a surface to configure the decoder against.
        if (format != null) {
            VideoSurface(
                streamWidth = format.width,
                streamHeight = format.height,
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
            )
        }

        when (val phase = state.phase) {
            is StreamPhase.Preparing -> ConnectingOverlay(
                appName = state.appName,
                step = phase.step,
                onCancel = onExit,
            )

            is StreamPhase.Streaming -> {
                // z3 — overlay chrome.
                if (state.showStats && format != null) {
                    StatsOverlay(
                        stats = state.stats,
                        format = format,
                        decoderName = phase.decoderName,
                        onDismiss = onDismissStats,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .safeDrawingPadding()
                            .padding(16.dp),
                    )
                }
                if (phase.notes.isNotEmpty()) {
                    CapabilityNotes(
                        notes = phase.notes,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(16.dp),
                    )
                }
            }

            is StreamPhase.Failed -> FailureOverlay(
                appName = state.appName,
                phase = phase,
                onExit = onExit,
            )
        }
    }
}

/**
 * The connecting state: a spinner, the app's name, and the step we are actually on.
 *
 * Naming the step is what turns "it is taking a while" into "it is stuck negotiating", which is
 * the difference between a bug report we can act on and one we cannot.
 */
@Composable
private fun ConnectingOverlay(
    appName: String?,
    step: StreamPreparationStep,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
            Text(
                text = appName ?: "Connecting",
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = step.label,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Cancel",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The failure state.
 *
 * Three things, in order of how much the user cares: what went wrong, what they can do, and the
 * small print an engineer would want. The detail block scrolls, because the decoder inventory on a
 * device with eight codecs is longer than a phone screen.
 */
@Composable
private fun FailureOverlay(
    appName: String?,
    phase: StreamPhase.Failed,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (appName != null) {
                Text(
                    text = appName,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = phase.title,
                color = Color.White,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = phase.message,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            val detail = phase.detail
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.5f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(12.dp),
                )
            }
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.16f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
            ) {
                Text(text = "Back to library", fontSize = 17.sp, color = Color.White)
            }
        }
    }
}

/**
 * Notes about capability downgrades, shown over the picture.
 *
 * A fallback the user was not told about looks like a bug in the app — "why is this soft?" — so
 * the reason travels with the stream rather than living only in a log.
 */
@Composable
private fun CapabilityNotes(notes: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        for (note in notes) {
            Text(
                text = note,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                textAlign = TextAlign.Start,
            )
        }
    }
}
