package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantEngine
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A pick that replaces a swiped word wears that word's capitals only when the
 * shift put them there (#212), never when they are the word's own spelling,
 * a name's or an acronym's (#893).
 */
@RunWith(RobolectricTestRunner::class)
class PickCaseTest {

    private fun engine(vararg spellings: String) = SuggestionEngine(
        Trie().apply {
            insert("qwerty", 500)
            insert("qwery", 300)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    ).apply {
        primaryLanguageId = "en"
        bundledCapitals = mapOf("en" to Trie().apply { spellings.forEach { insert(it, 1) } })
    }

    @Test
    fun `a pick over a name's capital mid-sentence keeps its own case`() {
        val (service, editor) = keyboard(settings(twoThumb = false), initial = "so ")
        plantEngine(service, engine("Qwerty"))
        swipe(service, GlideVerdict.Word("Qwerty"))
        drain(service)
        assertEquals("fixture: the swipe writes the name", "so Qwerty ", editor.text.toString())
        service.onSuggestionTapped("qwery")
        drain(service)
        assertEquals("so qwery ", editor.text.toString())
    }

    @Test
    fun `a pick over an acronym mid-sentence is not shouted`() {
        val (service, editor) = keyboard(settings(twoThumb = false), initial = "so ")
        plantEngine(service, engine("QWERTY"))
        swipe(service, GlideVerdict.Word("QWERTY"))
        drain(service)
        assertEquals("fixture: the swipe writes the acronym", "so QWERTY ", editor.text.toString())
        service.onSuggestionTapped("qwery")
        drain(service)
        assertEquals("so qwery ", editor.text.toString())
    }

    @Test
    fun `control - a capital the shift wrote still carries over`() {
        val (service, editor) = keyboard(settings(twoThumb = false), initial = "so ")
        plantEngine(service, engine())
        service.onKey(Key("⇧", action = KeyAction.Shift))
        swipe(service, GlideVerdict.Word("qwerty"))
        drain(service)
        assertEquals("fixture: the shift capitalizes the swipe", "so Qwerty ", editor.text.toString())
        service.onSuggestionTapped("qwery")
        drain(service)
        assertEquals("so Qwery ", editor.text.toString())
    }
}
