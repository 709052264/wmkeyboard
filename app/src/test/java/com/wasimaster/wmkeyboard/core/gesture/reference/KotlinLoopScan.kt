package com.wasimaster.wmkeyboard.core.gesture.reference

import com.wasimaster.wmkeyboard.core.gesture.DuetBeam
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import kotlin.math.sqrt
/**
 * The loop search [GlideBeam] and [DuetBeam] share: the places a stroke went
 * round on itself or rubbed back and forth, found as windows of a fine
 * resample of it. Finding them is the same job on a one-finger stroke and on
 * each swipe of a two-thumb word, so it lives here once (issue #740); what a
 * window *means* — the key it marks, what it waives and what it charges — is
 * each decoder's to decide. See [GlideBeam.Tuning.loopExtent] for the reading
 * and [GlideBeam.Tuning.wiggleExtent] for the wiggle's.
 *
 * One per workspace, reused across decodes, so a search allocates nothing
 * once its arrays have grown to the longest stroke seen.
 */
class KotlinLoopScan {

    /**
     * The stroke resampled finely, for the loop search alone: [fineCount]
     * points [fineStep] key widths apart. An alignment's samples are too
     * coarse for it — on a long word they sit half a key apart, and a loop
     * on a key is three of them.
     */
    var fineX = FloatArray(INITIAL_FINE); private set
    var fineY = FloatArray(INITIAL_FINE); private set
    var fineCount = 0; private set
    var fineStep = 0f; private set

    /**
     * What [find] found, in stroke order: fine points [windowFrom] to
     * [windowTo], a loop when [windowIsLoop] and a wiggle when not.
     */
    var windowFrom = IntArray(INITIAL_WINDOWS); private set
    var windowTo = IntArray(INITIAL_WINDOWS); private set
    var windowIsLoop = BooleanArray(INITIAL_WINDOWS); private set
    var windowCount = 0; private set

    /** The last [measure]d window's centroid in key widths, its path length and its chord. */
    var centreX = 0f; private set
    var centreY = 0f; private set
    var length = 0f; private set
    var chord = 0f; private set

    /**
     * Finds the loops and wiggles along [path] (pixels, keys [keyWidth]
     * wide) and returns how many. Nothing is found at zero extent.
     *
     * The search runs on a fine resample of the stroke rather than on the
     * alignment's samples: on a long word those sit half a key apart, a loop
     * is three of them, and no three points make a circle. The first start
     * that passes is usually a step or two early, on the way in, so once a
     * window is found every later start inside it is tried too and the
     * roundest wins — the loop itself rather than the loop with its approach.
     */
    fun find(path: List<GesturePoint>, keyWidth: Float, tuning: GlideBeam.Tuning): Int {
        windowCount = 0
        val reach = maxOf(tuning.loopExtent, tuning.wiggleExtent)
        if (reach <= 0f) return 0
        resampleFine(path, keyWidth)
        val n = fineCount
        var a = 0
        while (a < n - 2) {
            var window = roundestWindow(a, reach, tuning)
            if (window == NO_WINDOW) {
                a++
                continue
            }
            var from = a
            var other = a + 1
            while (other < windowEnd(window) - 1) {
                val later = roundestWindow(other, reach, tuning)
                if (later != NO_WINDOW && windowRoundness(later) > windowRoundness(window)) {
                    window = later
                    from = other
                }
                other++
            }
            val b = windowEnd(window)
            val extent = sqrt(extentSq(from, b))
            val loop = windowRoundness(window) >= LOOP_ROUNDNESS && extent <= tuning.loopExtent
            val wiggle = !loop && tuning.wiggleExtent > 0f && extent <= tuning.wiggleExtent
            if (!loop && !wiggle) {
                a++
                continue
            }
            addWindow(from, b, loop)
            a = b + 1
        }
        return windowCount
    }

