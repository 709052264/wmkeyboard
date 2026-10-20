package com.wasimaster.wmkeyboard.core.translate

/**
 * What the Bergamot engine (Mozilla's Firefox Translations models) can read
 * and write, kept apart from the engine so the settings screens and the unit
 * tests can reason about it without the native library.
 *
 * Its models differ from ML Kit's in three ways that shape this file:
 *
 *  - A model is one direction, not one language: French into English and
 *    English into French are separate downloads (about 14 to 36 MB each,
 *    [BergamotCatalog]). So a translation needs the directions on its route,
 *    and nothing more.
 *  - Mozilla trains every model to or from English. French into German is
 *    French into English, then English into German, in one pass of the engine
 *    (its own pivoting), with both directions on the phone.
 *  - Chinese comes in both scripts (`zh-Hans`, `zh-Hant`), and Norwegian is
 *    Bokmål (`nb`).
 */
object BergamotLanguages : OfflineLanguageTable {

    /** The language every route pivots through. */
    const val PIVOT = "en"

    private val byKey: Map<String, BergamotCatalog.Direction> by lazy {
        BergamotCatalog.directions.associateBy { it.key }
    }

    /** Every language with a model in at least one direction, by Mozilla's code. */
    val SUPPORTED: Set<String> by lazy {
        BergamotCatalog.directions.flatMapTo(mutableSetOf()) { listOf(it.from, it.to) }
    }

    /** Every direction, in catalogue order. */
    val directions: List<BergamotCatalog.Direction> get() = BergamotCatalog.directions

    /** Older and alternative names of Mozilla's languages. */
    private val ALIASES = mapOf(
        "iw" to "he",
        "in" to "id",
        "no" to "nb",
        "nn" to "nb",
        "fil" to "tl",
    )

    private val TRADITIONAL_CHINESE_SUBTAGS = setOf("tw", "hk", "mo", "hant")

    /** The languages an identifier may report a `-Latn` variant of, and no model reads that way. */
    private val ROMANIZABLE = setOf("ar", "bg", "el", "hi", "ja", "ru", "zh")

    /**
     * Mozilla's code for a picker code (`zh-TW`), a keyboard language id or a
     * tag from a language identifier, or null when no model reads or writes
     * it. Romanised tags (`hi-Latn`) are refused, as ML Kit's table does.
     */
    override fun modelCode(tag: String): String? {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        val primary = parts.firstOrNull()?.lowercase() ?: return null
        val rest = parts.drop(1).map { it.lowercase() }
        if ("latn" in rest && isRomanized(tag)) return null
        val code = when (primary) {
            "zh" -> if (rest.any { it in TRADITIONAL_CHINESE_SUBTAGS }) "zh-Hant" else "zh-Hans"
            else -> ALIASES[primary] ?: primary
        }
        return code.takeIf { it in SUPPORTED }
    }

    override fun isRomanized(tag: String): Boolean {
        val parts = tag.trim().replace('_', '-').lowercase().split('-')
        return parts.size >= 2 && parts[1] == "latn" && parts[0] in ROMANIZABLE
    }

    /** The direction [from] into [to] (model codes), when Mozilla has one. */
    fun direction(from: String, to: String): BergamotCatalog.Direction? = byKey["$from-$to"]

    /** The direction with this [BergamotCatalog.Direction.key], `fr-en`. */
    fun direction(key: String): BergamotCatalog.Direction? = byKey[key]

    /** Whether some model reads [code]: it is English, or it has a model into English. */
    fun reads(code: String): Boolean = code == PIVOT || direction(code, PIVOT) != null

    /** Whether some model writes [code]: it is English, or English has a model into it. */
    fun writes(code: String): Boolean = code == PIVOT || direction(PIVOT, code) != null

    /**
     * The directions a translation from [source] into [target] (model codes)
     * runs through, in order: none when the two are the same language, one
     * for a pair with its own model, two through English otherwise. Null when
     * there is no route at all.
     */
    fun route(source: String, target: String): List<BergamotCatalog.Direction>? {
        if (source == target) return emptyList()
        direction(source, target)?.let { return listOf(it) }
        if (source == PIVOT || target == PIVOT) return null
        val first = direction(source, PIVOT) ?: return null
        val second = direction(PIVOT, target) ?: return null
        return listOf(first, second)
    }
}

