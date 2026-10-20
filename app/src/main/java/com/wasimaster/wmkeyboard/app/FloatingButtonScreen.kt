package com.wasimaster.wmkeyboard.app

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.accessibility.ScreenContextService
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.DefaultFloatingMenu
import com.wasimaster.wmkeyboard.core.settings.FloatingAction
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonIdleRange
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonSizeRange
import com.wasimaster.wmkeyboard.core.settings.FloatingButtonStyle
import com.wasimaster.wmkeyboard.core.settings.MAX_FLOATING_MENU_ITEMS
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SummonButtonSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.fitsMenu
import com.wasimaster.wmkeyboard.core.settings.gestureActions
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.readsScreen
import com.wasimaster.wmkeyboard.ime.KeyboardControls
import com.wasimaster.wmkeyboard.ime.floating.FLOATING_MENU_ITEM_DP
import com.wasimaster.wmkeyboard.ime.floating.FLOATING_MENU_LABEL_DP
import com.wasimaster.wmkeyboard.ime.floating.FloatingButtonFace
import com.wasimaster.wmkeyboard.ime.floating.FloatingMenuItem
import com.wasimaster.wmkeyboard.ime.floating.floatingActionIcon
import com.wasimaster.wmkeyboard.ime.floating.floatingActionLabel
import com.wasimaster.wmkeyboard.ime.floating.floatingFan
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The route of Advanced › Floating button (#755). */
internal const val FloatingButtonRoute = "floating_button"

/** Which gesture a picker is choosing for, or the quick menu's add. */
private enum class FloatingPick { TAP, DOUBLE_TAP, HOLD, TAP_TYPING, DOUBLE_TAP_TYPING, MENU_ADD }

/**
 * Advanced › Floating button (#755): the one button over other apps that the
 * keyboard button (#374) and the AI button (#697) became, and everything about
 * it. A live preview heads the page, drawn from the very pieces the button is
 * drawn with, so each change below shows at once.
 */
@Composable
internal fun FloatingButtonSettingsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val button = settings.watch { it.summonButton }
    val overlayGranted = rememberGrantState { Settings.canDrawOverlays(it) }
    val askOverlay = rememberDisclosedSpecialAccess(SpecialAccess.OVERLAY)
    val screenReading = rememberGrantState { ScreenContextService.isEnabled(it) }
    val askScreenReading = rememberDisclosedSpecialAccess(SpecialAccess.SCREEN_CONTENT)
    // The keyboard only re-checks the grant when it next closes; coming back
    // from the system screen with it is worth showing the button at once.
    LaunchedEffect(button.enabled, overlayGranted) {
        KeyboardControls.host?.syncSummonButton()
    }
    var picking by remember { mutableStateOf<FloatingPick?>(null) }
    val defaults = SettingsDefaults.summonButton
    val percentFormat = stringResource(R.string.typing_value_percent)
    val dpFormat = stringResource(R.string.typing_value_dp)
    val menuOpens = FloatingAction.Menu in button.gestureActions()

    SettingsGroup {
        item { FloatingButtonPreview(button) }
        item {
            ToggleSetting(
                R.string.floating_button_enable_title,
                stringResource(R.string.floating_button_enable_subtitle),
                button.enabled,
                info = stringResource(R.string.floating_button_enable_info),
                default = defaults.enabled,
            ) { on ->
                scope.launch { repository.setSummonButton(on) }
                // The disclosure, then Android's own switch. The setting is
                // kept either way, so granting later is all it takes.
                if (on && !overlayGranted) askOverlay()
            }
        }
        item(visible = button.enabled && !overlayGranted) {
            NavRow(
                R.string.layout_summon_button_permission_title,
                subtitle = stringResource(R.string.layout_summon_button_permission_subtitle),
            ) { askOverlay() }
        }
        item(
            visible = button.enabled && overlayGranted && button.readsScreen() &&
                !screenReading && ChannelFeatures.SCREEN_CONTEXT,
        ) {
            NavRow(
                R.string.floating_button_screen_title,
                subtitle = stringResource(R.string.floating_button_screen_subtitle),
            ) { askScreenReading() }
        }
    }

    SettingsGroup(
        stringResource(R.string.floating_button_gestures_group),
        info = stringResource(R.string.floating_button_gestures_info),
    ) {
        item {
            NavRow(R.string.floating_button_tap_title, value = floatingActionLabel(button.tap)) {
                picking = FloatingPick.TAP
            }
        }
        item {
            NavRow(R.string.floating_button_double_tap_title, value = floatingActionLabel(button.doubleTap)) {
                picking = FloatingPick.DOUBLE_TAP
            }
        }
        item {
            NavRow(R.string.floating_button_hold_title, value = floatingActionLabel(button.hold)) {
                picking = FloatingPick.HOLD
            }
        }
    }

    // The same two gestures with the keyboard up (#626): one tap opens the
    // keyboard, the next does something else, such as leaving the field.
    // Only while the button stays on screen over the keyboard.
    SettingsGroup(
        stringResource(R.string.floating_button_typing_group),
        info = stringResource(R.string.floating_button_typing_info),
    ) {
        item(visible = button.whileTyping) {
            NavRow(
                R.string.floating_button_tap_typing_title,
                value = button.tapTyping?.let { floatingActionLabel(it, keyboardUp = true) }
                    ?: stringResource(R.string.floating_button_same_as, stringResource(R.string.floating_button_tap_title)),
            ) { picking = FloatingPick.TAP_TYPING }
        }
        item(visible = button.whileTyping) {
            NavRow(
                R.string.floating_button_double_tap_typing_title,
                value = button.doubleTapTyping?.let { floatingActionLabel(it, keyboardUp = true) }
                    ?: stringResource(
                        R.string.floating_button_same_as,
                        stringResource(R.string.floating_button_double_tap_title),
                    ),
            ) { picking = FloatingPick.DOUBLE_TAP_TYPING }
        }
    }

    SettingsGroup(
        stringResource(R.string.floating_button_menu_group),
        info = stringResource(R.string.floating_button_menu_info),
    ) {
        item(visible = !menuOpens) {
            NavRow(
                R.string.floating_button_menu_unreached_title,
                subtitle = stringResource(R.string.floating_button_menu_unreached_subtitle),
            ) { scope.launch { repository.setFloatingButtonHold(FloatingAction.Menu) } }
        }
        item {
            FloatingMenuEditor(
                items = button.menu,
                onChange = { scope.launch { repository.setFloatingButtonMenu(it) } },
            )
        }
        item {
            val full = button.menu.size >= MAX_FLOATING_MENU_ITEMS
            NavRow(
                R.string.floating_button_menu_add_title,
                subtitle = if (full) {
                    stringResource(R.string.floating_button_menu_full_subtitle)
                } else {
                    stringResource(R.string.floating_button_menu_add_subtitle, MAX_FLOATING_MENU_ITEMS)
                },
            ) { if (!full) picking = FloatingPick.MENU_ADD }
        }
        item {
            ToggleSetting(
                R.string.floating_button_menu_labels_title,
                stringResource(R.string.floating_button_menu_labels_subtitle),
                button.menuLabels,
                default = defaults.menuLabels,
            ) { scope.launch { repository.setFloatingButtonMenuLabels(it) } }
        }
        item(visible = button.menu != DefaultFloatingMenu) {
            NavRow(
                R.string.floating_button_menu_reset_title,
                subtitle = stringResource(R.string.floating_button_menu_reset_subtitle),
            ) { scope.launch { repository.resetFloatingButtonMenu() } }
        }
    }

    SettingsGroup(stringResource(R.string.floating_button_look_group)) {
        item {
            ChoiceSetting(
                title = R.string.floating_button_style_title,
                options = FloatingButtonStyle.entries.map { it to stringResource(styleLabel(it)) },
                selected = button.style,
                default = defaults.style,
            ) { scope.launch { repository.setFloatingButtonStyle(it) } }
        }
        item {
            SliderSetting(
                R.string.floating_button_size_title,
                subtitle = stringResource(R.string.floating_button_size_subtitle),
                value = button.sizeDp.toFloat(),
                range = FloatingButtonSizeRange.first.toFloat()..FloatingButtonSizeRange.last.toFloat(),
                display = { dpFormat.format(it.roundToInt()) },
                default = defaults.sizeDp.toFloat(),
            ) { scope.launch { repository.setFloatingButtonSize(it.roundToInt()) } }
        }
        item {
            SliderSetting(
                R.string.floating_button_opacity_title,
                subtitle = stringResource(R.string.floating_button_opacity_subtitle),
                value = button.opacity,
                range = 0.3f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                default = defaults.opacity,
            ) { scope.launch { repository.setFloatingButtonOpacity(it) } }
        }
        item {
            val never = stringResource(R.string.floating_button_idle_never)
            val seconds = stringResource(R.string.floating_button_idle_seconds)
            SliderSetting(
                R.string.floating_button_idle_title,
                subtitle = stringResource(R.string.floating_button_idle_subtitle),
                value = button.idleFadeSeconds.toFloat(),
                range = FloatingButtonIdleRange.first.toFloat()..FloatingButtonIdleRange.last.toFloat(),
                display = { it.roundToInt().let { s -> if (s == 0) never else seconds.format(s) } },
                default = defaults.idleFadeSeconds.toFloat(),
                typed = false,
            ) { scope.launch { repository.setFloatingButtonIdleFade(it.roundToInt()) } }
        }
        item(visible = button.idleFadeSeconds > 0) {
            SliderSetting(
                R.string.floating_button_idle_opacity_title,
                subtitle = stringResource(R.string.floating_button_idle_opacity_subtitle),
                value = button.idleOpacity,
                range = 0.15f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                default = defaults.idleOpacity,
            ) { scope.launch { repository.setFloatingButtonIdleOpacity(it) } }
        }
        item(visible = button.idleFadeSeconds > 0) {
            ToggleSetting(
                R.string.floating_button_tuck_title,
                stringResource(R.string.floating_button_tuck_subtitle),
                button.tuck,
                default = defaults.tuck,
            ) { scope.launch { repository.setFloatingButtonTuck(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.floating_button_behaviour_group)) {
        item {
            ToggleSetting(
                R.string.floating_button_while_typing_title,
                stringResource(R.string.floating_button_while_typing_subtitle),
                button.whileTyping,
                default = defaults.whileTyping,
            ) { scope.launch { repository.setFloatingButtonWhileTyping(it) } }
        }
        item {
            ToggleSetting(
                R.string.floating_button_landscape_title,
                stringResource(R.string.floating_button_landscape_subtitle),
                button.hideInLandscape,
                default = defaults.hideInLandscape,
            ) { scope.launch { repository.setFloatingButtonHideInLandscape(it) } }
        }
        item {
            ToggleSetting(
                R.string.floating_button_haptics_title,
                stringResource(R.string.floating_button_haptics_subtitle),
                button.haptics,
                default = defaults.haptics,
            ) { scope.launch { repository.setFloatingButtonHaptics(it) } }
        }
        item(visible = button.xFraction != defaults.xFraction || button.yFraction != defaults.yFraction) {
            NavRow(
                R.string.floating_button_reset_position_title,
                subtitle = stringResource(R.string.floating_button_reset_position_subtitle),
            ) { scope.launch { repository.resetSummonButtonPosition() } }
        }
    }

    picking?.let { pick ->
        val done = { picking = null }
        val gestureTitle = when (pick) {
            FloatingPick.TAP -> R.string.floating_button_tap_title
            FloatingPick.DOUBLE_TAP -> R.string.floating_button_double_tap_title
            FloatingPick.HOLD -> R.string.floating_button_hold_title
            FloatingPick.TAP_TYPING -> R.string.floating_button_tap_typing_title
            FloatingPick.DOUBLE_TAP_TYPING -> R.string.floating_button_double_tap_typing_title
            FloatingPick.MENU_ADD -> 0
        }
        FloatingActionPicker(
            title = if (pick == FloatingPick.MENU_ADD) {
                stringResource(R.string.floating_button_menu_add_pick_title)
            } else {
                stringResource(R.string.floating_button_pick_title, stringResource(gestureTitle))
            },
            current = when (pick) {
                FloatingPick.TAP -> button.tap
                FloatingPick.DOUBLE_TAP -> button.doubleTap
                FloatingPick.HOLD -> button.hold
                FloatingPick.TAP_TYPING -> button.tapTyping
                FloatingPick.DOUBLE_TAP_TYPING -> button.doubleTapTyping
                FloatingPick.MENU_ADD -> null
            },
            sameAs = when (pick) {
                FloatingPick.TAP_TYPING -> R.string.floating_button_tap_title
                FloatingPick.DOUBLE_TAP_TYPING -> R.string.floating_button_double_tap_title
                else -> null
            },
            keyboardUp = pick == FloatingPick.TAP_TYPING || pick == FloatingPick.DOUBLE_TAP_TYPING,
            forMenu = pick == FloatingPick.MENU_ADD,
            already = if (pick == FloatingPick.MENU_ADD) button.menu.toSet() else emptySet(),
            onDismiss = done,
            onPick = { action ->
                done()
                scope.launch {
                    // Only the keyboard-up gestures offer "Same as", the one null.
                    when {
                        pick == FloatingPick.TAP_TYPING -> repository.setFloatingButtonTapTyping(action)
                        pick == FloatingPick.DOUBLE_TAP_TYPING -> repository.setFloatingButtonDoubleTapTyping(action)
                        action == null -> Unit
                        pick == FloatingPick.TAP -> repository.setFloatingButtonTap(action)
                        pick == FloatingPick.DOUBLE_TAP -> repository.setFloatingButtonDoubleTap(action)
                        pick == FloatingPick.HOLD -> repository.setFloatingButtonHold(action)
                        else -> repository.setFloatingButtonMenu(button.menu + action)
                    }
                }
            },
        )
    }
}

private fun styleLabel(style: FloatingButtonStyle): Int = when (style) {
    FloatingButtonStyle.AUTO -> R.string.floating_button_style_auto
    FloatingButtonStyle.DARK -> R.string.floating_button_style_dark
    FloatingButtonStyle.LIGHT -> R.string.floating_button_style_light
    FloatingButtonStyle.ACCENT -> R.string.floating_button_style_accent
}

/**
 * The page's preview: a sketch of a phone screen with the button on the side
 * it rests on and its quick menu fanned out, at the size, colours and opacity
 * set below, so a change is seen without leaving the page.
 */
@Composable
private fun FloatingButtonPreview(button: SummonButtonSettings) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val slots = button.menu.takeIf { FloatingAction.Menu in button.gestureActions() }
        .orEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(if (button.menuLabels) 300.dp else 260.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceContainerHighest)
                .alpha(if (button.enabled) 1f else 0.55f),
        ) {
            // Lines standing in for an app's content.
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0.7f, 0.9f, 0.5f, 0.8f, 0.6f, 0.85f).forEach { width ->
                    Box(
                        Modifier
                            .fillMaxWidth(width)
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.onSurface.copy(alpha = 0.08f)),
                    )
                }
            }
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val disc = button.sizeDp
            val onLeft = button.xFraction < 0.5f
            val edge = with(density) { 10.dp.toPx() }
            val half = with(density) { (disc.dp / 2).toPx() }
            val anchor = Offset(
                if (onLeft) edge + half else widthPx - edge - half,
                heightPx * 0.55f,
            )
            val centers = remember(slots.size, widthPx, heightPx, anchor, button.menuLabels, disc) {
                with(density) {
                    floatingFan(
                        count = slots.size,
                        anchor = anchor,
                        width = widthPx,
                        height = heightPx,
                        radius = (disc.dp / 2 + 22.dp + (FLOATING_MENU_ITEM_DP.dp / 2)).toPx(),
                        spacing = (if (button.menuLabels) 86.dp else 64.dp).toPx(),
                        margin = (FLOATING_MENU_ITEM_DP.dp / 2 + 8.dp + if (button.menuLabels) 18.dp else 0.dp).toPx(),
                    )
                }
            }
            val labelHalf = with(density) { (FLOATING_MENU_LABEL_DP.dp / 2).toPx() }
            val itemHalf = with(density) { (FLOATING_MENU_ITEM_DP.dp / 2).toPx() }
            slots.forEachIndexed { i, action ->
                val center = centers.getOrNull(i) ?: return@forEachIndexed
                FloatingMenuItem(
                    icon = floatingActionIcon(action),
                    label = floatingActionLabel(action),
                    style = button.style,
                    showLabel = button.menuLabels,
                    hovered = i == 0,
                    modifier = Modifier.offset {
                        IntOffset((center.x - labelHalf).roundToInt(), (center.y - itemHalf).roundToInt())
                    },
                )
            }
            FloatingButtonFace(
                icon = floatingActionIcon(button.tap),
                style = button.style,
                sizeDp = disc,
                modifier = Modifier
                    .offset { IntOffset((anchor.x - half).roundToInt(), (anchor.y - half).roundToInt()) }
                    .alpha(button.opacity),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (button.enabled) {
                stringResource(
                    R.string.floating_button_preview_caption,
                    floatingActionLabel(button.tap),
                    floatingActionLabel(button.hold),
                )
            } else {
                stringResource(R.string.floating_button_preview_off)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The quick menu's slots, to reorder by their handles and remove by their bins. */
@Composable
private fun FloatingMenuEditor(items: List<FloatingAction>, onChange: (List<FloatingAction>) -> Unit) {
    if (items.isEmpty()) {
        Text(
            stringResource(R.string.floating_button_menu_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        return
    }
    val names = items.associateWith { floatingActionLabel(it) }
    ReorderableColumn(
        items = items,
        label = { names[it].orEmpty() },
        onReorder = onChange,
        onDelete = { gone -> onChange(items - gone) },
        keepLast = false,
    ) { action ->
        Icon(
            floatingActionIcon(action),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(names[action].orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/**
 * Picks what a gesture does, or what the quick menu gains: the button's own
 * actions first, then every tool, grouped as the Tools screen groups them.
 * [forMenu] leaves out what a menu slot cannot be ([fitsMenu]) and what the
 * menu [already] holds. With [sameAs], a keyboard-up gesture's picker (#626),
 * the first row hands the gesture back to the one it names, as null.
 */
@Composable
private fun FloatingActionPicker(
    title: String,
    current: FloatingAction?,
    forMenu: Boolean,
    already: Set<FloatingAction>,
    onDismiss: () -> Unit,
    onPick: (FloatingAction?) -> Unit,
    @StringRes sameAs: Int? = null,
    keyboardUp: Boolean = false,
) {
    fun offered(action: FloatingAction) = (!forMenu || action.fitsMenu()) && action !in already
    val own = listOf(
        FloatingAction.Keyboard to R.string.floating_button_pick_keyboard_subtitle,
        FloatingAction.AskAi to R.string.floating_button_pick_ai_subtitle,
        FloatingAction.LookUp to R.string.floating_button_pick_lookup_subtitle,
        FloatingAction.Menu to R.string.floating_button_pick_menu_subtitle,
        FloatingAction.Hide to R.string.floating_button_pick_hide_subtitle,
        FloatingAction.None to R.string.floating_button_pick_none_subtitle,
    ).filter { (action, _) -> offered(action) }
        // Look up reads the screen, which the Play build cannot (#765).
        .filter { (action, _) -> action != FloatingAction.LookUp || ChannelFeatures.SCREEN_CONTEXT }
    val sameRow = sameAs?.let { gesture ->
        PickerRow(
            key = "same",
            title = stringResource(R.string.floating_button_same_as, stringResource(gesture)),
            subtitle = stringResource(R.string.floating_button_pick_same_subtitle),
            selected = current == null,
            onPick = { onPick(null) },
        )
    }
    val ownRows = listOfNotNull(sameRow) + own.map { (action, subtitle) ->
        PickerRow(
            key = action.token,
            title = floatingActionLabel(action, keyboardUp),
            subtitle = stringResource(subtitle),
            selected = current == action,
            leading = { Icon(floatingActionIcon(action), contentDescription = null) },
            onPick = { onPick(action) },
        )
    }
    val supported = ToolbarTool.entries.filter { isSupportedTool(it) && offered(FloatingAction.Tool(it)) }.toSet()
    val grouped = ToolGroups.map { (titleRes, tools) -> titleRes to tools.filter { it in supported } }
    val placed = grouped.flatMapTo(HashSet()) { it.second }
    val leftovers = supported.filter { it !in placed }
    @Composable
    fun toolRow(tool: ToolbarTool) = PickerRow(
        key = tool.name,
        title = stringResource(toolTitle(tool)),
        subtitle = stringResource(toolDescription(tool)),
        selected = current == FloatingAction.Tool(tool),
        leading = { SlotIcon(IconSlots.forTool(tool), contentDescription = null) },
        onPick = { onPick(FloatingAction.Tool(tool)) },
    )
    val groups = buildList {
        if (ownRows.isNotEmpty()) {
            add(PickerGroup("own", stringResource(R.string.floating_button_pick_group_button), ownRows))
        }
        for ((titleRes, tools) in grouped) {
            if (tools.isEmpty()) continue
            add(PickerGroup("group-$titleRes", stringResource(titleRes), tools.map { toolRow(it) }))
        }
        if (leftovers.isNotEmpty()) {
            add(PickerGroup("group-other", stringResource(R.string.tools_group_other_title), leftovers.map { toolRow(it) }))
        }
    }
    ActionPickerPage(
        memoryKey = if (forMenu) "floating-menu" else "floating-gesture",
        title = title,
        groups = groups,
        onDismiss = onDismiss,
    )
}
