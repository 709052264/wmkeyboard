package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.input.pointer.PointerId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #737: under two-thumb typing a thumb waiting on a key while the other
 * types is resting, not long-pressing, and a third finger is ignored while the
 * thumbs draw.
 */
class RestingThumbTest {

    // ---- isRestingThumb -----------------------------------------------------

    @Test
    fun `a still thumb held through the other's typing rests`() {
        assertTrue(isRestingThumb(stillWithinSlop = true, otherInputDuringHold = true, heldMs = RESTING_HOLD_MS))
        assertTrue(isRestingThumb(stillWithinSlop = true, otherInputDuringHold = true, heldMs = 2_000L))
    }

    /** One thumb landing before the other lifts is a roll, and both letters type. */
    @Test
    fun `a short overlap with the other thumb is a tap`() {
        assertFalse(isRestingThumb(stillWithinSlop = true, otherInputDuringHold = true, heldMs = 150L))
        assertFalse(isRestingThumb(stillWithinSlop = true, otherInputDuringHold = true, heldMs = RESTING_HOLD_MS - 1))
    }

    /** The other thumb idle: a deliberate long press, which keeps its popup and its lift. */
    @Test
    fun `a hold with nothing else going on is not resting`() {
        assertFalse(isRestingThumb(stillWithinSlop = true, otherInputDuringHold = false, heldMs = 2_000L))
    }

    @Test
    fun `a thumb that moved off its key is not resting`() {
        assertFalse(isRestingThumb(stillWithinSlop = false, otherInputDuringHold = true, heldMs = 2_000L))
    }

    // ---- ignoresExtraPointer ------------------------------------------------

    @Test
    fun `a third finger while one thumb swipes is ignored`() {
        assertTrue(ignoresExtraPointer(pointersDown = 2, anySwiping = true))
    }

    @Test
    fun `a third finger while both thumbs only rest still counts`() {
        assertFalse(ignoresExtraPointer(pointersDown = 2, anySwiping = false))
    }

    @Test
    fun `the second thumb is never ignored`() {
        assertFalse(ignoresExtraPointer(pointersDown = 1, anySwiping = true))
        assertFalse(ignoresExtraPointer(pointersDown = 0, anySwiping = false))
    }

    // ---- RestGate -----------------------------------------------------------

    private val left = PointerId(1L)
    private val right = PointerId(2L)

    /** Two-thumb typing off writes nothing, so no key's long press changes. */
    @Test
    fun `an empty gate quiets nothing`() {
        assertFalse(RestGate().quiets(left))
    }

    @Test
    fun `a lone finger long-presses as always`() {
        val gate = RestGate()
        gate.press(left, canRest = true)
        assertFalse(gate.quiets(left))
    }

    @Test
    fun `a long press due while the other thumb is down does nothing`() {
        val gate = RestGate()
        gate.press(left, canRest = true)
        gate.press(right, canRest = true)
        assertTrue(gate.quiets(left))
    }

    @Test
    fun `input from a thumb that has lifted again still quiets`() {
        val gate = RestGate()
        gate.press(left, canRest = true)
        gate.press(right, canRest = true)
        gate.quiet(left)
        gate.lift(right)
        assertTrue(gate.quiets(left))
    }

    /** Shift, space, delete: their holds are spoken for whatever the other thumb does. */
    @Test
    fun `a key the loop leaves its stroke to keeps its hold`() {
        val gate = RestGate()
        gate.press(left, canRest = false)
        gate.press(right, canRest = true)
        gate.quiet(left)
        assertFalse(gate.quiets(left))
    }

    @Test
    fun `clearing forgets every finger`() {
        val gate = RestGate()
        gate.press(left, canRest = true)
        gate.press(right, canRest = true)
        gate.quiet(left)
        gate.longPressed(right)
        gate.clear()
        assertFalse(gate.quiets(left))
        assertFalse(gate.hasLongPressed(right))
    }
}
