package com.wasimaster.wmkeyboard.core.media.gallery.edit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Everything done to one photo in the gallery tool's editor (#708), as data.
 *
 * Nothing is applied to pixels until [PhotoEditRenderer] draws it, so every
 * step can be undone, a slider can be dragged back, and the same edit renders
 * a small preview and the full-size copy that is sent.
 *
 * Coordinates are fractions (0 to 1) of the **turned** picture: after the
 * quarter turns, the mirror and the straightening, before the crop. A crop is
 * a window onto that picture, so changing it never moves a drawing or a
 * sticker off what it was put on.
 */
data class PhotoEdit(
    val crop: CropRect = CropRect.FULL,
    val aspect: CropAspect = CropAspect.FREE,
    /** Clockwise quarter turns, 0 to 3. */
    val quarterTurns: Int = 0,
    val mirrored: Boolean = false,
    /** Fine rotation in degrees, [-MAX_STRAIGHTEN, MAX_STRAIGHTEN]. The picture zooms to keep its corners filled. */
    val straighten: Float = 0f,
    val filter: PhotoFilter = PhotoFilter.ORIGINAL,
    /** How strongly [filter] applies, 0 to 1. */
    val filterAmount: Float = 1f,
    val adjust: Adjust = Adjust(),
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    val stickers: List<StickerLayer> = emptyList(),
    /**
     * The photo enlarged by the upscaler before anything else is drawn on it,
     * or null for the photo as it is. Every other field is in fractions of the
     * picture, so none of them changes when this does.
     */
    val upscale: PhotoUpscale? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    /** Whether the picture's shape or framing changed: anything the crop tool touches. */
    val hasGeometry: Boolean
        get() = crop != CropRect.FULL || quarterTurns != 0 || mirrored || straighten != 0f

    /**
     * Turns the picture a quarter clockwise on screen, carrying the crop and
     * every layer with it. The picture is shown as mirror-of-turned, so with
     * the mirror on a clockwise turn on screen is an anticlockwise one under it.
     */
    fun turned(): PhotoEdit = copy(
        quarterTurns = if (mirrored) (quarterTurns + 3) % 4 else (quarterTurns + 1) % 4,
        crop = crop.turnedClockwise(),
        aspect = aspect.turned(),
        strokes = strokes.map { s -> s.copy(points = s.points.map { it.turnedClockwise() }) },
        texts = texts.map { it.copy(center = it.center.turnedClockwise(), rotation = it.rotation + 90f) },
        stickers = stickers.map { it.copy(center = it.center.turnedClockwise(), rotation = it.rotation + 90f) },
    )

    /** Mirrors the picture left to right; text and stickers move but still read the right way round. */
    fun mirroredOnce(): PhotoEdit = copy(
        mirrored = !mirrored,
        // A tilt seen in a mirror leans the other way.
        straighten = -straighten,
        crop = crop.mirrored(),
        strokes = strokes.map { s -> s.copy(points = s.points.map { it.mirrored() }) },
        texts = texts.map { it.copy(center = it.center.mirrored(), rotation = -it.rotation) },
        stickers = stickers.map { it.copy(center = it.center.mirrored(), rotation = -it.rotation) },
    )

    companion object {
        val NONE = PhotoEdit()
        const val MAX_STRAIGHTEN = 45f
    }
}

/** How the upscaler enlarged the photo: by [factor] (2 or 4), with its network for drawings when [art]. */
data class PhotoUpscale(val factor: Int, val art: Boolean)

/** A point as fractions of the turned picture. */
data class NPoint(val x: Float, val y: Float) {
    fun turnedClockwise() = NPoint(1f - y, x)
    fun mirrored() = NPoint(1f - x, y)
}

/** A crop window, as fractions of the turned picture. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun turnedClockwise() = CropRect(1f - bottom, left, 1f - top, right)
    fun mirrored() = CropRect(1f - right, top, 1f - left, bottom)

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

/** The crop tool's shape choices. */
enum class CropAspect(val w: Int, val h: Int) {
    FREE(0, 0),
    ORIGINAL(-1, -1),
    SQUARE(1, 1),
    R4_3(4, 3),
    R3_4(3, 4),
    R16_9(16, 9),
    R9_16(9, 16),
    R3_2(3, 2),
    R2_3(2, 3),
    ;

    /** Width over height, or null for free; [ORIGINAL] needs the picture's own, passed as [pictureRatio]. */
    fun ratio(pictureRatio: Float): Float? = when (this) {
        FREE -> null
        ORIGINAL -> pictureRatio
        else -> w.toFloat() / h
    }

