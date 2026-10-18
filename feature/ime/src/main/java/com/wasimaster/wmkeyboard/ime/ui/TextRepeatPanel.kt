package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.TextRepeat
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.TextRepeatAction
import com.wasimaster.wmkeyboard.ime.TextRepeatField
import com.wasimaster.wmkeyboard.ime.textRepeatSpec
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * The Text repeat tool: a box for the text, a count, what goes between the
 * copies, two toggles, and a live preview of what Insert will write. The
 * options are settings (see TextRepeatSettings), so the panel reopens the way
 * it was left; the service owns the boxes and the undo (see TextRepeatUi).
 */

/** The three modes, as the header's tabs: the ring's CHIPS region. */
@Composable
internal fun RowScope.TextRepeatHeaderTabs(state: KeyboardUiState, onAction: (TextRepeatAction) -> Unit) {
    val mode = state.settings.textRepeat.mode
    val modes = TextRepeat.Mode.entries
    PanelFocusTarget(
        panel = PanelMode.TEXT_REPEAT,
        region = FocusRegion.CHIPS,
        count = modes.size,
        columns = modes.size,
    ) { index -> onAction(TextRepeatAction.Mode(modes[index])) }
    val focusedTab = state.focusedIndex(FocusRegion.CHIPS)
    Spacer(Modifier.width(4.dp))
    modes.forEachIndexed { index, candidate ->
        if (index > 0) Spacer(Modifier.width(6.dp))
        ToolPanelChip(
            stringResource(modeLabel(candidate)),
            selected = mode == candidate,
            modifier = Modifier.focusRing(focusedTab == index),
        ) { onAction(TextRepeatAction.Mode(candidate)) }
    }
    Spacer(Modifier.weight(1f))
}

private fun modeLabel(mode: TextRepeat.Mode): Int = when (mode) {
    TextRepeat.Mode.REPEAT -> R.string.ime_repeat_tab_repeat
    TextRepeat.Mode.STRETCH -> R.string.ime_repeat_tab_stretch
    TextRepeat.Mode.STAIRS -> R.string.ime_repeat_tab_stairs
}

private fun separatorLabel(separator: TextRepeat.Separator): Int = when (separator) {
    TextRepeat.Separator.NONE -> R.string.ime_repeat_sep_none
    TextRepeat.Separator.SPACE -> R.string.ime_repeat_sep_space
    TextRepeat.Separator.NEWLINE -> R.string.ime_repeat_sep_newline
    TextRepeat.Separator.COMMA -> R.string.ime_repeat_sep_comma
    TextRepeat.Separator.CUSTOM -> R.string.ime_repeat_sep_custom
}

