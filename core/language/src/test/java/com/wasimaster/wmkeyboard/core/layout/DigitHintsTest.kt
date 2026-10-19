package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DigitHintsTest {

    private fun row(letters: String, vararg extra: Pair<Char, List<String>>) =
        letters.map { c -> Key(c.toString(), longPress = extra.toMap()[c].orEmpty()) }

    private val ukrainian = listOf(
        row("йцукенгшщзхї", 'г' to listOf("ґ")),
        row("фівапролджє"),
        listOf(Key("⇧", action = KeyAction.Shift)) + row("ячсмитьбю"),
    )

    @Test
    fun `digits lead the top row and symbols the two below`() {
        val rows = DigitHints.fill(ukrainian)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null),
            rows[0].map { it.longPress.firstOrNull() },
        )
        assertEquals(listOf("7", "ґ"), rows[0][6].longPress)
        assertEquals("@", rows[1][0].longPress.first())
        assertEquals("*", rows[2][1].longPress.first())
    }

    @Test
    fun `a grid that already offers a digit is left as authored`() {
        val rows = listOf(row("qwertyuiop", 'q' to listOf("1")), row("asdfghjkl"))
        assertSame(rows, DigitHints.fill(rows))
    }

    @Test
    fun `a grid of symbols gets nothing`() {
        val rows = listOf("♩♪♫♬♭♮♯♠♣".map { Key(it.toString()) })
        assertSame(rows, DigitHints.fill(rows))
    }
}
