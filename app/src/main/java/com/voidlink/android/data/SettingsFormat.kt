package com.voidlink.android.data

import java.util.Locale

/**
 * Pure formatting helpers for setting values.
 *
 * These deliberately live outside the UI layer and touch no Android API, so the exact strings the
 * user sees ("23.0 Mbps", "| 50% | 50% |") are covered by plain JVM unit tests.
 */
object SettingsFormat {

    /**
     * Renders a bitrate for the value label of the bitrate slider.
     *
     * Below 1 Mbps the raw kbps figure is clearer, so `750` becomes `"750 kbps"`; at or above
     * 1 Mbps the value is shown with one decimal, so `23000` becomes `"23.0 Mbps"`.
     */
    fun bitrate(kbps: Int): String = if (kbps < 1_000) {
        "$kbps kbps"
    } else {
        String.format(Locale.US, "%.1f Mbps", kbps / 1_000f)
    }

    /**
     * Renders a percentage multiplier, e.g. `100` becomes `"100%"`.
     */
    fun percent(value: Int): String = "$value%"

    /**
     * Renders the touch-divider position as the two-column glyph used in the sidebar:
     * `50` becomes `"| 50% | 50% |"`.
     *
     * The value is clamped so a corrupt stored value can never produce a negative right-hand share.
     */
    fun dividerPosition(leftPercent: Int): String {
        val left = leftPercent.coerceIn(0, 100)
        return "| $left% | ${100 - left}% |"
    }

    /**
     * Renders a density-independent distance, e.g. `160` becomes `"160 dp"`.
     *
     * Kept in physical units rather than converted to millimetres or inches: the value is a
     * gesture threshold the user tunes by feel, and dp is what the gesture code measures in.
     */
    fun distanceDp(value: Int): String = "$value dp"

    /**
     * Renders the "1080p60" style summary shown next to the Video section header.
     */
    fun videoSummary(resolution: StreamResolution, frameRate: FrameRate): String =
        "${resolution.label}${frameRate.label}"

    /**
     * Renders a host's last-seen timestamp as a coarse relative age.
     *
     * @param lastSeenEpochMillis when the host last answered a probe, or `0` if never.
     * @param nowEpochMillis current wall-clock time.
     */
    fun lastSeen(lastSeenEpochMillis: Long, nowEpochMillis: Long): String {
        if (lastSeenEpochMillis <= 0L) return "Never seen"
        val deltaMillis = (nowEpochMillis - lastSeenEpochMillis).coerceAtLeast(0L)
        val minutes = deltaMillis / 60_000L
        return when {
            minutes < 1L -> "Just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 60L * 24L -> "${minutes / 60L} h ago"
            else -> "${minutes / (60L * 24L)} d ago"
        }
    }
}
