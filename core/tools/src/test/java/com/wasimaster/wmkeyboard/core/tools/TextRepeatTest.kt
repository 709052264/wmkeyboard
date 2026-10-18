package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.tools.TextRepeat.Mode
import com.wasimaster.wmkeyboard.core.tools.TextRepeat.Separator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRepeatTest {

    private fun spec(text: String, count: Int, tweak: (TextRepeat.Spec) -> TextRepeat.Spec = { it }) =
        tweak(TextRepeat.Spec(text, count))

    @Test
    fun repeatJoinsCopiesWithTheSeparator() {
        assertEquals("ha ha ha", TextRepeat.build(spec("ha", 3)).text)
        assertEquals("hahaha", TextRepeat.build(spec("ha", 3) { it.copy(separator = Separator.NONE) }).text)
        assertEquals("ha\nha", TextRepeat.build(spec("ha", 2) { it.copy(separator = Separator.NEWLINE) }).text)
        assertEquals("ha, ha", TextRepeat.build(spec("ha", 2) { it.copy(separator = Separator.COMMA) }).text)
        assertEquals(
            "ha | ha",
            TextRepeat.build(spec("ha", 2) { it.copy(separator = Separator.CUSTOM, customSeparator = " | ") }).text,
        )
    }

    @Test
    fun countIsClampedAndEmptyTextBuildsNothing() {
        assertEquals("x", TextRepeat.build(spec("x", 0)).text)
        assertEquals(TextRepeat.MAX_COUNT, TextRepeat.build(spec("x", 5000) { it.copy(maxChars = 20000) }).copies)
        val empty = TextRepeat.build(spec("", 5))
        assertEquals("", empty.text)
        assertEquals(0, empty.copies)
        assertFalse(empty.truncated)
    }

    @Test
    fun numberingAndAlternateCaseApplyPerCopy() {
        assertEquals(
            "1. hi\n2. hi\n3. hi",
            TextRepeat.build(spec("hi", 3) { it.copy(numbered = true, separator = Separator.NEWLINE) }).text,
        )
        assertEquals("hi HI hi HI", TextRepeat.build(spec("hi", 4) { it.copy(alternateCase = true) }).text)
        assertEquals(
            "1. hi 2. HI",
            TextRepeat.build(spec("hi", 2) { it.copy(numbered = true, alternateCase = true) }).text,
        )
    }

    @Test
    fun stairsGrowOneCopyAtATime() {
        assertEquals(
            "ha\nhaha\nhahaha",
            TextRepeat.build(spec("ha", 3) { it.copy(mode = Mode.STAIRS, separator = Separator.NEWLINE) }).text,
        )
        assertEquals(
            "1. ha 2. HAHA",
            TextRepeat.build(spec("ha", 2) { it.copy(mode = Mode.STAIRS, numbered = true, alternateCase = true) }).text,
        )
    }

    @Test
    fun stretchLengthensTheLastUserPerceivedCharacter() {
        assertEquals("nooooo", TextRepeat.build(spec("no", 4) { it.copy(mode = Mode.STRETCH) }).text)
        // A surrogate pair stays whole, and a separator means nothing here.
        assertEquals(
            "ok😂😂😂",
            TextRepeat.build(spec("ok😂", 2) { it.copy(mode = Mode.STRETCH, separator = Separator.NEWLINE) }).text,
        )
        // A base with its combining mark is one character.
        assertEquals(
            "ééé",
            TextRepeat.build(spec("é", 2) { it.copy(mode = Mode.STRETCH) }).text,
        )
    }

    @Test
    fun limitCutsAtWholeCopies() {
        val r = TextRepeat.build(spec("abcd", 1000) { it.copy(separator = Separator.NONE, maxChars = 500) })
        assertTrue(r.truncated)
        assertEquals(125, r.copies)
        assertEquals(500, r.text.length)
        val spaced = TextRepeat.build(spec("abcd", 1000) { it.copy(maxChars = 500) })
        assertEquals(100, spaced.copies)
        assertTrue(spaced.text.length <= 500)
        assertTrue(spaced.text.endsWith("abcd"))
        // The text alone over the limit: nothing, flagged.
        val huge = TextRepeat.build(spec("x".repeat(600), 2) { it.copy(maxChars = 500) })
        assertEquals(0, huge.copies)
        assertTrue(huge.truncated)
        val stretched = TextRepeat.build(spec("x".repeat(498), 10) { it.copy(mode = Mode.STRETCH, maxChars = 500) })
        assertEquals(2, stretched.copies)
        assertTrue(stretched.truncated)
    }

    @Test
    fun separatorPresetsRoundTrip() {
        assertEquals(Separator.SPACE, Separator.of(" "))
        assertEquals(Separator.NEWLINE, Separator.of("\n"))
        assertEquals(Separator.COMMA, Separator.of(", "))
        assertEquals(Separator.NONE, Separator.of(""))
        assertEquals(Separator.CUSTOM, Separator.of(","))
    }

    @Test
    fun countAfterAtLeastDoublesWhatWasTyped() {
        assertEquals(10, TextRepeat.countAfter(3, 10))
        assertEquals(10, TextRepeat.countAfter(5, 3))
        assertEquals(25, TextRepeat.countAfter(11, 10))
        assertEquals(300, TextRepeat.countAfter(150, 10))
    }

    // ---- the run detector ----

    private fun run(text: String, min: Int = 3, truncated: Boolean = false) = TextRepeat.findRun(text, min, truncated)

    @Test
    fun spacedWordsCountAsARun() {
        val r = run("no no no")!!
        assertEquals("no", r.unit)
        assertEquals(" ", r.separator)
        assertEquals(3, r.count)
        assertEquals(8, r.length)
        // One trailing separator is part of the run.
        assertEquals(9, run("no no no ")!!.length)
        assertEquals(4, run("the the the the")!!.count)
        assertEquals("very", run("it was very very very")!!.unit)
    }

    @Test
    fun lineAndCommaRunsAreRead() {
        val lines = run("ok\nok\nok")!!
        assertEquals("\n", lines.separator)
        assertEquals(3, lines.count)
        val commas = run("5, 5, 5")!!
        assertEquals("5", commas.unit)
        assertEquals(", ", commas.separator)
        assertEquals(",", run("a,a,a")!!.separator)
    }

    @Test
    fun gluedRunsNeedARealUnit() {
        val r = run("hahaha")!!
        assertEquals("ha", r.unit)
        assertEquals("", r.separator)
        assertEquals(3, r.count)
        assertEquals("lol", run("lollollol")!!.unit)
        for (typed in listOf("!!!!!!", "aaaaaa", "😂😂😂", "xhahaha", "haha")) {
            assertNull("\"$typed\" is not a run", run(typed))
        }
        // Spaced, a single glyph is fine.
        assertEquals(3, run("😂 😂 😂")!!.count)
        assertEquals(3, run("a a a")!!.count)
    }

    @Test
    fun theShortestUnitWins() {
        assertEquals("no", run("no no no no")!!.unit)
        assertEquals("ha", run("hahahaha")!!.unit)
    }

    @Test
    fun runsEndAtTheCursor() {
        assertNull(run("no no no x"))
        assertNull(run("no no no  "))
        assertNull(run("no no no."))
        assertNull(run(""))
        assertNull(run("no no", 3))
        assertEquals(2, run("no no", 2)!!.count)
    }

    @Test
    fun aCutOffWindowDropsItsFirstCopy() {
        assertEquals(3, run("no no no no", truncated = true)!!.count)
        assertNull(run("no no no", truncated = true))
        // Not at the window's edge: nothing to doubt.
        assertEquals(3, run(" no no no", truncated = true)!!.count)
    }
}
