package com.wasimaster.wmkeyboard.core.kdeconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic behind the Input tab: no sockets, no Android, just numbers. */
class RemoteInputMathTest {

    // ---- typing on the PC ----

    @Test
    fun `typing appends`() {
        assertEquals(RemoteEdit(0, "o"), RemoteTextDiff.between("hell", "hello"))
        assertEquals(RemoteEdit(0, "hello "), RemoteTextDiff.between("", "hello "))
        assertTrue(RemoteTextDiff.between("same", "same").isEmpty)
    }

    @Test
    fun `backspace removes`() {
        assertEquals(RemoteEdit(1, ""), RemoteTextDiff.between("hello", "hell"))
        assertEquals(RemoteEdit(5, ""), RemoteTextDiff.between("hello", ""))
    }

    @Test
    fun `a suggestion pick or autocorrect rewrites only the tail that differs`() {
        // "teh" corrected to "the " — the shared "t" stays on the PC's screen.
        assertEquals(RemoteEdit(2, "he "), RemoteTextDiff.between("teh", "the "))
        // A glide replaced by its alternate.
        assertEquals(RemoteEdit(3, "can "), RemoteTextDiff.between("I van", "I can ").let { RemoteEdit(it.backspaces, it.insert) })
    }

    @Test
    fun `an emoji is one backspace and is never split`() {
        val grin = "😀"
        val wink = "😉"
        assertEquals(RemoteEdit(1, ""), RemoteTextDiff.between("a$grin", "a"))
        // Both start with the same high surrogate; the prefix must stop before it.
        assertEquals(RemoteEdit(1, wink), RemoteTextDiff.between("a$grin", "a$wink"))
    }

    // ---- the pointer ----

    @Test
    fun `without acceleration the gain is flat`() {
        val flat = PointerAcceleration(sensitivity = 1f, accelerate = false)
        assertEquals(flat.gain(1.0, 16), flat.gain(40.0, 16), 0.0)
        assertEquals(PointerAcceleration.BASE_GAIN, flat.gain(10.0, 16), 1e-9)
    }

    @Test
    fun `slow is precise and fast is far and both are bounded`() {
        val curve = PointerAcceleration(sensitivity = 1f, accelerate = true)
        val crawl = curve.gain(0.5, 16)
        val drag = curve.gain(9.0, 16)
        val flick = curve.gain(60.0, 16)
        assertTrue(crawl < drag && drag < flick)
        assertEquals(PointerAcceleration.BASE_GAIN * PointerAcceleration.SLOW_GAIN, curve.gain(0.0, 16), 1e-9)
        assertEquals(PointerAcceleration.BASE_GAIN * PointerAcceleration.MAX_BOOST, curve.gain(10_000.0, 16), 1e-9)
    }

    @Test
    fun `sensitivity scales everything and direction is preserved`() {
        val one = PointerAcceleration(1f, accelerate = true)
        val two = PointerAcceleration(2f, accelerate = true)
        assertEquals(one.gain(8.0, 16) * 2, two.gain(8.0, 16), 1e-9)
        val (dx, dy) = PointerAcceleration(1f, accelerate = false).apply(-30.0, 40.0, 16)
        assertTrue(dx < 0 && dy > 0)
        assertEquals(-3.0 / 4.0, dx / dy, 1e-9)
    }

    @Test
    fun `only whole pixels go out and the fraction is carried, not lost`() {
        val flat = PointerAcceleration(1f, accelerate = false)
        // 0.3 dp a frame is 0.54 px: nothing the first frame, a pixel the second.
        var sent = 0.0
        repeat(100) {
            val (dx, _) = flat.apply(0.3, 0.0, 8)
            assertEquals(dx, kotlin.math.truncate(dx), 0.0)
            sent += dx
        }
        assertEquals(100 * 0.3 * PointerAcceleration.BASE_GAIN, sent, 1.0)
        // A new touch starts with nothing owed.
        flat.reset()
        assertEquals(0.0, flat.apply(0.3, 0.0, 8).first, 0.0)
    }

    @Test
    fun `an uneven frame cadence does not make a steady drag lurch`() {
        // The same steady 0.5 dp/ms, delivered 4 ms then 12 ms apart, with the
        // short frame reading slow because touch timestamps are coarse.
        val curve = PointerAcceleration(1f, accelerate = true)
        val raw = PointerAcceleration(1f, accelerate = true)
        repeat(20) {
            curve.apply(1.0, 0.0, 4)
            curve.apply(7.0, 0.0, 12)
        }
        val (smoothFast, _) = curve.apply(7.0, 0.0, 12)
        val swing = raw.gain(7.0, 12) / raw.gain(1.0, 4)
        // Per-frame gain would differ by [swing]; smoothed, a long frame's gain
        // sits far closer to its neighbours'.
        assertTrue(swing > 1.4)
        assertTrue(smoothFast / 7.0 < raw.gain(7.0, 12))
    }

    @Test
    fun `a stalled frame does not read as a standstill`() {
        // A long gap between events would divide the speed to nothing and drop
        // the pointer to its slowest gain mid-swipe.
        val curve = PointerAcceleration(1f, accelerate = true)
        assertEquals(curve.gain(20.0, PointerAcceleration.MAX_FRAME_MS), curve.gain(20.0, 5_000), 1e-9)
        assertEquals(curve.gain(20.0, 1), curve.gain(20.0, 0), 1e-9)
    }

    // ---- scrolling ----

    @Test
    fun `scroll is released a step at a time`() {
        val scroll = ScrollAccumulator(stepDp = 10.0)
        assertNull(scroll.add(0.0, 4.0))
        assertNull(scroll.add(0.0, 4.0))
        val out = scroll.add(0.0, 4.0)
        assertNotNull(out)
        assertEquals(12.0 * ScrollAccumulator.WIRE_UNITS_PER_DP, out!!.second, 1e-9)
        // Emptied by the release.
        assertNull(scroll.add(0.0, 1.0))
    }

    @Test
    fun `natural scrolling drags the content with the fingers`() {
        // Fingers down the glass is positive; the wire's positive dy scrolls up,
        // which moves the content down with them.
        assertTrue(ScrollAccumulator(stepDp = 1.0, natural = true).add(0.0, 5.0)!!.second > 0)
        assertTrue(ScrollAccumulator(stepDp = 1.0, natural = false).add(0.0, 5.0)!!.second < 0)
    }

    @Test
    fun `a reversal unwinds before it scrolls the other way`() {
        val scroll = ScrollAccumulator(stepDp = 10.0)
        assertNull(scroll.add(0.0, 8.0))
        assertNull(scroll.add(0.0, -12.0))
        assertTrue(scroll.add(0.0, -7.0)!!.second < 0)
    }
}
