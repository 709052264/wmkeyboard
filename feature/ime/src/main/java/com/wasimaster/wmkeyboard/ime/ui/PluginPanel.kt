package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.plugins.InstalledPlugin
import com.wasimaster.wmkeyboard.core.plugins.PluginEvent
import com.wasimaster.wmkeyboard.core.plugins.PluginWidget
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.plugins.ui.LocalPluginPanelStyle
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginInputHost
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginPanelStyle
import com.wasimaster.wmkeyboard.core.plugins.ui.PluginWidgetList
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.PluginPanelUi
import com.wasimaster.wmkeyboard.ime.R

/**
 * The Plugins panel: the installed list, and whichever plugin is running.
 *
 * Everything a plugin draws is drawn *here*, by the keyboard, from a plain data
 * description the script handed back. The script never gets a Compose handle, a
 * colour, or a pixel — which is why a plugin cannot make itself look like the
 * keyboard's own UI, cannot draw outside its panel, and cannot cover a system
 * prompt. The header above it always says which plugin is running.
 *
 * Text inputs are the delicate part. There are no `TextField`s anywhere in the
 * IME (they fight the `InputConnection`), so a plugin's input is a tap target
 * that, once focused, makes the keyboard route keystrokes into
 * [KeyboardUiState.pluginInputs] instead of the user's app. The service owns
 * that switch and clears it whenever the panel closes.
 */
@Composable
internal fun PluginPanel(
    state: KeyboardUiState,
    onOpenPlugin: (String) -> Unit,
    onEvent: (PluginEvent) -> Unit,
    onInputFocus: (String?) -> Unit,
    onToolInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPaste: (String) -> Unit,
    onManage: () -> Unit,
) {
    when (val panel = state.plugins) {
        is PluginPanelUi.List -> PluginList(state, panel, onOpenPlugin, onManage)
        is PluginPanelUi.Running -> RunningPlugin(
            state = state,
            panel = panel,
            onEvent = onEvent,
            onInputFocus = onInputFocus,
            onToolInsert = onToolInsert,
            onCopy = onCopy,
            onPaste = onPaste,
        )
    }
}

@Composable
private fun PluginList(
    state: KeyboardUiState,
    panel: PluginPanelUi.List,
    onOpenPlugin: (String) -> Unit,
    onManage: () -> Unit,
) {
    val kb = LocalKbTheme.current
    // The ring walks the cards and ends on Manage.
    PanelFocusTarget(
        panel = PanelMode.PLUGINS,
        region = FocusRegion.RESULTS,
        count = panel.plugins.size + 1,
        columns = 1,
    ) { index ->
        val plugin = panel.plugins.getOrNull(index)
        if (plugin != null) onOpenPlugin(plugin.id) else onManage()
    }
    val focused = state.focusedIndex(FocusRegion.RESULTS)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        panel.notice?.let { notice ->
            Text(
                notice,
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        if (panel.plugins.isEmpty()) {
            // Only when nothing else has already explained the empty list. With
            // the subsystem off, or every plugin switched off, the notice above
            // says why — and "No plugins yet" beside it is simply untrue.
            if (panel.notice == null) {
                Text(
                    stringResource(R.string.ime_plugin_empty_title),
                    color = kb.keyText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.ime_plugin_empty_body),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                )
            }
            ToolPanelChip(
                label = stringResource(R.string.ime_plugin_manage_action),
                modifier = Modifier.focusRing(focused == panel.plugins.size),
                onClick = onManage,
            )
        } else {
            for ((index, plugin) in panel.plugins.withIndex()) {
                PluginRow(plugin, focused = index == focused) { onOpenPlugin(plugin.id) }
            }
            ToolPanelChip(
                label = stringResource(R.string.ime_plugin_manage_action),
                modifier = Modifier.focusRing(focused == panel.plugins.size),
                onClick = onManage,
            )
        }
    }
}

