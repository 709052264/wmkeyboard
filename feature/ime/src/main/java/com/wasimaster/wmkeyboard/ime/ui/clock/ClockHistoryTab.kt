package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.HistoryEntry
import com.wasimaster.wmkeyboard.core.clock.HistoryKind
import com.wasimaster.wmkeyboard.core.clock.HistoryOutcome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Alarm
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassBottom
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockController
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import java.util.TimeZone

/**
 * What rang and what was timed, newest first, grouped by day, with today's
 * totals on top. A stopwatch run opens to show its laps; any line can be
 * typed into the field.
 */
@Composable
internal fun HistoryTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val now = rememberNow(60_000L)
    val zone = TimeZone.getDefault()
    val filter = ui.historyFilter
    val entries = remember(ui.data.history, filter) { ui.data.history.filter { filter == null || it.kind == filter } }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolPanelChip(stringResource(R.string.ime_clock_all), selected = filter == null) { act(ClockAction.HistoryFilter(null)) }
                HistoryKind.entries.forEach { k ->
                    ToolPanelChip(stringResource(ClockController.kindRes(k)), selected = filter == k) {
                        act(ClockAction.HistoryFilter(if (filter == k) null else k))
                    }
                }
            }
            if (entries.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                RoundButton(Icons.Outlined.DeleteSweep, stringResource(R.string.ime_clock_clear_history), size = 30.dp) {
                    act(ClockAction.HistoryClear)
                }
            }
        }
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.ime_clock_history_empty_title),
                body = stringResource(R.string.ime_clock_history_empty_body),
            )
            return@Column
        }
        TodaySummary(ui.data.history, now)
        val grouped = remember(entries) { entries.groupBy { ClockFormats.dayKey(it.at, zone) } }
        val today = ClockFormats.dayKey(now, zone)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            grouped.forEach { (day, rows) ->
                item(key = "day-$day") {
                    SectionLabel(
                        when (day) {
                            today -> stringResource(R.string.ime_clock_today)
                            today - 1 -> stringResource(R.string.ime_clock_yesterday)
                            else -> ClockFormats.shortDate(rows.first().at, zone)
                        },
                    )
                }
                items(rows, key = { it.id }) { e ->
                    HistoryRow(e, ui, open = e.id in ui.historyOpen, act = act)
                }
            }
        }
    }
}

/** Today's focus time and how many things rang, the two numbers people check. */
@Composable
private fun TodaySummary(history: List<HistoryEntry>, now: Long) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val today = ClockFormats.dayKey(now, zone)
    val todays = history.filter { ClockFormats.dayKey(it.at, zone) == today }
    val focus = todays.filter { it.kind == HistoryKind.POMODORO && it.outcome == HistoryOutcome.DONE }.sumOf { it.durationMs }
    val timed = todays.filter { it.kind == HistoryKind.TIMER || it.kind == HistoryKind.STOPWATCH }.sumOf { it.durationMs }
    if (focus == 0L && timed == 0L) return
    Row(Modifier.padding(start = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (focus > 0) {
            Text(stringResource(R.string.ime_clock_focus_today, ClockText.duration(focus)), color = kb.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (timed > 0) {
            Text(stringResource(R.string.ime_clock_timed_today, ClockText.duration(timed)), color = kb.secondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HistoryRow(e: HistoryEntry, ui: ClockUi, open: Boolean, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val expandable = e.kind == HistoryKind.STOPWATCH && e.laps.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(enabled = expandable) { act(ClockAction.HistoryToggle(e.id)) }
            .padding(horizontal = 4.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(kb.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(kindIcon(e.kind), contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    e.label.ifEmpty { stringResource(ClockController.kindRes(e.kind)) },
                    color = kb.suggestionText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        ClockFormats.time(e.at, zone, ui.hour24),
                        e.durationMs.takeIf { it > 0 }?.let { ClockText.duration(it, seconds = true) },
                        outcomeLabel(e.outcome),
                    ).joinToString(" · "),
                    color = if (e.outcome == HistoryOutcome.MISSED) kb.accent else kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            RoundButton(Icons.Outlined.ContentPaste, stringResource(R.string.ime_clock_insert), size = 28.dp) {
                act(ClockAction.HistoryInsert(e.id))
            }
        }
        AnimatedVisibility(visible = open && expandable) {
            Column(Modifier.padding(start = 36.dp, top = 4.dp)) {
                val lengths = e.laps.mapIndexed { i, at -> at - (if (i == 0) 0 else e.laps[i - 1]) }
                lengths.forEachIndexed { i, len ->
                    Row {
                        Text(stringResource(R.string.ime_clock_lap_number, i + 1), color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.width(48.dp))
                        Text(ClockFormats.stopwatch(len), color = kb.suggestionText, style = digits(11.sp, FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
private fun outcomeLabel(outcome: HistoryOutcome): String? = when (outcome) {
    HistoryOutcome.DONE -> null
    HistoryOutcome.SNOOZED -> stringResource(R.string.ime_clock_outcome_snoozed)
    HistoryOutcome.MISSED -> stringResource(R.string.ime_clock_outcome_missed)
    HistoryOutcome.CANCELLED -> stringResource(R.string.ime_clock_outcome_stopped)
}

private fun kindIcon(kind: HistoryKind): ImageVector = when (kind) {
    HistoryKind.ALARM -> Icons.Outlined.Alarm
    HistoryKind.TIMER -> Icons.Outlined.HourglassBottom
    HistoryKind.STOPWATCH -> Icons.Outlined.Timer
    HistoryKind.POMODORO -> Icons.Outlined.Psychology
}
