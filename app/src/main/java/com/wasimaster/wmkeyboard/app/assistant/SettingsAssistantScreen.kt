package com.wasimaster.wmkeyboard.app.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.RegisterSettingsCrumb
import com.wasimaster.wmkeyboard.app.ResizeForKeyboard
import com.wasimaster.wmkeyboard.app.ResultIcon
import com.wasimaster.wmkeyboard.app.SettingsSearchEntry
import com.wasimaster.wmkeyboard.app.WmIconTile
import com.wasimaster.wmkeyboard.app.listItemMotion
import com.wasimaster.wmkeyboard.app.settingsSearchIndex
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Assistant
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.ui.ChatLink
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdown
import com.wasimaster.wmkeyboard.ime.ui.ChatMarkdownColors
import kotlinx.coroutines.launch

/**
 * The Settings assistant (#788): ask about the keyboard in your own words, and
 * it finds the rows that matter, shows them, and proposes changes you apply.
 *
 * Opened from the search screen. Nothing here leaves the conversation on the
 * model's say: a row it shows is a card with where it lives and Open, a row it
 * names in its answer is a link that brings up the same card in a sheet, and a
 * change it proposes is a card with the current and proposed value and Apply.
 * See [SettingsAssistantSession] for the rules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsAssistantScreen(
    settings: LiveSettings,
    repository: SettingsRepository,
    onBack: () -> Unit,
    onOpen: (SettingsSearchEntry) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val context = LocalContext.current
    val items by SettingsAssistantSession.items.collectAsState()
    val busy by SettingsAssistantSession.busy.collectAsState()
    val partial by SettingsAssistantSession.partial.collectAsState()
    val chosenKey by SettingsAssistantSession.chosen.collectAsState()
    val index = remember { settingsSearchIndex(context.resources) }
    val byKey = remember(index) { index.associateBy { it.key } }
    val scope = rememberCoroutineScope()
    val reduceMotion = settings.watch { it.reduceMotion }
    var draft by rememberSaveable {
        mutableStateOf(SettingsAssistantSession.pendingQuestion.also { SettingsAssistantSession.pendingQuestion = "" })
    }
    // Everything already in the conversation when the screen opened is drawn
    // where it is; only what arrives while it is open comes in moving.
    val arrivedBefore by rememberSaveable { mutableIntStateOf(items.maxOfOrNull { it.id } ?: -1) }
    // The row a link in an answer named, while its sheet is up.
    var peekKey by rememberSaveable { mutableStateOf<String?>(null) }

    val ai = settings.watch { it.ai }
    val providers = settings.watch { it.providers }
    val rows = remember(ai, providers) { SettingsAssistantSession.rows(settings.value) }
    val row = remember(rows, chosenKey) { SettingsAssistantSession.row(settings.value) }
    val noProvider = stringResource(R.string.settings_assistant_no_provider)
    val title = stringResource(R.string.settings_assistant_title)
    RegisterSettingsCrumb(title)

    fun send(text: String = draft) {
        SettingsAssistantSession.send(
            text = text,
            settings = { settings.value },
            index = index,
            noProvider = noProvider,
            failure = { reason -> context.getString(R.string.settings_assistant_failed, reason) },
        )
        draft = ""
    }

    val listState = rememberLazyListState()
    LaunchedEffect(items.size, partial.length) {
        val last = items.size + (if (busy) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    val primary = MaterialTheme.colorScheme.primary
    val linkStyle = remember(primary) {
        SpanStyle(color = primary, fontWeight = FontWeight.SemiBold, background = primary.copy(alpha = 0.10f))
    }
    val settingLink: (String) -> ChatLink? = { url ->
        if (!url.startsWith(SettingRefs.LINK_SCHEME)) {
            null
        } else {
            // A link to a row the model never searched for, or made up, reads
            // as plain words rather than as a link that goes nowhere.
            val entry = SettingsAssistantSession.linkedKey(url)?.let(byKey::get)
            if (entry == null) ChatLink(style = null, onTap = null) else ChatLink(linkStyle) { peekKey = entry.key }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row != null) {
                            ModelPicker(
                                row = row,
                                rows = rows,
                                model = { SettingsAssistantSession.modelOf(settings.value, it) },
                                enabled = !busy,
                                reduceMotion = reduceMotion,
                                onPick = { SettingsAssistantSession.choose(it.key) },
                                onSetUp = onOpenAiSettings,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CommonR.string.common_back))
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = SettingsAssistantSession::clear) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.settings_assistant_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        ResizeForKeyboard()
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (items.isEmpty()) {
                    item(key = "intro") {
                        Intro(
                            hasProvider = row != null,
                            onOpenAiSettings = onOpenAiSettings,
                            onStarter = { starter -> if (row != null && !busy) send(starter) else draft = starter },
                            modifier = listItemMotion(reduceMotion),
                        )
                    }
                }
                items(items, key = { it.id }) { item ->
                    val moving = !reduceMotion && item.id > arrivedBefore
                    val arrive = listItemMotion(reduceMotion).arrival(item.id, moving)
                    when (item) {
                        is SettingsAssistantSession.Item.User -> Bubble(mine = true, modifier = arrive) {
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        is SettingsAssistantSession.Item.Reply -> Bubble(mine = false, failed = item.failed, modifier = arrive) {
                            if (item.failed) {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else {
                                SelectionContainer { Answer(item.text, settingLink) }
                            }
                        }
                        is SettingsAssistantSession.Item.Setting -> {
                            val entry = byKey[item.entryKey]
                            if (entry != null) {
                                SettingPreviewCard(
                                    entry = entry,
                                    settings = settings,
                                    revealKey = "card${item.id}",
                                    reduceMotion = reduceMotion,
                                    revealed = !moving,
                                    onOpen = { onOpen(entry) },
                                    flightOrigin = "assistant#${item.id}",
                                    modifier = arrive,
                                )
                            }
                        }
                        is SettingsAssistantSession.Item.Change -> ChangeCard(
                            item,
                            entry = remember(item.proposal.setting) {
                                index.firstOrNull { it.titleRes == item.proposal.setting.titleRes }
                            },
                            settings = settings,
                            onApply = { scope.launch { SettingsAssistantSession.apply(item.id, settings.value, repository) } },
                            onUndo = { scope.launch { SettingsAssistantSession.undo(item.id, settings.value, repository) } },
                            onDismiss = { SettingsAssistantSession.dismiss(item.id) },
                            modifier = arrive,
                        )
                    }
                }
                if (busy) {
                    item(key = "partial") {
                        Bubble(mine = false, modifier = listItemMotion(reduceMotion)) {
                            AnimatedContent(
                                targetState = partial.isBlank(),
                                transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform(clip = false) },
                                label = "partial",
                            ) { waiting ->
                                if (waiting) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TypingDots(reduceMotion)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            stringResource(R.string.settings_assistant_thinking),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    Answer(partial, settingLink)
                                }
                            }
                        }
                    }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                busy = busy,
                onSend = { send() },
                onStop = SettingsAssistantSession::stop,
            )
        }
    }

    peekKey?.let(byKey::get)?.let { entry ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { peekKey = null }, sheetState = sheet) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.settings_assistant_where),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                SettingPreviewCard(
                    entry = entry,
                    settings = settings,
                    revealKey = "sheet:${entry.key}",
                    reduceMotion = reduceMotion,
                    expanded = true,
                    onOpen = {
                        // The sheet slides away first, so the next screen does
                        // not cut its exit short.
                        scope.launch { sheet.hide() }.invokeOnCompletion {
                            peekKey = null
                            onOpen(entry)
                        }
                    },
                )
            }
        }
    }
}

/** An answer, drawn: markdown, with `setting:` links claimed by [settingLink]. */
@Composable
private fun Answer(text: String, settingLink: (String) -> ChatLink?) {
    val ink = MaterialTheme.colorScheme.onSurface
    ChatMarkdown(
        text,
        ChatMarkdownColors(
            text = ink,
            dim = ink.copy(alpha = 0.65f),
            codeBackground = ink.copy(alpha = 0.08f),
            link = MaterialTheme.colorScheme.primary,
        ),
        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
        customLink = settingLink,
    )
}

