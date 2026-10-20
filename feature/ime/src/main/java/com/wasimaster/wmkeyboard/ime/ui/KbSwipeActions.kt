package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.ui.SwipeAction
import com.wasimaster.wmkeyboard.core.ui.SwipeActionColors
import com.wasimaster.wmkeyboard.core.ui.SwipeActionsRow
import com.wasimaster.wmkeyboard.core.ui.SwipeTone

/** Delete behind a keyboard panel's row, in red whatever the theme. */
internal fun kbDeleteSwipeAction(label: String, onDelete: () -> Unit) =
    SwipeAction(Icons.Outlined.Delete, label, SwipeTone.DESTRUCTIVE, onDelete)

/**
 * A panel row (a chat, a note, a countdown) that slides toward the start to
 * show [actions] behind it, in the keyboard's own colours rather than the
 * settings app's Material scheme, which a black theme would clash with.
 *
 * The buttons are a size down from the settings app's: a panel's rows are
 * shorter than a settings row and the keyboard is a short window. Delete
 * keeps a fixed red, since a theme has no colour that means "this goes".
 * As in settings, every action here must also be on the row or behind its
 * hold, since a swipe is invisible to TalkBack.
 */
@Composable
internal fun KbSwipeActionsRow(
    actions: List<SwipeAction>,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    fullSwipe: SwipeAction? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val kb = LocalKbTheme.current
    val haptics = LocalHapticFeedback.current
    SwipeActionsRow(
        actions = actions,
        colors = SwipeActionColors(
            neutral = kb.chip,
            onNeutral = kb.chipText,
            accent = kb.chipActive,
            onAccent = kb.chipActiveText,
            destructive = if (kb.dark) KbDeleteDark else KbDeleteLight,
            onDestructive = if (kb.dark) KbOnDeleteDark else KbOnDeleteLight,
        ),
        modifier = modifier,
        fullSwipe = fullSwipe,
        enabled = enabled,
        reduceMotion = reduceMotion,
        onTick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
        buttonSize = 36.dp,
        slotWidth = 44.dp,
        content = content,
    )
}

// Material's error-container pair, which reads on both a light and a dark board.
private val KbDeleteLight = Color(0xFFFFDAD6)
private val KbOnDeleteLight = Color(0xFF410002)
private val KbDeleteDark = Color(0xFF8C1D18)
private val KbOnDeleteDark = Color(0xFFFFDAD6)
