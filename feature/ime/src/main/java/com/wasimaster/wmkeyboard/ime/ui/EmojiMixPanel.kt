package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shuffle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.emojimix.EmojiMixCell
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the emoji mixes panel reaches. Rides [ToolHoldCallbacks] for the
 * reason its other passengers do: the caller cannot afford another parameter.
 */
data class EmojiMixCallbacks(
    /** An emoji in the picker: it goes into the slot the panel is on. */
    val onPickEmoji: (String) -> Unit = {},
    /** One of the two slots: the next pick replaces that emoji. */
    val onSlot: (Int) -> Unit = {},
    val onSwap: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    /** A mix tapped: send it. */
    val onSend: (EmojiMixCell) -> Unit = {},
    /** A mix starred, or its star taken off. */
    val onFavourite: (EmojiMixCell, Boolean) -> Unit = { _, _ -> },
    val onAddToStickers: (EmojiMixCell) -> Unit = {},
    val onSaveToGallery: (EmojiMixCell) -> Unit = {},
)

private val SlotSize = 52.dp
private val ResultSize = 64.dp
private val PickerCell = 40.dp
private val FavouriteCell = 44.dp

/** The two emoji coming together: a bouncy spring, under-damped so the mix lands with a little overshoot. */
private val FuseSpring = spring<Float>(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow)

/** A slot taking its new emoji, and the picker's press: snappy, barely bouncing. */
private val PopSpring = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

private const val BurstMs = 620
private const val BurstSparks = 10
private const val GridStaggerMs = 22
private const val GridStaggerCap = 18
private const val GridRiseMs = 260
private const val SentMs = 200

/**
 * The emoji mixes panel: the two emoji and their mix along the top, the
 * user's starred mixes, every mix of the first emoji, and the emoji to pick
 * from at the bottom. A tap on a mix sends it; a hold offers a star, the
 * sticker packs and the gallery. [switcher] is the emoji / GIF / sticker
 * switch when the panel carries it beside its own header.
 *
 * Everything moves when motion is on, and nothing does when it is off:
 * the mix lands on a spring with a ring and sparks thrown out behind it,
 * each slot's emoji rolls in, the grid of mixes rises in a quick stagger,
 * and a sent mix swells and lifts away.
 */
