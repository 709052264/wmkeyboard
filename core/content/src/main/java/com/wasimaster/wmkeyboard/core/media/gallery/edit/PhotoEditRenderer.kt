package com.wasimaster.wmkeyboard.core.media.gallery.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a [PhotoEdit] onto a picture: the same code for the editor's preview
 * and for the copy that is sent, so what is seen is what goes.
 *
 * Three stages, so the editor can redraw only what changed: [base] is the
 * turned, straightened and recoloured picture; [withLayers] draws the
 * drawings, text and stickers onto a copy of it; [crop] cuts the window out.
 * Software canvases throughout, because the neon glow and the hiding brushes
 * use a mask filter and shaders a hardware canvas does not draw.
 */
object PhotoEditRenderer {

    /** Where a sticker layer's picture comes from: the editor's own cache. */
    fun interface StickerSource {
        fun bitmap(layer: StickerLayer): Bitmap?
    }

    /** Everything at once, for the sent copy. */
    fun render(source: Bitmap, edit: PhotoEdit, stickers: StickerSource): Bitmap {
        val base = base(source, edit)
        val layered = withLayers(base, edit, stickers)
        if (layered !== base) base.recycle()
        val cropped = crop(layered, edit.crop)
        if (cropped !== layered) layered.recycle()
        return cropped
    }

    /** The picture turned, mirrored, straightened and recoloured, before layers and crop. */
    fun base(source: Bitmap, edit: PhotoEdit): Bitmap {
        val odd = edit.quarterTurns % 2 == 1
        val tw = if (odd) source.height else source.width
        val th = if (odd) source.width else source.height
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cover = CropMath.coverScale(tw.toFloat(), th.toFloat(), edit.straighten)
        // Shown as straighten-of-mirror-of-turned: see PhotoEdit.turned.
        val m = Matrix().apply {
            postTranslate(-source.width / 2f, -source.height / 2f)
            postRotate(edit.quarterTurns * 90f)
            if (edit.mirrored) postScale(-1f, 1f)
            postRotate(edit.straighten)
            postScale(cover, cover)
            postTranslate(tw / 2f, th / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val colour = ColorMath.forEdit(edit)
        if (!colour.contentEquals(ColorMath.identity())) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(colour))
        canvas.drawBitmap(source, m, paint)

        val sharpen = edit.adjust.sharpen
        if (sharpen > 0f) sharpenInPlace(out, sharpen)
        val amount = edit.filterAmount.coerceIn(0f, 1f)
        val vignette = (edit.adjust.vignette + ColorMath.filterVignette(edit.filter) * amount).coerceIn(0f, 1f)
        if (vignette > 0f) drawVignette(canvas, tw, th, vignette)
        val grain = (edit.adjust.grain + ColorMath.filterGrain(edit.filter) * amount).coerceIn(0f, 1f)
        if (grain > 0f) drawGrain(canvas, tw, th, grain)
        return out
    }

    /**
     * [base] with the strokes, text and stickers drawn on, as a new bitmap
     * ([base] itself when there are none). [skip] leaves one text or sticker
     * out: the one a finger is dragging, which the editor draws on top live.
     */
    fun withLayers(base: Bitmap, edit: PhotoEdit, stickers: StickerSource, skip: Long? = null): Bitmap {
        if (edit.strokes.isEmpty() && edit.texts.isEmpty() && edit.stickers.isEmpty()) return base
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width
        val h = out.height
        val short = min(w, h).toFloat()
        if (edit.strokes.isNotEmpty()) {
            // Strokes go on a layer of their own so the eraser takes back
            // drawing, never the photo underneath it.
            val overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val oc = Canvas(overlay)
            val shaders = BrushShaders(base)
            for (stroke in edit.strokes) drawStroke(oc, stroke, w, h, short, shaders)
            shaders.release()
            canvas.drawBitmap(overlay, 0f, 0f, null)
            overlay.recycle()
        }
        for (s in edit.stickers) if (s.id != skip) drawSticker(canvas, s, w, h, short, stickers)
        for (t in edit.texts) if (t.id != skip) drawText(canvas, t, w, h, short)
        return out
    }

    fun crop(full: Bitmap, rect: CropRect): Bitmap {
        if (rect == CropRect.FULL) return full
        val l = (rect.left * full.width).roundToInt().coerceIn(0, full.width - 1)
        val t = (rect.top * full.height).roundToInt().coerceIn(0, full.height - 1)
        val r = (rect.right * full.width).roundToInt().coerceIn(l + 1, full.width)
        val b = (rect.bottom * full.height).roundToInt().coerceIn(t + 1, full.height)
        return Bitmap.createBitmap(full, l, t, r - l, b - t)
    }

    // ---- Strokes --------------------------------------------------------

    /**
     * The blurred and blocky copies the two hiding brushes paint with, made on
     * first use: a copy a few dozen pixels across, drawn back up to size by a
     * shader, so no second full-size picture is ever allocated.
     */
    private class BrushShaders(private val base: Bitmap) {
        private var blur: Bitmap? = null
        private var blocks: Bitmap? = null

        fun shader(kind: BrushKind): Shader? {
            val short = min(base.width, base.height).toFloat()
            val small = when (kind) {
                BrushKind.BLUR -> blur ?: shrink(short / BLUR_SIDE).also { blur = it }
                BrushKind.PIXELATE -> blocks ?: shrink(short / BLOCK_SIDE).also { blocks = it }
                else -> return null
            }
            return BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    Matrix().apply { setScale(base.width.toFloat() / small.width, base.height.toFloat() / small.height) },
                )
            }
        }

