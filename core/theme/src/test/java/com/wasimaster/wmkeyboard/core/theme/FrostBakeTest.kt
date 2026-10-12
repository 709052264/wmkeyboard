package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pixel half of the glass bake, which needs no Android to run. */
class FrostBakeTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `radius zero leaves the pixels alone`() {
        val pixels = IntArray(16) { argb(255, it * 10, 0, 255 - it * 10) }
        val before = pixels.copyOf()
        FrostBake.boxBlur(pixels, 4, 4, 0)
        assertArrayEquals(before, pixels)
    }

    @Test
    fun `a flat image stays flat`() {
        val pixels = IntArray(64) { argb(255, 120, 60, 200) }
        FrostBake.boxBlur(pixels, 8, 8, 2)
        for (p in pixels) assertEquals(argb(255, 120, 60, 200), p)
    }

    @Test
    fun `an edge clamps to itself rather than fading to black`() {
        // All white: a blur that read past the edge as black would darken the border.
        val pixels = IntArray(10 * 3) { argb(255, 255, 255, 255) }
        FrostBake.boxBlur(pixels, 10, 3, 3)
        for (p in pixels) assertEquals(argb(255, 255, 255, 255), p)
    }

    @Test
    fun `a single bright pixel spreads symmetrically`() {
        val width = 9
        val pixels = IntArray(width) { argb(255, 0, 0, 0) }
        pixels[4] = argb(255, 255, 255, 255)
        FrostBake.boxBlur(pixels, width, 1, 2)
        for (i in 0 until 4) {
            assertEquals("mirror at $i", pixels[i], pixels[width - 1 - i])
        }
        // The centre keeps the most, and the far ends (outside the window) none.
        assertTrue((pixels[4] and 0xFF) > (pixels[3] and 0xFF))
        assertEquals(0, pixels[0] and 0xFF)
    }

    @Test
    fun `the blur radius follows the slider and never swallows a tiny image`() {
        assertEquals(0, FrostBake.radiusFor(0f, 200, 200))
        assertEquals(8, FrostBake.radiusFor(25f, 200, 200))
        // A 16 px decode is held to a quarter of its short side.
        assertEquals(4, FrostBake.radiusFor(25f, 16, 64))
    }

    @Test
    fun `the grain tile is the same every time and never pure black or white`() {
        val a = FrostNoise.pixels()
        val b = FrostNoise.pixels()
        assertArrayEquals(a, b)
        assertEquals(FrostNoise.SIZE * FrostNoise.SIZE, a.size)
        for (p in a) {
            val grey = p and 0xFF
            assertTrue(grey in 64..191)
            assertEquals(grey, (p ushr 8) and 0xFF)
            assertEquals(0xFF, p ushr 24)
        }
    }
}
