package com.wasimaster.wmkeyboard.core.theme

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.theme.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * How one colour is grown into a whole palette: the scheme variants Android's
 * own wallpaper colours come in (Settings → Wallpaper & style offers the same
 * set under other names), so one app colour can look several different ways.
 *
 * Each is five tonal palettes cut at a hue and a colourfulness, written down in
 * [appPalette]. The numbers follow Material's published scheme definitions;
 * the colour space they are cut in is CIELAB rather than Material's HCT (see
 * [TonalPalette]), so a palette lands close to Android's, not on it.
 */
enum class PaletteStyle(@StringRes val labelRes: Int) {
    /** Android's default: a calm primary, quiet secondary, a tertiary 60° round. */
    TONAL_SPOT(R.string.core_theme_palette_style_tonal_spot),

    /** The colour as loud as it goes, with the rest near it on the wheel. */
    VIBRANT(R.string.core_theme_palette_style_vibrant),

    /** The primary turned well away from the colour itself, for something playful. */
    EXPRESSIVE(R.string.core_theme_palette_style_expressive),

    /** As close to the exact colour as a palette can stay, complement as tertiary. */
    FIDELITY(R.string.core_theme_palette_style_fidelity),

    /** Barely tinted: the colour as a hint in greys. */
    NEUTRAL(R.string.core_theme_palette_style_neutral),

    /** Pure greys. Only the light and dark of the theme survive. */
    MONOCHROME(R.string.core_theme_palette_style_monochrome),

    /** Colourful accents over untinted greys. */
    RAINBOW(R.string.core_theme_palette_style_rainbow),

    /** The primary turned 50° back, the colour itself as tertiary. */
    FRUIT_SALAD(R.string.core_theme_palette_style_fruit_salad),
}

/**
 * Where an app's colours are looked for.
 *
 * [APP_THEME] is what AnySoftKeyboard does: the colours the app's own launch
 * screen declares (`colorPrimary` and friends), which is the colour of its
 * toolbar. Apps written in Compose or edge to edge often declare nothing, or
 * the template's purple, so [ICON] reads the launcher icon instead, which every
 * app has. [AUTO] takes the declared colour when there is a real one and the
 * icon's otherwise, and offers the rest as alternatives.
 */
enum class AppColorSource { AUTO, ICON, APP_THEME }

/**
 * The colours one app offered, before any choice is made: what its theme
 * declares, and what its icon is made of, each most prominent first.
 *
 * Kept as two lists rather than one so that changing [AppColorSource] needs no
 * second look at the app.
 */
data class AppColorSample(
    val packageName: String,
    val themeColors: List<Long>,
    val iconColors: List<Long>,
) {

    /**
     * The colours worth building a palette from, best first, as [source]
     * orders them: greys, near-black and near-white dropped (a palette grown
     * from grey is the stock look again), Android Studio's template colours
     * dropped (they are the app having no colour, not the app's colour), and
     * colours too close to one already listed folded into it.
     */
    fun candidates(source: AppColorSource, max: Int = MAX_CANDIDATES): List<Long> {
        val ordered = when (source) {
            AppColorSource.AUTO -> themeColors + iconColors
            AppColorSource.ICON -> iconColors
            AppColorSource.APP_THEME -> themeColors
        }
        val chosen = ArrayList<Lab>()
        val out = ArrayList<Long>()
        for (color in ordered) {
            val opaque = color or OPAQUE
            if (opaque in TEMPLATE_COLORS) continue
            val lab = Lab.of(opaque)
            if (lab.chroma < MIN_CHROMA || lab.l < MIN_TONE || lab.l > MAX_TONE) continue
            val duplicate = chosen.any {
                hueDistance(it.hue, lab.hue) < SAME_HUE && abs(it.l - lab.l) < SAME_TONE
            }
            if (duplicate) continue
            chosen += lab
            out += opaque
            if (out.size >= max) break
        }
        return out
    }

    companion object {
        /** How many alternatives the keyboard offers for one app. */
        const val MAX_CANDIDATES = 4
    }
}

