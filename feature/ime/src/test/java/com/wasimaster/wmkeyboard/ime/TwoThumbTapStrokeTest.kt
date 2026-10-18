package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.grid
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #738, the service half: a stroke too small to be a swipe reaches the
 * two-thumb word as the tap it was, typing the letter of the key it started
 * on, rather than being read as a swipe or lost.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbTapStrokeTest {

    /** Three samples rolling a fifth of a key off [letter]: a quick tap that drifted. */
    private fun drift(letter: Char, t: Long = 1_000L): List<GesturePoint> {
        val key = grid.first { it.codePoint == letter.code }
        return List(3) { GesturePoint(key.x + it * GLIDE_KEY_WIDTH / 10, key.y, t + it * 10L) }
    }

    private fun stroke(service: WMKeyboardService, points: List<GesturePoint>) =
        service.onGesture(points, grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())

    @Test
    fun `a 3-point stroke during a two-thumb word adds its one letter`() {
        val (service, editor) = keyboard(settings())
        swipe(service)
        drain(service)
        val before = editor.text.toString()

        stroke(service, drift('a'))
        drain(service)

        assertEquals("$before" + "a", editor.text.toString())
    }

    @Test
    fun `a lone 3-point stroke types its key's letter`() {
        val (service, editor) = keyboard(settings())
        stroke(service, drift('a'))
        drain(service)
        assertEquals("a", editor.text.toString())
    }

    @Test
    fun `control - with two-thumb typing off a short stroke is not turned into a tap`() {
        val (service, editor) = keyboard(settings(twoThumb = false))
        stroke(service, drift('a'))
        drain(service)
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `tap-sized strokes are the short or few-sampled ones`() {
        assertTrue(isTapSizedStroke(drift('a'), GLIDE_KEY_WIDTH))
        assertTrue(isTapSizedStroke(drift('a').take(1), GLIDE_KEY_WIDTH))
        assertFalse(isTapSizedStroke(straightStroke(0), GLIDE_KEY_WIDTH))
        // Enough samples, but all inside half a key.
        val key = grid.first { it.codePoint == 'a'.code }
        val jitter = List(6) { GesturePoint(key.x + it * GLIDE_KEY_WIDTH / 20, key.y, it * 10L) }
        assertTrue(isTapSizedStroke(jitter, GLIDE_KEY_WIDTH))
    }
}
