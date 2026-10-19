package com.wasimaster.wmkeyboard.app.tips

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.CaptionText
import com.wasimaster.wmkeyboard.app.ConfettiOverlay
import com.wasimaster.wmkeyboard.app.LocalReduceMotion
import com.wasimaster.wmkeyboard.app.StateBanner
import com.wasimaster.wmkeyboard.app.rememberSettingsHaptics
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChatBubbleOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Person
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import kotlinx.coroutines.launch

/**
 * The tip jar on About › Supporters, Play build only: a row of tiers, the
 * name to be listed under, and one button that opens Play's purchase sheet.
 *
 * The whole card turns into its thank-you once Play reports the order paid,
 * and back into the jar on "Tip again". Every motion here is decoration, so
 * reduce motion keeps each state and drops the movement between them.
 */
@Composable
internal fun TipJarCard(jar: TipJar) {
    val offers by jar.offers.collectAsStateWithLifecycle()
    val purchase by jar.purchase.collectAsStateWithLifecycle()
    when (val state = offers) {
        TipOffers.Unavailable -> StateBanner(stringResource(R.string.tips_unavailable))
        TipOffers.Loading, is TipOffers.Ready -> Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AnimatedContent(
                targetState = purchase is TipPurchase.Thanked,
                transitionSpec = { cardSwap() },
                label = "tip-card",
            ) { thanked ->
                val done = purchase as? TipPurchase.Thanked
                if (thanked && done != null) {
                    ThankYou(done, onAgain = jar::reset)
                } else {
                    Jar(
                        offers = (state as? TipOffers.Ready)?.offers,
                        purchase = purchase,
                        onBuy = jar::buy,
                    )
                }
            }
        }
    }
}

private fun cardSwap(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(360, delayMillis = 90)) + scaleIn(tween(360, delayMillis = 90), initialScale = 0.92f),
    initialContentExit = fadeOut(tween(160)),
    sizeTransform = SizeTransform(clip = false),
)

