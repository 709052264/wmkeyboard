package com.wasimaster.wmkeyboard.ime.ui.gallery

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Black under photos whatever the theme: a picture is judged against neutral dark. */
internal val PhotoBackdrop = Color(0xFF0B0B0D)

/** White on a photo, with the shadow that keeps it legible on a white sky. */
internal val OnPhoto = Color.White

/** "1:05", or "1:02:05" past the hour. */
internal fun durationText(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** A byte count the way Android's Files app says it. */
@Composable
internal fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))

/** "October 2026": the fast scroller's bubble. */
@Composable
internal fun monthText(dateMs: Long): String =
    DateUtils.formatDateTime(LocalContext.current, dateMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY)

/** The long date and time a photo was taken, for the viewer's info. */
@Composable
internal fun takenText(dateMs: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    dateMs,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY,
)

/**
 * The round check in a cell's corner: a ring when unpicked, the accent with
 * the pick's number when picked, popping in on a spring.
 */
@Composable
internal fun SelectRing(order: Int, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val kb = LocalKbTheme.current
    val picked = order > 0
    val pop by animateFloatAsState(
        targetValue = if (picked) 1f else 0f,
        animationSpec = if (kb.reduceMotion) tween(0) else spring(dampingRatio = 0.45f, stiffness = 700f),
        label = "ring",
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .shadow(2.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.6.dp, OnPhoto, CircleShape),
        )
        if (pop > 0.01f) {
            Box(
                Modifier
                    .size(size)
                    .scale(0.4f + 0.6f * pop)
                    .clip(CircleShape)
                    .background(kb.accent)
                    .border(1.6.dp, OnPhoto, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    order.toString(),
                    color = onAccent(kb.accent),
                    fontSize = if (order > 99) 9.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Black or white, whichever reads on [c]. */
internal fun onAccent(c: Color): Color {
    val l = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return if (l > 0.6f) Color.Black else Color.White
}

/** A dark pill over a photo: a video's length, a GIF's badge. */
@Composable
internal fun PhotoPill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = OnPhoto, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(text, color = OnPhoto, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A round icon button drawn on a photo: dark glass, white glyph. */
@Composable
internal fun PhotoButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) kb.accent else Color.Black.copy(alpha = 0.42f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = (if (active) onAccent(kb.accent) else OnPhoto).copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * A slider in the editor's colours. [twoWay] fills from the middle, for the
 * sliders whose zero is "unchanged"; a double tap puts it back to [reset].
 */
@Composable
internal fun GallerySlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    twoWay: Boolean = false,
    reset: Float = if (twoWay) 0f else range.start,
    description: String = "",
    onChange: (Float) -> Unit,
    onDone: (Float) -> Unit,
) {
    val kb = LocalKbTheme.current
    var width by remember { mutableFloatStateOf(1f) }
    var live by remember { mutableFloatStateOf(value) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val span = range.endInclusive - range.start
    fun valueAt(x: Float) = (range.start + (x / width).coerceIn(0f, 1f) * span)
    Box(
        modifier
            .height(36.dp)
            .fillMaxWidth()
            .semantics { contentDescription = description }
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(range) {
                detectTapGestures(
                    onDoubleTap = {
                        live = reset
                        done(reset)
                    },
                    onTap = {
                        live = valueAt(it.x)
                        done(live)
                    },
                )
            }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragStart = { live = valueAt(it.x); change(live) },
                    onDragEnd = { done(live) },
                    onDragCancel = { done(live) },
                ) { change2, _ ->
                    live = valueAt(change2.position.x)
                    change(live)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val y = size.height / 2f
            val track = 4.dp.toPx()
            val f = ((value - range.start) / span).coerceIn(0f, 1f)
            drawRoundRect(
                color = kb.secondaryText.copy(alpha = 0.35f),
                topLeft = Offset(0f, y - track / 2),
                size = Size(size.width, track),
                cornerRadius = CornerRadius(track),
            )
            val from = if (twoWay) ((reset - range.start) / span) * size.width else 0f
            val to = f * size.width
            drawRoundRect(
                color = kb.accent,
                topLeft = Offset(minOf(from, to), y - track / 2),
                size = Size(abs(to - from), track),
                cornerRadius = CornerRadius(track),
            )
            if (twoWay) drawCircle(kb.secondaryText, radius = 2.dp.toPx(), center = Offset(from, y))
            drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(to, y))
            drawCircle(kb.accent, radius = 6.dp.toPx(), center = Offset(to, y))
        }
    }
}

/** A slider's value as the label beside it: "+35", "-12", "0". */
internal fun signedPercent(v: Float, twoWay: Boolean): String {
    val p = (v * 100).roundToInt()
    return if (twoWay && p > 0) "+$p" else p.toString()
}

/** The colour row: a ring of swatches, the picked one ringed. */
@Composable
internal fun ColorRow(colors: List<Int>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEach { c ->
            val on = c == selected
            Box(
                Modifier
                    .size(if (on) 30.dp else 26.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (on) 3.dp else 1.dp, if (on) kb.accent else kb.secondaryText.copy(alpha = 0.6f), CircleShape)
                    .clickable { onPick(c) },
            )
        }
    }
}

/** A badge-sized dot pinned to a box's corner. */
@Composable
internal fun BoxScope.CornerMark(icon: ImageVector, alignment: Alignment, modifier: Modifier = Modifier) {
    Box(
        modifier
            .align(alignment)
            .padding(4.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = OnPhoto, modifier = Modifier.size(11.dp)) }
}

/**
 * Pinch-zoom, pan, double-tap and tap for a picture in a pager: the
 * settings app's viewer gesture (`ImageViewer.zoomAndPan`), here for the
 * keyboard. A one-finger drag at rest falls through to the pager, so swiping
 * still turns the page; once zoomed, the image keeps every drag.
 */
@Composable
internal fun Modifier.zoomAndPan(
    zoomed: Boolean,
    onTransform: (zoomChange: Float, panChange: Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onTap: () -> Unit,
): Modifier {
    val isZoomed by rememberUpdatedState(zoomed)
    val transform by rememberUpdatedState(onTransform)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val tap by rememberUpdatedState(onTap)
    return this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pinching = event.changes.count { it.pressed } > 1
                    if (!pinching && !isZoomed) continue
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    if (zoomChange == 1f && panChange == Offset.Zero) continue
                    transform(zoomChange, panChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap(it) }, onTap = { tap() }) }
}

internal const val ZOOM_EPSILON = 0.01f
internal const val MAX_ZOOM = 6f
internal const val DOUBLE_TAP_ZOOM = 2.5f
