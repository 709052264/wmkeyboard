package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.PAUSE_MS
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.allUpPause
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Issue #747, the service half: an all-thumbs-up space that a letter typed
 * straight afterwards takes back when the two still lead to a dictionary word,
 * the separate pauses, and the band over the pause. The gate itself is pure,
 * in [TwoThumbRetractionTest].
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbRetractSpaceTest {

    private val window = 4 * PAUSE_MS.toInt()

    private fun retracting(
        vararg words: Pair<String, Int>,
        base: KeyboardSettings = settings(allUp = true, retractMs = window),
    ) = keyboard(base).also { (service, _) -> plantDictionary(service, *words) }

    private fun type(service: WMKeyboardService, letters: String) = letters.forEach { tap(service, it.toString()) }

    private fun letTimePass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun thumbsUp(service: WMKeyboardService) =
        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_ALL_UP, GlideCase.None)

    private fun thumbDown(service: WMKeyboardService) =
        service.onGesturePreview(emptyList(), emptyList(), DUET_PING_DOWN, GlideCase.None)

    @Test
    fun `car, the space, then pet inside the window gives carpet`() {
        val (service, editor) = retracting("car" to 200, "carpet" to 100, "pet" to 100)
        type(service, "car")
        allUpPause(service)
        assertEquals("car ", editor.text.toString())
        type(service, "pet")
        assertEquals("the space was not taken back", "carpet", editor.text.toString())
        allUpPause(service)
        assertEquals("carpet ", editor.text.toString())
    }

    @Test
    fun `car, the space, then dog stays car dog`() {
        val (service, editor) = retracting("car" to 200, "carpet" to 100, "dog" to 100)
        type(service, "car")
        allUpPause(service)
        type(service, "dog")
        allUpPause(service)
        assertEquals("car dog ", editor.text.toString())
    }

    @Test
    fun `car d o g splits back where cardo leads nowhere`() {
        // "card" is a word, so the d takes the space back; the o is where the
        // joined letters stop leading anywhere.
        val (service, editor) = retracting("car" to 200, "card" to 150, "dog" to 100)
        type(service, "car")
        allUpPause(service)
        tap(service, "d")
        assertEquals("card", editor.text.toString())
        tap(service, "o")
        assertEquals("the reopened word did not split back", "car do", editor.text.toString())
        tap(service, "g")
        allUpPause(service)
        assertEquals("car dog ", editor.text.toString())
    }

    @Test
    fun `letters that finish no word with the reopened one end as their own word`() {
        // "carpet" keeps car p e open, but the pause comes before the t.
        val (service, editor) = retracting("car" to 200, "carpet" to 100, "pe" to 50)
        type(service, "car")
        allUpPause(service)
        type(service, "pe")
        allUpPause(service)
        assertEquals("car pe ", editor.text.toString())
    }

    @Test
    fun `a letter after the window never takes the space back`() {
        val (service, editor) = retracting("car" to 200, "carpet" to 100, "pet" to 100)
        type(service, "car")
        allUpPause(service)
        letTimePass(window + 100L)
        type(service, "pet")
        allUpPause(service)
        assertEquals("car pet ", editor.text.toString())
    }

    @Test
    fun `the window is measured to the thumb landing, not the letter`() {
        val (service, editor) = retracting("car" to 200, "carpet" to 100)
        type(service, "car")
        allUpPause(service)
        thumbDown(service)
        letTimePass(window + 100L)
        type(service, "pet")
        assertEquals("carpet", editor.text.toString())
    }

    @Test
    fun `a window of zero never takes the space back`() {
        val (service, editor) = retracting(
            "car" to 200, "carpet" to 100, "pet" to 100,
            base = settings(allUp = true, retractMs = 0),
        )
        type(service, "car")
        allUpPause(service)
        type(service, "pet")
        allUpPause(service)
        assertEquals("car pet ", editor.text.toString())
    }

    @Test
    fun `control - the Space word end takes no space back`() {
        val (service, editor) = retracting(
            "car" to 200, "carpet" to 100, "pet" to 100,
            base = settings(allUp = false, retractMs = window),
        )
        type(service, "car")
        service.onKey(space)
        drain(service)
        type(service, "pet")
        service.onKey(space)
        drain(service)
        assertEquals("car pet ", editor.text.toString())
    }

    @Test
    fun `control - a mode with automatic spaces off has no space to take back`() {
        val (service, editor) = retracting(
            "car" to 200, "carpet" to 100,
            base = settings(allUp = true, noAutoSpaceMode = true, retractMs = window),
        )
        type(service, "car")
        allUpPause(service)
        assertEquals("car", editor.text.toString())
        type(service, "pet")
        allUpPause(service)
        assertEquals("nothing was deleted, nothing was spaced", "carpet", editor.text.toString())
    }

    @Test
    fun `a full stop after the space still hugs the word`() {
        val (service, editor) = retracting("car" to 200, "carpet" to 100)
        type(service, "car")
        allUpPause(service)
        tap(service, ".")
        drain(service)
        assertEquals("car.", editor.text.toString())
    }

    @Test
    fun `a swipe starting nowhere near a way on leaves the space`() {
        // The stroke runs along the top row from Q: "carq…", "carw…" lead nowhere.
        val (service, editor) = retracting("car" to 200, "carpet" to 100)
        type(service, "car")
        allUpPause(service)
        swipe(service)
        drain(service)
        assertTrue("the swipe was glued to car: ${editor.text}", editor.text.toString().startsWith("car "))
    }

    @Test
    fun `a tapped word waits its own pause`() {
        val base = settings(allUp = true)
        val slow = base.copy(gesture = base.gesture.copy(twoThumbTapPauseMs = 3 * PAUSE_MS.toInt()))
        val (service, editor) = keyboard(slow)
        type(service, "to")
        thumbsUp(service)
        letTimePass(PAUSE_MS + 50)
        drain(service)
        assertEquals("the swiped-word pause ended a tapped word", "to", editor.text.toString())
        letTimePass(2 * PAUSE_MS)
        drain(service)
        assertEquals("to ", editor.text.toString())
    }

    @Test
    fun `a one-letter word waits at least the floor`() {
        val base = settings(allUp = true)
        val floor = base.copy(gesture = base.gesture.copy(twoThumbOneLetterPauseMs = 3 * PAUSE_MS.toInt()))
        val (service, editor) = keyboard(floor)
        tap(service, "a")
        thumbsUp(service)
        letTimePass(PAUSE_MS + 50)
        drain(service)
        assertEquals("a", editor.text.toString())
        letTimePass(2 * PAUSE_MS)
        drain(service)
        assertEquals("a ", editor.text.toString())
    }

    @Test
    fun `the band shows during the pause and goes when a thumb lands`() {
        val (service, _) = keyboard(settings(allUp = true))
        type(service, "to")
        thumbsUp(service)
        val countdown = service.uiState.value.allUpCountdown
        assertNotNull("no band during the pause", countdown)
        assertEquals(PAUSE_MS, countdown!!.durationMs)
        thumbDown(service)
        assertNull("the band outlived the pause", service.uiState.value.allUpCountdown)
    }

    @Test
    fun `the band goes when the word ends`() {
        val (service, editor) = keyboard(settings(allUp = true))
        type(service, "to")
        allUpPause(service)
        assertEquals("to ", editor.text.toString())
        assertNull(service.uiState.value.allUpCountdown)
    }

    @Test
    fun `no band with the switch off`() {
        val (service, _) = keyboard(settings(allUp = true, band = false))
        type(service, "to")
        thumbsUp(service)
        assertNull(service.uiState.value.allUpCountdown)
    }
}
