package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.prediction.WordRanks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold a strip word and slide it (#790): where the rank lands, and where blocking starts. */
class RankSlideTest {

    @Test
    fun `steps move the rank from where the word already sat`() {
        assertEquals(3, RankSlide("form", held = 1, steps = 2).target)
        assertEquals(-2, RankSlide("form", held = 1, steps = -3).target)
    }

    @Test
    fun `the rank stops at the word card's own bounds`() {
        assertEquals(RankSlide.MAX_RANK, RankSlide("form", held = 9, steps = 5).target)
        assertEquals(RankSlide.MIN_RANK, RankSlide("form", held = -9, steps = -5).target)
    }

    @Test
    fun `the bounds are the store's`() {
        assertEquals(WordRanks.MIN_STEPS, RankSlide.MIN_RANK)
        assertEquals(WordRanks.MAX_STEPS, RankSlide.MAX_RANK)
    }

    @Test
    fun `far enough down is never suggest, and nothing short of it is`() {
        assertFalse(RankSlide("form", held = 0, steps = -(RankSlide.BLOCK_STEPS - 1)).blocks)
        assertTrue(RankSlide("form", held = 0, steps = -RankSlide.BLOCK_STEPS).blocks)
        assertFalse(RankSlide("form", held = 0, steps = 20).blocks)
    }

    @Test
    fun `the badge writes a rank with its sign`() {
        assertEquals("+2", signedRank(2))
        assertEquals("0", signedRank(0))
        assertEquals("−3", signedRank(-3))
    }
}
