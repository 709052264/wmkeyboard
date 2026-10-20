package com.wasimaster.wmkeyboard.core.gesture.latinime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import kotlin.math.roundToInt

/**
 * A keyboard as AOSP LatinIME's native `ProximityInfo` takes it: integer key
 * rectangles in a pixel space whose origin is the top left of the leftmost,
 * topmost key, one code per key, and for each cell of a [GRID_WIDTH] by
 * [GRID_HEIGHT] grid the codes of the keys near it. The arrays are exactly
 * the arguments of `ProximityInfo.setProximityInfoNative`.
 *
 * [originX] and [originY] are where that origin sits in the stroke's own
 * pixel space, so [LatinImeGeometry.trace] can move a stroke onto it.
 */
class LatinImeKeyboard internal constructor(
    val width: Int,
    val height: Int,
    val keyWidth: Int,
    val keyHeight: Int,
    val proximityChars: IntArray,
    val keyX: IntArray,
    val keyY: IntArray,
    val keyWidths: IntArray,
    val keyHeights: IntArray,
    val codes: IntArray,
    internal val originX: Float,
    internal val originY: Float,
) {
    val keyCount: Int get() = codes.size
    val gridWidth: Int get() = LatinImeGeometry.GRID_WIDTH
    val gridHeight: Int get() = LatinImeGeometry.GRID_HEIGHT
}

/**
 * A stroke as `BinaryDictionary.getSuggestionsNative` takes it: integer
 * coordinates on a [LatinImeKeyboard], milliseconds since the stroke began,
 * one pointer.
 */
class LatinImeTrace internal constructor(
    val xs: IntArray,
    val ys: IntArray,
    val times: IntArray,
    val pointerIds: IntArray,
) {
    val size: Int get() = xs.size
}

/**
 * The conversions between this keyboard's glide model and the one Google's
 * gesture library was written for: the key grid ([keyboard]), the stroke
 * ([trace]) and the answer ([candidates]). Pure, so the JVM tests can hold it
 * to the native code's expectations without the library.
 *
 * The library wants one code per key and no duplicates. A [GlideKeyMap]
 * already collapses a key's shifted and long-press characters onto its
 * centre, so each key is labelled with the character it was first reported
 * with, its base label; a second key with the same label is left out
 * (HeliBoard notes that duplicate keys confuse the library).
 */
object LatinImeGeometry {

    /** The grid AOSP LatinIME divides a keyboard into (`config_keyboard_grid_*`). */
    const val GRID_WIDTH = 32
    const val GRID_HEIGHT = 16

    /** Codes per grid cell; `MAX_PROXIMITY_CHARS_SIZE` in the native code. */
    const val MAX_PROXIMITY_CHARS = 16

    /** An empty slot in the proximity table, and an empty input code. */
    const val NOT_A_CODE = -1

    /** Keys within this many key widths of a cell's centre are near it, as in AOSP. */
    private const val SEARCH_DISTANCE = 1.2f

    /**
     * The longest stroke handed over. The native code copies the trace onto
     * its stack, so a long, densely sampled stroke is thinned to this many
     * points first; AOSP's own strokes are of this order.
     */
    const val MAX_POINTS = 256

    /** The space bar's code and the floor of a key the library places (`needsProximityInfo`). */
    private const val CODE_SPACE = 0x20

    /** Centres closer than this, in key widths, are one row. */
    private const val ROW_TOLERANCE = 0.3f

    /** Row pitch, in key widths, for a grid of one row: about a phone's. */
    private const val SINGLE_ROW_PITCH = 1.4f

    /** A clock for a stroke that came without one, so speed reads as even. */
    private const val SYNTHETIC_STEP_MS = 8

