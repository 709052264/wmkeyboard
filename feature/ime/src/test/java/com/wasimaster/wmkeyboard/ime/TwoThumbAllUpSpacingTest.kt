package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.ime.TwoThumbKit.allUpPause
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * All thumbs up ends a tapped word with the same automatic space a swiped word
 * gets, not with a Space pressed on the user's behalf (#734).
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbAllUpSpacingTest {

    @Test
    fun `a tapped word ends at the pause with a space`() {
        val (service, editor) = keyboard(settings(allUp = true))
        tap(service, "t")
        tap(service, "o")
        allUpPause(service)
        assertEquals("to ", editor.text.toString())
    }

    @Test
    fun `control - a swiped word under a mode with automatic spaces off gets no space`() {
        val (service, editor) = keyboard(settings(allUp = true, noAutoSpaceMode = true))
        swipe(service)
        drain(service)
        val word = editor.text.toString()
        allUpPause(service)
        assertEquals(word, editor.text.toString())
    }

    @Test
    fun `a tapped word under a mode with automatic spaces off gets no space`() {
        val (service, editor) = keyboard(settings(allUp = true, noAutoSpaceMode = true))
        tap(service, "t")
        tap(service, "o")
        allUpPause(service)
        assertEquals("to", editor.text.toString())
    }

    @Test
    fun `control - a habitual space after a swiped word's pause is absorbed`() {
        val (service, editor) = keyboard(settings(allUp = true))
        swipe(service)
        drain(service)
        val word = editor.text.toString()
        allUpPause(service)
        service.onKey(space)
        drain(service)
        assertEquals("$word ", editor.text.toString())
    }

    @Test
    fun `a habitual space after a tapped word's pause is absorbed like after a swipe`() {
        val (service, editor) = keyboard(settings(allUp = true))
        tap(service, "t")
        tap(service, "o")
        allUpPause(service)
        service.onKey(space)
        drain(service)
        assertEquals("to ", editor.text.toString())
    }

    @Test
    fun `a full stop after a tapped word's pause hugs the word`() {
        val (service, editor) = keyboard(settings(allUp = true))
        tap(service, "t")
        tap(service, "o")
        allUpPause(service)
        tap(service, ".")
        drain(service)
        assertEquals("to.", editor.text.toString())
    }

    @Test
    fun `control - a full stop after a swiped word's pause hugs the word`() {
        val (service, editor) = keyboard(settings(allUp = true))
        swipe(service)
        drain(service)
        val word = editor.text.toString()
        allUpPause(service)
        tap(service, ".")
        drain(service)
        assertEquals("$word.", editor.text.toString())
    }

    @Test
    fun `a physical space press still types a space under the same mode`() {
        val (service, editor) = keyboard(settings(allUp = true, noAutoSpaceMode = true))
        tap(service, "t")
        tap(service, "o")
        service.onKey(space)
        drain(service)
        assertEquals("to ", editor.text.toString())
    }
}
