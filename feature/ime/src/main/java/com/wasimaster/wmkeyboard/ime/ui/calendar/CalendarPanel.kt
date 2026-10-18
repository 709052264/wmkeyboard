package com.wasimaster.wmkeyboard.ime.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EventRepeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Group
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notifications
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.AltCalendar
import com.wasimaster.wmkeyboard.core.tools.AltCalendars
import com.wasimaster.wmkeyboard.core.tools.CalendarSystems
import com.wasimaster.wmkeyboard.core.tools.DeviceCalendarEvent
import com.wasimaster.wmkeyboard.core.tools.StoredEvent
import com.wasimaster.wmkeyboard.core.tools.calendar.EditScope
import com.wasimaster.wmkeyboard.core.tools.calendar.EventDate
import com.wasimaster.wmkeyboard.core.tools.calendar.RRule
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.calendar.CalendarAction
import com.wasimaster.wmkeyboard.ime.calendar.CalendarCallbacks
import com.wasimaster.wmkeyboard.ime.calendar.CalendarError
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextEntry
import com.wasimaster.wmkeyboard.ime.calendar.CalendarTextField
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUi
import com.wasimaster.wmkeyboard.ime.calendar.CalendarUndo
import com.wasimaster.wmkeyboard.ime.calendar.CalendarView
import com.wasimaster.wmkeyboard.ime.calendar.ScopePrompt
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.IconHint
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.PanelUndoBar
import com.wasimaster.wmkeyboard.ime.ui.SearchQueryText
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import com.wasimaster.wmkeyboard.ime.ui.clock.Banner
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass

/**
 * The Calendar tool: a month grid with the selected day's events under it,
 * an event's own sheet, and a composer that adds and changes events in the
 * device calendar. The state is [com.wasimaster.wmkeyboard.ime.calendar.CalendarController]'s;
 * this file only draws it.
 *
 * While the keys type into the composer the panel folds down over them, as
 * the Clock's does, and the header becomes the text box.
 */
