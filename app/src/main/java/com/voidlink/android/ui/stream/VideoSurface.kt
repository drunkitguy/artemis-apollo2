package com.voidlink.android.ui.stream

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import com.voidlink.android.media.Letterbox
import com.voidlink.android.media.VideoRect

/**
 * The decoded video, letterboxed inside its container.
 *
 * A [SurfaceView], not a `TextureView`. Spec §12.1 is unambiguous about why: a `TextureView` routes
 * every frame through the GPU as an extra copy and costs a frame of latency, and latency is the
 * product.
 *
 * Three behaviours worth naming, all from UI spec §5.1 and §5.7:
 *
 * * **The stream's dimensions drive the buffer, the view's drive the bounds.** `setFixedSize` pins
 *   the surface buffer to the stream size, so rotating the device re-letterboxes without touching
 *   the decoder. The negotiated stream size never changes mid-session, and there is no path in the
 *   protocol to renegotiate it.
 * * **Letterboxing is pure black**, `#000000`, in both themes — a themed grey band around a game
 *   looks like a rendering bug.
 * * **The surface's lifetime bounds the decoder's.** [onSurfaceDestroyed] is called synchronously
 *   from `surfaceDestroyed` and must have torn the codec down before it returns;
 *   [StreamController.onSurfaceDestroyed] does exactly that.
 *
 * @param streamWidth the negotiated stream width in pixels.
 * @param streamHeight the negotiated stream height in pixels.
 * @param onSurfaceAvailable called with a usable [Surface]; may be called again after a
 *   destroy/create cycle with a different surface.
 * @param onSurfaceDestroyed called before the surface goes away. Must release the decoder
 *   synchronously.
 * @param modifier layout modifier for the container.
 * @param onVideoRectChanged reports where the video ended up inside the container, in view pixels.
 *   Touch input maps against this rectangle rather than the view's bounds (UI spec §5.7); the
 *   default ignores it.
 */
@Composable
fun VideoSurface(
    streamWidth: Int,
    streamHeight: Int,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    onVideoRectChanged: (VideoRect) -> Unit = {},
) {
    val currentOnAvailable = rememberUpdatedState(onSurfaceAvailable)
    val currentOnDestroyed = rememberUpdatedState(onSurfaceDestroyed)

    // One callback object for the lifetime of the composition. Re-creating it on recomposition
    // would add a second listener to the same holder and deliver every event twice.
    val holderCallback = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                currentOnAvailable.value(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Nothing to do: the buffer size is pinned by setFixedSize and the decoder is
                // configured from the negotiated format, not from the view.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                currentOnDestroyed.value()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Pure black, per UI spec §5.1 — not the theme background.
            .background(Color.Black),
    ) {
        Layout(
            content = {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.setFixedSize(streamWidth, streamHeight)
                            holder.addCallback(holderCallback)
                        }
                    },
                    update = { view ->
                        view.holder.setFixedSize(streamWidth, streamHeight)
                    },
                    onRelease = { view ->
                        view.holder.removeCallback(holderCallback)
                    },
                )
            },
        ) { measurables, constraints ->
            val containerWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else streamWidth
            val containerHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else streamHeight
            val rect = Letterbox.fit(streamWidth, streamHeight, containerWidth, containerHeight)
            onVideoRectChanged(rect)

            val placeable = measurables.firstOrNull()?.measure(
                Constraints.fixed(
                    width = rect.width.coerceAtLeast(1),
                    height = rect.height.coerceAtLeast(1),
                ),
            )
            layout(containerWidth, containerHeight) {
                if (placeable != null) {
                    placeable.place(rect.left, rect.top)
                }
            }
        }
    }

    // A composition that goes away without the surface being destroyed first — the Activity
    // finishing, say — must still stop the decoder, or the codec outlives its render target.
    DisposableEffect(Unit) {
        onDispose {
            currentOnDestroyed.value()
        }
    }
}
