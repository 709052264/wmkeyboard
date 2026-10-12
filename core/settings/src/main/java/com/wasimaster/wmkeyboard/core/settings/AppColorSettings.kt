package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.AppColorSample
import com.wasimaster.wmkeyboard.core.theme.AppColorSource
import com.wasimaster.wmkeyboard.core.theme.PaletteStyle
import com.wasimaster.wmkeyboard.core.theme.WallpaperPalette
import com.wasimaster.wmkeyboard.core.theme.appPalette

/** Which themes take the colours of the app being typed into. */
enum class AppColorScope {
    /**
     * The themes that already take their colours from outside: Default
     * (system), and any theme with Follow wallpaper colors on. A theme built
     * around fixed colours keeps them.
     */
    FOLLOWING,

    /** Every theme, fixed palettes included. */
    ALL,
}

/**
 * The keyboard dressing itself in the colours of the app it is typing into
 * (issue #592), the way AnySoftKeyboard's "Apply remote app colors" does.
 *
 * The app's colours are read when the keyboard comes up in it (see
 * `AppColorProbe`), grown into a whole palette in [style], and laid over the
 * theme on the way to the screen, the same way a wallpaper-following theme is
 * moved onto the wallpaper. Nothing is written into a theme.
 *
 * Nested for the `KeyboardSettings` field ceiling; the DataStore keys stay
 * flat, `app_colors_*`.
 */
data class AppColorSettings(
    val enabled: Boolean = false,
    val scope: AppColorScope = AppColorScope.FOLLOWING,
    val source: AppColorSource = AppColorSource.AUTO,
    val style: PaletteStyle = PaletteStyle.TONAL_SPOT,
    /**
     * Per app, which of its colours to use: an index into
     * [AppColorSample.candidates], or [PICK_OFF] for an app that should keep
     * the plain theme. An app with no entry uses its first colour, so the map
     * only ever holds the apps somebody changed.
     */
    val pickByPackage: Map<String, Int> = emptyMap(),
) {

    /** The index chosen for [packageName]: 0 unless somebody picked another. */
    fun pickFor(packageName: String): Int = pickByPackage[packageName] ?: 0

    /**
     * The palette the keyboard should wear in the app [sample] describes, or
     * null when it should wear its theme as it is: the feature is off, the app
     * is switched off, or the app offered no colour worth using.
     *
     * A remembered pick past the end of the list (the app changed its icon)
     * falls back to the first colour rather than to nothing.
     */
    fun paletteFor(sample: AppColorSample?): WallpaperPalette? {
        if (!enabled || sample == null) return null
        val pick = pickFor(sample.packageName)
        if (pick == PICK_OFF) return null
        val candidates = sample.candidates(source)
        val seed = candidates.getOrNull(pick) ?: candidates.firstOrNull() ?: return null
        return appPalette(seed, style)
    }

    companion object {
        /** A [pickByPackage] value that leaves the app on the plain theme. */
        const val PICK_OFF = -1
    }
}

/** `pkg=index;pkg=index`, the same shape the per-app layout map is stored in. */
internal fun encodeAppColorPicks(map: Map<String, Int>): String =
    map.entries
        .filter { it.key.isNotEmpty() }
        .joinToString(";") { (pkg, pick) -> "$pkg=$pick" }

internal fun decodeAppColorPicks(raw: String): Map<String, Int> =
    raw.split(';')
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val eq = entry.lastIndexOf('=')
            if (eq <= 0) return@mapNotNull null
            val pick = entry.substring(eq + 1).toIntOrNull() ?: return@mapNotNull null
            entry.substring(0, eq) to pick
        }
        .toMap()
