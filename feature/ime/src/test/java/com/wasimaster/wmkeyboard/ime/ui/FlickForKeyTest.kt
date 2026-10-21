package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #746: under two-thumb typing a short flick off a letter types its
 * hint or its capital, judged per finger at the lift with the one-finger
 * loop's own tests.
 */
class FlickForKeyTest {

    private val keyHeight = 120f
    private val slop = 20f

    /** Straight along y from (50, 50), [dy] in total, over three samples in 80 ms. */
    private fun stroke(dy: Float, durationMs: Long = 80L): List<GesturePoint> = listOf(
        GesturePoint(50f, 50f, 1_000L),
        GesturePoint(50f, 50f + dy / 2, 1_000L + durationMs / 2),
        GesturePoint(51f, 50f + dy, 1_000L + durationMs),
    )

    @Test
    fun `a short flick down types the hint`() {
        assertEquals(ThumbFlick.HINT, flickForKey(stroke(70f), keyHeight, keyHeight, slop))
    }

    @Test
    fun `a short flick up types the capital`() {
        assertEquals(ThumbFlick.CAPITAL, flickForKey(stroke(-70f), keyHeight, keyHeight, slop))
    }

    /** With both settings off the loop reads every stroke as it did before. */
    @Test
    fun `with neither target the stroke is never a flick`() {
        assertEquals(ThumbFlick.NONE, flickForKey(stroke(70f), null, null, slop))
        assertEquals(ThumbFlick.NONE, flickForKey(stroke(-70f), null, null, slop))
    }

    @Test
    fun `each direction answers only to its own setting`() {
        assertEquals(ThumbFlick.NONE, flickForKey(stroke(-70f), keyHeight, null, slop))
        assertEquals(ThumbFlick.NONE, flickForKey(stroke(70f), null, keyHeight, slop))
    }

    /** A swipe leg that runs on past two key heights is part of a word. */
    @Test
    fun `a long vertical swipe is a swipe`() {
        assertEquals(ThumbFlick.NONE, flickForKey(stroke(300f), keyHeight, keyHeight, slop))
    }

    @Test
    fun `a sideways swipe is a swipe`() {
        val points = listOf(
            GesturePoint(50f, 50f, 1_000L),
            GesturePoint(120f, 55f, 1_040L),
            GesturePoint(190f, 60f, 1_080L),
        )
        assertEquals(ThumbFlick.NONE, flickForKey(points, keyHeight, keyHeight, slop))
    }

    /** A thumb that rested before moving drew a slow stroke from its landing, which no flick is. */
    @Test
    fun `a slow stroke is not a flick`() {
        assertEquals(ThumbFlick.NONE, flickForKey(stroke(70f, durationMs = 400L), keyHeight, keyHeight, slop))
    }

    /** Right after a tap the start distance grows, and the flick floor with it. */
    @Test
    fun `the floor follows the stroke's own start distance`() {
        assertEquals(ThumbFlick.NONE, flickForKey(stroke(70f), keyHeight, keyHeight, startSlopPx = 70f))
    }

    /** A two-thumb fragment like `o`→`k` lifts on the next letter: part of the word (#859). */
    @Test
    fun `a fragment that lifts on another letter is not a flick`() {
        val onLetter: (Float, Float) -> Boolean = { _, y -> y >= 118f }
        assertEquals(
            ThumbFlick.NONE,
            flickForKey(stroke(70f), keyHeight, keyHeight, slop, landsOnLetter = onLetter),
        )
        assertEquals(
            ThumbFlick.HINT,
            flickForKey(stroke(65f), keyHeight, keyHeight, slop, landsOnLetter = onLetter),
        )
    }
}