    /**
     * Measures the fine window `[from, to]` into [centreX], [centreY],
     * [length] and [chord]: where the mark sat, and how much of its path the
     * finger net-moved.
     */
    fun measure(from: Int, to: Int) {
        val xs = fineX
        val ys = fineY
        var cx = 0f
        var cy = 0f
        var path = 0f
        for (j in from..to) {
            cx += xs[j]
            cy += ys[j]
            if (j > from) {
                val sx = xs[j] - xs[j - 1]
                val sy = ys[j] - ys[j - 1]
                path += sqrt(sx * sx + sy * sy)
            }
        }
        val points = (to - from + 1).toFloat()
        centreX = cx / points
        centreY = cy / points
        length = path
        val dx = xs[to] - xs[from]
        val dy = ys[to] - ys[from]
        chord = sqrt(dx * dx + dy * dy)
    }

    /**
     * The key nearest ([x], [y]) within `sqrt(radiusSq)` key widths, or -1.
     * Only that key is credited with a mark: a loop is a mark on one key, and
     * crediting every key in reach let a circle drawn a little off the f of
     * `off` double the d of `odd` (issue #337).
     */
    fun nearestKey(x: Float, y: Float, keys: GlideKeyMap, radiusSq: Float): Int {
        var own = -1
        var nearestSq = radiusSq
        for (k in 0 until keys.keyCount) {
            val kx = x - keys.keyX[k]
            val ky = y - keys.keyY[k]
            val d = kx * kx + ky * ky
            if (d <= nearestSq) {
                nearestSq = d
                own = k
            }
        }
        return own
    }

    private fun addWindow(from: Int, to: Int, loop: Boolean) {
        val w = windowCount
        if (w == windowFrom.size) {
            windowFrom = windowFrom.copyOf(w * 2)
            windowTo = windowTo.copyOf(w * 2)
            windowIsLoop = windowIsLoop.copyOf(w * 2)
        }
        windowFrom[w] = from
        windowTo[w] = to
        windowIsLoop[w] = loop
        windowCount = w + 1
    }

    /**
     * Resamples [path] into [fineX] and [fineY] at [FINE_SPACING] key widths
     * a point — coarser only when a stroke would need more than
     * [MAX_FINE_POINTS] of them. Position only: the loop search has no use
     * for the clock.
     */
    private fun resampleFine(path: List<GesturePoint>, keyWidth: Float) {
        var totalPx = 0f
        for (i in 1 until path.size) totalPx += distance(path[i - 1], path[i])
        val total = totalPx / keyWidth
        val spacing = maxOf(FINE_SPACING, total / (MAX_FINE_POINTS - 1))
        val count = (total / spacing).toInt() + 1
        if (fineX.size < count) {
            fineX = FloatArray(count)
            fineY = FloatArray(count)
        }
        fineStep = spacing
        val stepPx = spacing * keyWidth
        val first = path.first()
        fineX[0] = first.x / keyWidth
        fineY[0] = first.y / keyWidth
        var out = 1
        var index = 0
        var cx = first.x
        var cy = first.y
        var accumulated = 0f
        while (out < count && index < path.size - 1) {
            val next = path[index + 1]
            val segment = sqrt((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy))
            if (accumulated + segment >= stepPx && segment > 0f) {
                val fraction = (stepPx - accumulated) / segment
                cx += fraction * (next.x - cx)
                cy += fraction * (next.y - cy)
                fineX[out] = cx / keyWidth
                fineY[out] = cy / keyWidth
                out++
                accumulated = 0f
            } else {
                accumulated += segment
                cx = next.x
                cy = next.y
                index++
            }
        }
        fineCount = out
    }

