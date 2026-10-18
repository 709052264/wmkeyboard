package com.wasimaster.wmkeyboard.core.plugins

/**
 * What a plugin draws before anyone touches it, for a picture of it in a list.
 *
 * The script runs in the same sandbox, with the same prelude and the same load
 * and render budgets, as it would in the keyboard, but on the calling thread
 * and once: loaded, then drawn. Storage and reminders, when the plugin declares
 * them, live in memory and are gone afterwards. Nothing is logged and nothing is kept. Meant
 * for scripts that ship with the app; an unknown script belongs in
 * [PluginRuntime], whose watchdog can abandon a thread that stops counting.
 */
object PluginThumbnail {

    /** The widgets [script] draws first, or null when it fails to load, has no render, or runs out of budget. */
    fun render(plugin: InstalledPlugin, script: String): RenderedUi? = runCatching {
        val budget = PluginBudget()
        val globals = PluginSandbox.create(budget) { }
        val storage = if (PluginPermission.Storage.wire in plugin.permissions) PluginStorage(null) else null
        val reminders = if (PluginPermission.Reminders.wire in plugin.permissions) PluginReminders(null) else null
        val api = PluginHostApi(
            plugin = plugin,
            log = PluginLog(null),
            storage = storage,
            setInput = { _, _ -> },
            revoked = { false },
            reminders = reminders,
        )
        globals.set("wm", api.table())
        budget.begin(PluginLimit.LOAD)
        PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
        PluginSandbox.compile(globals, script).call()
        budget.begin(PluginLimit.RENDER)
        val render = globals.get("render")
        if (!render.isfunction()) return null
        PluginUiCodec.fromLua(render.call())
    }.getOrNull()
}