    /** The same shape after a quarter turn: 4:3 becomes 3:4. */
    fun turned(): CropAspect = when (this) {
        R4_3 -> R3_4
        R3_4 -> R4_3
        R16_9 -> R9_16
        R9_16 -> R16_9
        R3_2 -> R2_3
        R2_3 -> R3_2
        else -> this
    }
}

/** Which part of the crop frame a finger has hold of. */
enum class CropHandle { MOVE, TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT }

/** The crop frame's geometry, kept apart from the UI so it can be tested. */
object CropMath {

    /** Smallest crop side, as a fraction of the picture. */
    const val MIN_SIDE = 0.08f

    /**
     * The largest window of shape [ratio] (width over height, in pixels)
     * centred on [around], inside a picture of shape [pictureRatio].
     */
    fun fit(ratio: Float, pictureRatio: Float, around: CropRect = CropRect.FULL): CropRect {
        // In fraction units a window of pixel ratio r has fraction ratio r / pictureRatio.
        val fr = ratio / pictureRatio
        var w = 1f
        var h = w / fr
        if (h > 1f) {
            h = 1f
            w = h * fr
        }
        val cx = (around.left + around.right) / 2f
        val cy = (around.top + around.bottom) / 2f
        val left = (cx - w / 2f).coerceIn(0f, 1f - w)
        val top = (cy - h / 2f).coerceIn(0f, 1f - h)
        return CropRect(left, top, left + w, top + h)
    }

    /**
     * [rect] after a drag of ([dx], [dy]) fractions on [handle], held inside
     * the picture, no smaller than [MIN_SIDE], and of shape [ratio] when given.
     */
    @Suppress("detekt.CyclomaticComplexMethod")
    fun drag(rect: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float?, pictureRatio: Float): CropRect {
        if (handle == CropHandle.MOVE) {
            val mx = dx.coerceIn(-rect.left, 1f - rect.right)
            val my = dy.coerceIn(-rect.top, 1f - rect.bottom)
            return CropRect(rect.left + mx, rect.top + my, rect.right + mx, rect.bottom + my)
        }
        var l = rect.left
        var t = rect.top
        var r = rect.right
        var b = rect.bottom
        when (handle) {
            CropHandle.TOP_LEFT -> { l += dx; t += dy }
            CropHandle.TOP -> t += dy
            CropHandle.TOP_RIGHT -> { r += dx; t += dy }
            CropHandle.RIGHT -> r += dx
            CropHandle.BOTTOM_RIGHT -> { r += dx; b += dy }
            CropHandle.BOTTOM -> b += dy
            CropHandle.BOTTOM_LEFT -> { l += dx; b += dy }
            CropHandle.LEFT -> l += dx
            CropHandle.MOVE -> Unit
        }
        l = l.coerceIn(0f, rect.right - MIN_SIDE)
        r = r.coerceIn(rect.left + MIN_SIDE, 1f)
        t = t.coerceIn(0f, rect.bottom - MIN_SIDE)
        b = b.coerceIn(rect.top + MIN_SIDE, 1f)
        if (ratio == null) return CropRect(l, t, r, b)
        // A fixed shape: the dragged side leads and the other follows, from
        // the corner opposite the handle, then everything is pulled back
        // inside the picture together.
        val fr = ratio / pictureRatio
        val anchorRight = handle in setOf(CropHandle.TOP_LEFT, CropHandle.LEFT, CropHandle.BOTTOM_LEFT)
        val anchorBottom = handle in setOf(CropHandle.TOP_LEFT, CropHandle.TOP, CropHandle.TOP_RIGHT)
        var w = r - l
        var h = b - t
        when (handle) {
            CropHandle.TOP, CropHandle.BOTTOM -> w = h * fr
            CropHandle.LEFT, CropHandle.RIGHT -> h = w / fr
            else -> if (w / fr > h) h = w / fr else w = h * fr
        }
        val maxW = if (anchorRight) rect.right else 1f - rect.left
        val maxH = if (anchorBottom) rect.bottom else 1f - rect.top
        if (w > maxW) { w = maxW; h = w / fr }
        if (h > maxH) { h = maxH; w = h * fr }
        val cx = (rect.left + rect.right) / 2f
        val nl = when (handle) {
            CropHandle.TOP, CropHandle.BOTTOM -> (cx - w / 2f).coerceIn(0f, 1f - w)
            else -> if (anchorRight) rect.right - w else rect.left
        }
        val cy = (rect.top + rect.bottom) / 2f
        val nt = when (handle) {
            CropHandle.LEFT, CropHandle.RIGHT -> (cy - h / 2f).coerceIn(0f, 1f - h)
            else -> if (anchorBottom) rect.bottom - h else rect.top
        }
        return CropRect(nl, nt, nl + w, nt + h)
    }

