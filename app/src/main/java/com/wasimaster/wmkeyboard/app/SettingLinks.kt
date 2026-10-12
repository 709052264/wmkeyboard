package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.settings.SettingLinkMode
import java.net.URLEncoder

/*
 * Links to one setting, copied from the setting itself (#646).
 *
 * The documentation site's link builder already writes these, but only for
 * someone who goes there and finds the row in a list. Here the row the user is
 * looking at hands over its own address: on a long press, or from a small
 * button beside its reset and its "?", after **About › Documentation ›
 * Setting links**. What is copied is the site's `/open/` web address around a
 * `wmkeyboard://` link, the form the builder calls "Web address (https)": a
 * chat app makes that tappable where it would leave the bare scheme as text,
 * and this app claims the page as an App Link, so the tap lands here.
 */

/** Where a row's link points: the screen, when it has one a link can name, and the row's resource name. */
@Immutable
internal data class SettingLinkTarget(val route: String?, val name: String)

internal object SettingLinks {

    /**
     * The `wmkeyboard://` link to the row [name] on [route].
     *
     * A row whose screen no link can name, or whose screen is not known, gets
     * the setting host instead, which finds the screen through the search
     * index. [version] becomes `since=`, so a copy of the app too old to have
     * the row can say which version does rather than open nothing; it is left
     * out when it is not a plain version number, as a debug build's may not
     * be, because [SettingsDeepLink] would refuse it anyway.
     */
    fun appLink(route: String?, name: String, version: String): String {
        val since = version.takeIf { it.matches(VERSION) }
            ?.let { "${SettingsDeepLink.SINCE_PARAM}=$it" }
        val screen = route?.takeIf { it.isNotEmpty() && !it.contains('{') }
            ?.let { SettingsRoutes.resolve(it) }
        return if (screen != null) {
            val params = listOfNotNull("${SettingsDeepLink.SETTING_PARAM}=$name", since)
            "${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SCREEN_HOST}/$screen?${params.joinToString("&")}"
        } else {
            "${SettingsDeepLink.SCHEME}://${SettingsDeepLink.SETTING_HOST}/$name" + since?.let { "?$it" }.orEmpty()
        }
    }

    /** [appLink] inside the documentation site's `/open/` page; see [WebOpenLink]. */
    fun webLink(appLink: String): String = "$DOCS_URL${WebOpenLink.PATH}/#$appLink"

    /**
     * The route a back stack entry is showing, with its arguments filled in:
     * `tool/CLIPBOARD` for `tool/{tool}`. [argument] reads one argument by
     * name. Null when an argument is missing, since a route with a hole in it
     * names no screen.
     *
     * Arguments are percent-encoded, which is the form [SettingsRoutes.resolve]
     * keeps and `navigate()` decodes from.
     */
    fun concreteRoute(pattern: String?, argument: (String) -> String?): String? {
        if (pattern.isNullOrEmpty()) return null
        var missing = false
        val route = ARGUMENT.replace(pattern) { match ->
            val value = argument(match.groupValues[1])
            if (value == null) {
                missing = true
                ""
            } else {
                URLEncoder.encode(value, "UTF-8").replace("+", "%20")
            }
        }
        return route.takeUnless { missing }
    }

    private val VERSION = Regex("[0-9]{1,4}(\\.[0-9]{1,6}){0,3}")
    private val ARGUMENT = Regex("\\{([^}]+)\\}")
}

/**
 * What the settings app needs to answer "what is this row's link": the mode
 * the user picked, and a way to name a row that only has its drawn title.
 *
 * Published at the settings root as [LocalSettingLinks], and null while the
 * mode is [SettingLinkMode.OFF], so a row asks one null check and nothing else
 * when the feature is off.
 */
@Stable
internal class SettingLinkContext(
    val mode: SettingLinkMode,
    private val resources: Resources,
) {
    /**
     * The search index by title, built the first time a row without its own
     * resource asks. A group heading is the usual one: most groups are named
     * with a plain string, and the index is the only place that knows which
     * resource that string came from.
     */
    private val byTitle: Map<String, List<SettingsSearchEntry>> by lazy {
        settingsSearchIndex(resources).groupBy { it.title }
    }

    /** Every row name the index knows, for a link that has no screen to name. */
    private val indexed: Set<String> by lazy {
        byTitle.values.flatten().mapTo(HashSet()) { it.key.substringAfter('#') }
    }

    /**
     * The link target of a row on [route], named by its title resource [key]
     * or, failing that, by its drawn [title]. Null for a row nothing can name:
     * a language, a layout, anything the user made.
     */
    fun target(route: String?, title: String?, @StringRes key: Int): SettingLinkTarget? {
        val screen = route?.takeIf { SettingsRoutes.resolve(it) != null }
        if (key != 0) {
            val name = runCatching { resources.getResourceEntryName(key) }.getOrNull() ?: return null
            // Without a screen the link leans on the index, so the row has to
            // be in it, or the link would open a "needs a newer version" prompt
            // for a row this very version has.
            if (screen == null && name !in indexed) return null
            return SettingLinkTarget(screen, name)
        }
        if (title.isNullOrEmpty()) return null
        val entries = byTitle[title].orEmpty()
        val pattern = SettingsRoutes.patternOf(screen)
        val here = entries.firstOrNull { it.route == screen || (it.screenPattern != null && it.screenPattern == pattern) }
        if (here != null) return SettingLinkTarget(screen, here.key.substringAfter('#'))
        // Indexed somewhere else under one name: the setting host finds it.
        val names = entries.map { it.key.substringAfter('#') }.distinct()
        return names.singleOrNull()?.let { SettingLinkTarget(null, it) }
    }
}

