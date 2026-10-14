package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.chipShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlin.math.abs

/**
 * An hour and a minute, picked on drums that spin and snap like a phone's own
 * time picker, with a tick under the finger at every step. In 12-hour mode a
 * third drum carries AM and PM. [onChange] gets the 24-hour hour.
 */
@Composable
internal fun TimeWheel(
    hour: Int,
    minute: Int,
    hour24: Boolean,
    amLabel: String,
    pmLabel: String,
    hourDescription: String,
    minuteDescription: String,
    onChange: (hour: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val current by rememberUpdatedState(hour to minute)
    Box(modifier, contentAlignment = Alignment.Center) {
        // The band behind the selected row, across every drum.
        Box(
            Modifier
                .fillMaxWidth()
                .height(ITEM_HEIGHT)
                .clip(kb.chipShape())
                .background(kb.accent.copy(alpha = 0.14f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hour24) {
                Drum(
                    count = 24, value = hour, label = ::two, width = 52.dp, description = hourDescription,
                ) { onChange(it, current.second) }
            } else {
                val h12 = if (hour % 12 == 0) 12 else hour % 12
                Drum(
                    count = 12, value = h12 - 1, label = { (it + 1).toString() }, width = 52.dp, description = hourDescription,
                ) { idx ->
                    val pm = current.first >= 12
                    val h = (idx + 1) % 12 + if (pm) 12 else 0
                    onChange(h, current.second)
                }
            }
            Text(":", color = kb.suggestionText, fontSize = 26.sp, fontWeight = FontWeight.Light)
            Drum(
                count = 60, value = minute, label = ::two, width = 52.dp, description = minuteDescription,
            ) { onChange(current.first, it) }
            if (!hour24) {
                Drum(
                    count = 2, value = if (hour >= 12) 1 else 0, label = { if (it == 0) amLabel else pmLabel },
                    width = 48.dp, cyclic = false, description = "$amLabel/$pmLabel", fontSize = 16,
                ) { idx ->
                    val h = current.first % 12 + if (idx == 1) 12 else 0
                    onChange(h, current.second)
                }
            }
        }
    }
}

/**
 * One drum. Cyclic drums repeat their values a few hundred times either way,
 * which is a loop as far as any thumb can tell. The value is reported when
 * the drum comes to rest, and a value set from outside (a quick chip) spins
 * the drum to it.
 */
@Composable
private fun Drum(
    count: Int,
    value: Int,
    label: (Int) -> String,
    width: Dp,
    description: String,
    cyclic: Boolean = true,
    fontSize: Int = 26,
    onValue: (Int) -> Unit,
) {
    val kb = LocalKbTheme.current
    val haptic = LocalHapticFeedback.current
    val total = if (cyclic) count * REPEATS else count
    // The list index of value 0: the middle of the loop, or just past the
    // blank row that lets a plain drum's first value reach the centre.
    val start = if (cyclic) (total / 2) - (total / 2) % count else 1
    fun valueAt(index: Int) = if (cyclic) Math.floorMod(index - start, count) else (index - start).coerceIn(0, count - 1)
    // The list's first visible row sits one above the selected one.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start + value - 1)
    val fling = rememberSnapFlingBehavior(state)
    val centered by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: (start + value)
        }
    }
    val report by rememberUpdatedState(onValue)
    LaunchedEffect(state) {
        snapshotFlow { centered }.distinctUntilChanged().drop(1).collect {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().collect { moving ->
            if (!moving) {
                report(valueAt(centered))
            }
        }
    }
    LaunchedEffect(value) {
        if (valueAt(centered) != value && !state.isScrollInProgress) {
            val target = centered - valueAt(centered) + value
            state.animateScrollToItem((target - 1).coerceAtLeast(0))
        }
    }
    val leading = if (cyclic) 0 else 1
    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = Modifier
            .width(width)
            .height(ITEM_HEIGHT * VISIBLE)
            .semantics {
                contentDescription = description
                stateDescription = label(value)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!cyclic) item { Box(Modifier.height(ITEM_HEIGHT)) }
        items(total) { i ->
            val distance = abs(i + leading - centered)
            Box(Modifier.height(ITEM_HEIGHT), contentAlignment = Alignment.Center) {
                Text(
                    label(Math.floorMod(i, count)),
                    color = if (distance == 0) kb.suggestionText else kb.secondaryText,
                    style = digits(fontSize.sp, if (distance == 0) FontWeight.Normal else FontWeight.Light),
                    modifier = Modifier.graphicsLayer {
                        val d = distance.coerceAtMost(2)
                        alpha = 1f - d * 0.35f
                        scaleX = 1f - d * 0.12f
                        scaleY = scaleX
                    },
                )
            }
        }
        if (!cyclic) item { Box(Modifier.height(ITEM_HEIGHT)) }
    }
}

private val ITEM_HEIGHT = 36.dp
private const val VISIBLE = 3
private const val REPEATS = 200
