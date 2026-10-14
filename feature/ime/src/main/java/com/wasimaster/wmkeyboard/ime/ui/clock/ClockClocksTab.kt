package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.IcuZoneNames
import com.wasimaster.wmkeyboard.core.clock.InsertFormat
import com.wasimaster.wmkeyboard.core.clock.WorldZone
import com.wasimaster.wmkeyboard.core.clock.ZoneCatalog
import com.wasimaster.wmkeyboard.core.clock.ZoneCoordinates
import com.wasimaster.wmkeyboard.core.clock.ZoneEntry
import com.wasimaster.wmkeyboard.core.clock.utcOffsetLabel
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bedtime
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbTwilight
import com.wasimaster.wmkeyboard.core.tools.SolarCalculator
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * World clocks. The phone's own time on top, large; under it a card per city
 * with its time, how far ahead or behind it is, whether it is day or night
 * there, and its sunrise and sunset. A tap opens the ways to type that time;
 * a hold offers rename, move and remove.
 */
@Composable
internal fun ClocksTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val search = ui.search
    if (search != null) {
        SearchResults(search, ui, act)
        return
    }
    val now = rememberNow(if (ui.config.showSeconds) 1000L else 15_000L)
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "home") {
            HomeClock(now, ui, act)
        }
        if (ui.insertZone == "") {
            item(key = "home-insert") { InsertSheet("", now, ui, act) }
        }
        items(ui.config.zones, key = { it.zoneId }) { zone ->
            Column(Modifier.animateItem()) {
                ZoneCard(
                    zone = zone,
                    now = now,
                    ui = ui,
                    menuOpen = menuFor == zone.zoneId,
                    onTap = {
                        menuFor = null
                        act(ClockAction.InsertSheet(zone.zoneId))
                    },
                    onHold = { menuFor = if (menuFor == zone.zoneId) null else zone.zoneId },
                    act = act,
                )
                AnimatedVisibility(
                    visible = ui.insertZone == zone.zoneId,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        InsertSheet(zone.zoneId, now, ui, act)
                    }
                }
            }
        }
        item(key = "footer") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PillButton(
                    stringResource(R.string.ime_clock_add_city),
                    icon = Icons.Outlined.Add,
                    primary = ui.config.zones.isEmpty(),
                    modifier = Modifier.weight(1f),
                ) { act(ClockAction.SearchOpen) }
                if (ui.config.zones.isNotEmpty()) {
                    PillButton(
                        stringResource(R.string.ime_clock_insert_all),
                        modifier = Modifier.weight(1f),
                    ) { act(ClockAction.InsertAllClocks) }
                }
            }
        }
    }
}

