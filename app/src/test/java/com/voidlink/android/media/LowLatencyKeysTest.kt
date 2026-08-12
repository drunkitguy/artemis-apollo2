package com.voidlink.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which low-latency vendor keys get sent to which codec.
 *
 * The null-versus-empty distinction is the whole test: below API 31 the platform cannot list a
 * codec's vendor parameters, and reading that silence as "supports none" would quietly disable
 * low-latency mode on most devices in use.
 */
class LowLatencyKeysTest {

    private fun candidate(
        vendorParameters: List<String> = emptyList(),
        known: Boolean = false,
    ): DecoderCandidate = DecoderCandidate(
        name = "c2.qti.hevc.decoder",
        codec = VideoCodecType.HEVC,
        hardwareAccelerated = true,
        supportsRequestedSize = true,
        supportsRequestedFrameRate = true,
        supportedVendorParameters = vendorParameters,
        vendorParametersKnown = known,
    )

    @Test
    fun `a device that cannot list its vendor parameters gets all of them tried`() {
        val keys = LowLatencyKeys.vendorKeysFor(candidate(known = false))

        assertEquals(LowLatencyKeys.VENDOR, keys)
    }

    @Test
    fun `a codec that reports no vendor parameters gets none`() {
        val keys = LowLatencyKeys.vendorKeysFor(candidate(vendorParameters = emptyList(), known = true))

        assertTrue(keys.isEmpty())
    }

    @Test
    fun `only the reported vendor parameters are sent`() {
        val keys = LowLatencyKeys.vendorKeysFor(
            candidate(
                vendorParameters = listOf(
                    "vendor.qti-ext-dec-low-latency.enable",
                    "vendor.something-unrelated.value",
                ),
                known = true,
            ),
        )

        assertEquals(1, keys.size)
        assertEquals("vendor.qti-ext-dec-low-latency.enable", keys[0].name)
        assertEquals(1, keys[0].value)
    }

    @Test
    fun `matching ignores case, because vendors do not agree on it`() {
        val keys = LowLatencyKeys.vendorKeysFor(
            candidate(
                vendorParameters = listOf("VENDOR.QTI-EXT-DEC-LOW-LATENCY.ENABLE"),
                known = true,
            ),
        )

        assertEquals(1, keys.size)
    }

    @Test
    fun `the HiSilicon ready key keeps its negative value`() {
        val ready = LowLatencyKeys.VENDOR.single {
            it.name == "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy"
        }

        assertEquals(-1, ready.value)
    }

    @Test
    fun `every key from the spec table is present`() {
        val names = LowLatencyKeys.VENDOR.map { it.name }

        assertTrue(names.contains("vendor.qti-ext-dec-low-latency.enable"))
        assertTrue(names.contains("vendor.qti-ext-dec-picture-order.enable"))
        assertTrue(names.contains("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req"))
        assertTrue(names.contains("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy"))
        assertTrue(names.contains("vendor.rtc-ext-dec-low-latency.enable"))
        assertTrue(names.contains("vendor.low-latency.enable"))
        assertTrue(names.contains("vdec-lowlatency"))
        assertEquals(7, names.size)
    }

    @Test
    fun `the standard key is the string literal, gated at API 30`() {
        assertEquals("low-latency", LowLatencyKeys.STANDARD_LOW_LATENCY)
        assertEquals(30, LowLatencyKeys.STANDARD_LOW_LATENCY_MIN_API)
    }
}
