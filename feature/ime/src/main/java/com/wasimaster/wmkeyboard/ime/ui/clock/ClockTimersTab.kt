package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.ClockNotifications
import com.wasimaster.wmkeyboard.core.clock.RunningTimer
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockController
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.tools.R as ToolsR

/**
 * Timers. With timers running the tab is their list, each a ring that drains
 * as the time runs out; "New timer" (or any preset) adds another. With none,
 * or while one is being set, it is a keypad that fills from the right, the
 * way a microwave's does: 1, 3, 0 is a minute and a half.
 */
@Composable
internal fun TimersTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    var entering by remember { mutableStateOf(false) }
    val timers = ui.data.timers
    if (timers.isEmpty() || entering || ui.timerDigits.isNotEmpty()) {
        TimerEntry(
            ui = ui,
            canGoBack = timers.isNotEmpty(),
            onBack = {
                entering = false
                act(ClockAction.TimerClear)
            },
            onStarted = { entering = false },
            act = act,
        )
    } else {
        RunningList(ui, onNew = { entering = true }, act = act)
    }
}

@Composable
private fun RunningList(ui: ClockUi, onNew: () -> Unit, act: (ClockAction) -> Unit) {
    val now = rememberNow(RING_TICK_MS)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ui.data.timers, key = { it.id }) { t ->
            TimerCard(t, now, ui, act, Modifier.animateItem())
        }
        item(key = "new") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(stringResource(R.string.ime_clock_new_timer), icon = Icons.Outlined.Add, primary = true, height = 32.dp, onClick = onNew)
                ui.data.presets.forEach { p ->
                    ToolPanelChip(presetLabel(p.label, p.durationMs)) { act(ClockAction.StartPreset(p.id)) }
                }
            }
        }
    }
}

@Composable
private fun TimerCard(t: RunningTimer, now: Long, ui: ClockUi, act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val left = t.remainingAt(now)
    val title = t.label.ifEmpty {
        t.pomodoro?.let { stringResource(ClockNotifications.phaseRes(it.phase)) } ?: stringResource(R.string.ime_clock_timer)
    }
    ClockCard(modifier = modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = t.progressAt(now),
                paused = t.paused,
                modifier = Modifier.size(60.dp),
            ) {
                Text(
                    ClockFormats.countdown(left),
                    color = kb.suggestionText,
                    style = digits(if (left >= HOUR_MS) 11.sp else 14.sp, FontWeight.Medium),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (t.paused) {
                        stringResource(R.string.ime_clock_paused)
                    } else {
                        stringResource(R.string.ime_clock_ends_at, ClockFormats.time(t.endsAt, java.util.TimeZone.getDefault(), ui.hour24))
                    },
                    color = if (t.paused) kb.accent else kb.secondaryText,
                    fontSize = 11.sp,
                )
                t.pomodoro?.let { run ->
                    val plan = ui.config.pomodoro
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 3.dp)) {
                        repeat(plan.longEvery.coerceAtLeast(1)) { i ->
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (i < plan.dotsFilled(run)) kb.accent else kb.secondaryText.copy(alpha = 0.3f)),
                            )
                        }
                    }
                }
            }
            ToolPanelChip(stringResource(R.string.ime_clock_plus_minute)) { act(ClockAction.ExtendTimer(t.id, MINUTE_MS)) }
            Spacer(Modifier.width(6.dp))
            if (t.paused) {
                RoundButton(Icons.Outlined.PlayArrow, stringResource(R.string.ime_clock_resume), primary = true, size = 38.dp) {
                    act(ClockAction.ResumeTimer(t.id))
                }
            } else {
                RoundButton(Icons.Outlined.Pause, stringResource(R.string.ime_clock_pause), primary = true, size = 38.dp) {
                    act(ClockAction.PauseTimer(t.id))
                }
            }
            Spacer(Modifier.width(4.dp))
            if (t.paused && t.pausedRemainingMs < t.totalMs) {
                RoundButton(Icons.Outlined.Replay, stringResource(R.string.ime_clock_reset), size = 34.dp) {
                    act(ClockAction.ResetTimer(t.id))
                }
            } else {
                RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_clock_stop), size = 34.dp) {
                    act(ClockAction.CancelTimer(t.id))
                }
            }
        }
    }
}

