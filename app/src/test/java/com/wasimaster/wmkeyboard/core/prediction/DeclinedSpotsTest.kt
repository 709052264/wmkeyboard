package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclinedSpotsTest {

    /** "Wassup" at [start] corrected to "Wasp", then deleted letter by letter. */
    private fun DeclinedSpots.fireAndDelete(start: Int) {
        fired(start, "Wassup", "Wasp")
        for (end in start + 4 downTo start + 1) onDeleted(end - 1, end)
    }

    @Test
    fun twoDeletionsAtOneSpotStopTheCorrection() {
        val spots = DeclinedSpots()
        spots.fireAndDelete(10)
        assertFalse(spots.blocks(10, "Wassup", threshold = 2))
        spots.fireAndDelete(10)
        assertTrue(spots.blocks(10, "Wassup", threshold = 2))
    }

    @Test
    fun oneDeletionCountsOnceHoweverManyLettersGo() {
        val spots = DeclinedSpots()
        spots.fireAndDelete(10)
        assertFalse(spots.blocks(10, "Wassup", threshold = 2))
        assertTrue(spots.blocks(10, "Wassup", threshold = 1))
    }

    @Test
    fun deletingTheSpaceAfterTheCorrectionIsNotADecline() {
        val spots = DeclinedSpots()
        spots.fired(10, "teh", "the")
        spots.onDeleted(13, 14)
        assertFalse(spots.blocks(10, "teh", threshold = 1))
    }

    @Test
    fun anUndoCountsLikeADeletion() {
        val spots = DeclinedSpots()
        spots.fired(0, "teh", "the")
        spots.undone("teh")
        spots.fired(0, "teh", "the")
        spots.undone("teh")
        assertTrue(spots.blocks(0, "teh", threshold = 2))
    }

    @Test
    fun anUndoAndADeletionOfTheSameCorrectionCountOnce() {
        val spots = DeclinedSpots()
        spots.fired(0, "teh", "the")
        spots.undone("teh")
        spots.onDeleted(0, 3)
        assertFalse(spots.blocks(0, "teh", threshold = 2))
    }

    @Test
    fun onlyThatSpotAndThatSpellingAreLeftAlone() {
        val spots = DeclinedSpots()
        spots.fireAndDelete(10)
        spots.fireAndDelete(10)
        assertFalse(spots.blocks(40, "Wassup", threshold = 2))
        assertFalse(spots.blocks(10, "Wassap", threshold = 2))
        assertTrue(spots.blocks(11, "wassup", threshold = 2))
    }

    @Test
    fun aSpotMovesWithTextDeletedInFrontOfIt() {
        val spots = DeclinedSpots()
        spots.fireAndDelete(20)
        spots.fireAndDelete(20)
        spots.onDeleted(0, 5)
        assertTrue(spots.blocks(15, "Wassup", threshold = 2))
        assertFalse(spots.blocks(20, "Wassup", threshold = 2))
    }

    @Test
    fun zeroTurnsItOff() {
        val spots = DeclinedSpots()
        spots.fireAndDelete(0)
        spots.fireAndDelete(0)
        assertFalse(spots.blocks(0, "Wassup", threshold = 0))
    }

    @Test
    fun clearForgetsEverySpot() {
        val spots = DeclinedSpots()
        spots.fireAndDelete(0)
        spots.clear()
        assertFalse(spots.blocks(0, "Wassup", threshold = 1))
    }
}