        private fun shrink(factor: Float): Bitmap {
            val f = max(1f, factor)
            return Bitmap.createScaledBitmap(
                base,
                (base.width / f).roundToInt().coerceAtLeast(2),
                (base.height / f).roundToInt().coerceAtLeast(2),
                true,
            )
        }

        fun release() {
            blur?.recycle()
            blocks?.recycle()
        }

        companion object {
            const val BLUR_SIDE = 40f
            const val BLOCK_SIDE = 28f
        }
    }

    /** A smooth path through [points] (midpoint quadratics), in pixels of a [w] by [h] picture. */
    fun pathOf(points: List<NPoint>, w: Int, h: Int): Path {
        val path = Path()
        if (points.isEmpty()) return path
        val first = points.first()
        path.moveTo(first.x * w, first.y * h)
        if (points.size == 1) {
            // A tap: a dot the width of the brush.
            path.lineTo(first.x * w + 0.1f, first.y * h)
            return path
        }
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            path.quadTo(p.x * w, p.y * h, (p.x + q.x) / 2f * w, (p.y + q.y) / 2f * h)
        }
        val last = points.last()
        path.lineTo(last.x * w, last.y * h)
        return path
    }

    private fun strokePaint(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = width
    }

    @Suppress("detekt.CyclomaticComplexMethod")
    private fun drawStroke(canvas: Canvas, stroke: Stroke, w: Int, h: Int, short: Float, shaders: BrushShaders) {
        val width = max(1f, stroke.width * short)
        val path = pathOf(stroke.points, w, h)
        when (stroke.brush) {
            BrushKind.PEN -> canvas.drawPath(path, strokePaint(width).apply { color = stroke.color })
            BrushKind.MARKER -> canvas.drawPath(
                path,
                strokePaint(width * MARKER_WIDTH).apply {
                    color = stroke.color
                    alpha = MARKER_ALPHA
                    strokeCap = Paint.Cap.SQUARE
                },
            )
            BrushKind.NEON -> {
                canvas.drawPath(
                    path,
                    strokePaint(width * 1.6f).apply {
                        color = stroke.color
                        maskFilter = BlurMaskFilter(width * 1.2f, BlurMaskFilter.Blur.NORMAL)
                    },
                )
                canvas.drawPath(path, strokePaint(width * 0.9f).apply { color = stroke.color })
                canvas.drawPath(
                    path,
                    strokePaint(width * 0.38f).apply {
                        color = Color.WHITE
                        alpha = 230
                    },
                )
            }
            BrushKind.ARROW -> {
                val paint = strokePaint(width).apply { color = stroke.color }
                canvas.drawPath(path, paint)
                arrowHead(stroke.points, w, h, width)?.let { canvas.drawPath(it, paint) }
            }
            BrushKind.ERASER -> canvas.drawPath(
                path,
                strokePaint(width * ERASER_WIDTH).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) },
            )
            BrushKind.BLUR, BrushKind.PIXELATE -> {
                val shader = shaders.shader(stroke.brush) ?: return
                canvas.drawPath(
                    path,
                    strokePaint(width * HIDE_WIDTH).apply {
                        this.shader = shader
                        isFilterBitmap = stroke.brush == BrushKind.BLUR
                        if (stroke.brush == BrushKind.PIXELATE) strokeCap = Paint.Cap.SQUARE
                    },
                )
            }
        }
    }

    /** The two lines of an arrow's head at the end of [points], or null for a stroke too short to point. */
    fun arrowHead(points: List<NPoint>, w: Int, h: Int, width: Float): Path? {
        if (points.size < 2) return null
        val tip = points.last()
        val tx = tip.x * w
        val ty = tip.y * h
        // Aim from a point some way back along the line, not the last
        // sample, which a lifting finger wobbles.
        val reach = width * 4f
        var from = points.first()
        for (i in points.indices.reversed()) {
            val p = points[i]
            if (hypot(p.x * w - tx, p.y * h - ty) >= reach) {
                from = p
                break
            }
        }
        val angle = atan2(ty - from.y * h, tx - from.x * w)
        val len = max(width * 3.2f, 18f)
        val spread = Math.toRadians(28.0)
        return Path().apply {
            moveTo((tx - len * cos(angle - spread)).toFloat(), (ty - len * sin(angle - spread)).toFloat())
            lineTo(tx, ty)
            lineTo((tx - len * cos(angle + spread)).toFloat(), (ty - len * sin(angle + spread)).toFloat())
        }
    }

    /** How much wider than the chosen size each brush draws, so one size slider suits all. */
    const val MARKER_WIDTH = 2.2f
    const val MARKER_ALPHA = 115
    const val ERASER_WIDTH = 2.4f
    const val HIDE_WIDTH = 3.5f

    // ---- Text -----------------------------------------------------------

    /** Dark on light, light on dark: what goes on or around a colour so it reads. */
    fun contrastOn(color: Int): Int {
        val l = 0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)
        return if (l > 150f) Color.BLACK else Color.WHITE
    }

    private fun textPaint(layer: TextLayer, short: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = max(8f, layer.size * short)
        typeface = if (layer.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        color = layer.color
    }

    private fun layoutOf(text: String, paint: TextPaint, maxWidth: Int, align: TextAlign): StaticLayout {
        val natural = (text.lines().maxOfOrNull { paint.measureText(it) } ?: 0f).toInt() + 2
        val width = min(maxWidth, natural).coerceAtLeast(1)
        val alignment = when (align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(alignment).build()
    }

    /**
     * The box a text layer fills, in pixels of a [w] by [h] picture, around its
     * centre and before its rotation: where a finger lands to pick it up.
     */
    fun textBox(layer: TextLayer, w: Int, h: Int): RectF {
        val short = min(w, h).toFloat()
        val paint = textPaint(layer, short)
        val layout = layoutOf(layer.text.ifEmpty { " " }, paint, (w * TEXT_MAX_WIDTH).toInt(), layer.align)
        val pad = boxPad(layer, paint)
        val bw = layout.width + pad * 2
        val bh = layout.height + pad * 2
        val cx = layer.center.x * w
        val cy = layer.center.y * h
        return RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
    }

    private fun boxPad(layer: TextLayer, paint: TextPaint): Float =
        if (layer.style == TextStyle.BOX || layer.style == TextStyle.GLASS) paint.textSize * 0.35f else paint.textSize * 0.1f

    fun drawText(canvas: Canvas, layer: TextLayer, w: Int, h: Int, short: Float = min(w, h).toFloat()) {
        if (layer.text.isBlank()) return
        val paint = textPaint(layer, short)
        val layout = layoutOf(layer.text, paint, (w * TEXT_MAX_WIDTH).toInt(), layer.align)
        val pad = boxPad(layer, paint)
        canvas.save()
        canvas.translate(layer.center.x * w, layer.center.y * h)
        canvas.rotate(layer.rotation)
        canvas.translate(-layout.width / 2f, -layout.height / 2f)
        when (layer.style) {
            TextStyle.PLAIN -> {
                paint.setShadowLayer(paint.textSize * 0.08f, 0f, paint.textSize * 0.04f, SHADOW)
                layout.draw(canvas)
            }
            TextStyle.OUTLINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = paint.textSize * 0.14f
                paint.color = contrastOn(layer.color)
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = layer.color
                layout.draw(canvas)
            }
            TextStyle.BOX, TextStyle.GLASS -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = layer.color
                    if (layer.style == TextStyle.GLASS) alpha = GLASS_ALPHA
                }
                val r = paint.textSize * 0.3f
                canvas.drawRoundRect(RectF(-pad, -pad, layout.width + pad, layout.height + pad), r, r, bg)
                paint.color = if (layer.style == TextStyle.GLASS) Color.WHITE else contrastOn(layer.color)
                layout.draw(canvas)
            }
        }
        canvas.restore()
    }

    /** Text wraps at this share of the picture's width. */
    const val TEXT_MAX_WIDTH = 0.9f
    private const val SHADOW = 0x99000000.toInt()
    private const val GLASS_ALPHA = 140

    // ---- Stickers -------------------------------------------------------

    /** A sticker layer's box in pixels, before rotation, for a sticker [aspect] wide over high (emoji are square). */
    fun stickerBox(layer: StickerLayer, w: Int, h: Int, aspect: Float = 1f): RectF {
        val short = min(w, h).toFloat()
        val sw = layer.size * short
        val sh = sw / aspect.coerceAtLeast(0.05f)
        val cx = layer.center.x * w
        val cy = layer.center.y * h
        return RectF(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f)
    }

    fun drawSticker(canvas: Canvas, layer: StickerLayer, w: Int, h: Int, short: Float, source: StickerSource) {
        val sw = layer.size * short
        canvas.save()
        canvas.translate(layer.center.x * w, layer.center.y * h)
        canvas.rotate(layer.rotation)
        val emoji = layer.emoji
        if (emoji != null) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sw * EMOJI_FILL
                textAlign = Paint.Align.CENTER
            }
            val fm = paint.fontMetrics
            canvas.drawText(emoji, 0f, -(fm.ascent + fm.descent) / 2f, paint)
        } else {
            source.bitmap(layer)?.let { bmp ->
                val sh = sw * bmp.height / bmp.width.coerceAtLeast(1)
                canvas.drawBitmap(
                    bmp,
                    null,
                    RectF(-sw / 2f, -sh / 2f, sw / 2f, sh / 2f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
        }
        canvas.restore()
    }

    /** An emoji glyph is drawn a little smaller than its box, which holds the font's side bearings too. */
    const val EMOJI_FILL = 0.86f

    // ---- Passes ---------------------------------------------------------

    private fun drawVignette(canvas: Canvas, w: Int, h: Int, amount: Float) {
        val radius = hypot(w.toFloat(), h.toFloat()) / 2f
        val edge = (amount * VIGNETTE_MAX_ALPHA).roundToInt().coerceIn(0, 255)
        val paint = Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(edge, 0, 0, 0)),
                floatArrayOf(0f, 0.55f - amount * 0.2f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    private const val VIGNETTE_MAX_ALPHA = 235

    private fun drawGrain(canvas: Canvas, w: Int, h: Int, amount: Float) {
        val shader = BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // The same grain, relative to the picture, on a preview and on the sent copy.
        val scale = max(1f, min(w, h) / GRAIN_REFERENCE)
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        val paint = Paint().apply {
            this.shader = shader
            alpha = (amount * GRAIN_MAX_ALPHA).roundToInt().coerceIn(0, 255)
            isFilterBitmap = false
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    private const val GRAIN_REFERENCE = 900f
    private const val GRAIN_MAX_ALPHA = 120

    /** A fixed field of noise, grey speckles on clear, the same every time so a preview matches its copy. */
    private val grainTile: Bitmap by lazy {
        val size = GRAIN_TILE
        val rnd = java.util.Random(GRAIN_SEED)
        val px = IntArray(size * size) {
            val v = rnd.nextInt(256)
            val a = (kotlin.math.abs(v - 128) * 2).coerceAtMost(255)
            Color.argb(a, v, v, v)
        }
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private const val GRAIN_TILE = 128
    private const val GRAIN_SEED = 708L

    /**
     * A 3x3 sharpen, in bands of rows so a large picture is never copied into
     * one array whole. Each band reads one row either side of what it writes,
     * and the row above comes from the previous band's saved copy rather than
     * the bitmap that band has already rewritten. Each band is one call into
     * the native kernel (NEON on arm64), or [sharpenRows] without it.
     */
    fun sharpenInPlace(bitmap: Bitmap, amount: Float) {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return
        val a = amount * SHARPEN_STRENGTH
        var prevRow = IntArray(w)
        var y0 = 0
        while (y0 < h) {
            val y1 = min(h, y0 + SHARPEN_BAND)
            val readTop = max(0, y0 - 1)
            val readBottom = min(h, y1 + 1)
            val rows = readBottom - readTop
            val src = IntArray(w * rows)
            bitmap.getPixels(src, 0, w, 0, readTop, w, rows)
            if (y0 > 0) System.arraycopy(prevRow, 0, src, 0, w)
            val lastSrc = IntArray(w)
            System.arraycopy(src, (y1 - 1 - readTop) * w, lastSrc, 0, w)
            val out = IntArray(w * (y1 - y0))
            val first = y0 - readTop
            if (!NativeEngine.sharpen(src, w, first, y1 - y0, a, out)) sharpenRows(src, w, first, y1 - y0, a, out)
            bitmap.setPixels(out, 0, w, 0, y0, w, y1 - y0)
            prevRow = lastSrc
            y0 = y1
        }
    }

    /**
     * Rows [first] until [first] + [count] of [src] ([w] pixels a row)
     * sharpened into [out]. A pixel's neighbours are clamped to [src]: the
     * row above its first row is that row itself, and so on, so a band
     * passes one row of the picture either side of the rows it writes. The
     * Kotlin twin of [NativeEngine.sharpen], which must match it bit for bit.
     */
    fun sharpenRows(src: IntArray, w: Int, first: Int, count: Int, a: Float, out: IntArray) {
        val rows = src.size / w
        for (sy in first until first + count) {
            val up = if (sy == 0) sy else sy - 1
            val down = if (sy == rows - 1) sy else sy + 1
            for (x in 0 until w) {
                val c = src[sy * w + x]
                val l = src[sy * w + max(0, x - 1)]
                val r = src[sy * w + min(w - 1, x + 1)]
                val u = src[up * w + x]
                val d = src[down * w + x]
                out[(sy - first) * w + x] = Color.argb(
                    Color.alpha(c),
                    sharpenChannel(Color.red(c), Color.red(l), Color.red(r), Color.red(u), Color.red(d), a),
                    sharpenChannel(Color.green(c), Color.green(l), Color.green(r), Color.green(u), Color.green(d), a),
                    sharpenChannel(Color.blue(c), Color.blue(l), Color.blue(r), Color.blue(u), Color.blue(d), a),
                )
            }
        }
    }

    /** One channel of the sharpen: the centre pushed away from its four neighbours' mean. */
    fun sharpenChannel(c: Int, l: Int, r: Int, u: Int, d: Int, a: Float): Int =
        (c + a * (4 * c - l - r - u - d) / 4f).roundToInt().coerceIn(0, 255)

    private const val SHARPEN_STRENGTH = 1.6f
    private const val SHARPEN_BAND = 256
}