@Composable
private fun Jar(
    offers: List<TipOffer>?,
    purchase: TipPurchase,
    onBuy: (TipTier, String, String) -> Unit,
) {
    val context = LocalContext.current
    val credits = remember { TipCredits(context) }
    var selected by rememberSaveable { mutableStateOf(TipTier.DEFAULT) }
    var anonymous by rememberSaveable { mutableStateOf(credits.anonymous) }
    var name by rememberSaveable { mutableStateOf(credits.name) }
    var message by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val haptics = rememberSettingsHaptics()
    val busy = purchase is TipPurchase.Paying

    Column(Modifier.padding(vertical = 20.dp).animateContentSize()) {
        Text(
            stringResource(R.string.tips_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.tips_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(16.dp))

        TierRow(offers, selected) { selected = it }

        Spacer(Modifier.height(12.dp))
        AnonymousSwitch(anonymous, enabled = !busy) {
            haptics.toggle(it)
            anonymous = it
        }

        // The name and the message only mean something to a tip that is
        // listed, so they fold away under the switch rather than sit there
        // disabled, asking for text that would be thrown away.
        AnimatedVisibility(
            visible = !anonymous,
            enter = expandVertically(tween(260)) + fadeIn(tween(220, delayMillis = 60)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(120)),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(NAME_LIMIT) },
                    label = { Text(stringResource(R.string.tips_name_label)) },
                    supportingText = { Text(stringResource(R.string.tips_name_hint)) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(MESSAGE_LIMIT) },
                    label = { Text(stringResource(R.string.tips_message_label)) },
                    supportingText = {
                        Row {
                            Text(stringResource(R.string.tips_message_hint), modifier = Modifier.weight(1f))
                            Text("${message.length}/$MESSAGE_LIMIT")
                        }
                    },
                    leadingIcon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        val price = offers?.firstOrNull { it.tier == selected }?.price
        TipButton(
            tier = selected,
            price = price,
            busy = busy,
            enabled = price != null && !busy,
            onClick = {
                focus.clearFocus()
                credits.anonymous = anonymous
                if (!anonymous) credits.name = name
                // Anonymous sends a blank name, which is what keeps a tip off
                // the list and its credit from being queued at all.
                if (anonymous) onBuy(selected, "", "") else onBuy(selected, name, message)
            },
        )

        val note = when (purchase) {
            is TipPurchase.Pending -> stringResource(R.string.tips_pending)
            TipPurchase.Failed -> stringResource(R.string.tips_failed)
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            CaptionText(
                note,
                error = purchase == TipPurchase.Failed,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

/** "Stay anonymous": a switch row inside the card, pressable along its whole width. */
@Composable
private fun AnonymousSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        AnimatedContent(
            targetState = checked,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)).togetherWith(fadeOut()) },
            label = "anonymous-icon",
        ) { hidden ->
            Icon(
                if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tips_anonymous_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.tips_anonymous_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The row owns the toggle; the switch only shows it.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * The tiers as a snapping row of tiles, small to large. Null [offers] is the
 * moment before Play answers: the tiles are drawn without prices and pulse
 * gently, so the card has its final size from the first frame.
 */
@Composable
private fun TierRow(offers: List<TipOffer>?, selected: TipTier, onSelect: (TipTier) -> Unit) {
    val tiers = offers?.map { it.tier } ?: TipTier.entries
    val listState = rememberLazyListState()
    val haptics = rememberSettingsHaptics()
    val still = LocalReduceMotion.current
    // Opens with the default tier in view rather than scrolled off the end.
    LaunchedEffect(tiers) {
        val index = tiers.indexOf(selected).coerceAtLeast(0)
        listState.scrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(tiers, key = { _, tier -> tier.productId }) { index, tier ->
            TierTile(
                tier = tier,
                price = offers?.firstOrNull { it.tier == tier }?.price,
                selected = tier == selected,
                loading = offers == null,
                still = still,
                onClick = {
                    if (tier != selected) haptics.tick()
                    onSelect(tier)
                },
                entranceDelay = index * TILE_STAGGER_MS,
            )
        }
    }
}

@Composable
private fun TierTile(
    tier: TipTier,
    price: String?,
    selected: Boolean,
    loading: Boolean,
    still: Boolean,
    entranceDelay: Int,
    onClick: () -> Unit,
) {
    val label = stringResource(tier.label)
    val scale by animateFloatAsState(
        if (selected) 1f else 0.94f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "tile-scale",
    )
    val (from, to) = tier.wash
    val outline = MaterialTheme.colorScheme.outlineVariant
    val border by animateColorAsState(if (selected) Color.Transparent else outline, label = "tile-border")
    val onWash = Color.White
    val iconTint by animateColorAsState(if (selected) onWash else from, label = "tile-icon")
    val textColor by animateColorAsState(
        if (selected) onWash else MaterialTheme.colorScheme.onSurface,
        label = "tile-text",
    )
    val wash by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "tile-wash")

    // Each tile rises into place a beat after the one before it.
    val entrance = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!still) entrance.animateTo(1f, tween(380, delayMillis = entranceDelay, easing = FastOutSlowInEasing))
    }
    // The icon hops when its tile is picked.
    val hop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected && !still) {
            hop.animateTo(ICON_HOP, tween(110))
            hop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
        }
    }
    val pulse = if (loading && !still) loadingPulse() else 1f
    val shape = RoundedCornerShape(22.dp)
    val description = if (price != null) "$label, $price" else label

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = entrance.value * pulse
                translationY = (1f - entrance.value) * 24.dp.toPx()
            }
            .width(TILE_WIDTH)
            .height(TILE_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .background(Brush.linearGradient(listOf(from, to)), alpha = wash)
            .border(BorderStroke(1.dp, border), shape)
            .selectable(selected = selected, enabled = !loading, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                tier.icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(30.dp).scale(hop.value),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1,
            )
            Text(
                price ?: " ",
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

/** A slow breathing alpha for the tiles while Play has not answered. */
@Composable
private fun loadingPulse(): Float {
    val transition = rememberInfiniteTransition(label = "tile-loading")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "tile-loading-alpha",
    )
    return alpha
}

/**
 * The one button, washed in the selected tier's colours. The price rolls up
 * when a bigger tier is picked and down for a smaller one, so the change is
 * seen as well as read.
 */
@Composable
private fun TipButton(tier: TipTier, price: String?, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (from, to) = tier.wash
    val start by animateColorAsState(from, tween(320), label = "button-from")
    val end by animateColorAsState(to, tween(320), label = "button-to")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(56.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(start, end)), alpha = if (enabled || busy) 1f else 0.5f),
        ) {
            AnimatedContent(
                targetState = Triple(tier, price, busy),
                transitionSpec = {
                    val up = targetState.first.ordinal >= initialState.first.ordinal
                    (slideInVertically { if (up) it else -it } + fadeIn())
                        .togetherWith(slideOutVertically { if (up) -it else it } + fadeOut())
                        .using(SizeTransform(clip = false))
                },
                label = "button-label",
            ) { (_, shownPrice, shownBusy) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shownBusy) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.tips_paying),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            if (shownPrice != null) stringResource(R.string.tips_button, shownPrice)
                            else stringResource(R.string.tips_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the card becomes once a tip is paid: a heart that pops and sends out
 * a ring, confetti over the card, and the line saying where the name went.
 */
@Composable
private fun ThankYou(done: TipPurchase.Thanked, onAgain: () -> Unit) {
    val still = LocalReduceMotion.current
    val view = LocalView.current
    val pop = remember { Animatable(if (still) 1f else 0f) }
    val ring = remember { Animatable(if (still) 1f else 0f) }
    var confetti by remember { mutableStateOf(!still) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS,
        )
        if (still) return@LaunchedEffect
        scope.launch { ring.animateTo(1f, tween(900, delayMillis = 120, easing = FastOutSlowInEasing)) }
        pop.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessLow))
    }
    val (from, to) = done.tier.wash

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
                Canvas(Modifier.matchParentSize()) {
                    val t = ring.value
                    if (t in 0f..0.999f) {
                        drawCircle(
                            brush = Brush.linearGradient(listOf(from, to)),
                            radius = size.minDimension / 2 * (0.45f + 0.55f * t),
                            alpha = 1f - t,
                            style = Stroke(width = 4.dp.toPx() * (1f - t) + 1f),
                        )
                    }
                }
                Heart(
                    brush = Brush.linearGradient(listOf(from, to)),
                    modifier = Modifier.size(72.dp).scale(pop.value),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.tips_thanks_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (done.name != null) stringResource(R.string.tips_thanks_named, done.name)
                else stringResource(R.string.tips_thanks_anonymous),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onAgain, shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.tips_again))
            }
        }
        if (confetti) {
            ConfettiOverlay(Modifier.matchParentSize()) { confetti = false }
        }
    }
}