    /**
     * How much a picture of [width] by [height] has to grow, turned by
     * [degrees], for its own frame to stay covered with no empty corner.
     */
    fun coverScale(width: Float, height: Float, degrees: Float): Float {
        if (degrees == 0f || width <= 0f || height <= 0f) return 1f
        val a = Math.toRadians(abs(degrees).toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return max((width * c + height * s) / width, (width * s + height * c) / height)
    }

    /** Pixel width over height after [quarterTurns]. */
    fun turnedRatio(width: Int, height: Int, quarterTurns: Int): Float {
        if (width <= 0 || height <= 0) return 1f
        return if (quarterTurns % 2 == 0) width.toFloat() / height else height.toFloat() / width
    }

    /** The size of the sent picture for a source of [width] by [height]. */
    fun outputSize(width: Int, height: Int, edit: PhotoEdit): Pair<Int, Int> {
        val (tw, th) = if (edit.quarterTurns % 2 == 0) width to height else height to width
        val w = (tw * edit.crop.width).toInt().coerceAtLeast(1)
        val h = (th * edit.crop.height).toInt().coerceAtLeast(1)
        return w to h
    }

    fun clampUnit(v: Float): Float = min(1f, max(0f, v))
}

// ---- Colour ----------------------------------------------------------------

/**
 * The Adjust tab's sliders, each from -1 to 1 with 0 as "unchanged" (vignette,
 * grain and sharpen run 0 to 1). Colour ones become one 4x5 matrix through
 * [ColorMath]; the other three are drawn passes in [PhotoEditRenderer].
 */
data class Adjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val exposure: Float = 0f,
    val fade: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val sharpen: Float = 0f,
) {
    fun get(knob: AdjustKnob): Float = when (knob) {
        AdjustKnob.BRIGHTNESS -> brightness
        AdjustKnob.CONTRAST -> contrast
        AdjustKnob.SATURATION -> saturation
        AdjustKnob.WARMTH -> warmth
        AdjustKnob.TINT -> tint
        AdjustKnob.EXPOSURE -> exposure
        AdjustKnob.FADE -> fade
        AdjustKnob.HIGHLIGHTS -> highlights
        AdjustKnob.SHADOWS -> shadows
        AdjustKnob.VIGNETTE -> vignette
        AdjustKnob.GRAIN -> grain
        AdjustKnob.SHARPEN -> sharpen
    }

    fun with(knob: AdjustKnob, value: Float): Adjust {
        val v = value.coerceIn(knob.min, 1f)
        return when (knob) {
            AdjustKnob.BRIGHTNESS -> copy(brightness = v)
            AdjustKnob.CONTRAST -> copy(contrast = v)
            AdjustKnob.SATURATION -> copy(saturation = v)
            AdjustKnob.WARMTH -> copy(warmth = v)
            AdjustKnob.TINT -> copy(tint = v)
            AdjustKnob.EXPOSURE -> copy(exposure = v)
            AdjustKnob.FADE -> copy(fade = v)
            AdjustKnob.HIGHLIGHTS -> copy(highlights = v)
            AdjustKnob.SHADOWS -> copy(shadows = v)
            AdjustKnob.VIGNETTE -> copy(vignette = v)
            AdjustKnob.GRAIN -> copy(grain = v)
            AdjustKnob.SHARPEN -> copy(sharpen = v)
        }
    }
}

/** One Adjust slider. [min] is -1 for the two-way ones and 0 for the strength ones. */
enum class AdjustKnob(val min: Float) {
    BRIGHTNESS(-1f), CONTRAST(-1f), SATURATION(-1f), WARMTH(-1f), TINT(-1f), EXPOSURE(-1f),
    HIGHLIGHTS(-1f), SHADOWS(-1f), FADE(0f), VIGNETTE(0f), GRAIN(0f), SHARPEN(0f),
}

/**
 * The Filters strip. Each is a colour matrix plus, for a few, a vignette or
 * grain of its own; [PhotoEdit.filterAmount] blends it with the original.
 */
enum class PhotoFilter {
    ORIGINAL, VIVID, WARM, COOL, MONO, NOIR, FADE, SEPIA, CHROME, DRAMATIC, MATTE, RETRO, LUSH, DUSK,
}

/**
 * 4x5 colour matrices as plain float arrays, in the row-major layout Android's
 * `ColorMatrix` takes: each output channel is a row of [r, g, b, a, offset],
 * with colour values from 0 to 255. Plain Kotlin so the maths is testable.
 */
