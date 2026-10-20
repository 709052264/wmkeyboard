package com.wasimaster.wmkeyboard.core.gesture

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A neural swipe model: reads a stroke and says, for each of a fixed number of
 * time steps, how likely each letter is to be the one the finger is writing
 * there. [CtcGlideBeam] turns those emissions into words.
 *
 * The one implementation is FUTO Swipe's encoder and English QWERTY decoder,
 * run on the app's ExecuTorch runtime in `:core:voice` (`FutoSwipeModel`).
 * Kept as an interface so this module, which holds the dictionaries and the
 * search, needs nothing native.
 */
interface SwipeModel {

    /**
     * Emissions for [features]: the stroke in [SwipeFrame]'s unit square,
     * resampled to [SwipeFrame.POINTS] points, x row then y row. Null when the
     * model could not run; the caller falls back to [GlideBeam].
     */
    fun emissions(features: FloatArray): SwipeEmissions?
}

/**
 * Log-probabilities over the 26 letters a to z, then the CTC blank, for each
 * of [steps] time steps, row-major.
 */
class SwipeEmissions(val steps: Int, val logProbs: FloatArray) {
    init {
        require(logProbs.size == steps * CLASSES) { "expected ${steps * CLASSES} values, got ${logProbs.size}" }
    }

    companion object {
        /** a..z, then blank. */
        const val CLASSES = 27
        const val BLANK = 26
    }
}

/**
 * The frame FUTO Swipe's models were trained in: the three letter rows of a
 * QWERTY keyboard as a unit square, each key a tenth of the width and a third
 * of the height, the home row half a key in and the bottom row a whole key.
 *
 * A real keyboard is mapped onto it with one scale and offset per axis, fitted
 * by least squares to where its own 26 letter keys sit, so key size, row
 * height, side padding and the stroke's pixel space all drop out. The fit is
 * also the layout check: a grid whose letters are not where QWERTY puts them
 * (AZERTY, Dvorak, any non-Latin layout) leaves a residual no QWERTY can, and
 * [fit] answers null for it.
 */