@Composable
internal fun CalendarPanel(
    state: KeyboardUiState,
    callbacks: CalendarCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val typing = state.calendarText
    // The month view publishes the ring's targets; anywhere else they are
    // published empty, so a count left from the grid never steers the ring.
    if (typing != null || ui.view != CalendarView.MONTH) {
        PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.ACTIONS, count = 0, columns = 3) {}
        PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.RESULTS, count = 0, columns = 7) {}
    }
    FullBleedTool(
        state = state,
        title = if (typing == null && ui.view == CalendarView.MONTH) stringResource(R.string.ime_tool_calendar) else "",
        onClose = onClose,
        compact = typing != null,
        compactHeight = 150.dp,
        headerActions = {
            when {
                typing != null -> TypingHeader(typing, act)
                ui.view == CalendarView.COMPOSE -> ComposeHeader(ui, act)
                ui.view == CalendarView.DETAIL -> DetailHeader(act)
                else -> MonthHeader(act)
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            val reduce = state.settings.reduceMotion
            if (typing != null) {
                TypingBody(ui, typing, act)
            } else {
                AnimatedContent(
                    targetState = ui.view,
                    transitionSpec = {
                        if (reduce) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            val up = targetState.ordinal > initialState.ordinal
                            (slideInVertically(tween(VIEW_MS)) { if (up) it / 8 else -it / 8 } + fadeIn(tween(VIEW_MS))) togetherWith
                                fadeOut(tween(VIEW_MS / 2))
                        }
                    },
                    label = "calendarView",
                    modifier = Modifier.fillMaxSize(),
                ) { view ->
                    when (view) {
                        CalendarView.MONTH -> MonthView(state, ui, act)
                        CalendarView.DETAIL -> ui.detail?.let { EventSheet(it, ui, act) }
                        CalendarView.COMPOSE -> ui.draft?.let { CalendarComposer(it, ui, act) }
                    }
                }
            }
            ui.scopePrompt?.let {
                ScopeSheet(it, act, Modifier.align(Alignment.BottomCenter))
            }
            val undo = ui.undo
            PanelUndoBar(
                text = undo?.let { undoText(it) }.orEmpty(),
                visible = undo != null && typing == null,
                key = undo,
                reduceMotion = reduce,
                onUndo = { act(CalendarAction.Undo) },
                onDismiss = { act(CalendarAction.UndoDismiss) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun undoText(undo: CalendarUndo): String = stringResource(
    when (undo.kind) {
        CalendarUndo.Kind.ADDED -> R.string.ime_calendar_undo_added
        CalendarUndo.Kind.CHANGED -> R.string.ime_calendar_undo_changed
        CalendarUndo.Kind.DELETED -> R.string.ime_calendar_undo_deleted
    },
)

@Composable
internal fun errorText(error: CalendarError): String = stringResource(
    when (error) {
        CalendarError.END_BEFORE_START -> R.string.ime_calendar_error_end
        CalendarError.NO_CALENDAR -> R.string.ime_calendar_error_no_calendar
        CalendarError.NO_ACCESS -> R.string.ime_calendar_error_no_access
        CalendarError.WRITE_FAILED -> R.string.ime_calendar_error_failed
        CalendarError.NO_APP -> R.string.ime_calendar_error_no_app
    },
)

// ---- Headers ----------------------------------------------------------------

@Composable
private fun RowScope.MonthHeader(act: (CalendarAction) -> Unit) {
    Spacer(Modifier.weight(1f))
    PillButton(stringResource(R.string.ime_calendar_new_event), icon = Icons.Outlined.Add, primary = true, height = 30.dp) {
        act(CalendarAction.NewEvent())
    }
    Spacer(Modifier.width(6.dp))
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_calendar_settings_desc),
        active = false,
    ) { act(CalendarAction.OpenSettings) }
}

@Composable
private fun RowScope.DetailHeader(act: (CalendarAction) -> Unit) {
    RoundButton(Icons.Outlined.Close, stringResource(R.string.ime_calendar_back_desc), size = 30.dp) {
        act(CalendarAction.CloseDetail)
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun RowScope.ComposeHeader(ui: CalendarUi, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    Text(
        stringResource(if (ui.editing) R.string.ime_calendar_edit_event else R.string.ime_calendar_new_event),
        color = kb.modifierKeyText,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp),
    )
    PillButton(stringResource(R.string.ime_calendar_cancel), height = 30.dp) { act(CalendarAction.CancelCompose) }
    Spacer(Modifier.width(6.dp))
    PillButton(stringResource(R.string.ime_calendar_save), primary = true, enabled = !ui.busy, height = 30.dp) {
        act(CalendarAction.Save)
    }
    Spacer(Modifier.width(4.dp))
}

/** While the keys type into the composer, the header is the text box and a Done. */
@Composable
private fun RowScope.TypingHeader(entry: CalendarTextEntry, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val placeholder = stringResource(
        when (entry.field) {
            CalendarTextField.TITLE -> R.string.ime_calendar_title_hint
            CalendarTextField.LOCATION -> R.string.ime_calendar_location_hint
            CalendarTextField.NOTES -> R.string.ime_calendar_notes_hint
            CalendarTextField.GUEST -> R.string.ime_calendar_guest_hint
        },
    )
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(start = 6.dp, end = 4.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = entry.text,
            placeholder = placeholder,
            active = true,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
    ToolPanelChip(stringResource(R.string.ime_calendar_done), selected = true) { act(CalendarAction.TextDone) }
    Spacer(Modifier.width(4.dp))
}

/**
 * What the folded panel shows while the keys type: contacts to pick as a
 * guest, or the draft's when and where so the user keeps their bearings.
 */
@Composable
private fun TypingBody(ui: CalendarUi, entry: CalendarTextEntry, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val draft = ui.draft ?: return
    if (entry.field == CalendarTextField.GUEST) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(ui.guestSuggestions, key = { it.email }) { g ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(kb.cardShape())
                        .background(kb.chip)
                        .clickable { act(CalendarAction.AddGuest(g)) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.label, color = kb.suggestionText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (g.name.isNotBlank()) {
                            Text(g.email, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
                }
            }
            if (ui.guestSuggestions.isEmpty()) {
                item {
                    GuestChips(draft.guests, act)
                    if (!ui.contactsAllowed) {
                        Text(
                            stringResource(R.string.ime_calendar_contacts_action),
                            color = kb.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable { act(CalendarAction.AskContacts) },
                        )
                    }
                }
            }
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            draft.title.ifBlank { stringResource(R.string.ime_calendar_untitled) },
            color = kb.suggestionText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            rangeText(draft.startMillis, draft.endMillis, draft.allDay, draft.zone, ui.hour24),
            color = kb.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (draft.location.isNotBlank()) {
            Text(draft.location, color = kb.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Month ----------------------------------------------------------------

/**
 * The month grid, as it always was: the first chosen alternate calendar's
 * date inside each cell, both chosen calendars' month spans in the header, a
 * Today shortcut, and now a dot under every day with something on. A press
 * selects a day; a long press starts an event on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthView(state: KeyboardUiState, ui: CalendarUi, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val today = ui.today
    val shownYear = ui.shownYear
    val shownMonth = ui.shownMonth
    val selected = ui.selected
    val monthLabel = remember(shownYear, shownMonth) { CalendarText.month(shownYear, shownMonth) }
    val altCalendars = listOf(state.settings.calendarTool.altOne, state.settings.calendarTool.altTwo)
        .filter { it != AltCalendar.NONE }
        .distinct()
    val cellCalendar = altCalendars.firstOrNull { it.hasDayLabel }
    val hijriAdjust = state.settings.calendarTool.hijriAdjustDays
    val daysInMonth = CalendarSystems.gregorianMonthLength(shownYear, shownMonth)
    val spanLabel = remember(shownYear, shownMonth, altCalendars, hijriAdjust) {
        altCalendars
            .map { AltCalendars.monthSpan(it, shownYear, shownMonth, hijriAdjust) }
            .filter { it.isNotEmpty() }
            .joinToString("  ·  ")
    }

    // Ring regions: three fixed ACTIONS slots (Today no-ops while already on
    // the current month, rather than renumbering next); the day grid is seven
    // columns, so Up/Down walk weeks the way the eye does.
    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.ACTIONS, count = 3, columns = 3) { index ->
        when (index) {
            0 -> act(CalendarAction.Month(-1))
            1 -> act(CalendarAction.Today)
            2 -> act(CalendarAction.Month(1))
        }
    }
    PanelFocusTarget(panel = PanelMode.CALENDAR, region = FocusRegion.RESULTS, count = daysInMonth, columns = 7) { index ->
        val day = index + 1
        if (day in 1..daysInMonth) act(CalendarAction.SelectDay(EventDate(shownYear, shownMonth, day)))
    }
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val focusedDay = state.focusedIndex(FocusRegion.RESULTS)?.plus(1)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            if (ui.resumable && ui.draft != null) {
                Banner(
                    stringResource(R.string.ime_calendar_resume_draft, ui.draft.title.ifBlank { stringResource(R.string.ime_calendar_untitled) }),
                    stringResource(R.string.ime_calendar_resume_action),
                ) { act(CalendarAction.ResumeDraft) }
                Spacer(Modifier.height(4.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconHint(stringResource(R.string.ime_calendar_previous_month_desc)) {
                    IconButton(
                        onClick = { act(CalendarAction.Month(-1)) },
                        modifier = Modifier
                            .size(30.dp)
                            .focusRing(focusedAction == 0, CircleShape),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.ime_calendar_previous_month_desc),
                            tint = kb.toolbarIcon,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(monthLabel, color = kb.modifierKeyText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (spanLabel.isNotEmpty()) {
                        Text(spanLabel, color = kb.toolbarIcon, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (shownYear != today.year || shownMonth != today.month) {
                    Box(
                        modifier = Modifier
                            .clip(kb.chipShape())
                            .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
                            .background(kb.chip)
                            .chipBorder(kb, kb.chipShape())
                            .focusRing(focusedAction == 1, kb.chipShape())
                            .clickable { act(CalendarAction.Today) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(stringResource(R.string.ime_calendar_today_label), color = kb.modifierKeyText, fontSize = 11.sp)
                    }
                }
                IconHint(stringResource(R.string.ime_calendar_next_month_desc)) {
                    IconButton(
                        onClick = { act(CalendarAction.Month(1)) },
                        modifier = Modifier
                            .size(30.dp)
                            .focusRing(focusedAction == 2, CircleShape),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.ime_calendar_next_month_desc),
                            tint = kb.toolbarIcon,
                        )
                    }
                }
            }
            val weekend = state.settings.calendarTool.weekend.days
            val weekdayInitials = stringArrayResource(R.array.ime_calendar_weekday_initials)
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdayInitials.forEachIndexed { index, initial ->
                    Text(
                        initial,
                        color = if (index in weekend) kb.accent else kb.toolbarIcon,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val firstDow = CalendarSystems.dayOfWeek(CalendarSystems.gregorianToJdn(shownYear, shownMonth, 1))
            val weeks = (firstDow + daysInMonth + 6) / 7
            // Legible week rows first, the day's events everything left over
            // (22 dp a week at the keyboard's own height, #665).
            val eventsMaxHeight = (panelHeight - CALENDAR_CHROME_HEIGHT - (weeks * 22).dp).coerceIn(0.dp, 200.dp)
            Column(modifier = Modifier.weight(1f)) {
                for (week in 0 until weeks) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        for (dow in 0 until 7) {
                            val day = week * 7 + dow - firstDow + 1
                            if (day < 1 || day > daysInMonth) {
                                Spacer(Modifier.weight(1f))
                            } else {
                                DayCell(
                                    day = day,
                                    isToday = today.year == shownYear && today.month == shownMonth && today.day == day,
                                    isSelected = selected.year == shownYear && selected.month == shownMonth && selected.day == day,
                                    busy = day in ui.busyDays,
                                    focused = day == focusedDay,
                                    altDay = cellCalendar?.let { AltCalendars.dayLabel(it, shownYear, shownMonth, day, hijriAdjust) },
                                    onClick = { act(CalendarAction.SelectDay(EventDate(shownYear, shownMonth, day))) },
                                    onLongClick = { act(CalendarAction.NewEvent(EventDate(shownYear, shownMonth, day))) },
                                )
                            }
                        }
                    }
                }
            }
            DaySummary(ui, altCalendars, hijriAdjust)
            DayEvents(
                ui = ui,
                act = act,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = eventsMaxHeight),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    busy: Boolean,
    focused: Boolean,
    altDay: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(horizontal = 2.dp)
            .clip(shape)
            .background(if (isSelected) kb.chipActive else Color.Transparent)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, kb.accent, shape) else Modifier)
            .focusRing(focused, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        val primary = when {
            isSelected -> kb.chipActiveText
            isToday -> kb.accent
            else -> kb.modifierKeyText
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                day.toString(),
                color = primary,
                fontSize = 12.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            if (altDay != null) {
                Text(
                    altDay,
                    color = if (isSelected) kb.chipActiveText.copy(alpha = 0.7f) else kb.toolbarIcon,
                    fontSize = 8.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
        if (busy) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) kb.chipActiveText else kb.accent),
            )
        }
    }
}

/** The selected day in words: its date, weekday, how far off, and the alternate calendars. */
@Composable
private fun DaySummary(ui: CalendarUi, altCalendars: List<AltCalendar>, hijriAdjust: Int) {
    val kb = LocalKbTheme.current
    val selected = ui.selected
    val dateText = remember(selected) { CalendarText.longDate(selected) }
    val relativeDays = ui.today.daysUntil(selected).toInt()
    val relativeText = when {
        relativeDays == 0 -> stringResource(R.string.ime_calendar_today_label)
        relativeDays == 1 -> stringResource(R.string.ime_calendar_tomorrow_label)
        relativeDays == -1 -> stringResource(R.string.ime_calendar_yesterday_label)
        relativeDays > 0 -> pluralStringResource(R.plurals.ime_calendar_in_days_info, relativeDays, relativeDays)
        else -> pluralStringResource(R.plurals.ime_calendar_days_ago_info, -relativeDays, -relativeDays)
    }
    val altText = remember(selected, altCalendars, hijriAdjust) {
        altCalendars
            .map { AltCalendars.fullDate(it, selected.year, selected.month, selected.day, hijriAdjust) }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Text(dateText, color = kb.modifierKeyText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(
            if (altText.isEmpty()) relativeText else "$relativeText · $altText",
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/**
 * The selected day's events, or the prompt for calendar access until it is
 * granted. A press opens the event's sheet.
 */
@Composable
private fun DayEvents(ui: CalendarUi, act: (CalendarAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    if (!ui.canRead) {
        Row(modifier = modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ime_calendar_permission_info),
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            PillButton(stringResource(R.string.ime_calendar_allow_action), primary = true, height = 30.dp) {
                act(CalendarAction.AskRead)
            }
        }
        return
    }
    val list = ui.dayEvents
    when {
        list == null -> Unit
        list.isEmpty() -> Text(
            stringResource(R.string.ime_calendar_no_events_empty),
            color = kb.secondaryText,
            fontSize = 12.sp,
            modifier = modifier.padding(top = 6.dp),
        )
        else -> LazyColumn(
            modifier = modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 44.dp),
        ) {
            // No key: EVENT_ID repeats across the instances of a recurring
            // event, and duplicate keys are a crash rather than a glitch.
            items(list) { event -> EventRow(event, ui.hour24) { act(CalendarAction.OpenEvent(event)) } }
        }
    }
}

/**
 * One event card: the calendar's own colour as a stripe down the left, the
 * title, and the time (with the place after it, when there is one).
 */
@Composable
private fun EventRow(event: DeviceCalendarEvent, hour24: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val color = if (event.color != 0) Color(event.color) else kb.accent
    val shape = kb.cardShape()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip, shape)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color),
        )
        Column(
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                .weight(1f),
        ) {
            Text(
                event.title,
                color = kb.modifierKeyText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val time = if (event.allDay) {
                stringResource(R.string.ime_calendar_all_day_label)
            } else {
                val zone = java.util.TimeZone.getDefault()
                CalendarText.time(com.wasimaster.wmkeyboard.core.tools.calendar.EventTimes.minuteOfDay(event.begin, zone), hour24)
            }
            Text(
                event.location?.let { "$time · $it" } ?: time,
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (event.recurring) {
            Icon(
                Icons.Outlined.EventRepeat,
                contentDescription = null,
                tint = kb.secondaryText,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(14.dp),
            )
        }
    }
}

// ---- Event sheet ------------------------------------------------------------

/**
 * One event read whole: when, how it repeats, where, notes, guests and
 * reminders, and what can be done with it. Delete, Insert, Open and Edit
 * stay pinned under it however far the rest scrolls.
 */
@Composable
private fun EventSheet(event: StoredEvent, ui: CalendarUi, act: (CalendarAction) -> Unit) {
    val kb = LocalKbTheme.current
    val color = if (event.color != 0) Color(event.color) else kb.accent
    val end = event.instanceEnd.takeIf { it > event.instanceBegin } ?: (event.dtEnd ?: event.instanceBegin)
    val zone = event.zone
    val start = EventDate.of(event.instanceBegin, zone)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    event.title.ifBlank { stringResource(R.string.ime_calendar_untitled) },
                    color = kb.suggestionText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DetailLine(Icons.Outlined.Schedule, rangeText(event.instanceBegin, end, event.allDay, zone, ui.hour24))
            ui.series?.let { s ->
                DetailLine(Icons.Outlined.EventRepeat, recurrenceText(RRule.decode(s.rule, start, zone), start, ui.today))
            }
            if (event.location.isNotBlank()) DetailLine(Icons.Outlined.LocationOn, event.location)
            if (event.notes.isNotBlank()) DetailLine(Icons.Outlined.Notes, event.notes, maxLines = 8)
            val guests = event.invited
            if (guests.isNotEmpty()) {
                val yes = stringResource(R.string.ime_calendar_guest_yes)
                val no = stringResource(R.string.ime_calendar_guest_no)
                val maybe = stringResource(R.string.ime_calendar_guest_maybe)
                DetailLine(
                    Icons.Outlined.Group,
                    guests.joinToString("\n") { g ->
                        val status = when (g.status) {
                            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> " · $yes"
                            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> " · $no"
                            android.provider.CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> " · $maybe"
                            else -> ""
                        }
                        g.name.ifBlank { g.email } + status
                    },
                    maxLines = 12,
                )
            }
            if (event.reminders.isNotEmpty()) {
                val texts = event.reminders.sorted().map { reminderText(it) }
                DetailLine(Icons.Outlined.Notifications, texts.joinToString(", "))
            }
            if (event.calendarName.isNotBlank()) {
                Text(event.calendarName, color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.padding(start = 26.dp))
            }
            if (!event.canModify) {
                Text(
                    stringResource(R.string.ime_calendar_read_only),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                )
            }
            ui.error?.let { e ->
                Banner(errorText(e), stringResource(R.string.ime_calendar_done)) { act(CalendarAction.ErrorDismiss) }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (event.canModify) {
                RoundButton(Icons.Outlined.Delete, stringResource(R.string.ime_calendar_delete), size = 34.dp) {
                    act(CalendarAction.DeleteEvent)
                }
            }
            RoundButton(Icons.Outlined.OpenInNew, stringResource(R.string.ime_calendar_open_app), size = 34.dp) {
                act(CalendarAction.OpenInApp)
            }
            RoundButton(Icons.Outlined.EditNote, stringResource(R.string.ime_calendar_insert), size = 34.dp) {
                act(CalendarAction.InsertEvent)
            }
            Spacer(Modifier.weight(1f))
            if (event.canModify) {
                PillButton(stringResource(R.string.ime_calendar_edit), icon = Icons.Outlined.Edit, primary = true, enabled = !ui.busy) {
                    act(CalendarAction.EditEvent)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(icon: ImageVector, text: String, maxLines: Int = 3) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = kb.secondaryText,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = kb.suggestionText, fontSize = 13.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** The one question a repeating event's change or delete needs answered first. */
@Composable
private fun ScopeSheet(prompt: ScopePrompt, act: (CalendarAction) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    AnimatedVisibility(visible = true, modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CARDS)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(if (prompt.delete) R.string.ime_calendar_scope_delete_title else R.string.ime_calendar_scope_edit_title),
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            for (s in prompt.scopes) {
                PillButton(
                    stringResource(
                        when (s) {
                            EditScope.THIS -> R.string.ime_calendar_scope_this
                            EditScope.FOLLOWING -> R.string.ime_calendar_scope_following
                            EditScope.ALL -> R.string.ime_calendar_scope_all
                        },
                    ),
                    primary = s == prompt.scopes.first(),
                    height = 32.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { act(CalendarAction.Scope(s)) }
            }
            PillButton(stringResource(R.string.ime_calendar_cancel), height = 32.dp, modifier = Modifier.fillMaxWidth()) {
                act(CalendarAction.CancelScope)
            }
        }
    }
}

/**
 * Everything in the month view that is not the day grid or the event list:
 * the month header, the weekday initials, the selected-day summary and the
 * paddings between them.
 */
private val CALENDAR_CHROME_HEIGHT = 96.dp

private const val VIEW_MS = 200
