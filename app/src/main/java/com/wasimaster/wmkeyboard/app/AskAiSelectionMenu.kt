package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "Ask AI" in other apps' text selection menus (#897) is an activity alias, as
 * "Look up" is, so switching it off takes it out of every menu rather than
 * leaving an entry that does nothing. AskAiActivity itself stays for the
 * floating button.
 *
 * Unlike Look up's, this one follows the settings from the process start: it
 * also goes when the AI tool is turned off, which happens on the Tools screen,
 * in a restored backup or a sync, none of which know about the alias.
 */
internal object AskAiSelectionMenu {
    private const val ALIAS = "com.wasimaster.wmkeyboard.app.AskAiSelectionMenu"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> DebugLog.e("AskAiSelectionMenu", "watch failed", failure) },
    )

    @Volatile
    private var started = false

    fun start(context: Context, repository: SettingsRepository) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            repository.settings
                // Before the first unlock the store reads as the defaults,
                // which would put back an entry the user took out.
                .filter { DirectBoot.isUserUnlocked(app) }
                .map { it.ai.selectionMenu && ToolbarTool.AI in it.enabledTools }
                .distinctUntilChanged()
                .collect { apply(app, it) }
        }
    }

    /** Writes only when the state is wrong, so a process start pays one read. */
    private fun apply(context: Context, enabled: Boolean) {
        val component = ComponentName(context.packageName, ALIAS)
        val pm = context.packageManager
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            if (pm.getComponentEnabledSetting(component) != state) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
