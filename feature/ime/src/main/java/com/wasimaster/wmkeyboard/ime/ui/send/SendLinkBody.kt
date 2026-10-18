package com.wasimaster.wmkeyboard.ime.ui.send

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Send
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.send.SendServerConfig
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.send.LinkStage
import com.wasimaster.wmkeyboard.ime.send.ReceiveUi
import com.wasimaster.wmkeyboard.ime.send.SendAction
import com.wasimaster.wmkeyboard.ime.send.SendDelivered
import com.wasimaster.wmkeyboard.ime.send.SendSheet
import com.wasimaster.wmkeyboard.ime.send.SendSource
import com.wasimaster.wmkeyboard.ime.send.SendTextField
import com.wasimaster.wmkeyboard.ime.send.SendUi
import com.wasimaster.wmkeyboard.ime.ui.ConfettiBurst
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.MotionCrossfade
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.blockTouches
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockCard
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import kotlinx.coroutines.delay

/**
 * The Link mode (#729): composing an upload, the upload itself, the finished
 * link, a link being received, or what went wrong. One stage at a time,
 * cross-faded.
 */
@Composable
internal fun SendLinkBody(state: KeyboardUiState, ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    if (state.sendTypingActive) {
        TypingBar(state, ui, act)
        return
    }
    val stage = ui.link
    val currentKey = stageKey(stage)
    MotionCrossfade(
        target = stage,
        animate = kb.motion.panels,
        modifier = Modifier.fillMaxSize(),
        contentKey = ::stageKey,
        label = "send-stage",
    ) { shown ->
        Box(Modifier.fillMaxSize().blockTouches(stageKey(shown) != currentKey)) {
            when (shown) {
                LinkStage.Compose -> ComposeStage(ui, act)
                is LinkStage.Uploading -> UploadingStage(shown, act)
                is LinkStage.Done -> DoneStage(ui, shown, act)
                is LinkStage.Receiving -> ReceiveStage(ui, shown.ui, act)
                is LinkStage.Failed -> FailedStage(shown, act)
            }
        }
    }
}

/** What makes a stage a different picture; a progress tick is not. */
private fun stageKey(stage: LinkStage): Any = when (stage) {
    LinkStage.Compose -> "compose"
    is LinkStage.Uploading -> "uploading"
    is LinkStage.Done -> "done-${stage.upload.id}"
    is LinkStage.Receiving -> when {
        stage.ui.error != null -> "receive-error"
        stage.ui.done -> "receive-done"
        stage.ui.remote != null -> "receive-ready"
        stage.ui.needsPassword -> "receive-password"
        else -> "receive-checking"
    }
    is LinkStage.Failed -> "failed"
}

// ---- the box while the keys type into it ----

/**
 * The panel collapsed over the key rows: the one box that has the keys, with
 * what can be done to it. A password shows dots unless asked to show itself.
 */
