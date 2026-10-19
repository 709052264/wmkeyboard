package com.wasimaster.wmkeyboard.core.script

/**
 * Why a language was suggested. The UI turns this into a one-line reason, so
 * a suggestion never looks like it came out of nowhere.
 */
enum class SuggestionReason {
    /** It is one of the languages the phone itself is set to. */
    SYSTEM_LANGUAGE,

    /** It is widely typed where the phone is (SIM country, or the locale's region). */
    REGION,

    /** Nothing on the device said anything useful, so this is the safe default. */
    FALLBACK,
}

/**
 * A language WM Keyboard thinks the user probably wants, with the signal that
 * put it there. [regionCode] is set only for [SuggestionReason.REGION], and is
 * an ISO-3166 alpha-2 code the caller can turn into a country name.
 */
data class SuggestedLanguage(
    val language: LanguageDef,
    val reason: SuggestionReason,
    val regionCode: String? = null,
)

/**
 * What the device told us about where it is and what it speaks.
 *
 * Deliberately a plain data class of strings rather than Android types, so the
 * ranking below is pure and testable. `DeviceLanguageSignals.read(context)`
 * (in the Android source set) is what actually fills it in.
 *
 * @param systemLocales BCP-47 tags from the phone's language list, most
 *   preferred first.
 * @param regionCodes ISO-3166 alpha-2 codes, most trustworthy first — the SIM's
 *   country, then the phone's time zone, then the locale's region, which is
 *   often just whatever shipped with the ROM. Only the first one that has
 *   anything to offer is used; see [LanguageSuggestions.suggest].
 */
data class DeviceLanguageSignals(
    val systemLocales: List<String> = emptyList(),
    val regionCodes: List<String> = emptyList(),
)

/**
 * Turns what the device knows about itself into a ranked list of languages to
 * offer, the way Gboard's "suggested" languages work.
 *
 * The only signals used are ones already on the device and free of permissions:
 * the phone's own language list, and its region. Nothing is read from what the
 * user has typed, and nothing leaves the device.
 */
object LanguageSuggestions {

    /** How many suggestions the pickers show before falling back to search. */
    const val DEFAULT_LIMIT = 6

    /**
     * How many layouts a fresh install starts with. Enough for a bilingual
     * phone plus English, without handing someone a switch cycle they have to
     * prune before they can type.
     */
    private const val SEED_LIMIT = 4

    /**
     * Ranks languages to offer, best first, skipping anything in [exclude]
     * (normally the languages already enabled).
     *
     * Order is signal strength: every system language first, in the phone's own
     * preference order, then the region's languages. A language the user has
     * actually chosen in Android beats one merely common where they are.
     *
     * Only *one* region is ever read — the first in [DeviceLanguageSignals]
     * that has something to offer. The regions disagree more often than they
     * agree: a phone set to English (United Kingdom) sitting in Dhaka carries
     * both BD and GB, and reading both buried Bengali under Welsh, Irish and
     * Scottish Gaelic. The SIM and the time zone say where the phone is; the
     * locale's region frequently says only which ROM it shipped with.
     */
    fun suggest(
        signals: DeviceLanguageSignals,
        exclude: Set<String> = emptySet(),
        limit: Int = DEFAULT_LIMIT,
    ): List<SuggestedLanguage> {
        val seen = HashSet(exclude)
        val out = ArrayList<SuggestedLanguage>(limit)

        // A language with no layout can't be enabled, so it can't be suggested
        // either — which also keeps a typo in the region table below invisible
        // rather than offering an "Unknown" row.
        fun add(language: LanguageDef?, reason: SuggestionReason, region: String? = null) {
            if (language == null || language.layoutIds.isEmpty()) return
            if (out.size < limit && seen.add(language.id)) {
                out += SuggestedLanguage(language, reason, region)
            }
            // Someone who wants Bengali on a phone very often wants Banglish
            // too, and would otherwise have to know the word to search for it.
            // Straight after its own language, so the pair reads as a pair —
            // and offered even when the language itself was skipped, since
            // somebody already typing Bengali is exactly who wants Banglish.
            val romanized = romanizedOf(language.id) ?: return
            if (out.size < limit && seen.add(romanized.id)) {
                out += SuggestedLanguage(romanized, reason, region)
            }
        }

        for (tag in signals.systemLocales) {
            add(LanguageRegistry.byLocale(tag), SuggestionReason.SYSTEM_LANGUAGE)
        }
        for (region in signals.regionCodes) {
            val code = region.uppercase()
            val before = out.size
            for (id in REGION_LANGUAGES[code].orEmpty()) {
                add(LanguageRegistry.byId(id), SuggestionReason.REGION, code)
            }
            if (out.size > before) break
        }
        // Never hand back an empty list: a picker with no suggestions is worse
        // than one suggesting the language every layout can fall back to.
        if (out.isEmpty() && ENGLISH_ID !in exclude) {
            out += SuggestedLanguage(LanguageRegistry.byId(ENGLISH_ID), SuggestionReason.FALLBACK)
        }
        return out
    }