/** See [SettingLinkContext]. Null outside the settings host and while the feature is off. */
internal val LocalSettingLinks = compositionLocalOf<SettingLinkContext?> { null }

/** The [SettingLinkContext] the settings root publishes for [mode]. */
@Composable
internal fun rememberSettingLinks(mode: SettingLinkMode): SettingLinkContext? {
    val resources = LocalContext.current.resources
    return remember(mode, resources) {
        if (mode == SettingLinkMode.OFF) null else SettingLinkContext(mode, resources)
    }
}

/**
 * The row a link button or a long press below this point belongs to, as
 * [HighlightableRow] knows it: the drawn title and its resource. Null where
 * no row has claimed the space.
 */
@Immutable
internal data class RowLinkKey(val title: String?, @StringRes val key: Int)

internal val LocalRowLinkKey = compositionLocalOf<RowLinkKey?> { null }

/** The route the screen being drawn is showing, arguments filled in. */
@Composable
internal fun currentLinkRoute(): String? {
    val entry = currentCrumbEntry() ?: return null
    return remember(entry) {
        val args: Bundle? = entry.arguments
        SettingLinks.concreteRoute(entry.destination.route) { name ->
            @Suppress("DEPRECATION")
            args?.get(name)?.toString()
        }
    }
}

/** The link target of the row around the caller, or null when it has none or the feature is off. */
@Composable
internal fun currentRowLink(): SettingLinkTarget? {
    val links = LocalSettingLinks.current ?: return null
    val row = LocalRowLinkKey.current ?: return null
    val route = currentLinkRoute()
    return remember(links, row, route) { links.target(route, row.title, row.key) }
}

/** Puts [target]'s web link on the clipboard and says so where the system does not. */
internal fun copySettingLink(context: Context, target: SettingLinkTarget) {
    val link = SettingLinks.webLink(SettingLinks.appLink(target.route, target.name, BuildConfig.VERSION_NAME))
    val copied = runCatching {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(link, link))
    }.isSuccess
    // Android 13 and later show their own confirmation for every copy.
    if (copied && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.about_setting_link_copied, Toast.LENGTH_SHORT).show()
    }
}

/** The link button's tap target: the reset control's size, for the same reason. */
internal val SettingLinkTargetSize = 36.dp

/**
 * The small link button a row shows beside its reset and its "?" while
 * **Setting links** is on Button. Draws nothing in any other mode, or on a row
 * that has no link. [name] is the row's own name, for the content description.
 */
@Composable
internal fun SettingLinkButton(name: String) {
    if (LocalSettingLinks.current?.mode != SettingLinkMode.BUTTON) return
    val target = currentRowLink() ?: return
    val context = LocalContext.current
    IconButton(
        onClick = { copySettingLink(context, target) },
        modifier = Modifier.size(SettingLinkTargetSize),
    ) {
        Icon(
            Icons.Outlined.Link,
            contentDescription = stringResource(R.string.about_setting_link_desc, name),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Whether the link button is drawn on rows, for the rows that budget their own width. */
@Composable
internal fun settingLinkButtonShown(): Boolean = LocalSettingLinks.current?.mode == SettingLinkMode.BUTTON

/**
 * Copies the row's link on a long press while **Setting links** is on Hold.
 *
 * The row's own click stays its own. The press is watched in the main pass,
 * after the row's controls have seen it, and given up the moment one of them
 * takes it — a slider starting to drag, a field selecting text — or the
 * finger moves or lifts. Only once the hold has fired does this step in front
 * of the row, taking the rest of the gesture in the initial pass so the lift
 * that ends a hold is not also read as a tap.
 */
@Composable
internal fun Modifier.settingLinkHold(): Modifier {
    if (LocalSettingLinks.current?.mode != SettingLinkMode.HOLD) return this
    val target = currentRowLink() ?: return this
    val context = LocalContext.current
    val view = LocalView.current
    val config = LocalViewConfiguration.current
    return pointerInput(target) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // The scope's own timeout: the gesture scope restricts suspension,
            // so the coroutines one cannot be called from in here.
            val held = withTimeoutOrNull(config.longPressTimeoutMillis) {
                var givenUp = false
                while (!givenUp) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    givenUp = change == null || !change.pressed || change.isConsumed ||
                        (change.position - down.position).getDistance() > config.touchSlop
                }
                false
            } == null
            if (!held) return@awaitEachGesture
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            copySettingLink(context, target)
            // The rest of this gesture is the hold's, not the row's.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}
