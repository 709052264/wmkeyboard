package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.BookmarkAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MyLocation
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoCamera
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Place
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.location.GeoPoint
import com.wasimaster.wmkeyboard.core.location.LocationFix
import com.wasimaster.wmkeyboard.core.location.LocationFormat
import com.wasimaster.wmkeyboard.core.location.LocationFormatter
import com.wasimaster.wmkeyboard.core.location.LocationGrant
import com.wasimaster.wmkeyboard.core.location.LocationPrecision
import com.wasimaster.wmkeyboard.core.location.PlusCode
import com.wasimaster.wmkeyboard.core.location.SavedPlace
import com.wasimaster.wmkeyboard.core.settings.LocationSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.location.AddressState
import com.wasimaster.wmkeyboard.ime.location.LocationStatus
import com.wasimaster.wmkeyboard.ime.location.LocationTab
import com.wasimaster.wmkeyboard.ime.location.LocationUi
import com.wasimaster.wmkeyboard.ime.location.currentShare
import com.wasimaster.wmkeyboard.ime.location.formatOr
import com.wasimaster.wmkeyboard.ime.location.precisionOr
import com.wasimaster.wmkeyboard.ime.location.share
import java.util.Date
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.wasimaster.wmkeyboard.common.R as CommonR

/** What the Location panel hands back (#350); one bundle, see [ToolHoldCallbacks]. */
data class LocationCallbacks(
    val state: StateFlow<LocationUi> = MutableStateFlow(LocationUi()),
    val onLocate: () -> Unit = {},
    val onTab: (LocationTab) -> Unit = {},
    val onPrecision: () -> Unit = {},
    val onFormat: (LocationFormat) -> Unit = {},
    /** A held format chip: make it what Insert types from now on. */
    val onFormatDefault: (LocationFormat) -> Unit = {},
    val onInsert: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onOpenMaps: (GeoPoint, String?) -> Unit = { _, _ -> },
    val onUseForWeather: (GeoPoint, String?) -> Unit = { _, _ -> },
    val onPlace: (Long) -> Unit = {},
    val onPlaceMore: (Long) -> Unit = {},
    val onPlaceDelete: (Long) -> Unit = {},
    val onAllowPrecise: () -> Unit = {},
    val onServicesSettings: () -> Unit = {},
    /** "Use my location" on the weather and compass panels. */
    val onUseMyLocationForTools: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** The camera, with its location stamp on for one photo. */
    val onPhoto: () -> Unit = {},
    /** The camera's stamp or caption wants a reading, with the Location panel closed. */
    val onLocateForCamera: () -> Unit = {},
    /** The camera's caption: the place as text, before the photo. */
    val onCaption: () -> Unit = {},
    /** The camera read [LocationUi.stampOnce]. */
    val onStampConsumed: () -> Unit = {},
)

/**
 * The Location tool (#350).
 *
 * **Here** is the phone's position: a dial, not a map, because a map needs
 * tiles from a server that would learn where the user is every time the panel
 * opened. The dial shows what a map would have been checked for: how sure the
 * reading is (the ring), and, at a coarser precision, the cell actually shared
 * and where in it the user really is. The address under it is the check that
 * it is the right street. Pick a format, see exactly what will be typed, tap
 * Insert.
 *
 * **Places** are the ones the user saved: tap to insert, no permission and no
 * network needed.
 */
@Composable
internal fun LocationPanel(
    state: KeyboardUiState,
    callbacks: LocationCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val settings = state.settings.location
    val precision = ui.precisionOr(settings)
    val format = ui.formatOr(settings)
    val formats = LocationFormat.entries

    PanelFocusTarget(
        panel = PanelMode.LOCATION,
        region = FocusRegion.CHIPS,
        count = formats.size,
        columns = formats.size,
    ) { index -> formats.getOrNull(index)?.let(callbacks.onFormat) }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        headerActions = { Header(ui, settings, precision, callbacks) },
    ) {
        when (ui.tab) {
            LocationTab.HERE -> HereBody(state, ui, settings, precision, format, callbacks)
            LocationTab.PLACES -> PlacesBody(state, ui, settings, precision, format, callbacks)
        }
    }
}