    /**
     * The layout ids a fresh install should start with, derived from the phone's
     * own language list.
     *
     * Only system languages seed — being in a country is a reason to *offer* a
     * language, not to switch someone's keyboard to it. Each language joins by
     * its first layout; the rest are one tap away in Settings → Languages.
     *
     * English is always included. Partly because it is the fallback for fields
     * that force ASCII (passwords, URLs, `textEmailAddress`), and partly because
     * a phone set to one language is still, overwhelmingly, a phone that types
     * some English. It goes last unless the device actually asked for it.
     */
    fun seedLayoutIds(
        signals: DeviceLanguageSignals,
        limit: Int = SEED_LIMIT,
    ): List<String> {
        val languages = LinkedHashSet<String>()
        for (tag in signals.systemLocales) {
            val language = LanguageRegistry.byLocale(tag)
            if (language != null && language.layoutIds.isNotEmpty()) languages += language.id
            if (languages.size >= limit) break
        }
        languages += ENGLISH_ID
        return languages
            .take(limit)
            .mapNotNull { LanguageRegistry.byId(it).layoutIds.firstOrNull() }
            .distinct()
    }

    /**
     * The "type this language in Latin letters" variant of [id] — Banglish for
     * Bengali, Hinglish for Hindi — or null where there is none.
     *
     * Keyed on the id suffix rather than a table: the registry names every one
     * of them `<language>_rom`, and a new one should not need a second edit
     * here to be suggested.
     */
    private fun romanizedOf(id: String): LanguageDef? {
        if (id.endsWith(ROMANIZED_SUFFIX)) return null
        val romanized = LanguageRegistry.byId(id + ROMANIZED_SUFFIX)
        // byId never throws: an id it does not know comes back as GENERIC.
        return romanized.takeIf { it.id != LanguageRegistry.GENERIC.id && it.layoutIds.isNotEmpty() }
    }

    private const val ENGLISH_ID = "en"

    private const val ROMANIZED_SUFFIX = "_rom"
}

/**
 * Languages widely typed in a given country, most common first.
 *
 * English is left out throughout — it reaches the list through the phone's own
 * language settings, or through the fallback, and repeating it in 90 rows would
 * only crowd out the language someone actually came looking for. Entries are
 * language ids in [LanguageRegistry]; a country with nothing to add beyond
 * English simply isn't listed.
 */
