package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scalloped outline counts its bumps from the box it is given, and a box
 * laid out at zero (#963) used to take the keyboard down: NaN for zero by
 * zero, Int.MAX_VALUE bumps for a box with width and no height.
 */
class ScallopKeyShapeTest {

    @Test
    fun `a box with no area gets no bumps`() {
        assertNull(scallopBumps(0f, 0f))
        assertNull(scallopBumps(120f, 0f))
        assertNull(scallopBumps(0f, 48f))
        assertNull(scallopBumps(Float.NaN, 48f))
        assertNull(scallopBumps(Float.POSITIVE_INFINITY, 48f))
    }

    @Test
    fun `a box a pixel or two tall gets no bumps`() {
        // #1001: a wide box growing in from nothing asked for thousands of arcs.
        assertNull(scallopBumps(1080f, 0.05f))
        assertNull(scallopBumps(1080f, 6f))
    }

    @Test
    fun `no side gets more than the cap`() {
        assertEquals(64, scallopBumps(20_000f, 70f)!!.first)
        assertEquals(64, scallopBumps(70f, 20_000f)!!.second)
    }

    @Test
    fun `a square key gets the fewest bumps`() {
        // Depth 10: five depths of side left between the corners, two and a
        // half bumps' worth, rounded.
        assertEquals(3 to 3, scallopBumps(70f, 70f))
    }

    @Test
    fun `a spacebar gets more bumps, not longer ones`() {
        val (across, down) = scallopBumps(700f, 70f)!!
        assertEquals(3, down)
        assertEquals(34, across)
    }
}