/**
 * A rough idea of the language a text is in, for the Bergamot and NLLB
 * engines on a phone without ML Kit's language identifier (F-Droid, and
 * Play before its module is here). It is a stand-in, not an identifier:
 *
 *  - most scripts belong to one language, or to a few told apart by a letter
 *    only one of them uses (Ukrainian ї, Urdu ے);
 *  - text in Latin letters is matched against each language's commonest
 *    short words, which a sentence of five words or more almost always has.
 *
 * Its candidates go through [OfflineTranslatePlan.resolveSource] like the
 * identifier's, so the keyboard's own languages settle what it cannot, and
 * the panel shows a guess as a guess.
 */
object ScriptLanguageGuess {

    /** What a text gives too little to go on below: a word or two. */
    private const val MIN_LETTERS = 2

    fun candidates(text: String): List<OfflineTranslatePlan.Candidate> {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull() ?: continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        val letters = counts.values.sum()
        if (letters < MIN_LETTERS) return emptyList()
        val kana = (counts[Character.UnicodeScript.HIRAGANA] ?: 0) + (counts[Character.UnicodeScript.KATAKANA] ?: 0)
        if (kana > 0) return listOf(candidate("ja", 0.95f))
        val (script, count) = counts.maxByOrNull { it.value } ?: return emptyList()
        val share = count.toFloat() / letters
        return when (script) {
            Character.UnicodeScript.LATIN -> latin(text)
            Character.UnicodeScript.CYRILLIC -> cyrillic(text)
            Character.UnicodeScript.ARABIC -> arabic(text)
            Character.UnicodeScript.DEVANAGARI -> listOf(candidate("hi", 0.45f), candidate("mr", 0.35f))
            Character.UnicodeScript.ETHIOPIC -> listOf(candidate("am", 0.45f), candidate("ti", 0.3f))
            Character.UnicodeScript.TIBETAN -> listOf(candidate("bo", 0.45f), candidate("dz", 0.3f))
            else -> SINGLE[script]?.let { listOf(candidate(it, 0.9f * share)) }.orEmpty()
        }
    }

    private fun candidate(tag: String, confidence: Float) = OfflineTranslatePlan.Candidate(tag, confidence)

    /** Scripts that, among the languages the engines have, belong to one. */
    private val SINGLE = mapOf(
        Character.UnicodeScript.HAN to "zh",
        Character.UnicodeScript.HANGUL to "ko",
        Character.UnicodeScript.GREEK to "el",
        Character.UnicodeScript.HEBREW to "he",
        Character.UnicodeScript.THAI to "th",
        Character.UnicodeScript.BENGALI to "bn",
        Character.UnicodeScript.GUJARATI to "gu",
        Character.UnicodeScript.GURMUKHI to "pa",
        Character.UnicodeScript.KANNADA to "kn",
        Character.UnicodeScript.MALAYALAM to "ml",
        Character.UnicodeScript.TAMIL to "ta",
        Character.UnicodeScript.TELUGU to "te",
        Character.UnicodeScript.GEORGIAN to "ka",
        Character.UnicodeScript.ARMENIAN to "hy",
        // Only NLLB has these.
        Character.UnicodeScript.KHMER to "km",
        Character.UnicodeScript.LAO to "lo",
        Character.UnicodeScript.MYANMAR to "my",
        Character.UnicodeScript.SINHALA to "si",
        Character.UnicodeScript.ORIYA to "or",
    )

    private fun cyrillic(text: String): List<OfflineTranslatePlan.Candidate> {
        val lower = text.lowercase()
        return when {
            lower.any { it in "ђћџљњј" } -> listOf(candidate("sr", 0.85f))
            lower.any { it in "ў" } -> listOf(candidate("be", 0.85f))
            lower.any { it in "їєґ" } -> listOf(candidate("uk", 0.85f))
            lower.any { it in "ѓќѕ" } -> listOf(candidate("mk", 0.85f))
            lower.any { it in "ыэё" } -> listOf(candidate("ru", 0.75f), candidate("be", 0.1f))
            lower.any { it == 'і' } -> listOf(candidate("uk", 0.5f), candidate("be", 0.3f))
            else -> listOf(candidate("ru", 0.45f), candidate("bg", 0.3f), candidate("uk", 0.1f))
        }
    }