internal val REGION_LANGUAGES: Map<String, List<String>> = mapOf(
    "AD" to listOf("ca"),
    "AE" to listOf("ar", "hi", "ur", "ml"),
    "AF" to listOf("fa", "ps"),
    "AL" to listOf("sq"),
    "AM" to listOf("hy", "ru"),
    "AO" to listOf("pt"),
    "AR" to listOf("es"),
    "AT" to listOf("de"),
    "AZ" to listOf("az", "ru"),
    "BA" to listOf("bs", "hr", "sr"),
    "BD" to listOf("bn", "syl"),
    "BE" to listOf("nl", "fr", "de"),
    "BF" to listOf("fr", "mos"),
    "BG" to listOf("bg", "tr"),
    "BH" to listOf("ar"),
    "BI" to listOf("rw", "fr"),
    "BJ" to listOf("fr", "fon"),
    "BO" to listOf("es", "qu", "ay"),
    "BR" to listOf("pt"),
    "BT" to listOf("dz"),
    "BW" to listOf("tn"),
    "BY" to listOf("be", "ru"),
    "CA" to listOf("fr", "iu"),
    "CD" to listOf("fr", "ln", "sw"),
    "CF" to listOf("fr", "sg"),
    "CG" to listOf("fr", "ln"),
    "CH" to listOf("de", "gsw", "fr", "it", "rm"),
    "CI" to listOf("fr"),
    "CL" to listOf("es"),
    "CM" to listOf("fr"),
    "CN" to listOf("zh", "yue", "bo", "ug", "mn", "za"),
    "CO" to listOf("es"),
    "CR" to listOf("es"),
    "CU" to listOf("es"),
    "CY" to listOf("el", "tr"),
    "CZ" to listOf("cs", "sk"),
    "DE" to listOf("de", "tr", "ru", "nds", "hsb"),
    "DJ" to listOf("ar", "so", "fr"),
    "DK" to listOf("da", "fo", "kl"),
    "DO" to listOf("es"),
    "DZ" to listOf("ar", "kab", "fr"),
    "EC" to listOf("es", "qu"),
    "EE" to listOf("et", "ru"),
    "EG" to listOf("arz", "ar"),
    "ES" to listOf("es", "ca", "gl", "eu", "oc", "ast", "an"),
    "ET" to listOf("am", "om", "ti", "so"),
    "FI" to listOf("fi", "sv", "se"),
    "FJ" to listOf("fj", "hi"),
    "FO" to listOf("fo", "da"),
    "FR" to listOf("fr", "oc", "br", "co", "eu", "ca"),
    "GA" to listOf("fr"),
    "GB" to listOf("cy", "gd", "ga", "gv", "kw"),
    "GE" to listOf("ka", "ab", "os", "hy", "ru"),
    "GH" to listOf("ak", "ee", "gur", "dag", "fat"),
    "GL" to listOf("kl", "da"),
    "GM" to listOf("wo", "ff"),
    "GN" to listOf("fr", "ff"),
    "GR" to listOf("el"),
    "GT" to listOf("es"),
    "HK" to listOf("yue", "zh"),
    "HN" to listOf("es"),
    "HR" to listOf("hr"),
    "HT" to listOf("ht", "fr"),
    "HU" to listOf("hu"),
    "ID" to listOf("id", "jv", "su", "min", "ban", "bug", "ace", "bjn", "mad"),
    "IE" to listOf("ga"),
    "IL" to listOf("he", "ar", "ru", "yi"),
    "IN" to listOf(
        "hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa", "or", "as", "ur",
        "sa", "ne", "mai", "bho", "sat", "kok", "doi", "brx", "mni", "ks",
    ),
    "IQ" to listOf("ar", "ckb", "syc"),
    "IR" to listOf("fa", "azb", "ckb", "glk", "mzn", "ar"),
    "IS" to listOf("is"),
    "IT" to listOf("it", "scn", "vec", "nap", "lij", "fur", "sc", "lld", "de"),
    "JM" to listOf("jam"),
    "JO" to listOf("ar"),
    "JP" to listOf("ja"),
    "KE" to listOf("sw", "ki", "so"),
    "KG" to listOf("ky", "ru"),
    "KH" to listOf("km"),
    "KR" to listOf("ko"),
    "KW" to listOf("ar"),
    "KZ" to listOf("kk", "ru"),
    "LA" to listOf("lo"),
    "LB" to listOf("ar", "hy", "fr"),
    "LI" to listOf("de"),
    "LK" to listOf("si", "ta"),
    "LS" to listOf("st"),
    "LT" to listOf("lt", "ru", "sgs"),
    "LU" to listOf("lb", "fr", "de"),
    "LV" to listOf("lv", "ltg", "ru"),
    "LY" to listOf("ar"),
    "MA" to listOf("ary", "zgh", "shi", "fr", "ar"),
    "MD" to listOf("ro", "ru"),
    "ME" to listOf("sr"),
    "MG" to listOf("mg", "fr"),
    "MK" to listOf("mk", "sq"),
    "ML" to listOf("fr", "bm"),
    "MM" to listOf("my", "shn", "mnw", "rki", "blk"),
    "MN" to listOf("mn", "kk"),
    "MO" to listOf("yue", "zh", "pt"),
    "MR" to listOf("ar", "ff"),
    "MT" to listOf("mt"),
    "MV" to listOf("dv"),
    "MW" to listOf("ny"),
    "MX" to listOf("es", "nah"),
    "MY" to listOf("ms", "zh", "ta", "iba"),
    "MZ" to listOf("pt", "ts"),
    "NA" to listOf("af", "nd"),
    "NE" to listOf("fr", "ha", "knc"),
    "NG" to listOf("ha", "yo", "ig", "pcm", "ff", "nup"),
    "NI" to listOf("es"),
    "NL" to listOf("nl", "fy", "li", "nds"),
    "NO" to listOf("nb", "nn", "se"),
    "NP" to listOf("ne", "mai", "bho", "new", "dty", "awa"),
    "NZ" to listOf("mi"),
    "OM" to listOf("ar"),
    "PE" to listOf("es", "qu", "ay"),
    "PF" to listOf("ty", "fr"),
    "PH" to listOf("tl", "ceb", "ilo", "war", "pam", "pag", "bcl"),
    "PK" to listOf("ur", "pnb", "ps", "sd", "skr", "ks"),
    "PL" to listOf("pl", "csb", "szl"),
    "PR" to listOf("es"),
    "PT" to listOf("pt", "mwl"),
    "PY" to listOf("es", "gn"),
    "QA" to listOf("ar"),
    "RO" to listOf("ro", "hu", "rup"),
    "RS" to listOf("sr", "hu"),
    "RU" to listOf(
        "ru", "tt", "ba", "cv", "ce", "sah", "udm", "kv", "xal", "tyv", "bua",
        "myv", "chm", "ady", "kbd", "av", "lez", "os", "inh", "koi", "krc",
        "lbe", "mdf", "mrj", "alt",
    ),
    "RW" to listOf("rw", "fr"),
    "SA" to listOf("ar"),
    "SD" to listOf("ar"),
    "SE" to listOf("sv", "se", "fi"),
    "SG" to listOf("zh", "ms", "ta"),
    "SI" to listOf("sl"),
    "SK" to listOf("sk", "hu"),
    "SM" to listOf("sm"),
    "SN" to listOf("wo", "fr", "ff"),
    "SO" to listOf("so", "ar"),
    "SR" to listOf("nl", "srn"),
    "SS" to listOf("ar", "din"),
    "SY" to listOf("ar", "ku", "syc"),
    "TD" to listOf("fr", "ar"),
    "TG" to listOf("fr", "ee"),
    "TH" to listOf("th", "mnw", "tdd", "shn"),
    "TJ" to listOf("tg", "ru", "uz"),
    "TL" to listOf("tet", "pt"),
    "TM" to listOf("tk", "ru"),
    "TN" to listOf("ar", "zgh", "fr"),
    "TO" to listOf("to"),
    "TR" to listOf("tr", "ku", "ckb", "diq", "hyw", "crh"),
    "TW" to listOf("zh", "tay", "pwn", "szy", "ami", "trv"),
    "TZ" to listOf("sw"),
    "UA" to listOf("uk", "ru", "crh", "rue", "rsk"),
    "UG" to listOf("sw", "lg"),
    "US" to listOf("es", "nv", "chr", "haw"),
    "UY" to listOf("es"),
    "UZ" to listOf("uz", "kaa", "ru", "tg"),
    "VE" to listOf("es"),
    "VN" to listOf("vi"),
    "VU" to listOf("bi", "fr"),
    "WS" to listOf("sm"),
    "YE" to listOf("ar"),
    "ZA" to listOf("af", "zu", "xh", "st", "tn", "ts", "ss", "nso", "ve", "nr"),
    "ZM" to listOf("ny"),
    "ZW" to listOf("sn", "nd"),
)