/**
 * A whole wallpaper-shaped palette grown from [seed], in [style].
 *
 * The result is the same type the device's own wallpaper colours come in, so
 * everything that follows the wallpaper ([recoloredBy], the Default theme's
 * colour scheme) follows this instead without knowing the difference.
 */
fun appPalette(seed: Long, style: PaletteStyle): WallpaperPalette {
    val lab = Lab.of(seed or OPAQUE)
    val h = lab.hue
    val c = lab.chroma
    val set = when (style) {
        PaletteStyle.TONAL_SPOT -> PaletteSet(
            primary = TonalPalette(h, 36f),
            secondary = TonalPalette(h, 16f),
            tertiary = TonalPalette(h + 60f, 24f),
            neutral = TonalPalette(h, 6f),
            neutralVariant = TonalPalette(h, 8f),
        )
        PaletteStyle.VIBRANT -> PaletteSet(
            primary = TonalPalette(h, VIVID),
            secondary = TonalPalette(h + 18f, 24f),
            tertiary = TonalPalette(h + 50f, 32f),
            neutral = TonalPalette(h, 10f),
            neutralVariant = TonalPalette(h, 12f),
        )
        PaletteStyle.EXPRESSIVE -> PaletteSet(
            primary = TonalPalette(h + 240f, 40f),
            secondary = TonalPalette(h + 45f, 24f),
            tertiary = TonalPalette(h + 120f, 32f),
            neutral = TonalPalette(h + 15f, 8f),
            neutralVariant = TonalPalette(h + 15f, 12f),
        )
        PaletteStyle.FIDELITY -> PaletteSet(
            primary = TonalPalette(h, c),
            secondary = TonalPalette(h, max(c - 32f, c * 0.5f)),
            tertiary = TonalPalette(h + 180f, max(c * 0.6f, 24f)),
            neutral = TonalPalette(h, c / 8f),
            neutralVariant = TonalPalette(h, c / 8f + 4f),
        )
        PaletteStyle.NEUTRAL -> PaletteSet(
            primary = TonalPalette(h, 12f),
            secondary = TonalPalette(h, 8f),
            tertiary = TonalPalette(h, 16f),
            neutral = TonalPalette(h, 2f),
            neutralVariant = TonalPalette(h, 2f),
        )
        PaletteStyle.MONOCHROME -> PaletteSet(
            primary = TonalPalette(h, 0f),
            secondary = TonalPalette(h, 0f),
            tertiary = TonalPalette(h, 0f),
            neutral = TonalPalette(h, 0f),
            neutralVariant = TonalPalette(h, 0f),
        )
        PaletteStyle.RAINBOW -> PaletteSet(
            primary = TonalPalette(h, 48f),
            secondary = TonalPalette(h, 16f),
            tertiary = TonalPalette(h + 60f, 24f),
            neutral = TonalPalette(h, 0f),
            neutralVariant = TonalPalette(h, 0f),
        )
        PaletteStyle.FRUIT_SALAD -> PaletteSet(
            primary = TonalPalette(h - 50f, 48f),
            secondary = TonalPalette(h - 50f, 36f),
            tertiary = TonalPalette(h, 36f),
            neutral = TonalPalette(h, 10f),
            neutralVariant = TonalPalette(h, 16f),
        )
    }
    return WallpaperPalette(roles = set.roles(), tones = set.tones())
}

/**
 * Three colours that stand for [style] grown from [seed]: primary, secondary
 * and tertiary at the tone a light scheme uses them. What a style picker draws
 * as its swatch, so the choice is made by looking rather than by name.
 */
fun paletteStyleSwatch(seed: Long, style: PaletteStyle): List<Long> {
    val tones = appPalette(seed, style).tones
    val index = WallpaperTones.TONES.indexOf(SWATCH_TONE)
    return listOf(tones.primary[index], tones.secondary[index], tones.tertiary[index])
}

