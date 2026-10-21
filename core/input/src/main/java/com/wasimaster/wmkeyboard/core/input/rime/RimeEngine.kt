package com.wasimaster.wmkeyboard.core.input.rime

import android.content.Context
import com.wasimaster.wmkeyboard.core.input.composer.CjkConfig
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngine
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngines
import com.wasimaster.wmkeyboard.core.input.composer.HanVariant
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.script.ComposerType

/**
 * RIME as a [ConversionEngine]: for each Chinese language the user switched
 * to RIME, the built-in conversion composers whose keys a RIME schema reads
 * type through [RimeComposer] instead, once RIME is ready.
 *
 * The keyboard pushes the user's choices in with [configure] from its
 * settings collector, the way it fills [CjkConfig], so [composerFor] answers
 * from fields alone.
 */
object RimeEngine : ConversionEngine {

    override val id: String = "rime"

    /** The languages that type through RIME (`zh`, `yue`). */
    @Volatile
    var languages: Set<String> = emptySet()
        private set

    /** The schema the user picked per method, by [RimeMethod] name. */
    @Volatile
    var schemaChoices: Map<String, String> = emptyMap()
        private set

    private val composers = HashMap<Triple<ComposerType, String, String>, RimeComposer>()

    init {
        ConversionEngines.register(this)
    }

    /**
     * The user's RIME settings. Starts RIME in the background when a language
     * uses it, which on a locked phone only notes that it cannot yet.
     */
    fun configure(context: Context, languages: Set<String>, schemaChoices: Map<String, String>) {
        val changed = languages != this.languages || schemaChoices != this.schemaChoices
        this.languages = languages
        this.schemaChoices = schemaChoices
        if (languages.isNotEmpty()) RimeRuntime.prepare(context)
        if (changed) ConversionEngines.changed()
    }

    override fun composerFor(base: Composer, type: ComposerType, langId: String?): Composer? {
        if (langId == null || langId !in languages || !RimeRuntime.ready) return null
        val method = methodOf(type) ?: return null
        val schema = schemaFor(method) ?: return null
        return synchronized(composers) {
            composers.getOrPut(Triple(type, langId, schema)) {
                RimeComposer(base, schema, method, { optionsFor(langId) }, predictorFor(langId))
            }
        }
    }

    /**
     * The schema [method] types through: the user's pick while it is
     * compiled, else the first compiled schema a catalog pack offers for it.
     * Null when nothing compiled serves it, which leaves that layout on its
     * built-in composer.
     */
    fun schemaFor(method: RimeMethod): String? {
        val compiled = RimeRuntime.schemaIds
        schemaChoices[method.name]?.takeIf { it in compiled }?.let { return it }
        return RimeSchemaCatalog.schemaPacks.firstNotNullOfOrNull { pack ->
            pack.methods[method]?.takeIf { it in compiled }
        }
    }

    /**
     * Who offers 联想 after a commit: the built-in Mandarin predictor for
     * Chinese, whichever method typed it, since it reads the Han text and not
     * the keys. Its word pairs are Mandarin, so Cantonese gets none.
     */
    private fun predictorFor(langId: String): Composer? = if (langId == "zh") PinyinComposer else null

    fun methodOf(type: ComposerType): RimeMethod? = when (type) {
        ComposerType.PINYIN -> RimeMethod.PINYIN
        ComposerType.T9_PINYIN -> RimeMethod.T9_PINYIN
        ComposerType.ZHUYIN -> RimeMethod.ZHUYIN
        ComposerType.CANGJIE -> RimeMethod.CANGJIE
        ComposerType.STROKE -> RimeMethod.STROKE
        ComposerType.JYUTPING -> RimeMethod.JYUTPING
        else -> null
    }

    /**
     * Traditional output and its region, the same settings the built-in
     * composers read, in each schema family's own switch names. RIME's own
     * Pinyin and Zhuyin schemas write Traditional and switch between
     * `zh_hant`, `zh_hans`, `zh_hant_tw` and `zh_hant_hk`; Cangjie and Terra
     * Pinyin have a plain `simplification`; Rime Ice writes Simplified and has
     * `traditionalization`. Cantonese is left Traditional, as its schema and
     * the built-in Jyutping dictionary write it, with its own regional forms
     * when a region is picked.
     */
    internal fun optionsFor(langId: String): RimeOptions {
        val traditional = CjkConfig.traditionalOutput
        val region = HanVariant.region
        val taiwan = region == HanVariant.HanRegion.TAIWAN || region == HanVariant.HanRegion.TAIWAN_WORDING
        val hongKong = region == HanVariant.HanRegion.HONG_KONG
        if (langId == "yue") {
            return RimeOptions(
                mapOf(
                    "noop" to !(traditional && (taiwan || hongKong)),
                    "variants_hk" to (traditional && hongKong),
                    "trad_tw" to (traditional && taiwan),
                    "simplification" to false,
                ),
            )
        }
        return RimeOptions(
            mapOf(
                "zh_hans" to !traditional,
                "zh_hant" to (traditional && !taiwan && !hongKong),
                "zh_hant_tw" to (traditional && taiwan),
                "zh_hant_hk" to (traditional && hongKong),
                "simplification" to !traditional,
                "traditionalization" to traditional,
            ),
        )
    }
}