@Composable
internal fun TextRepeatPanel(state: KeyboardUiState, onAction: (TextRepeatAction) -> Unit) {
    val kb = LocalKbTheme.current
    val motion = kb.motion.any
    val ui = state.textRepeat
    val options = state.settings.textRepeat
    val spec = state.textRepeatSpec()
    val built = remember(spec) { TextRepeat.build(spec) }
    val custom = options.separator == TextRepeat.Separator.CUSTOM
    val stretch = options.mode == TextRepeat.Mode.STRETCH
    val canInsert = built.text.isNotEmpty()
    val separators = TextRepeat.Separator.entries
    val quick = TextRepeat.quickCounts

    // Ring regions. SEARCH: the text box, then the separator box while it is
    // shown. ACTIONS: Insert, Copy, Undo. RESULTS: every option chip in
    // drawing order, the stepper's − and + first.
    PanelFocusTarget(
        panel = PanelMode.TEXT_REPEAT,
        region = FocusRegion.SEARCH,
        count = if (custom) 2 else 1,
        columns = 2,
    ) { index -> onAction(TextRepeatAction.Focus(if (index == 1) TextRepeatField.SEPARATOR else TextRepeatField.TEXT)) }
    PanelFocusTarget(panel = PanelMode.TEXT_REPEAT, region = FocusRegion.ACTIONS, count = 3, columns = 3) { index ->
        when (index) {
            0 -> if (canInsert) onAction(TextRepeatAction.Insert)
            1 -> if (canInsert) onAction(TextRepeatAction.Copy)
            else -> if (ui.lastInsert != null) onAction(TextRepeatAction.Undo)
        }
    }
    val optionEntries: List<() -> Unit> = buildList {
        add { onAction(TextRepeatAction.Count(options.count - 1)) }
        add { onAction(TextRepeatAction.Count(options.count + 1)) }
        quick.forEach { n -> add { onAction(TextRepeatAction.Count(n)) } }
        if (!stretch) {
            separators.forEach { s -> add { onAction(TextRepeatAction.Separator(s)) } }
            add { onAction(TextRepeatAction.Numbered(!options.numbered)) }
            add { onAction(TextRepeatAction.AlternateCase(!options.alternateCase)) }
        }
    }
    PanelFocusTarget(
        panel = PanelMode.TEXT_REPEAT,
        region = FocusRegion.RESULTS,
        count = optionEntries.size,
        columns = optionEntries.size,
    ) { index -> optionEntries.getOrNull(index)?.invoke() }
    val focusedBox = state.focusedIndex(FocusRegion.SEARCH)
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedOption = state.focusedIndex(FocusRegion.RESULTS)

    // Insert lands: the preview card gives one small push, so the tap reads
    // as "that went in" without the panel going anywhere.
    val pulse = remember { Animatable(1f) }
    var seenTick by remember { mutableIntStateOf(ui.insertTick) }
    LaunchedEffect(ui.insertTick) {
        if (ui.insertTick == seenTick) return@LaunchedEffect
        seenTick = ui.insertTick
        if (!motion) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(PULSE_SCALE, tween(PULSE_UP_MS))
        pulse.animateTo(1f, tween(PULSE_DOWN_MS))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The boxes. Keys route into whichever is focused, so the platform
        // draws no caret of its own; the separator box only exists for Custom.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val textHint = stringResource(R.string.ime_repeat_text_hint)
            RepeatPill(
                text = ui.text,
                placeholder = textHint,
                active = ui.focused == TextRepeatField.TEXT,
                ringed = focusedBox == 0,
                modifier = Modifier.weight(1f),
                onClear = { onAction(TextRepeatAction.Clear(TextRepeatField.TEXT)) },
            ) { onAction(TextRepeatAction.Focus(TextRepeatField.TEXT)) }
            if (custom && !stretch) {
                Spacer(Modifier.width(6.dp))
                RepeatPill(
                    text = ui.customSeparator,
                    placeholder = stringResource(R.string.ime_repeat_separator_hint),
                    active = ui.focused == TextRepeatField.SEPARATOR,
                    ringed = focusedBox == 1,
                    modifier = Modifier.width(96.dp),
                    onClear = { onAction(TextRepeatAction.Clear(TextRepeatField.SEPARATOR)) },
                ) { onAction(TextRepeatAction.Focus(TextRepeatField.SEPARATOR)) }
            }
        }
        // The count: a stepper that runs while held, and the usual numbers.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepperRow(
                label = stringResource(if (stretch) R.string.ime_repeat_letters_label else R.string.ime_repeat_count_label),
                value = options.count,
                focusedMinus = focusedOption == 0,
                focusedPlus = focusedOption == 1,
                holdSteps = true,
                labelWidth = 48.dp,
                onChange = { onAction(TextRepeatAction.Count(it)) },
            )
            quick.forEachIndexed { index, n ->
                ToolPanelChip(
                    "$n",
                    selected = options.count == n,
                    modifier = Modifier.focusRing(focusedOption == 2 + index),
                ) { onAction(TextRepeatAction.Count(n)) }
            }
        }
        // Between the copies, and the two toggles. Stretch has no copies to
        // put anything between, so its row is the count alone.
        if (!stretch) {
            val base = 2 + quick.size
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                separators.forEachIndexed { index, s ->
                    val label = if (s == TextRepeat.Separator.CUSTOM && custom && ui.customSeparator.isNotEmpty()) {
                        "“${ui.customSeparator}”"
                    } else {
                        stringResource(separatorLabel(s))
                    }
                    ToolPanelChip(
                        label,
                        selected = options.separator == s,
                        modifier = Modifier.focusRing(focusedOption == base + index),
                    ) { onAction(TextRepeatAction.Separator(s)) }
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .heightIn(min = 18.dp)
                        .background(kb.secondaryText.copy(alpha = 0.3f)),
                )
                val numberedDesc = stringResource(R.string.ime_repeat_numbered_desc)
                ToolPanelChip(
                    stringResource(R.string.ime_repeat_numbered_label),
                    selected = options.numbered,
                    modifier = Modifier
                        .focusRing(focusedOption == base + separators.size)
                        .semantics { contentDescription = numberedDesc },
                ) { onAction(TextRepeatAction.Numbered(!options.numbered)) }
                val alternateDesc = stringResource(R.string.ime_repeat_alternate_desc)
                ToolPanelChip(
                    stringResource(R.string.ime_repeat_alternate_label),
                    selected = options.alternateCase,
                    modifier = Modifier
                        .focusRing(focusedOption == base + separators.size + 1)
                        .semantics { contentDescription = alternateDesc },
                ) { onAction(TextRepeatAction.AlternateCase(!options.alternateCase)) }
            }
        }
        // The preview: what Insert writes, as much of it as fits, and the
        // figures under it. The character count runs to its new value.
        val cardShape = kb.cardShape()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = pulse.value
                    scaleY = pulse.value
                }
                .clip(cardShape)
                .frostGlass(kb, cardShape, FrostSurface.CARDS)
                .background(kb.chip)
                .chipBorder(kb, cardShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val preview = remember(built.text, options.mode) { previewOf(built.text, options.mode) }
            MotionCrossfade(target = preview, animate = motion, label = "repeatPreview") { shown ->
                Text(
                    shown.ifEmpty { stringResource(R.string.ime_repeat_empty_info) },
                    color = if (shown.isEmpty()) kb.secondaryText else kb.chipText,
                    fontSize = 13.sp,
                    maxLines = if (options.mode == TextRepeat.Mode.STAIRS) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val shownChars by animateIntAsState(
                built.text.length,
                animationSpec = if (motion) tween(COUNT_UP_MS) else snap(),
                label = "repeatChars",
            )
            val meta = when {
                built.text.isEmpty() && built.truncated -> stringResource(R.string.ime_repeat_too_long_info)
                built.text.isEmpty() -> ""
                built.truncated -> stringResource(R.string.ime_repeat_cut_info, built.copies, spec.maxChars)
                else -> stringResource(R.string.ime_repeat_meta_info, built.copies, shownChars)
            }
            val run = ui.run
            val runNote = when {
                run == null -> null
                state.settings.textRepeat.chipInsert == TextRepeat.ChipInsert.REPLACE_RUN ->
                    stringResource(R.string.ime_repeat_run_replace_info, run.count)
                else -> stringResource(R.string.ime_repeat_run_append_info, run.count)
            }
            if (meta.isNotEmpty() || runNote != null) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        meta,
                        color = if (built.truncated) kb.accent else kb.secondaryText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (runNote != null) {
                        Text(runNote, color = kb.accent, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
        // Insert, Copy, Undo, and the texts repeated before.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolPanelChip(
                stringResource(R.string.ime_repeat_insert_action),
                enabled = canInsert,
                modifier = Modifier.focusRing(focusedAction == 0),
            ) { onAction(TextRepeatAction.Insert) }
            ToolPanelChip(
                stringResource(CommonR.string.common_copy),
                enabled = canInsert,
                modifier = Modifier.focusRing(focusedAction == 1),
            ) { onAction(TextRepeatAction.Copy) }
            ToolPanelChip(
                stringResource(CommonR.string.common_undo),
                enabled = ui.lastInsert != null,
                modifier = Modifier.focusRing(focusedAction == 2),
            ) { onAction(TextRepeatAction.Undo) }
            if (ui.recents.isNotEmpty()) {
                Text(
                    stringResource(R.string.ime_repeat_recent_label),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
                val recentDesc = stringResource(R.string.ime_repeat_recent_desc)
                ui.recents.forEach { recent ->
                    ToolPanelChip(
                        recent.replace("\n", "↵"),
                        modifier = Modifier.semantics { contentDescription = "$recentDesc: $recent" },
                    ) { onAction(TextRepeatAction.PickRecent(recent)) }
                }
                ToolPanelChip(stringResource(R.string.ime_repeat_recent_clear)) { onAction(TextRepeatAction.ClearRecents) }
            }
        }
    }
}

/**
 * The preview line. Line breaks show as ↵ so a two-line preview holds the
 * shape of the output; a staircase keeps its lines, each stepped in one more
 * space, so the three the card has room for draw the stairs.
 */
private fun previewOf(text: String, mode: TextRepeat.Mode): String {
    if (text.isEmpty()) return ""
    val cut = text.take(PREVIEW_CHARS)
    if (mode == TextRepeat.Mode.STAIRS && '\n' in cut) {
        return cut.split('\n').take(3).mapIndexed { index, line -> " ".repeat(index) + line }.joinToString("\n")
    }
    return cut.replace("\n", "↵ ")
}

/** One of the panel's boxes: text or placeholder, the shared caret, and a × to empty it. */
@Composable
private fun RepeatPill(
    text: String,
    placeholder: String,
    active: Boolean,
    ringed: Boolean,
    modifier: Modifier = Modifier,
    onClear: () -> Unit,
    onTap: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .focusRing(ringed, shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = text.replace("\n", "↵"),
            placeholder = placeholder,
            active = active,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (text.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_repeat_clear_desc, placeholder),
                tint = kb.secondaryText,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
            )
        }
    }
}

/**
 * Keeps calling [onStep] while the finger stays down: nothing for the first
 * [HOLD_DELAY_MS], then faster and faster, the way a key repeats. The up
 * that ends a hold is consumed on the initial pass, so the chip's own click
 * does not add one more step on release; a plain tap is left to the click.
 */
internal fun Modifier.repeatOnHold(enabled: Boolean, onStep: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(onStep) {
            coroutineScope {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var held = false
                    val job = launch {
                        delay(HOLD_DELAY_MS)
                        var interval = HOLD_START_MS
                        while (isActive) {
                            held = true
                            onStep()
                            delay(interval)
                            interval = (interval - HOLD_ACCEL_MS).coerceAtLeast(HOLD_MIN_MS)
                        }
                    }
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    job.cancel()
                    if (held) up?.consume()
                }
            }
        }
    }

private const val PREVIEW_CHARS = 200
private const val COUNT_UP_MS = 220
private const val PULSE_SCALE = 1.04f
private const val PULSE_UP_MS = 90
private const val PULSE_DOWN_MS = 160
private const val HOLD_DELAY_MS = 400L
private const val HOLD_START_MS = 120L
private const val HOLD_MIN_MS = 40L
private const val HOLD_ACCEL_MS = 10L
