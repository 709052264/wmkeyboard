package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spacebar's live speed readout (#752). */
class LiveTypingSpeedTest {

    private var now = 100_000L
    private val meter = LiveTypingSpeed { now }

    /** Types [word] at [msPerChar], then a space, advancing the clock. */
    private fun type(word: String, msPerChar: Long, into: List<LiveTypingSpeed> = listOf(meter)) {
        for (char in word) {
            now += msPerChar
            into.forEach { it.onTyped(true, 1, now) }
        }
        now += msPerChar
        into.forEach { it.onSeparator(now) }
    }

    @Test
    fun `a steady rhythm reads its speed`() {
        // Five letters and a space every 1.2 s is 6 characters a 1.2 s, 60 wpm.
        repeat(10) { type("abcde", 200L) }
        assertEquals(60.0, meter.reading(now).toDouble(), 3.0)
    }

    @Test
    fun `one word is not a reading`() {
        type("hello", 200L)
        assertEquals(0, meter.reading(now))
    }

    @Test
    fun `a pause past the idle break clears it`() {
        repeat(5) { type("abcde", 200L) }
        assertTrue(meter.reading(now) > 0)
        assertEquals(0, meter.reading(now + LiveTypingSpeed.IDLE_BREAK_MS + 1))
        // Typing again starts a new history: a single word shows nothing yet.
        now += LiveTypingSpeed.IDLE_BREAK_MS + 1_000L
        type("abcde", 200L)
        assertEquals(0, meter.reading(now))
    }

    @Test
    fun `old words leave the window and the shown number eases towards the new pace`() {
        repeat(30) { type("abcde", 200L) } // 60 wpm, well past the window
        val before = meter.reading(now)
        type("abcde", 100L) // one word at twice the pace
        val after = meter.reading(now)
        assertTrue("moves up", after >= before)
        assertTrue("but only part of the way", after < 120)
        // Once the window holds only the faster words, the number follows them.
        repeat(80) { type("abcde", 100L) }
        assertEquals(120.0, meter.reading(now).toDouble(), 6.0)
    }

    @Test
    fun `a whole word lands at once from a pick or a stroke`() {
        type("hello", 200L)
        now += 1_500L
        // A glide that started 600 ms ago.
        meter.onWord(letters = 5, startUptimeMs = now - 600L)
        assertTrue(meter.reading(now) > 0)
        // A pick finishing typed letters takes the typed word's start.
        now += 400L
        meter.onTyped(true, 1, now)
        now += 200L
        meter.onWord(letters = 0, startUptimeMs = 0L)
        assertTrue(meter.reading(now) > 0)
    }

    @Test
    fun `a private field times nothing and reads nothing`() {
        meter.privateField = true
        repeat(10) { type("abcde", 200L) }
        now += 400L
        meter.onWord(letters = 5, startUptimeMs = now - 600L)
        assertEquals(0, meter.reading(now))
        // Out of it, the meter reads as one that never saw the private words.
        meter.privateField = false
        val fresh = LiveTypingSpeed { now }
        type("abcde", 200L, listOf(meter, fresh))
        assertEquals("one word is not a reading", 0, meter.reading(now))
        type("abcde", 200L, listOf(meter, fresh))
        assertTrue(fresh.reading(now) > 0)
        assertEquals(fresh.reading(now), meter.reading(now))
    }

    @Test
    fun `turning private forgets the words typed before it`() {
        repeat(5) { type("abcde", 200L) }
        assertTrue(meter.reading(now) > 0)
        meter.privateField = true
        assertEquals(0, meter.reading(now))
        meter.privateField = false
        assertEquals("nothing from before comes back", 0, meter.reading(now))
        type("abcde", 200L)
        assertEquals("one word after it is not a reading yet", 0, meter.reading(now))
    }

    @Test
    fun `saying again that the field is not private keeps the words`() {
        // The service restates the gate before every event it feeds; a meter
        // that is never told reads the same.
        val control = LiveTypingSpeed { now }
        repeat(5) {
            meter.privateField = false
            type("abcde", 200L, listOf(meter, control))
        }
        assertTrue(control.reading(now) > 0)
        assertEquals(control.reading(now), meter.reading(now))
    }
}
