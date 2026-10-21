package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.allUpPause
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.grid
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.strip
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Splitting the last swipe off a two-thumb word (#832): the chip that offers
 * the two words apart, while the word is open, after the pause has typed it,
 * and after Backspace has taken the swipe out.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbSplitTest {

    private fun top(service: WMKeyboardService) =
        service.onGesture(straightStroke(0), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())

    private fun middle(service: WMKeyboardService) =
        service.onGesture(straightStroke(1), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())

    private fun words(service: WMKeyboardService) = plantDictionary(
        service,
        "qwerty" to 300, "asdfgh" to 200, "asdfg" to 150, "sdfgh" to 100, "sdfghj" to 100,
    )

    /** The middle row's stroke read on its own, whichever planted word that is. */
    private fun tail(service: WMKeyboardService): String {
        val (probe, _) = keyboard(settings(twoThumb = false))
        words(probe)
        middle(probe)
        drain(probe)
        return probe.uiState.value.suggestions.first()
    }

    @Test
    fun `two swipes offer the split, and picking it opens the second as a new word`() {
        val (service, editor) = keyboard(settings())
        words(service)
        val tail = tail(service)
        top(service)
        middle(service)
        drain(service)
        val chip = "qwerty $tail"
        assertTrue(strip(service).toString(), chip in strip(service))
        service.onSuggestionTapped(chip)
        drain(service)
        assertEquals("qwerty $tail", editor.text.toString())
        assertEquals(tail, service.uiState.value.composingPreview)
    }

    @Test
    fun `after the pause the chip replaces the joined word`() {
        val (service, editor) = keyboard(settings(allUp = true))
        words(service)
        val tail = tail(service)
        top(service)
        middle(service)
        drain(service)
        allUpPause(service)
        val chip = "qwerty $tail"
        assertTrue(strip(service).toString(), chip in strip(service))
        service.onSuggestionTapped(chip)
        drain(service)
        assertTrue(editor.text.toString(), editor.text.toString().startsWith("qwerty $tail"))
    }

    @Test
    fun `backspace offers the swipe it took back`() {
        val (service, editor) = keyboard(settings())
        words(service)
        val tail = tail(service)
        top(service)
        middle(service)
        drain(service)
        service.onKey(Key("⌫", action = KeyAction.Delete))
        drain(service)
        assertTrue(strip(service).toString(), "↪ $tail" in strip(service))
        service.onSuggestionTapped("↪ $tail")
        drain(service)
        assertEquals("qwerty $tail", editor.text.toString())
    }

    @Test
    fun `backspace on a word begun with taps offers the swipe, and drops the merged reading`() {
        // #870: no swipe left in the word, so the chip was never offered,
        // and the strip's stale "qw tail" typed the swipe back in.
        val (service, editor) = keyboard(settings())
        words(service)
        val tail = tail(service)
        service.onKey(Key("q"))
        service.onKey(Key("w"))
        middle(service)
        drain(service)
        service.onKey(Key("⌫", action = KeyAction.Delete))
        drain(service)
        assertTrue(strip(service).toString(), "↪ $tail" in strip(service))
        assertTrue(strip(service).toString(), "qw $tail" !in strip(service))
        service.onSuggestionTapped("↪ $tail")
        drain(service)
        assertEquals("qw $tail", editor.text.toString())
    }

    @Test
    fun `one swipe offers no split`() {
        val (service, _) = keyboard(settings())
        words(service)
        top(service)
        drain(service)
        assertTrue(strip(service).none { ' ' in it || it.startsWith("↪") })
    }
}