@Composable
private fun PluginRow(plugin: InstalledPlugin, focused: Boolean = false, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            plugin.name,
            color = kb.chipText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Read outside ifBlank: that lambda is not a composable one.
        val versionLabel = stringResource(R.string.ime_plugin_version_label, plugin.version)
        val subtitle = plugin.description.ifBlank { versionLabel }
        Text(
            subtitle,
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The running view's interactive leaves in render order, for the focus ring.
 * Tabs are skipped on purpose: which page is showing lives in composable-local
 * saved state a pure walk cannot see, so ringing into a hidden page would
 * activate controls that are not on screen. Ring coverage inside tabbed
 * plugins is future work; everything else is reachable.
 */
private fun interactiveLeaves(widgets: List<PluginWidget>): List<PluginWidget> = buildList {
    fun walk(list: List<PluginWidget>) {
        for (widget in list) {
            when (widget) {
                is PluginWidget.Column -> walk(widget.children)
                is PluginWidget.Row -> walk(widget.children)
                is PluginWidget.Button -> if (widget.enabled) add(widget)
                is PluginWidget.Toggle, is PluginWidget.Input -> add(widget)
                else -> Unit
            }
        }
    }
    walk(widgets)
}

@Composable
private fun RunningPlugin(
    state: KeyboardUiState,
    panel: PluginPanelUi.Running,
    onEvent: (PluginEvent) -> Unit,
    onInputFocus: (String?) -> Unit,
    onToolInsert: (String) -> Unit,
    onCopy: (String) -> Unit,
    onPaste: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val leaves = interactiveLeaves(panel.ui.root)
    PanelFocusTarget(
        panel = PanelMode.PLUGINS,
        region = FocusRegion.RESULTS,
        count = leaves.size,
        columns = 1,
    ) { index ->
        when (val leaf = leaves.getOrNull(index)) {
            is PluginWidget.Button -> onEvent(PluginEvent.Click(leaf.id))
            is PluginWidget.Toggle -> onEvent(PluginEvent.ToggleChanged(leaf.id, !leaf.checked))
            // Focusing an input flips pluginTypingActive, so the next
            // keystrokes take the existing safe path into the box.
            is PluginWidget.Input -> onInputFocus(leaf.id)
            else -> Unit
        }
    }
    val ringed = state.focusedIndex(FocusRegion.RESULTS)?.let { leaves.getOrNull(it) }
    val style = rememberKeyboardPluginStyle(kb)
    // A box whose keys the focus switch sent to the app's field (#665) draws
    // as unfocused, and pressing it takes them back.
    val caretHandle = LocalCaptureCaret.current
    val reclaim = caretHandle.onFocusSwitch?.takeIf { caretHandle.toApp }
    val host = remember(state, onInputFocus, onPaste, reclaim) { KeyboardInputHost(state, onInputFocus, onPaste, reclaim) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.pluginTypingActive) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                FieldFocusSwitch(caretHandle)
            }
        }
        if (panel.busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = kb.accent,
                trackColor = kb.chip,
            )
        }
        panel.error?.let { message ->
            Text(
                message,
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        val context = LocalContext.current
        for (line in panel.ui.repairs) {
            Text(
                line.resolve(context),
                color = kb.secondaryText,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompositionLocalProvider(LocalPluginPanelStyle provides style) {
                PluginWidgetList(
                    widgets = panel.ui.root,
                    host = host,
                    onEvent = onEvent,
                    onInsert = onToolInsert,
                    onCopy = onCopy,
                    ringed = ringed,
                )
            }
        }
    }
}

/**
 * The shared plugin renderer, dressed as the keyboard. Every piece reads the
 * keyboard theme field the panel read before the renderer moved to
 * core.plugins, and the chip, outline and focus ring are the keyboard's own
 * functions rather than copies of them.
 */
@Composable
private fun rememberKeyboardPluginStyle(kb: KbTheme): PluginPanelStyle = remember(kb) { keyboardPluginStyle(kb) }

/** The style [rememberKeyboardPluginStyle] remembers, built without a composition so a test can check each colour's source. */
internal fun keyboardPluginStyle(kb: KbTheme): PluginPanelStyle =
    PluginPanelStyle(
        text = kb.keyText,
        secondaryText = kb.secondaryText,
        surface = kb.chip,
        onSurface = kb.chipText,
        accent = kb.accent,
        cardShape = kb.cardShape(),
        cardBorder = { modifier, shape -> modifier.chipBorder(kb, shape) },
        ring = { modifier, active -> modifier.focusRing(active) },
        chip = { label, selected, enabled, modifier, onClick ->
            ToolPanelChip(label = label, selected = selected, modifier = modifier, enabled = enabled, onClick = onClick)
        },
    )

/**
 * A plugin's text boxes as the keyboard hosts them: the text is what the service
 * collected, pressing a box hands it the keys, and there is no text field, so
 * [onValueChange] is null.
 */
private class KeyboardInputHost(
    private val state: KeyboardUiState,
    private val focus: (String?) -> Unit,
    private val paste: (String) -> Unit,
    /** The focus switch, while it has the focused box's keys with the app's field (#665). */
    private val reclaim: (() -> Unit)?,
) : PluginInputHost {
    override fun value(id: String): String = state.pluginInputs[id].orEmpty()

    override fun isFocused(id: String): Boolean = state.pluginFocusedInput == id && !state.captureHandedToApp

    override fun onFocus(id: String) {
        if (reclaim != null && state.pluginFocusedInput == id) reclaim.invoke() else focus(id)
    }

    override fun onPaste(id: String) = paste(id)

    override val onValueChange: ((String, String) -> Unit)? = null
}