object ColorMath {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** [b] applied after [a]: the matrix that does both. */
    fun concat(b: FloatArray, a: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) sum += b[row * 5 + k] * a[k * 5 + col]
                if (col == 4) sum += b[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }

    /** Each entry of [m] moved [amount] of the way from the identity. */
    fun lerpFromIdentity(m: FloatArray, amount: Float): FloatArray {
        val id = identity()
        return FloatArray(20) { id[it] + (m[it] - id[it]) * amount }
    }

    fun scale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun offset(r: Float, g: Float, b: Float): FloatArray = identity().also {
        it[4] = r
        it[9] = g
        it[14] = b
    }

    /** Saturation, 0 grey, 1 unchanged, above 1 stronger; the Rec. 709 weights Android uses. */
    fun saturation(s: Float): FloatArray {
        val lr = 0.213f
        val lg = 0.715f
        val lb = 0.072f
        val i = 1f - s
        return floatArrayOf(
            lr * i + s, lg * i, lb * i, 0f, 0f,
            lr * i, lg * i + s, lb * i, 0f, 0f,
            lr * i, lg * i, lb * i + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Contrast around mid grey: 1 unchanged. */
    fun contrast(c: Float): FloatArray {
        val t = 128f * (1f - c)
        return floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun sepia(): FloatArray = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** The sliders' colour part as one matrix. Highlights and shadows are approximated as curves of gain and lift. */
    fun adjust(a: Adjust): FloatArray {
        var m = identity()
        if (a.exposure != 0f) {
            val gain = Math.pow(2.0, (a.exposure * 1.5f).toDouble()).toFloat()
            m = concat(scale(gain, gain, gain), m)
        }
        if (a.brightness != 0f) {
            val o = a.brightness * 60f
            m = concat(offset(o, o, o), m)
        }
        if (a.highlights != 0f) {
            // Lifts or lowers the top of the range while the bottom stays put.
            val g = 1f + a.highlights * 0.25f
            m = concat(scale(g, g, g), m)
        }
        if (a.shadows != 0f) {
            // Lifts the bottom of the range and gives the top back, so white stays white.
            val lift = a.shadows * 40f
            val g = (255f - lift) / 255f
            m = concat(concat(offset(lift, lift, lift), scale(g, g, g)), m)
        }
        if (a.contrast != 0f) m = concat(contrast(1f + a.contrast * if (a.contrast > 0) 0.8f else 0.6f), m)
        if (a.saturation != 0f) m = concat(saturation(1f + a.saturation), m)
        if (a.warmth != 0f) {
            val w = a.warmth * 0.12f
            m = concat(scale(1f + w, 1f, 1f - w), m)
        }
        if (a.tint != 0f) {
            val t = a.tint * 0.10f
            m = concat(scale(1f + t / 2f, 1f - t, 1f + t / 2f), m)
        }
        if (a.fade > 0f) {
            // Lifted blacks and a little less contrast: a washed-out print.
            val f = a.fade
            val g = 1f - f * 0.25f
            val o = f * 45f
            m = concat(concat(offset(o, o, o), scale(g, g, g)), m)
        }
        return m
    }

    @Suppress("detekt.CyclomaticComplexMethod")
    fun filter(f: PhotoFilter): FloatArray = when (f) {
        PhotoFilter.ORIGINAL -> identity()
        PhotoFilter.VIVID -> concat(contrast(1.12f), saturation(1.45f))
        PhotoFilter.WARM -> concat(scale(1.08f, 1.02f, 0.88f), saturation(1.1f))
        PhotoFilter.COOL -> concat(scale(0.92f, 1.0f, 1.1f), offset(0f, 2f, 8f))
        PhotoFilter.MONO -> saturation(0f)
        PhotoFilter.NOIR -> concat(contrast(1.35f), saturation(0f))
        PhotoFilter.FADE -> concat(offset(30f, 30f, 30f), concat(scale(0.82f, 0.82f, 0.82f), saturation(0.75f)))
        PhotoFilter.SEPIA -> sepia()
        PhotoFilter.CHROME -> concat(contrast(1.18f), concat(saturation(1.2f), scale(1.02f, 1.02f, 1.06f)))
        PhotoFilter.DRAMATIC -> concat(contrast(1.4f), concat(saturation(0.8f), offset(-10f, -10f, -10f)))
        PhotoFilter.MATTE -> concat(offset(22f, 18f, 18f), concat(scale(0.86f, 0.88f, 0.9f), saturation(0.85f)))
        PhotoFilter.RETRO -> concat(offset(18f, 8f, -6f), concat(scale(1.0f, 0.95f, 0.78f), saturation(0.8f)))
        PhotoFilter.LUSH -> concat(scale(0.95f, 1.08f, 0.95f), saturation(1.3f))
        PhotoFilter.DUSK -> concat(scale(1.06f, 0.92f, 1.04f), concat(contrast(1.08f), saturation(0.9f)))
    }

    /** The filter's own vignette, for the ones that come with one. */
    fun filterVignette(f: PhotoFilter): Float = when (f) {
        PhotoFilter.NOIR, PhotoFilter.DRAMATIC -> 0.35f
        PhotoFilter.RETRO -> 0.3f
        PhotoFilter.DUSK -> 0.2f
        else -> 0f
    }

    fun filterGrain(f: PhotoFilter): Float = when (f) {
        PhotoFilter.RETRO -> 0.35f
        PhotoFilter.NOIR -> 0.2f
        PhotoFilter.FADE, PhotoFilter.MATTE -> 0.12f
        else -> 0f
    }

    /** Filter (at its amount) then the sliders: what the picture's colours go through. */
    fun forEdit(edit: PhotoEdit): FloatArray =
        concat(adjust(edit.adjust), lerpFromIdentity(filter(edit.filter), edit.filterAmount.coerceIn(0f, 1f)))

    /** [m] applied to one colour, each channel 0 to 255 and clamped: for tests and swatches. */
    fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 3] * 255f + m[row * 5 + 4])
            .coerceIn(0f, 255f)
        return Triple(ch(0), ch(1), ch(2))
    }
}

