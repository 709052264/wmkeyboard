package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The provider each panel's chooser is on, for a list in pick mode: the key
 * of a row of that tool's list, or nothing yet, which is its first row.
 *
 * Process state, not a setting: the choice lasts while the keyboard runs and
 * is the first provider again after a restart, and choosing writes nothing to
 * the settings store (where it would also make the list count as edited).
 * Kept outside [KeyboardUiState] and away from KeyboardScreen's parameters,
 * which both sit against JVM size limits: the chooser reads and writes it
 * directly, and the service hears [changes] to run the panel's work again.
 */
object ProviderPicks {

    private val state = MutableStateFlow<Map<ProviderTool, String>>(emptyMap())
    val picks: StateFlow<Map<ProviderTool, String>> = state.asStateFlow()

    private val changed = MutableSharedFlow<ProviderTool>(extraBufferCapacity = 8)

    /** The tool whose choice just changed. */
    val changes: SharedFlow<ProviderTool> = changed.asSharedFlow()

    /** The chosen row's key, or "" for the first row. Never null: a panel always picks. */
    fun of(tool: ProviderTool): String = state.value[tool].orEmpty()

    fun choose(tool: ProviderTool, key: String) {
        if (state.value[tool] == key) return
        state.update { it + (tool to key) }
        changed.tryEmit(tool)
    }
}