    private fun arabic(text: String): List<OfflineTranslatePlan.Candidate> = when {
        text.any { it in "ۆۇۈېۋ" } -> listOf(candidate("ug", 0.85f))
        text.any { it in "ٹڈڑںے" } -> listOf(candidate("ur", 0.85f))
        text.any { it in "پچژگ" } -> listOf(candidate("fa", 0.6f), candidate("ur", 0.25f))
        else -> listOf(candidate("ar", 0.8f))
    }

    /**
     * Each Latin-script language's commonest short words. Not a frequency
     * list, just words that are frequent in that language and rare as words
     * in the others it is likely to be confused with.
     */
    private val WORDS: Map<String, Set<String>> = mapOf(
        "en" to "the and of to is in that it was for with you this are have not on be at".words(),
        "de" to "der die das und ist nicht ich sie mit den ein eine zu auf auch es dem wir".words(),
        "fr" to "le la les et est une des pas que pour dans avec je qui sur nous vous ce".words(),
        "es" to "el la los las y es que una por para con del pero como muy esta lo".words(),
        "it" to "il la di che non per una sono con gli della questo anche ma ho mi".words(),
        "pt" to "o a os as que não uma com para por mas é do da você isso muito".words(),
        "nl" to "de het een en van is niet dat ik je op met zijn voor ook maar we".words(),
        "sv" to "och att det är som en jag inte på för med till har av den vi".words(),
        "da" to "og at det er som en jeg ikke på for med til har af den vi".words(),
        "nb" to "og at det er som en jeg ikke på for med til har av den vi meg".words(),
        "fi" to "ja on ei se että hän oli mutta kun minä sinä ovat myös tämä niin".words(),
        "et" to "ja on ei see et ta oli aga kui mina sina nad ka see mis".words(),
        "pl" to "i w nie się na to że jest z do jak co ale tak jestem".words(),
        "cs" to "a je v se na to že s jsem ale jak co by tak není".words(),
        "sk" to "a je v sa na to že s som ale ako čo by tak nie".words(),
        "sl" to "in je v se na da so ne pa za z to tudi kot sem".words(),
        "hr" to "i je u se na da su ne za s to ali kao sam što".words(),
        "bs" to "i je u se na da su ne za s to ali kao sam šta".words(),
        "hu" to "a az és nem hogy is egy van de meg ez csak már volt".words(),
        "ro" to "și este în nu că o un pe cu la sunt pentru mai dar".words(),
        "ca" to "el la els i és que una per amb no del però com molt".words(),
        "gl" to "o a os e é que unha por para con non do da pero".words(),
        "eu" to "eta da ez du bat ere baina zer hau dira dut naiz".words(),
        "tr" to "ve bir bu da de için ne ile çok ama ben sen değil var".words(),
        "az" to "və bir bu da də üçün nə ilə çox amma mən sən deyil var".words(),
        "id" to "yang dan di itu ini tidak dengan untuk ada saya kamu dari akan".words(),
        "ms" to "yang dan di itu ini tidak dengan untuk ada saya awak dari akan".words(),
        "tl" to "ang ng sa na at mga ay hindi ko ako siya para ito".words(),
        "vi" to "và là của có không một những được cho này tôi bạn với".words(),
        "sq" to "dhe në është një që të nuk për me unë ti por".words(),
        "is" to "og að er í á það ekki ég við sem til með en".words(),
        "af" to "die en van is nie dat ek jy het in op vir met".words(),
        "lv" to "un ir ar ka no uz par bet es tu viņš nav kas".words(),
        "lt" to "ir yra kad su ne į bet aš tu jis iš kas tai".words(),
    )

    private fun String.words(): Set<String> = split(' ').toSet()

    private fun latin(text: String): List<OfflineTranslatePlan.Candidate> {
        val tokens = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val scores = WORDS.mapValues { (_, words) -> tokens.count { it in words } }
        val total = scores.values.sum()
        if (total == 0) return emptyList()
        val ranked = scores.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.value ?: 0
        // Sure of it only when it is clearly ahead and the text gave enough:
        // two close neighbours (Danish, Norwegian) are left to the hints.
        val lead = (best.value - runnerUp).toFloat() / best.value
        val coverage = best.value.toFloat() / tokens.size
        val confidence = (0.35f + 0.4f * lead + 0.5f * coverage).coerceAtMost(0.95f)
        return ranked.take(3).mapIndexed { index, entry ->
            candidate(entry.key, if (index == 0) confidence else confidence * entry.value / best.value / 2)
        }
    }
}