@Composable
private fun RowScope.Header(
    ui: LocationUi,
    settings: LocationSettings,
    precision: LocationPrecision,
    callbacks: LocationCallbacks,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        ToolPanelChip(
            stringResource(R.string.ime_location_tab_here),
            selected = ui.tab == LocationTab.HERE,
        ) { callbacks.onTab(LocationTab.HERE) }
        ToolPanelChip(
            if (settings.places.isEmpty()) {
                stringResource(R.string.ime_location_tab_places)
            } else {
                stringResource(R.string.ime_location_tab_places_count, settings.places.size)
            },
            selected = ui.tab == LocationTab.PLACES,
        ) { callbacks.onTab(LocationTab.PLACES) }
        val precisionLabel = stringResource(precision.shortLabelRes)
        val precisionDescription = stringResource(R.string.ime_location_precision_desc, stringResource(precision.labelRes))
        ToolPanelChip(
            precisionLabel,
            selected = precision != LocationPrecision.EXACT,
            modifier = Modifier.semantics { contentDescription = precisionDescription },
        ) { callbacks.onPrecision() }
        Spacer(Modifier.width(2.dp))
    }
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_location_settings_desc),
        active = false,
    ) { callbacks.onOpenSettings() }
}

// ---- Here ----

@Composable
private fun HereBody(
    state: KeyboardUiState,
    ui: LocationUi,
    settings: LocationSettings,
    precision: LocationPrecision,
    format: LocationFormat,
    callbacks: LocationCallbacks,
) {
    val fix = ui.fix
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    when {
        !ui.declared -> Notice(
            text = stringResource(R.string.ime_location_no_permission_build),
            action = stringResource(R.string.ime_location_tab_places),
            focused = focusedAction == 0,
        ) { callbacks.onTab(LocationTab.PLACES) }
        fix == null && ui.grant == LocationGrant.NONE -> Intro(focusedAction == 0, callbacks.onLocate)
        fix == null && !ui.servicesOn -> Notice(
            text = stringResource(R.string.ime_location_services_off),
            action = stringResource(R.string.ime_location_services_on_action),
            focused = focusedAction == 0,
            onAction = callbacks.onServicesSettings,
        )
        fix == null && ui.status == LocationStatus.LOCATING -> Searching(state)
        fix == null && ui.status == LocationStatus.FAILED -> Notice(
            text = stringResource(R.string.ime_location_failed_error),
            action = stringResource(CommonR.string.common_retry),
            focused = focusedAction == 0,
            onAction = callbacks.onLocate,
        )
        fix == null -> Intro(focusedAction == 0, callbacks.onLocate)
        else -> FixView(state, ui, fix, settings, precision, format, callbacks)
    }
    val count = when {
        fix != null -> FIX_ACTIONS
        fix == null && ui.status == LocationStatus.LOCATING -> 0
        else -> 1
    }
    PanelFocusTarget(
        panel = PanelMode.LOCATION,
        region = FocusRegion.ACTIONS,
        count = count,
        columns = count.coerceAtLeast(1),
    ) { index ->
        if (fix == null) {
            when {
                !ui.declared -> callbacks.onTab(LocationTab.PLACES)
                !ui.servicesOn && ui.grant != LocationGrant.NONE -> callbacks.onServicesSettings()
                else -> callbacks.onLocate()
            }
        } else {
            when (index) {
                0 -> callbacks.onInsert()
                1 -> callbacks.onSave()
                2 -> callbacks.onOpenMaps(precision.apply(fix.point), null)
                3 -> callbacks.onUseForWeather(fix.point, null)
                4 -> callbacks.onPhoto()
                else -> callbacks.onLocate()
            }
        }
    }
}

/** Insert, Save, Map, Weather, Photo, Refresh. */
private const val FIX_ACTIONS = 6