private class PaletteSet(
    val primary: TonalPalette,
    val secondary: TonalPalette,
    val tertiary: TonalPalette,
    val neutral: TonalPalette,
    val neutralVariant: TonalPalette,
) {
    fun tones() = WallpaperTones(
        primary = WallpaperTones.TONES.map { primary.tone(it) },
        secondary = WallpaperTones.TONES.map { secondary.tone(it) },
        tertiary = WallpaperTones.TONES.map { tertiary.tone(it) },
        neutral = WallpaperTones.TONES.map { neutral.tone(it) },
        neutralVariant = WallpaperTones.TONES.map { neutralVariant.tone(it) },
    )

    /** Material 3's role-to-tone table, light then dark. */
    fun roles(): SnyggPalette {
        val p = primary
        val s = secondary
        val t = tertiary
        val n = neutral
        val nv = neutralVariant
        val baseLight = SnyggPalette.Baseline.light
        val baseDark = SnyggPalette.Baseline.dark
        fun err(map: Map<String, Long>, role: String) = map.getValue(SnyggPalette.normalizeRole(role))
        return SnyggPalette(
            light = SnyggPalette.buildRoles(
                primary = p.tone(40), onPrimary = p.tone(100),
                primaryContainer = p.tone(90), onPrimaryContainer = p.tone(10),
                secondary = s.tone(40), onSecondary = s.tone(100),
                secondaryContainer = s.tone(90), onSecondaryContainer = s.tone(10),
                tertiary = t.tone(40), onTertiary = t.tone(100),
                tertiaryContainer = t.tone(90), onTertiaryContainer = t.tone(10),
                background = n.tone(98), onBackground = n.tone(10),
                surface = n.tone(98), onSurface = n.tone(10),
                surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30),
                surfaceDim = n.tone(87), surfaceBright = n.tone(98),
                surfaceContainerLowest = n.tone(100), surfaceContainerLow = n.tone(96),
                surfaceContainer = n.tone(94), surfaceContainerHigh = n.tone(92),
                surfaceContainerHighest = n.tone(90),
                outline = nv.tone(50), outlineVariant = nv.tone(80),
                inverseSurface = n.tone(20), inverseOnSurface = n.tone(95),
                inversePrimary = p.tone(80),
                error = err(baseLight, "error"), onError = err(baseLight, "onError"),
                errorContainer = err(baseLight, "errorContainer"),
                onErrorContainer = err(baseLight, "onErrorContainer"),
                scrim = OPAQUE,
            ),
            dark = SnyggPalette.buildRoles(
                primary = p.tone(80), onPrimary = p.tone(20),
                primaryContainer = p.tone(30), onPrimaryContainer = p.tone(90),
                secondary = s.tone(80), onSecondary = s.tone(20),
                secondaryContainer = s.tone(30), onSecondaryContainer = s.tone(90),
                tertiary = t.tone(80), onTertiary = t.tone(20),
                tertiaryContainer = t.tone(30), onTertiaryContainer = t.tone(90),
                background = n.tone(6), onBackground = n.tone(90),
                surface = n.tone(6), onSurface = n.tone(90),
                surfaceVariant = nv.tone(30), onSurfaceVariant = nv.tone(80),
                surfaceDim = n.tone(6), surfaceBright = n.tone(24),
                surfaceContainerLowest = n.tone(4), surfaceContainerLow = n.tone(10),
                surfaceContainer = n.tone(12), surfaceContainerHigh = n.tone(17),
                surfaceContainerHighest = n.tone(22),
                outline = nv.tone(60), outlineVariant = nv.tone(30),
                inverseSurface = n.tone(90), inverseOnSurface = n.tone(20),
                inversePrimary = p.tone(40),
                error = err(baseDark, "error"), onError = err(baseDark, "onError"),
                errorContainer = err(baseDark, "errorContainer"),
                onErrorContainer = err(baseDark, "onErrorContainer"),
                scrim = OPAQUE,
            ),
        )
    }
}

/**
 * Every lightness of one hue at one colourfulness: Material's tonal palette,
 * cut in CIELAB.
 *
 * Material cuts its palettes in HCT, whose tone *is* CIELAB lightness and whose
 * hue and chroma come from CAM16. CIELAB's own hue and chroma are close enough
 * for a keyboard — the tone, which is what contrast is made of, is exact — and
 * this needs no colour-appearance model in the app. A colour the screen cannot
 * show at the asked chroma keeps its hue and tone and loses chroma until it
 * fits, which is what HCT does too.
 */
internal class TonalPalette(hue: Float, private val chroma: Float) {
    private val hue = ((hue % 360f) + 360f) % 360f

