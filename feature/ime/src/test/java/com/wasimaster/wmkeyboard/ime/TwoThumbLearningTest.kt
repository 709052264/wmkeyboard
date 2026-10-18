package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSample
import com.wasimaster.wmkeyboard.core.gesture.KeyOffsets
import com.wasimaster.wmkeyboard.core.prediction.CorrectionMemory
import com.wasimaster.wmkeyboard.core.prediction.LearningBuffer
import com.wasimaster.wmkeyboard.core.prediction.WordOrigin
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.allUpPause
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.delete
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.grid
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #741: a two-thumb word learns as what it is, a word read off gestures.
 * It reaches the learning buffer as [WordOrigin.DUET], its swipes teach the
 * hand model and the shape store with an undo that takes the hand back, a fix
 * of it teaches the pair alone, and a fallback nobody spelled never reaches
 * the waiting room.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbLearningTest {

    private class Taught(val word: String, val hand: KeyOffsets.Adjustment?, val shape: GlideShapeSample?)

    /** A keyboard that learns, in a field that lets it. */
    private fun learningKeyboard(allUp: Boolean = false): Pair<WMKeyboardService, RecordingEditor> {
        val learning = settings(allUp = allUp).copy(learnFromTyping = true)
        val (service, editor) = TwoThumbKit.keyboard(learning)
        seedState(service, glideReadyState(settings = learning, fieldNoSuggestions = false))
        return service to editor
    }

    private fun <T> field(service: WMKeyboardService, name: String): T {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(service) as T
    }

    private fun buffer(service: WMKeyboardService): LearningBuffer = field(service, "learningBuffer")

    private fun taught(service: WMKeyboardService): MutableList<Taught> {
        val taught = mutableListOf<Taught>()
        service.duetLearned = { word, hand, shape -> taught += Taught(word, hand, shape) }
        return taught
    }

    @Test
    fun `a two-thumb word reaches the learning buffer as a two-thumb word`() {
        val (service, editor) = learningKeyboard()
        plantDictionary(service, "qwerty" to 500)
        swipe(service)
        drain(service)
        service.onKey(space)
        drain(service)
        assertEquals("fixture: the stroke reads as the word", "qwerty ", editor.text.toString())
        assertEquals(WordOrigin.DUET, buffer(service).originOf("qwerty"))
    }

    @Test
    fun `its swipe teaches the hand model and the shape store`() {
        val (service, _) = learningKeyboard()
        plantDictionary(service, "qwerty" to 500)
        val taught = taught(service)
        swipe(service)
        drain(service)
        service.onKey(space)
        drain(service)
        val lesson = taught.singleOrNull()
        assertNotNull("the word's gestures taught nothing", lesson)
        assertEquals("qwerty", lesson!!.word)
        assertNotNull("the swipe did not reach the hand model", lesson.hand)
        assertNotNull("a word that is one swipe did not file its shape", lesson.shape)
    }

    @Test
    fun `backspacing the word takes back what its swipes taught the hand`() {
        val (service, editor) = learningKeyboard(allUp = true)
        plantDictionary(service, "qwerty" to 500)
        val taught = taught(service)
        swipe(service)
        drain(service)
        allUpPause(service)
        assertEquals("fixture: the pause types the word", "qwerty ", editor.text.toString())
        assertNotNull("fixture: the swipe taught the hand", taught.singleOrNull()?.hand)
        val before = field<KeyOffsets>(service, "keyOffsets").version

        service.onKey(delete)
        drain(service)

        assertEquals("", editor.text.toString())
        assertTrue("the undo did not retract the hand model", field<KeyOffsets>(service, "keyOffsets").version > before)
        assertNull("the retracted adjustment is still armed", field<KeyOffsets.Adjustment?>(service, "lastHandAdjustment"))
    }

    @Test
    fun `a fallback with a swipe in it is not counted towards the waiting room`() {
        val (service, editor) = learningKeyboard()
        swipe(service)
        drain(service)
        service.onKey(space)
        drain(service)
        assertEquals("fixture: no word fits, so the keys under the swipe are typed", "qy ", editor.text.toString())
        val entry = buffer(service).drain().lastOrNull { it.word == "qy" }
        assertTrue("the unspelled fallback was queued as a sighting", entry == null || entry.contextOnly)
    }

    @Test
    fun `control - a fallback of taps alone is still counted as typed`() {
        val (service, editor) = learningKeyboard()
        tap(service, "q")
        tap(service, "y")
        service.onKey(space)
        drain(service)
        assertEquals("fixture: the taps are typed as they are", "qy ", editor.text.toString())
        val entry = buffer(service).drain().lastOrNull { it.word == "qy" }
        assertNotNull("a tapped word never reached the waiting room", entry)
        assertTrue("a tapped word was not counted", !entry!!.contextOnly)
        assertEquals(WordOrigin.TYPED, entry.origin)
    }

    @Test
    fun `a fix of a two-thumb word teaches the pair alone`() {
        assertEquals(CorrectionMemory.Kind.PAIR_ONLY, revisionKind(WordOrigin.DUET) { false })
        assertEquals(CorrectionMemory.Kind.PAIR_ONLY, revisionKind(WordOrigin.GLIDE) { false })
        assertEquals(CorrectionMemory.Kind.PAIR_AND_HABITS, revisionKind(WordOrigin.TYPED) { false })
    }

    @Test
    fun `a swipe teaches the run of the word it drew`() {
        val keys = GlideKeyMap.of(grid, GLIDE_KEY_WIDTH)
        fun at(letter: Char): GesturePoint = grid.first { it.codePoint == letter.code }.let { GesturePoint(it.x, it.y, 0L) }
        assertEquals("ert", duetSwipeSpan("qwerty", at('e'), at('t'), keys))
        assertEquals("qwerty", duetSwipeSpan("Qwerty", at('q'), at('y'), keys))
        assertNull("a single letter holds no swipe", duetSwipeSpan("q", at('q'), at('q'), keys))
    }
}
