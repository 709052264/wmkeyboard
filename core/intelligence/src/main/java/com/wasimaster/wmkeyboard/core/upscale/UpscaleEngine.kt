package com.wasimaster.wmkeyboard.core.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenEngine
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenNative
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** What the upscaler is asked to do. Plain values, so it crosses processes in a Bundle. */
data class UpscaleRequest(
    /** A picture file any decoder reads. */
    val input: String,
    /** Where the result goes: PNG when the picture has transparency, JPEG when it has none. */
    val output: String,
    val kind: UpscaleKind,
    /** 2 or 4. */
    val factor: Int,
)

/**
 * The upscaler itself, in the sticker generator's process
 * ([com.wasimaster.wmkeyboard.core.imagegen.ImageGenService]): one network
 * loaded at a time on MNN's CPU runtime, run a tile at a time so neither the
 * picture's float copy nor the network's working memory ever covers the whole
 * picture. ×4 is two ×2 passes. A cancel lands between two tiles.
 *
 * Transparency goes through the network too, as a grey picture of its own
 * after the colour: measured on stickers at ×4, an alpha channel enlarged by
 * plain filtering instead leaves the outline soft (28.6 dB against 36.7 for
 * the same network). The colour under fully transparent pixels is filled in from the
 * visible edge first (see [UpscaleTiles.bleed]), or the network would draw
 * whatever the encoder left there into the sticker's outline.
 */
class UpscaleEngine internal constructor(private val context: Context) : Closeable {

    private var handle = 0L
    private var loadedId: String? = null

    /**
     * Enlarges [request]'s picture into its output file. [progress] gets
     * (tiles done, tiles in all) after each tile. False when it was cancelled.
     */
    internal fun run(request: UpscaleRequest, cancelled: AtomicBoolean, progress: (Int, Int) -> Unit): Boolean {
        val passes = when (request.factor) {
            2 -> 1
            4 -> 2
            else -> throw IOException("×${request.factor} is not a factor the upscaler offers")
        }
        val source = BitmapFactory.decodeFile(request.input, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("the picture would not decode")
        var width = source.width
        var height = source.height
        var colour = IntArray(width * height)
        source.getPixels(colour, 0, width, 0, 0, width, height)
        source.recycle()
        var alpha = if (UpscaleTiles.hasAlpha(colour)) UpscaleTiles.alphaAsGrey(colour) else null
        if (alpha != null) UpscaleTiles.bleed(colour, width, height)

        val net = load(UpscaleModels.Model.of(request.kind))
        val total = (0 until passes).sumOf { p ->
            val w = width shl p
            val h = height shl p
            UpscaleTiles.tiles(w, h, UpscaleTiles.side(w, h)).size
        } * (if (alpha != null) 2 else 1)
        var done = 0
        val step = { if (!cancelled.get()) progress(++done, total) }

        // Every pass but the last into an array twice the size; the last one
        // straight into the result, alpha first so the colour can carry it.
        repeat(passes - 1) {
            alpha = alpha?.let { pass(net, it, width, height, cancelled, step) ?: return false }
            colour = pass(net, colour, width, height, cancelled, step) ?: return false
            width *= UpscaleModels.SCALE
            height *= UpscaleModels.SCALE
        }
        val bigAlpha = alpha?.let { pass(net, it, width, height, cancelled, step) ?: return false }
        alpha = null
        val outWidth = width * UpscaleModels.SCALE
        val out = Bitmap.createBitmap(outWidth, height * UpscaleModels.SCALE, Bitmap.Config.ARGB_8888)
        try {
            val finished = tiles(net, colour, width, height, cancelled) { x, y, w, h, rgb ->
                if (bigAlpha != null && !NativeEngine.mergeAlpha(rgb, w, h, bigAlpha, outWidth, y * outWidth + x)) {
                    for (row in 0 until h) {
                        val from = (y + row) * outWidth + x
                        for (col in 0 until w) {
                            val i = row * w + col
                            rgb[i] = (UpscaleTiles.greyToAlpha(bigAlpha[from + col]) shl 24) or (rgb[i] and RGB_MASK)
                        }
                    }
                }
                out.setPixels(rgb, 0, w, x, y, w, h)
                step()
            }
            if (!finished) return false
            write(out, File(request.output), bigAlpha != null)
            return true
        } finally {
            out.recycle()
        }
    }

    /** One ×2 pass of [pixels] into a new array, or null when cancelled. */
    private fun pass(
        net: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
        cancelled: AtomicBoolean,
        step: () -> Unit,
    ): IntArray? {
        val outWidth = width * UpscaleModels.SCALE
        val out = IntArray(outWidth * height * UpscaleModels.SCALE)
        val finished = tiles(net, pixels, width, height, cancelled) { x, y, w, h, rgb ->
            for (row in 0 until h) System.arraycopy(rgb, row * w, out, (y + row) * outWidth + x, w)
            step()
        }
        return if (finished) out else null
    }

    /** [UpscaleTiles.pass] on the loaded network, its answer checked for shape. */
    private fun tiles(
        net: Long,
        pixels: IntArray,
        width: Int,
        height: Int,
        cancelled: AtomicBoolean,
        put: (Int, Int, Int, Int, IntArray) -> Unit,
    ): Boolean {
        val outShape = IntArray(4)
        return UpscaleTiles.pass(pixels, width, height, network = { input, inSide ->
            val outSide = (inSide - 2 * UpscaleModels.CONTEXT) * UpscaleModels.SCALE
            val result = ImageGenNative.nativeRun(net, arrayOf(input), arrayOf(intArrayOf(1, 3, inSide, inSide)), outShape)
                ?: throw IOException("MNN refused the tile")
            if (outShape[1] != 3 || outShape[2] != outSide || outShape[3] != outSide) {
                throw IOException("the network answered ${outShape.joinToString("×")} for a $inSide tile")
            }
            result
        }, cancelled = { cancelled.get() }, put = put)
    }

    private fun write(bitmap: Bitmap, target: File, alpha: Boolean) {
        target.parentFile?.mkdirs()
        val staged = File(target.path + ".part")
        staged.outputStream().use { stream ->
            val format = if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            if (!bitmap.compress(format, if (alpha) PNG_QUALITY else JPEG_QUALITY, stream)) {
                throw IOException("the result would not encode")
            }
        }
        target.delete()
        if (!staged.renameTo(target)) throw IOException("could not move the result into place")
    }

    private fun load(model: UpscaleModels.Model): Long {
        if (loadedId == model.id && handle != 0L) return handle
        unload()
        if (!ImageGenNative.available) throw IOException("the MNN runtime is not here")
        val noBackup = context.noBackupFilesDir
        if (!UpscaleModels.isDownloaded(noBackup, model)) throw IOException("${model.id} is not downloaded")
        val file = UpscaleModels.file(noBackup, model)
        val h = ImageGenNative.nativeLoad(
            file.path,
            arrayOf(UpscaleModels.INPUT),
            arrayOf(UpscaleModels.OUTPUT),
            ImageGenEngine.threads(),
        )
        if (h == 0L) throw IOException("${file.name} would not load")
        handle = h
        loadedId = model.id
        return h
    }

    private fun unload() {
        if (handle != 0L) ImageGenNative.nativeFree(handle)
        handle = 0L
        loadedId = null
    }

    override fun close() = unload()

    private companion object {
        const val RGB_MASK = 0x00FFFFFF
        const val PNG_QUALITY = 100
        const val JPEG_QUALITY = 95
    }
}
