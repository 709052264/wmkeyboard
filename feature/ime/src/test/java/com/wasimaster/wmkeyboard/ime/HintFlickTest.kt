package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shape test that decides whether a downward stroke off a key types its
 * corner hint or is left to the glide decoder (issue #178).
 *
 * Direction, length, straightness and total duration decide. The octopus flick
 * does without the last of those; this one keeps it because a short downward
 * stroke is what a two-letter word across neighbouring rows looks like, and the
 * clock is the only thing that separates a flick from a quick "ed".
 */
class HintFlickTest {

    private val keyHeight = 100f
    private val minTravel = keyHeight * HINT_FLICK_MIN_TRAVEL_HEIGHTS

    /** A straight stroke [degrees] off straight down, sampled evenly. */
    private fun stroke(
        degrees: Float = 0f,
        length: Float = 100f,
        durationMs: Long = 90L,
        samples: Int = 6,
    ): List<GesturePoint> {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = (sin(radians) * length).toFloat()
        val dy = (cos(radians) * length).toFloat()
        return (0..samples).map { i ->
            val f = i.toFloat() / samples
            GesturePoint(200f + dx * f, 400f + dy * f, durationMs * i / samples)
        }
    }

    private fun flick(points: List<GesturePoint>) =
        hintFlick(points = points, keyHeightPx = keyHeight, minTravelPx = minTravel)

    // ---- what a flick looks like ----

    @Test
    fun `a quick straight flick down types the hint`() {
        assertTrue(flick(stroke()))
    }

    @Test
    fun `a slight lean either way is still a flick`() {
        assertTrue(flick(stroke(degrees = 20f)))
        assertTrue(flick(stroke(degrees = -20f)))
    }

    @Test
    fun `half a key height is enough`() {
        assertTrue(flick(stroke(length = minTravel)))
    }

    @Test
    fun `two samples are enough to have a direction`() {
        assertTrue(flick(stroke(samples = 1)))
    }

    @Test
    fun `a flick that decelerates at the end still counts`() {
        // Most of the travel in the first half, then a crawl: the total time is
        // what is judged, never the speed of the last samples.
        val points = listOf(
            GesturePoint(200f, 400f, 0L),
            GesturePoint(200f, 470f, 30L),
            GesturePoint(200f, 495f, 90L),
            GesturePoint(200f, 500f, 180L),
        )
        assertTrue(flick(points))
    }

    // ---- what is not one ----

    @Test
    fun `an upward stroke is never a hint flick`() {
        assertFalse(flick(stroke(degrees = 180f)))
    }

    @Test
    fun `a sideways stroke is left alone`() {
        assertFalse(flick(stroke(degrees = 60f)))
        assertFalse(flick(stroke(degrees = 90f)))
    }

    @Test
    fun `too short is a tap that drifted`() {
        assertFalse(flick(stroke(length = minTravel - 1f)))
    }

    @Test
    fun `too long is a drawn stroke`() {
        assertFalse(flick(stroke(length = keyHeight * HINT_FLICK_MAX_TRAVEL_HEIGHTS + 1f)))
    }

    @Test
    fun `a slow drag down is not a flick`() {
        assertFalse(flick(stroke(durationMs = HINT_FLICK_MAX_MS + 1)))
    }

    @Test
    fun `a wandering path that ends lower is a glide`() {
        // Out to the side and back before heading down: far more path than
        // straight line.
        val points = listOf(
            GesturePoint(200f, 400f, 0L),
            GesturePoint(260f, 420f, 20L),
            GesturePoint(140f, 440f, 40L),
            GesturePoint(200f, 500f, 60L),
        )
        assertFalse(flick(points))
    }

    @Test
    fun `a single point has no direction`() {
        assertFalse(flick(listOf(GesturePoint(200f, 400f, 0L))))
    }

    // ---- onto another letter: a word, not a flick (#859) ----

    /**
     * QWERTY's top two rows, keys 100 wide and 150 tall, the second row set in
     * half a key: `o` is the ninth key of the first, `k` the eighth of the second.
     */
    private val qwerty: List<Rect> =
        List(10) { i -> Rect(i * 100f, 0f, i * 100f + 100f, 150f) } +
            List(9) { i -> Rect(50f + i * 100f, 150f, 150f + i * 100f, 300f) }
    private val o = qwerty[8]
    private val k = qwerty[10 + 7]
    private val d = qwerty[10 + 2]
    private val e = qwerty[2]

    private fun letterAt(x: Float, y: Float): Rect? = qwerty.firstOrNull { it.contains(Offset(x, y)) }

    private fun line(from: Rect, toX: Float, toY: Float, durationMs: Long) = (0..6).map { i ->
        val f = i / 6f
        GesturePoint(
            from.center.x + (toX - from.center.x) * f,
            from.center.y + (toY - from.center.y) * f,
            durationMs * i / 6,
        )
    }

    private fun onQwerty(points: List<GesturePoint>, from: Rect, direction: KeyFlickDirection) = keyFlick(
        points,
        keyHeightPx = from.height,
        minTravelPx = from.height * HINT_FLICK_MIN_TRAVEL_HEIGHTS,
        direction = direction,
    ) { x, y -> inAnotherLetterCore(x, y, from, ::letterAt) }

    @Test
    fun `a quick glide from o to k types ok, not the hint`() {
        for (ms in listOf(150L, 250L)) {
            assertFalse(onQwerty(line(o, k.center.x, k.center.y, ms), o, KeyFlickDirection.DOWN))
        }
    }

    @Test
    fun `a flick that stops short of the next row still types the hint`() {
        assertTrue(onQwerty(line(o, o.center.x, o.center.y + 100f, 120L), o, KeyFlickDirection.DOWN))
    }

    /** Straight down from `o` is the seam between `k` and `l`, which is neither key's core. */
    @Test
    fun `a flick that overshoots onto the seam below still types the hint`() {
        assertTrue(onQwerty(line(o, o.center.x, o.center.y + 160f, 120L), o, KeyFlickDirection.DOWN))
    }

    @Test
    fun `a quick glide from d up to e is the word, not the capital`() {
        assertFalse(onQwerty(line(d, e.center.x, e.center.y, 150L), d, KeyFlickDirection.UP))
    }

    @Test
    fun `the key the stroke began on is not another letter`() {
        assertFalse(inAnotherLetterCore(o.center.x, o.center.y, o, ::letterAt))
        assertTrue(inAnotherLetterCore(k.center.x, k.center.y, o, ::letterAt))
    }
}
