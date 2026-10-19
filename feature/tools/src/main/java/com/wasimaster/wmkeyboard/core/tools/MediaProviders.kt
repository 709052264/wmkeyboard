package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.providers.ChainMode
import com.wasimaster.wmkeyboard.core.providers.ProviderEnv
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.providerMode
import com.wasimaster.wmkeyboard.core.settings.resolveProviders

/**
 * The GIF and sticker panels' providers: the user's list for the tool, with
 * the rows that are off or cannot be asked left out, each ready to fetch.
 * Both the service and the panel read this, so the chip row and the fetch
 * cannot disagree about who is asked.
 */
object MediaProviders {

    fun tool(sticker: Boolean): ProviderTool = if (sticker) ProviderTool.STICKER else ProviderTool.GIF

    fun targets(
        settings: KeyboardSettings,
        sticker: Boolean,
        env: ProviderEnv = ProviderEnvironment.current(),
    ): List<MediaTarget> = settings.resolveProviders(tool(sticker), env, grouped = true).mapNotNull { row ->
        target(row)?.copy(backups = row.backups.mapNotNull(::target))
    }

    // Commons with no address of its own is Wikimedia Commons; the client
    // reads a blank endpoint that way.
    private fun target(row: ResolvedProvider): MediaTarget? {
        val source = GifSources.sourceOf(row.typeId) ?: return null
        return MediaTarget(key = row.key, source = source, apiKey = row.apiKey, url = row.url, name = row.customName)
    }

    fun mode(settings: KeyboardSettings, sticker: Boolean, env: ProviderEnv = ProviderEnvironment.current()): ChainMode =
        settings.providerMode(tool(sticker), env)
}
