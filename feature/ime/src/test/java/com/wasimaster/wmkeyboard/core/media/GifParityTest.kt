package com.wasimaster.wmkeyboard.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.wasimaster.wmkeyboard.core.media.reference.KotlinGifEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Rust GIF encoder behind [GifEncoder] against the Kotlin one it
 * replaced ([KotlinGifEncoder]), whole files compared byte for byte.
 *
 * The frames: animated WebPs decoded on real pixels (Robolectric's native
 * graphics), the small fixture [AnimatedWebpToGifTest] reads and a lossy
 * 512 px sticker whose compression noise spreads its colours the way a real
 * one does; sticker-like frames drawn here, smooth and flat; and frames made
 * to reach every edge the encoder has: one pixel, a single row or column,
 * nothing drawn, alpha either side of half, one to 300 colours (each palette
 * size and code width), noise that fills the code table many times over, and
 * delays past both clamps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GifParityTest {

    private class Clip(val width: Int, val height: Int, val frames: List<Pair<IntArray, Int>>)

    private fun assertSameFile(name: String, clip: Clip) {
        val kotlin = ByteArrayOutputStream()
        val native = ByteArrayOutputStream()
        KotlinGifEncoder(kotlin, clip.width, clip.height).apply { clip.frames.forEach { (px, ms) -> addFrame(px, ms) } }.finish()
        GifEncoder(native, clip.width, clip.height).apply { clip.frames.forEach { (px, ms) -> addFrame(px, ms) } }.finish()
        val expected = kotlin.toByteArray()
        val actual = native.toByteArray()
        val first = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] }
        assertEquals("$name: sizes (first difference at byte $first)", expected.size, actual.size)
        assertArrayEquals(name, expected, actual)
    }

    @Test
    fun `animated WebPs encode to the same bytes`() {
        assertSameFile("24 px fixture", webpFrames(fixture))

        // A sticker sent at its own size: lossy WebP frames with alpha.
        val writer = AnimatedWebpWriter(512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (phase in 0 until 6) {
            bitmap.setPixels(stickerFrame(512, phase * 5), 0, 512, 0, 0, 512, 512)
            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            bitmap.compress(Bitmap.CompressFormat.WEBP, 75, out)
            check(writer.addFrame(out.toByteArray(), 40 + phase * 10))
        }
        val clip = webpFrames(writer.build())
        assertEquals(6, clip.frames.size)
        assertSameFile("512 px lossy sticker", clip)
    }

    @Test
    fun `sticker-like frames encode to the same bytes`() {
        assertSameFile("smooth sticker, 30 frames", Clip(512, 512, List(30) { stickerFrame(512, it) to 40 }))
        assertSameFile("flat cartoon, 8 frames", Clip(320, 240, List(8) { flatFrame(320, 240, it) to 70 }))
    }

    @Test
    fun `edge cases encode to the same bytes`() {
        val random = Random(7)
        val opaque = { random.nextInt() or (0xFF shl 24) }
        assertSameFile("one pixel", Clip(1, 1, listOf(intArrayOf(0xFF102030.toInt()) to 10, intArrayOf(0) to 30)))
        assertSameFile("nothing drawn", Clip(9, 4, listOf(IntArray(36) to 0, IntArray(36) { 0x7FFFFFFF } to 50)))
        assertSameFile("a row", Clip(301, 1, listOf(IntArray(301) { opaque() } to 25)))
        assertSameFile("a column", Clip(1, 301, listOf(IntArray(301) { opaque() } to 25)))
        assertSameFile(
            "alpha either side of half",
            Clip(17, 13, listOf(IntArray(17 * 13) { (if (it % 3 == 0) 0x7F else 0x80) shl 24 or (random.nextInt() and 0xFFFFFF) } to 40)),
        )
        for (colours in listOf(1, 2, 3, 4, 5, 8, 9, 16, 17, 33, 64, 65, 128, 129, 254, 255, 256, 300)) {
            val palette = IntArray(colours) { opaque() }
            val px = IntArray(37 * 23) { if (random.nextInt(11) == 0) 0 else palette[random.nextInt(colours)] }
            assertSameFile("$colours colours", Clip(37, 23, listOf(px to 40)))
        }
        // Noise over all 2^15 buckets: the code table fills and clears many times.
        assertSameFile("noise", Clip(640, 480, List(2) { IntArray(640 * 480) { opaque() } to 40 }))
        // Runs and noise in bands, so the table fills with long strings too.
        assertSameFile(
            "bands",
            Clip(256, 256, listOf(IntArray(256 * 256) { if ((it / 700) % 2 == 0) 0xFF00FF00.toInt() else opaque() } to 40)),
        )
        val delays = listOf(-5, 0, 14, 15, 19, 20, 24, 25, 26, 655_349, 655_350, 655_351, Int.MAX_VALUE)
        assertSameFile("delays", Clip(4, 4, delays.map { ms -> IntArray(16) { opaque() } to ms }))
        assertSameFile("no frames", Clip(5, 5, emptyList()))
    }

    /** Every frame of an animated WebP, drawn the way a player would, as [AnimatedWebpToGif] does. */
    private fun webpFrames(webp: ByteArray): Clip {
        val animation = checkNotNull(AnimatedWebpReader.read(webp))
        val canvasBitmap = Bitmap.createBitmap(animation.width, animation.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        var toClear: Rect? = null
        val frames = animation.frames.map { frame ->
            toClear?.let { canvas.drawRect(it, clear) }
            val still = checkNotNull(BitmapFactory.decodeByteArray(frame.still, 0, frame.still.size))
            val at = Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
            canvas.drawBitmap(still, null, at, if (frame.blend) null else replace)
            toClear = at.takeIf { frame.disposeToBackground }
            val pixels = IntArray(animation.width * animation.height)
            canvasBitmap.getPixels(pixels, 0, animation.width, 0, 0, animation.width, animation.height)
            pixels to frame.durationMs
        }
        return Clip(animation.width, animation.height, frames)
    }

    /** The Phase 0 benchmark's sticker: a soft-edged blob on transparent, shaded to fill the palette. */
    private fun stickerFrame(side: Int, phase: Int): IntArray {
        val px = IntArray(side * side)
        val c = side / 2f
        val radius = side * 0.42f
        val shift = phase * 0.21f
        for (y in 0 until side) for (x in 0 until side) {
            val dx = x - c - 20f * sin(shift)
            val dy = y - c - 20f * cos(shift)
            val d = sqrt(dx * dx + dy * dy)
            val a = ((radius - d) * 0.25f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val rr = (128 + 100 * sin(x * 0.031f + shift)).toInt().coerceIn(0, 255)
            val gg = (128 + 100 * sin(y * 0.027f - shift)).toInt().coerceIn(0, 255)
            val bb = (128 + 90 * sin((x + y) * 0.019f + 2 * shift)).toInt().coerceIn(0, 255)
            px[y * side + x] = ((a * 255).toInt() shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return px
    }

    /** A cartoon: flat fills in a few colours inside a dark outline, moving with [phase]. */
    private fun flatFrame(width: Int, height: Int, phase: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width - width / 2 + phase * 3
        val y = i / width - height / 2
        val d = x * x + y * y
        when {
            d > 110 * 110 -> 0
            d > 104 * 104 -> 0xFF101010.toInt()
            (x / 20 + y / 20) % 3 == 0 -> 0xFFE04030.toInt()
            y > 30 -> 0xFF3080E0.toInt()
            else -> 0xFFF0D020.toInt()
        }
    }

    private val fixture = Base64.getDecoder().decode(
        "UklGRsYAAABXRUJQVlA4WAoAAAASAAAAFwAAFwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAABcAABcAAEYA" +
            "AAJWUDhMDwAAAC8XwAUABxD9j/4HIqL/AQBBTk1GKAAAAAIAAAIAAAcAAAcAAFoAAABWUDhMDwAAAC8HwAEAB9D/" +
            "iP4HIqL/AQBBTk1GOgAAAAAAAAAAABcAABcAAJYAAAJWUDhMIgAAAC8XwAUQDxAx//MfDIJsmxrc3/IUzxEi+j8O" +
            "AAAAAKA5zRo=",
    )
}
