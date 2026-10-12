package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.AppColorSettings
import com.wasimaster.wmkeyboard.core.theme.AppColorSample
import com.wasimaster.wmkeyboard.core.theme.PaletteStyle
import com.wasimaster.wmkeyboard.core.theme.paletteStyleSwatch
import com.wasimaster.wmkeyboard.ime.R

/**
 * The app colours row at the top of the Themes tool (issue #592): the colours
 * the app being typed into offered, then a "plain theme" circle, then the
 * palette styles each drawn in that app's colour.
 *
 * A colour press is remembered for this app alone, which is how an app whose
 * icon has two brand colours gets the second one. A style press applies to
 * every app, the way Android's own wallpaper style does.
 */
@Composable
internal fun AppColorStrip(
    sample: AppColorSample,
    settings: AppColorSettings,
    onPick: (String, Int) -> Unit,
    onStyle: (PaletteStyle) -> Unit,
) {
    val kb = LocalKbTheme.current
    val candidates = remember(sample, settings.source) { sample.candidates(settings.source) }
    val pick = settings.pickFor(sample.packageName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.ime_app_colors_label),
            color = kb.toolbarIcon,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.ime_app_colors_none),
                color = kb.toolbarIcon,
                fontSize = 11.sp,
            )
            return@Row
        }
        // A remembered pick past the end (the app changed its icon) shows on
        // the first colour, which is what the theme falls back to as well.
        val shown = if (pick == AppColorSettings.PICK_OFF) pick else pick.takeIf { it in candidates.indices } ?: 0
        candidates.forEachIndexed { index, color ->
            val description = stringResource(R.string.ime_app_colors_pick_desc, index + 1)
            ColorDot(
                color = Color(color.toInt()),
                selected = shown == index,
                ring = kb.accent,
                description = description,
            ) { onPick(sample.packageName, index) }
        }
        val offDescription = stringResource(R.string.ime_app_colors_off_desc)
        OffDot(
            selected = shown == AppColorSettings.PICK_OFF,
            ring = kb.accent,
            stroke = kb.toolbarIcon,
            description = offDescription,
        ) { onPick(sample.packageName, AppColorSettings.PICK_OFF) }
        if (shown == AppColorSettings.PICK_OFF) return@Row
        val seed = candidates[shown]
        val shape = kb.chipShape()
        PaletteStyle.entries.forEach { style ->
            val active = style == settings.style
            val swatch = remember(seed, style) { paletteStyleSwatch(seed, style) }
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) kb.chipActive else kb.chip)
                    .chipBorder(kb, shape)
                    .semantics { selected = active }
                    .clickable { onStyle(style) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StyleSwatch(swatch)
                Text(
                    stringResource(style.labelRes),
                    color = if (active) kb.chipActiveText else kb.chipText,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ColorDot(
    color: Color,
    selected: Boolean,
    ring: Color,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DOT_SIZE.dp)
            .clip(CircleShape)
            .border(if (selected) 2.dp else 0.dp, if (selected) ring else Color.Transparent, CircleShape)
            .padding(if (selected) 4.dp else 0.dp)
            .clip(CircleShape)
            .background(color)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .clickable(onClick = onClick),
    )
}

/** A hollow circle struck through: this app keeps the plain theme. */
@Composable
private fun OffDot(
    selected: Boolean,
    ring: Color,
    stroke: Color,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DOT_SIZE.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) ring else stroke.copy(alpha = 0.5f), CircleShape)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.size(DOT_SIZE.dp)) {
            val inset = size.minDimension * 0.25f
            drawLine(
                color = if (selected) ring else stroke.copy(alpha = 0.7f),
                start = Offset(inset, size.height - inset),
                end = Offset(size.width - inset, inset),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/** Three overlapping dots: the style's primary, secondary and tertiary. */
@Composable
private fun StyleSwatch(colors: List<Long>) {
    Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
        val radius = size.height / 2f
        colors.forEachIndexed { index, argb ->
            drawCircle(
                color = Color(argb.toInt()),
                radius = radius,
                center = Offset(radius + index * radius * 1.4f, radius),
            )
        }
    }
}

private const val DOT_SIZE = 26
