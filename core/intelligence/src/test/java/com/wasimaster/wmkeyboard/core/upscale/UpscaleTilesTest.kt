package com.wasimaster.wmkeyboard.core.upscale

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpscaleTilesTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * A stand-in for the network that does what UpConv7's shape does: eats
     * CONTEXT pixels on every side and doubles the rest, by repeating each
     * pixel into a 2x2 square.
     */
    private fun nearest(input: FloatArray, inSide: Int): FloatArray {
        val c = UpscaleModels.CONTEXT
        val side = inSide - 2 * c
        val outSide = side * 2
        val out = FloatArray(3 * outSide * outSide)
        for (ch in 0 until 3) {
            for (y in 0 until outSide) {
                for (x in 0 until outSide) {
                    out[ch * outSide * outSide + y * outSide + x] =
                        input[ch * inSide * inSide + (c + y / 2) * inSide + (c + x / 2)]
                }
            }
        }
        return out
    }

    @Test
    fun tilesCoverThePictureOnce() {
        val w = 300
        val h = 130
        val side = UpscaleTiles.side(w, h)
        assertEquals(UpscaleModels.TILE, side)
        val seen = IntArray(w * h)
        for (t in UpscaleTiles.tiles(w, h, side)) {
            for (y in t.y until t.y + t.height) for (x in t.x until t.x + t.width) seen[y * w + x]++
        }
        assertTrue(seen.all { it == 1 })
    }

    @Test
    fun smallPictureTakesOneSmallTile() {
        assertEquals(64, UpscaleTiles.side(60, 33))
        assertEquals(1, UpscaleTiles.tiles(60, 33, 64).size)
    }

    @Test
    fun passWithANearestNetworkDoublesThePictureExactly() {
        val w = 290
        val h = 150
        val pixels = IntArray(w * h) { i -> argb(255, i % 251, (i / 7) % 253, (i * 13) % 255) }
        val out = IntArray(4 * w * h)
        val done = UpscaleTiles.pass(pixels, w, h, ::nearest, { false }) { x, y, tw, th, rgb ->
            for (row in 0 until th) System.arraycopy(rgb, row * tw, out, (y + row) * 2 * w + x, tw)
        }
        assertTrue(done)
        for (y in 0 until 2 * h) {
            for (x in 0 until 2 * w) {
                assertEquals("($x, $y)", pixels[(y / 2) * w + x / 2], out[y * 2 * w + x])
            }
        }
    }

    @Test
    fun inputRepeatsTheEdgePastTheBorder() {
        val w = 3
        val h = 2
        val pixels = intArrayOf(
            argb(255, 10, 0, 0), argb(255, 20, 0, 0), argb(255, 30, 0, 0),
            argb(255, 40, 0, 0), argb(255, 50, 0, 0), argb(255, 60, 0, 0),
        )
        val side = 5
        val out = FloatArray(3 * side * side)
        UpscaleTiles.input(pixels, w, h, -1, -1, side, out)
        // Row 0 is above the picture: the top row again, its left pixel repeated.
        assertEquals(10 / 255f, out[0], 1e-6f)
        assertEquals(10 / 255f, out[1], 1e-6f)
        assertEquals(20 / 255f, out[2], 1e-6f)
        assertEquals(30 / 255f, out[4], 1e-6f)
        // Row 4 is below it: the bottom row.
        assertEquals(60 / 255f, out[4 * side + 4], 1e-6f)
    }

    @Test
    fun cancelStopsBetweenTiles() {
        val pixels = IntArray(300 * 300) { argb(255, 1, 2, 3) }
        var tiles = 0
        val done = UpscaleTiles.pass(pixels, 300, 300, ::nearest, { tiles >= 2 }) { _, _, _, _, _ -> tiles++ }
        assertFalse(done)
        assertEquals(2, tiles)
    }

    @Test
    fun bleedFillsHiddenColourAndKeepsAlpha() {
        val w = 4
        val h = 1
        val pixels = intArrayOf(argb(255, 200, 100, 50), argb(0, 0, 0, 0), argb(0, 0, 0, 0), argb(0, 0, 0, 0))
        UpscaleTiles.bleed(pixels, w, h)
        for (i in 1 until 4) {
            assertEquals(0, pixels[i] ushr 24)
            assertEquals(argb(0, 200, 100, 50), pixels[i])
        }
        assertEquals(argb(255, 200, 100, 50), pixels[0])
    }

    @Test
    fun bleedPastItsRingsTakesTheMeanColour() {
        val w = 40
        val pixels = IntArray(w) { if (it == 0) argb(255, 90, 60, 30) else 0 }
        UpscaleTiles.bleed(pixels, w, 1, rings = 3)
        assertEquals(argb(0, 90, 60, 30), pixels[3])
        assertEquals(argb(0, 90, 60, 30), pixels[39])
    }

    @Test
    fun opaquePicturesNeedNoAlphaPass() {
        assertFalse(UpscaleTiles.hasAlpha(intArrayOf(argb(255, 1, 2, 3))))
        assertTrue(UpscaleTiles.hasAlpha(intArrayOf(argb(255, 1, 2, 3), argb(254, 1, 2, 3))))
    }

    @Test
    fun alphaSurvivesTheGreyRoundTrip() {
        val pixels = IntArray(256) { argb(it, 9, 9, 9) }
        val grey = UpscaleTiles.alphaAsGrey(pixels)
        assertArrayEquals(IntArray(256) { it }, IntArray(256) { UpscaleTiles.greyToAlpha(grey[it]) })
        assertTrue(grey.all { (it ushr 24) == 255 })
    }

    @Test
    fun largestFactorStaysUnderTheEdge() {
        assertEquals(4, UpscaleTiles.largestFactor(200, 100, 1024))
        assertEquals(2, UpscaleTiles.largestFactor(400, 100, 1024))
        assertEquals(0, UpscaleTiles.largestFactor(600, 100, 1024))
    }
}
