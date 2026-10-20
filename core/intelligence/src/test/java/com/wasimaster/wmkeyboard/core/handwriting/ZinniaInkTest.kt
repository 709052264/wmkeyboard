package com.wasimaster.wmkeyboard.core.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry zinnia's recogniser depends on: where one character ends and
 * the next begins, and how a character lands in the models' 1000-unit box.
 * The recogniser itself is checked on the desktop build (native/zinnia-jni
 * README), since a JVM test has no libwmzinnia.
 */
class ZinniaInkTest {

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        HwStroke(listOf(HwPoint(x0, y0, 0), HwPoint((x0 + x1) / 2, (y0 + y1) / 2, 8), HwPoint(x1, y1, 16)))

    /** 川 written 300 px tall: three downstrokes with gaps of about a quarter of its height. */
    private fun chuan(left: Float) = listOf(
        line(left, 20f, left - 20f, 320f),
        line(left + 90f, 40f, left + 90f, 280f),
        line(left + 180f, 20f, left + 185f, 320f),
    )

    @Test
    fun `a character whose parts stand apart stays one character`() {
        assertEquals(1, ZinniaInk.segment(chuan(100f), areaHeight = 400f).size)
    }

    @Test
    fun `two characters with a clear gap are read one at a time`() {
        val groups = ZinniaInk.segment(chuan(100f) + chuan(500f), areaHeight = 400f)
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].size)
        assertEquals(3, groups[1].size)
    }

    @Test
    fun `a stroke going back over an earlier character joins that one`() {
        // A dot added to the first character after the second was written.
        val ink = chuan(100f) + chuan(500f) + listOf(line(150f, 0f, 160f, 10f))
        val groups = ZinniaInk.segment(ink, areaHeight = 400f)
        assertEquals(2, groups.size)
        assertEquals(4, groups[0].size)
        assertEquals(3, groups[1].size)
    }

    @Test
    fun `fit centres the character and scales its longer side`() {
        val encoded = ZinniaInk.fit(chuan(700f), minSide = 0f)
        val xs = encoded.points.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = encoded.points.filterIndexed { i, _ -> i % 2 == 1 }
        // 300 px tall becomes about 820 units, centred near (500, 490),
        // wherever on the canvas it was written.
        assertEquals(820, ys.max() - ys.min(), 2)
        assertEquals(490, (ys.max() + ys.min()) / 2, 2)
        assertEquals(500, (xs.max() + xs.min()) / 2, 2)
        assertEquals(listOf(3, 6, 9), encoded.strokeEnds.toList())
    }

    @Test
    fun `a short mark is not blown up to the full box`() {
        // A 20 px dash on a 400 px canvas: the floor is 120 px, not 20.
        val encoded = ZinniaInk.fit(listOf(line(100f, 200f, 120f, 200f)), ZinniaInk.minSideFor(400f))
        val xs = encoded.points.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue(xs.max() - xs.min() < 200)
    }

    @Test
    fun `empty strokes are dropped`() {
        val ink = listOf(HwStroke(emptyList())) + chuan(100f)
        assertEquals(1, ZinniaInk.segment(ink, 400f).size)
        assertEquals(3, ZinniaInk.fit(ink, 0f).strokeEnds.size)
        assertTrue(ZinniaInk.segment(emptyList(), 400f).isEmpty())
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected ± $tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
