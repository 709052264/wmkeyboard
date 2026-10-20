package com.wasimaster.wmkeyboard.core.docscan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCornersTest {

    private val tilted = PageCorners(floatArrayOf(100f, 50f, 900f, 120f, 860f, 1300f, 60f, 1220f))

    @Test
    fun aPageIsConvexAndCrossedCornersAreNot() {
        assertTrue(tilted.convex)
        // Top right dragged past the bottom right: the outline folds over itself.
        assertFalse(tilted.moved(1, 860f, 1400f).moved(2, 900f, 120f).convex)
        // Three corners on one line make no page.
        assertFalse(PageCorners(floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f, 0f, 100f)).convex)
    }

    @Test
    fun pageSizeTakesTheLongerOfOppositeSides() {
        val (w, h) = PageCorners(floatArrayOf(0f, 0f, 800f, 0f, 700f, 1000f, 100f, 1000f)).pageSize(4000)
        assertEquals(800, w)
        assertEquals(1005, h)
    }

    @Test
    fun pageSizeScalesDownToTheLimitKeepingTheShape() {
        val (w, h) = PageCorners.whole(4001, 3001).pageSize(2000)
        assertEquals(2000, w)
        assertEquals(1500, h)
    }

    @Test
    fun aQuarterTurnMovesEachCornerAndStartsFromTheNewTopLeft() {
        // A 640x480 sensor frame turned upright (90 degrees clockwise) is 480x640.
        val frame = PageCorners(floatArrayOf(10f, 20f, 600f, 30f, 590f, 460f, 15f, 450f))
        val upright = frame.rotated(90, 640, 480)
        // The frame's bottom left (15, 450) lands at the top left: (479 - 450, 15).
        assertArrayEquals(
            floatArrayOf(29f, 15f, 459f, 10f, 449f, 600f, 19f, 590f),
            upright.toArray(),
            0.001f,
        )
        assertTrue(upright.convex)
    }

    @Test
    fun turningAllTheWayRoundGivesTheSameCorners() {
        var c = tilted
        repeat(4) { c = c.rotated(90, if (it % 2 == 0) 1000 else 1400, if (it % 2 == 0) 1400 else 1000) }
        assertArrayEquals(tilted.toArray(), c.rotated(0, 1000, 1400).toArray(), 0.001f)
    }
}
