package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.DayPart
import com.wasimaster.wmkeyboard.core.clock.IcuZoneNames
import com.wasimaster.wmkeyboard.core.clock.PlannerDay
import com.wasimaster.wmkeyboard.core.clock.ZoneCatalog
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.core.clock.MeetingPlanner
import java.util.TimeZone

/**
 * The meeting planner: one strip per place across the same 24 hours, shaded
 * by working hours, awake hours and night. The columns where everyone is at
 * work glow. Drag along the strips and every place's time follows the
 * finger; Insert types the moment in all of them at once, and Discord types
 * it as a timestamp each reader sees in their own zone.
 */
@Composable
internal fun PlannerTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val now = rememberNow(60_000L)
    val home = TimeZone.getDefault()
    if (ui.config.zones.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Group,
            title = stringResource(R.string.ime_clock_planner_empty_title),
            body = stringResource(R.string.ime_clock_planner_empty_body),
        ) {
            PillButton(stringResource(R.string.ime_clock_add_city), primary = true) {
                act(ClockAction.Tab(com.wasimaster.wmkeyboard.core.clock.ClockTab.CLOCKS))
                act(ClockAction.SearchOpen)
            }
        }
        return
    }
    val dayStart = now + ui.planner.dayOffset * DAY_MS
    val plan = remember(dayStart / SLOT_BUCKET, ui.config) {
        MeetingPlanner.plan(
            dayStart, home,
            listOf(home) + ui.config.zones.map { TimeZone.getTimeZone(it.zoneId) },
            workStart = ui.config.workStartMinutes, workEnd = ui.config.workEndMinutes,
        )
    }
    val currentSlot = if (ui.planner.dayOffset == 0) ((now - plan.slots.first()) / PlannerDay.SLOT_MS).toInt() else null
    val slot = ui.planner.slot ?: currentSlot ?: plan.best ?: 0
    val at = plan.slots.getOrElse(slot) { now }
    val overlap = remember(plan) { plan.overlap.toSet() }
    val names = remember(ui.config.zones) {
        listOf(IcuZoneNames().city(home.id) ?: ZoneCatalog.fallbackCity(home.id)) +
            ui.config.zones.map { z -> z.label.ifEmpty { IcuZoneNames().city(z.zoneId) ?: ZoneCatalog.fallbackCity(z.zoneId) } }
    }
    val zones = remember(ui.config.zones) { listOf(home) + ui.config.zones.map { TimeZone.getTimeZone(it.zoneId) } }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_clock_previous_day_desc), size = 30.dp) {
                act(ClockAction.PlannerDay(-1))
            }
            Text(
                dayTitle(ui.planner.dayOffset, dayStart, home),
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_clock_next_day_desc), size = 30.dp) {
                act(ClockAction.PlannerDay(1))
            }
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Outlined.AutoAwesome, stringResource(R.string.ime_clock_best_time), size = 30.dp) {
                act(ClockAction.PlannerBest)
            }
            Spacer(Modifier.width(6.dp))
            PillButton(stringResource(R.string.ime_clock_format_discord), height = 30.dp) {
                act(ClockAction.PlannerDiscord)
            }
            Spacer(Modifier.width(6.dp))
            PillButton(stringResource(R.string.ime_clock_insert), icon = Icons.Outlined.ContentPaste, primary = true, height = 30.dp) {
                act(ClockAction.PlannerInsert)
            }
        }
        Text(
            if (overlap.isEmpty()) {
                stringResource(R.string.ime_clock_planner_no_overlap)
            } else {
                stringResource(R.string.ime_clock_planner_overlap, ClockText.duration(overlap.size * PlannerDay.SLOT_MS))
            },
            color = kb.secondaryText,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            plan.parts.forEachIndexed { row, parts ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                    Column(Modifier.width(96.dp)) {
                        Text(
                            names.getOrElse(row) { "" },
                            color = kb.suggestionText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            ClockFormats.time(at, zones[row], ui.hour24) + dayMark(at, zones[row], home),
                            color = kb.secondaryText,
                            style = digits(11.sp, FontWeight.Normal),
                            maxLines = 1,
                        )
                    }
                    Strip(
                        parts = parts,
                        overlap = overlap,
                        cursor = slot,
                        onSlot = { act(ClockAction.PlannerSlot(it)) },
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp),
                    )
                }
            }
        }
    }
}

/** Half-hour cells; a drag or a tap moves the cursor, with a tick at each cell. */
@Composable
private fun Strip(
    parts: List<DayPart>,
    overlap: Set<Int>,
    cursor: Int,
    onSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    val report by rememberUpdatedState(onSlot)
    val last = remember { intArrayOf(-1) }
    fun move(x: Float, width: Int) {
        val s = (x / width * PlannerDay.SLOTS).toInt().coerceIn(0, PlannerDay.SLOTS - 1)
        if (s != last[0]) {
            last[0] = s
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            report(s)
        }
    }
    val description = stringResource(R.string.ime_clock_planner_strip_desc)
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { move(it.x, size.width) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { move(it.x, size.width) }) { change, _ ->
                    move(change.position.x, size.width)
                }
            },
    ) {
        val cell = size.width / PlannerDay.SLOTS
        val gap = 0.6.dp.toPx()
        parts.forEachIndexed { i, part ->
            val color = when (part) {
                DayPart.WORK -> kb.accent.copy(alpha = if (i in overlap) 0.95f else 0.55f)
                DayPart.AWAKE -> kb.accent.copy(alpha = 0.2f)
                DayPart.NIGHT -> kb.secondaryText.copy(alpha = 0.12f)
            }
            drawRoundRect(
                color,
                topLeft = Offset(i * cell + gap / 2, 0f),
                size = Size(cell - gap, size.height),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
        val x = cursor * cell + cell / 2
        drawLine(kb.suggestionText, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun dayTitle(offset: Int, at: Long, zone: TimeZone): String = when (offset) {
    0 -> stringResource(R.string.ime_clock_today)
    1 -> stringResource(R.string.ime_clock_tomorrow)
    -1 -> stringResource(R.string.ime_clock_yesterday)
    else -> ClockFormats.shortDate(at, zone)
}

/** "+1" or "−1" after a time that falls on another day than at home. */
private fun dayMark(at: Long, zone: TimeZone, home: TimeZone): String =
    when (ClockFormats.dayShift(at, zone, home)) {
        1 -> " +1"
        -1 -> " −1"
        else -> ""
    }

private const val DAY_MS = 86_400_000L

/** Recompute the plan at most once a half hour, not every minute's tick. */
private const val SLOT_BUCKET = 30 * 60_000L
