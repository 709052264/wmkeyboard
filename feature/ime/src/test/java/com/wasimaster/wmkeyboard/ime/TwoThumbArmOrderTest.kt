package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.flickKey
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A special key's arm flicked by one thumb while the other thumb's two-thumb
 * word is still being read (#789, test coverage for #838 and #878).
 *
 * The arm is a key like any other once it is flicked, so it waits behind the
 * word's read (`deferBehindDuet`): the word lands whole, once, and the arm's
 * text after it. Nothing is cancelled, typed twice or committed early.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbArmOrderTest {

    private val grid = TwoThumbKit.grid

    private fun stroke(service: WMKeyboardService, row: Int) =
        service.onGesture(straightStroke(row), grid, GLIDE_KEY_WIDTH, GlideVerdict.Leader())

    /** The keys a straight stroke starts and ends on, the way the field spells an unread swipe. */
    private fun literalOf(stroke: List<GesturePoint>): String {
        fun nearest(p: GesturePoint): KeyCenter =
            grid.minBy { (it.x - p.x) * (it.x - p.x) + (it.y - p.y) * (it.y - p.y) }
        val first = Character.toLowerCase(nearest(stroke.first()).codePoint)
        val last = Character.toLowerCase(nearest(stroke.last()).codePoint)
        return buildString {
            appendCodePoint(first)
            if (last != first) appendCodePoint(last)
        }
    }

    private fun armed(action: KeyAction, text: String) =
        Key("", action = action, flick = mapOf(FlickDirection.UP to text)).flickKey(FlickDirection.UP)!!

    @Test
    fun `an arm flicked mid-word lands after the whole word, once`() {
        for ((action, text) in listOf(KeyAction.Enter to "?", KeyAction.Shift to "!", KeyAction.Symbols to "@")) {
            val (service, editor) = TwoThumbKit.keyboard(TwoThumbKit.settings())
            val first = literalOf(straightStroke(0))
            val both = first + literalOf(straightStroke(1))

            stroke(service, 0)
            settle { editor.text.toString() == first }
            // The second thumb's gesture is still being read when the arm fires.
            stroke(service, 1)
            service.onKey(armed(action, text))
            TwoThumbKit.drain(service)

            assertEquals("$action: the word, whole and once, then the arm", "$both$text", editor.text.toString().trimEnd())
            assertEquals("$action: no editor action", emptyList<Int>(), editor.editorActions)
        }
    }
}