    /**
     * Of the windows of fine points starting at [a] that stay within [reach]
     * of themselves and hold at least [GlideBeam.Tuning.loopMinArc] of path
     * and [LOOP_RATIO] times their own extent, the roundest — its end and its
     * roundness packed into one long, or [NO_WINDOW].
     *
     * Roundness is the isoperimetric quotient `4πA / P²` of the polygon the
     * window's points make when closed back to the start: 1 for a circle,
     * nothing for a path that goes out and comes back along itself, and
     * nothing for a zigzag, whose signed area cancels. Turning was the
     * obvious measure and the wrong one: a loop drawn on a key that sits at
     * a reversal — the `o` of `good` on QWERTY — has the corner's own turn
     * cancelling half of the circle's, and a loop entered on a cusp cancels
     * all of it. Area does not care which way round the finger went.
     *
     * Path length is the sum of the chords, not the point count times the
     * spacing: a sharp corner between two points has less chord than
     * spacing, and counting the spacing turned every hairpin into a loop.
     * The extent is the window's widest pairwise distance, which only grows
     * as the window does, so the scan stops the moment it passes [reach].
     */
    private fun roundestWindow(a: Int, reach: Float, tuning: GlideBeam.Tuning): Long {
        val n = fineCount
        val xs = fineX
        val ys = fineY
        val ax = xs[a]
        val ay = ys[a]
        val reachSq = reach * reach
        var extentSq = 0f
        var length = 0f
        var twiceArea = 0f
        var bestEnd = -1
        var bestRoundness = 0f
        var b = a + 1
        while (b < n && b - a <= MAX_WINDOW_POINTS) {
            val bx = xs[b]
            val by = ys[b]
            val sx = bx - xs[b - 1]
            val sy = by - ys[b - 1]
            length += sqrt(sx * sx + sy * sy)
            twiceArea += xs[b - 1] * by - bx * ys[b - 1]
            var i = a
            while (i < b) {
                val dx = xs[i] - bx
                val dy = ys[i] - by
                val d = dx * dx + dy * dy
                if (d > extentSq) extentSq = d
                i++
            }
            if (extentSq > reachSq) break
            if (length >= tuning.loopMinArc && extentSq >= MIN_LOOP_EXTENT_SQ &&
                length * length >= LOOP_RATIO * LOOP_RATIO * extentSq
            ) {
                val closed = twiceArea + (bx * ay - ax * by)
                val dx = bx - ax
                val dy = by - ay
                val perimeter = length + sqrt(dx * dx + dy * dy)
                val roundness = TWO_PI * (if (closed < 0f) -closed else closed) / (perimeter * perimeter)
                if (roundness > bestRoundness) {
                    bestRoundness = roundness
                    bestEnd = b
                }
            }
            b++
        }
        return if (bestEnd < 0) NO_WINDOW else (bestEnd.toLong() shl Int.SIZE_BITS) or
            (bestRoundness.toRawBits().toLong() and 0xffffffffL)
    }

    private fun windowEnd(window: Long): Int = (window shr Int.SIZE_BITS).toInt()

    private fun windowRoundness(window: Long): Float = Float.fromBits(window.toInt())

    /** Squared widest pairwise distance among fine points `[a, b]`. */
    private fun extentSq(a: Int, b: Int): Float {
        var extent = 0f
        for (j in a + 1..b) {
            for (i in a until j) {
                val dx = fineX[i] - fineX[j]
                val dy = fineY[i] - fineY[j]
                val d = dx * dx + dy * dy
                if (d > extent) extent = d
            }
        }
        return extent
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /** Most points the fine resample holds; a longer stroke is spaced out to fit. */
        const val MAX_FINE_POINTS = 1024

        private const val INITIAL_FINE = 256
        private const val INITIAL_WINDOWS = 16

        /**
         * Arc over extent a window needs to be a loop: a straight pass is 1,
         * any single corner at most 2 — exactly 2 for a full reversal — and a
         * circle π. Halfway between the corner and the circle.
         */
        private const val LOOP_RATIO = 2.5f

        /**
         * Least roundness — `4πA / P²` of the closed window, 1 for a circle —
         * a loop must show. A loop drawn as an egg or a teardrop still clears
         * this; a corner, a reversal and a zigzag are nowhere near it.
         */
        private const val LOOP_ROUNDNESS = 0.55f

        /** Point spacing of the fine resample, in key widths. */
        private const val FINE_SPACING = 0.08f

        /** Longest window the search extends, in fine points: five key widths of path. */
        private const val MAX_WINDOW_POINTS = 64

        private const val TWO_PI = 2f * Math.PI.toFloat()

        /** [roundestWindow]'s answer when no window starting at a point is a loop. */
        private const val NO_WINDOW = -1L

        /** Below this extent a window is tremor, whatever its path: a loop a finger means is wider than a third of a key. */
        private const val MIN_LOOP_EXTENT_SQ = 0.3f * 0.3f
    }
}
