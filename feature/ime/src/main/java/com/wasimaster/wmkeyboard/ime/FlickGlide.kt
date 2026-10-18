package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * What a stroke that began on a letter key with flick arms turned out to be,
 * once the finger lifted without glide typing having claimed it (#720).
 */
internal enum class FlickGlideVerdict {
    /** The arm in the stroke's direction: the key's own handler types it. */
    FLICK,

    /** A glided word: the glide loop takes the lift and decodes the stroke. */
    WORD,

    /** Neither: the key's handler decides, a forgiving arm or the centre tap. */
    KEY,
}

/**
 * Decides, for one stroke, whether a letter key's flick arms or glide typing
 * owns it (#720). A pure function of the samples, so the one-finger glide
 * loop, the two-thumb loop and a unit test all read the same answer.
 *
 * The rule it replaces (#655) let glide take the stroke the moment the finger
 * left the key's cell. A corner sits half a key from a key's centre, and a
 * natural corner flick on a phone-width key overshoots it nearly every time,
 * so on a board with arms on every letter (an Unexpected Keyboard import) a
 * glide took every flick and the arms stopped working.
 *
 * Now nothing is consumed while the stroke could still be a flick. It
 * becomes a glide mid-move ([step] returns true) only once it cannot be one:
 *
 * - it travelled further from the down than [reachPx], which is the short/long
 *   boundary CleverKeys uses (141 % of the key's diagonal by default);
 * - it reached the core of another letter key heading somewhere no arm
 *   points — "er" from an `e` whose arms are all on its corners;
 * - it reached another letter's core and turned. A flick goes out along one
 *   line and, for its capital, comes back along the same one; "the" from `t`
 *   does not.
 *
 * A stroke still undecided at the lift is judged by [lift]. An arm in its
 * exact direction wins even when the overshoot ran into the next key, unless
 * [ontoLetterGlides] says a lift on another letter means the word; any other
 * stroke that reached a second letter is a word ("pop", "lol" come back to
 * where they started and never travel far); and the rest goes back to the key,
 * whose own resolver is more forgiving about direction than this one is.
 *
 * A letter's core is its cell inset by [CORE_INSET] a side, so grazing the
 * junction where four keys meet, exactly where a corner flick ends, visits no
 * key at all. Every coordinate here, the cells included, is in one space; the
 * callers use root space, where [KeyRects] keeps its cells.
 */
internal class FlickGlideArbiter(
    private val downX: Float,
    private val downY: Float,
    /** The flick key's own cell. */
    private val cell: Rect,
    private val hasArm: (FlickDirection) -> Boolean,
    /** Travel past which the stroke is a glide whatever its shape. */
    private val reachPx: Float,
    /** How far the finger has to travel before its direction means anything. */
    private val slopPx: Float,
    private val keyWidthPx: Float,
    /** The least reach a stroke needs to be a word at the lift. */
    private val minWordReachPx: Float,
    private val ontoLetterGlides: Boolean,
    /** The cell of the letter key under a point, or null off the letters. */
    private val letterCellAt: (Float, Float) -> Rect?,
) {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val visited = HashSet<Rect>().apply { add(cell) }
    private var extremeX = downX
    private var extremeY = downY
    private var extremeDist = 0f

    init {
        xs.add(downX)
        ys.add(downY)
    }

    /** The farthest the finger has been from where it landed. */
    val reach: Float get() = extremeDist

    /** Feeds one sample; true once the stroke can only be a glide. */
    fun step(x: Float, y: Float): Boolean {
        xs.add(x)
        ys.add(y)
        val dist = hypot(x - downX, y - downY)
        if (dist > extremeDist) {
            extremeDist = dist
            extremeX = x
            extremeY = y
        }
        letterCoreAt(x, y)?.let { visited.add(it) }
        if (extremeDist > reachPx) return true
        if (visited.size < 2) return false
        return exactArm() == null || turned()
    }

    /** The verdict for a stroke [step] never latched, given the lift point. */
    fun lift(x: Float, y: Float): FlickGlideVerdict {
        step(x, y)
        val arm = exactArm()
        return when {
            arm != null -> {
                val onLetter = letterCoreAt(x, y)?.takeIf { it != cell } != null
                if (ontoLetterGlides && onLetter) FlickGlideVerdict.WORD else FlickGlideVerdict.FLICK
            }
            visited.size >= 2 && extremeDist >= minWordReachPx -> FlickGlideVerdict.WORD
            else -> FlickGlideVerdict.KEY
        }
    }

    /**
     * The arm the stroke's farthest point lies on, within [FLICK_EXACT_DEGREES]
     * of it, or null. Stricter than [resolveFlickDirection]'s 67.5° cone, which
     * is right for a key nobody glides on and would hand a corner arm every
     * sideways two-letter word here. A corner arm's bearing is the cell's real
     * corner rather than 45°: on a key half again as tall as it is wide the
     * corner is 33° off vertical, and that is where a finger aims.
     */
    private fun exactArm(): FlickDirection? {
        if (extremeDist < slopPx) return null
        val bearing = compassBearing(extremeX - downX, extremeY - downY)
        var best: FlickDirection? = null
        var bestGap = Float.MAX_VALUE
        for (direction in FlickDirection.entries) {
            if (!hasArm(direction)) continue
            val gap = angularGap(bearing, armBearing(direction, cell.width, cell.height))
            if (gap < bestGap) {
                bestGap = gap
                best = direction
            }
        }
        return best?.takeIf { bestGap <= FLICK_EXACT_DEGREES }
    }

    /**
     * Whether any sample strays more than [TURN_WIDTHS] of a key from the line
     * through the down and the farthest point: a stroke that changed direction
     * rather than one that went out and, maybe, came back.
     */
    private fun turned(): Boolean {
        val lineX = extremeX - downX
        val lineY = extremeY - downY
        val length = hypot(lineX, lineY)
        if (length < slopPx) return false
        val limit = keyWidthPx * TURN_WIDTHS
        for (i in xs.indices) {
            val offLine = abs((xs[i] - downX) * lineY - (ys[i] - downY) * lineX) / length
            if (offLine > limit) return true
        }
        return false
    }

    private fun letterCoreAt(x: Float, y: Float): Rect? {
        val letter = letterCellAt(x, y) ?: return null
        val insetX = letter.width * CORE_INSET
        val insetY = letter.height * CORE_INSET
        val inCore = x >= letter.left + insetX && x <= letter.right - insetX &&
            y >= letter.top + insetY && y <= letter.bottom - insetY
        return letter.takeIf { inCore }
    }
}

/**
 * The compass bearing an arm points along on a cell [width] by [height]: up at
 * 0°, clockwise. Edges are the four axes; corners are the cell's own corners
 * as seen from its centre, see [FlickGlideArbiter].
 */
internal fun armBearing(direction: FlickDirection, width: Float, height: Float): Float {
    if (!direction.isDiagonal || width <= 0f || height <= 0f) return direction.compassDegrees
    val corner = Math.toDegrees(atan2(width.toDouble(), height.toDouble())).toFloat()
    return when (direction) {
        FlickDirection.UP_RIGHT -> corner
        FlickDirection.DOWN_RIGHT -> 180f - corner
        FlickDirection.DOWN_LEFT -> 180f + corner
        else -> 360f - corner
    }
}

/** Screen offset to compass bearing, up at 0° and clockwise, as [resolveFlickDirection] reads it. */
private fun compassBearing(dx: Float, dy: Float): Float =
    Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat().let { if (it < 0f) it + 360f else it }

/**
 * How far off an arm a stroke that reached another letter may be and still
 * be that arm. One 45° sector each side would let a two-letter word along a
 * row reach a corner arm; this leaves the sideways strokes to glide typing.
 */
internal const val FLICK_EXACT_DEGREES = 30f

/** How far a stroke may stray from its own line, in key widths, before it has turned. */
internal const val TURN_WIDTHS = 0.35f

/** The share of a letter's cell, each side, that is not its core. */
internal const val CORE_INSET = 0.2f
