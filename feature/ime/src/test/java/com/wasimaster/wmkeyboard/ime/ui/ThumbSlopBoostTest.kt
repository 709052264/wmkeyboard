package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #738: the two-thumb loop's post-typing cooldown, run per thumb from
 * the last tap on that thumb's own half of the board.
 */
class ThumbSlopBoostTest {

    private val cooldown = 160

    @Test
    fun `right after a tap the stroke has to travel the most`() {
        assertEquals(3.5f, thumbSlopBoost(sinceSideTapMs = 0L, cooldownMs = cooldown), 1e-4f)
    }

    @Test
    fun `the boost fades out across the window`() {
        assertEquals(2.25f, thumbSlopBoost(sinceSideTapMs = 80L, cooldownMs = cooldown), 1e-4f)
    }

    /** A deliberate swipe long after this thumb's last tap starts at the user's start distance. */
    @Test
    fun `after the window the start distance is the plain one`() {
        assertEquals(1f, thumbSlopBoost(sinceSideTapMs = 160L, cooldownMs = cooldown), 0f)
        assertEquals(1f, thumbSlopBoost(sinceSideTapMs = 5_000L, cooldownMs = cooldown), 0f)
    }

    /** No tap on this half yet: the stamp is 0 and the gap is the whole uptime. */
    @Test
    fun `a thumb that has not tapped is not held back`() {
        assertEquals(1f, thumbSlopBoost(sinceSideTapMs = 9_000_000L, cooldownMs = cooldown), 0f)
    }

    @Test
    fun `a cooldown of zero is off`() {
        assertEquals(1f, thumbSlopBoost(sinceSideTapMs = 0L, cooldownMs = 0), 0f)
    }

    @Test
    fun `a stamp from the future boosts nothing`() {
        assertEquals(1f, thumbSlopBoost(sinceSideTapMs = -5L, cooldownMs = cooldown), 0f)
    }
}
