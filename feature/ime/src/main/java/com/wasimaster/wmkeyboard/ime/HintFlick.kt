package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Whether a stroke is the short downward flick that types the key's corner
 * hint — its first long-press character — without waiting out the hold
 * (issue #178).
 *
 * The shape test lives beside [octopusFlick] and is built the same way, and for
 * the same reason: a gesture that has to be told apart from a glide must be
 * checkable without a live keyboard. Both callers — the glide loop, which asks
 * at its own lift, and the loop that runs for the strokes the glide loop never
 * claims — ask this one function.
 *
 * Judged at the lift. A flick is over before anything could be gained by
 * claiming the pointer early, and claiming it early would take every downward
 * stroke off the glide decoder instead of only the ones that turned out to be
 * flicks.
 *
 * What separates it from a glide is where the stroke went and how long it took:
 *
 * - **Down**, inside a cone about straight down. A glide toward the row below
 *   leans, and a flick does not.
 * - **Short.** From half a key height to [HINT_FLICK_MAX_TRAVEL_HEIGHTS] key
 *   heights. Any further and the finger is drawing, not flicking.
 * - **Straight.** The path may wander only a little past the straight line.
 * - **Quick.** Down to up inside [HINT_FLICK_MAX_MS]. This is the one rule the
 *   octopus flick deliberately does without, and it is here on purpose: the
 *   octopus judges *upward* strokes, which few words open with, while a short
 *   downward stroke is exactly what a two-letter word on neighbouring rows looks
 *   like. Total duration, not instantaneous speed — a flick decelerates as the
 *   finger leaves the glass, and a rule about its last samples would reject the
 *   gesture it is meant to accept.
 *
 * - **Not onto another letter** (#859). A flick ends where the finger leaves
 *   the glass, short of the next row or on the seam between two of its keys;
 *   a glide aims for a key. A stroke that lifts inside another letter key's
 *   core ([inAnotherLetterCore]) is a word like "ok" or "ed", however quick and
 *   straight. On staggered rows the key straight below is two half keys, so a
 *   flick that overshoots a whole row still lands on their seam and counts.
 *
 * Even so, a stroke that lifts on the seam can be a fast glide of a two-letter
 * word. That is the trade the setting's own text names, and why it ships off.
 *
 * @param points the stroke, oldest first, stamped with their arrival times
 * @param keyHeightPx the unit travel is measured in
 * @param minTravelPx the shortest stroke that counts; the caller floors it at
 *        the system's touch slop so a tiny key cannot turn a sloppy tap into a
 *        flick
 * @param landsOnLetter whether a point, in the stroke's own space, is in the
 *        core of a letter key other than the one the stroke began on
 */
internal fun hintFlick(
    points: List<GesturePoint>,
    keyHeightPx: Float,
    minTravelPx: Float,
    landsOnLetter: (x: Float, y: Float) -> Boolean = NoLetter,
): Boolean = keyFlick(points, keyHeightPx, minTravelPx, KeyFlickDirection.DOWN, landsOnLetter)

/** Which way a [keyFlick] goes: down for the corner hint, up for the capital. */
internal enum class KeyFlickDirection { UP, DOWN }

/**
 * Whether a stroke is the short, straight, quick flick off a key in [direction]:
 * the shape [hintFlick] tests for downward, and the up-flick that types a key's
 * capital tests for upward. Same cone, length, straightness and clock both ways,
 * so the two gestures feel like one pair.
 *
 * An upward stroke is also what the octopus flick takes a floating word with;
 * that one is decided by its callers, which leave a key carrying a word to it.
 */
internal fun keyFlick(
    points: List<GesturePoint>,
    keyHeightPx: Float,
    minTravelPx: Float,
    direction: KeyFlickDirection,
    landsOnLetter: (x: Float, y: Float) -> Boolean = NoLetter,
): Boolean {
    if (keyHeightPx <= 0f || points.size < 2) return false
    val first = points.first()
    val last = points.last()
    if (last.t - first.t > HINT_FLICK_MAX_MS) return false

    val dx = last.x - first.x
    // Measured along the flick's own direction: positive is the right way.
    val dy = if (direction == KeyFlickDirection.DOWN) last.y - first.y else first.y - last.y
    // The right way, and inside the cone.
    if (dy <= 0f) return false
    val direct = hypot(dx, dy)
    if (direct < minTravelPx) return false
    if (direct > keyHeightPx * HINT_FLICK_MAX_TRAVEL_HEIGHTS) return false
    if (angleOffDown(dx, dy) > HINT_FLICK_CONE_DEGREES) return false

    // One motion, not a path that happened to end lower than it started.
    var travelled = 0f
    for (i in 1 until points.size) {
        travelled += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    if (travelled > direct * HINT_FLICK_MAX_DETOUR) return false
    // Asked last: it is the one test that looks up a key.
    return !landsOnLetter(last.x, last.y)
}

/** No letter anywhere: the default for a caller that has no grid to ask. */
internal val NoLetter: (Float, Float) -> Boolean = { _, _ -> false }

/**
 * Whether ([x], [y]) is in the core of a letter key other than [cell] (#859):
 * the cell [letterCellAt] finds there, inset by [CORE_INSET] a side, as
 * [FlickGlideArbiter] draws its cores. The seams between keys belong to no
 * key, so a flick whose overshoot ends on one is still a flick. All in one
 * space, root space where [letterCellAt] answers from the key table.
 */
internal fun inAnotherLetterCore(
    x: Float,
    y: Float,
    cell: Rect,
    letterCellAt: (Float, Float) -> Rect?,
): Boolean {
    val letter = letterCellAt(x, y) ?: return false
    if (letter == cell) return false
    val insetX = letter.width * CORE_INSET
    val insetY = letter.height * CORE_INSET
    return x >= letter.left + insetX && x <= letter.right - insetX &&
        y >= letter.top + insetY && y <= letter.bottom - insetY
}

/** Degrees between the stroke and straight along its direction. */
private fun angleOffDown(dx: Float, dy: Float): Float =
    Math.toDegrees(atan2(abs(dx).toDouble(), dy.toDouble())).toFloat()

/** Half-width of the downward cone, in degrees. */
internal const val HINT_FLICK_CONE_DEGREES = 30f

/** How much longer than the straight line the stroke may wander. */
internal const val HINT_FLICK_MAX_DETOUR = 1.4f

/** The shortest stroke that counts, in key heights. */
internal const val HINT_FLICK_MIN_TRAVEL_HEIGHTS = 0.5f

/** The longest stroke that counts, in key heights. */
internal const val HINT_FLICK_MAX_TRAVEL_HEIGHTS = 2f

/** Down to up, in ms. A flick of one key height takes well under half this. */
internal const val HINT_FLICK_MAX_MS = 250L
