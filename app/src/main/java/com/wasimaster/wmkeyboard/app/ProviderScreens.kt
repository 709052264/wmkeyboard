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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyBitcoin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CurrencyExchange
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Dns
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.Need
import com.wasimaster.wmkeyboard.core.providers.ProviderCatalog
import com.wasimaster.wmkeyboard.core.providers.ProviderChain
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderType
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
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
    // Read once: whether offline GIF packs exist is a look at the disk.
    val env = remember { ProviderEnvironment.current() }
    val rows = settings.watch { it.resolveProviders(tool, env, all = true) }
    val mode = settings.watch { it.providerMode(tool, env) }
    val stored = settings.watch { tool in it.providers.chains }
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
        item {
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.providers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                ReorderableColumn(
                    items = rows,
                    label = { providerTitle(context, it) },
                    onReorder = { ordered ->
                        save(ProviderEdits.setChain(tool, chain().copy(entries = ordered.map { it.entry })))
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                    rowHeight = ProviderRowHeight,
                ) { row ->
                    ProviderRowBody(
                        row = row,
                        modifier = Modifier.weight(1f),
                        onToggle = { on -> save(ProviderEdits.setChain(tool, chain().toggled(row.key, on))) },
                        onEdit = row.type?.takeIf { !it.credential.none }?.let { type ->
                            {
                                editing = ProviderEditTarget(
                                    type = type,
                                    credential = row.credential ?: ProviderCredential(row.entry.credentialKey, type.id),
                                    isNew = false,
                                )
                            }
                        },
                        onRemove = { removing = row },
                    )
                }
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
            tool = tool,
            target = target,
            usedBy = ProviderEdits.usedBy(settings.value, target.credential.id, tool, env),
            onSave = { credential ->
                editing = null
                save(ProviderEdits.saveCredential(settings.value, credential, if (target.isNew) tool else null, env))
            },
            onDismiss = { editing = null },
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

/** What the editor sheet is editing: one credential of one type, new or saved. */
internal data class ProviderEditTarget(
    val type: ProviderType,
    val credential: ProviderCredential,
    val isNew: Boolean,
)

/** What tapping a kind in the add sheet does: add a row straight away, or set one up first. */
internal sealed interface ProviderPick {
    data class Add(val entry: ProviderEntry) : ProviderPick
    data class Edit(val target: ProviderEditTarget) : ProviderPick
}

/**
 * A kind that needs nothing typed in (no key, a key the build carries, a
 * credential another tool already set up) goes straight into the list; the
 * rest open the editor. A server kind with no default instance, or one whose
 * default instance is already listed, always sets up a new server.
 */
internal fun pickProvider(type: ProviderType, existing: ProviderCredential?, chain: ProviderChain): ProviderPick {
    if (existing != null) {
        val credentialId = existing.id.takeIf { type.multiInstance && it != type.id }
        return ProviderPick.Add(ProviderEntry(type.id, credentialId))
    }
    val defaultListed = chain.entries.any { it.typeId == type.id && it.credentialId == null }
    if (type.readyOutOfTheBox && !defaultListed) return ProviderPick.Add(ProviderEntry(type.id))
    val id = if (type.multiInstance) ProviderCredential.newId(type.id) else type.id
    return ProviderPick.Edit(ProviderEditTarget(type, ProviderCredential(id, type.id), isNew = true))
}

private val ProviderRowHeight = 64.dp

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
        ProviderProblem.NEEDS_SERVER -> stringResource(R.string.providers_status_needs_server) to true
        ProviderProblem.NEEDS_PACKS -> stringResource(R.string.providers_status_needs_packs) to true
        ProviderProblem.UNAVAILABLE -> stringResource(R.string.providers_status_unavailable) to true
        ProviderProblem.UNKNOWN -> stringResource(R.string.providers_status_unknown) to true
        null -> when {
            !row.entry.enabled -> stringResource(R.string.providers_status_off)
            type?.onDevice == true -> stringResource(R.string.providers_status_on_device)
            // A front end's engine beside its host: two Mozhi rows may differ in nothing else.
            row.url.isNotBlank() -> type?.credential?.engine(row.model)
                ?.let { hostOf(row.url) + stringResource(R.string.providers_tag_separator) + it.label }
                ?: hostOf(row.url)
            row.usesBuiltInKey -> stringResource(R.string.providers_status_builtin_key)
            row.apiKey.isNotBlank() -> stringResource(R.string.providers_status_your_key)
            else -> stringResource(R.string.providers_status_no_key)
        } to false
    }
}

