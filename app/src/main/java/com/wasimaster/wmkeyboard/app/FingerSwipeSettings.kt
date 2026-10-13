package com.wasimaster.wmkeyboard.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.FingerSwipe
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.SwipeDirection
import com.wasimaster.wmkeyboard.core.layout.actionOf
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.LetterSwipeAction
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import kotlinx.coroutines.launch

/**
 * What a multi-finger swipe may be bound to (issue #647): any key action, bar
 * the ones that only mean something as a held key in a chord engine.
 */
private val FingerSwipeActionCatalog: List<KeyActionOption> =
    KeyActionCatalog.filterNot { option ->
        val action = option.build()
        action is KeyAction.BrailleDot || action == KeyAction.MorseDot || action == KeyAction.MorseDash
    }

/**
 * The multi-finger swipes group of the gestures page (issue #647): the switch,
 * then one row per finger count and direction, each opening the layout
 * editor's action picker and, for an action that names something, the same
 * follow-up picker a key's action gets.
 */
@Composable
internal fun FingerSwipeSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.gesture.multiFingerSwipes }
    // Two-finger swipes stand aside while the two-thumb loop runs.
    val thumbsGlide = settings.watch {
        it.gestureTyping && it.letterSwipeAction == LetterSwipeAction.TYPE_WORDS && it.gesture.twoThumbGlide
    }
    val bound = settings.watch { it.gesture.fingerSwipes }
    val layouts = secondaryLayouts(settings.watch { it.customLayouts })
    // The slot whose action is being picked, then the follow-up it needs.
    var picking by remember { mutableStateOf<FingerSwipe?>(null) }
    var followUp by remember { mutableStateOf<Pair<FingerSwipe, KeyAction>?>(null) }
    fun bind(swipe: FingerSwipe, binding: KeyAlternate?) {
        scope.launch { repository.setFingerSwipe(swipe, binding) }
    }
    SettingsGroup(stringResource(R.string.typing_group_finger_swipes_title)) {
        item {
            ToggleSetting(
                R.string.typing_finger_swipes_title,
                stringResource(R.string.typing_finger_swipes_subtitle),
                on,
                info = stringResource(R.string.typing_finger_swipes_info),
                default = SettingsDefaults.gesture.multiFingerSwipes,
            ) { scope.launch { repository.setMultiFingerSwipes(it) } }
        }
        for (swipe in FingerSwipe.all) {
            item(visible = on, key = swipe.token) {
                val binding = bound.actionOf(swipe)
                NavRow(
                    title = stringResource(
                        R.string.typing_finger_swipe_row_title,
                        swipe.fingers,
                        stringResource(directionLabel(swipe.direction)),
                    ),
                    subtitle = if (swipe.fingers == 2 && thumbsGlide) {
                        stringResource(R.string.typing_finger_swipe_two_thumb_subtitle)
                    } else {
                        null
                    },
                    value = binding?.let { fingerSwipeBindingName(it, layouts) }
                        ?: stringResource(R.string.typing_finger_swipe_nothing_value),
                ) { picking = swipe }
            }
        }
        item(visible = on && bound.isNotEmpty()) {
            NavRow(
                R.string.typing_finger_swipes_reset_title,
                stringResource(R.string.typing_finger_swipes_reset_subtitle),
            ) {
                scope.launch { FingerSwipe.all.forEach { repository.setFingerSwipe(it, null) } }
            }
        }
    }

    picking?.let { swipe ->
        KeyActionPickerDialog(
            current = bound.actionOf(swipe)?.action ?: KeyAction.None,
            options = FingerSwipeActionCatalog,
            onPick = { action ->
                picking = null
                // Half an answer until it names its text, tool, layout or keyboard.
                val needsMore = action == KeyAction.Text || action is KeyAction.Tool ||
                    action is KeyAction.Edit || action is KeyAction.Layout ||
                    action is KeyAction.SwitchInputMethod || action is KeyAction.Broadcast
                if (needsMore) followUp = swipe to action else bind(swipe, KeyAlternate(action))
            },
            onDismiss = { picking = null },
        )
    }

    followUp?.let { (swipe, action) ->
        val done = { followUp = null }
        when (action) {
            is KeyAction.Tool -> ToolPickerDialog(
                title = stringResource(R.string.layout_editor_tool_picker_title),
                current = (bound.actionOf(swipe)?.action as? KeyAction.Tool)?.tool,
                options = ToolbarTool.entries.filter(::isSupportedTool),
                onDismiss = done,
                onPick = { picked ->
                    done()
                    picked?.let { bind(swipe, KeyAlternate(KeyAction.Tool(it))) }
                },
            )
            is KeyAction.Edit -> TextEditActionPickerDialog(
                current = (bound.actionOf(swipe)?.action as? KeyAction.Edit)?.op,
                onDismiss = done,
                onPick = { op ->
                    done()
                    bind(swipe, KeyAlternate(KeyAction.Edit(op)))
                },
            )
            is KeyAction.Layout -> SecondaryLayoutPickerDialog(
                current = (bound.actionOf(swipe)?.action as? KeyAction.Layout)?.id,
                options = layouts,
                onDismiss = done,
                onPick = { picked ->
                    done()
                    bind(swipe, KeyAlternate(KeyAction.Layout(picked.id), label = picked.name))
                },
            )
            is KeyAction.SwitchInputMethod -> InputMethodPickerDialog(
                current = (bound.actionOf(swipe)?.action as? KeyAction.SwitchInputMethod)?.id,
                onDismiss = done,
                onPick = { id, name ->
                    done()
                    bind(swipe, KeyAlternate(KeyAction.SwitchInputMethod(id), label = name))
                },
            )
            is KeyAction.Broadcast -> FingerSwipeTextDialog(
                title = stringResource(R.string.typing_finger_swipe_broadcast_title),
                initial = (bound.actionOf(swipe)?.action as? KeyAction.Broadcast)?.action.orEmpty(),
                onDismiss = done,
                onDone = { text ->
                    done()
                    bind(swipe, KeyAlternate(KeyAction.Broadcast(text)))
                },
            )
            else -> FingerSwipeTextDialog(
                title = stringResource(R.string.typing_finger_swipe_text_title),
                initial = bound.actionOf(swipe)?.takeIf { it.action == KeyAction.Text }?.label.orEmpty(),
                onDismiss = done,
                onDone = { text ->
                    done()
                    bind(swipe, KeyAlternate(KeyAction.Text, label = text))
                },
            )
        }
    }
}

/** What a row says its swipe does: the typed text in quotes, else the action's name. */
@Composable
private fun fingerSwipeBindingName(binding: KeyAlternate, layouts: List<LayoutSpec>): String =
    when (binding.action) {
        KeyAction.Text -> stringResource(R.string.typing_finger_swipe_text_value, binding.label)
        is KeyAction.SwitchInputMethod -> binding.label.ifBlank { actionAlternateName(binding, layouts) }
        else -> actionAlternateName(binding, layouts)
    }

private fun directionLabel(direction: SwipeDirection): Int = when (direction) {
    SwipeDirection.UP -> R.string.typing_finger_swipe_up
    SwipeDirection.DOWN -> R.string.typing_finger_swipe_down
    SwipeDirection.LEFT -> R.string.typing_finger_swipe_left
    SwipeDirection.RIGHT -> R.string.typing_finger_swipe_right
}

/** One line of text for a swipe that types it, or the action a broadcast sends. */
@Composable
private fun FingerSwipeTextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = text.isNotEmpty()) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