    /**
     * [keys] (in key widths, keys [keyWidthPx] wide) as the native code's
     * keyboard, or null when fewer than two keys can be placed.
     */
    fun keyboard(keys: GlideKeyMap, keyWidthPx: Float): LatinImeKeyboard? {
        if (keyWidthPx <= 0f || keys.keyCount < 2) return null
        val placed = ArrayList<Int>(keys.keyCount)
        val seen = HashSet<Int>()
        for (k in 0 until keys.keyCount) {
            val code = keys.primaryCodePoint(k)
            if (code < CODE_SPACE || !seen.add(code)) continue
            placed += k
        }
        if (placed.size < 2) return null
        // AOSP sorts keys top to bottom, then left to right; the proximity
        // table lists a cell's keys in that order.
        placed.sortWith(compareBy({ keys.keyY[it] }, { keys.keyX[it] }))
        val pitch = rowPitch(placed.map { keys.keyY[it] })
        val w = keyWidthPx.roundToInt().coerceAtLeast(1)
        val h = (pitch * keyWidthPx).roundToInt().coerceAtLeast(1)
        val originX = placed.minOf { keys.keyX[it] } * keyWidthPx - w / 2f
        val originY = placed.minOf { keys.keyY[it] } * keyWidthPx - h / 2f
        val n = placed.size
        val xs = IntArray(n)
        val ys = IntArray(n)
        val codes = IntArray(n)
        for ((i, k) in placed.withIndex()) {
            xs[i] = (keys.keyX[k] * keyWidthPx - w / 2f - originX).roundToInt().coerceAtLeast(0)
            ys[i] = (keys.keyY[k] * keyWidthPx - h / 2f - originY).roundToInt().coerceAtLeast(0)
            codes[i] = keys.primaryCodePoint(k)
        }
        val width = (0 until n).maxOf { xs[it] + w }
        val height = (0 until n).maxOf { ys[it] + h }
        return LatinImeKeyboard(
            width = width,
            height = height,
            keyWidth = w,
            keyHeight = h,
            proximityChars = proximity(width, height, xs, ys, w, h, codes),
            keyX = xs,
            keyY = ys,
            keyWidths = IntArray(n) { w },
            keyHeights = IntArray(n) { h },
            codes = codes,
            originX = originX,
            originY = originY,
        )
    }

    /**
     * For each grid cell, row-major, up to [MAX_PROXIMITY_CHARS] codes of the
     * keys whose rectangle comes within [SEARCH_DISTANCE] key widths of the
     * cell's centre, [NOT_A_CODE] after the last. AOSP's
     * `ProximityInfo.computeNearestNeighbors` and its
     * `Key.squaredDistanceToEdge`, without the cell pre-selection that is
     * only a speed-up.
     */
    @Suppress("LongParameterList")
    private fun proximity(
        width: Int,
        height: Int,
        xs: IntArray,
        ys: IntArray,
        w: Int,
        h: Int,
        codes: IntArray,
    ): IntArray {
        val cellWidth = (width + GRID_WIDTH - 1) / GRID_WIDTH
        val cellHeight = (height + GRID_HEIGHT - 1) / GRID_HEIGHT
        val threshold = (w * SEARCH_DISTANCE).toInt()
        val thresholdSquared = threshold * threshold
        val table = IntArray(GRID_WIDTH * GRID_HEIGHT * MAX_PROXIMITY_CHARS) { NOT_A_CODE }
        for (gy in 0 until GRID_HEIGHT) {
            val cy = gy * cellHeight + cellHeight / 2
            for (gx in 0 until GRID_WIDTH) {
                val cx = gx * cellWidth + cellWidth / 2
                var slot = (gy * GRID_WIDTH + gx) * MAX_PROXIMITY_CHARS
                val end = slot + MAX_PROXIMITY_CHARS
                for (i in codes.indices) {
                    if (slot >= end) break
                    if (squaredDistanceToEdge(cx, cy, xs[i], ys[i], w, h) < thresholdSquared) {
                        table[slot++] = codes[i]
                    }
                }
            }
        }
        return table
    }

    @Suppress("LongParameterList")
    private fun squaredDistanceToEdge(x: Int, y: Int, left: Int, top: Int, w: Int, h: Int): Int {
        val edgeX = x.coerceIn(left, left + w)
        val edgeY = y.coerceIn(top, top + h)
        val dx = x - edgeX
        val dy = y - edgeY
        return dx * dx + dy * dy
    }

    /**
     * The distance between letter rows in key widths: the smallest gap
     * between distinct row centres, so a number row or a spacebar row does
     * not stretch the letter keys.
     */
    private fun rowPitch(ys: List<Float>): Float {
        val rows = ArrayList<Float>()
        for (y in ys.sorted()) {
            if (rows.isEmpty() || y - rows.last() > ROW_TOLERANCE) rows += y
        }
        var best = Float.MAX_VALUE
        for (i in 1 until rows.size) best = minOf(best, rows[i] - rows[i - 1])
        return if (best == Float.MAX_VALUE) SINGLE_ROW_PITCH else best
    }