@Composable
private fun Intro(focused: Boolean, onLocate: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(kb.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = kb.accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.ime_location_intro_title),
            color = kb.suggestionText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.ime_location_intro_body),
            color = kb.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            label = stringResource(R.string.ime_location_locate_action),
            icon = Icons.Outlined.MyLocation,
            focused = focused,
            onClick = onLocate,
        )
    }
}

@Composable
private fun Searching(state: KeyboardUiState) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PositionDial(fix = null, precision = LocationPrecision.EXACT, locating = true, reduceMotion = state.settings.reduceMotion, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.ime_location_searching_progress), color = kb.secondaryText, fontSize = 13.sp)
    }
}

@Composable
private fun Notice(text: String, action: String, focused: Boolean, onAction: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = kb.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        ToolPanelChip(action, selected = true, modifier = Modifier.focusRing(focused, kb.chipShape())) { onAction() }
    }
}

@Composable
private fun FixView(
    state: KeyboardUiState,
    ui: LocationUi,
    fix: LocationFix,
    settings: LocationSettings,
    precision: LocationPrecision,
    format: LocationFormat,
    callbacks: LocationCallbacks,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val shared = precision.apply(fix.point)
    val address = ui.addressFor(precision)
    val timeText = remember(fix.timeMillis) {
        android.text.format.DateFormat.getTimeFormat(context).format(Date(fix.timeMillis))
    }
    val preview = remember(ui, settings, precision, format, timeText) {
        ui.currentShare(settings, timeText)?.let {
            LocationFormatter.format(it, format, settings.mapLink, settings.template)
        }.orEmpty()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PositionDial(
                fix = fix,
                precision = precision,
                locating = ui.status == LocationStatus.LOCATING,
                reduceMotion = state.settings.reduceMotion,
                modifier = Modifier.size(84.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    address ?: when (ui.addressState) {
                        AddressState.LOOKING -> stringResource(R.string.ime_location_address_looking)
                        else -> LocationFormatter.coordinates(shared, precision)
                    },
                    color = kb.suggestionText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (address != null || ui.addressState == AddressState.LOOKING) {
                    Text(
                        LocationFormatter.coordinates(shared, precision),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                Text(
                    listOf(
                        LocationFormatter.accuracy(fix.accuracyM, precision),
                        ageText(fix),
                        PlusCode.encode(shared, precision.plusCodeLength),
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    ui.status == LocationStatus.LOCATING -> Text(
                        stringResource(R.string.ime_location_refining_progress),
                        color = kb.accent,
                        fontSize = 11.sp,
                    )
                    ui.grant == LocationGrant.APPROXIMATE -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ime_location_approximate_label), color = kb.secondaryText, fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.ime_location_allow_precise_action),
                            color = kb.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable { callbacks.onAllowPrecise() },
                        )
                    }
                    ui.addressState == AddressState.FAILED -> Text(
                        stringResource(R.string.ime_location_address_failed),
                        color = kb.secondaryText,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        FormatChips(format, settings.format, focusedChip, callbacks)
        Spacer(Modifier.height(6.dp))
        PreviewBox(preview, Modifier.weight(1f))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryButton(
                label = stringResource(R.string.ime_location_insert_action),
                icon = null,
                focused = focusedAction == 0,
                modifier = Modifier.weight(1f),
                onClick = callbacks.onInsert,
            )
            ActionIcon(Icons.Outlined.BookmarkAdd, stringResource(R.string.ime_location_save_action), focusedAction == 1, callbacks.onSave)
            ActionIcon(Icons.Outlined.OpenInNew, stringResource(R.string.ime_location_map_action), focusedAction == 2) {
                callbacks.onOpenMaps(shared, address)
            }
            ActionIcon(Icons.Outlined.WbSunny, stringResource(R.string.ime_location_weather_action), focusedAction == 3) {
                callbacks.onUseForWeather(fix.point, null)
            }
            ActionIcon(Icons.Outlined.PhotoCamera, stringResource(R.string.ime_location_photo_desc), focusedAction == 4, callbacks.onPhoto)
            ActionIcon(Icons.Outlined.Refresh, stringResource(R.string.ime_location_refresh_desc), focusedAction == 5, callbacks.onLocate)
        }
    }
}

@Composable
private fun ageText(fix: LocationFix): String {
    val seconds = ((System.currentTimeMillis() - fix.timeMillis) / 1000).coerceAtLeast(0)
    return if (seconds < 60) {
        stringResource(R.string.ime_location_age_now)
    } else {
        val minutes = (seconds / 60).toInt()
        pluralStringResource(R.plurals.ime_location_age_minutes, minutes, minutes)
    }
}

/**
 * One chip per format. A tap picks it for this visit and the preview follows;
 * a hold makes it the default. The default wears a dot, so the hold has
 * something to show for itself.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormatChips(
    format: LocationFormat,
    default: LocationFormat,
    focused: Int?,
    callbacks: LocationCallbacks,
) {
    val kb = LocalKbTheme.current
    val holdHint = stringResource(R.string.ime_location_format_hold_desc)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LocationFormat.entries.forEachIndexed { index, entry ->
            val selected = entry == format
            val shape = kb.chipShape()
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .focusRing(focused == index, shape)
                    .combinedClickable(
                        onLongClickLabel = holdHint,
                        onClick = { callbacks.onFormat(entry) },
                        onLongClick = { callbacks.onFormatDefault(entry) },
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(entry.labelRes),
                    color = if (selected) kb.chipActiveText else kb.chipText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (entry == default) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (selected) kb.chipActiveText else kb.accent),
                    )
                }
            }
        }
    }
}

/** Exactly what Insert will type, in the field's own words. */
@Composable
private fun PreviewBox(text: String, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = kb.chipText, fontSize = 13.sp)
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: ImageVector?,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .background(kb.chipActive)
            .focusRing(focused, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = kb.chipActiveText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = kb.chipActiveText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, focused: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    IconHint(description) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(shape)
                .background(kb.chip)
                .chipBorder(kb, shape)
                .focusRing(focused, shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = kb.chipText, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Where the reading puts the user, drawn to scale without a map: the ring is
 * the reading's accuracy, the square the precision cell that is shared, the
 * filled dot the reading and the hollow one the point that goes out. North is
 * the tick at the top.
 */
@Composable
private fun PositionDial(
    fix: LocationFix?,
    precision: LocationPrecision,
    locating: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val description = stringResource(R.string.ime_location_dial_desc)
    val pulse = if (locating && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "locating")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
            label = "pulse",
        ).value
    } else {
        null
    }
    Canvas(modifier.semantics { contentDescription = description }) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(kb.chip, radius, center)
        drawCircle(kb.secondaryText.copy(alpha = 0.25f), radius, center, style = Stroke(1.dp.toPx()))
        drawLine(kb.toolbarIcon, Offset(center.x, 3.dp.toPx()), Offset(center.x, 10.dp.toPx()), 2.dp.toPx())
        if (fix != null) {
            val accuracy = fix.accuracyM ?: 0f
            val shared = precision.apply(fix.point)
            val metresPerDegree = 111_320.0
            // Offset of the shared point from the reading, in metres, north up.
            val dx = ((shared.longitude - fix.point.longitude) * metresPerDegree * cos(Math.toRadians(fix.point.latitude))).toFloat()
            val dy = ((shared.latitude - fix.point.latitude) * metresPerDegree).toFloat()
            val half = precision.cellMetres / 2f
            val extent = max(max(accuracy, 12f), hypot(dx, dy) + half * 1.42f) * 1.15f
            val scale = (radius - 4.dp.toPx()) / extent
            if (precision.cellMetres > 0) {
                val cellCenter = Offset(center.x + dx * scale, center.y - dy * scale)
                val side = precision.cellMetres * scale
                val topLeft = Offset(cellCenter.x - side / 2f, cellCenter.y - side / 2f)
                drawRect(kb.accent.copy(alpha = 0.12f), topLeft, Size(side, side))
                drawRect(kb.accent.copy(alpha = 0.55f), topLeft, Size(side, side), style = Stroke(1.dp.toPx()))
                drawCircle(kb.accent, 4.dp.toPx(), cellCenter, style = Stroke(2.dp.toPx()))
            }
            if (accuracy > 0f) {
                drawCircle(kb.accent.copy(alpha = 0.18f), accuracy * scale, center)
                drawCircle(kb.accent.copy(alpha = 0.5f), accuracy * scale, center, style = Stroke(1.dp.toPx()))
            }
            drawCircle(kb.accent, 4.5.dp.toPx(), center)
        }
        if (pulse != null) {
            drawCircle(kb.accent.copy(alpha = (1f - pulse) * 0.5f), radius * pulse, center, style = Stroke(2.dp.toPx()))
            if (fix == null) drawCircle(kb.accent, 4.5.dp.toPx(), center)
        }
    }
}

// ---- Places ----

@Composable
private fun PlacesBody(
    state: KeyboardUiState,
    ui: LocationUi,
    settings: LocationSettings,
    precision: LocationPrecision,
    format: LocationFormat,
    callbacks: LocationCallbacks,
) {
    val kb = LocalKbTheme.current
    val places = settings.places
    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)
    PanelFocusTarget(
        panel = PanelMode.LOCATION,
        region = FocusRegion.RESULTS,
        count = places.size,
        columns = 1,
    ) { index -> places.getOrNull(index)?.let { callbacks.onPlace(it.id) } }
    PanelFocusTarget(
        panel = PanelMode.LOCATION,
        region = FocusRegion.ACTIONS,
        count = if (places.isEmpty()) 1 else 0,
        columns = 1,
    ) { callbacks.onOpenSettings() }
    if (places.isEmpty()) {
        Notice(
            text = stringResource(R.string.ime_location_places_empty),
            action = stringResource(R.string.ime_location_places_add_action),
            focused = state.focusedIndex(FocusRegion.ACTIONS) == 0,
            onAction = callbacks.onOpenSettings,
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        FormatChips(format, settings.format, focusedChip, callbacks)
        Spacer(Modifier.height(6.dp))
        val listState = rememberLazyListState()
        ScrollFocusIntoView(focusedRow) { listState.animateScrollToItem(it) }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(places, key = { _, place -> place.id }) { index, place ->
                PlaceRow(
                    place = place,
                    from = ui.fix?.point,
                    precision = precision,
                    open = ui.openPlace == place.id,
                    focused = index == focusedRow,
                    callbacks = callbacks,
                )
            }
        }
        Text(
            stringResource(R.string.ime_location_places_hint),
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PlaceRow(
    place: SavedPlace,
    from: GeoPoint?,
    precision: LocationPrecision,
    open: Boolean,
    focused: Boolean,
    callbacks: LocationCallbacks,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val shared = place.share(precision)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (open) kb.chip else kb.chip.copy(alpha = kb.chip.alpha * 0.5f))
            .focusRing(focused, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { callbacks.onPlace(place.id) }
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Place, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    place.name,
                    color = kb.suggestionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shared.address ?: LocationFormatter.coordinates(precision.apply(place.point), precision),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (from != null) {
                Spacer(Modifier.width(8.dp))
                Text(LocationFormatter.distance(from.distanceTo(place.point)), color = kb.secondaryText, fontSize = 12.sp, maxLines = 1)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { callbacks.onPlaceMore(place.id) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.ime_location_place_more_desc, place.name),
                    modifier = Modifier.size(18.dp),
                    tint = kb.toolbarIcon,
                )
            }
        }
        if (open) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 36.dp, end = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolPanelChip(stringResource(R.string.ime_location_map_action)) {
                    callbacks.onOpenMaps(precision.apply(place.point), place.name)
                }
                ToolPanelChip(stringResource(R.string.ime_location_weather_action)) {
                    callbacks.onUseForWeather(place.point, place.name)
                }
                ToolPanelChip(stringResource(R.string.ime_location_place_delete_action), icon = Icons.Outlined.Delete) {
                    callbacks.onPlaceDelete(place.id)
                }
            }
        }
    }
}
