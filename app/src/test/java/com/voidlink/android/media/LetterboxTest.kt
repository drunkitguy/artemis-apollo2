package com.voidlink.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aspect-ratio and letterbox arithmetic, including the portrait case UI spec §5.7 describes.
 *
 * All of it is integer pixel maths, tested exactly, because an off-by-one here shows up as a
 * one-pixel seam on one device and nowhere else.
 */
class LetterboxTest {

    @Test
    fun `an exact fit fills the container`() {
        val rect = Letterbox.fit(1920, 1080, 1920, 1080)

        assertEquals(VideoRect(0, 0, 1920, 1080), rect)
    }

    @Test
    fun `a same-aspect container of a different size still fills it`() {
        val rect = Letterbox.fit(1920, 1080, 1280, 720)

        assertEquals(VideoRect(0, 0, 1280, 720), rect)
    }

    @Test
    fun `a landscape stream in a portrait window becomes a centred horizontal band`() {
        // 1080x2400 phone held upright, 16:9 stream.
        val rect = Letterbox.fit(1920, 1080, 1080, 2400)

        assertEquals(1080, rect.width)
        assertEquals(607, rect.height)
        assertEquals(0, rect.left)
        assertEquals(896, rect.top)
        // Equal black bands above and below, to within the odd pixel.
        assertTrue(Math.abs((2400 - rect.bottom) - rect.top) <= 1)
    }

    @Test
    fun `a 16 by 9 stream in a wider window is pillarboxed`() {
        val rect = Letterbox.fit(1920, 1080, 2400, 1080)

        assertEquals(1920, rect.width)
        assertEquals(1080, rect.height)
        assertEquals(240, rect.left)
        assertEquals(0, rect.top)
    }

    @Test
    fun `a 4 by 3 stream in a 16 by 9 window is pillarboxed`() {
        val rect = Letterbox.fit(1600, 1200, 1920, 1080)

        assertEquals(1440, rect.width)
        assertEquals(1080, rect.height)
        assertEquals(240, rect.left)
        assertEquals(0, rect.top)
    }

    @Test
    fun `an unmeasured view produces an empty rectangle rather than a division by zero`() {
        assertEquals(VideoRect.EMPTY, Letterbox.fit(1920, 1080, 0, 0))
        assertEquals(VideoRect.EMPTY, Letterbox.fit(0, 0, 1920, 1080))
        assertEquals(VideoRect.EMPTY, Letterbox.fit(1920, -1, 1920, 1080))
        assertTrue(Letterbox.fit(1920, 1080, 0, 0).isEmpty)
    }

    @Test
    fun `a wildly mismatched aspect still yields at least one pixel`() {
        val rect = Letterbox.fit(4000, 1, 100, 100)

        assertTrue(rect.height >= 1)
        assertTrue(rect.width >= 1)
    }

    @Test
    fun `containment follows the video rectangle, not the view`() {
        val rect = Letterbox.fit(1920, 1080, 1080, 2400)

        assertTrue(Letterbox.contains(rect, 540f, 1200f))
        // In the black band above the video.
        assertFalse(Letterbox.contains(rect, 540f, 10f))
        // In the black band below.
        assertFalse(Letterbox.contains(rect, 540f, 2390f))
    }

    @Test
    fun `touches in the black bands normalize to null`() {
        val rect = Letterbox.fit(1920, 1080, 1080, 2400)

        assertNull(Letterbox.normalize(rect, 540f, 10f))
        assertNull(Letterbox.normalize(rect, 540f, 2390f))
    }

    @Test
    fun `normalization maps the video rectangle onto zero to one`() {
        val rect = Letterbox.fit(1920, 1080, 1920, 1080)

        val topLeft = Letterbox.normalize(rect, 0f, 0f)
        assertEquals(0f, topLeft?.x ?: -1f, 0.0001f)
        assertEquals(0f, topLeft?.y ?: -1f, 0.0001f)

        val middle = Letterbox.normalize(rect, 960f, 540f)
        assertEquals(0.5f, middle?.x ?: -1f, 0.001f)
        assertEquals(0.5f, middle?.y ?: -1f, 0.001f)
    }

    @Test
    fun `normalization is relative to the letterboxed rectangle's own origin`() {
        val rect = Letterbox.fit(1920, 1080, 2400, 1080)

        // The video starts 240px in, so that x is the video's left edge, not 0.1 of the way across.
        val point = Letterbox.normalize(rect, 240f, 0f)
        assertEquals(0f, point?.x ?: -1f, 0.0001f)
    }

    @Test
    fun `normalized points convert to stream pixels`() {
        val (x, y) = Letterbox.toStreamPixels(NormalizedPoint(0.5f, 0.5f), 1920, 1080)

        assertEquals(960, x)
        assertEquals(540, y)
    }

    @Test
    fun `stream pixel conversion stays inside the frame at the far edge`() {
        val (x, y) = Letterbox.toStreamPixels(NormalizedPoint(1f, 1f), 1920, 1080)

        assertEquals(1919, x)
        assertEquals(1079, y)
    }
}
