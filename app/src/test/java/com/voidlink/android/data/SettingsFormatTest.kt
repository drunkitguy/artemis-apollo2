package com.voidlink.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the exact strings the settings sidebar shows next to each control.
 *
 * These are user-visible to the character, which is why the formatting lives in a pure object
 * rather than being inlined into composables.
 */
class SettingsFormatTest {

    @Test
    fun `bitrate at or above one megabit reads as Mbps with one decimal`() {
        assertEquals("23.0 Mbps", SettingsFormat.bitrate(23_000))
        assertEquals("20.0 Mbps", SettingsFormat.bitrate(20_000))
        assertEquals("1.5 Mbps", SettingsFormat.bitrate(1_500))
        assertEquals("150.0 Mbps", SettingsFormat.bitrate(StreamSettings.BITRATE_MAX_KBPS))
    }

    @Test
    fun `bitrate below one megabit reads as raw kbps`() {
        assertEquals("500 kbps", SettingsFormat.bitrate(StreamSettings.BITRATE_MIN_KBPS))
        assertEquals("999 kbps", SettingsFormat.bitrate(999))
    }

    @Test
    fun `bitrate uses a dot as the decimal separator regardless of the default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("23.0 Mbps", SettingsFormat.bitrate(23_000))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `percent appends a percent sign`() {
        assertEquals("100%", SettingsFormat.percent(100))
        assertEquals("25%", SettingsFormat.percent(StreamSettings.VELOCITY_MIN_PERCENT))
        assertEquals("300%", SettingsFormat.percent(StreamSettings.VELOCITY_MAX_PERCENT))
    }

    @Test
    fun `divider position renders both halves between pipes`() {
        assertEquals("| 50% | 50% |", SettingsFormat.dividerPosition(50))
        assertEquals("| 30% | 70% |", SettingsFormat.dividerPosition(30))
        assertEquals("| 90% | 10% |", SettingsFormat.dividerPosition(90))
    }

    @Test
    fun `divider position clamps a corrupt value rather than showing a negative share`() {
        assertEquals("| 0% | 100% |", SettingsFormat.dividerPosition(-40))
        assertEquals("| 100% | 0% |", SettingsFormat.dividerPosition(180))
    }

    @Test
    fun `video summary joins resolution and frame rate`() {
        assertEquals(
            "1080p60",
            SettingsFormat.videoSummary(StreamResolution.RES_1080P, FrameRate.FPS_60),
        )
        assertEquals(
            "4K120",
            SettingsFormat.videoSummary(StreamResolution.RES_2160P, FrameRate.FPS_120),
        )
        assertEquals(
            "Native30",
            SettingsFormat.videoSummary(StreamResolution.NATIVE, FrameRate.FPS_30),
        )
    }

    @Test
    fun `last seen describes the age of a sighting in coarse steps`() {
        val now = 1_700_000_000_000L
        val minute = 60_000L

        assertEquals("Never seen", SettingsFormat.lastSeen(0L, now))
        assertEquals("Just now", SettingsFormat.lastSeen(now - 30_000L, now))
        assertEquals("5 min ago", SettingsFormat.lastSeen(now - 5 * minute, now))
        assertEquals("2 h ago", SettingsFormat.lastSeen(now - 120 * minute, now))
        assertEquals("3 d ago", SettingsFormat.lastSeen(now - 3 * 24 * 60 * minute, now))
    }

    @Test
    fun `last seen treats a clock that ran backwards as just now`() {
        val now = 1_700_000_000_000L

        assertEquals("Just now", SettingsFormat.lastSeen(now + 60_000L, now))
    }
}