// ---- Layers ----------------------------------------------------------------

/** What a stroke is drawn with. */
enum class BrushKind {
    PEN, MARKER, NEON, ARROW, ERASER,

    /** Paints a blurred copy of the picture under the finger: for hiding a face or a number. */
    BLUR,

    /** The same, in large square blocks. */
    PIXELATE,
}

/**
 * A finger's line. [width] is a fraction of the turned picture's shorter
 * side, so a stroke is as thick on the sent copy as it looked in the preview.
 */
data class Stroke(
    val brush: BrushKind,
    val color: Int,
    val width: Float,
    val points: List<NPoint>,
)

enum class TextStyle {
    /** Coloured letters with a soft shadow. */
    PLAIN,

    /** Coloured letters ringed in black or white. */
    OUTLINE,

    /** Letters on a rounded box of the colour. */
    BOX,

    /** The box half see-through. */
    GLASS,
}

enum class TextAlign { START, CENTER, END }

/** A text box on the picture. [size] is the line height as a fraction of the shorter side. */
data class TextLayer(
    val id: Long,
    val text: String,
    val color: Int,
    val style: TextStyle = TextStyle.PLAIN,
    val align: TextAlign = TextAlign.CENTER,
    val center: NPoint = NPoint(0.5f, 0.5f),
    val size: Float = 0.08f,
    val rotation: Float = 0f,
    val bold: Boolean = true,
)

/** A sticker or an emoji on the picture. [size] is its width as a fraction of the shorter side. */
data class StickerLayer(
    val id: Long,
    /** A sticker file the keyboard keeps, or null for [emoji]. */
    val path: String?,
    val emoji: String? = null,
    val center: NPoint = NPoint(0.5f, 0.5f),
    val size: Float = 0.3f,
    val rotation: Float = 0f,
)

/** One item's edit with its undo and redo steps; the editor's working copy. */
data class EditHistory(
    val current: PhotoEdit = PhotoEdit.NONE,
    val undo: List<PhotoEdit> = emptyList(),
    val redo: List<PhotoEdit> = emptyList(),
    /** Where a change still under the finger started from; null when none is. */
    val pendingFrom: PhotoEdit? = null,
) {
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** A finished step: a stroke laid down, a slider let go. */
    fun push(next: PhotoEdit): EditHistory {
        val from = pendingFrom ?: current
        return if (next == from) {
            EditHistory(next, undo, redo)
        } else {
            EditHistory(next, (undo + from).takeLast(MAX_STEPS), emptyList())
        }
    }

    /** A change while a finger is still down: shown, but not a step of its own until [push]. */
    fun preview(next: PhotoEdit): EditHistory = copy(current = next, pendingFrom = pendingFrom ?: current)

    fun undone(): EditHistory = if (undo.isEmpty()) this else EditHistory(undo.last(), undo.dropLast(1), redo + current)
    fun redone(): EditHistory = if (redo.isEmpty()) this else EditHistory(redo.last(), undo + current, redo.dropLast(1))

    companion object {
        const val MAX_STEPS = 60
    }
}
