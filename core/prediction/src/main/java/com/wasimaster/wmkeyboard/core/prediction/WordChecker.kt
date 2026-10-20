package com.wasimaster.wmkeyboard.core.prediction

/**
 * A spelling checker the suggestion engine asks beside its word lists: a word
 * it accepts is never autocorrected away, and its spellings of a word it does
 * not accept join the strip.
 *
 * The lists hold words; a checker holds rules. Hunspell, the one in use
 * ([HunspellChecker]), accepts the compounds German, Dutch, Hungarian and
 * Finnish make up on the spot, which no list can hold, and offers spellings
 * for them. The engine has [NONE] until a language with an imported Hunspell
 * dictionary is typed on a build whose Hunspell library is present.
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
    }
}