@Composable
private fun TypingBar(state: KeyboardUiState, ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val entry = state.sendText ?: return
    val field = entry.field
    val masked = field == SendTextField.PASSWORD && !ui.passwordVisible
    val shown = if (masked) "•".repeat(entry.text.length) else entry.text
    val receiving = ui.link is LinkStage.Receiving
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(
            stringResource(
                when (field) {
                    SendTextField.PASSWORD -> if (receiving) R.string.ime_send_password_enter else R.string.ime_send_password
                    SendTextField.LINK -> R.string.ime_send_paste_link
                    SendTextField.INSTANCE -> R.string.ime_send_instance_add
                },
            ),
            color = kb.secondaryText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(kb.chipShape())
                .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
                .background(kb.chip)
                .chipBorder(kb, kb.chipShape())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (field == SendTextField.PASSWORD) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            SearchQueryText(
                query = shown,
                placeholder = stringResource(
                    when (field) {
                        SendTextField.PASSWORD -> R.string.ime_send_password_hint
                        SendTextField.LINK -> R.string.ime_send_link_hint
                        SendTextField.INSTANCE -> R.string.ime_send_instance_hint
                    },
                ),
                active = true,
                textColor = kb.chipText,
                placeholderColor = kb.secondaryText,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (field == SendTextField.PASSWORD) {
                Spacer(Modifier.width(6.dp))
                ToolPanelChip(
                    stringResource(if (ui.passwordVisible) R.string.ime_send_password_hide else R.string.ime_send_password_show),
                    icon = if (ui.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                ) { act(SendAction.PasswordToggleVisible) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (field == SendTextField.PASSWORD && !receiving) {
                ToolPanelChip(stringResource(R.string.ime_send_password_generate)) { act(SendAction.PasswordGenerate) }
            }
            if (receiving && (ui.link as LinkStage.Receiving).ui.wrongPassword) {
                Text(stringResource(R.string.ime_send_wrong_password), color = FailedRed, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            ToolPanelChip(stringResource(R.string.ime_send_cancel)) { act(SendAction.Dismiss) }
            ToolPanelChip(
                stringResource(
                    when (field) {
                        SendTextField.PASSWORD -> R.string.ime_send_done
                        SendTextField.LINK -> R.string.ime_send_receive_go
                        SendTextField.INSTANCE -> R.string.ime_send_instance_add
                    },
                ),
                selected = true,
                enabled = entry.text.isNotBlank() || field == SendTextField.PASSWORD,
            ) { act(SendAction.TextDone) }
        }
    }
}

// ---- compose ----

private enum class Picking { EXPIRY, DOWNLOADS }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeStage(ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val animate = kb.motion.panels
    var picking by remember { mutableStateOf<Picking?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceTile(Icons.Outlined.FolderOpen, stringResource(R.string.ime_send_source_files), 0, animate, Modifier.weight(1f)) {
                act(SendAction.AddFrom(SendSource.FILES))
            }
            SourceTile(Icons.Outlined.PhotoLibrary, stringResource(R.string.ime_send_source_gallery), 1, animate, Modifier.weight(1f)) {
                act(SendAction.AddFrom(SendSource.GALLERY))
            }
            SourceTile(Icons.Outlined.ContentPaste, stringResource(R.string.ime_send_source_clipboard), 2, animate, Modifier.weight(1f)) {
                act(SendAction.AddFrom(SendSource.CLIPBOARD))
            }
            SourceTile(Icons.Outlined.Notes, stringResource(R.string.ime_send_source_text), 3, animate, Modifier.weight(1f)) {
                act(SendAction.AddFrom(SendSource.FIELD_TEXT))
            }
        }
        val draft = ui.draft
        if (draft.isEmpty) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.ime_send_compose_hint),
                color = kb.secondaryText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                ToolPanelChip(stringResource(R.string.ime_send_paste_link)) { act(SendAction.LinkFocus) }
            }
            Spacer(Modifier.height(4.dp))
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(draft.files, key = { it.id }) { file ->
                FileTile(file, Modifier.animateItem()) { act(SendAction.RemoveFile(file.id)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        val config = ui.config ?: SendServerConfig.DEFAULT
        val total = bytesText(context, draft.totalSize)
        val cap = bytesText(context, ui.maxBytes)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.ime_send_files_count, draft.files.size, draft.files.size) + " · " +
                    if (ui.overCap) stringResource(R.string.ime_send_over_cap, hostText(ui.instance)) else stringResource(R.string.ime_send_total_of, total, cap),
                color = if (ui.overCap) FailedRed else kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolPanelChip(stringResource(R.string.ime_send_clear)) { act(SendAction.ClearFiles) }
        }
        Spacer(Modifier.height(3.dp))
        SizeMeter(
            fraction = if (ui.maxBytes > 0) draft.totalSize.toFloat() / ui.maxBytes else 0f,
            over = ui.overCap,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        if (ui.overCap) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.wasimaster.wmkeyboard.core.send.SendInstances.roomFor(draft.totalSize).take(4).forEach { instance ->
                    ToolPanelChip(stringResource(R.string.ime_send_try_instance, instance.host)) { act(SendAction.PickInstance(instance.url)) }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        AnimatedContent(
            targetState = picking,
            transitionSpec = {
                fadeIn(tween(if (animate) 140 else 0)) togetherWith fadeOut(tween(if (animate) 100 else 0))
            },
            label = "send-options",
        ) { pick ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (pick) {
                        null -> {
                            ToolPanelChip(
                                stringResource(R.string.ime_send_expiry) + " " + durationText(draft.expireSeconds),
                            ) { picking = Picking.EXPIRY }
                            ToolPanelChip(
                                pluralStringResource(R.plurals.ime_send_downloads_count, draft.downloads, draft.downloads),
                            ) { picking = Picking.DOWNLOADS }
                            ToolPanelChip(
                                when {
                                    !draft.passwordOn -> stringResource(R.string.ime_send_password)
                                    draft.password.isEmpty() -> stringResource(R.string.ime_send_password_hint)
                                    ui.passwordVisible -> draft.password
                                    else -> stringResource(R.string.ime_send_meta_password)
                                },
                                selected = draft.passwordOn,
                                icon = if (draft.passwordOn && draft.password.isEmpty()) null else if (draft.passwordOn) null else Icons.Outlined.Lock,
                            ) { act(if (draft.passwordOn) SendAction.PasswordFocus else SendAction.PasswordOn(true)) }
                            if (draft.passwordOn) {
                                ToolPanelChip(stringResource(R.string.ime_send_remove)) { act(SendAction.PasswordOn(false)) }
                            }
                        }
                        Picking.EXPIRY -> {
                            config.expireChoices.forEach { seconds ->
                                ToolPanelChip(durationText(seconds), selected = seconds == draft.expireSeconds) {
                                    act(SendAction.SetExpiry(seconds))
                                    picking = null
                                }
                            }
                            ToolPanelChip(stringResource(R.string.ime_send_back)) { picking = null }
                        }
                        Picking.DOWNLOADS -> {
                            config.downloadChoices.forEach { count ->
                                ToolPanelChip(count.toString(), selected = count == draft.downloads) {
                                    act(SendAction.SetDownloads(count))
                                    picking = null
                                }
                            }
                            ToolPanelChip(stringResource(R.string.ime_send_back)) { picking = null }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                UploadButton(count = draft.files.size, enabled = !ui.overCap) { hold ->
                    if (hold) act(SendAction.OpenSheet(SendSheet.UPLOAD_MENU)) else act(SendAction.Upload())
                }
            }
        }
    }
}

/** The accent circle that sends: a tap uploads, a hold opens the menu of ways to. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UploadButton(count: Int, enabled: Boolean, onPress: (hold: Boolean) -> Unit) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Box(Modifier.size(48.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .align(Alignment.BottomStart)
                .clip(CircleShape)
                .background(kb.accent.copy(alpha = if (enabled) 1f else 0.4f))
                .combinedClickable(
                    enabled = enabled,
                    onClick = {
                        feedback()
                        onPress(false)
                    },
                    onLongClick = {
                        feedback()
                        onPress(true)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(R.string.ime_send_upload),
                tint = com.wasimaster.wmkeyboard.ime.ui.maxContrastOn(kb.accent),
                modifier = Modifier.size(22.dp),
            )
        }
        if (count > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(kb.popup)
                    .chipBorder(kb, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.toString(), color = kb.popupText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---- uploading ----

@Composable
private fun UploadingStage(stage: LinkStage.Uploading, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = SystemClock.elapsedRealtime()
        }
    }
    val elapsed = ((now - stage.startedAtMs) / 1000f).coerceAtLeast(0.5f)
    val speed = stage.sent / elapsed
    val eta = if (speed > 1 && stage.total > stage.sent) ((stage.total - stage.sent) / speed).toLong() else -1L
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(fraction = if (stage.sent > 0) stage.fraction else null)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stage.name, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            MetaText(stringResource(R.string.ime_send_uploading))
            MetaText(stringResource(R.string.ime_send_total_of, bytesText(context, stage.sent), bytesText(context, stage.total)))
            if (stage.sent > 0) {
                MetaText(
                    stringResource(R.string.ime_send_speed, bytesText(context, speed.toLong())) +
                        if (eta >= 0) " · " + stringResource(R.string.ime_send_eta, durationText(eta)) else "",
                )
            } else {
                MetaText(stringResource(R.string.ime_send_starting))
            }
            Spacer(Modifier.height(8.dp))
            PillButton(stringResource(R.string.ime_send_cancel), height = 32.dp) { act(SendAction.Cancel) }
        }
    }
}

// ---- done ----

@Composable
private fun DoneStage(ui: SendUi, stage: LinkStage.Done, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val upload = stage.upload
    val appear = remember(upload.id) { MutableTransitionState(kb.reduceMotion).apply { targetState = true } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visibleState = appear,
            enter = slideInVertically(tween(if (kb.reduceMotion) 0 else 220)) { it / 3 } + fadeIn(tween(if (kb.reduceMotion) 0 else 160)),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    QrImage(
                        upload.url,
                        sizePx = 256,
                        modifier = Modifier.size(84.dp).combinedClickableNoIndication { act(SendAction.OpenSheet(SendSheet.QR)) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ime_send_done_title), color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            upload.url.removePrefix("https://").removePrefix("http://"),
                            color = kb.accent,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        val left = ((upload.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                        val info = ui.uploadInfo[upload.id]
                        val downloads = if (info != null) {
                            pluralStringResource(R.plurals.ime_send_downloads_used, info.downloadLimit, info.downloads, info.downloadLimit)
                        } else {
                            pluralStringResource(R.plurals.ime_send_downloads_count, upload.downloadLimit, upload.downloadLimit)
                        }
                        MetaText(
                            stringResource(R.string.ime_send_meta_expires, durationText(left)) + " · " + downloads +
                                if (upload.password) " · " + stringResource(R.string.ime_send_meta_password) else "",
                        )
                        when (stage.delivered) {
                            SendDelivered.INSERTED -> MetaText(stringResource(R.string.ime_send_inserted_line), color = ConnectedGreen)
                            SendDelivered.COPIED -> MetaText(stringResource(R.string.ime_send_copied_line), color = ConnectedGreen)
                            null -> Unit
                        }
                        if (stage.password != null) {
                            MetaText(stringResource(R.string.ime_send_meta_password_value, stage.password))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ui.hasField) ToolPanelChip(stringResource(R.string.ime_send_insert), selected = true) { act(SendAction.InsertLink) }
                    ToolPanelChip(stringResource(R.string.ime_send_copy)) { act(SendAction.CopyLink) }
                    ToolPanelChip(stringResource(R.string.ime_send_share)) { act(SendAction.ShareLink) }
                    ToolPanelChip(stringResource(R.string.ime_send_qr)) { act(SendAction.OpenSheet(SendSheet.QR)) }
                    ToolPanelChip(stringResource(R.string.ime_send_manage), selected = stage.manageOpen) { act(SendAction.ManageOpen(!stage.manageOpen)) }
                    ToolPanelChip(stringResource(R.string.ime_send_new_upload)) { act(SendAction.Reset) }
                }
                AnimatedVisibility(visible = stage.manageOpen) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            MetaText(stringResource(R.string.ime_send_downloads))
                            (ui.config ?: SendServerConfig.DEFAULT).downloadChoices.forEach { count ->
                                ToolPanelChip(count.toString(), selected = count == upload.downloadLimit) { act(SendAction.SetUploadDownloads(upload, count)) }
                            }
                            if (stage.password != null) {
                                ToolPanelChip(stringResource(R.string.ime_send_copy_password)) { act(SendAction.CopyPassword) }
                            }
                            ToolPanelChip(stringResource(R.string.ime_send_delete)) { act(SendAction.DeleteUpload(upload)) }
                        }
                    }
                }
            }
        }
        if (kb.motion.toolResults && stage.delivered != null) {
            ConfettiBurst(key = upload.id, accent = kb.accent, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A clickable with no ripple, for pictures. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoIndication(onClick: () -> Unit): Modifier = this.then(
    Modifier.combinedClickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    ),
)

// ---- receiving ----

@Composable
private fun ReceiveStage(ui: SendUi, r: ReceiveUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val host = hostText(r.link.base)
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(kb.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(
                    if (r.needsPassword && r.remote == null) Icons.Outlined.Lock else fileIcon(r.remote?.metadata?.type.orEmpty(), r.remote?.metadata?.name.orEmpty()),
                    contentDescription = null,
                    tint = kb.accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        r.done -> stringResource(R.string.ime_send_saved_title)
                        r.remote != null -> r.remote.metadata.name
                        else -> stringResource(R.string.ime_send_receive_title)
                    },
                    color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                MetaText(stringResource(R.string.ime_send_receive_from, host))
            }
            ToolPanelChip(stringResource(if (r.done) R.string.ime_send_done else R.string.ime_send_back)) { act(SendAction.Reset) }
        }
        Spacer(Modifier.height(8.dp))
        when {
            r.error != null -> {
                Text(stringResource(errorText(r.error)), color = kb.suggestionText, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillButton(stringResource(R.string.ime_send_retry), primary = true, height = 32.dp) { act(SendAction.Receive(r.link.url)) }
                }
            }
            r.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                Radar(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                MetaText(stringResource(R.string.ime_send_checking, host))
            }
            r.done -> {
                MetaText(stringResource(R.string.ime_send_saved_where))
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.saved.forEach { item ->
                        ClockCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(fileIcon(item.type, item.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = kb.chipText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.size > 0) MetaText(bytesText(context, item.size))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ToolPanelChip(stringResource(R.string.ime_send_open), selected = true) { act(SendAction.OpenReceived(item)) }
                                    if (ui.hasField && (ui.fieldTakesImages && item.type.startsWith("image/"))) {
                                        ToolPanelChip(stringResource(R.string.ime_send_insert_file)) { act(SendAction.InsertReceived(item)) }
                                    }
                                    ToolPanelChip(stringResource(R.string.ime_send_share_file)) { act(SendAction.ShareReceived(item)) }
                                }
                            }
                        }
                    }
                }
            }
            r.remote == null && r.needsPassword -> {
                Text(
                    stringResource(if (r.wrongPassword) R.string.ime_send_wrong_password else R.string.ime_send_needs_password),
                    color = if (r.wrongPassword) FailedRed else kb.suggestionText,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                PillButton(stringResource(R.string.ime_send_password_enter), icon = Icons.Outlined.Lock, primary = true, height = 32.dp) { act(SendAction.PasswordFocus) }
            }
            r.remote != null -> {
                val meta = r.remote.metadata
                MetaText(
                    bytesText(context, meta.size) +
                        (if (meta.isArchive) " · " + pluralStringResource(R.plurals.ime_send_files_count, meta.files.size, meta.files.size) else "") +
                        " · " + stringResource(R.string.ime_send_meta_expires, durationText(r.remote.ttlMs / 1000)),
                )
                if (r.remote.finalDownload) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.ime_send_final_download), color = kb.accent, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                val progress = r.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { if (progress.second > 0) (progress.first.toFloat() / progress.second).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = kb.accent,
                        trackColor = kb.divider,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetaText(stringResource(R.string.ime_send_total_of, bytesText(context, progress.first), bytesText(context, progress.second)), Modifier.weight(1f))
                        ToolPanelChip(stringResource(R.string.ime_send_cancel)) { act(SendAction.Cancel) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillButton(stringResource(R.string.ime_send_download), primary = true, height = 32.dp) { act(SendAction.Download) }
                    }
                }
            }
        }
    }
}

// ---- failed ----

@Composable
private fun FailedStage(stage: LinkStage.Failed, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = FailedRed, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ime_send_failed_title), color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(errorText(stage.error)), color = kb.secondaryText, fontSize = 12.sp)
        if (stage.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                stage.suggestions.take(5).forEach { instance ->
                    ToolPanelChip(stringResource(R.string.ime_send_try_instance, instance.host)) { act(SendAction.PickInstance(instance.url)) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton(stringResource(R.string.ime_send_retry), primary = true, height = 32.dp) { act(SendAction.Upload()) }
            PillButton(stringResource(R.string.ime_send_back), height = 32.dp) { act(SendAction.Reset) }
        }
    }
}
