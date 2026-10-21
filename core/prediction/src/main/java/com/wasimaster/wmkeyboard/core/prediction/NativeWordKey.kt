package com.wasimaster.wmkeyboard.core.prediction

/**
 * The Rust twins of [WordKey.of], [WordKey.surface] and
 * [WordContext.isLearnableWord] (`native/wm-engine/src/word_key.rs`): what
 * the user lexicon folds and judges with when it loads its file, and what
 * WMK Desktop folds every word with.
 *
 * The keyboard keeps folding with [WordKey] on the typing path, where a
 * crossing per word would cost more than the fold itself. Public so the
 * parity test in `:app` (`WordKeyParityTest`) can hold the two to each other.
 */
object NativeWordKey {
    fun of(word: String): String = PredictionNative.wordKey(word)

    fun surface(word: String): String = PredictionNative.wordSurface(word)

    fun isLearnable(word: String): Boolean = PredictionNative.isLearnableWord(word)
}
