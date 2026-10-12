package com.wasimaster.wmkeyboard.core.theme

import android.content.Context
import android.os.Build
import android.util.TypedValue
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toBitmap

/**
 * The Android half of [AppColorSample]: looks at an app and writes down what
 * colours it is made of. Choosing among them is plain arithmetic next door.
 *
 * Two places are looked at:
 *
 * - **The launch screen's theme.** AnySoftKeyboard's approach, and the one
 *   that matches the app's toolbar exactly when it works: open the app's
 *   resources, apply the theme its launcher activity declares, read
 *   `colorPrimary` and friends. Both the framework's attributes and the app's
 *   own (AppCompat and Material Components define `colorPrimary` in the app's
 *   namespace, not Android's) are tried.
 * - **The launcher icon**, drawn small and run through the same histogram the
 *   photo palette uses ([seedsFrom]). Every app has one, including the Compose
 *   apps whose theme declares nothing at all.
 *
 * An input method can see the package it is typing into without a `<queries>`
 * entry, which is what makes both of these legal for exactly that app.
 *
 * Costs a few milliseconds, most of it the resource context, so the caller runs
 * it off the main thread and keeps the result per package.
 */
object AppColorProbe {

    fun sample(context: Context, packageName: String): AppColorSample? = runCatching {
        val excluded = deviceAccentColors(context)
        AppColorSample(
            packageName = packageName,
            themeColors = themeColors(context, packageName).filter { it !in excluded },
            iconColors = iconColors(context, packageName),
        )
    }.getOrNull()

    private fun themeColors(context: Context, packageName: String): List<Long> = runCatching {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName)?.component
        val activityTheme = launch
            ?.let { runCatching { pm.getActivityInfo(it, 0).themeResource }.getOrNull() }
            ?.takeIf { it != 0 }
        val themeRes = activityTheme ?: pm.getApplicationInfo(packageName, 0).theme
        if (themeRes == 0) return@runCatching emptyList()
        val remote = context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        remote.setTheme(themeRes)
        val attrs = buildList {
            // The app's own attributes first: when an app uses AppCompat or
            // Material Components, these are the ones it actually set, and the
            // framework's are the library's defaults.
            for (name in APP_ATTRS) {
                val id = remote.resources.getIdentifier(name, "attr", packageName)
                if (id != 0) add(id)
            }
            addAll(FRAMEWORK_ATTRS)
        }
        val value = TypedValue()
        attrs.mapNotNull { attr -> remote.colorAttr(attr, value) }.distinct()
    }.getOrDefault(emptyList())

    private fun Context.colorAttr(attr: Int, value: TypedValue): Long? {
        if (!theme.resolveAttribute(attr, value, true)) return null
        val argb = when {
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> runCatching { getColor(value.resourceId) }.getOrNull() ?: return null
            else -> return null
        }
        // A see-through colour is a scrim, not a brand colour.
        if ((argb ushr 24) < MIN_ALPHA) return null
        return argb.toLong() and 0xFFFFFFFFL
    }

    private fun iconColors(context: Context, packageName: String): List<Long> = runCatching {
        val icon = context.packageManager.getApplicationIcon(packageName)
        val bitmap = icon.toBitmap(ICON_SIZE, ICON_SIZE)
        val grid = PhotoSampling.gridOf(bitmap, ICON_SIZE)
        // toBitmap hands back the drawable's own bitmap when it already is one,
        // and that one belongs to the package manager's cache.
        if (bitmap !== (icon as? android.graphics.drawable.BitmapDrawable)?.bitmap) bitmap.recycle()
        grid?.let { seedsFrom(it, max = AppColorSample.MAX_CANDIDATES) }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * The wallpaper's colours. On Android 12 and later a theme that inherits
     * `DeviceDefault` resolves its accent to these, which would make every such
     * app "match" by showing the wallpaper again.
     */
    private fun deviceAccentColors(context: Context): Set<Long> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) systemAccents(context) else emptySet()

    @RequiresApi(Build.VERSION_CODES.S)
    private fun systemAccents(context: Context): Set<Long> =
        deviceWallpaperPalette(context)?.tones?.let { it.primary + it.secondary + it.tertiary }
            ?.toSet()
            .orEmpty()

    private val APP_ATTRS = listOf(
        "colorPrimary",
        "colorPrimaryDark",
        "colorPrimaryVariant",
        "colorSecondary",
        "colorAccent",
    )

    private val FRAMEWORK_ATTRS = listOf(
        android.R.attr.colorPrimary,
        android.R.attr.colorPrimaryDark,
        android.R.attr.statusBarColor,
        android.R.attr.colorAccent,
    )

    private const val ICON_SIZE = 64
    private const val MIN_ALPHA = 0xC0
}
