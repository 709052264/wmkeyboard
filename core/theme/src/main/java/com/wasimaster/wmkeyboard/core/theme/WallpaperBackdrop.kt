package com.wasimaster.wmkeyboard.core.theme

import android.Manifest
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * The band of the wallpaper a glass theme stands on when nothing else is
 * behind it: the theme has no background image, and the app behind the
 * keyboard is not being blurred (an older Android, battery saver, the
 * floating keyboard, the settings app's preview).
 *
 * The wallpaper's pixels are only readable on Android 12 and below, and
 * there only with the storage permission ([canReadPixels]); Android 13
 * keeps them private from every app. Everywhere else the backdrop is
 * *painted* in the wallpaper's own colours instead — the three
 * `WallpaperColors` the system publishes without any permission — as soft
 * fields of light, which under the glass's blur read much the same. A phone
 * below Android 8.1 has no wallpaper colours either, and the theme's own
 * colours stand in.
 */
object WallpaperBackdrop {

    /** Whether the wallpaper's real pixels can be read on this phone, right now. */
    fun canReadPixels(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /**
     * Changes when the wallpaper does, or when the pixels become readable:
     * what a cache keys the backdrop on. Zero on a phone that will not say.
     */
    fun stamp(context: Context): Long {
        val id = runCatching {
            WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        }.getOrDefault(0)
        return id.toLong() * 2 + if (canReadPixels(context)) 1 else 0
    }

    /** The wallpaper's primary, secondary and tertiary colours, where the system publishes them. */
    fun wallpaperColors(context: Context): List<Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return emptyList()
        val colors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull() ?: return emptyList()
        return listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
            .map { it.toArgb().toLong() and 0xFFFFFFFFL }
    }

    /**
     * The backdrop at [targetW] x [targetH]: the wallpaper's [band] where the
     * pixels can be read, else colour fields in the wallpaper's colours, else
     * in [fallbackColors] (the theme's own). Never null, so a glass theme
     * always has something to stand on.
     */
    fun render(
        context: Context,
        band: WallpaperBand,
        targetW: Int,
        targetH: Int,
        dark: Boolean,
        fallbackColors: List<Long>,
    ): Bitmap {
        readBand(context, band, targetW, targetH)?.let { return it }
        val colors = wallpaperColors(context).ifEmpty { fallbackColors }
        return colourFields(colors, targetW, targetH, dark)
    }

    /**
     * The wallpaper bake for the glass: [render] put through [FrostBake] and
     * cached under the wallpaper's [stamp], so a wallpaper change or a newly
     * granted permission produces a new backdrop and nothing else re-bakes.
     */
    suspend fun loadFrosted(
        context: Context,
        band: WallpaperBand,
        blur: Float,
        saturation: Float,
        brightness: Float,
        targetW: Int,
        targetH: Int,
        dark: Boolean,
        fallbackColors: List<Long>,
    ): Bitmap? {
        val key = BackgroundBitmapCache.frostedKey(
            path = "wallpaper:${band.name}:${if (dark) "dark" else "light"}:${fallbackColors.hashCode()}",
            stamp = stamp(context),
            blur = blur,
            saturation = saturation,
            brightness = brightness,
            targetW = targetW,
            targetH = targetH,
        )
        return BackgroundBitmapCache.loadBaked(key) {
            val sharp = render(context, band, key.targetW, key.targetH, dark, fallbackColors)
            // Never recycled: the sharp band may be the wallpaper manager's own
            // cached bitmap, and the cache's rule is to let the collector take it.
            FrostBake.bake(
                sharp,
                blur = key.blurStep.toFloat(),
                saturation = key.saturationStep / BackgroundBitmapCache.SATURATION_STEPS,
                brightness = key.brightnessStep / BackgroundBitmapCache.BRIGHTNESS_STEPS,
            )
        }
    }

    /** The wallpaper's [band] at the target's aspect ratio, or null where it cannot be read. */
    private fun readBand(context: Context, band: WallpaperBand, targetW: Int, targetH: Int): Bitmap? {
        if (!canReadPixels(context)) return null
        val drawable = runCatching { WallpaperManager.getInstance(context).drawable }.getOrNull() ?: return null
        val source = (drawable as? BitmapDrawable)?.bitmap ?: runCatching {
            val w = max(1, drawable.intrinsicWidth)
            val h = max(1, drawable.intrinsicHeight)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, w, h)
                drawable.draw(Canvas(bitmap))
            }
        }.getOrNull() ?: return null
        if (source.width <= 0 || source.height <= 0) return null
        // A hardware bitmap cannot be cropped or read; a software copy can.
        val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            source
        }
        // The widest band of the target's aspect ratio that fits the
        // wallpaper, taken from the edge the theme asked for.
        val bandH = min(readable.height, (readable.width.toFloat() * targetH / max(1, targetW)).toInt().coerceAtLeast(1))
        val top = when (band) {
            WallpaperBand.TOP -> 0
            WallpaperBand.CENTER -> (readable.height - bandH) / 2
            WallpaperBand.BOTTOM -> readable.height - bandH
        }
        val cropped = Bitmap.createBitmap(readable, 0, top, readable.width, bandH)
        return Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
    }

    /**
     * Soft fields of [colors] over a base of the first one darkened (or, for
     * a light theme, lightened): what a blurred wallpaper looks like from a
     * distance. Fixed positions, so the same colours always paint the same
     * picture. Drawn at a quarter size; the bake upscales and blurs it.
     */
    fun colourFields(colors: List<Long>, targetW: Int, targetH: Int, dark: Boolean): Bitmap {
        val w = max(8, targetW / 4)
        val h = max(8, targetH / 4)
        val palette = colors.ifEmpty { listOf(0xFF3B5BDB) }.map { Color(it.toInt()).copy(alpha = 1f) }
        val base = lerp(palette.first(), if (dark) Color.Black else Color.White, if (dark) 0.78f else 0.72f)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(base.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((index, field) in FIELDS.withIndex()) {
            val color = palette[index % palette.size]
            val cx = field[0] * w
            val cy = field[1] * h
            val radius = field[2] * max(w, h)
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(color.copy(alpha = field[3]).toArgb(), color.copy(alpha = 0f).toArgb()),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }
        return bitmap
    }

    /** Centre x, centre y (fractions), radius (fraction of the long side), peak alpha. */
    private val FIELDS = listOf(
        floatArrayOf(0.18f, 0.30f, 0.46f, 0.70f),
        floatArrayOf(0.82f, 0.42f, 0.40f, 0.60f),
        floatArrayOf(0.32f, 0.88f, 0.42f, 0.55f),
        floatArrayOf(0.70f, 0.08f, 0.26f, 0.40f),
        floatArrayOf(0.55f, 0.62f, 0.26f, 0.35f),
    )
}

/** Which part of the wallpaper a glass theme stands on. Never serialized — travels as a string. */
enum class WallpaperBand { BOTTOM, CENTER, TOP }

/** The band behind [FrostSpec.wallpaperBand]; an absent or unknown name is the bottom, where the keyboard is. */
fun wallpaperBandOrDefault(name: String?): WallpaperBand =
    WallpaperBand.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WallpaperBand.BOTTOM
