package com.wasimaster.wmkeyboard.core.media.gallery.edit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo editor's model (#708): geometry, colour maths and the undo history. */
class PhotoEditTest {

    private val eps = 1e-4f

    private fun assertRect(expected: CropRect, actual: CropRect) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }

    @Test
    fun `four turns bring everything back where it was`() {
        val edit = PhotoEdit(
            crop = CropRect(0.1f, 0.2f, 0.6f, 0.9f),
            texts = listOf(TextLayer(1, "hi", 0, center = NPoint(0.2f, 0.3f))),
            strokes = listOf(Stroke(BrushKind.PEN, 0, 0.01f, listOf(NPoint(0.1f, 0.9f)))),
        )
        val back = edit.turned().turned().turned().turned()
        assertRect(edit.crop, back.crop)
        assertEquals(0, back.quarterTurns)
        assertEquals(edit.texts.single().center.x, back.texts.single().center.x, eps)
        assertEquals(edit.strokes.single().points.single().y, back.strokes.single().points.single().y, eps)
        assertEquals(360f, back.texts.single().rotation, eps)
    }

    @Test
    fun `a turn moves the crop window with the picture`() {
        // The top-left quarter of the picture is the top-right quarter once turned clockwise.
        assertRect(CropRect(0.5f, 0f, 1f, 0.5f), CropRect(0f, 0f, 0.5f, 0.5f).turnedClockwise())
    }

    @Test
    fun `mirroring twice is no change, and it flips the tilt`() {
        val edit = PhotoEdit(crop = CropRect(0.1f, 0f, 0.4f, 1f), straighten = 12f)
        val once = edit.mirroredOnce()
        assertTrue(once.mirrored)
        assertEquals(-12f, once.straighten, eps)
        assertRect(CropRect(0.6f, 0f, 0.9f, 1f), once.crop)
        // The crop comes back within float rounding (1 - (1 - 0.1f) is not 0.1f).
        val twice = once.mirroredOnce()
        assertRect(edit.crop, twice.crop)
        assertEquals(edit, twice.copy(crop = edit.crop))
    }

    @Test
    fun `a turn under the mirror turns the other way underneath`() {
        val turned = PhotoEdit(mirrored = true).turned()
        assertEquals(3, turned.quarterTurns)
    }

    @Test
    fun `fitting a shape gives the biggest window of that shape, centred`() {
        // A square crop in a 4:3 picture: full height, three quarters of the width.
        val square = CropMath.fit(1f, 4f / 3f)
        assertEquals(0.75f, square.width, eps)
        assertEquals(1f, square.height, eps)
        assertEquals(0.125f, square.left, eps)
    }

    @Test
    fun `dragging a corner never leaves the picture or goes below the smallest side`() {
        val r = CropMath.drag(CropRect.FULL, CropHandle.TOP_LEFT, -0.5f, -0.5f, null, 1f)
        assertRect(CropRect.FULL, r)
        val tiny = CropMath.drag(CropRect.FULL, CropHandle.BOTTOM_RIGHT, -2f, -2f, null, 1f)
        assertEquals(CropMath.MIN_SIDE, tiny.width, eps)
        assertEquals(CropMath.MIN_SIDE, tiny.height, eps)
    }

    @Test
    fun `dragging with a fixed shape keeps the shape`() {
        val start = CropMath.fit(1f, 1f)
        val r = CropMath.drag(start, CropHandle.BOTTOM_RIGHT, -0.3f, -0.1f, 1f, 1f)
        assertEquals(r.width, r.height, eps)
        assertEquals(0f, r.left, eps)
        assertEquals(0f, r.top, eps)
    }

    @Test
    fun `moving the window stops at the picture's edge`() {
        val r = CropMath.drag(CropRect(0.2f, 0.2f, 0.6f, 0.6f), CropHandle.MOVE, 0.9f, -0.9f, null, 1f)
        assertRect(CropRect(0.6f, 0f, 1f, 0.4f), r)
    }

    @Test
    fun `straightening zooms just enough to fill the corners`() {
        assertEquals(1f, CropMath.coverScale(100f, 100f, 0f), eps)
        // A square turned 45 degrees needs root two to cover itself.
        assertEquals(Math.sqrt(2.0).toFloat(), CropMath.coverScale(100f, 100f, 45f), 1e-3f)
        assertTrue(CropMath.coverScale(400f, 300f, 10f) > 1f)
    }

    @Test
    fun `output size follows the turn and the crop`() {
        assertEquals(3000 to 4000, CropMath.outputSize(4000, 3000, PhotoEdit(quarterTurns = 1)))
        assertEquals(2000 to 1500, CropMath.outputSize(4000, 3000, PhotoEdit(crop = CropRect(0f, 0f, 0.5f, 0.5f))))
    }

    @Test
    fun `the default edit leaves colours exactly as they are`() {
        assertArrayEquals(ColorMath.identity(), ColorMath.forEdit(PhotoEdit.NONE), eps)
        assertArrayEquals(ColorMath.identity(), ColorMath.adjust(Adjust()), eps)
    }

    @Test
    fun `a filter at no strength is the original`() {
        PhotoFilter.entries.forEach {
            assertArrayEquals(it.name, ColorMath.identity(), ColorMath.forEdit(PhotoEdit(filter = it, filterAmount = 0f)), eps)
        }
    }

    @Test
    fun `mono greys every colour and brightness lifts it`() {
        val (r, g, b) = ColorMath.apply(ColorMath.filter(PhotoFilter.MONO), 200f, 40f, 90f)
        assertEquals(r, g, 0.5f)
        assertEquals(g, b, 0.5f)
        val (lr, _, _) = ColorMath.apply(ColorMath.adjust(Adjust(brightness = 0.5f)), 100f, 100f, 100f)
        assertTrue(lr > 100f)
        val (wr, _, wb) = ColorMath.apply(ColorMath.adjust(Adjust(warmth = 1f)), 100f, 100f, 100f)
        assertTrue(wr > wb)
    }

    @Test
    fun `concat applies the right-hand matrix first`() {
        val double = ColorMath.scale(2f, 2f, 2f)
        val plus = ColorMath.offset(10f, 10f, 10f)
        // (x * 2) + 10, not (x + 10) * 2.
        assertEquals(30f, ColorMath.apply(ColorMath.concat(plus, double), 10f, 10f, 10f).first, eps)
        assertEquals(40f, ColorMath.apply(ColorMath.concat(double, plus), 10f, 10f, 10f).first, eps)
    }

    @Test
    fun `slider values are held to their range`() {
        assertEquals(1f, Adjust().with(AdjustKnob.CONTRAST, 3f).contrast, eps)
        assertEquals(-1f, Adjust().with(AdjustKnob.CONTRAST, -3f).contrast, eps)
        assertEquals(0f, Adjust().with(AdjustKnob.VIGNETTE, -0.5f).vignette, eps)
        AdjustKnob.entries.forEach { k -> assertEquals(0.4f, Adjust().with(k, 0.4f).get(k), eps) }
    }

    @Test
    fun `a drag is one undo step however many frames it took`() {
        var h = EditHistory()
        h = h.preview(PhotoEdit(straighten = 1f))
        h = h.preview(PhotoEdit(straighten = 2f))
        h = h.push(PhotoEdit(straighten = 3f))
        assertEquals(3f, h.current.straighten, eps)
        assertEquals(1, h.undo.size)
        h = h.undone()
        assertEquals(PhotoEdit.NONE, h.current)
        assertTrue(h.canRedo)
        h = h.redone()
        assertEquals(3f, h.current.straighten, eps)
    }

    @Test
    fun `a new step clears what could be redone, and a no-op adds no step`() {
        var h = EditHistory().push(PhotoEdit(quarterTurns = 1)).undone()
        assertTrue(h.canRedo)
        h = h.push(PhotoEdit(mirrored = true))
        assertFalse(h.canRedo)
        val same = h.push(h.current)
        assertEquals(h.undo.size, same.undo.size)
    }

    @Test
    fun `a turn swaps a fixed shape for its sideways twin`() {
        assertEquals(CropAspect.R3_4, CropAspect.R4_3.turned())
        assertEquals(CropAspect.SQUARE, CropAspect.SQUARE.turned())
        assertEquals(16f / 9f, CropAspect.R16_9.ratio(1f)!!, eps)
        assertEquals(1.5f, CropAspect.ORIGINAL.ratio(1.5f)!!, eps)
        assertEquals(null, CropAspect.FREE.ratio(1f))
    }
}