@Composable
private fun HomeClock(now: Long, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val coords = remember(zone.id) { ZoneCoordinates.of(zone.id) }
    val sun = remember(zone.id, now / DAY_MS) { coords?.let { SolarCalculator.forDate(it.first, it.second, now, zone) } }
    val city = remember(zone.id) { IcuZoneNames().city(zone.id) ?: ZoneCatalog.fallbackCity(zone.id) }
    ClockCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = ui.insertZone == "",
        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        onClick = { act(ClockAction.InsertSheet("")) },
        onLongClick = { act(ClockAction.InsertDefault("")) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        ClockFormats.timeDigits(now, zone, ui.hour24),
                        color = kb.suggestionText,
                        style = digits(40.sp),
                    )
                    if (ui.config.showSeconds) {
                        Text(
                            ":" + two(Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.SECOND)),
                            color = kb.secondaryText,
                            style = digits(20.sp),
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    val ampm = ClockFormats.amPm(now, zone, ui.hour24)
                    if (ampm.isNotEmpty()) {
                        Text(
                            ampm,
                            color = kb.secondaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                    }
                }
                Text(
                    "${ClockFormats.longDate(now, zone)} · $city",
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sun != null) SunLine(sun.sunriseMinutes, sun.sunsetMinutes, ui.hour24)
            }
            AnalogDial(
                now = now,
                zone = zone,
                day = isDay(now, zone, sun?.sunriseMinutes, sun?.sunsetMinutes),
                seconds = ui.config.showSeconds,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoneCard(
    zone: WorldZone,
    now: Long,
    ui: ClockUi,
    menuOpen: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    act: (ClockAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val tz = remember(zone.zoneId) { TimeZone.getTimeZone(zone.zoneId) }
    val home = TimeZone.getDefault()
    val coords = remember(zone) {
        val lat = zone.latitude
        val lon = zone.longitude
        if (lat != null && lon != null) lat to lon else ZoneCoordinates.of(zone.zoneId)
    }
    val sun = remember(zone.zoneId, now / DAY_MS) { coords?.let { SolarCalculator.forDate(it.first, it.second, now, tz) } }
    val city = remember(zone) { zone.label.ifEmpty { IcuZoneNames().city(zone.zoneId) ?: ZoneCatalog.fallbackCity(zone.zoneId) } }
    val shift = ClockFormats.dayShift(now, tz, home)
    val delta = ClockFormats.offsetDelta(now, tz, home)
    val dayWord = stringResource(
        when (shift) {
            1 -> R.string.ime_clock_tomorrow
            -1 -> R.string.ime_clock_yesterday
            else -> R.string.ime_clock_today
        },
    )
    val day = isDay(now, tz, sun?.sunriseMinutes, sun?.sunsetMinutes)
    ClockCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = ui.insertZone == zone.zoneId || menuOpen,
        onClick = onTap,
        onLongClick = onHold,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnalogDial(now, tz, day, seconds = false, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    city,
                    color = kb.suggestionText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.ime_clock_zone_delta, dayWord, ClockFormats.deltaLabel(delta)),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                if (sun != null) SunLine(sun.sunriseMinutes, sun.sunsetMinutes, ui.hour24)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        ClockFormats.timeDigits(now, tz, ui.hour24),
                        color = kb.suggestionText,
                        style = digits(26.sp, FontWeight.Normal),
                    )
                    val ampm = ClockFormats.amPm(now, tz, ui.hour24)
                    if (ampm.isNotEmpty()) {
                        Text(
                            ampm,
                            color = kb.secondaryText,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                        )
                    }
                }
                Text(ClockFormats.weekday(now, tz), color = kb.secondaryText, fontSize = 11.sp)
            }
        }
        AnimatedVisibility(visible = menuOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(
                    stringResource(formatLabel(ui.insertFormat)),
                    primary = true,
                    height = 32.dp,
                    modifier = Modifier.weight(1f, fill = false),
                ) { act(ClockAction.InsertDefault(zone.zoneId)) }
                RoundButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.ime_clock_move_up_desc), size = 32.dp) {
                    act(ClockAction.MoveZone(zone.zoneId, -1))
                }
                RoundButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ime_clock_move_down_desc), size = 32.dp) {
                    act(ClockAction.MoveZone(zone.zoneId, 1))
                }
                RoundButton(Icons.Outlined.Edit, stringResource(R.string.ime_clock_rename_desc), size = 32.dp) {
                    act(ClockAction.RenameZone(zone.zoneId))
                }
                RoundButton(Icons.Outlined.Delete, stringResource(R.string.ime_clock_remove_desc), size = 32.dp) {
                    act(ClockAction.RemoveZone(zone.zoneId))
                }
            }
        }
    }
}

