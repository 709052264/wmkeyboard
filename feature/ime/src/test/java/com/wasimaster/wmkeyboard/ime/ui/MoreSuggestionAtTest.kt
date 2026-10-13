package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which word of the swipe-up popup the finger is on. The fixture is two rows of
 * three 100x40 cells, the lower row's top at y = 440, over a strip below y = 480.
 */
class MoreSuggestionAtTest {
    private val cells = buildMap {
        for (row in 0..1) for (column in 0..2) {
            val top = 440f - row * 40f
            put(row * 3 + column, Rect(column * 100f, top, column * 100f + 100f, top + 40f))
        }
    }

    @Test
    fun `a finger inside a cell picks that cell`() {
        assertEquals(1, moreSuggestionAt(cells, Offset(150f, 460f)))
        assertEquals(5, moreSuggestionAt(cells, Offset(250f, 410f)))
    }

    @Test
    fun `a finger just past the edge picks the nearest cell`() {
        assertEquals(2, moreSuggestionAt(cells, Offset(310f, 470f)))
        assertEquals(3, moreSuggestionAt(cells, Offset(20f, 395f)))
    }

    @Test
    fun `a finger back down on the strip picks nothing`() {
        assertEquals(-1, moreSuggestionAt(cells, Offset(150f, 530f)))
    }

    @Test
    fun `no cells picks nothing`() {
        assertEquals(-1, moreSuggestionAt(emptyMap(), Offset(0f, 0f)))
    }
}
