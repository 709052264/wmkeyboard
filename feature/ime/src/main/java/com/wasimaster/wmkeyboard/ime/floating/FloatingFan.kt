package com.wasimaster.wmkeyboard.ime.floating

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where the quick menu's [count] slots go round a button centred on [anchor]
 * (#755), on a screen [width] by [height], all in pixels.
 *
 * The slots fan out on an arc that faces the middle of the screen, so a button
 * resting on the right edge opens to the left, one in a top corner opens down
 * and inwards. Neighbours sit at least [spacing] apart along the arc: when
 * half a circle at [radius] is too short for that, the arc grows rather than
 * crowding. Each slot is then kept [margin] inside the screen, which near a
 * corner can only bend the arc, never push a slot off it.
 */
fun floatingFan(
    count: Int,
    anchor: Offset,
    width: Float,
    height: Float,
    radius: Float,
    spacing: Float,
    margin: Float,
): List<Offset> {
    if (count <= 0) return emptyList()
    val facing = atan2(height / 2f - anchor.y, width / 2f - anchor.x)
    // Half a circle holds count - 1 gaps of `spacing` once the radius allows it.
    val ring = max(radius, if (count > 1) spacing * (count - 1) / PI.toFloat() else radius)
    val step = if (count > 1) min(spacing / ring, PI.toFloat() / (count - 1)) else 0f
    val first = facing - step * (count - 1) / 2f
    return List(count) { i ->
        val angle = first + step * i
        Offset(
            (anchor.x + ring * cos(angle)).coerceIn(margin, max(margin, width - margin)),
            (anchor.y + ring * sin(angle)).coerceIn(margin, max(margin, height - margin)),
        )
    }
}

/** The slot within [reach] of [point], nearest first, or null. */
fun floatingSlotAt(point: Offset, centers: List<Offset>, reach: Float): Int? {
    var best: Int? = null
    var bestDistance = reach * reach
    centers.forEachIndexed { i, c ->
        val d = (c - point).getDistanceSquared()
        if (d <= bestDistance) {
            best = i
            bestDistance = d
        }
    }
    return best
}
