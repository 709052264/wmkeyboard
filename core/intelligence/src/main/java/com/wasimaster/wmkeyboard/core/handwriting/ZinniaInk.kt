package com.wasimaster.wmkeyboard.core.handwriting

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns panel ink into what zinnia reads: one character at a time, in the
 * 1000-unit square the Tegaki models were trained in.
 *
 * zinnia scores a single character and takes each point as a fraction of the
 * box it is given, so a character written small in one corner of a wide
 * canvas would read as a different shape. Each character is therefore cut
 * out of the line ([segment]) and scaled into the box by its own bounding
 * box ([fit]), the way the training characters sit in theirs: centred near
 * (500, 490) and about 820 units on their longer side (measured over all
 * three models' training sets).
 *
 * Pure geometry, no Android: ZinniaInkTest runs it on the JVM.
 */
internal object ZinniaInk {

    /** The side of the square the models' training characters were drawn in. */
    const val BOX = 1000

    private const val TARGET_SIDE = 820f
    private const val CENTRE_X = 500f
    private const val CENTRE_Y = 490f

    /**
     * The smallest a character is taken to be, as a share of the writing
     * area's height. Keeps a lone dot or dash (、 一) from being blown up to
     * the full box: written on its own it is still small.
     */
    private const val MIN_SIDE_OF_AREA = 0.3f

    /**
     * A stroke that starts this far right of everything written so far, as
     * a share of the character size, starts the next character. Wide enough
     * that the parts of 川, 八 or 什 stay together; a character written
     * after a gap narrower than this is read with the one before it, and the
     * recogniser's whole-ink reading is there for exactly that case.
     */
    private const val CHARACTER_GAP = 0.35f

    /** A bounding box in canvas pixels. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val side: Float get() = max(width, height)

        fun union(other: Box) = Box(
            minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom),
        )
    }

    /** One character's ink as zinnia takes it: x, y pairs, and where each stroke ends. */
    class Encoded(val points: IntArray, val strokeEnds: IntArray)

    fun boxOf(strokes: List<HwStroke>): Box? {
        var box: Box? = null
        for (stroke in strokes) {
            val b = boxOf(stroke) ?: continue
            box = box?.union(b) ?: b
        }
        return box
    }

    private fun boxOf(stroke: HwStroke): Box? {
        if (stroke.points.isEmpty()) return null
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in stroke.points) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return Box(l, t, r, b)
    }

    /**
     * Splits a line of writing into characters, left to right in writing
     * order: a stroke opens a new character when it starts clear of the
     * current one by [CHARACTER_GAP] of the size of what has been written.
     * A stroke that goes back over an earlier character (a dot added last)
     * joins that one. One group for ink written as a single character.
     */
    fun segment(strokes: List<HwStroke>, areaHeight: Float): List<List<HwStroke>> {
        val drawn = strokes.filter { it.points.isNotEmpty() }
        if (drawn.size <= 1) return listOf(drawn).filter { it.isNotEmpty() }
        val floor = areaHeight * MIN_SIDE_OF_AREA
        val groups = mutableListOf<MutableList<HwStroke>>()
        val boxes = mutableListOf<Box>()
        for (stroke in drawn) {
            val b = boxOf(stroke)!!
            val current = boxes.lastOrNull()
            if (current == null) {
                groups += mutableListOf(stroke)
                boxes += b
                continue
            }
            val size = max(max(current.height, b.height), floor)
            if (b.left > current.right + size * CHARACTER_GAP) {
                groups += mutableListOf(stroke)
                boxes += b
                continue
            }
            val middle = (b.left + b.right) / 2f
            val earlier = if (middle < current.left) {
                boxes.indices.lastOrNull { it < boxes.lastIndex && middle >= boxes[it].left && middle <= boxes[it].right }
            } else {
                null
            }
            val target = earlier ?: boxes.lastIndex
            groups[target] += stroke
            boxes[target] = boxes[target].union(b)
        }
        return groups
    }

    /**
     * Scales [strokes] into the [BOX] square around their own bounding box.
     * [minSide] is the smallest the character is taken to be, in canvas
     * pixels, so a short mark stays short.
     */
    fun fit(strokes: List<HwStroke>, minSide: Float): Encoded {
        val box = boxOf(strokes) ?: return Encoded(IntArray(0), IntArray(0))
        val side = max(box.side, max(minSide, 1f))
        val scale = TARGET_SIDE / side
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val count = strokes.sumOf { it.points.size }
        val points = IntArray(count * 2)
        val ends = IntArray(strokes.count { it.points.isNotEmpty() })
        var n = 0
        var s = 0
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            for (p in stroke.points) {
                points[2 * n] = ((p.x - cx) * scale + CENTRE_X).roundToInt().coerceIn(0, BOX)
                points[2 * n + 1] = ((p.y - cy) * scale + CENTRE_Y).roundToInt().coerceIn(0, BOX)
                n++
            }
            ends[s++] = n
        }
        return Encoded(points, ends)
    }

    /** [MIN_SIDE_OF_AREA] of the writing area, for [fit]. */
    fun minSideFor(areaHeight: Float): Float = areaHeight * MIN_SIDE_OF_AREA
}
