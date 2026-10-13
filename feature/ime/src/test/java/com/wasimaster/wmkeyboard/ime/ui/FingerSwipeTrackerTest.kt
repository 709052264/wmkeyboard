package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.FingerSwipe
import com.wasimaster.wmkeyboard.core.layout.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When fingers on the key grid are a multi-finger swipe (issue #647), and which. */
class FingerSwipeTrackerTest {

    private fun tracker(twoThumb: Boolean = false) = FingerSwipeTracker(claimPx = 50f, twoThumb = twoThumb)

    /** Lands each finger, then moves them all by [dx], [dy] in [steps] steps, then lifts them. */
    private fun FingerSwipeTracker.swipe(starts: List<Pair<Float, Float>>, dx: Float, dy: Float, steps: Int = 5) {
        starts.forEachIndexed { id, (x, y) -> track(id.toLong(), x, y, pressed = true) }
        for (step in 1..steps) {
            starts.forEachIndexed { id, (x, y) ->
                track(id.toLong(), x + dx * step / steps, y + dy * step / steps, pressed = true)
            }
        }
        starts.forEachIndexed { id, (x, y) -> track(id.toLong(), x + dx, y + dy, pressed = false) }
    }

    @Test
    fun `two fingers swiped up together are a two-finger swipe up`() {
        val t = tracker()
        t.swipe(listOf(100f to 300f, 200f to 300f), dx = 0f, dy = -120f)
        assertTrue(t.claimed)
        assertEquals(FingerSwipe(2, SwipeDirection.UP), t.result())
    }

    @Test
    fun `three fingers swiped left are a three-finger swipe left`() {
        val t = tracker()
        t.swipe(listOf(300f to 100f, 300f to 200f, 300f to 300f), dx = -150f, dy = 10f)
        assertEquals(FingerSwipe(3, SwipeDirection.LEFT), t.result())
    }

    @Test
    fun `a pinch is no swipe`() {
        val t = tracker()
        t.track(0, 100f, 300f, pressed = true)
        t.track(1, 300f, 300f, pressed = true)
        t.track(0, 180f, 300f, pressed = true)
        t.track(1, 220f, 300f, pressed = true)
        assertFalse(t.claimed)
    }

    @Test
    fun `a still resting finger keeps a moving one from claiming`() {
        val t = tracker()
        t.track(0, 100f, 300f, pressed = true)
        t.track(1, 300f, 300f, pressed = true)
        t.track(1, 300f, 150f, pressed = true)
        assertFalse(t.claimed)
    }

    @Test
    fun `rollover typing never claims`() {
        val t = tracker()
        t.track(0, 100f, 300f, pressed = true)
        t.track(1, 140f, 300f, pressed = true)
        t.track(0, 104f, 302f, pressed = false)
        t.track(1, 160f, 300f, pressed = true)
        t.track(1, 220f, 300f, pressed = true)
        assertTrue(t.abandoned)
        assertFalse(t.claimed)
        t.track(1, 220f, 300f, pressed = false)
        assertNull(t.result())
    }

    @Test
    fun `a finger joining a glide already under way is not a swipe`() {
        val t = tracker()
        t.track(0, 100f, 300f, pressed = true)
        t.track(0, 200f, 300f, pressed = true)
        t.track(1, 100f, 200f, pressed = true)
        assertTrue(t.abandoned)
    }

    @Test
    fun `a diagonal is no direction`() {
        val t = tracker()
        t.track(0, 100f, 300f, pressed = true)
        t.track(1, 200f, 300f, pressed = true)
        t.track(0, 180f, 220f, pressed = true)
        t.track(1, 280f, 220f, pressed = true)
        assertFalse(t.claimed)
    }

    @Test
    fun `a finger landing after the claim raises the count`() {
        val t = tracker()
        t.track(0, 100f, 300f, pressed = true)
        t.track(1, 200f, 300f, pressed = true)
        t.track(0, 100f, 380f, pressed = true)
        t.track(1, 200f, 380f, pressed = true)
        assertTrue(t.claimed)
        t.track(2, 300f, 300f, pressed = true)
        t.track(2, 300f, 390f, pressed = true)
        for (id in 0L..2L) t.track(id, 0f, 0f, pressed = false)
        assertEquals(FingerSwipe(3, SwipeDirection.DOWN), t.result())
    }

    @Test
    fun `two thumbs under two-thumb glide never claim, three fingers still do`() {
        val two = tracker(twoThumb = true)
        two.swipe(listOf(100f to 300f, 200f to 300f), dx = 150f, dy = 0f)
        assertNull(two.result())
        val three = tracker(twoThumb = true)
        three.swipe(listOf(100f to 300f, 200f to 300f, 300f to 300f), dx = 150f, dy = 0f)
        assertEquals(FingerSwipe(3, SwipeDirection.RIGHT), three.result())
    }

    @Test
    fun `no result while a finger is still down`() {
        val t = tracker()
        t.track(0, 100f, 300f, pressed = true)
        t.track(1, 200f, 300f, pressed = true)
        t.track(0, 100f, 200f, pressed = true)
        t.track(1, 200f, 200f, pressed = true)
        t.track(0, 100f, 200f, pressed = false)
        assertNull(t.result())
    }
}
