package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.PAUSE_MS
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Issue #743: under All thumbs up, a finger on the strip or the tools' row
 * holds the word open as a thumb on the keys does, and the pause runs from the
 * last finger to lift anywhere.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbBarTouchTest {

    private fun ping(service: WMKeyboardService, kind: Float) =
        service.onGesturePreview(emptyList(), emptyList(), kind, GlideCase.None)

    private fun letTimePass(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** "to" tapped and every thumb lifted off the keys, the pause begun. */
    private fun tappedWord(): Pair<WMKeyboardService, RecordingEditor> {
        val (service, editor) = keyboard(settings(allUp = true))
        tap(service, "t")
        tap(service, "o")
        drain(service)
        ping(service, DUET_PING_ALL_UP)
        return service to editor
    }

    @Test
    fun `a finger on the strip when the pause runs out holds the word, and its pick replaces it`() {
        val (service, editor) = tappedWord()
        letTimePass(PAUSE_MS / 2)
        ping(service, DUET_PING_BAR_DOWN)
        letTimePass(PAUSE_MS * 2)
        drain(service)
        assertEquals("the word was typed under the finger on the strip", "to", editor.text.toString())

        service.onSuggestionTapped("toe")
        ping(service, DUET_PING_BAR_UP)
        letTimePass(PAUSE_MS * 2)
        drain(service)

        val text = editor.text.toString()
        assertEquals("the pick did not replace the word", "toe", text.trimEnd())
        assertFalse("the pause typed a second space after the pick: '$text'", text.endsWith("  "))
    }

    @Test
    fun `a strip touch that picks nothing starts the pause again from its lift`() {
        val (service, editor) = tappedWord()
        letTimePass(PAUSE_MS / 2)
        ping(service, DUET_PING_BAR_DOWN)
        letTimePass(PAUSE_MS * 2)
        ping(service, DUET_PING_BAR_UP)
        letTimePass(PAUSE_MS / 2)
        drain(service)
        assertEquals("the word ended before a full pause after the lift", "to", editor.text.toString())

        letTimePass(PAUSE_MS)
        drain(service)
        assertEquals("the word did not end one pause after the lift", "to ", editor.text.toString())
    }

    @Test
    fun `a thumb still on the keys holds the pause while a strip finger lifts`() {
        val (service, editor) = tappedWord()
        ping(service, DUET_PING_DOWN)
        ping(service, DUET_PING_BAR_DOWN)
        ping(service, DUET_PING_BAR_UP)
        letTimePass(PAUSE_MS * 2)
        drain(service)
        assertEquals("the word ended under a thumb on the keys", "to", editor.text.toString())

        ping(service, DUET_PING_ALL_UP)
        letTimePass(PAUSE_MS + 50)
        drain(service)
        assertEquals("to ", editor.text.toString())
    }
}
