package com.wasimaster.wmkeyboard.core.docscan

import android.graphics.Bitmap
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A page's four corners in a photo's own pixels, clockwise from top left:
 * top left, top right, bottom right, bottom left, as x, y pairs.
 */
class PageCorners(private val xy: FloatArray) {
    init {
        require(xy.size >= 8) { "four corners need eight numbers" }
    }

    fun x(i: Int): Float = xy[i * 2]

    fun y(i: Int): Float = xy[i * 2 + 1]

    fun toArray(): FloatArray = xy.copyOf(8)

    /** The same corners with corner [i] moved to [x], [y]. */
    fun moved(i: Int, x: Float, y: Float): PageCorners =
        PageCorners(toArray().also { it[i * 2] = x; it[i * 2 + 1] = y })

    /**
     * Whether the four make a page at all: convex and turning the same way at
     * every corner. A handle dragged across its neighbour's side folds the
     * quad over itself, and flattening that would mirror half the page.
     */
    val convex: Boolean
        get() {
            var sign = 0
            for (i in 0 until 4) {
                val a = i
                val b = (i + 1) % 4
                val c = (i + 2) % 4
                val cross = (x(b) - x(a)) * (y(c) - y(b)) - (y(b) - y(a)) * (x(c) - x(b))
                val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
                if (s == 0) return false
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }

    /**
     * The flattened page's size: the longer of each pair of opposite sides,
     * scaled down so neither axis passes [maxSide]. Mirrors pageSize() in
     * native/docscan-jni/docscan.cpp.
     */
    fun pageSize(maxSide: Int): Pair<Int, Int> {
        fun dist(a: Int, b: Int) = hypot((x(a) - x(b)).toDouble(), (y(a) - y(b)).toDouble())
        var w = max(dist(0, 1), dist(3, 2))
        var h = max(dist(0, 3), dist(1, 2))
        val over = max(w, h) / maxSide
        if (over > 1) {
            w /= over
            h /= over
        }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /**
     * The same corners in the picture turned [degrees] clockwise (0, 90, 180
     * or 270), where the unturned picture is [width] by [height]: how a
     * camera frame's corners land on the upright photo. Starts again from
     * whichever corner is top left now.
     */
    fun rotated(degrees: Int, width: Int, height: Int): PageCorners {
        val turned = FloatArray(8)
        for (i in 0 until 4) {
            val px = x(i)
            val py = y(i)
            val (nx, ny) = when (((degrees % 360) + 360) % 360) {
                90 -> (height - 1 - py) to px
                180 -> (width - 1 - px) to (height - 1 - py)
                270 -> py to (width - 1 - px)
                else -> px to py
            }
            turned[i * 2] = nx
            turned[i * 2 + 1] = ny
        }
        // A turn keeps the order clockwise; only the start moves.
        val first = (0 until 4).minBy { turned[it * 2] + turned[it * 2 + 1] }
        return PageCorners(FloatArray(8) { turned[((first + it / 2) % 4) * 2 + it % 2] })
    }

    companion object {
        /** The whole of a [width] by [height] picture, a few pixels in from its edges. */
        fun whole(width: Int, height: Int, inset: Float = 0f): PageCorners {
            val r = width - 1 - inset
            val b = height - 1 - inset
            return PageCorners(floatArrayOf(inset, inset, r, inset, r, b, inset, b))
        }
    }
}

/** What the scanner does to a flattened page. [code] is native/docscan-jni's Enhance value. */
enum class PageFilter(val code: Int) {
    /** The photo as it is. */
    ORIGINAL(0),

    /** Paper to white and shadows out, ink in its own colours. */
    COLOUR(1),

    /** The same, in grey. */
    GREY(2),

    /** Black ink on white paper. */
    BLACK_WHITE(3),
}

/**
 * The built-in document scanner's image work, for phones without Google's
 * (no Play services, or the F-Droid build): find the page in a photo,
 * flatten it, clean it up. Each call needs [DocScanNative.available]; each
 * returns null or false when the library is not there or gives up, and the
 * caller falls back to the whole picture as it is.
 */
object DocScanner {
    /** The longest side a flattened page is drawn at. A4 at 300 dpi is 3508. */
    const val MAX_PAGE_SIDE = 3508

    val available: Boolean get() = DocScanNative.available

    /** The page in [photo] and how sure the detector is (0..1), or null when it saw none. */
    fun detect(photo: Bitmap): Pair<PageCorners, Float>? {
        if (!available) return null
        val found = runCatching { DocScanNative.nativeDetect(argb(photo)) }.getOrNull() ?: return null
        return PageCorners(found.copyOf(8)) to found[8]
    }

    /** The page inside [corners] of [photo], drawn flat into a new bitmap. */
    fun flatten(photo: Bitmap, corners: PageCorners, maxSide: Int = MAX_PAGE_SIDE): Bitmap? {
        if (!available || !corners.convex) return null
        val (w, h) = corners.pageSize(maxSide)
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = runCatching { DocScanNative.nativeWarp(argb(photo), corners.toArray(), page) }.getOrDefault(false)
        if (!ok) {
            page.recycle()
            return null
        }
        return page
    }

    /** A cleaned-up copy of [page]; [page] itself for [PageFilter.ORIGINAL]. */
    fun filtered(page: Bitmap, filter: PageFilter): Bitmap {
        if (filter == PageFilter.ORIGINAL || !available) return page
        val copy = page.copy(Bitmap.Config.ARGB_8888, true) ?: return page
        val ok = runCatching { DocScanNative.nativeEnhance(copy, filter.code) }.getOrDefault(false)
        if (!ok) {
            copy.recycle()
            return page
        }
        return copy
    }

    /** The library reads only ARGB_8888; a camera or decoder bitmap usually is already. */
    private fun argb(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
}
