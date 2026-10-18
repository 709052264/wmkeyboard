package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * Issue #742: a swipe right after the caret lands at the end of a word starts a
 * new word, under two-thumb typing and under Swipe finishes typed letters
 * (#595) alike. Letters typed into that word first still let the swipe
 * continue it.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbResumedWordTest {

    /** A field holding "Hello" with the caret come back to its end, the word resumed. */
    private fun resumed(settings: KeyboardSettings): Pair<WMKeyboardService, RecordingEditor> {
        val (service, editor) = TwoThumbKit.keyboard(settings, initial = "Hello")
        // The resume asks the word list whether there is anything to offer
        // for the word, and a field that wants no suggestions resumes nothing.
        plantDictionary(service, "hello" to 100)
        seedState(service, glideReadyState(settings = settings, fieldNoSuggestions = false))
        // Past the caret-scrub window, which Robolectric's clock starts inside.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        service.onUpdateSelection(0, 0, 5, 5, -1, -1)
        assertEquals("fixture: the caret did not resume the word", "Hello", service.uiState.value.composingPreview)
        return service to editor
    }

    @Test
    fun `a two-thumb swipe after a resumed word is a new word with its space`() {
        val (service, editor) = resumed(settings())
        swipe(service)
        drain(service)
        assertTrue("the swipe joined the resumed word: ${editor.text}", editor.text.startsWith("Hello "))
    }

    @Test
    fun `with two-thumb typing off, Swipe finishes typed letters leaves a resumed word alone`() {
        val base = settings(twoThumb = false)
        val (service, editor) = resumed(base.copy(gesture = base.gesture.copy(swipeContinuesTyped = true)))
        swipe(service, GlideVerdict.Word("world"))
        drain(service)
        assertEquals("Hello world ", editor.text.toString())
    }

    @Test
    fun `a letter typed into the resumed word lets the swipe continue it`() {
        val (service, editor) = resumed(settings())
        tap(service, "s")
        drain(service)
        swipe(service)
        drain(service)
        assertTrue("the typed letter was lost: ${editor.text}", editor.text.startsWith("Hellos"))
        assertFalse("the swipe started a word of its own: ${editor.text}", editor.text.contains(' '))
    }
}
