package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
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
        val withPopups = letters.rows.flatten().filter { it.longPress.isNotEmpty() }.toSet()
        assertEquals(withPopups, authoredHolds(own, letters))
    }
}
