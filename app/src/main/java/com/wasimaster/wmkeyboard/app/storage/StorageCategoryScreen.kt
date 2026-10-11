package com.wasimaster.wmkeyboard.app.storage

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmIconTile
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.formatBytes
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One category, item by item.
 *
 * Deliberately generic: every category gets the same screen rather than
 * twenty-odd bespoke ones, because the question is always the same — what is in
 * here, how big is each of them, and can this one go. Where a category already
 * has a proper management screen (voice models, sticker packs, plugins) this
 * one links across to it instead of trying to be it.
 */
@Composable
internal fun StorageCategoryScreen(
    categoryId: String,
    repository: SettingsRepository,
    onOpenRoute: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val category = remember(categoryId) { StorageCategories.byId(categoryId) } ?: return
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmItem by remember { mutableStateOf<StorageItem?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    // Items on their way out: hidden the moment the deletion is confirmed, so
    // the row folds away while the files go rather than a beat later when the
    // list is read again. Cleared on that read — an item that is still there
    // (the deletion failed) comes back.
    var leaving by remember(categoryId) { mutableStateOf(emptySet<String>()) }
    val ledger = remember(categoryId) { RowLedger() }

    val items by produceState(emptyList<StorageItem>(), categoryId, revision) {
        value = withContext(Dispatchers.IO) {
            category.items(StorageEnv(context, repository, storageRoots(context)))
                .sortedByDescending { it.bytes }
        }
    }

    LaunchedEffect(items) { leaving = emptySet() }
    val rows = remember(items) { ledger.update(items) }

    fun perform(work: suspend (StorageEnv) -> Unit) {
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                work(StorageEnv(context, repository, storageRoots(context)))
            }
            // The Storage screen paints from the last measurement, so it has to
            // be told that measurement is now wrong — otherwise going back
            // shows the size of something that has just been deleted.
            StorageSnapshot.invalidate()
            busy = false
            revision++
        }
    }

    CaptionText(stringResource(category.subtitle))
    if (category.note != 0) CaptionText(stringResource(category.note))

    if (items.isEmpty()) {
        CaptionText(stringResource(R.string.storage_detail_empty))
    } else {
        // A leading slot on every row or on none of them. Thumbnails on the
        // picture rows and nothing on the JSON beside them would step the
        // headlines in and out down the column, which reads as a broken list
        // rather than as two kinds of thing.
        val slotted = items.any { it.preview != null }
        SettingsGroup {
            for (row in rows) {
                // A deleted item keeps its slot, hidden, so the group folds it
                // away in place and the rows under it close the gap.
                item(visible = row.present && row.item.id !in leaving) {
                    StorageItemRow(
                        item = row.item,
                        accent = category.accent,
                        slotted = slotted,
                        // Also off while it folds away: the fading row still
                        // takes a press.
                        enabled = !busy && row.item.id !in leaving,
                    ) { confirmItem = row.item }
                }
            }
        }
    }

    category.manageRoute?.let { route ->
        SettingsGroup {
            item {
                WmRow(
                    title = stringResource(R.string.storage_detail_manage_action),
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    accent = category.accent,
                    flightTo = route,
                    onClick = { onOpenRoute(route) },
                )
            }
        }
    }

    if (category.clearable && items.any { it.deletable }) {
        TextButton(
            onClick = { confirmAll = true },
            enabled = !busy,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.storage_delete_all_desc, stringResource(category.title)),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    confirmItem?.let { item ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_item_title, item.label),
            body = stringResource(bodyForItem(category.danger), formatBytes(item.bytes)),
            lock = AppLockTargets["action_storage_delete"],
            onDismiss = { confirmItem = null },
            onConfirm = {
                confirmItem = null
                leaving = leaving + item.id
                perform { env -> category.delete(env, item) }
            },
        )
    }

    if (confirmAll) {
        val total = items.filter { it.deletable }.sumOf { it.bytes }
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_confirm_title, stringResource(category.title)),
            body = stringResource(bodyForItem(category.danger), formatBytes(total)),
            lock = AppLockTargets["action_storage_delete"],
            onDismiss = { confirmAll = false },
            onConfirm = {
                confirmAll = false
                perform { env -> category.clear(env) }
            },
        )
    }
}

/** One slot of the item list: the item, and whether it is still on disk. */
private class ItemRow(val item: StorageItem, val present: Boolean)

/**
 * The item list as the screen shows it, across reloads.
 *
 * [SettingsGroup] lays its rows out by position, with no keys. Handed the
 * fresh list after a deletion, every row under the deleted one would shift up
 * a slot in a single frame. So while a reload only takes items away — which
 * is all a deletion does — the deleted ones stay in their slots marked gone,
 * and the group folds each one away where it was. Anything else (an item
 * that is new, or a different order) starts the ledger again from the fresh
 * list, which is drawn as it always was.
 */
private class RowLedger {
    private var rows: List<ItemRow> = emptyList()

    fun update(items: List<StorageItem>): List<ItemRow> {
        val fresh = items.associateBy { it.id }
        val known = rows.map { it.item.id }
        val survivors = known.filter { it in fresh }
        val onlyRemoved = rows.isNotEmpty() &&
            fresh.keys.all { it in known } &&
            survivors == items.map { it.id }
        rows = if (onlyRemoved) {
            rows.map { row -> fresh[row.item.id]?.let { ItemRow(it, present = true) } ?: ItemRow(row.item, present = false) }
        } else {
            items.map { ItemRow(it, present = true) }
        }
        return rows
    }
}

@Composable
private fun StorageItemRow(
    item: StorageItem,
    accent: Color,
    slotted: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    val deleteDesc = stringResource(R.string.storage_delete_item_desc, item.label)
    val preview = item.preview
    WmRow(
        title = item.label.ifBlank { stringResource(R.string.storage_item_unknown_label) },
        subtitle = item.detail,
        leading = when {
            preview != null -> {
                { StorageThumbnail(preview, accent) }
            }
            slotted -> {
                {
                    WmIconTile(
                        if (item.directory) Icons.Outlined.Folder else Icons.Outlined.Description,
                        accent,
                    )
                }
            }
            else -> null
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatBytes(item.bytes), style = MaterialTheme.typography.labelLarge)
                if (item.deletable) {
                    IconButton(onClick = onDelete, enabled = enabled) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = deleteDesc,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    )
}

/**
 * The picture itself in the row's icon slot.
 *
 * Drawn inside the same tile the icon rows use, so the two kinds of row line up
 * to the pixel and a picture with transparency in it — a decal, a cut-out
 * sticker — has the tile's wash behind it instead of the card.
 *
 * No content description: the row's own headline already names it, and a second
 * reading of "Key texture" is noise to a screen reader rather than help.
 */
@Composable
private fun StorageThumbnail(file: File, accent: Color) {
    WmIconTile(accent) {
        AsyncImage(
            model = file,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

private fun bodyForItem(danger: Danger): Int = when (danger) {
    Danger.NONE -> R.string.storage_confirm_free_body
    Danger.REDOWNLOAD -> R.string.storage_confirm_redownload_body
    Danger.PERSONAL, Danger.RESET -> R.string.storage_confirm_personal_body
}
