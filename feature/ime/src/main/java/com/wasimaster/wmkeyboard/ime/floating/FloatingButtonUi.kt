package com.wasimaster.wmkeyboard.ime.floating

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardHide
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.FloatingAction
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonStyle
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.toolLabelRes
import kotlin.math.roundToInt

/**
 * What the floating button (#755) and its quick menu look like. Public because
 * the settings page draws its preview from these same pieces, so the preview
 * cannot drift from the button it previews.
 */
@Immutable
data class FloatingPalette(
    val container: Color,
    val content: Color,
    val outline: Color,
    /** The ring round a slot the finger is on, and the accent style's fill. */
    val accent: Color,
)

@Composable
fun floatingPalette(style: FloatingButtonStyle): FloatingPalette {
    val dark = isSystemInDarkTheme()
    val accent = systemAccent()
    val darkFace = FloatingPalette(Color(0xF0202124), Color.White, Color(0x33FFFFFF), accent)
    val lightFace = FloatingPalette(Color(0xF7FFFFFF), Color(0xFF1F1F1F), Color(0x1F000000), accent)
    return when (style) {
        FloatingButtonStyle.AUTO -> if (dark) darkFace.copy(container = Color(0xF02E3036)) else lightFace
        FloatingButtonStyle.DARK -> darkFace
        FloatingButtonStyle.LIGHT -> lightFace
        FloatingButtonStyle.ACCENT -> FloatingPalette(accent.copy(alpha = 0.96f), Color.White, Color(0x33FFFFFF), accent)
    }
}

/** The system's accent from Android 12, a calm indigo before it. */
@Composable
private fun systemAccent(): Color {
    val context = LocalContext.current
    return remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Color(context.getColor(android.R.color.system_accent1_600))
        } else {
            Color(0xFF3F51B5)
        }
    }
}

/** The glyph an action is drawn with; the keyboard's swaps while the keyboard is up. */
fun floatingActionIcon(action: FloatingAction, keyboardUp: Boolean = false): ImageVector = when (action) {
    FloatingAction.None -> Icons.Outlined.Block
    FloatingAction.Keyboard -> if (keyboardUp) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard
    FloatingAction.AskAi -> Icons.Outlined.AutoAwesome
    FloatingAction.LookUp -> com.wasimaster.wmkeyboard.core.icons.SymbolIcons.Dictionary
    FloatingAction.Menu -> Icons.Outlined.Apps
    FloatingAction.Hide -> Icons.Outlined.VisibilityOff
    is FloatingAction.Tool -> IconDefaults.forTool(action.tool)
}

/** What an action is called, on the button, in its menu and on the settings page. */
@Composable
fun floatingActionLabel(action: FloatingAction, keyboardUp: Boolean = false): String =
    stringResource(floatingActionLabelRes(action, keyboardUp))

internal fun floatingActionLabelRes(action: FloatingAction, keyboardUp: Boolean): Int = when (action) {
    FloatingAction.None -> R.string.ime_floating_none_label
    FloatingAction.Keyboard -> if (keyboardUp) R.string.ime_summon_button_hide_label else R.string.ime_summon_button_label
    FloatingAction.AskAi -> R.string.ime_ai_button_label
    FloatingAction.LookUp -> R.string.ime_floating_lookup_label
    FloatingAction.Menu -> R.string.ime_floating_menu_label
    FloatingAction.Hide -> R.string.ime_floating_hide_label
    is FloatingAction.Tool -> toolLabelRes(action.tool)
}

/**
 * The button itself: a disc with a glyph, a soft shadow under it, and a press
 * that sinks it a little. [sizeDp] is the disc; the caller leaves room round it
 * for the shadow ([FLOATING_SHADOW_ROOM_DP] each side).
 */
@Composable
fun FloatingButtonFace(
    icon: ImageVector,
    style: FloatingButtonStyle,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    contentDescription: String? = null,
) {
    val palette = floatingPalette(style)
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "floating-press",
    )
    Box(
        modifier
            .size(sizeDp.dp)
            .scale(scale)
            .shadow(if (pressed) 2.dp else 6.dp, CircleShape)
            .background(palette.container, CircleShape)
            .border(1.dp, palette.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = palette.content,
            modifier = Modifier.size((sizeDp * 0.46f).dp),
        )
    }
}

/** Room left round the disc for its shadow and its press, in dp. */
const val FLOATING_SHADOW_ROOM_DP = 8

/** A quick menu slot's disc, in dp. */
const val FLOATING_MENU_ITEM_DP = 52

/** How wide a slot's name may run under it, in dp. */
const val FLOATING_MENU_LABEL_DP = 84

