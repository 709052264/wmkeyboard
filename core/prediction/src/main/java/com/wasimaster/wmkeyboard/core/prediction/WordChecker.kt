package com.wasimaster.wmkeyboard.core.prediction

/**
 * A spelling checker the suggestion engine asks beside its word lists: a word
 * it accepts is never autocorrected away, and its spellings of a word it does
 * not accept join the strip.
 *
 * The lists hold words; a checker holds rules. Hunspell ([HunspellChecker])
 * accepts the compounds German, Dutch, Hungarian and Finnish make up on the
 * spot, which no list can hold, and offers spellings for them; a GiellaLT
 * speller ([DivvunChecker]) does the same for the inflections and compounds
 * of North Sámi, Greenlandic, Faroese and the other languages GiellaLT builds
 * for. The engine has [NONE] until a language with an imported Hunspell
 * dictionary or a GiellaLT speller is typed on a build whose spelling
 * libraries are present, and [of] both when a language has both.
 */
interface WordChecker {

    /**
     * Whether [word], as typed, is spelt right. Asked on the main thread at
     * every space, so an implementation answers quickly or says no.
     */
    fun accepts(word: String): Boolean

    /**
     * Spellings of [word], best first, at most [limit]. May take a quarter of
     * a second, so the engine asks only on its background pass.
     */
    fun suggestions(word: String, limit: Int): List<String>

    companion object {
        /** No checker: accepts nothing and suggests nothing, which is the engine without one. */
        val NONE: WordChecker = object : WordChecker {
            override fun accepts(word: String): Boolean = false

            override fun suggestions(word: String, limit: Int): List<String> = emptyList()
        }

        /**
         * One checker over [checkers]: a word any of them accepts is accepted,
         * and the spellings take turns, each checker's best before anyone's
         * second, so neither crowds the other off the strip.
         */
        fun of(checkers: List<WordChecker>): WordChecker = when (checkers.size) {
            0 -> NONE
            1 -> checkers[0]
            else -> object : WordChecker {
                override fun accepts(word: String): Boolean = checkers.any { it.accepts(word) }

                override fun suggestions(word: String, limit: Int): List<String> {
                    val each = checkers.map { it.suggestions(word, limit) }
                    val out = LinkedHashSet<String>()
                    for (rank in 0 until (each.maxOfOrNull { it.size } ?: 0)) {
                        for (list in each) list.getOrNull(rank)?.let { out += it }
                    }
                    return out.take(limit)
                }
            }
        }
    }
}
