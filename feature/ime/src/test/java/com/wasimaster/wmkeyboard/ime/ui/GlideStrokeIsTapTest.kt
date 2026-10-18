package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap that rolled past the glide start slop (#782) must still type its key,
 * and the shortest real word, two neighbouring letters, must still glide.
 */
class GlideStrokeIsTapTest {

    private val keyWidth = 100f

    @Test
    fun `a roll that stays inside its key is a tap`() {
        assertTrue(glideStrokeIsTap(reachPx = 50f, samples = 12, keyWidthPx = keyWidth))
    }

    @Test
    fun `a stroke too short to commit is a tap however far it went`() {
        assertTrue(glideStrokeIsTap(reachPx = 300f, samples = 3, keyWidthPx = keyWidth))
    }

    @Test
    fun `a stroke to the neighbouring key is a word`() {
        assertFalse(glideStrokeIsTap(reachPx = 100f, samples = 8, keyWidthPx = keyWidth))
    }

    @Test
    fun `an unmeasured grid only falls back on the sample count`() {
        assertFalse(glideStrokeIsTap(reachPx = 10f, samples = 8, keyWidthPx = 0f))
    }
}
