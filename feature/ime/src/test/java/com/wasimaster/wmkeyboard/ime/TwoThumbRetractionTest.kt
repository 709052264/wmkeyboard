package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #747, the pure half: which pause a word waits, whether a letter lands
 * inside the window after an all-thumbs-up space, and the dictionary gate on
 * taking that space back. The service half is [TwoThumbRetractSpaceTest].
 */
class TwoThumbRetractionTest {

    private val words = setOf("car", "card", "carpet", "dog")
    private val live: (String) -> Boolean = { prefix -> words.any { it.startsWith(prefix) } }

    @Test
    fun `car then p leads on to carpet`() {
        assertTrue(continuesWord("car", setOf("p"), live))
    }

    @Test
    fun `car then x leads nowhere, and the space stays`() {
        assertFalse(continuesWord("car", setOf("x"), live))
    }

    @Test
    fun `a swipe passes the gate when any letter it may have started on leads on`() {
        assertTrue(continuesWord("car", setOf("o", "p", "l"), live))
        assertFalse(continuesWord("car", setOf("q", "w", "a"), live))
    }

    @Test
    fun `nothing reopens an empty word or on no letter`() {
        assertFalse(continuesWord("", setOf("c"), live))
        assertFalse(continuesWord("car", emptySet(), live))
    }

    @Test
    fun `a reopened word holds while it is on its way to a word`() {
        assertTrue(reopenedWordHolds("car", "carp", setOf("p"), complete = false, live))
        assertTrue(reopenedWordHolds("car", "carpet", setOf("p"), complete = false, live))
        assertTrue(reopenedWordHolds("car", "card", setOf("d"), complete = false, live))
    }

    @Test
    fun `car d o splits once cardo leads nowhere`() {
        assertFalse(reopenedWordHolds("car", "cardo", setOf("d"), complete = false, live))
    }

    @Test
    fun `a reading that does not carry the word on with its own first letter does not hold`() {
        // A decoder that read car plus a swipe from d as "cargo" turned the
        // swipe into letters it never started on.
        assertFalse(reopenedWordHolds("car", "cargo", setOf("d", "s", "f"), complete = true) { true })
        assertFalse(reopenedWordHolds("car", "car", setOf("p"), complete = true, live))
        assertFalse(reopenedWordHolds("car", "vat", setOf("t"), complete = true, live))
    }

    @Test
    fun `a complete reading holds without the prefix lookup`() {
        assertTrue(reopenedWordHolds("Car", "Carpool", setOf("p"), complete = true) { false })
    }

    @Test
    fun `the window counts from the space to the landing`() {
        assertTrue(insideRetractWindow(spaceAt = 1_000, landedAt = 1_000, windowMs = 700))
        assertTrue(insideRetractWindow(spaceAt = 1_000, landedAt = 1_700, windowMs = 700))
        assertFalse(insideRetractWindow(spaceAt = 1_000, landedAt = 1_701, windowMs = 700))
        assertFalse("a thumb that landed before the space", insideRetractWindow(1_000, 999, 700))
        assertFalse("a window of 0 is the switch off", insideRetractWindow(1_000, 1_000, 0))
    }

    @Test
    fun `swiped and tapped words wait their own pauses`() {
        val gesture = GestureSettings(twoThumbPauseMs = 300, twoThumbTapPauseMs = 500, twoThumbOneLetterPauseMs = 100)
        assertEquals(300L, allUpPauseMs(gesture, swiped = true, letters = 4))
        assertEquals(500L, allUpPauseMs(gesture, swiped = false, letters = 4))
    }

    @Test
    fun `a one-letter word waits at least the floor`() {
        val gesture = GestureSettings(twoThumbPauseMs = 300, twoThumbTapPauseMs = 300, twoThumbOneLetterPauseMs = 800)
        assertEquals(800L, allUpPauseMs(gesture, swiped = false, letters = 1))
        assertEquals(300L, allUpPauseMs(gesture, swiped = false, letters = 2))
        val low = gesture.copy(twoThumbOneLetterPauseMs = 100)
        assertEquals("the floor never shortens a pause", 300L, allUpPauseMs(low, swiped = false, letters = 1))
    }

    @Test
    fun `the defaults change nothing until a slider moves`() {
        val gesture = GestureSettings()
        val one = allUpPauseMs(gesture, swiped = true, letters = 5)
        assertEquals(one, allUpPauseMs(gesture, swiped = false, letters = 5))
        assertEquals(one, allUpPauseMs(gesture, swiped = false, letters = 1))
        assertEquals("the window is twice the pause", 2 * one, allUpRetractWindowMs(gesture))
    }
}
