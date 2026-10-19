package com.wasimaster.wmkeyboard.app.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.RegisterSettingsCrumb
import com.wasimaster.wmkeyboard.app.ResizeForKeyboard
import com.wasimaster.wmkeyboard.app.SettingsSearchEntry
import com.wasimaster.wmkeyboard.app.settingsSearchIndex
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowBack
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The Settings assistant (#788): ask about the keyboard in your own words, and
 * it finds the rows that matter, opens them, and proposes changes you apply.
 *
 * Opened from the search screen. Each change it proposes is a card with the
 * setting's current and proposed value and an Apply button; nothing changes
 * until that is pressed. See [SettingsAssistantSession] for the rules.
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
    val index = remember { settingsSearchIndex(context.resources) }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable {
        mutableStateOf(SettingsAssistantSession.pendingQuestion.also { SettingsAssistantSession.pendingQuestion = "" })
    }
    val noProvider = stringResource(R.string.settings_assistant_no_provider)
    val hasProvider = SettingsAssistantSession.provider(settings.watch { it }) != null
    val title = stringResource(R.string.settings_assistant_title)
    RegisterSettingsCrumb(title)

    LaunchedEffect(Unit) {
        SettingsAssistantSession.opens.collect { onOpen(it) }
    }

    fun send() {
        SettingsAssistantSession.send(
            text = draft,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (items.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.settings_assistant_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!hasProvider) {
                        item {
                            TextButton(onClick = onOpenAiSettings) {
                                Text(stringResource(R.string.settings_assistant_set_up_ai))
                            }
                        }
                    }
                }
                items(items, key = { it.id }) { item ->
                    when (item) {
                        is SettingsAssistantSession.Item.User -> Message(item.text, mine = true)
                        is SettingsAssistantSession.Item.Reply -> Message(item.text, mine = false, failed = item.failed)
                        is SettingsAssistantSession.Item.Change -> ChangeCard(
                            item,
                            onApply = { scope.launch { SettingsAssistantSession.apply(item.id, settings.value, repository) } },
                            onUndo = { scope.launch { SettingsAssistantSession.undo(item.id, settings.value, repository) } },
                            onDismiss = { SettingsAssistantSession.dismiss(item.id) },
                        )
                    }
                }
                if (busy) {
                    item(key = "partial") {
                        Message(partial.ifBlank { stringResource(R.string.settings_assistant_thinking) }, mine = false)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.settings_assistant_hint)) },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    IconButton(onClick = SettingsAssistantSession::stop) {
                        Icon(Icons.Outlined.Stop, contentDescription = stringResource(R.string.settings_assistant_stop))
                    }
                } else {
                    IconButton(onClick = ::send, enabled = draft.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.settings_assistant_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, mine: Boolean, failed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 48.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                failed -> colors.errorContainer
                mine -> colors.primaryContainer
                else -> colors.surfaceContainerHigh
            },
        ),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One proposed change: the setting, its current and proposed value, and what can be done with it. */
@Composable
private fun ChangeCard(
    item: SettingsAssistantSession.Item.Change,
    onApply: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val proposal = item.proposal
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(proposal.setting.titleRes), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.settings_assistant_change_values,
                    shown(proposal.setting, proposal.from),
                    shown(proposal.setting, proposal.to),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
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
            if (status != 0) {
                Text(
                    stringResource(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (proposal.state) {
                    AssistantProposal.State.PROPOSED -> {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_assistant_dismiss)) }
                        TextButton(onClick = onApply) { Text(stringResource(R.string.settings_assistant_apply)) }
                    }
                    AssistantProposal.State.APPLIED ->
                        TextButton(onClick = onUndo) { Text(stringResource(R.string.settings_assistant_undo)) }
                    else -> Unit
                }
            }
        }
    }
}

/** A value the way the card shows it: On and Off for a switch, the number and its unit otherwise. */
@Composable
private fun shown(setting: AssistantSetting, value: String): String = when (setting) {
    is AssistantSetting.Switch ->
        stringResource(if (value == "true") CommonR.string.common_on else CommonR.string.common_off)
    is AssistantSetting.Number -> value
}
