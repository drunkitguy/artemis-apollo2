package com.voidlink.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-codec capability report the failure screen shows.
 *
 * This is what answers "can this device actually do AV1" — so it has to say "software only" when
 * that is the truth, and it must not let a software decoder's generous advertised limits stand in
 * for hardware the device does not have.
 */
class DecoderInventoryTest {

    private fun candidate(
        name: String,
        codec: VideoCodecType,
        hardware: Boolean = true,
        tenBit: Boolean = false,
        maxWidth: Int = 3840,
        maxHeight: Int = 2160,
        maxFrameRate: Int = 60,
        size: Boolean = true,
        rate: Boolean = true,
    ): DecoderCandidate = DecoderCandidate(
        name = name,
        codec = codec,
        hardwareAccelerated = hardware,
        supportsRequestedSize = size,
        supportsRequestedFrameRate = rate,
        supportsTenBit = tenBit,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        maxFrameRate = maxFrameRate,
        maxFrameRateAtRequestedSize = if (rate) maxFrameRate else 0,
    )

    @Test
    fun `every codec gets an entry, in efficiency order`() {
        val inventory = DecoderInventory.from(emptyList())

        assertEquals(
            listOf(VideoCodecType.AV1, VideoCodecType.HEVC, VideoCodecType.H264),
            inventory.map { it.codec },
        )
        assertTrue(inventory.none { it.available })
        assertTrue(inventory.none { it.usableForRealTime })
    }

    @Test
    fun `a device with only software AV1 reports AV1 present but not usable`() {
        val inventory = DecoderInventory.from(
            listOf(
                candidate("c2.android.av1-decoder", VideoCodecType.AV1, hardware = false),
                candidate("c2.qti.hevc.decoder", VideoCodecType.HEVC, tenBit = true),
            ),
        )

        val av1 = inventory.single { it.codec == VideoCodecType.AV1 }
        assertTrue(av1.available)
        assertFalse(av1.hardwareAccelerated)
        assertFalse(av1.usableForRealTime)
        assertTrue(av1.describe().contains("software only"))

        val hevc = inventory.single { it.codec == VideoCodecType.HEVC }
        assertTrue(hevc.usableForRealTime)
        assertTrue(hevc.describe().contains("10-bit/HDR"))
    }

    @Test
    fun `a software decoder does not inflate a codec's reported limits`() {
        val inventory = DecoderInventory.from(
            listOf(
                candidate(
                    name = "c2.android.av1-decoder",
                    codec = VideoCodecType.AV1,
                    hardware = false,
                    tenBit = true,
                    maxWidth = 7680,
                    maxHeight = 4320,
                    maxFrameRate = 240,
                ),
                candidate(
                    name = "c2.qti.av1.decoder",
                    codec = VideoCodecType.AV1,
                    hardware = true,
                    tenBit = false,
                    maxWidth = 1920,
                    maxHeight = 1080,
                    maxFrameRate = 60,
                ),
            ),
        )

        val av1 = inventory.single { it.codec == VideoCodecType.AV1 }
        assertTrue(av1.hardwareAccelerated)
        assertEquals("c2.qti.av1.decoder", av1.decoderName)
        assertEquals(1920, av1.maxWidth)
        assertEquals(1080, av1.maxHeight)
        assertEquals(60, av1.maxFrameRate)
        // The software decoder's 10-bit support is not the device's 10-bit support.
        assertFalse(av1.supportsTenBit)
    }

    @Test
    fun `hardware maxima are taken across every hardware decoder for the codec`() {
        val inventory = DecoderInventory.from(
            listOf(
                candidate("hw.hevc.a", VideoCodecType.HEVC, maxWidth = 1920, maxHeight = 1080, maxFrameRate = 60),
                candidate("hw.hevc.b", VideoCodecType.HEVC, maxWidth = 3840, maxHeight = 2160, maxFrameRate = 120, tenBit = true),
            ),
        )

        val hevc = inventory.single { it.codec == VideoCodecType.HEVC }
        assertEquals(3840, hevc.maxWidth)
        assertEquals(2160, hevc.maxHeight)
        assertEquals(120, hevc.maxFrameRate)
        assertTrue(hevc.supportsTenBit)
    }

    @Test
    fun `a codec whose hardware decoder cannot reach the requested size is not usable`() {
        val inventory = DecoderInventory.from(
            listOf(candidate("hw.av1", VideoCodecType.AV1, size = false, rate = false)),
        )

        val av1 = inventory.single { it.codec == VideoCodecType.AV1 }
        assertTrue(av1.available)
        assertTrue(av1.hardwareAccelerated)
        assertFalse(av1.usableForRealTime)
        assertTrue(av1.describe().contains("requested size unsupported"))
    }

    @Test
    fun `a codec with no decoder describes itself as absent`() {
        val inventory = DecoderInventory.from(listOf(candidate("hw.avc", VideoCodecType.H264)))

        val av1 = inventory.single { it.codec == VideoCodecType.AV1 }
        assertTrue(av1.describe().contains("no decoder"))
        assertEquals(null, av1.decoderName)
    }
}
