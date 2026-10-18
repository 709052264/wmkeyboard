package com.wasimaster.wmkeyboard.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rings [SelectWordGrowth] takes a repeated Select word through (#679). */
class SelectWordGrowthTest {

    private fun steps(text: String, word: String): List<String> {
        val start = text.indexOf(word)
        var range = start until start + word.length
        val seen = mutableListOf(word)
        while (true) {
            val next = SelectWordGrowth.next(text, range.first, range.last + 1) ?: break
            range = next
            seen += text.substring(next)
        }
        return seen
    }

    @Test
    fun linkComesApartTheWayItIsBuilt() {
        val link = "https://github.com/wasi-master/wmkeyboard/issues/662#issue-5841522533"
        assertEquals(
            listOf(
                "github",
                "github.com",
                "github.com/wasi-master/wmkeyboard/issues/662",
                link,
            ),
            steps("see $link", "github").take(4),
        )
    }

    @Test
    fun hyphenAndUnderscoreComeFirst() {
        assertEquals(
            listOf("wasi", "wasi-master", "x.wasi-master"),
            steps("x.wasi-master", "wasi"),
        )
        assertEquals(listOf("snake", "snake_case"), steps("snake_case", "snake"))
    }

    @Test
    fun spacesComeLast() {
        assertEquals(
            listOf("auto", "auto-space", "an auto-space fix"),
            steps("an auto-space fix", "auto"),
        )
    }

    @Test
    fun aCaretOnMarksTakesTheRunAroundIt() {
        assertEquals(0 until 8, SelectWordGrowth.tokenAt("example@", 8))
        assertNull(SelectWordGrowth.tokenAt("a  b", 2))
    }
}