    fun tone(tone: Int): Long = tone(tone.toFloat())

    fun tone(tone: Float): Long = lchToArgb(tone.coerceIn(0f, 100f), chroma, hue)
}

/** The sRGB colour at [l], [c], [h] in CIELAB, chroma lowered until it fits. */
private fun lchToArgb(l: Float, c: Float, h: Float): Long {
    if (l <= 0f) return OPAQUE
    if (l >= 100f) return 0xFFFFFFFFL
    labToLinear(l, c, h)?.let { return encode(it) }
    // Bisect the largest chroma that is still on screen at this hue and tone.
    var low = 0f
    var high = c
    var best = labToLinear(l, 0f, h) ?: doubleArrayOf(0.0, 0.0, 0.0)
    repeat(GAMUT_STEPS) {
        val mid = (low + high) / 2f
        val rgb = labToLinear(l, mid, h)
        if (rgb != null) {
            best = rgb
            low = mid
        } else {
            high = mid
        }
    }
    return encode(best)
}

/** Linear sRGB for an LCh colour, or null when the screen cannot show it. */
private fun labToLinear(l: Float, c: Float, h: Float): DoubleArray? {
    val radians = Math.toRadians(h.toDouble())
    val a = c * cos(radians)
    val b = c * sin(radians)
    val fy = (l + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - b / 200.0
    fun inverse(t: Double) = if (t * t * t > EPSILON) t * t * t else (116.0 * t - 16.0) / KAPPA
    val x = inverse(fx) * WHITE_X
    val y = if (l > KAPPA * EPSILON) fy * fy * fy else l / KAPPA
    val z = inverse(fz) * WHITE_Z
    val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
    val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    val inGamut = listOf(r, g, bl).all { it >= -GAMUT_SLACK && it <= 1.0 + GAMUT_SLACK }
    return if (inGamut) doubleArrayOf(r, g, bl) else null
}

private fun encode(linear: DoubleArray): Long {
    fun channel(v: Double): Long {
        val clamped = v.coerceIn(0.0, 1.0)
        val srgb = if (clamped <= 0.0031308) 12.92 * clamped else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
        return (srgb * 255.0).roundToLong().coerceIn(0L, 255L)
    }
    return OPAQUE or (channel(linear[0]) shl 16) or (channel(linear[1]) shl 8) or channel(linear[2])
}

/**
 * Colours that mean the app never picked one: the primaries and accents of
 * Android Studio's project templates, AppCompat's defaults and Material's
 * baseline. A palette grown from these is the same purple on every app.
 */
private val TEMPLATE_COLORS = setOf(
    0xFF6200EEL, 0xFF3700B3L, 0xFF03DAC5L, 0xFF018786L, // Material Components template
    0xFF6750A4L, 0xFFD0BCFFL, 0xFF625B71L, 0xFF7D5260L, // Material 3 baseline
    0xFF3F51B5L, 0xFF303F9FL, 0xFFFF4081L, // older Android Studio template
    0xFF008577L, 0xFF00574BL, 0xFFD81B60L, // Android Studio 3.x template
    0xFF009688L, 0xFF00796BL, // framework Material default accent
    0xFF607D8BL, 0xFF455A64L, // blue grey
)

/** CIELAB chroma below which a colour is a grey with a tint. */
private const val MIN_CHROMA = 16f

/** Tones outside this range are black or white with a tint. */
private const val MIN_TONE = 12f
private const val MAX_TONE = 94f

/** Two candidates closer than this in hue and in tone are the same colour. */
private const val SAME_HUE = 18f
private const val SAME_TONE = 16f

/** Asks for more chroma than any hue has; the gamut search brings it down. */
private const val VIVID = 130f

/** The tone a style's swatch is drawn at: a light scheme's primary. */
private const val SWATCH_TONE = 40

private const val OPAQUE = 0xFF000000L
private const val GAMUT_STEPS = 14
private const val GAMUT_SLACK = 1e-4
private const val EPSILON = 216.0 / 24389.0
private const val KAPPA = 24389.0 / 27.0
private const val WHITE_X = 0.95047
private const val WHITE_Z = 1.08883
