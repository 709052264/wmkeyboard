package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.ime.TwoThumbKit.allUpPause
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.delete
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.strip
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A word the all-thumbs-up pause typed is still a swiped word afterwards, the
 * way a glided word is: Backspace takes it back, and its alternates on the
 * strip replace it (#733).
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbCommitUndoTest {

    @Test
    fun `control - a pick after an ordinary glide replaces it`() {
        val (service, editor) = keyboard(settings(twoThumb = false))
        swipe(service, GlideVerdict.Word("there"))
        drain(service)
        assertEquals("there ", editor.text.toString())
        service.onSuggestionTapped("hello")
        drain(service)
        assertEquals("hello ", editor.text.toString())
    }

    @Test
    fun `one backspace after the pause takes the swiped word and its space`() {
        val (service, editor) = keyboard(settings(allUp = true))
        swipe(service)
        drain(service)
        allUpPause(service)
        assertEquals("fixture: the pause types the word and its space", "qy ", editor.text.toString())
        service.onKey(delete)
        drain(service)
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `after the pause the strip offers the word's alternates, and a pick replaces it`() {
        val (service, editor) = keyboard(settings(allUp = true))
        plantDictionary(service, "qwerty" to 500, "qwery" to 300)
        swipe(service)
        drain(service)
        assertEquals("fixture: the dictionary reads the stroke", "qwerty", editor.text.toString())
        allUpPause(service)
        assertEquals("qwerty ", editor.text.toString())
        assertTrue("the strip should offer the other reading: ${strip(service)}", "qwery" in strip(service))
        service.onSuggestionTapped("qwery")
        drain(service)
        assertEquals("qwery ", editor.text.toString())
    }

    @Test
    fun `a word with no alternates leaves the next-word strip, and a pick there is a new word`() {
        val (service, editor) = keyboard(settings(allUp = true))
        swipe(service)
        drain(service)
        allUpPause(service)
        assertEquals("fixture: the pause types the word and its space", "qy ", editor.text.toString())
        service.onSuggestionTapped("hello")
        drain(service)
        assertEquals("qy hello ", editor.text.toString())
    }
}
