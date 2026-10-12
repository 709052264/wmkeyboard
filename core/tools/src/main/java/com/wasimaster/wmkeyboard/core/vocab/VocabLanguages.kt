package com.wasimaster.wmkeyboard.core.vocab

import java.util.Locale

/**
 * How the keyboard's language ids meet the translation sidecars' codes.
 *
 * The sidecars are keyed by Wiktionary's language codes, which mostly match
 * the registry's ids but not always: the registry says `zh`, Wiktionary says
 * `cmn`; a romanised layout is `bn_rom` but reads the `bn` glosses; and a
 * Bokmål or Croatian keyboard is happy with the `no` or `sh` sidecar when a
 * pack has no closer one. This is the one place those rules live, so the
 * card, the packs screen and the auto-download agree on what "your
 * languages" means.
 */
object VocabLanguages {

    /** Registry id → the sidecar codes that serve it, best first. */
    private val PREFERRED: Map<String, List<String>> = mapOf(
        "zh" to listOf("cmn"),
        "yue" to listOf("yue", "cmn"),
        "nb" to listOf("nb", "no"),
        "nn" to listOf("nn", "no"),
        "hr" to listOf("hr", "sh"),
        "sr" to listOf("sr", "sh"),
        "bs" to listOf("bs", "sh"),
        "fil" to listOf("tl"),
    )

    /** English names for the codes the registry has no language for. */
    private val NAMES: Map<String, String> = mapOf(
        "cmn" to "Mandarin Chinese",
        "no" to "Norwegian",
        "nb" to "Norwegian Bokmål",
        "nn" to "Norwegian Nynorsk",
        "ota" to "Ottoman Turkish",
        "sh" to "Serbo-Croatian",
        "grc" to "Ancient Greek",
        "gv" to "Manx",
        "io" to "Ido",
        "oc" to "Occitan",
        "eo" to "Esperanto",
        "la" to "Latin",
        "mi" to "Māori",
        "tl" to "Tagalog",
        "gd" to "Scottish Gaelic",
        "ga" to "Irish",
        "cy" to "Welsh",
        "gl" to "Galician",
        "hy" to "Armenian",
        "ka" to "Georgian",
        "kk" to "Kazakh",
        "be" to "Belarusian",
        "mk" to "Macedonian",
        "az" to "Azerbaijani",
    )

    /** The sidecar codes a keyboard language reads, best first: `bn_rom` → `[bn]`, `hr` → `[hr, sh]`. */
    fun codesFor(languageId: String): List<String> {
        val base = languageId.substringBefore('_').substringBefore('-').lowercase(Locale.ROOT)
        return PREFERRED[base] ?: listOf(base)
    }

    /** Whether [languageId] is a romanised layout, whose readers want the romanisation up front. */
    fun isRomanized(languageId: String): Boolean = languageId.endsWith("_rom")

    /**
     * The codes the user wants translations in: [explicit] when they chose
     * some, else what follows from the keyboard's [enabledLanguageIds] minus
     * the pack's own language. Ordered, deduplicated; fallbacks (`sh` for a
     * Croatian keyboard) come after the codes they stand in for.
     */
    fun wantedCodes(explicit: List<String>, enabledLanguageIds: List<String>, packLang: String = "en"): List<String> {
        // A pick may be a keyboard language id as well as a sidecar code — the
        // picker once listed both — so picks go through the same map.
        val ids = explicit.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { enabledLanguageIds }
        val out = LinkedHashSet<String>()
        val fallbacks = LinkedHashSet<String>()
        for (id in ids) {
            val codes = codesFor(id)
            if (codes.first() != packLang) out += codes.first()
            for (code in codes.drop(1)) if (code != packLang) fallbacks += code
        }
        out += fallbacks
        return out.toList()
    }

    /**
     * The translation codes a record should show: the wanted ones it has,
     * else whatever it has at all — a sidecar the user downloaded by hand is
     * worth showing even when it is not one of "their" languages. Empty only
     * when the record carries no translation.
     */
    fun codesToShow(wanted: List<String>, available: Collection<String>, registryName: (String) -> String? = { null }): List<String> {
        val have = available.toSet()
        val mine = wanted.filter { it in have }
        if (mine.isNotEmpty()) return mine
        return available.distinct().sortedBy { displayName(it, registryName) }
    }

    /**
     * Which of [wanted] a pack that offers [available] should fetch: every
     * wanted code it has, but a fallback only when none of the codes it
     * stands in for is there. Keeps the order of [wanted].
     */
    fun codesToFetch(wanted: List<String>, available: Collection<String>): List<String> {
        val have = available.toSet()
        val out = ArrayList<String>()
        for (code in wanted) {
            if (code !in have) continue
            val standsInFor = PREFERRED.filterValues { code in it.drop(1) }.keys
            if (standsInFor.isNotEmpty() && standsInFor.any { it in out || (it in have && it in wanted) }) continue
            out += code
        }
        return out
    }

    /**
     * The word's meaning in the reader's language on one line, for the
     * surfaces with room for one (the word-of-the-day card, a flashcard's
     * back, the Dictionary panel): the first language [codesToShow] picks
     * that has a gloss, up to [max] glosses, the romanisation leading on a
     * romanised layout. Null when the record carries no translation, so the
     * caller draws exactly what it drew before.
     */
    fun primaryMeaning(word: VocabWord, wanted: List<String>, enabledLanguageIds: List<String>, max: Int = 3): VocabMeaning? {
        for (code in codesToShow(wanted, word.translations.keys)) {
            val glosses = word.translations[code]?.glosses().orEmpty().take(max)
            if (glosses.isEmpty()) continue
            val romanFirst = prefersRomanized(code, enabledLanguageIds)
            val lead = glosses.map { if (romanFirst && it.roman != null) it.roman else it.word }
            val trail = glosses.mapNotNull { if (romanFirst && it.roman != null) it.word else it.roman }
            return VocabMeaning(code, lead.joinToString(", "), trail.joinToString(", ").ifEmpty { null })
        }
        return null
    }

    /** Whether the romanisation should lead for someone typing on any of [enabledLanguageIds]. */
    fun prefersRomanized(code: String, enabledLanguageIds: List<String>): Boolean =
        enabledLanguageIds.any { isRomanized(it) && codesFor(it).first() == code }

    /**
     * An English display name for a sidecar code: the registry's when the
     * caller has one, else the table above, else the platform's, else the
     * code itself.
     */
    fun displayName(code: String, registryName: (String) -> String? = { null }): String {
        registryName(code)?.takeIf { it.isNotBlank() }?.let { return it }
        NAMES[code]?.let { return it }
        val platform = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return if (platform.isBlank() || platform.equals(code, ignoreCase = true)) code else platform
    }
}

/**
 * One line of a word's meaning in another language: [text] leads, [trail]
 * (the romanisation, or the script when the romanisation leads) follows it
 * smaller, when there is one. [code] is the sidecar it came from.
 */
data class VocabMeaning(val code: String, val text: String, val trail: String?)
