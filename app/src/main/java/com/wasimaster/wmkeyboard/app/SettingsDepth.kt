package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth

/*
 * Settings depth: how much of the settings app is shown.
 *
 * Every row carries a [SettingLevel]; the depth in force ([SettingsDepth],
 * stored in `AppUiSettings.settingsDepth` and resolved by the nav host)
 * decides which levels are drawn. A row above the depth is not dropped:
 * [SettingsGroup] moves it behind a "N more settings" row at the end of its
 * group ([MoreSettingsRow]), and a page that folded anything says so under
 * its last group ([TuckedFooter]). The depth is changed from Home
 * ([DepthRow]), that footer, or Accessibility, all through the one sheet
 * ([DepthPickerSheet]).
 *
 * The point is parity with the keyboards most people come from: at Simple
 * the app shows about what they offer, and the rest is one press away rather
 * than gone. Nothing here changes what the keyboard does.
 */

/** How far down the settings depth a row sits. Untagged rows are [BASIC]. */
internal enum class SettingLevel {
    /**
     * Shown at every depth: what other keyboards offer, a feature's master
     * switch, or the list a page exists for.
     */
    BASIC,

    /** Shown at Standard and Everything: a choice a non-technical person could still want. */
    STANDARD,

    /** Shown at Everything only: timings, thresholds, multipliers, per-variant overrides. */
    ADVANCED,
}

/** Whether a row of [level] is drawn at this depth. */
internal fun SettingsDepth.shows(level: SettingLevel): Boolean = when (this) {
    SettingsDepth.SIMPLE -> level == SettingLevel.BASIC
    SettingsDepth.STANDARD -> level != SettingLevel.ADVANCED
    // AUTO never reaches a screen: the nav host publishes the resolved depth.
    SettingsDepth.EVERYTHING, SettingsDepth.AUTO -> true
}

/** The depth in force and the way to change it, published for every screen. */
@Stable
internal class DepthControl(val depth: SettingsDepth, val change: (SettingsDepth) -> Unit)

internal val LocalSettingsDepth = compositionLocalOf { DepthControl(SettingsDepth.EVERYTHING) {} }

/**
 * What one screen has folded away at the current depth, by group: each group
 * reports its own count under its "more settings" id, and the footer sums them.
 */
@Stable
internal class ScreenTuck {
    private val counts = mutableStateMapOf<String, Int>()

    val total: Int get() = counts.values.sum()

    fun set(id: String, count: Int) {
        counts[id] = count
    }

    fun remove(id: String) {
        counts.remove(id)
    }
}

internal val LocalScreenTuck = compositionLocalOf<ScreenTuck?> { null }

/** The depth's name, as the sheet and the rows say it. */
@Composable
internal fun depthName(depth: SettingsDepth): String = stringResource(
    when (depth) {
        SettingsDepth.SIMPLE -> R.string.settings_depth_simple
        SettingsDepth.STANDARD, SettingsDepth.AUTO -> R.string.settings_depth_standard
        SettingsDepth.EVERYTHING -> R.string.settings_depth_everything
    },
)

/** One line on what the depth shows. */
@Composable
internal fun depthDescription(depth: SettingsDepth): String = stringResource(
    when (depth) {
        SettingsDepth.SIMPLE -> R.string.settings_depth_simple_desc
        SettingsDepth.STANDARD, SettingsDepth.AUTO -> R.string.settings_depth_standard_desc
        SettingsDepth.EVERYTHING -> R.string.settings_depth_everything_desc
    },
)

/** The three depths a user can pick, named. */
@Composable
internal fun depthOptions(): List<Pair<SettingsDepth, String>> = listOf(
    SettingsDepth.SIMPLE to depthName(SettingsDepth.SIMPLE),
    SettingsDepth.STANDARD to depthName(SettingsDepth.STANDARD),
    SettingsDepth.EVERYTHING to depthName(SettingsDepth.EVERYTHING),
)

/** The one sheet every depth control opens. */
@Composable
internal fun DepthPickerSheet(control: DepthControl, onDismiss: () -> Unit) {
    ChoiceSheet(
        title = stringResource(R.string.settings_depth_title),
        subtitle = stringResource(R.string.settings_depth_sheet_subtitle),
        options = depthOptions(),
        selected = control.depth,
        detail = { ChoiceDetail(depthDescription(it)) },
        onPick = {
            control.change(it)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/**
 * Home's row: the depth in force, and the sheet to change it. First on the
 * list on purpose — when a row someone is looking for is not on Home, this is
 * where the answer is.
 */
@Composable
internal fun DepthRow() {
    val control = LocalSettingsDepth.current
    var sheet by remember { mutableStateOf(false) }
    SettingsGroup {
        item {
            NavRow(
                R.string.settings_depth_title,
                stringResource(R.string.settings_depth_subtitle),
                value = depthName(control.depth),
            ) { sheet = true }
        }
    }
    if (sheet) DepthPickerSheet(control) { sheet = false }
}

/**
 * The row at the end of a group that holds the rows above the depth. One row
 * rather than a heading: it sits in the group's own card stack, and the rows
 * it opens grow in under it, so the group stays one object.
 */
@Composable
internal fun MoreSettingsRow(count: Int, open: Boolean, onToggle: () -> Unit) {
    val depth = LocalSettingsDepth.current.depth
    WmRow(
        title = pluralStringResource(R.plurals.settings_depth_more_rows, count, count),
        subtitle = stringResource(R.string.settings_depth_more_subtitle, depthName(depth)),
        icon = Icons.Outlined.Tune,
        trailing = { ExpandChevron(open) },
        onClick = onToggle,
    )
}

/**
 * Under a page's last group: how much the page folded away, and the way to
 * change the depth. Nothing while the page shows everything it has.
 */
@Composable
internal fun TuckedFooter(tuck: ScreenTuck) {
    val total = tuck.total
    if (total == 0) return
    val control = LocalSettingsDepth.current
    var sheet by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.settings_depth_footer, total, total, depthName(control.depth)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { sheet = true }) { Text(stringResource(R.string.settings_depth_change)) }
    }
    if (sheet) DepthPickerSheet(control) { sheet = false }
}