class SwipeFrame private constructor(
    private val ax: Float,
    private val bx: Float,
    private val ay: Float,
    private val by: Float,
) {

    /** [x], [y] in key widths (a [GlideKeyMap]'s units) to the unit square. */
    fun mapX(x: Float): Float = ax * x + bx
    fun mapY(y: Float): Float = ay * y + by

    /**
     * The stroke as the model reads it: mapped into the frame, put on a 60 Hz
     * clock, resampled to [POINTS] points by index and clamped to the square.
     * x row then y row. Null for a stroke too short to read.
     *
     * The clock comes first because that is how the models were trained: an
     * even clock keeps the stroke's speed in the sample spacing, so a pause
     * holds several samples on its key. A path with no clock at all (every
     * timestamp the same) is resampled by index alone.
     */
    fun features(path: List<GesturePoint>, keyWidth: Float): FloatArray? {
        if (path.size < 2 || keyWidth <= 0f) return null
        val n = path.size
        val xs = FloatArray(n) { mapX(path[it].x / keyWidth) }
        val ys = FloatArray(n) { mapY(path[it].y / keyWidth) }
        val t0 = path[0].t
        val duration = (path[n - 1].t - t0).toFloat()
        val clockX: FloatArray
        val clockY: FloatArray
        if (duration > 0f) {
            val count = maxOf(2, (duration / FRAME_MS).roundToInt() + 1)
            clockX = FloatArray(count)
            clockY = FloatArray(count)
            val step = duration / (count - 1)
            var hi = 0
            for (i in 0 until count) {
                val target = minOf(i * step, duration)
                // First sample at or after the target, as a lower bound.
                while (hi < n && (path[hi].t - t0).toFloat() < target) hi++
                when {
                    hi == 0 -> { clockX[i] = xs[0]; clockY[i] = ys[0] }
                    hi >= n -> { clockX[i] = xs[n - 1]; clockY[i] = ys[n - 1] }
                    else -> {
                        val ta = (path[hi - 1].t - t0).toFloat()
                        val span = (path[hi].t - t0).toFloat() - ta
                        val f = if (span > 0f) (target - ta) / span else 0f
                        clockX[i] = xs[hi - 1] + f * (xs[hi] - xs[hi - 1])
                        clockY[i] = ys[hi - 1] + f * (ys[hi] - ys[hi - 1])
                    }
                }
            }
        } else {
            clockX = xs
            clockY = ys
        }
        val out = FloatArray(2 * POINTS)
        val last = clockX.size - 1
        for (i in 0 until POINTS) {
            val pos = i.toFloat() * last / (POINTS - 1)
            val a = minOf(pos.toInt(), last)
            val b = minOf(a + 1, last)
            val f = pos - a
            out[i] = (clockX[a] + f * (clockX[b] - clockX[a])).coerceIn(0f, 1f)
            out[POINTS + i] = (clockY[a] + f * (clockY[b] - clockY[a])).coerceIn(0f, 1f)
        }
        return out
    }

    companion object {
        /** Points the models read a stroke as. */
        const val POINTS = 64

        /** The models' clock: 60 Hz. */
        private const val FRAME_MS = 1000f / 60f

        /** a..z centres in the frame, in alphabetical order. */
        val KEY_X = floatArrayOf(
            0.10f, 0.60f, 0.40f, 0.30f, 0.25f, 0.40f, 0.50f, 0.60f, 0.75f, 0.70f, 0.80f, 0.90f, 0.80f,
            0.70f, 0.85f, 0.95f, 0.05f, 0.35f, 0.20f, 0.45f, 0.65f, 0.50f, 0.15f, 0.30f, 0.55f, 0.20f,
        )
        val KEY_Y = floatArrayOf(
            0.500f, 0.833f, 0.833f, 0.500f, 0.167f, 0.500f, 0.500f, 0.500f, 0.167f, 0.500f, 0.500f, 0.500f, 0.833f,
            0.833f, 0.167f, 0.167f, 0.167f, 0.167f, 0.500f, 0.167f, 0.167f, 0.833f, 0.167f, 0.833f, 0.167f, 0.833f,
        )

        /**
         * Worst a letter may sit from where QWERTY puts it after the fit, in
         * frame units: a little over half a key across, a quarter of a row
         * down. The shipped QWERTY fits exactly (`SwipeFrameTest`), and a
         * variant whose bottom row is staggered half a key differently still
         * fits; every other Latin layout moves some letter to another row
         * (AZERTY's a and q, QWERTZ's y and z, most of Dvorak and Colemak),
         * which misses by a whole row.
         */
        private const val MAX_RESIDUAL_X = 0.055f
        private const val MAX_RESIDUAL_Y = 0.085f

        /**
         * The frame for [keys], or null when it does not hold all 26 letters
         * a to z on keys of their own, laid out as QWERTY.
         */
        fun fit(keys: GlideKeyMap): SwipeFrame? {
            val x = FloatArray(LETTERS)
            val y = FloatArray(LETTERS)
            val seen = HashSet<Int>()
            for (i in 0 until LETTERS) {
                val key = keys.keyIndex('a'.code + i)
                if (key < 0 || !seen.add(key)) return null
                x[i] = keys.keyX[key]
                y[i] = keys.keyY[key]
            }
            val (ax, bx) = line(x, KEY_X) ?: return null
            val (ay, by) = line(y, KEY_Y) ?: return null
            if (ax <= 0f || ay <= 0f) return null
            for (i in 0 until LETTERS) {
                if (abs(ax * x[i] + bx - KEY_X[i]) > MAX_RESIDUAL_X) return null
                if (abs(ay * y[i] + by - KEY_Y[i]) > MAX_RESIDUAL_Y) return null
            }
            return SwipeFrame(ax, bx, ay, by)
        }

        /** Least-squares `to ≈ a * from + b`, or null when [from] does not vary. */
        private fun line(from: FloatArray, to: FloatArray): Pair<Float, Float>? {
            val n = from.size
            val mf = from.average()
            val mt = to.average()
            var cov = 0.0
            var varF = 0.0
            for (i in 0 until n) {
                cov += (from[i] - mf) * (to[i] - mt)
                varF += (from[i] - mf) * (from[i] - mf)
            }
            if (varF < 1e-9) return null
            val a = cov / varF
            return a.toFloat() to (mt - a * mf).toFloat()
        }

        private const val LETTERS = 26
    }
}
