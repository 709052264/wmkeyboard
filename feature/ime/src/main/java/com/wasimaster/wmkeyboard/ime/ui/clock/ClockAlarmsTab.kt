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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.Alarm
import com.wasimaster.wmkeyboard.core.clock.AlarmMath
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.Weekdays
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Alarm as AlarmIcon
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Label
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SkipNext
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Vibration
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.AlarmEdit
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Alarms: what rings next and when, a quick "in 20 minutes", and a card per
 * alarm with its switch. A tap on a card opens the editor in place; a hold
 * offers Skip next and Delete.
 */
@Composable
internal fun AlarmsTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val edit = ui.alarmEdit
    if (edit != null) {
        AlarmEditor(edit, ui, act)
        return
    }
    val now = rememberNow(30_000L)
    val alarms = ui.data.alarms
    var menuFor by remember { mutableStateOf<Long?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (alarms.isNotEmpty()) {
            if (!ui.exactAllowed) {
                item(key = "exact") {
                    Banner(stringResource(R.string.ime_clock_exact_banner), stringResource(R.string.ime_clock_allow)) {
                        act(ClockAction.AskExact)
                    }
                }
            } else if (!ui.fullScreenAllowed) {
                item(key = "full") {
                    Banner(stringResource(R.string.ime_clock_full_screen_banner), stringResource(R.string.ime_clock_allow)) {
                        act(ClockAction.AskFullScreen)
                    }
                }
            } else if (!ui.notificationsAllowed) {
                item(key = "notify") {
                    Banner(stringResource(R.string.ime_clock_notifications_banner), stringResource(R.string.ime_clock_open_app)) {
                        act(ClockAction.OpenApp)
                    }
                }
            }
        }
        item(key = "next") { NextAlarm(now, ui, act) }
        items(alarms, key = { it.id }) { alarm ->
            Column(Modifier.animateItem()) {
                AlarmCard(
                    alarm = alarm,
                    now = now,
                    ui = ui,
                    menuOpen = menuFor == alarm.id,
                    onTap = {
                        menuFor = null
                        act(ClockAction.EditAlarm(alarm.id))
                    },
                    onHold = { menuFor = if (menuFor == alarm.id) null else alarm.id },
                    act = act,
                )
            }
        }
    }
}

/** The headline: the next ring and how long until it, plus the quick ones. */
@Composable
private fun NextAlarm(now: Long, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val next = AlarmMath.nextOfAll(ui.data.alarms, now)
    Column(Modifier.fillMaxWidth()) {
        if (next == null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Outlined.AlarmIcon, contentDescription = null, tint = kb.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.ime_clock_no_alarms_title), color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ime_clock_no_alarms_body), color = kb.secondaryText, fontSize = 12.sp)
                }
            }
        } else {
            val (alarm, at) = next
            val zone = TimeZone.getDefault()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ime_clock_rings_in, ClockText.duration(at - now)),
                        color = kb.suggestionText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOf(ClockFormats.shortDate(at, zone) + ", " + ClockFormats.time(at, zone, ui.hour24), alarm.label)
                            .filter { it.isNotEmpty() }.joinToString(" · "),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(stringResource(R.string.ime_clock_new_alarm), icon = Icons.Outlined.Add, primary = true, height = 32.dp) {
                act(ClockAction.NewAlarm)
            }
            QUICK_MINUTES.forEach { m ->
                ToolPanelChip(quickLabel(m)) { act(ClockAction.QuickAlarm(m)) }
            }
        }
    }
}

@Composable
private fun quickLabel(minutes: Int): String =
    stringResource(R.string.ime_clock_quick_in, ClockText.duration(minutes * 60_000L))

