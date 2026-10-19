package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyBitcoin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyExchange
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dns
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragIndicator
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Key
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HomeIotDevice
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.app.homeassistant.HomeProviderExtras
import com.wasimaster.wmkeyboard.app.homeassistant.homeReady
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.Need
import com.wasimaster.wmkeyboard.core.providers.ProviderCatalog
import com.wasimaster.wmkeyboard.core.providers.ProviderChain
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderLogos
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.providers.SameKind
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.settings.availableVoiceEngines
import com.wasimaster.wmkeyboard.core.settings.ProviderProblem
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.providerChain
import com.wasimaster.wmkeyboard.core.settings.providerCredentials
import com.wasimaster.wmkeyboard.core.settings.providerMode
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import kotlinx.coroutines.launch

/*
 * One screen part for every tool that can ask more than one service: the GIF
 * and sticker search, translate, the dictionary, synonyms, vocabulary, web
 * search, background photos, currency. It works the way backup locations do.
 * A provider is added from a sheet of kinds, set up once in an editor, and
 * then moved, switched off or removed in the tool's list. What a provider was
 * set up with (a key, a server) is shared with every other tool that lists
 * it, so one KLIPY key serves GIFs and stickers both.
 */

/** A tool's provider list as a settings group: the mode, the rows, Add, and Reset. */
@Composable
internal fun ProvidersGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
    tool: ProviderTool,
    /** The group's heading, which is also what a settings search lands on. */
    @StringRes titleRes: Int = R.string.providers_group_title,
    info: String? = null,
    /** After every saved change: for a cache that answered for the old setup. */
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read once: whether offline GIF packs exist is a look at the disk, and
    // which voice engines can run is a load of their libraries.
    val env = remember(tool) {
        ProviderEnvironment.current().let {
            if (tool == ProviderTool.VOICE) it.copy(voiceEngines = availableVoiceEngines()) else it
        }
    }
    val rows = settings.watch { it.resolveProviders(tool, env, all = true) }
    val mode = settings.watch { it.providerMode(tool, env) }
    val stored = settings.watch { tool in it.providers.chains }
    // Only a list that folds two of a kind, with some kind on it twice, has anything to decide here.
    val sameKind = settings.watch { s ->
        s.providerChain(tool, env).takeIf { tool.foldsSameKind(it.mode) && it.repeatsKind }?.sameKindInForce
    }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProviderEditTarget?>(null) }
    var removing by remember { mutableStateOf<ResolvedProvider?>(null) }

    fun save(change: ProviderEdits.Change) {
        scope.launch {
            repository.saveProviders(change)
            onChanged()
        }
    }
    fun chain(): ProviderChain = settings.value.providerChain(tool, env)

    SettingsGroup(
        stringResource(titleRes),
        // What a settings search lands on; one list per page.
        highlightKey = titleRes,
        info = info ?: stringResource(R.string.providers_group_info),
    ) {
        if (tool.modes.size > 1) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ChoiceControl(
                        options = tool.modes.map { it to stringResource(it.labelRes) },
                        selected = mode,
                        label = stringResource(R.string.providers_mode_label),
                        onChange = { picked: ChainMode -> save(ProviderEdits.setChain(tool, chain().copy(mode = picked))) },
                    )
                    Text(
                        stringResource(mode.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        item(visible = sameKind != null) {
            val shown = sameKind ?: SameKind.FALLBACK
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChoiceControl(
                    options = SameKind.entries.map { it to stringResource(it.labelRes) },
                    selected = shown,
                    label = stringResource(R.string.providers_same_kind_label),
                    onChange = { picked: SameKind -> save(ProviderEdits.setChain(tool, chain().copy(sameKind = picked))) },
                )
                Text(
                    stringResource(shown.descRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item {
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.providers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                ProviderList(
                    rows = rows,
                    onReorder = { ordered ->
                        save(ProviderEdits.setChain(tool, chain().copy(entries = ordered.map { it.entry })))
                    },
                    onToggle = { row, on -> save(ProviderEdits.setChain(tool, chain().toggled(row.key, on))) },
                    onOpen = { row ->
                        val type = row.type
                        if (type == null) {
                            // A kind a newer build added: nothing to edit, only to remove.
                            removing = row
                        } else {
                            editing = ProviderEditTarget(
                                type = type,
                                credential = row.credential ?: ProviderCredential(row.entry.credentialKey, type.id),
                                isNew = false,
                                row = row,
                            )
                        }
                    },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.providers_add),
                subtitle = stringResource(R.string.providers_add_subtitle),
                icon = Icons.Outlined.Add,
                onClick = { adding = true },
            )
        }
        item(visible = stored) {
            WmRow(
                title = stringResource(R.string.providers_reset),
                subtitle = stringResource(R.string.providers_reset_subtitle),
                icon = Icons.Outlined.History,
                onClick = {
                    scope.launch {
                        repository.resetProviderChain(tool)
                        onChanged()
                    }
                },
            )
        }
    }

    if (adding) {
        val current = chain()
        ProviderTypeSheet(
            tool = tool,
            env = env,
            chain = current,
            credentials = settings.value.providerCredentials(),
            onPick = { type, existing ->
                adding = false
                val pick = pickProvider(type, existing, current)
                when (pick) {
                    is ProviderPick.Add -> save(ProviderEdits.addEntry(settings.value, tool, pick.entry, env))
                    is ProviderPick.Edit -> editing = pick.target
                }
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { target ->
        ProviderEditorSheet(
            repository = repository,
            tool = tool,
            target = target,
            usedBy = ProviderEdits.usedBy(settings.value, target.credential.id, tool, env),
            onSave = { credential ->
                editing = null
                save(ProviderEdits.saveCredential(settings.value, credential, if (target.isNew) tool else null, env))
            },
            onRemove = target.row?.let { row ->
                {
                    editing = null
                    removing = row
                }
            },
            onDismiss = {
                editing = null
                onChanged()
            },
        )
    }
    removing?.let { row ->
        val title = providerTitle(context, row)
        val change = ProviderEdits.removeEntry(settings.value, tool, row.key, env)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.providers_remove_title, title)) },
            text = {
                Text(
                    stringResource(
                        if (change.credentials != null) R.string.providers_remove_body_credential else R.string.providers_remove_body,
                        stringResource(tool.labelRes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    save(change)
                }) { Text(stringResource(R.string.providers_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

/** What the editor sheet is editing: one credential of one type, new or saved, and the list row it was opened from. */
internal data class ProviderEditTarget(
    val type: ProviderType,
    val credential: ProviderCredential,
    val isNew: Boolean,
    val row: ResolvedProvider? = null,
)

/** What tapping a kind in the add sheet does: add a row straight away, or set one up first. */
internal sealed interface ProviderPick {
    data class Add(val entry: ProviderEntry) : ProviderPick
    data class Edit(val target: ProviderEditTarget) : ProviderPick
}

/**
 * A kind that needs nothing typed in (no key, a key the build carries, a
 * credential another tool already set up) goes straight into the list; the
 * rest open the editor. A kind whose own row is already listed sets up
 * another one of it: a new server, or a second key or account.
 */
internal fun pickProvider(type: ProviderType, existing: ProviderCredential?, chain: ProviderChain): ProviderPick {
    if (existing != null) {
        return ProviderPick.Add(ProviderEntry(type.id, existing.id.takeIf { it != type.id }))
    }
    val defaultListed = chain.entries.any { it.typeId == type.id && it.credentialId == null }
    if (type.readyOutOfTheBox && !defaultListed) return ProviderPick.Add(ProviderEntry(type.id))
    val id = if (type.multiInstance || defaultListed) ProviderCredential.newId(type.id) else type.id
    return ProviderPick.Edit(ProviderEditTarget(type, ProviderCredential(id, type.id), isNew = true))
}

/** Set up beside the type's own credential: a second key or account, which brings its own key. */
private fun ProviderCredential.isAnother(type: ProviderType): Boolean = !type.multiInstance && id != type.id

/** What tells a saved setup apart when it has no nickname: its server, else the end of its key. */
@Composable
private fun credentialLabel(type: ProviderType, credential: ProviderCredential): String = when {
    credential.name.isNotBlank() -> credential.name
    credential.url.isNotBlank() -> hostOf(credential.url)
    credential.apiKey.isNotBlank() ->
        stringResource(R.string.providers_type_existing_key, stringResource(type.labelRes), credential.apiKey.takeLast(KEY_TAIL))
    else -> stringResource(type.labelRes)
}

/** How much of a key a row shows: enough to tell two apart, too little to use. */
private const val KEY_TAIL = 4


/** The name a row goes by: the one the user gave a server, else its kind's. */
internal fun providerTitle(context: Context, row: ResolvedProvider): String =
    row.customName.ifEmpty { row.type?.let { context.getString(it.labelRes) } ?: row.typeId }

/** A server's host, which is what tells two servers of one kind apart. */
private fun hostOf(url: String): String =
    runCatching { Uri.parse(if ("://" in url) url else "https://$url").host }.getOrNull()?.ifBlank { null } ?: url

/** The line under a row's name, and whether it reports a problem. */
@Composable
private fun providerStatus(row: ResolvedProvider): Pair<String, Boolean> {
    val type = row.type
    return when (row.problem) {
        ProviderProblem.NEEDS_KEY -> stringResource(R.string.providers_status_needs_key) to true
        ProviderProblem.NEEDS_SIGN_IN -> stringResource(
            when (HomeKind.of(type?.id.orEmpty())) {
                HomeKind.HUE -> R.string.providers_status_needs_pairing
                HomeKind.GOOGLE_HOME -> R.string.providers_status_needs_grant
                else -> R.string.providers_status_needs_sign_in
            },
        ) to true
        ProviderProblem.NEEDS_SERVER -> stringResource(R.string.providers_status_needs_server) to true
        ProviderProblem.NEEDS_MODEL -> stringResource(R.string.providers_status_needs_model) to true
        ProviderProblem.NEEDS_PACKS -> stringResource(R.string.providers_status_needs_packs) to true
        ProviderProblem.UNAVAILABLE -> stringResource(R.string.providers_status_unavailable) to true
        ProviderProblem.UNKNOWN -> stringResource(R.string.providers_status_unknown) to true
        null -> modelAfter(row, when {
            !row.entry.enabled -> stringResource(R.string.providers_status_off)
            // A home reads as what it is called, else where it is.
            HomeKind.of(type?.id.orEmpty()) != null -> row.credential?.extra?.get("instance_name")?.takeIf { it.isNotBlank() }
                ?: row.url.takeIf { it.isNotBlank() }?.let(::hostOf)
                ?: stringResource(R.string.providers_status_signed_in)
            type?.onDevice == true -> stringResource(R.string.providers_status_on_device)
            // A front end's engine beside its host: two Mozhi rows may differ in nothing else.
            row.url.isNotBlank() -> type?.credential?.engine(row.model)
                ?.let { hostOf(row.url) + stringResource(R.string.providers_tag_separator) + it.label }
                ?: hostOf(row.url)
            row.usesBuiltInKey -> stringResource(R.string.providers_status_builtin_key)
            row.apiKey.length > KEY_TAIL * 2 -> stringResource(R.string.providers_status_your_key_ending, row.apiKey.takeLast(KEY_TAIL))
            row.apiKey.isNotBlank() -> stringResource(R.string.providers_status_your_key)
            else -> stringResource(R.string.providers_status_no_key)
        }) to false
    }
}

/**
 * [status] with the model after it, for an AI service or a transcription
 * server: two rows of one service often differ in nothing else. A front
 * end's engine is in the model field too, and is shown on its own already.
 */
@Composable
private fun modelAfter(row: ResolvedProvider, status: String): String {
    val type = row.type ?: return status
    val shows = type.credential.model && type.credential.engines.isEmpty() &&
        (ProviderTool.AI in type.serves || ProviderTool.VOICE in type.serves)
    if (!shows || row.model.isBlank() || !row.entry.enabled) return status
    return status + stringResource(R.string.providers_tag_separator) + row.model
}

/**
 * The list itself. Each row is the order, the provider, and its switch;
 * tapping a row opens its setup, where it can also be removed. The order
 * column (the position over a grip) is the drag handle, and TalkBack gets
 * Move up and Move down instead.
 *
 * Not [ReorderableColumn]: that one needs every row the same height, and a
 * provider's name and state wrap onto as many lines as they need here. Each
 * row's height is measured, and a dragged row swaps with a neighbour once it
 * is past half of that neighbour's height.
 */
@Composable
private fun ProviderList(
    rows: List<ResolvedProvider>,
    onReorder: (List<ResolvedProvider>) -> Unit,
    onToggle: (ResolvedProvider, Boolean) -> Unit,
    onOpen: (ResolvedProvider) -> Unit,
) {
    var working by remember { mutableStateOf(rows) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    // Mid-drag the finger's order wins, and reaches the caller on the drop.
    LaunchedEffect(rows) { if (dragKey == null) working = rows }
    val currentRows by rememberUpdatedState(rows)
    val currentOnReorder by rememberUpdatedState(onReorder)
    fun finish() {
        if (working.map { it.key } != currentRows.map { it.key }) currentOnReorder(working)
        dragKey = null
        dragOffset = 0f
    }
    fun moved(from: Int, to: Int): List<ResolvedProvider> =
        working.toMutableList().apply { add(to, removeAt(from)) }

    val shape = MaterialTheme.shapes.medium
    val lift = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        working.forEachIndexed { index, row ->
            key(row.key) {
                val dragging = dragKey == row.key
                ProviderRow(
                    row = row,
                    position = index + 1,
                    modifier = Modifier
                        .onSizeChanged { heights[row.key] = it.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            shadowElevation = if (dragging) 6.dp.toPx() else 0f
                            this.shape = shape
                        }
                        .background(if (dragging) lift else Color.Transparent, shape),
                    handle = Modifier.pointerInput(row.key) {
                        detectDragGestures(
                            onDragStart = {
                                dragKey = row.key
                                dragOffset = 0f
                            },
                            onDragEnd = { finish() },
                            onDragCancel = { finish() },
                        ) { change, drag ->
                            change.consume()
                            val from = working.indexOfFirst { it.key == dragKey }
                            if (from < 0) return@detectDragGestures
                            // Held at the ends: past the first or last row it stops.
                            dragOffset = (dragOffset + drag.y).let {
                                when {
                                    from == 0 && it < 0f -> 0f
                                    from == working.lastIndex && it > 0f -> 0f
                                    else -> it
                                }
                            }
                            if (dragOffset > 0f && from < working.lastIndex) {
                                val next = heights[working[from + 1].key] ?: return@detectDragGestures
                                if (dragOffset > next / 2f) {
                                    working = moved(from, from + 1)
                                    dragOffset -= next
                                }
                            } else if (dragOffset < 0f && from > 0) {
                                val previous = heights[working[from - 1].key] ?: return@detectDragGestures
                                if (-dragOffset > previous / 2f) {
                                    working = moved(from, from - 1)
                                    dragOffset += previous
                                }
                            }
                        }
                    },
                    onMove = if (dragKey == null) {
                        { by ->
                            val to = (index + by).coerceIn(working.indices)
                            if (to != index) {
                                working = moved(index, to)
                                currentOnReorder(working)
                            }
                        }
                    } else {
                        null
                    },
                    canMoveUp = index > 0,
                    canMoveDown = index < working.lastIndex,
                    onToggle = { on -> onToggle(row, on) },
                    onOpen = { onOpen(row) },
                )
            }
        }
    }
}

/** One provider: the order and its grip, the tile, the name and state (as many lines as they take), the switch. */
@Composable
private fun ProviderRow(
    row: ResolvedProvider,
    position: Int,
    modifier: Modifier,
    handle: Modifier,
    onMove: ((Int) -> Unit)?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val title = providerTitle(context, row)
    val (status, problem) = providerStatus(row)
    val upLabel = stringResource(R.string.providers_move_up)
    val downLabel = stringResource(R.string.providers_move_down)
    val shape = MaterialTheme.shapes.medium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(shape)
            .clickable(onClickLabel = stringResource(R.string.providers_edit)) { onOpen() }
            .semantics {
                customActions = buildList {
                    if (onMove != null && canMoveUp) add(CustomAccessibilityAction(upLabel) { onMove(-1); true })
                    if (onMove != null && canMoveDown) add(CustomAccessibilityAction(downLabel) { onMove(1); true })
                }
            }
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        // The order, over the grip that changes it. One narrow column where
        // the number and the drag handle used to sit at the two ends of the row.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .width(28.dp)
                .heightIn(min = 48.dp)
                .then(handle),
        ) {
            Text(
                position.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Outlined.DragIndicator,
                contentDescription = stringResource(R.string.rows_reorder_handle_desc, title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        ProviderTile(row.type, active = row.entry.enabled && row.usable)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.Top) {
                if (problem && row.entry.enabled) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp, end = 4.dp).size(14.dp),
                    )
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem && row.entry.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // A switch at four fifths of its size: the row's one control, without
        // the width a full switch and a menu button took between them.
        Box(Modifier.size(width = 48.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            Switch(
                checked = row.entry.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.requiredSize(width = 52.dp, height = 32.dp).scale(0.8f),
            )
        }
    }
}

/**
 * A kind's tile: its logo where the app carries one, else the glyph for what
 * it is. A one-colour mark is made readable on the theme ([readableMark]); a
 * picture that vanishes on one theme sits on its own plate.
 */
@Composable
internal fun ProviderTile(type: ProviderType?, active: Boolean = true) {
    val logo = type?.let { ProviderLogos.of(it.id) }
    val dark = isSystemInDarkTheme()
    val accent = when {
        !active -> MaterialTheme.colorScheme.outline
        logo != null && logo.monochrome -> readableMark(Color(logo.color), dark)
        logo != null -> Color(0xFF9E9E9E)
        else -> readableMark(providerAccent(type), dark)
    }
    if (logo == null) {
        WmIconTile(icon = providerIcon(type), accent = accent)
        return
    }
    WmIconTile(accent) {
        val plate = logo.backdrop?.takeIf { active }
        Box(
            contentAlignment = Alignment.Center,
            modifier = if (plate != null) {
                Modifier.size(30.dp).background(Color(plate), RoundedCornerShape(8.dp))
            } else {
                Modifier
            },
        ) {
            Icon(
                painterResource(logo.res),
                contentDescription = null,
                // A brand's own colours stay as they are; a one-colour mark takes the tile's.
                tint = if (logo.monochrome || !active) LocalContentColor.current else Color.Unspecified,
                modifier = Modifier.size(20.dp).graphicsLayer { alpha = if (active) 1f else 0.6f },
            )
        }
    }
}

/**
 * [color] moved toward white on a dark theme, or toward black on a light
 * one, until it stands out from the tile. The tile draws its glyph in the
 * accent itself on a dark theme, so a near-black brand (Unsplash, Wiktionary,
 * DeepL's navy) disappeared there; a pale one did the same on a light theme.
 */
internal fun readableMark(color: Color, dark: Boolean): Color {
    var mark = color
    repeat(MARK_STEPS) {
        val luminance = mark.luminance()
        if (if (dark) luminance >= DARK_MIN_LUMINANCE else luminance <= LIGHT_MAX_LUMINANCE) return mark
        mark = lerp(mark, if (dark) Color.White else Color.Black, MARK_STEP)
    }
    return mark
}

private const val MARK_STEPS = 10
private const val MARK_STEP = 0.2f
private const val DARK_MIN_LUMINANCE = 0.22f
private const val LIGHT_MAX_LUMINANCE = 0.45f

/** The glyph a kind of provider is drawn with: by what it is first, then by what it does. */
internal fun providerIcon(type: ProviderType?): ImageVector = when {
    type == null -> Icons.Outlined.Public
    HomeKind.of(type.id) != null -> Icons.Outlined.HomeIotDevice
    ProviderTool.VOICE in type.serves && !type.selfHosted -> Icons.Outlined.Mic
    type.onDevice -> Icons.Outlined.PhoneAndroid
    type.selfHosted -> Icons.Outlined.Dns
    ProviderTool.TRANSLATE in type.serves -> Icons.Outlined.Translate
    ProviderTool.SEARCH in type.serves -> Icons.Outlined.Search
    ProviderTool.CURRENCY_CRYPTO in type.serves && ProviderTool.CURRENCY_FIAT !in type.serves -> Icons.Outlined.CurrencyBitcoin
    ProviderTool.CURRENCY_FIAT in type.serves || ProviderTool.CURRENCY_CRYPTO in type.serves -> Icons.Outlined.CurrencyExchange
    ProviderTool.DICTIONARY in type.serves || ProviderTool.SYNONYMS in type.serves -> Icons.Outlined.Spellcheck
    type === ProviderTypes.COMMONS -> Icons.Outlined.Public
    ProviderTool.PHOTOS in type.serves -> Icons.Outlined.PhotoLibrary
    ProviderTool.AI in type.serves -> Icons.Outlined.AutoAwesome
    else -> Icons.Outlined.Image
}

/** A kind's tile colour: the brand's where there is one, muted where there is none. */
internal fun providerAccent(type: ProviderType?): Color = when (type?.id) {
    ProviderTypes.KLIPY.id -> Color(0xFF6C47FF)
    ProviderTypes.GIPHY.id -> Color(0xFF00B4E6)
    ProviderTypes.COMMONS.id -> Color(0xFF006699)
    ProviderTypes.GIFCITIES.id -> Color(0xFFB0306B)
    ProviderTypes.DEEPL.id -> Color(0xFF0F2B46)
    ProviderTypes.GOOGLE_TRANSLATE.id -> Color(0xFF1A73E8)
    ProviderTypes.LIBRETRANSLATE.id -> Color(0xFF1565C0)
    ProviderTypes.MOZHI.id -> Color(0xFF7B5CBF)
    ProviderTypes.SIMPLYTRANSLATE.id -> Color(0xFF2B6CB0)
    ProviderTypes.LINGVA.id -> Color(0xFF38A169)
    ProviderTypes.TRANSLITE.id -> Color(0xFFC05621)
    ProviderTypes.BRAVE.id -> Color(0xFFFB542B)
    ProviderTypes.TAVILY.id -> Color(0xFF3B5BDB)
    ProviderTypes.SEARXNG.id -> Color(0xFF3050FF)
    ProviderTypes.UNSPLASH.id -> Color(0xFF111111)
    ProviderTypes.PEXELS.id -> Color(0xFF05A081)
    ProviderTypes.HOME_ASSISTANT.id -> Color(0xFF18BCF2)
    ProviderTypes.GOOGLE_HOME.id -> Color(0xFF4285F4)
    ProviderTypes.SMARTTHINGS.id -> Color(0xFF1F8EFA)
    ProviderTypes.HUE.id -> Color(0xFF0065D3)
    ProviderTypes.OPENHAB.id -> Color(0xFFE64A19)
    ProviderTypes.TUYA.id -> Color(0xFFFF4800)
    ProviderTypes.SWITCHBOT.id -> Color(0xFFE60012)
    ProviderTypes.ANTHROPIC.id -> Color(0xFFD97757)
    ProviderTypes.OPENAI.id -> Color(0xFF10A37F)
    ProviderTypes.GEMINI.id -> Color(0xFF4285F4)
    ProviderTypes.XAI.id -> Color(0xFF111111)
    ProviderTypes.DEEPSEEK.id -> Color(0xFF4D6BFE)
    ProviderTypes.BRAVE_ANSWERS.id -> Color(0xFFFB542B)
    else -> when {
        type == null -> Color(0xFF757575)
        type.onDevice -> Color(0xFF2E7D32)
        type.selfHosted -> Color(0xFF455A64)
        type.freeSoftware -> Color(0xFF00897B)
        else -> Color(0xFF5C6BC0)
    }
}

/** The short facts under a kind in the add sheet: free software, on the phone, a key it needs. */
@Composable
private fun providerTags(type: ProviderType): String {
    val tags = buildList {
        if (type.onDevice) add(stringResource(R.string.providers_tag_on_device))
        if (type.freeSoftware) add(stringResource(R.string.providers_tag_free))
        if (type.selfHosted) add(stringResource(R.string.providers_tag_self_hosted))
        when {
            type.credential.key == Need.REQUIRED && type.hasBuiltInKey -> add(stringResource(R.string.providers_tag_builtin))
            type.credential.key == Need.REQUIRED -> add(stringResource(R.string.providers_tag_needs_key))
            type.credential.key == Need.NONE && !type.onDevice && !type.selfHosted ->
                add(stringResource(R.string.providers_tag_no_key))
        }
    }
    return tags.joinToString(stringResource(R.string.providers_tag_separator))
}

/**
 * "Add a provider" as a sheet of kinds, the build's suggestions first. A
 * server kind also lists the servers set up for another tool, so a
 * LibreTranslate server added once can be used again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderTypeSheet(
    tool: ProviderTool,
    env: ProviderEnv,
    chain: ProviderChain,
    credentials: List<ProviderCredential>,
    onPick: (ProviderType, ProviderCredential?) -> Unit,
    onDismiss: () -> Unit,
) {
    val types = remember(tool, env, chain) { ProviderCatalog.addable(tool, chain, env) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Text(
                stringResource(R.string.providers_type_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.providers_type_subtitle, stringResource(tool.labelRes)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            for (type in types) {
                val tags = providerTags(type)
                val desc = stringResource(type.descriptionRes(tool))
                WmRow(
                    title = stringResource(type.labelRes),
                    subtitle = if (tags.isEmpty()) desc else desc + "\n" + tags,
                    leading = { ProviderTile(type) },
                    onClick = {
                        // The type's own setup, made on another tool's page, when this list does not have it yet.
                        onPick(type, credentials.firstOrNull { it.id == type.id && !type.multiInstance && !chain.has(type.id) })
                    },
                )
                // The other setups of this kind that this list does not use yet: servers, second keys.
                if (tool.allowsAnother(type)) {
                    val unused = credentials.filter { c ->
                        c.typeId == type.id && !chain.has(c.id) && (type.multiInstance || c.id != type.id) &&
                            (c.url.isNotBlank() || c.apiKey.isNotBlank())
                    }
                    for (credential in unused) {
                        WmRow(
                            title = stringResource(R.string.providers_type_use_existing, credentialLabel(type, credential)),
                            icon = if (credential.url.isNotBlank()) Icons.Outlined.Dns else Icons.Outlined.Key,
                            onClick = { onPick(type, credential) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Setting up or changing one provider. Typed fields edit a draft that Save
 * keeps, so a half-typed address is never asked by a search meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
private fun ProviderEditorSheet(
    repository: SettingsRepository,
    tool: ProviderTool,
    target: ProviderEditTarget,
    usedBy: List<ProviderTool>,
    onSave: (ProviderCredential) -> Unit,
    /** Takes the row out of the list (after asking); null while the provider is being added. */
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = target.type
    val spec = type.credential
    var draft by remember { mutableStateOf(target.credential) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val home = HomeKind.of(type.id) != null
    // A second key or account of a single kind: the build's key is the first one's, so this one needs its own.
    val another = target.credential.isAnother(type)
    val ready = if (home) {
        homeReady(type, draft)
    } else {
        (spec.url != Need.REQUIRED || draft.url.isNotBlank()) &&
            (spec.key != Need.REQUIRED || (type.hasBuiltInKey && !another) || draft.apiKey.isNotBlank()) &&
            (!another || spec.key == Need.NONE || draft.apiKey.isNotBlank() || (type === ProviderTypes.DEEPL && draft.url.isNotBlank())) &&
            (!spec.modelRequired || draft.model.isNotBlank()) &&
            (type !== ProviderTypes.DEEPL || draft.apiKey.isNotBlank() || draft.url.isNotBlank())
    }
    fun edit(next: ProviderCredential) {
        draft = next
        testResult = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ScrollRail(state = rememberScrollRailState(), fadeColor = BottomSheetDefaults.ContainerColor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                ProviderTile(type)
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(
                        target.credential.name.ifBlank { stringResource(type.labelRes) },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(type.descriptionRes(tool)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A nickname wherever there can be two of the kind, so the list and the panel can tell them apart.
            if (type.multiInstance || tool.allowsAnother(type)) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_name),
                    value = draft.name,
                    supporting = stringResource(R.string.providers_field_name_hint),
                ) { edit(draft.copy(name = it)) }
            }
            if (spec.url != Need.NONE) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_url),
                    value = draft.url,
                    supporting = stringResource(
                        if (spec.url == Need.REQUIRED) R.string.providers_field_url_hint else R.string.providers_field_url_optional_hint,
                    ),
                    keyboardType = KeyboardType.Uri,
                ) { edit(draft.copy(url = it)) }
            }
            if (spec.path) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_path),
                    value = draft.extra[CredentialSpec.PATH].orEmpty(),
                    supporting = stringResource(R.string.providers_field_path_hint),
                    keyboardType = KeyboardType.Uri,
                ) { path ->
                    edit(draft.copy(extra = if (path.isBlank()) draft.extra - CredentialSpec.PATH else draft.extra + (CredentialSpec.PATH to path)))
                }
            }
            if (spec.engines.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ChoiceControl(
                        options = spec.engines.map { it.id to it.label },
                        selected = spec.engine(draft.model)?.id ?: spec.engines.first().id,
                        label = stringResource(R.string.providers_field_engine),
                        onChange = { picked: String -> edit(draft.copy(model = picked)) },
                    )
                    Text(
                        stringResource(R.string.providers_field_engine_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            // A home's key is a token pasted or a pairing made in its own section below, not a plain field.
            if (spec.key != Need.NONE && !home) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_key),
                    value = draft.apiKey,
                    supporting = stringResource(
                        when {
                            another -> R.string.providers_field_key_another_hint
                            type.hasBuiltInKey -> R.string.providers_field_key_builtin_hint
                            spec.key == Need.OPTIONAL -> R.string.providers_field_key_optional_hint
                            else -> R.string.providers_field_key_required_hint
                        },
                    ),
                    password = true,
                ) { edit(draft.copy(apiKey = it)) }
            }
            if (spec.model) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_model),
                    value = draft.model,
                    supporting = aiModelHint(type) ?: stringResource(
                        if (spec.modelRequired) R.string.providers_field_model_required_hint else R.string.providers_field_model_hint,
                    ),
                ) { edit(draft.copy(model = it)) }
            }
            if (home) {
                HomeProviderExtras(
                    repository = repository,
                    type = type,
                    draft = draft,
                    isNew = target.isNew,
                    onDraft = ::edit,
                    onSave = { onSave(it.trimmed()) },
                    onDismiss = onDismiss,
                )
            }
            if (usedBy.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.providers_used_by,
                        usedBy.joinToString(", ") { context.getString(it.labelRes) },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            // The cheapest request the provider answers, with what is on
            // screen now: a typo in a host name shows here, not in the panel.
            val probe = ProviderProbes.probe(context, tool, type, draft.trimmed())
            if (probe != null && ready) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val failure = runCatching { probe() }.exceptionOrNull()
                                testing = false
                                testResult = if (failure == null) {
                                    true to context.getString(R.string.providers_test_ok)
                                } else {
                                    false to context.getString(
                                        R.string.providers_test_failed,
                                        ToolHttp.friendlyMessage(context, failure),
                                    )
                                }
                            }
                        },
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.providers_testing))
                        } else {
                            Text(stringResource(R.string.providers_test))
                        }
                    }
                }
                testResult?.let { (ok, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                        Icon(
                            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.providers_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                // A kind with nothing to set up only has its description and Remove here.
                if (spec.none) {
                    TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                    Button(enabled = ready, onClick = { onSave(draft.trimmed()) }) {
                        Text(stringResource(if (target.isNew) CommonR.string.common_add else CommonR.string.common_save))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * What a blank model means for an AI service: the default the client falls
 * back to, named from the client's own list so the two cannot drift apart.
 * Null for a kind that is not an AI service.
 */
@Composable
private fun aiModelHint(type: ProviderType): String? {
    val default = when (AiProviders.of(type.id)) {
        AiProvider.ANTHROPIC -> AiClient.DefaultModels.ANTHROPIC
        AiProvider.OPENAI -> AiClient.DefaultModels.OPENAI
        AiProvider.GEMINI -> AiClient.DefaultModels.GEMINI
        AiProvider.XAI -> AiClient.DefaultModels.XAI
        AiProvider.DEEPSEEK -> AiClient.DefaultModels.DEEPSEEK
        AiProvider.OLLAMA -> AiClient.DefaultModels.OLLAMA
        AiProvider.LM_STUDIO -> return stringResource(R.string.toolai_ai_lm_studio_model_hint)
        AiProvider.OPENAI_COMPATIBLE -> return stringResource(R.string.toolai_ai_compatible_model_hint)
        else -> return null
    }
    return stringResource(R.string.toolai_ai_model_hint, default)
}

/** Stored trimmed: a key or address pasted from a web page often arrives with a space on it. */
private fun ProviderCredential.trimmed() =
    copy(
        name = name.trim(),
        apiKey = apiKey.trim(),
        url = url.trim(),
        model = model.trim(),
        extra = extra[CredentialSpec.PATH]?.let { extra + (CredentialSpec.PATH to it.trim()) } ?: extra,
    )
