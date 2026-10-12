package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipRulesTest {

    private val stripScheme = ClipCopyRule("https?://(www\\.)?")

    @Test fun `a rule removes its match wherever it is in the copy`() {
        val rules = listOf(stripScheme)
        assertEquals(
            "wmkeyboard.pages.dev/tools/clipboard/#numbered-clips",
            ClipCopyRules.apply("https://wmkeyboard.pages.dev/tools/clipboard/#numbered-clips", rules),
        )
        assertEquals("github.com/wasi-master/wmkeyboard/", ClipCopyRules.apply("https://www.github.com/wasi-master/wmkeyboard/", rules))
        assertEquals(
            "New keyboard: github.com/wasi-master/wmkeyboard/. Seems highly recommended",
            ClipCopyRules.apply("New keyboard: https://www.github.com/wasi-master/wmkeyboard/. Seems highly recommended", rules),
        )
    }

    @Test fun `a replacement can add to the match`() {
        val rules = listOf(ClipCopyRule("(\\d{6})", "code $1"))
        assertEquals("Your code 482913 here", ClipCopyRules.apply("Your 482913 here", rules))
    }

    @Test fun `off, broken and empty rules leave the copy alone`() {
        val rules = listOf(
            stripScheme.copy(enabled = false),
            ClipCopyRule("[0-9"),
            ClipCopyRule(""),
            ClipCopyRule("x", "$9"),
        )
        assertEquals("https://x.com", ClipCopyRules.apply("https://x.com", rules))
    }

    @Test fun `rules survive a round trip through storage`() {
        val rules = listOf(stripScheme, ClipCopyRule("a", "b", enabled = false))
        assertEquals(rules, ClipCopyRules.decode(ClipCopyRules.encode(rules)))
        assertEquals(emptyList<ClipCopyRule>(), ClipCopyRules.decode("not json"))
        assertEquals(emptyList<ClipCopyRule>(), ClipCopyRules.decode(null))
    }

    @Test fun `clips join with the joiner between them`() {
        assertEquals("a | b | c", ClipCombine.combine(listOf("a", "b", "c"), " | "))
        assertEquals("a\nb", ClipCombine.combine(listOf("a", "b"), "\n"))
    }

    @Test fun `base and append keep only the matching part of each clip`() {
        val joined = ClipCombine.combine(
            listOf("https://m.youtube.com/watch?v=Z7pkvfRl5S8", "https://m.youtube.com/watch?v=A1bcdeFg2H3", "no id here"),
            joiner = "; ",
            base = "youtube.com/watch?v",
            append = "\\=[a-zA-Z0-9_]{11}",
        )
        assertEquals("youtube.com/watch?v=Z7pkvfRl5S8; =A1bcdeFg2H3", joined)
    }

    @Test fun `nothing to join is null`() {
        assertNull(ClipCombine.combine(listOf("abc"), " ", append = "\\d+"))
        assertNull(ClipCombine.combine(listOf("abc"), " ", append = "[0-9"))
        assertNull(ClipCombine.combine(emptyList(), " "))
    }
}