/**
 * Which model answers, under the screen's title, and the menu to pick
 * another. The menu offers every remote row of the AI list that can answer;
 * picking one changes the assistant's choice only, never the keyboard's.
 */
@Composable
private fun ModelPicker(
    row: ResolvedProvider,
    rows: List<ResolvedProvider>,
    model: (ResolvedProvider) -> String,
    enabled: Boolean,
    reduceMotion: Boolean,
    onPick: (ResolvedProvider) -> Unit,
    onSetUp: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "pickerChevron")
    val label = rowLabel(row)
    val current = listOf(label, model(row)).filter { it.isNotBlank() }.joinToString(" · ")
    val described = stringResource(R.string.settings_assistant_model_desc, current)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClickLabel = described) { open = true }
                .background(colors.surfaceContainerHigh)
                .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val swap = if (reduceMotion) {
                        fadeIn() togetherWith fadeOut()
                    } else {
                        (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
                    }
                    swap using SizeTransform(clip = false)
                },
                label = "pickerLabel",
            ) { shown ->
                Text(
                    shown,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            for (option in rows) {
                val picked = option.key == row.key
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(rowLabel(option), style = MaterialTheme.typography.bodyLarge)
                            val name = model(option)
                            if (name.isNotBlank()) {
                                Text(name, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    },
                    leadingIcon = {
                        if (picked) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.primary)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_assistant_add_provider)) },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    open = false
                    onSetUp()
                },
            )
        }
    }
}

