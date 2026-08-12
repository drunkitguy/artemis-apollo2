package com.voidlink.android.media

/**
 * A rectangle in view pixels, describing where the video sits inside its container.
 *
 * @property left distance from the container's left edge, in pixels.
 * @property top distance from the container's top edge, in pixels.
 * @property width video width in pixels.
 * @property height video height in pixels.
 */
data class VideoRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    /** Exclusive right edge. */
    val right: Int get() = left + width

    /** Exclusive bottom edge. */
    val bottom: Int get() = top + height

    /** True when the rectangle has no area — the state before the view has been measured. */
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    companion object {
        /** The zero rectangle, used before a first measurement. */
        val EMPTY: VideoRect = VideoRect(0, 0, 0, 0)
    }
}

/**
 * A point expressed as a fraction of the video rectangle, `0f..1f` on both axes.
 *
 * @property x horizontal position, 0 at the video's left edge and 1 at its right.
 * @property y vertical position, 0 at the video's top edge and 1 at its bottom.
 */
data class NormalizedPoint(val x: Float, val y: Float)

/**
 * Aspect-ratio-preserving fit of a stream inside a view, and the touch mapping that follows.
 *
 * `docs/03-UI-SPEC.md` §5.1 and §5.7 between them say three things this object implements:
 *
 * * The video preserves the **stream's** aspect ratio, letterboxed with pure black. The stream's
 *   dimensions are fixed at `/launch` and never change on rotation; only the view's do.
 * * In portrait, a landscape stream is a horizontal band centred vertically — which falls out of
 *   the same fit, with no portrait-specific branch.
 * * Touch coordinates map against **the video rectangle, not the view**, and touches in the black
 *   bands are outside the stream.
 *
 * Everything here is integer arithmetic on pixels, so a rounding error cannot produce a one-pixel
 * seam that only appears on one device. All of it is pure, and all of it is unit-tested.
 */
object Letterbox {

    /**
     * Fits a `streamWidth × streamHeight` stream inside a `viewWidth × viewHeight` container,
     * centred, preserving the stream's aspect ratio.
     *
     * @return the video rectangle in view pixels, or [VideoRect.EMPTY] if any input is
     *   non-positive (which is the normal state before the view is measured, not an error).
     */
    fun fit(streamWidth: Int, streamHeight: Int, viewWidth: Int, viewHeight: Int): VideoRect {
        if (streamWidth <= 0 || streamHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return VideoRect.EMPTY
        }

        // Height the video would take if it were as wide as the view. Long arithmetic because
        // 3840 * 2160 already overflows nothing, but 7680 * 4320 on a wide surface is close enough
        // to Int.MAX_VALUE that it is not worth thinking about twice.
        val widthLimitedHeight = viewWidth.toLong() * streamHeight / streamWidth

        return if (widthLimitedHeight <= viewHeight) {
            val height = widthLimitedHeight.toInt().coerceAtLeast(1)
            VideoRect(
                left = 0,
                top = (viewHeight - height) / 2,
                width = viewWidth,
                height = height,
            )
        } else {
            val width = (viewHeight.toLong() * streamWidth / streamHeight).toInt().coerceAtLeast(1)
            VideoRect(
                left = (viewWidth - width) / 2,
                top = 0,
                width = width,
                height = viewHeight,
            )
        }
    }

    /** True when the view-space point `(x, y)` falls inside [rect]. */
    fun contains(rect: VideoRect, x: Float, y: Float): Boolean =
        !rect.isEmpty && x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

    /**
     * Converts a view-space touch position into stream-relative normalized coordinates.
     *
     * @return the normalized point, or `null` when the touch landed in a black band. Native Touch
     *   and Absolute Touch modes drop a `null` (UI spec §5.7); Touchpad mode ignores this function
     *   entirely, because relative movement has no reference frame to fall outside of.
     */
    fun normalize(rect: VideoRect, x: Float, y: Float): NormalizedPoint? {
        if (!contains(rect, x, y)) return null
        return NormalizedPoint(
            x = ((x - rect.left) / rect.width).coerceIn(0f, 1f),
            y = ((y - rect.top) / rect.height).coerceIn(0f, 1f),
        )
    }

    /**
     * Converts a normalized point into a pixel position in the *stream's* coordinate space.
     *
     * This is the reference frame Absolute Touch needs: the host wants a position within the
     * captured desktop, which is [streamWidth] × [streamHeight], not within our view.
     */
    fun toStreamPixels(
        point: NormalizedPoint,
        streamWidth: Int,
        streamHeight: Int,
    ): Pair<Int, Int> = Pair(
        (point.x * streamWidth).toInt().coerceIn(0, maxOf(streamWidth - 1, 0)),
        (point.y * streamHeight).toInt().coerceIn(0, maxOf(streamHeight - 1, 0)),
    )
}
