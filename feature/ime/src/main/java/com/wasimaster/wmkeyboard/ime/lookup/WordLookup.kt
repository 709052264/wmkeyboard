package com.wasimaster.wmkeyboard.ime.lookup

import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.DictionaryLookup
import com.wasimaster.wmkeyboard.core.tools.DictionarySource
import com.wasimaster.wmkeyboard.core.vocab.VocabIndex
import com.wasimaster.wmkeyboard.core.vocab.VocabIndexCache
import com.wasimaster.wmkeyboard.core.vocab.VocabLanguages
import com.wasimaster.wmkeyboard.core.vocab.VocabTranslation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One word looked up the way the Dictionary tool does it: the user's sources
 * in their order, the vocabulary packs' index when that source is on, and
 * the translations the tool shows under an entry (#764). Shared by the
 * keyboard's Dictionary panel and the Look up card (#765), so the two always
 * answer alike.
 */
object WordLookup {

    /** What a look-up came to. */
    sealed interface Outcome {
        /**
         * [entries] from [source], with [translations] for the first entry.
         * [translationsPending]: the source carried none, and Wiktionary
         * (which is on) should be asked for them next ([translationsFor]).
         */
        data class Found(
            val entries: List<DictEntry>,
            val source: DictionarySource,
            val translations: List<Pair<String, VocabTranslation>>,
            val translationsPending: Boolean,
        ) : Outcome

        data object NotFound : Outcome
        data object NotOnDevice : Outcome
        data object Failed : Outcome

        /** Every source is off. */
        data object NoSources : Outcome
    }

    /** The sources to ask, in order. [pick] is the keyboard panel's chooser, for a list in pick mode. */
    fun sources(settings: KeyboardSettings, pick: String? = null): List<DictionarySource> =
        settings.resolveProviders(ProviderTool.DICTIONARY, pick = pick).mapNotNull { DictionarySource.of(it.typeId) }

    /**
     * The languages the Dictionary tool translates a word into (#764): the
     * user's picks, else the keyboard's languages other than English. Empty
     * with translations off.
     */
    fun translationCodes(settings: KeyboardSettings): List<String> {
        val choice = settings.dictionaryTranslations
        if (!choice.enabled) return emptyList()
        return VocabLanguages.wantedCodes(choice.langList, settings.enabledLanguages.map { it.id })
    }

    /**
     * Whether an entry from [answered] should send its word on to Wiktionary
     * for translations: there are languages to ask for, Wiktionary is not
     * the source that already answered (its page carried them), and the user
     * left Wiktionary on in the tool's sources, so the word goes nowhere
     * they did not allow. Never for an imported dictionary's entry: it is
     * often not English, and Wiktionary's translation tables are for English
     * headwords.
     */
    fun asksWiktionaryForTranslations(answered: DictionarySource, codes: List<String>, settings: KeyboardSettings): Boolean {
        if (codes.isEmpty() || answered == DictionarySource.WIKTIONARY || answered == DictionarySource.IMPORTED) return false
        return sources(settings).any { it == DictionarySource.WIKTIONARY }
    }

    /** The codes the vocabulary packs' index is read with. */
    fun indexCodes(settings: KeyboardSettings): List<String> =
        VocabLanguages.wantedCodes(settings.vocabulary.translationLangList, settings.enabledLanguages.map { it.id })

    /**
     * Looks [word] up. [loaded] is an index already in memory (the keyboard's
     * vocabulary tool keeps one); without it the shared copy is read, and
     * only once the user has unlocked the phone ([unlocked]).
     */
    suspend fun lookUp(
        word: String,
        settings: KeyboardSettings,
        filesDir: File,
        unlocked: Boolean,
        loaded: VocabIndex? = null,
        pick: String? = null,
    ): Outcome {
        val sources = sources(settings, pick)
        if (sources.isEmpty()) return Outcome.NoSources
        val translationCodes = translationCodes(settings)
        val index = loaded ?: if (DictionarySource.VOCAB_PACKS in sources && unlocked) {
            withContext(Dispatchers.IO) { VocabIndexCache.get(filesDir, indexCodes(settings)) }.takeUnless { it.isEmpty }
        } else {
            null
        }
        val fetch = DictionaryLookup.fetcher({ index }, translationCodes)
        return when (val result = DictionaryLookup.resolve(word, sources, fetch)) {
            is DictionaryLookup.Result.Found -> found(result.entries, result.source, settings)
            DictionaryLookup.Result.NotFound -> Outcome.NotFound
            DictionaryLookup.Result.NotOnDevice -> Outcome.NotOnDevice
            DictionaryLookup.Result.Failed -> Outcome.Failed
        }
    }

    /** [entries] that are already in hand (an imported dictionary's, from a scan), dressed as a look-up's answer. */
    fun found(entries: List<DictEntry>, source: DictionarySource, settings: KeyboardSettings): Outcome.Found {
        val codes = translationCodes(settings)
        val translations = entries.firstOrNull()?.let { DictionaryLookup.translationsFor(it, codes) }.orEmpty()
        return Outcome.Found(
            entries = entries,
            source = source,
            translations = translations,
            translationsPending = translations.isEmpty() && asksWiktionaryForTranslations(source, codes, settings),
        )
    }

    /** The translations Wiktionary has for [entry], for an [Outcome.Found] with [Outcome.Found.translationsPending]. */
    suspend fun translationsFor(entry: DictEntry, settings: KeyboardSettings): List<Pair<String, VocabTranslation>> {
        val codes = translationCodes(settings)
        val found = withContext(Dispatchers.IO) { DictionaryLookup.translations(entry.word, codes) }
        return DictionaryLookup.translationsFor(entry.copy(translations = found), codes)
    }
}

/** How the keyboard opens the Look up card (#765); the activity lives in `:app`. */
object LookupLaunch {
    const val ACTIVITY = "com.wasimaster.wmkeyboard.app.LookupActivity"

    /** The text the word is in: a tapped node's text, around the tap. */
    const val EXTRA_TEXT = "com.wasimaster.wmkeyboard.lookup.TEXT"

    /** Where in [EXTRA_TEXT] the finger was, or -1 when the tap could not be placed on a character. */
    const val EXTRA_OFFSET = "com.wasimaster.wmkeyboard.lookup.OFFSET"

    /** The app the text is from. */
    const val EXTRA_PACKAGE = "com.wasimaster.wmkeyboard.lookup.PACKAGE"

    /**
     * Screen rectangles of the characters from [EXTRA_RECTS_FROM] on, four
     * ints each (left, top, right, bottom), for the highlight and the popup's
     * place. Absent when the app gave none.
     */
    const val EXTRA_RECTS = "com.wasimaster.wmkeyboard.lookup.RECTS"

    /** The index in [EXTRA_TEXT] of the first character [EXTRA_RECTS] covers. */
    const val EXTRA_RECTS_FROM = "com.wasimaster.wmkeyboard.lookup.RECTS_FROM"

    /** The tapped text's own rectangle, for placing the popup when there are no character rectangles. */
    const val EXTRA_BOUNDS = "com.wasimaster.wmkeyboard.lookup.BOUNDS"

    /** Opened by the floating button with screen reading off: the card says how to turn it on. */
    const val EXTRA_SCREEN_MISSING = "com.wasimaster.wmkeyboard.lookup.SCREEN_MISSING"
}