/** A row of the AI list by the name the list shows: the user's own for it, else its service's. */
@Composable
private fun rowLabel(row: ResolvedProvider): String =
    row.customName.ifEmpty { AiProviders.of(row.typeId)?.let { stringResource(it.labelRes) } ?: row.typeId }

/** The first thing on an empty conversation: what the assistant does, and three ways to start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intro(
    hasProvider: Boolean,
    onOpenAiSettings: () -> Unit,
    onStarter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WmIconTile(Icons.Outlined.Assistant, accent = colors.primary)
        Text(
            stringResource(R.string.settings_assistant_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (!hasProvider) {
            FilledTonalButton(onClick = onOpenAiSettings) {
                Text(stringResource(R.string.settings_assistant_set_up_ai))
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (starter in listOf(
                    R.string.settings_assistant_starter_long_press,
                    R.string.settings_assistant_starter_quiet,
                    R.string.settings_assistant_starter_glide,
                )) {
                    val text = stringResource(starter)
                    AssistChip(onClick = { onStarter(text) }, label = { Text(text) })
                }
            }
        }
    }
}

/** One message: the user's on the right in the accent, the assistant's on the left. */
@Composable
private fun Bubble(
    mine: Boolean,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (mine) 20.dp else 6.dp,
                bottomEnd = if (mine) 6.dp else 20.dp,
            ),
            color = when {
                failed -> colors.errorContainer
                mine -> colors.primaryContainer
                else -> colors.surfaceContainerHigh
            },
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
        }
    }
}

/** The box the question is typed in, with Send, or Stop while an answer is coming. */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.settings_assistant_hint)) },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        AnimatedContent(
            targetState = busy,
            transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
            label = "composerAction",
        ) { running ->
            if (running) {
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.settings_assistant_stop))
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.settings_assistant_send))
                }
            }
        }
    }
}

/**
 * One proposed change: the setting's tile and title, its current and proposed
 * value, and what can be done with it. Apply, Undo and the notes under them
 * trade places in one motion, so the card says what just happened.
 */
@Composable
private fun ChangeCard(
    item: SettingsAssistantSession.Item.Change,
    entry: SettingsSearchEntry?,
    settings: LiveSettings,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val proposal = item.proposal
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CardShape,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry != null) {
                    ResultIcon(entry, settings)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_assistant_proposal),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                    )
                    Text(stringResource(proposal.setting.titleRes), style = MaterialTheme.typography.titleMedium)
                }
            }
            ChangeValues(shown(proposal.setting, proposal.from), shown(proposal.setting, proposal.to))
            val status = when {
                item.note == AssistantRefusal.CHANGED_SINCE && proposal.state == AssistantProposal.State.APPLIED ->
                    R.string.settings_assistant_undo_refused
                item.note == AssistantRefusal.CHANGED_SINCE -> R.string.settings_assistant_stale
                item.note == AssistantRefusal.INVALID -> R.string.settings_assistant_invalid
                proposal.state == AssistantProposal.State.APPLIED -> R.string.settings_assistant_applied
                proposal.state == AssistantProposal.State.UNDONE -> R.string.settings_assistant_undone
                proposal.state == AssistantProposal.State.DISMISSED -> R.string.settings_assistant_dismissed
                else -> 0
            }
            AnimatedContent(
                targetState = proposal.state to status,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 3 }) togetherWith fadeOut() using SizeTransform(clip = false)
                },
                label = "changeState",
            ) { (state, note) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (note != 0) {
                        if (state == AssistantProposal.State.APPLIED && item.note == null) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            stringResource(note),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    when (state) {
                        AssistantProposal.State.PROPOSED -> {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_assistant_dismiss)) }
                            Spacer(Modifier.width(4.dp))
                            FilledTonalButton(onClick = onApply) { Text(stringResource(R.string.settings_assistant_apply)) }
                        }
                        AssistantProposal.State.APPLIED ->
                            TextButton(onClick = onUndo) { Text(stringResource(R.string.settings_assistant_undo)) }
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * The entrance of something new in the conversation: it rises a little and
 * fades in, on a soft spring. [moving] false draws it in place, for what was
 * there before the screen opened and under reduced motion.
 */
@Composable
private fun Modifier.arrival(id: Int, moving: Boolean): Modifier {
    var played by rememberSaveable(id) { mutableStateOf(!moving) }
    val progress = remember(id) { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(id) {
        if (played) return@LaunchedEffect
        progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
        played = true
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * ARRIVAL_RISE.toPx()
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
    }
}

private val ARRIVAL_RISE = 18.dp