@Composable
internal fun EmojiMixPanel(
    state: KeyboardUiState,
    callbacks: EmojiMixCallbacks,
    switcher: (@Composable () -> Unit)?,
) {
    val kb = LocalKbTheme.current
    val motion = kb.motion.any
    val ui = state.emojiMix
    val loader = rememberMediaImageLoader()
    val cellSize = state.settings.emoji.mix.cellSize.dp
    val starred = remember(ui.favourites) { ui.favourites.mapTo(HashSet()) { it.id } }
    if (!ui.usable) {
        Notice(R.string.ime_emoji_mix_no_source)
        return
    }
    // Counted here so the slots know a change came from the swap button and
    // slide sideways past each other instead of rolling.
    var swaps by remember { mutableIntStateOf(0) }
    var shuffles by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ResultSize + 12.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MixSlot(ui.left, swaps, toStart = false, ui.slot == 0, motion, R.string.ime_emoji_mix_first_desc) {
                callbacks.onSlot(0)
            }
            Sign("+")
            MixSlot(ui.right, swaps, toStart = true, ui.slot == 1, motion, R.string.ime_emoji_mix_second_desc) {
                callbacks.onSlot(1)
            }
            Sign("=", pulse = ui.result?.id, motion = motion)
            ResultSpot(state, ui.result, ui.loading, ui.noMix, loader, starred, motion, callbacks)
            Spacer(Modifier.weight(1f))
            val swapTurn by animateFloatAsState(
                targetValue = swaps * 180f,
                animationSpec = if (motion) PopSpring else tween(0),
                label = "swapTurn",
            )
            val shuffleTurn by animateFloatAsState(
                targetValue = shuffles * 360f,
                animationSpec = if (motion) spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow) else tween(0),
                label = "shuffleTurn",
            )
            HeaderIcon(Icons.Outlined.SwapHoriz, R.string.ime_emoji_mix_swap_desc, swapTurn) {
                if (ui.right.isNotEmpty()) swaps++
                callbacks.onSwap()
            }
            HeaderIcon(Icons.Outlined.Shuffle, R.string.ime_emoji_mix_shuffle_desc, shuffleTurn) {
                shuffles++
                callbacks.onShuffle()
            }
            switcher?.invoke()
        }
        if (ui.favourites.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(FavouriteCell + 8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(ui.favourites, key = { it.id }) { cell ->
                    Box(Modifier.animateItem()) {
                        MixCell(cell, FavouriteCell, loader, state.mediaDownloadingId == cell.id, true, motion, callbacks)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                ui.previewsHeld -> Notice(R.string.ime_emoji_mix_previews_held)
                ui.mixes.isEmpty() && ui.left.isEmpty() -> PickFirstNotice(motion)
                ui.mixes.isEmpty() && !ui.canList && !ui.loading -> Notice(R.string.ime_emoji_mix_pick_second)
                else -> {
                    // One clock per first emoji: a new one replays the rise,
                    // and a cell scrolled to later finds it already run out.
                    val rise = rememberGridRise(ui.left, motion)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(cellSize),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(6.dp),
                    ) {
                        itemsIndexed(ui.mixes, key = { _, cell -> cell.id }) { index, cell ->
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .gridRise(rise, index),
                                contentAlignment = Alignment.Center,
                            ) {
                                MixCell(
                                    cell, cellSize, loader,
                                    sending = state.mediaDownloadingId == cell.id,
                                    starred = cell.id in starred,
                                    motion = motion,
                                    callbacks = callbacks,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(kb.divider))
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().height(PickerCell * 2 + 8.dp),
            contentPadding = PaddingValues(4.dp),
        ) {
            items(ui.emoji.size, key = { ui.emoji[it] }) { i ->
                val emoji = ui.emoji[i]
                PickerEmoji(emoji, chosen = emoji == ui.left || emoji == ui.right, motion) { callbacks.onPickEmoji(emoji) }
            }
        }
    }
}

/**
 * One of the two emoji. A new emoji rolls up into it; one that arrived by a
 * swap slides across instead, the two passing each other. The slot the next
 * pick will fill glows softly while it waits for one.
 */
@Composable
private fun MixSlot(
    emoji: String,
    swaps: Int,
    toStart: Boolean,
    selected: Boolean,
    motion: Boolean,
    description: Int,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val label = stringResource(description)
    val glow = if (motion && selected) {
        val pulse = rememberInfiniteTransition(label = "slotGlow")
        pulse.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "slotGlowAlpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(SlotSize)
            .clip(shape)
            .background(if (selected) kb.chipActive else kb.chip)
            .then(
                if (selected) {
                    Modifier.border(2.dp, kb.accent.copy(alpha = if (emoji.isEmpty()) glow else 1f), shape)
                } else {
                    Modifier.chipBorder(kb, shape)
                },
            )
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = emoji to swaps,
            transitionSpec = { slotTransition(initialState, targetState, toStart, motion) },
            label = "slotEmoji",
        ) { (shown, _) ->
            Text(shown.ifEmpty { "?" }, fontSize = 28.sp, color = if (selected) kb.chipActiveText else kb.chipText)
        }
    }
}

private fun slotTransition(
    from: Pair<String, Int>,
    to: Pair<String, Int>,
    toStart: Boolean,
    motion: Boolean,
): ContentTransform {
    if (!motion || from.first == to.first) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val swapped = from.second != to.second
    val enter = if (swapped) {
        slideInHorizontally(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { w ->
            if (toStart) w else -w
        } + fadeIn(tween(120))
    } else {
        slideInVertically(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) { h -> h } +
            scaleIn(PopSpring, initialScale = 0.5f) + fadeIn(tween(90))
    }
    val exit = if (swapped) {
        slideOutHorizontally(tween(180)) { w -> if (toStart) -w else w } + fadeOut(tween(140))
    } else {
        slideOutVertically(tween(160)) { h -> -h / 2 } + scaleOut(tween(160), targetScale = 0.6f) + fadeOut(tween(120))
    }
    return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
}

/**
 * Where the mix lands. A new mix comes in small and turned a little, and
 * springs to size, while [FusionBurst] throws a ring and sparks out from
 * behind it: the two emoji have just become one.
 */
@Composable
private fun ResultSpot(
    state: KeyboardUiState,
    result: EmojiMixCell?,
    loading: Boolean,
    noMix: Boolean,
    loader: ImageLoader,
    starred: Set<String>,
    motion: Boolean,
    callbacks: EmojiMixCallbacks,
) {
    val kb = LocalKbTheme.current
    Box(Modifier.size(ResultSize), contentAlignment = Alignment.Center) {
        if (result != null && motion) FusionBurst(result.id, kb.accent)
        AnimatedContent(
            targetState = result,
            contentKey = { it?.id },
            transitionSpec = {
                val change = if (!motion) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    (scaleIn(FuseSpring, initialScale = 0.35f) + fadeIn(tween(120))) togetherWith
                        (scaleOut(tween(140), targetScale = 1.25f) + fadeOut(tween(120)))
                }
                change.using(SizeTransform(clip = false))
            },
            label = "mixResult",
        ) { cell ->
            if (cell != null) {
                val wobble = remember(cell.id) { Animatable(if (motion) -14f else 0f) }
                LaunchedEffect(cell.id) { wobble.animateTo(0f, FuseSpring) }
                Box(Modifier.graphicsLayer { rotationZ = wobble.value }) {
                    MixCell(
                        cell = cell,
                        size = ResultSize,
                        loader = loader,
                        sending = state.mediaDownloadingId == cell.id,
                        starred = cell.id in starred,
                        motion = motion,
                        callbacks = callbacks,
                    )
                }
            } else {
                Box(Modifier.size(ResultSize), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(24.dp), color = kb.accent, strokeWidth = 2.dp)
                        noMix -> Text(
                            stringResource(R.string.ime_emoji_mix_none),
                            color = kb.secondaryText,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A ring that opens out and fades, and sparks flung outward on their own
 * angles, once per [key]. Drawn behind the mix in its own canvas, so a
 * frame of it redraws nothing else.
 */
@Composable
private fun FusionBurst(key: Any, color: Color) {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(BurstMs, easing = LinearEasing)) }
    // A fresh scatter each time, so two mixes in a row never burst alike.
    val angles = remember(key) {
        FloatArray(BurstSparks) { i -> (i + Math.random().toFloat() * 0.6f) * (2f * PI.toFloat() / BurstSparks) }
    }
    val reach = remember(key) { FloatArray(BurstSparks) { 0.75f + Math.random().toFloat() * 0.5f } }
    val density = LocalDensity.current
    val ringWidth = with(density) { 3.dp.toPx() }
    val sparkRadius = with(density) { 2.5.dp.toPx() }
    Canvas(Modifier.size(ResultSize * 1.9f)) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val centre = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension / 2f
        val ease = 1f - (1f - t) * (1f - t)
        drawCircle(
            color = color.copy(alpha = (1f - t) * 0.55f),
            radius = maxR * (0.35f + 0.65f * ease),
            center = centre,
            style = Stroke(width = ringWidth * (1f - t * 0.7f)),
        )
        for (i in 0 until BurstSparks) {
            val r = maxR * (0.3f + 0.7f * ease) * reach[i]
            val a = angles[i]
            drawCircle(
                color = color.copy(alpha = (1f - t) * 0.9f),
                radius = sparkRadius * (1f - t * 0.6f),
                center = Offset(centre.x + cos(a) * r, centre.y + sin(a) * r),
            )
        }
    }
}

/** A plus or an equals sign; the equals gives a small beat each time a new mix lands. */
@Composable
private fun Sign(text: String, pulse: Any? = null, motion: Boolean = false) {
    val beat = remember { Animatable(1f) }
    LaunchedEffect(pulse) {
        if (motion && pulse != null) {
            beat.snapTo(1.45f)
            beat.animateTo(1f, PopSpring)
        }
    }
    Text(
        text,
        color = LocalKbTheme.current.secondaryText,
        fontSize = 18.sp,
        modifier = Modifier.graphicsLayer {
            scaleX = beat.value
            scaleY = beat.value
        },
    )
}

@Composable
private fun HeaderIcon(icon: ImageVector, description: Int, turn: Float, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(description),
            tint = kb.toolbarIcon,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn },
        )
    }
}

/** An emoji in the picker: it sinks under the finger and springs back, and sits lit while it is one of the two. */
@Composable
private fun PickerEmoji(emoji: String, chosen: Boolean, motion: Boolean, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion) 0.82f else 1f,
        animationSpec = if (motion) PopSpring else tween(0),
        label = "pickPress",
    )
    Box(
        modifier = Modifier
            .size(PickerCell)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (chosen) kb.chipActive.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 24.sp, color = kb.keyText)
    }
}