/** Sunrise and sunset, small, with their glyphs. */
@Composable
private fun SunLine(sunrise: Int, sunset: Int, hour24: Boolean) {
    val kb = LocalKbTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
        Icon(Icons.Outlined.WbTwilight, contentDescription = stringResource(R.string.ime_clock_sunrise_desc), tint = kb.secondaryText, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(minutesText(sunrise, hour24), color = kb.secondaryText, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Bedtime, contentDescription = stringResource(R.string.ime_clock_sunset_desc), tint = kb.secondaryText, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(minutesText(sunset, hour24), color = kb.secondaryText, fontSize = 10.sp)
    }
}

/**
 * Every way to type this clock's time, each chip showing exactly what it
 * types. Tap types it; hold makes it what a hold on a clock card types, and
 * the default wears a dot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InsertSheet(zoneId: String, now: Long, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val tz = if (zoneId.isEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(zoneId)
    val place = remember(zoneId, ui.config.zones) {
        ui.config.zones.firstOrNull { it.zoneId == zoneId }?.label?.takeIf { it.isNotEmpty() }
            ?: IcuZoneNames().city(tz.id) ?: ZoneCatalog.fallbackCity(tz.id)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InsertFormat.entries.forEach { format ->
                val text = remember(format, now / 60_000, zoneId, ui.hour24, place) {
                    ClockFormats.format(
                        format, now, tz, ui.hour24, place,
                        relative = { stringOrNow(context) },
                    )
                }
                val isDefault = format == ui.insertFormat
                val shape = kb.chipShape()
                Column(
                    modifier = Modifier
                        .clockChip(selected = isDefault, shape = shape)
                        .combinedClickable(
                            onClick = { act(ClockAction.Insert(zoneId, format)) },
                            onLongClick = { act(ClockAction.SetDefaultFormat(format)) },
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        stringResource(formatLabel(format)),
                        color = (if (isDefault) kb.chipActiveText else kb.secondaryText).copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text,
                        color = if (isDefault) kb.chipActiveText else kb.chipText,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.ime_clock_insert_hint),
            color = kb.secondaryText,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 4.dp, top = 3.dp),
        )
    }
}

private fun stringOrNow(context: android.content.Context): String = context.getString(R.string.ime_clock_relative_now)

@Composable
private fun SearchResults(results: List<ZoneEntry>, ui: ClockUi, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val now = rememberNow(60_000L)
    val added = remember(ui.config.zones) { ui.config.zones.map { it.zoneId }.toSet() }
    if (results.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Public,
            title = stringResource(R.string.ime_clock_search_empty_title),
            body = stringResource(R.string.ime_clock_search_empty_body),
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(results, key = { it.id }) { e ->
            val tz = remember(e.id) { TimeZone.getTimeZone(e.id) }
            val isAdded = e.id in added
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(kb.chipShape())
                    .clickable(enabled = !isAdded) { act(ClockAction.AddZone(e.id)) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.city, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        listOf(e.region, utcOffsetLabel(e.offsetMinutes)).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(ClockFormats.time(now, tz, ui.hour24), color = kb.secondaryText, style = digits(13.sp, FontWeight.Normal))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(24.dp)
                        .clockChip(selected = isAdded, shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isAdded) Icons.Outlined.Check else Icons.Outlined.Add,
                        contentDescription = null,
                        tint = if (isAdded) kb.chipActiveText else kb.chipText,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * A small clock face: hands for the hour and the minute (and the second),
 * the face light by day and dark by night, so a glance down the list says
 * who is awake.
 */
@Composable
internal fun AnalogDial(now: Long, zone: TimeZone, day: Boolean, seconds: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
    val h = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f
    val m = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
    val s = cal.get(Calendar.SECOND).toFloat()
    val face = if (day) kb.accent.copy(alpha = 0.18f) else kb.suggestionText.copy(alpha = 0.85f)
    // By night the face takes the text colour, so the hands take the board's.
    val hands = if (day) kb.suggestionText else kb.board
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(face, r)
            for (i in 0 until 12) {
                val a = i / 12f * 2 * PI.toFloat()
                val inner = if (i % 3 == 0) 0.78f else 0.86f
                drawLine(
                    hands.copy(alpha = 0.45f),
                    c + polar(a, r * inner),
                    c + polar(a, r * 0.94f),
                    strokeWidth = (if (i % 3 == 0) 1.6f else 1f) * density,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(hands, c, c + polar(h / 12f * 2 * PI.toFloat(), r * 0.5f), strokeWidth = r * 0.11f, cap = StrokeCap.Round)
            drawLine(hands, c, c + polar(m / 60f * 2 * PI.toFloat(), r * 0.74f), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
            if (seconds) {
                drawLine(kb.accent, c, c + polar(s / 60f * 2 * PI.toFloat(), r * 0.8f), strokeWidth = r * 0.035f, cap = StrokeCap.Round)
            }
            drawCircle(kb.accent, r * 0.07f, c)
            drawCircle(hands.copy(alpha = 0.15f), r, c, style = Stroke(width = 1f * density))
        }
    }
}

private fun polar(angle: Float, radius: Float) = Offset(sin(angle) * radius, -cos(angle) * radius)

/** Day between sunrise and sunset, or 6 to 18 where the table has no sun times for the zone. */
internal fun isDay(now: Long, zone: TimeZone, sunrise: Int?, sunset: Int?): Boolean {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val start = sunrise ?: (6 * 60)
    val end = sunset ?: (18 * 60)
    return minute in start until end
}

private fun minutesText(minutes: Int, hour24: Boolean): String {
    val h = (minutes / 60) % 24
    val m = minutes % 60
    return if (hour24) {
        "${two(h)}:${two(m)}"
    } else {
        val h12 = if (h % 12 == 0) 12 else h % 12
        "$h12:${two(m)}${if (h < 12) "a" else "p"}"
    }
}

internal fun two(n: Int) = n.toString().padStart(2, '0')

internal fun formatLabel(format: InsertFormat): Int = when (format) {
    InsertFormat.TIME -> R.string.ime_clock_format_time
    InsertFormat.DATE_TIME -> R.string.ime_clock_format_date_time
    InsertFormat.DATE_LONG -> R.string.ime_clock_format_date_long
    InsertFormat.TIME_PLACE -> R.string.ime_clock_format_time_place
    InsertFormat.ISO -> R.string.ime_clock_format_iso
    InsertFormat.RELATIVE -> R.string.ime_clock_format_relative
    InsertFormat.DISCORD -> R.string.ime_clock_format_discord
    InsertFormat.UNIX -> R.string.ime_clock_format_unix
}

private const val DAY_MS = 86_400_000L
