package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keys at the ends of a row with an outward arm, beside glide typing
 * (#789 point 1, as shipped: arms belong to keys, so the "edge zone" is the
 * row-end key's own arm; test coverage for #838 and #878). An outward flick
 * off the end of the row is the arm, and a glide that starts on the same key
 * and heads into the row is still a glide. The board is QWERTY in 100 × 150
 * px cells, with the row ends armed outward, plus a mirrored right-to-left row
 * whose ends are armed the same way.
 */
class EdgeKeyArmGlideTest {

    private val w = 100f
    private val h = 150f

    private fun cellOf(rows: List<String>, letter: Char): Rect {
        val row = rows.indexOfFirst { letter in it }
        val col = rows[row].indexOf(letter)
        return Rect(col * w, row * h, (col + 1) * w, (row + 1) * h)
    }

    private fun judge(rows: List<String>, key: Char, arms: Set<FlickDirection>, via: List<Pair<Float, Float>>): Any {
        val cell = cellOf(rows, key)
        val arbiter = FlickGlideArbiter(
            downX = cell.center.x,
            downY = cell.center.y,
            cell = cell,
            hasArm = { it in arms },
            reachPx = 1.41f * kotlin.math.hypot(w, h),
            slopPx = 60f,
            keyWidthPx = w,
            minWordReachPx = w * 0.65f,
            ontoLetterGlides = false,
            letterCellAt = { x, y ->
                val row = (y / h).toInt().takeIf { y >= 0f && it < rows.size }
                val col = row?.let { r -> (x / w).toInt().takeIf { x >= 0f && it < rows[r].length } }
                if (row == null || col == null) null else cellOf(rows, rows[row][col])
            },
        )
        val points = path(listOf(cell.center.x to cell.center.y) + via)
        if (points.dropLast(1).any { (x, y) -> arbiter.step(x, y) }) return "glide"
        val (x, y) = points.last()
        return arbiter.lift(x, y)
    }

    private fun path(via: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until via.size - 1) {
            val (x0, y0) = via[i]
            val (x1, y1) = via[i + 1]
            val steps = maxOf(1, (kotlin.math.hypot(x1 - x0, y1 - y0) / 10f).toInt())
            for (s in 1..steps) out.add(x0 + (x1 - x0) * s / steps to y0 + (y1 - y0) * s / steps)
        }
        return out
    }

    private fun centre(rows: List<String>, letter: Char) = cellOf(rows, letter).center.let { it.x to it.y }

    private fun check(rows: List<String>) {
        for (row in rows) {
            val first = row.first()
            val last = row.last()
            val left = setOf(FlickDirection.LEFT)
            val right = setOf(FlickDirection.RIGHT)
            val (fx, fy) = centre(rows, first)
            val (lx, ly) = centre(rows, last)
            // Outward off the end of the row: the arm.
            assertEquals("$first outward", FlickGlideVerdict.FLICK, judge(rows, first, left, listOf(fx - 80f to fy)))
            assertEquals("$last outward", FlickGlideVerdict.FLICK, judge(rows, last, right, listOf(lx + 80f to ly)))
            // Into the row through three more letters: a glide, arm or no arm.
            val inward = row[3]
            assertEquals("$first inward", "glide", judge(rows, first, left, listOf(centre(rows, inward))))
            assertEquals("$last inward", "glide", judge(rows, last, right, listOf(centre(rows, row[row.length - 4]))))
        }
    }

    @Test
    fun `an outward flick off a row end is the arm, a glide into the row still glides`() {
        check(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"))
    }

    @Test
    fun `the same on a right-to-left row`() {
        // Arabic's top row as drawn, right to left from ض; the left end is ج.
        check(listOf("ضصثقفغعهخحج".reversed()))
    }
}