/** A filled heart, drawn rather than taken from the icon set, which has no filled one. */
@Composable
internal fun Heart(brush: Brush, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawPath(heartPath(size.width, size.height), brush) }
}

/** A heart filling a [width] by [height] box from its top left, point at the bottom. */
internal fun heartPath(width: Float, height: Float): Path {
    val w = width
    val h = height
    return Path().apply {
        moveTo(w * 0.5f, h * 0.27f)
        cubicTo(w * 0.5f, h * 0.24f, w * 0.44f, h * 0.08f, w * 0.26f, h * 0.08f)
        cubicTo(w * 0.04f, h * 0.08f, 0f, h * 0.3f, 0f, h * 0.4f)
        cubicTo(0f, h * 0.62f, w * 0.22f, h * 0.78f, w * 0.5f, h * 0.95f)
        cubicTo(w * 0.78f, h * 0.78f, w, h * 0.62f, w, h * 0.4f)
        cubicTo(w, h * 0.3f, w * 0.96f, h * 0.08f, w * 0.74f, h * 0.08f)
        cubicTo(w * 0.56f, h * 0.08f, w * 0.5f, h * 0.24f, w * 0.5f, h * 0.27f)
        close()
    }
}

/** Same cuts as the server's NAME_LIMIT and MESSAGE_LIMIT, so what is typed is what is listed. */
private const val NAME_LIMIT = 60
private const val MESSAGE_LIMIT = 280

private val TILE_WIDTH = 96.dp
private val TILE_HEIGHT = 112.dp
private const val TILE_STAGGER_MS = 45
private const val ICON_HOP = 1.25f
