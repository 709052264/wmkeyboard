package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * "Add to home screen" for a tool with a screen of its own in this app (#970):
 * the clock, the notes, the to-do list. Asks the launcher to pin a shortcut
 * that opens [route] straight away, the way the four long-press shortcuts on
 * the app icon do for theirs.
 *
 * A tool that lives in the keyboard panel gets [ToolHomeScreenShortcutRow]
 * instead, which brings the keyboard up over a text box of its own.
 */
@Composable
internal fun HomeScreenShortcutRow(@StringRes label: Int, route: String) {
    val context = LocalContext.current
    val name = stringResource(label)
    ActionRow(
        R.string.home_shortcut_add_title,
        subtitle = stringResource(R.string.home_shortcut_add_subtitle, name),
        action = stringResource(R.string.home_shortcut_add_action),
    ) {
        // The same address the app icon's own shortcuts carry, read by
        // SettingsDeepLink on the way in.
        pinShortcut(
            context,
            "tool_$route",
            name,
            Intent(Intent.ACTION_VIEW, Uri.parse("wmkeyboard://settings/$route"))
                .setClassName(context, "com.wasimaster.wmkeyboard.app.MainActivity"),
        )
    }
}

/**
 * "Add to home screen" for any tool with a panel (#970): the level, the
 * compass, the calculator. The icon opens [ToolShortcutActivity], whose text
 * box brings the keyboard up with the tool open, so the tool stands in for an
 * app of its own.
 */
@Composable
internal fun ToolHomeScreenShortcutRow(tool: ToolbarTool) {
    val context = LocalContext.current
    val name = stringResource(toolTitle(tool))
    ActionRow(
        R.string.home_shortcut_add_title,
        subtitle = stringResource(R.string.home_shortcut_tool_subtitle, name),
        action = stringResource(R.string.home_shortcut_add_action),
    ) {
        pinShortcut(context, "panel_${tool.name}", name, ToolShortcutActivity.intent(context, tool))
    }
}

/** Asks the launcher to pin [intent] as [name], or says it cannot. */
private fun pinShortcut(context: Context, id: String, name: String, intent: Intent) {
    val shortcut = ShortcutInfoCompat.Builder(context, id)
        .setShortLabel(name)
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_tools))
        .setIntent(intent)
        .build()
    val asked = ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
        runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
    if (!asked) Toast.makeText(context, R.string.home_shortcut_unsupported, Toast.LENGTH_SHORT).show()
}
