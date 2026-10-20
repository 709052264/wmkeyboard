package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries

/**
 * The Yomichan / Yomitan dictionaries the user imported (#765): a row per
 * dictionary with its switch, its place in the order and its Delete, then the
 * import row. The Dictionary tool's source list asks them as one source,
 * "Imported dictionaries", wherever the user puts it; within it they are
 * asked in this order, and every one that knows the word answers.
 */
@Composable
internal fun ImportedDictionariesGroup() {
    val context = LocalContext.current
    val version by YomichanDictionaries.changes.collectAsState()
    val installed = remember(version) {
        YomichanDictionaries.attach(context.filesDir)
        YomichanDictionaries.installed()
    }
    SettingsGroup(
        stringResource(R.string.tooldetail_dictionary_imported_group),
        info = stringResource(R.string.tooldetail_dictionary_imported_info),
    ) {
        installed.forEachIndexed { index, dictionary ->
            item(key = dictionary.info.id) {
                ImportedDictionaryRow(
                    dictionary = dictionary,
                    canMoveUp = index > 0,
                    canMoveDown = index < installed.lastIndex,
                    onMove = { by ->
                        val ids = installed.map { it.info.id }.toMutableList()
                        val moved = ids.removeAt(index)
                        ids.add((index + by).coerceIn(0, ids.size), moved)
                        YomichanDictionaries.reorder(ids)
                    },
                    onToggle = { YomichanDictionaries.setEnabled(dictionary.info.id, it) },
                    onDelete = { YomichanDictionaries.delete(dictionary.info.id) },
                )
            }
        }
        item {
            OfflineImportRow(
                title = stringResource(R.string.tooldetail_dictionary_imported_import_title),
                subtitle = stringResource(R.string.tooldetail_dictionary_imported_import_subtitle),
                links = listOf(OfflineLink(stringResource(R.string.tooldetail_dictionary_imported_link), YOMITAN_DICTIONARIES)),
            )
        }
    }
}

@Composable
private fun ImportedDictionaryRow(
    dictionary: YomichanDictionaries.Installed,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val info = dictionary.info
    val languages = listOf(info.sourceLanguage, info.targetLanguage).filter { it.isNotBlank() }.joinToString(" → ")
    val detail = listOf(
        pluralStringResource(
            R.plurals.tooldetail_dictionary_imported_entries,
            info.terms,
            java.text.NumberFormat.getIntegerInstance().format(info.terms),
        ),
        formatBytes(dictionary.sizeBytes),
        languages,
        info.revision,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    WmRow(
        title = info.title,
        supporting = { Text(if (info.attribution.isBlank()) detail else detail + "\n" + info.attribution, maxLines = 4) },
        swipeActions = buildList {
            if (canMoveUp) {
                add(
                    SwipeAction(
                        Icons.Outlined.ArrowUpward,
                        stringResource(R.string.tooldetail_dictionary_imported_up_desc, info.title),
                    ) { onMove(-1) },
                )
            }
            if (canMoveDown) {
                add(
                    SwipeAction(
                        Icons.Outlined.ArrowDownward,
                        stringResource(R.string.tooldetail_dictionary_imported_down_desc, info.title),
                    ) { onMove(1) },
                )
            }
            add(deleteSwipeAction(stringResource(R.string.tooldetail_dictionary_offline_delete_desc, info.title), onDelete))
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canMoveUp) {
                    IconButton(onClick = { onMove(-1) }) {
                        Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.tooldetail_dictionary_imported_up_desc, info.title))
                    }
                }
                if (canMoveDown) {
                    IconButton(onClick = { onMove(1) }) {
                        Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.tooldetail_dictionary_imported_down_desc, info.title))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.tooldetail_dictionary_offline_delete_desc, info.title))
                }
                Switch(checked = dictionary.enabled, onCheckedChange = onToggle)
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** Where the community's Yomitan dictionaries are listed. */
private const val YOMITAN_DICTIONARIES = "https://yomitan.wiki/dictionaries/"