    /**
     * [path] (the stroke's pixel space) moved onto [board], clamped inside
     * it, consecutive repeats of one point dropped, thinned to [MAX_POINTS]
     * and timed from its first point; null for fewer than two points. A path
     * with no clock (every timestamp equal) gets an even one, since the
     * library reads speed off the timestamps.
     */
    fun trace(path: List<GesturePoint>, board: LatinImeKeyboard): LatinImeTrace? {
        if (path.size < 2) return null
        val xs = ArrayList<Int>(path.size)
        val ys = ArrayList<Int>(path.size)
        val ts = ArrayList<Long>(path.size)
        for (p in path) {
            val x = (p.x - board.originX).roundToInt().coerceIn(0, board.width - 1)
            val y = (p.y - board.originY).roundToInt().coerceIn(0, board.height - 1)
            if (xs.isNotEmpty() && xs.last() == x && ys.last() == y) continue
            xs += x
            ys += y
            ts += p.t
        }
        if (xs.size < 2) return null
        val picks = thin(xs.size, MAX_POINTS)
        val n = picks.size
        val t0 = ts[picks[0]]
        val clocked = ts[picks[n - 1]] > t0
        val times = IntArray(n)
        for (i in 0 until n) {
            val t = if (clocked) (ts[picks[i]] - t0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt() else i * SYNTHETIC_STEP_MS
            // Never backwards: the library divides by the time between samples.
            times[i] = if (i > 0) maxOf(t, times[i - 1]) else t
        }
        return LatinImeTrace(
            xs = IntArray(n) { xs[picks[it]] },
            ys = IntArray(n) { ys[picks[it]] },
            times = times,
            pointerIds = IntArray(n),
        )
    }

    /** Indices of [max] points spread evenly over [size], first and last kept; all of them when they fit. */
    fun thin(size: Int, max: Int): IntArray {
        if (size <= max) return IntArray(size) { it }
        return IntArray(max) { i -> ((i.toLong() * (size - 1) + (max - 1) / 2) / (max - 1)).toInt() }
    }

    /**
     * The library's answer as words and scores in nats, best first: [count]
     * results, each up to [maxWordLength] code points from [codePoints],
     * zero-terminated, scored by [scores]. The top result scores 0 and the
     * rest fall below it by their score gap over [SCORE_PER_NAT]. Empty words,
     * and two-word readings (the library's space-aware gestures are off, so
     * there should be none), are dropped.
     */
    fun candidates(codePoints: IntArray, scores: IntArray, count: Int, maxWordLength: Int): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Int>>(count)
        for (j in 0 until count.coerceAtMost(scores.size)) {
            val start = j * maxWordLength
            if (start >= codePoints.size) break
            var len = 0
            while (len < maxWordLength && start + len < codePoints.size && codePoints[start + len] != 0) len++
            if (len == 0) continue
            val word = String(codePoints, start, len)
            if (word.any { it.isWhitespace() }) continue
            out += word to scores[j]
        }
        if (out.isEmpty()) return emptyList()
        out.sortByDescending { it.second }
        val top = out[0].second.toDouble()
        val seen = HashSet<String>()
        return out.filter { seen.add(it.first) }.map { (word, score) -> word to (score - top) / SCORE_PER_NAT }
    }

    /**
     * How many of the library's score units make one nat of ours. AOSP
     * scales its scores by a million (`SUGGEST_INTERFACE_OUTPUT_SCALE`), so
     * one unit of its own distance is taken as one nat. Not measured: no
     * phone has run the library against this keyboard yet, so the gap the
     * ambiguity picker reads off these scores is a guess until one has.
     */
    const val SCORE_PER_NAT = 1_000_000.0

    /** [path]'s span in key widths, for the same too-short-to-be-a-glide test the other engines use. */
    internal fun spanKeys(path: List<GesturePoint>, keyWidthPx: Float): Float {
        var total = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            total += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return if (keyWidthPx > 0f) total / keyWidthPx else 0f
    }
}
