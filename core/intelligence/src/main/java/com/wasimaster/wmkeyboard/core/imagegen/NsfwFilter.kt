package com.wasimaster.wmkeyboard.core.imagegen

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * The picture half of the generator's safety filter: Falconsai's NSFW image
 * classifier (a ViT-Base at 224 × 224, Apache-2.0), converted to MNN with
 * 8-bit weights ([ImageGenModels.SAFETY]). Every generated picture passes
 * through it before the user sees it; one it calls unsafe is never shown.
 *
 * Its MNN output matches the original's probabilities to four decimals on the
 * test stickers (0.9994 / 0.0006 safe).
 */
internal class NsfwFilter(file: File) : Closeable {

    private val handle: Long = ImageGenNative.nativeLoad(file.absolutePath, arrayOf("image"), arrayOf("probs"), 2)
        .also { if (it == 0L) throw IOException("the picture filter did not load") }

    /** The classifier's probability that [bitmap] is not safe, 0..1. */
    fun unsafe(bitmap: Bitmap): Float {
        // Two halvings, then the last step: a single bilinear step from 512
        // to 224 skips pixels, where the reference (PIL) averages them.
        var b = bitmap
        while (b.width >= SIDE * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        val small = Bitmap.createScaledBitmap(b, SIDE, SIDE, true)
        val pixels = IntArray(SIDE * SIDE)
        small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        val plane = SIDE * SIDE
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            // ViTImageProcessor: /255, then (v - 0.5) / 0.5.
            chw[i] = ((p shr 16 and 0xff) / 255f - 0.5f) / 0.5f
            chw[plane + i] = ((p shr 8 and 0xff) / 255f - 0.5f) / 0.5f
            chw[2 * plane + i] = ((p and 0xff) / 255f - 0.5f) / 0.5f
        }
        val probs = ImageGenNative.nativeRun(handle, arrayOf(chw), arrayOf(intArrayOf(1, 3, SIDE, SIDE)), null)
            ?: throw IOException("the picture filter failed")
        return probs.getOrElse(1) { 1f }
    }

    override fun close() = ImageGenNative.nativeFree(handle)

    companion object {
        private const val SIDE = 224

        /** At or above this, a picture is held back. Falconsai's own cut-off is 0.5. */
        const val THRESHOLD = 0.4f
    }
}