/** One quick menu slot: its disc, ringed while [hovered], and with [label] its name under it. */
@Composable
fun FloatingMenuItem(
    icon: ImageVector,
    label: String,
    style: FloatingButtonStyle,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
    hovered: Boolean = false,
) {
    val palette = floatingPalette(style)
    val grow by animateFloatAsState(
        if (hovered) 1.16f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "floating-hover",
    )
    Column(
        modifier.width(FLOATING_MENU_LABEL_DP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(FLOATING_MENU_ITEM_DP.dp)
                .scale(grow)
                .shadow(4.dp, CircleShape)
                .background(palette.container, CircleShape)
                .border(if (hovered) 2.5.dp else 1.dp, if (hovered) palette.accent else palette.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.content, modifier = Modifier.size(24.dp))
        }
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = palette.content,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = if (hovered) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(palette.container, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The open quick menu, filling its window: a light scrim, the slots springing
 * out from the button one after another, and in the button's place a close
 * disc. [anchor] and the slots' [FloatingMenuState.centers] are in the window's
 * own pixels.
 */
@Composable
internal fun FloatingMenuContent(
    state: FloatingMenuState,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
    onClosed: () -> Unit,
) {
    val density = LocalDensity.current
    val opening = state.open
    val scrim = remember { Animatable(0f) }
    // The window composes before it is told to open; only a menu that opened
    // can close, or the first frame would take the window straight back down.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(opening) {
        if (opening) {
            wasOpen = true
            scrim.animateTo(1f, tween(160))
        } else if (wasOpen) {
            scrim.animateTo(0f, tween(140))
            onClosed()
        }
    }
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = scrim.value }
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
    ) {
        val origin = state.windowOrigin
        val anchor = state.anchor - origin
        state.items.forEachIndexed { index, item ->
            val travel = remember(item.key) { Animatable(0f) }
            LaunchedEffect(opening, item.key) {
                if (opening) {
                    kotlinx.coroutines.delay(index * 22L)
                    travel.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f))
                } else {
                    travel.animateTo(0f, tween(120))
                }
            }
            val target = state.centers.getOrNull(index)?.minus(origin) ?: anchor
            val labelHalf = with(density) { (FLOATING_MENU_LABEL_DP.dp / 2).toPx() }
            val itemHalf = with(density) { (FLOATING_MENU_ITEM_DP.dp / 2).toPx() }
            FloatingMenuItem(
                icon = item.icon,
                label = item.label,
                style = state.style,
                showLabel = state.labels,
                hovered = state.hovered == index,
                modifier = Modifier
                    .offset {
                        val p = travel.value
                        IntOffset(
                            (anchor.x + (target.x - anchor.x) * p - labelHalf).roundToInt(),
                            (anchor.y + (target.y - anchor.y) * p - itemHalf).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        val p = travel.value.coerceIn(0f, 1f)
                        alpha = p
                        scaleX = 0.4f + 0.6f * p
                        scaleY = 0.4f + 0.6f * p
                    }
                    .semantics { contentDescription = item.label }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        onPick(index)
                    },
            )
        }
        // Without names under the slots, the one under the finger is named here.
        val hovered = state.items.getOrNull(state.hovered ?: -1)
        if (!state.labels && hovered != null) {
            val palette = floatingPalette(state.style)
            Text(
                hovered.label,
                color = palette.content,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .offset {
                        val gap = with(density) { (state.buttonSizeDp.dp / 2 + 22.dp).toPx() }
                        IntOffset((anchor.x - 60 * density.density).roundToInt(), (anchor.y - gap - 24 * density.density).roundToInt())
                    }
                    .width(120.dp)
                    .wrapContentSize()
                    .background(palette.container, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        val closeRotation by animateFloatAsState(if (opening) 0f else 90f, tween(160), label = "floating-close")
        FloatingButtonFace(
            icon = Icons.Outlined.Close,
            style = state.style,
            sizeDp = state.buttonSizeDp,
            contentDescription = stringResource(R.string.ime_floating_close_menu),
            modifier = Modifier
                .offset {
                    val half = with(density) { (state.buttonSizeDp.dp / 2).toPx() }
                    IntOffset((anchor.x - half).roundToInt(), (anchor.y - half).roundToInt())
                }
                .graphicsLayer { rotationZ = closeRotation }
                .clickable(interactionSource = noRipple, indication = null, onClick = onClose),
        )
    }
}

/**
 * The drop target a drag shows at the bottom of the screen: let go of the
 * button on it and the button is put away. Grows and fills once the button is
 * close enough to snap onto it.
 */
@Composable
internal fun FloatingDismissTarget(state: FloatingDismissState) {
    val shown by animateFloatAsState(if (state.shown) 1f else 0f, tween(150), label = "floating-target")
    val armed by animateFloatAsState(
        if (state.armed) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "floating-armed",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(60.dp)
                .graphicsLayer {
                    alpha = shown
                    val s = (0.6f + 0.4f * shown) * (1f + 0.25f * armed)
                    scaleX = s
                    scaleY = s
                }
                .shadow(6.dp, CircleShape)
                .background(Color(0xFF202124).copy(alpha = 0.92f), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.35f + 0.65f * armed), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_floating_dismiss_target),
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * Look up waiting for its tap (#765): a light wash over the screen, so it is
 * plain that the next tap is not the app's, and a hint at the top saying what
 * it is for.
 */
@Composable
internal fun FloatingPickHint(label: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.08f)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 56.dp)
                .shadow(6.dp, RoundedCornerShape(50))
                .background(Color(0xFF202124).copy(alpha = 0.92f), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** What the open menu shows; written by [FloatingButton], read by [FloatingMenuContent]. */
internal class FloatingMenuState {
    var open by mutableStateOf(false)
    var items by mutableStateOf(emptyList<FloatingMenuSlot>())
    var centers by mutableStateOf(emptyList<Offset>())
    var anchor by mutableStateOf(Offset.Zero)

    /** Where the menu's window starts on screen, so screen points can be drawn in it. */
    var windowOrigin by mutableStateOf(Offset.Zero)
    var hovered by mutableStateOf<Int?>(null)
    var style by mutableStateOf(FloatingButtonStyle.AUTO)
    var labels by mutableStateOf(true)
    var buttonSizeDp by mutableStateOf(52)
}

@Immutable
internal class FloatingMenuSlot(val key: String, val action: FloatingAction, val icon: ImageVector, val label: String)

internal class FloatingDismissState {
    var shown by mutableStateOf(false)
    var armed by mutableStateOf(false)
}

/** What the button draws; written by [FloatingButton]. */
internal class FloatingFaceState {
    var icon by mutableStateOf<ImageVector>(Icons.Outlined.Keyboard)
    var label by mutableStateOf("")
    var style by mutableStateOf(FloatingButtonStyle.AUTO)
    var sizeDp by mutableStateOf(52)
    var pressed by mutableStateOf(false)
}