@Composable
private fun AlarmCard(
    alarm: Alarm,
    now: Long,
    ui: ClockUi,
    menuOpen: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    act: (ClockAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val at = remember(alarm.hour, alarm.minute) {
        Calendar.getInstance(zone).apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
        }.timeInMillis
    }
    val dim = if (alarm.enabled) 1f else 0.5f
    val skipping = AlarmMath.isSkipping(alarm, now)
    val snoozed = alarm.snoozedUntil > now
    ClockCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = menuOpen,
        onClick = onTap,
        onLongClick = onHold,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .alpha(dim),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(ClockFormats.timeDigits(at, zone, ui.hour24), color = kb.suggestionText, style = digits(30.sp))
                    val ampm = ClockFormats.amPm(at, zone, ui.hour24)
                    if (ampm.isNotEmpty()) {
                        Text(ampm, color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.padding(start = 3.dp, bottom = 5.dp))
                    }
                    if (alarm.label.isNotEmpty()) {
                        Text(
                            alarm.label,
                            color = kb.secondaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 10.dp, bottom = 5.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DayDots(alarm.days)
                    val note = when {
                        snoozed -> stringResource(R.string.ime_clock_snoozed_until, ClockFormats.time(alarm.snoozedUntil, zone, ui.hour24))
                        skipping -> stringResource(R.string.ime_clock_skipping_next)
                        !alarm.repeats && alarm.enabled -> AlarmMath.nextFire(alarm, now)?.let { onceLabel(it, now) }.orEmpty()
                        else -> ""
                    }
                    if (note.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(note, color = if (snoozed || skipping) kb.accent else kb.secondaryText, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            ClockSwitch(
                on = alarm.enabled,
                description = stringResource(R.string.ime_clock_alarm_switch_desc),
            ) { act(ClockAction.ToggleAlarm(alarm.id, it)) }
        }
        AnimatedVisibility(visible = menuOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            ) {
                if (alarm.repeats && alarm.enabled) {
                    PillButton(
                        stringResource(if (skipping) R.string.ime_clock_unskip else R.string.ime_clock_skip_next),
                        icon = Icons.Outlined.SkipNext,
                        height = 32.dp,
                    ) { act(ClockAction.SkipAlarm(alarm.id)) }
                }
                PillButton(stringResource(R.string.ime_clock_delete), icon = Icons.Outlined.Delete, height = 32.dp) {
                    act(ClockAction.DeleteAlarm(alarm.id))
                }
            }
        }
    }
}

@Composable
private fun onceLabel(at: Long, now: Long): String {
    val zone = TimeZone.getDefault()
    val days = ClockFormats.dayKey(at, zone) - ClockFormats.dayKey(now, zone)
    return stringResource(
        when (days) {
            0L -> R.string.ime_clock_once_today
            1L -> R.string.ime_clock_once_tomorrow
            else -> R.string.ime_clock_once
        },
    )
}

/** Seven small letters, the ringing days lit; nothing for a one-off. */
@Composable
private fun DayDots(days: Int) {
    val kb = LocalKbTheme.current
    if (days == 0) return
    val letters = remember { weekdayLetters() }
    val description = daysDescription(days)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        for (i in 0 until 7) {
            val on = Weekdays.has(days, i)
            Text(
                letters[i],
                color = if (on) kb.accent else kb.secondaryText.copy(alpha = 0.45f),
                fontSize = 11.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun daysDescription(days: Int): String = when (days) {
    Weekdays.ALL -> stringResource(R.string.ime_clock_every_day)
    Weekdays.WORKDAYS -> stringResource(R.string.ime_clock_weekdays)
    Weekdays.WEEKEND -> stringResource(R.string.ime_clock_weekends)
    else -> {
        val names = remember { weekdayShort() }
        (0 until 7).filter { Weekdays.has(days, it) }.joinToString(", ") { names[it] }
    }
}

/** The editor: drums for the time, then days, label, vibration and snooze, then Save. */
@Composable
private fun AlarmEditor(edit: AlarmEdit, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val now = rememberNow(30_000L)
    val letters = remember { weekdayLetters() }
    val vibrateDescription = stringResource(R.string.ime_clock_vibrate_desc)
    val preview = AlarmMath.nextFire(edit.toAlarm(null).copy(enabled = true), now)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TimeWheel(
                hour = edit.hour,
                minute = edit.minute,
                hour24 = ui.hour24,
                amLabel = remember { DateFormatSymbols.getInstance().amPmStrings[0] },
                pmLabel = remember { DateFormatSymbols.getInstance().amPmStrings[1] },
                hourDescription = stringResource(R.string.ime_clock_hour_desc),
                minuteDescription = stringResource(R.string.ime_clock_minute_desc),
                onChange = { h, m -> act(ClockAction.AlarmDraft(edit.copy(hour = h, minute = m))) },
                modifier = Modifier.width(if (ui.hour24) 132.dp else 180.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (i in 0 until 7) {
                        val on = Weekdays.has(edit.days, i)
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (on) kb.chipActive else kb.chip)
                                .clickable { act(ClockAction.AlarmDraft(edit.copy(days = Weekdays.toggle(edit.days, i)))) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(letters[i], color = if (on) kb.chipActiveText else kb.chipText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ToolPanelChip(stringResource(R.string.ime_clock_weekdays), selected = edit.days == Weekdays.WORKDAYS) {
                        act(ClockAction.AlarmDraft(edit.copy(days = if (edit.days == Weekdays.WORKDAYS) 0 else Weekdays.WORKDAYS)))
                    }
                    ToolPanelChip(stringResource(R.string.ime_clock_weekends), selected = edit.days == Weekdays.WEEKEND) {
                        act(ClockAction.AlarmDraft(edit.copy(days = if (edit.days == Weekdays.WEEKEND) 0 else Weekdays.WEEKEND)))
                    }
                    ToolPanelChip(stringResource(R.string.ime_clock_every_day), selected = edit.days == Weekdays.ALL) {
                        act(ClockAction.AlarmDraft(edit.copy(days = if (edit.days == Weekdays.ALL) 0 else Weekdays.ALL)))
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillButton(
                        edit.label.ifEmpty { stringResource(R.string.ime_clock_add_label) },
                        icon = Icons.Outlined.Label,
                        height = 30.dp,
                    ) { act(ClockAction.AlarmLabel) }
                    PillButton(
                        "",
                        icon = Icons.Outlined.Vibration,
                        primary = edit.vibrate,
                        height = 30.dp,
                        modifier = Modifier.semantics { contentDescription = vibrateDescription },
                    ) { act(ClockAction.AlarmDraft(edit.copy(vibrate = !edit.vibrate))) }
                    val snooze = edit.snoozeMinutes.takeIf { it > 0 } ?: ui.config.snoozeMinutes
                    ToolPanelChip(
                        pluralStringResource(R.plurals.ime_clock_snooze_minutes, snooze, snooze),
                        selected = edit.snoozeMinutes > 0,
                    ) {
                        val i = SNOOZE_STEPS.indexOf(edit.snoozeMinutes)
                        act(ClockAction.AlarmDraft(edit.copy(snoozeMinutes = SNOOZE_STEPS[(i + 1) % SNOOZE_STEPS.size])))
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                preview?.let { stringResource(R.string.ime_clock_rings_in, ClockText.duration(it - now)) }.orEmpty(),
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (edit.id != null) {
                RoundButton(Icons.Outlined.Delete, stringResource(R.string.ime_clock_delete), size = 34.dp) {
                    act(ClockAction.DeleteAlarm(edit.id))
                }
            }
            PillButton(stringResource(R.string.ime_clock_cancel)) { act(ClockAction.CancelAlarmEdit) }
            PillButton(stringResource(R.string.ime_clock_save), primary = true) { act(ClockAction.SaveAlarm) }
        }
    }
}

/** The narrow weekday names in the user's language, Monday first. */
private fun weekdayLetters(): List<String> {
    val names = DateFormatSymbols.getInstance(Locale.getDefault())
    val narrow = runCatching {
        val icu = android.icu.text.DateFormatSymbols.getInstance(Locale.getDefault())
        icu.getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, android.icu.text.DateFormatSymbols.NARROW)
    }.getOrNull() ?: names.shortWeekdays.map { it.take(1) }.toTypedArray()
    return (0 until 7).map { narrow[Weekdays.calendarDay(it)] }
}

private fun weekdayShort(): List<String> {
    val names = DateFormatSymbols.getInstance(Locale.getDefault()).shortWeekdays
    return (0 until 7).map { names[Weekdays.calendarDay(it)] }
}

private val QUICK_MINUTES = listOf(10, 20, 30, 60, 90)
private val SNOOZE_STEPS = listOf(0, 5, 10, 15, 20, 30)
