package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DriveFileRenameOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RestartAlt
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.ui.layoutSwitchLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The name the keyboard gives [layoutId] (spacebar, switch preview, hold-drag
 * picker), as [layoutSwitchLabel] works it out, with the user's own name left
 * out when [automatic].
 */
private fun keyboardName(s: KeyboardSettings, layoutId: String, automatic: Boolean = false): String =
    layoutSwitchLabel(
        layoutId,
        s.enabledLayoutIds,
        s.customLayouts,
        s.layoutBehavior.spacebarDisplay,
        if (automatic) emptyMap() else s.layoutDisplayNames,
    )

/**
 * One switched-on layout's name on the keyboard, under a language's layout
 * cards. A tap opens [LayoutNameDialog]; the swipe holds the same rename and,
 * once a name is set, the way back to the automatic one, which the dialog
 * offers too.
 *
 * The cards above are toggles from edge to edge, so the rename lives on a row
 * of its own rather than on a card: a long-press there would be the only way
 * in, and one nobody would find.
 */
@Composable
internal fun LayoutNameRow(
    layoutId: String,
    settings: LiveSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    val layoutName = settings.watch { resolveLayout(it.customLayouts, layoutId).name }
    val shown = settings.watch { keyboardName(it, layoutId) }
    val named = settings.watch { layoutId in it.layoutDisplayNames }
    val renameLabel = stringResource(R.string.languages_layout_name_rename_action)
    val resetLabel = stringResource(R.string.languages_layout_name_reset_action)
    WmRow(
        title = stringResource(R.string.languages_layout_name_title, layoutName),
        subtitle = stringResource(R.string.languages_layout_name_subtitle, shown),
        leading = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
        swipeActions = listOfNotNull(
            SwipeAction(Icons.Outlined.DriveFileRenameOutline, renameLabel) { renaming = true },
            if (named) {
                SwipeAction(Icons.Outlined.RestartAlt, resetLabel) {
                    scope.launch { repository.setLayoutDisplayName(layoutId, "") }
                }
            } else {
                null
            },
        ),
        onClick = { renaming = true },
    )
    if (renaming) {
        LayoutNameDialog(
            layoutName = layoutName,
            current = settings.value.layoutDisplayNames[layoutId].orEmpty(),
            automatic = keyboardName(settings.value, layoutId, automatic = true),
            onDismiss = { renaming = false },
            onSave = { name ->
                renaming = false
                scope.launch { repository.setLayoutDisplayName(layoutId, name) }
            },
        )
    }
}

/**
 * Asks for the name a layout goes by on the keyboard. The automatic name sits
 * in the empty field as its placeholder, so clearing the field reads as what
 * it does; "Use the automatic name" is the same thing in one tap, shown only
 * while there is a name of the user's to drop.
 */
@Composable
private fun LayoutNameDialog(
    layoutName: String,
    current: String,
    automatic: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.languages_layout_name_title, layoutName)) },
        text = {
            Column {
                Text(stringResource(R.string.languages_layout_name_body))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.languages_layout_name_field_label)) },
                    placeholder = { Text(automatic) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.End) {
                if (current.isNotEmpty()) {
                    TextButton(onClick = { onSave("") }) {
                        Text(stringResource(R.string.languages_layout_name_reset_action))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
                TextButton(onClick = { onSave(text) }) {
                    Text(stringResource(CommonR.string.common_save))
                }
            }
        },
    )
}
