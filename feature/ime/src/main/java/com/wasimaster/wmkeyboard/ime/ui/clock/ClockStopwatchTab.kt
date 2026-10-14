package com.wasimaster.wmkeyboard.ime.ui.clock

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.Stopwatch
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Flag
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pause
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stopwatch: a dial whose dot sweeps once a minute around the reading,
 * Lap and Start/Pause under it, and the laps beside it, newest first, the
 * fastest in green and the slowest in red. It keeps running with the panel
 * closed, and the shade has its buttons.
 */
@Composable
internal fun StopwatchTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val sw = ui.data.stopwatch
    val elapsed = rememberElapsed(sw, animate = !state.settings.reduceMotion)
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SweepDial(elapsed, sw.running, Modifier.size(132.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        ClockFormats.stopwatch(elapsed),
                        color = LocalKbTheme.current.suggestionText,
                        style = digits(if (elapsed >= HOUR_MS) 20.sp else 26.sp),
                    )
                    if (sw.laps.isNotEmpty()) {
                        Text(
                            ClockFormats.stopwatch(elapsed - sw.laps.last()),
                            color = LocalKbTheme.current.secondaryText,
                            style = digits(12.sp, FontWeight.Normal),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sw.running) {
                    RoundButton(Icons.Outlined.Flag, stringResource(R.string.ime_clock_lap), size = 40.dp) { act(ClockAction.StopwatchLap) }
                } else {
                    RoundButton(Icons.Outlined.Replay, stringResource(R.string.ime_clock_reset), size = 40.dp) { act(ClockAction.StopwatchReset) }
                }
                RoundButton(
                    if (sw.running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    stringResource(if (sw.running) R.string.ime_clock_pause else R.string.ime_clock_start),
                    primary = true,
                    size = 52.dp,
                ) { act(ClockAction.StopwatchToggle) }
                RoundButton(Icons.Outlined.ContentPaste, stringResource(R.string.ime_clock_insert_laps), size = 40.dp) {
                    act(ClockAction.StopwatchInsert)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (sw.laps.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Laps(sw, Modifier.weight(1f))
        }
    }
}

/** The reading, redrawn every frame while it runs (and every tenth of a second with reduced motion). */
@Composable
private fun rememberElapsed(sw: Stopwatch, animate: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sw.running, animate) {
        if (!sw.running) return@LaunchedEffect
        while (true) {
            if (animate) {
                withFrameMillis { now = System.currentTimeMillis() }
            } else {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(SLOW_TICK_MS)
            }
        }
    }
    return sw.elapsedAt(if (sw.running) now else System.currentTimeMillis())
}

@Composable
private fun SweepDial(elapsed: Long, running: Boolean, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val fraction = (elapsed % MINUTE_MS) / MINUTE_MS.toFloat()
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val r = size.minDimension / 2f - stroke * 2
        val c = center
        // Sixty ticks, every fifth longer.
        for (i in 0 until 60) {
            val a = i / 60f * 2 * PI.toFloat()
            val long = i % 5 == 0
            val inner = r - (if (long) 7.dp.toPx() else 4.dp.toPx())
            val passed = i / 60f <= fraction
            drawLine(
                if (passed && running) kb.accent.copy(alpha = 0.7f) else kb.secondaryText.copy(alpha = if (long) 0.45f else 0.25f),
                c + polarOffset(a, inner),
                c + polarOffset(a, r),
                strokeWidth = (if (long) 1.6f else 1f) * density,
                cap = StrokeCap.Round,
            )
        }
        val a = fraction * 2 * PI.toFloat()
        drawCircle(kb.accent, stroke * 1.4f, c + polarOffset(a, r + stroke))
        drawCircle(kb.accent.copy(alpha = 0.12f), r + stroke * 2, c, style = Stroke(stroke / 2))
    }
}

private fun polarOffset(angle: Float, radius: Float) =
    androidx.compose.ui.geometry.Offset(sin(angle) * radius, -cos(angle) * radius)

@Composable
private fun Laps(sw: Stopwatch, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val lengths = sw.lapLengths
    val best = if (lengths.size > 1) lengths.min() else null
    val worst = if (lengths.size > 1) lengths.max() else null
    val listState = rememberLazyListState()
    LaunchedEffect(lengths.size) { listState.animateScrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        val rows = lengths.indices.reversed().toList()
        itemsIndexed(rows, key = { _, i -> i }) { _, i ->
            val len = lengths[i]
            val color = when (len) {
                best -> BEST
                worst -> WORST
                else -> kb.suggestionText
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.ime_clock_lap_number, i + 1),
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.width(44.dp),
                )
                Text(ClockFormats.stopwatch(len), color = color, style = digits(13.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
                Text(ClockFormats.stopwatch(sw.laps[i]), color = kb.secondaryText, style = digits(11.sp, FontWeight.Normal))
            }
        }
    }
}

private val BEST = Color(0xFF43A047)
private val WORST = Color(0xFFE53935)
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
private const val SLOW_TICK_MS = 100L
