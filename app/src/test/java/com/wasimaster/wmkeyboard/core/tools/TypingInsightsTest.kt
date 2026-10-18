package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Streaks, bests, sources and equivalents on the Statistics screen. */
class TypingInsightsTest {

    private fun day(epochDay: Int, words: Long = 10, voice: Long = 0) =
        TypingStats.DayEntry(epochDay, chars = words * 5, words = words, backspaces = 0, activeMs = 0, voiceWords = voice)

    private fun totals(
        words: Long = 0,
        chars: Long = 0,
        backspaces: Long = 0,
        predicted: Long = 0,
        completed: Long = 0,
        glide: Long = 0,
        twoThumb: Long = 0,
        voice: Long = 0,
        voiceMs: Long = 0,
        letters: Long = 0,
        saved: Long = 0,
        activeMs: Long = 0,
        distanceMm: Double = 0.0,
    ) = TypingStats.Totals(
        chars = chars, words = words, backspaces = backspaces, activeMs = activeMs, hourHistogram = emptyList(),
        keystrokesSaved = saved, wordsPredicted = predicted, wordsCompleted = completed,
        glideWords = glide, glideDistanceMm = distanceMm, twoThumbWords = twoThumb,
        voiceWords = voice, voiceMs = voiceMs, wordLetters = letters,
    )

    @Test
    fun `streaks count days in a row and stay alive until a day is missed`() {
        val entries = listOf(day(1), day(2), day(3), day(7), day(8), day(9, words = 0, voice = 4))
        assertEquals(3, TypingInsights.longestStreak(entries))
        assertEquals("today, with voice only", 3, TypingInsights.currentStreak(entries, today = 9))
        assertEquals("nothing yet today still counts from yesterday", 3, TypingInsights.currentStreak(entries, today = 10))
        assertEquals("a full day missed ends it", 0, TypingInsights.currentStreak(entries, today = 11))
        assertEquals(0, TypingInsights.longestStreak(emptyList()))
    }

    @Test
    fun `the best day counts typed and spoken words`() {
        val entries = listOf(day(1, words = 30), day(2, words = 10, voice = 25), day(3, words = 0))
        assertEquals(2, TypingInsights.bestDay(entries)?.epochDay)
        assertNull(TypingInsights.bestDay(listOf(day(1, words = 0))))
    }

    @Test
    fun `sources split every word once`() {
        val t = totals(words = 100, predicted = 10, completed = 20, glide = 30, twoThumb = 5, voice = 7)
        val sources = TypingInsights.sources(t).toMap()
        assertEquals(40L, sources[TypingInsights.Source.TYPED])
        assertEquals(25L, sources[TypingInsights.Source.GLIDED])
        assertEquals(5L, sources[TypingInsights.Source.TWO_THUMB])
        assertEquals(7L, sources[TypingInsights.Source.SPOKEN])
        assertEquals(107L, sources.values.sum())
    }

    @Test
    fun `habits and equivalents`() {
        val t = totals(
            words = 160_000, chars = 1_000, backspaces = 50, letters = 720_000,
            voice = 120, voiceMs = 60_000, saved = 400, distanceMm = 800_000.0,
        )
        assertEquals(4.5, TypingInsights.averageWordLength(t), 1e-9)
        assertEquals(5.0, TypingInsights.correctionRate(t), 1e-9)
        assertEquals(120.0, TypingInsights.speakingWpm(t), 1e-9)
        assertEquals(160_120.0 / 80_000, TypingInsights.novels(t), 1e-9)
        assertEquals(2.0, TypingInsights.trackLaps(t), 1e-9)
        assertEquals(1_050L, TypingInsights.keyPresses(t))
        // No speed of its own yet: 400 characters at 40 wpm (200 a minute) is 2 minutes.
        assertEquals(120_000L, TypingInsights.timeSavedMs(t))
        assertEquals(0.0, TypingInsights.speakingWpm(totals(voice = 5, voiceMs = 30_000)), 0.0)
    }

    @Test
    fun `the busiest weekday sums its hours`() {
        val card = MutableList(168) { 0L }
        card[2 * 24 + 9] = 5
        card[4 * 24 + 1] = 3
        card[4 * 24 + 2] = 3
        assertEquals(4, TypingInsights.busiestWeekday(card))
        assertEquals(-1, TypingInsights.busiestWeekday(List(168) { 0L }))
        assertEquals(-1, TypingInsights.busiestWeekday(emptyList()))
    }
}
