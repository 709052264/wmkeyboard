package com.wasimaster.wmkeyboard.ime.ui.homeassistant

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.homeassistant.HaKind
import com.wasimaster.wmkeyboard.core.homeassistant.HaPrimary
import com.wasimaster.wmkeyboard.core.icons.symbols.filled.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.settings.HaTileSize
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.homeassistant.HaAction
import com.wasimaster.wmkeyboard.ime.homeassistant.HaTile
import com.wasimaster.wmkeyboard.ime.homeassistant.HaUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.chipBorder
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockSwitch
import com.wasimaster.wmkeyboard.ime.ui.clock.digits
import com.wasimaster.wmkeyboard.ime.ui.focusRing
import com.wasimaster.wmkeyboard.ime.ui.frostGlass
import com.wasimaster.wmkeyboard.ime.ui.maxContrastOn
import com.wasimaster.wmkeyboard.ime.ui.shortLandscape

/**
 * The grid of tiles for the open tab. Two across, three on a wide or short
 * screen, four when the user asked for compact tiles. Keyed by entity id so a
 * tile keeps its state when the list reorders, and staggered in on open.
 */
@Composable
internal fun HaTileGrid(state: KeyboardUiState, ui: HaUi, animate: Boolean, act: (HaAction) -> Unit) {
    val wide = shortLandscape()
    val columns = when (ui.tileSize) {
        HaTileSize.COMPACT -> if (wide) 5 else 4
        HaTileSize.COMFORTABLE -> if (wide) 3 else 2
    }
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    PanelFocusTarget(panel = PanelMode.HOME_ASSISTANT, region = FocusRegion.RESULTS, count = ui.tiles.size, columns = columns) { index ->
        ui.tiles.getOrNull(index)?.let { act(HaAction.Tap(it.id)) }
    }
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        modifier = Modifier.fillMaxSize().alpha(if (ui.fromCache) 0.72f else 1f),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(ui.tiles, key = { _, t -> t.id }) { index, tile ->
            EntityTile(
                tile = tile,
                compact = ui.tileSize == HaTileSize.COMPACT,
                focused = focused == index,
                animate = animate,
                shakeNonce = ui.shake?.takeIf { it.first == tile.id }?.second,
                ranAt = ui.ranAt[tile.id],
                confirmRisky = ui.confirmRisky,
                modifier = Modifier.tileAppear(index, animate),
                act = act,
            )
        }
    }
}

/**
 * One tile: the glyph in a soft circle that fills with the accent as the thing
 * comes on (and, for a light, as bright as the light), the name, the state,
 * and on the right the one control the kind has: a switch, a big figure, a
 * play arrow, or a chevron into the card. Tap acts; hold opens the card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun EntityTile(
    tile: HaTile,
    compact: Boolean,
    focused: Boolean,
    animate: Boolean,
    shakeNonce: Long?,
    ranAt: Long?,
    confirmRisky: Boolean,
    modifier: Modifier = Modifier,
    act: (HaAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val on = tile.shownOn || tile.shownState in ACTIVE_STATES
    val brightness = if (tile.kind == HaKind.LIGHT && tile.shownOn) (tile.entity.brightnessPct ?: 100) / 100f else 1f
    val fill by animateFloatAsState(
        targetValue = when {
            !tile.entity.usable -> 0.08f
            on -> 0.35f + 0.65f * brightness
            else -> 0.14f
        },
        animationSpec = if (animate) tween(220) else tween(0),
        label = "haFill",
    )
    val pendingAlpha by animateFloatAsState(if (tile.isPending) HaMotion.PENDING_ALPHA else 1f, tween(if (animate) 160 else 0), label = "haPending")
    // A risky thing on the tile is never one tap: the card has the hold.
    val tapExpands = tile.primary == HaPrimary.EXPAND || (confirmRisky && tile.risky)
    Column(
        modifier = modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CARDS)
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.8f))
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .statePulse(tile.changedAtMs, animate, kb.accent)
            .runFlash(ranAt, animate, kb.accent)
            .shake(shakeNonce, animate)
            .combinedClickable(
                onClick = { act(if (tapExpands) HaAction.Expand(tile.id) else HaAction.Tap(tile.id)) },
                onLongClick = { act(HaAction.Expand(tile.id)) },
            )
            .padding(horizontal = 10.dp, vertical = if (compact) 8.dp else 10.dp)
            .semantics { contentDescription = "${tile.name}, ${tile.stateLine}" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(if (compact) 28.dp else 34.dp)
                    .clip(CircleShape)
                    .background(if (on) kb.accent.copy(alpha = fill) else kb.accent.copy(alpha = fill)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    tile.glyph.vector(),
                    contentDescription = null,
                    tint = if (on && fill > 0.5f) maxContrastOn(kb.accent) else kb.accent,
                    modifier = Modifier.size(if (compact) 16.dp else 19.dp).alpha(if (tile.entity.usable) 1f else 0.5f),
                )
            }
            if (!compact) {
                Spacer(Modifier.weight(1f))
                if (tile.favourite) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = kb.accent.copy(alpha = 0.8f), modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Text(
            tile.name,
            color = kb.suggestionText,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().alpha(pendingAlpha)) {
            Box(Modifier.weight(1f)) {
                com.wasimaster.wmkeyboard.ime.ui.MotionCrossfade(tile.stateLine, animate, label = "haState") { line ->
                    Text(
                        line,
                        color = kb.secondaryText,
                        fontSize = if (compact) 10.sp else 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                TileTrailing(tile, on, confirmRisky, act)
            }
        }
    }
}

/** The control on the tile's right: a switch for the toggleable, the figure for a sensor, an arrow for the rest. */
@Composable
private fun TileTrailing(tile: HaTile, on: Boolean, confirmRisky: Boolean, act: (HaAction) -> Unit) {
    val kb = LocalKbTheme.current
    when {
        tile.hasSwitch && !(confirmRisky && tile.risky) -> ClockSwitch(
            on = on,
            description = stringResource(R.string.ime_ha_tile_switch_desc, tile.name),
        ) { act(HaAction.Toggle(tile.id, it)) }
        tile.value != null -> Text(
            tile.value,
            color = kb.suggestionText,
            style = digits(15.sp, FontWeight.Medium),
            maxLines = 1,
        )
        tile.primary == HaPrimary.RUN || tile.primary == HaPrimary.PRESS -> Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = stringResource(R.string.ime_ha_run),
            tint = kb.accent,
            modifier = Modifier.size(18.dp),
        )
        else -> Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = stringResource(R.string.ime_ha_tile_expand_desc),
            tint = kb.secondaryText,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** States that light the glyph up without being "on". */
private val ACTIVE_STATES = setOf("open", "opening", "unlocked", "playing", "heating", "cooling", "cleaning", "armed_home", "armed_away", "armed_night", "armed_vacation", "triggered", "home")

/** The accent for a state the tile paints with: unused for now, kept for the climate action colours. */
@Suppress("unused")
internal fun hvacColor(action: String?, kb: com.wasimaster.wmkeyboard.ime.ui.KbTheme): Color = when (action) {
    "heating" -> Color(0xFFFF7043)
    "cooling" -> Color(0xFF42A5F5)
    else -> kb.accent
}
