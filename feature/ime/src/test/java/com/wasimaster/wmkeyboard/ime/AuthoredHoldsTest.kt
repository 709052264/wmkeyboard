package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.withQwertyHolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #622: a popup the user wrote keeps the order they wrote, so the
 * "your language's letters first" pass has to know which ones those are.
 */
class AuthoredHoldsTest {

    private val german = BuiltInLayouts.GERMAN

    @Test
    fun `a shipped layout as it ships has no authored holds`() {
        assertTrue(authoredHolds(german, german.compile(LayoutLayer.LETTERS)).isEmpty())
    }

    @Test
    fun `an edit of a shipped layout marks only the key it changed`() {
        val letters = german.layer(LayoutLayer.LETTERS)!!
        val edited = german.copy(
            layers = german.layers + (
                LayoutLayer.LETTERS.key to letters.copy(
                    rows = letters.rows.map { row ->
                        row.map { if (it.label == "u") it.copy(longPress = listOf("7", "ü")) else it }
                    },
                )
                ),
        )
        val held = authoredHolds(edited, edited.compile(LayoutLayer.LETTERS))
        assertEquals(listOf("u"), held.map { it.label })
    }

    @Test
    fun `every key of a layout of the user's own is authored`() {
        val own = german.copy(id = "custom_mine")
        val letters = own.compile(LayoutLayer.LETTERS)
        val textKeys = letters.rows.flatten().filter { it.action == KeyAction.Text }.toSet()
        assertEquals(textKeys, authoredHolds(own, letters))
    }

    @Test
    fun `a layout of the user's own borrows no QWERTY holds`() {
        val qwerty = BuiltInLayouts.QWERTY
        val bare = qwerty.layer(LayoutLayer.LETTERS)!!.let { grid ->
            grid.copy(rows = grid.rows.map { row -> row.map { it.copy(longPress = emptyList()) } })
        }
        val own = qwerty.copy(id = "custom_bare", layers = qwerty.layers + (LayoutLayer.LETTERS.key to bare))
        val letters = own.compile(LayoutLayer.LETTERS)
        val a = letters.withQwertyHolds(keep = authoredHolds(own, letters)).rows.flatten().first { it.label == "a" }
        assertTrue(a.longPress.isEmpty())
    }

    @Test
    fun `a popup emptied on an edit of a shipped layout stays empty`() {
        val qwerty = BuiltInLayouts.QWERTY
        val grid = qwerty.layer(LayoutLayer.LETTERS)!!
        val edited = qwerty.copy(
            layers = qwerty.layers + (
                LayoutLayer.LETTERS.key to grid.copy(
                    rows = grid.rows.map { row -> row.map { if (it.label == "a") it.copy(longPress = emptyList()) else it } },
                )
                ),
        )
        val letters = edited.compile(LayoutLayer.LETTERS)
        val held = authoredHolds(edited, letters)
        assertEquals(listOf("a"), held.map { it.label })
        val a = letters.withQwertyHolds(keep = held).rows.flatten().first { it.label == "a" }
        assertTrue(a.longPress.isEmpty())
    }
}
