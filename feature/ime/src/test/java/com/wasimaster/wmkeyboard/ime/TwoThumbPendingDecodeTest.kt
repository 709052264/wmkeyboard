package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.grid
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #744: a key that ends a two-thumb word while its latest gesture is
 * still being read waits for that read instead of decoding the word again on
 * the main thread, and nothing typed after it overtakes it.
 *
 * The decode hops to another thread and comes back through a post to the main
 * looper, which nothing pumps between the calls below: every key here lands
 * while the second swipe is still being read.
 */
@RunWith(RobolectricTestRunner::class)
class TwoThumbPendingDecodeTest {

    private fun swipe(service: WMKeyboardService, row: Int) =
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

    @Test
    fun `space during a pending read types the whole word, then the space, then what follows`() {
        val (service, editor) = keyboard(settings())
        val both = literalOf(straightStroke(0)) + literalOf(straightStroke(1))
        swipe(service, 0)
        drain(service)

        swipe(service, 1)
        service.onKey(space)
        tap(service, "t")
        tap(service, "o")
        drain(service)

        assertEquals("$both to", editor.text.toString())
        assertEquals("the word was read again on the main thread", 0, service.duetSettles)
    }

    @Test
    fun `a full stop during a pending read hugs the whole word`() {
        val (service, editor) = keyboard(settings())
        val both = literalOf(straightStroke(0)) + literalOf(straightStroke(1))
        swipe(service, 0)
        drain(service)

        swipe(service, 1)
        tap(service, ".")
        drain(service)

        assertEquals(both, editor.text.toString().substringBefore('.'))
        assertEquals("the word was read again on the main thread", 0, service.duetSettles)
    }
}
