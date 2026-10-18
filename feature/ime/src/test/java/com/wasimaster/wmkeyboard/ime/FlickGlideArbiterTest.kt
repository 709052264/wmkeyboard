package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flick arms and glide typing on the same keys (#720), on the reporter's
 * board: an AZERTY imported from Unexpected Keyboard, with arms on the
 * corners of nearly every letter. Cells are 100 × 150 px, the shape of a
 * phone's 36 × 55 dp key.
 */
class FlickGlideArbiterTest {

    private val w = 100f
    private val h = 150f
    private val rows = listOf("azertyuiop", "qsdfghjklm")

    private val arms: Map<Char, Set<FlickDirection>> = mapOf(
        'a' to setOf(FlickDirection.UP_RIGHT, FlickDirection.DOWN_RIGHT),
        'e' to setOf(FlickDirection.UP_RIGHT, FlickDirection.DOWN_LEFT, FlickDirection.DOWN_RIGHT),
        't' to setOf(FlickDirection.UP_RIGHT, FlickDirection.DOWN_LEFT, FlickDirection.DOWN_RIGHT),
        'd' to setOf(FlickDirection.UP_RIGHT),
        'l' to setOf(FlickDirection.UP_RIGHT),
        'p' to setOf(FlickDirection.UP_RIGHT),
    )

    private fun cellOf(letter: Char): Rect {
        val row = rows.indexOfFirst { letter in it }
        val col = rows[row].indexOf(letter)
        return Rect(col * w, row * h, (col + 1) * w, (row + 1) * h)
    }

    private fun letterCellAt(x: Float, y: Float): Rect? {
        val row = (y / h).toInt().takeIf { y >= 0f && it < rows.size } ?: return null
        val col = (x / w).toInt().takeIf { x >= 0f && it < rows[row].length } ?: return null
        return cellOf(rows[row][col])
    }

    private fun arbiter(key: Char, ontoLetterGlides: Boolean = false): FlickGlideArbiter {
        val cell = cellOf(key)
        return FlickGlideArbiter(
            downX = cell.center.x,
            downY = cell.center.y,
            cell = cell,
            hasArm = { it in arms.getValue(key) },
            reachPx = 1.41f * kotlin.math.hypot(w, h),
            slopPx = 60f,
            keyWidthPx = w,
            minWordReachPx = w * 0.65f,
            ontoLetterGlides = ontoLetterGlides,
            letterCellAt = ::letterCellAt,
        )
    }

    /** A stroke through [via], sampled every 10 px. */
    private fun path(vararg via: Pair<Float, Float>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until via.size - 1) {
            val (x0, y0) = via[i]
            val (x1, y1) = via[i + 1]
            val steps = maxOf(1, (kotlin.math.hypot(x1 - x0, y1 - y0) / 10f).toInt())
            for (s in 1..steps) out.add(x0 + (x1 - x0) * s / steps to y0 + (y1 - y0) * s / steps)
        }
        return out
    }

    private fun centre(letter: Char) = cellOf(letter).center.let { it.x to it.y }

    /** Index of the sample the arbiter latched to glide at, or -1 if it never did. */
    private fun latchAt(arbiter: FlickGlideArbiter, points: List<Pair<Float, Float>>): Int =
        points.dropLast(1).indexOfFirst { (x, y) -> arbiter.step(x, y) }

    /** What the stroke came to: "glide" for a mid-move latch, else the lift's verdict. */
    private fun judge(key: Char, points: List<Pair<Float, Float>>, ontoLetterGlides: Boolean = false): Any {
        val arbiter = arbiter(key, ontoLetterGlides)
        if (latchAt(arbiter, points) >= 0) return "glide"
        val (x, y) = points.last()
        return arbiter.lift(x, y)
    }

    @Test
    fun `a corner flick that overshoots into the next key is still the arm`() {
        // d's up-right arm (é) aims at the corner it shares with e and r, and
        // the finger carries on into r's core.
        val stroke = path(centre('d'), 330f to 110f)
        assertEquals(FlickGlideVerdict.FLICK, judge('d', stroke))
    }

    @Test
    fun `a sideways two-letter glide latches as soon as it reaches the second letter`() {
        // "er": e's arms are all on its corners, and nothing points right.
        val arbiter = arbiter('e')
        val stroke = path(centre('e'), centre('r'))
        val at = latchAt(arbiter, stroke)
        assertTrue("never latched", at >= 0)
        // r's core starts 20 px into its cell.
        assertTrue(stroke[at].first >= 320f)
    }

    @Test
    fun `a glide that turns is a glide even when it set off along an arm`() {
        // "the": t to h runs exactly along t's down-right arm, then turns for e.
        val arbiter = arbiter('t')
        val stroke = path(centre('t'), centre('h'), centre('e'))
        val at = latchAt(arbiter, stroke)
        assertTrue("never latched", at >= 0)
        val (hx, _) = centre('h')
        assertTrue("latched before the turn", stroke[at].first < hx)
    }

    @Test
    fun `words that come back to where they started glide`() {
        assertEquals("glide", judge('p', path(centre('p'), centre('o'), centre('p'))))
        assertEquals("glide", judge('l', path(centre('l'), centre('o'), centre('l'))))
    }

    @Test
    fun `a stroke onto a neighbour along an arm takes the arm unless asked otherwise`() {
        // "as" runs down a's down-right arm.
        val stroke = path(centre('a'), centre('s'))
        assertEquals(FlickGlideVerdict.FLICK, judge('a', stroke))
        assertEquals(FlickGlideVerdict.WORD, judge('a', stroke, ontoLetterGlides = true))
    }

    @Test
    fun `out to an arm and back is the arm, for its capital`() {
        val stroke = path(centre('d'), 310f to 140f, 265f to 200f)
        assertEquals(FlickGlideVerdict.FLICK, judge('d', stroke))
    }

    @Test
    fun `a straight stroke along an arm becomes a glide past the reach`() {
        val arbiter = arbiter('d')
        val (dx, dy) = centre('d')
        // Along the cell's own corner bearing, out past 1.41 diagonals (254 px).
        val stroke = path(dx to dy, dx + 0.555f * 300f to dy - 0.832f * 300f)
        val at = latchAt(arbiter, stroke)
        assertTrue("never latched", at >= 0)
        val (x, y) = stroke[at]
        assertTrue(kotlin.math.hypot(x - dx, y - dy) > 254f)
    }

    @Test
    fun `a short roll inside the key goes back to the key`() {
        val stroke = path(centre('e'), 290f to 75f)
        assertEquals(FlickGlideVerdict.KEY, judge('e', stroke))
    }

    @Test
    fun `corner arms point at the cell's real corner`() {
        assertEquals(33.69f, armBearing(FlickDirection.UP_RIGHT, w, h), 0.01f)
        assertEquals(146.31f, armBearing(FlickDirection.DOWN_RIGHT, w, h), 0.01f)
        assertEquals(213.69f, armBearing(FlickDirection.DOWN_LEFT, w, h), 0.01f)
        assertEquals(326.31f, armBearing(FlickDirection.UP_LEFT, w, h), 0.01f)
        assertEquals(90f, armBearing(FlickDirection.RIGHT, w, h), 0f)
        assertEquals(45f, armBearing(FlickDirection.UP_RIGHT, w, w), 0.001f)
    }
}