/** A ring that empties clockwise as time runs out. Paused, it dims. */
@Composable
internal fun ProgressRing(progress: Float, paused: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val kb = LocalKbTheme.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(kb.secondaryText.copy(alpha = 0.18f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            drawArc(
                if (paused) kb.secondaryText else kb.accent,
                -90f,
                360f * (1f - progress),
                false,
                topLeft,
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

@Composable
private fun TimerEntry(
    ui: ClockUi,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    act: (ClockAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val ms = ClockController.msOfDigits(ui.timerDigits)
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolPanelChip(stringResource(R.string.ime_clock_mode_timer), selected = !ui.pomodoroMode) {
                    act(ClockAction.PomodoroMode(false))
                }
                Spacer(Modifier.width(6.dp))
                ToolPanelChip(stringResource(R.string.ime_clock_mode_pomodoro), selected = ui.pomodoroMode) {
                    act(ClockAction.PomodoroMode(true))
                }
                Spacer(Modifier.weight(1f))
                if (canGoBack) {
                    RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_clock_cancel), size = 30.dp, onClick = onBack)
                }
            }
            if (ui.pomodoroMode) {
                PomodoroIntro(ui)
                Spacer(Modifier.weight(1f))
                PillButton(
                    stringResource(R.string.ime_clock_start_focus),
                    icon = Icons.Outlined.PlayArrow,
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    act(ClockAction.StartPomodoro)
                    onStarted()
                }
                return@Column
            }
            Spacer(Modifier.height(4.dp))
            Readout(ui.timerDigits)
            Text(
                if (ms > 0) ClockText.duration(ms, seconds = true) else stringResource(R.string.ime_clock_type_duration),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            PresetRow(ui, act)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Outlined.Label, ui.timerLabel.ifEmpty { stringResource(R.string.ime_clock_add_label) }, size = 36.dp) {
                    act(ClockAction.TimerLabel)
                }
                PillButton(
                    stringResource(R.string.ime_clock_start),
                    icon = Icons.Outlined.PlayArrow,
                    primary = true,
                    enabled = ms > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    act(ClockAction.StartTimer)
                    onStarted()
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Keypad(act, Modifier.width(150.dp))
    }
}

/** hh mm ss, the typed digits bright and the padding zeros faint. */
@Composable
private fun Readout(digits: String) {
    val kb = LocalKbTheme.current
    val padded = digits.padStart(6, '0')
    val typedFrom = 6 - digits.length
    Row(verticalAlignment = Alignment.Bottom) {
        listOf(0 to "h", 2 to "m", 4 to "s").forEach { (at, unit) ->
            for (i in at until at + 2) {
                Text(
                    padded[i].toString(),
                    color = if (i >= typedFrom) kb.suggestionText else kb.secondaryText.copy(alpha = 0.5f),
                    style = digits(32.sp),
                )
            }
            Text(unit, color = kb.secondaryText, fontSize = 13.sp, modifier = Modifier.padding(start = 1.dp, end = 6.dp, bottom = 6.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ui.data.presets.forEach { p ->
            val shape = kb.chipShape()
            Text(
                presetLabel(p.label, p.durationMs),
                color = kb.chipText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(shape)
                    .background(kb.chip)
                    .combinedClickable(
                        onClick = { act(ClockAction.TimerClear); act(ClockAction.TimerAdd(p.durationMs)) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            act(ClockAction.DeletePreset(p.id))
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (ui.timerDigits.isNotEmpty()) {
            ToolPanelChip(stringResource(R.string.ime_clock_save_preset), icon = null) { act(ClockAction.SavePreset) }
        }
    }
}

@Composable
private fun presetLabel(label: String, ms: Long): String {
    val time = ClockText.duration(ms, seconds = true)
    return if (label.isEmpty()) time else "$label · $time"
}

/** 1 to 9, then 00, 0 and backspace. A hold on backspace clears. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(act: (ClockAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    val rows = listOf("123", "456", "789", "#0<")
    val backspace = stringResource(R.string.ime_clock_backspace_desc)
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c ->
                    val shape = kb.chipShape()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(kb.key)
                            .combinedClickable(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    when (c) {
                                        '<' -> act(ClockAction.TimerBackspace)
                                        '#' -> { act(ClockAction.TimerDigit('0')); act(ClockAction.TimerDigit('0')) }
                                        else -> act(ClockAction.TimerDigit(c))
                                    }
                                },
                                onLongClick = if (c == '<') {
                                    { act(ClockAction.TimerClear) }
                                } else {
                                    null
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (c) {
                            '<' -> Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = backspace, tint = kb.keyText, modifier = Modifier.size(18.dp))
                            '#' -> Text("00", color = kb.keyText, style = digits(17.sp, FontWeight.Normal))
                            else -> Text(c.toString(), color = kb.keyText, style = digits(18.sp, FontWeight.Normal))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PomodoroIntro(ui: ClockUi) {
    val kb = LocalKbTheme.current
    val plan = ui.config.pomodoro
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.ime_clock_pomodoro_title), color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        listOf(
            ToolsR.string.core_tools_clock_focus to plan.focusMinutes,
            ToolsR.string.core_tools_clock_short_break to plan.shortBreakMinutes,
            ToolsR.string.core_tools_clock_long_break to plan.longBreakMinutes,
        ).forEach { (res, minutes) ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text(stringResource(res), color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Text(ClockText.duration(minutes * MINUTE_MS), color = kb.suggestionText, fontSize = 12.sp)
            }
        }
        Text(
            pluralStringResource(R.plurals.ime_clock_pomodoro_rhythm, plan.longEvery, plan.longEvery),
            color = kb.secondaryText,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private const val RING_TICK_MS = 250L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