/** One row of the list: tile, name, state, the switch, and a menu. Drawn inside [ReorderableColumn]'s row. */
@Composable
private fun ProviderRowBody(
    row: ResolvedProvider,
    modifier: Modifier,
    onToggle: (Boolean) -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val title = providerTitle(context, row)
    val (status, problem) = providerStatus(row)
    var menu by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        WmIconTile(
            icon = providerIcon(row.type),
            accent = if (row.entry.enabled && row.usable) providerAccent(row.type) else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (problem && row.entry.enabled) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp).padding(end = 2.dp),
                    )
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem && row.entry.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Switch(checked = row.entry.enabled, onCheckedChange = onToggle)
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.providers_more, title))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (onEdit != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.providers_edit)) },
                    onClick = {
                        menu = false
                        onEdit()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.providers_remove)) },
                onClick = {
                    menu = false
                    onRemove()
                },
            )
        }
    }
}

/** The glyph a kind of provider is drawn with: by what it is first, then by what it does. */
internal fun providerIcon(type: ProviderType?): ImageVector = when {
    type == null -> Icons.Outlined.Public
    type.onDevice -> Icons.Outlined.PhoneAndroid
    type.selfHosted -> Icons.Outlined.Dns
    ProviderTool.TRANSLATE in type.serves -> Icons.Outlined.Translate
    ProviderTool.SEARCH in type.serves -> Icons.Outlined.Search
    ProviderTool.CURRENCY_CRYPTO in type.serves && ProviderTool.CURRENCY_FIAT !in type.serves -> Icons.Outlined.CurrencyBitcoin
    ProviderTool.CURRENCY_FIAT in type.serves || ProviderTool.CURRENCY_CRYPTO in type.serves -> Icons.Outlined.CurrencyExchange
    ProviderTool.DICTIONARY in type.serves || ProviderTool.SYNONYMS in type.serves -> Icons.Outlined.Spellcheck
    type === ProviderTypes.COMMONS -> Icons.Outlined.Public
    ProviderTool.PHOTOS in type.serves -> Icons.Outlined.PhotoLibrary
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
    val groups = remember(tool, env, chain) { ProviderCatalog.addable(tool, chain, env) }
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
            for (group in groups) {
                Text(
                    stringResource(if (group.suggested) R.string.providers_type_suggested else R.string.providers_type_more),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                )
                for (type in group.types) {
                    val tags = providerTags(type)
                    val desc = stringResource(type.descriptionRes(tool))
                    WmRow(
                        title = stringResource(type.labelRes),
                        subtitle = if (tags.isEmpty()) desc else desc + "\n" + tags,
                        leading = { WmIconTile(icon = providerIcon(type), accent = providerAccent(type)) },
                        onClick = { onPick(type, credentials.firstOrNull { it.id == type.id && !type.multiInstance }) },
                    )
                    if (type.multiInstance) {
                        val unused = credentials.filter { c -> c.typeId == type.id && !chain.has(c.id) && c.url.isNotBlank() }
                        for (credential in unused) {
                            WmRow(
                                title = stringResource(
                                    R.string.providers_type_use_existing,
                                    credential.name.ifBlank { hostOf(credential.url) },
                                ),
                                icon = Icons.Outlined.Dns,
                                onClick = { onPick(type, credential) },
                            )
                        }
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
private fun ProviderEditorSheet(
    tool: ProviderTool,
    target: ProviderEditTarget,
    usedBy: List<ProviderTool>,
    onSave: (ProviderCredential) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = target.type
    val spec = type.credential
    var draft by remember { mutableStateOf(target.credential) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val ready = (spec.url != Need.REQUIRED || draft.url.isNotBlank()) &&
        (spec.key != Need.REQUIRED || type.hasBuiltInKey || draft.apiKey.isNotBlank()) &&
        (type !== ProviderTypes.DEEPL || draft.apiKey.isNotBlank() || draft.url.isNotBlank())
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
                WmIconTile(icon = providerIcon(type), accent = providerAccent(type))
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(stringResource(type.labelRes), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(type.descriptionRes(tool)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (type.multiInstance) {
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
            if (spec.key != Need.NONE) {
                StoredTextField(
                    label = stringResource(R.string.providers_field_key),
                    value = draft.apiKey,
                    supporting = stringResource(
                        when {
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
                    supporting = stringResource(R.string.providers_field_model_hint),
                ) { edit(draft.copy(model = it)) }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                Button(enabled = ready, onClick = { onSave(draft.trimmed()) }) {
                    Text(stringResource(if (target.isNew) CommonR.string.common_add else CommonR.string.common_save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Stored trimmed: a key or address pasted from a web page often arrives with a space on it. */
private fun ProviderCredential.trimmed() =
    copy(name = name.trim(), apiKey = apiKey.trim(), url = url.trim(), model = model.trim())
