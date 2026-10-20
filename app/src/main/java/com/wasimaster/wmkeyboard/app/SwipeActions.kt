package com.wasimaster.wmkeyboard.app

import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.ui.SwipeActionColors
import com.wasimaster.wmkeyboard.core.ui.SwipeActionsRow as CoreSwipeActionsRow

// The row itself lives in :core:common so the keyboard's panels share it; this
// file is the settings app's dressing of it: Material colours, Reduce motion,
// the app's haptics.

internal typealias SwipeAction = com.wasimaster.wmkeyboard.core.ui.SwipeAction
internal typealias SwipeTone = com.wasimaster.wmkeyboard.core.ui.SwipeTone

/** The usual last action: delete, drawn in the error colours. */
internal fun deleteSwipeAction(label: String, onDelete: () -> Unit) =
    SwipeAction(Icons.Outlined.Delete, label, SwipeTone.DESTRUCTIVE, onDelete)

/**
 * A settings row that slides toward the start to show [actions] behind it.
 * See the `:core:common` row for the behaviour; every action here must also be
 * reachable on the row, its menu or the screen it opens, since a swipe is
 * invisible to TalkBack.
 */
@Composable
internal fun SwipeActionsRow(
    actions: List<SwipeAction>,
    modifier: Modifier = Modifier,
    fullSwipe: SwipeAction? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val haptics = rememberSettingsHaptics()
    val c = MaterialTheme.colorScheme
    CoreSwipeActionsRow(
        actions = actions,
        colors = SwipeActionColors(
            neutral = c.secondaryContainer,
            onNeutral = c.onSecondaryContainer,
            accent = c.primaryContainer,
            onAccent = c.onPrimaryContainer,
            destructive = c.errorContainer,
            onDestructive = c.onErrorContainer,
        ),
        modifier = modifier,
        fullSwipe = fullSwipe,
        enabled = enabled,
        reduceMotion = LocalReduceMotion.current,
        onTick = haptics::tick,
        content = content,
    )
}