@Composable
private fun Notice(text: Int) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(text),
            color = LocalKbTheme.current.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Before anything is picked: two emoji drifting toward each other and apart
 * again over the words, which is the whole idea of the panel in one look.
 */
@Composable
private fun PickFirstNotice(motion: Boolean) {
    val kb = LocalKbTheme.current
    val drift = if (motion) {
        rememberInfiniteTransition(label = "pickFirst").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pickFirstDrift",
        ).value
    } else {
        0.5f
    }
    val gap = with(LocalDensity.current) { 18.dp.toPx() }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (side in listOf(-1f, 1f)) {
                Text(
                    if (side < 0) "😀" else "🐱",
                    fontSize = 30.sp,
                    modifier = Modifier.graphicsLayer {
                        translationX = side * gap * (1f - drift)
                        rotationZ = side * 10f * (1f - drift)
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.ime_emoji_mix_pick_first),
            color = kb.secondaryText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** The grid's rise: one clock for every cell, each cell a stagger behind the one before. */
@Stable
private class GridRise(val clock: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) {
    fun of(index: Int): Float {
        val ms = clock.value * GridClockMs
        return ((ms - minOf(index, GridStaggerCap) * GridStaggerMs) / GridRiseMs).coerceIn(0f, 1f)
    }
}

private const val GridClockMs = (GridRiseMs + GridStaggerMs * GridStaggerCap).toFloat()

@Composable
private fun rememberGridRise(key: String, motion: Boolean): GridRise {
    val clock = remember(key) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(clock) { clock.animateTo(1f, tween(GridClockMs.toInt(), easing = LinearEasing)) }
    return remember(clock) { GridRise(clock) }
}

private fun Modifier.gridRise(rise: GridRise, index: Int): Modifier = graphicsLayer {
    val p = rise.of(index)
    val eased = 1f - (1f - p) * (1f - p) * (1f - p)
    alpha = p.coerceAtMost(1f)
    translationY = (1f - eased) * 24.dp.toPx()
    val s = 0.82f + 0.18f * eased
    scaleX = s
    scaleY = s
}

/**
 * One mix: its picture, a spinner while it comes down to be sent, and on a
 * hold the menu of what else it can do. A tap swells it and lifts it a
 * little as it fades, then puts it back: the mix is on its way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixCell(
    cell: EmojiMixCell,
    size: Dp,
    loader: ImageLoader,
    sending: Boolean,
    starred: Boolean,
    motion: Boolean,
    callbacks: EmojiMixCallbacks,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    var menu by remember { mutableStateOf(false) }
    val sent = remember { Animatable(0f) }
    var held by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val heldScale by animateFloatAsState(held, if (motion) PopSpring else tween(0), label = "cellHeld")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val gone = sent.value
                val grow = heldScale * (1f + 0.22f * gone)
                scaleX = grow
                scaleY = grow
                translationY = -gone * 10.dp.toPx()
                alpha = 1f - gone
            }
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = {
                    callbacks.onSend(cell)
                    if (motion) {
                        scope.launch {
                            sent.animateTo(1f, tween(SentMs, easing = FastOutSlowInEasing))
                            sent.snapTo(0f)
                        }
                    }
                },
                onLongClick = {
                    feedback()
                    held = 1.12f
                    menu = true
                },
                onLongClickLabel = stringResource(R.string.ime_emoji_mix_more_desc),
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = cell.url,
            contentDescription = stringResource(R.string.ime_emoji_mix_item_desc, cell.left, cell.right),
            imageLoader = loader,
            modifier = Modifier.size(size).padding(2.dp),
            contentScale = ContentScale.Fit,
        )
        if (sending) CircularProgressIndicator(Modifier.size(size / 3), color = kb.accent, strokeWidth = 2.dp)
        DropdownMenu(
            expanded = menu,
            onDismissRequest = {
                menu = false
                held = 1f
            },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(if (starred) R.string.ime_emoji_mix_unstar else R.string.ime_emoji_mix_star)) },
                onClick = {
                    menu = false
                    held = 1f
                    callbacks.onFavourite(cell, !starred)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ime_emoji_mix_add_to_stickers)) },
                onClick = {
                    menu = false
                    held = 1f
                    callbacks.onAddToStickers(cell)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ime_emoji_mix_save_to_gallery)) },
                onClick = {
                    menu = false
                    held = 1f
                    callbacks.onSaveToGallery(cell)
                },
            )
        }
    }
}
