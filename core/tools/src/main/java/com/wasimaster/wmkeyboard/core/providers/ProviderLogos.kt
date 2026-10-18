package com.wasimaster.wmkeyboard.core.providers

import androidx.annotation.DrawableRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * A provider's mark, as a small vector in this module's `res/drawable`.
 *
 * A [monochrome] mark is drawn in one colour that the caller tints: the
 * settings tile takes [color] for it, a keyboard chip its text colour. The
 * rest keep their own colours. [color] is ARGB.
 *
 * Sources: Simple Icons (CC0) where it has the brand, else the project's own
 * logo from its repository or site. Kinds with no vector logo anywhere
 * (Translite, Rens, Datamuse, the currency APIs, ML Kit) keep the glyph for
 * what they are.
 */
data class ProviderLogo(@DrawableRes val res: Int, val color: Long, val monochrome: Boolean)

object ProviderLogos {

    private fun mark(@DrawableRes res: Int, color: Long) = ProviderLogo(res, color, monochrome = true)
    private fun picture(@DrawableRes res: Int) = ProviderLogo(res, 0xFF9E9E9E, monochrome = false)

    private val wiktionary = mark(R.drawable.core_tools_logo_wiktionary, 0xFF202122)

    private val byId: Map<String, ProviderLogo> = mapOf(
        ProviderTypes.KLIPY.id to picture(R.drawable.core_tools_logo_klipy),
        ProviderTypes.GIPHY.id to mark(R.drawable.core_tools_logo_giphy, 0xFFFF6666),
        ProviderTypes.COMMONS.id to mark(R.drawable.core_tools_logo_commons, 0xFF006699),
        ProviderTypes.GIFCITIES.id to mark(R.drawable.core_tools_logo_gifcities, 0xFF666666),
        ProviderTypes.DEEPL.id to mark(R.drawable.core_tools_logo_deepl, 0xFF0F2B46),
        ProviderTypes.LIBRETRANSLATE.id to mark(R.drawable.core_tools_logo_libretranslate, 0xFF1565C0),
        ProviderTypes.MOZHI.id to picture(R.drawable.core_tools_logo_mozhi),
        ProviderTypes.SIMPLYTRANSLATE.id to picture(R.drawable.core_tools_logo_simplytranslate),
        ProviderTypes.LINGVA.id to picture(R.drawable.core_tools_logo_lingva),
        ProviderTypes.GOOGLE_TRANSLATE.id to mark(R.drawable.core_tools_logo_google_translate, 0xFF4285F4),
        ProviderTypes.WIKTIONARY.id to wiktionary,
        ProviderTypes.WIKTIONARY_REST.id to wiktionary,
        ProviderTypes.OFFLINE_WIKTIONARY.id to wiktionary,
        ProviderTypes.SEARXNG.id to mark(R.drawable.core_tools_logo_searxng, 0xFF3050FF),
        ProviderTypes.TAVILY.id to picture(R.drawable.core_tools_logo_tavily),
        ProviderTypes.BRAVE.id to mark(R.drawable.core_tools_logo_brave, 0xFFFB542B),
        ProviderTypes.UNSPLASH.id to mark(R.drawable.core_tools_logo_unsplash, 0xFF000000),
        ProviderTypes.PEXELS.id to mark(R.drawable.core_tools_logo_pexels, 0xFF05A081),
        ProviderTypes.FRANKFURTER.id to mark(R.drawable.core_tools_logo_frankfurter, 0xFF1A1A1A),
        ProviderTypes.COINBASE.id to mark(R.drawable.core_tools_logo_coinbase, 0xFF0052FF),
        ProviderTypes.COINGECKO.id to picture(R.drawable.core_tools_logo_coingecko),
    )

    fun of(typeId: String): ProviderLogo? = byId[typeId]
}
