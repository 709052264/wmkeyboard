package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Urdu written back in Latin letters: کرنا → karna, مرد → mard, بچہ → bacha.
 * The rules live in the Rust engine (`native/wm-engine/src/urdu_romanizer.rs`);
 * this is the call into it.
 */
object UrduRomanizer {

    /**
     * Urdu letters and the marks that spell a word with them, not its digits
     * (۰–۹ and ٠–٩), and not the full stop ۔ or the comma ،.
     */
    fun isUrdu(c: Char): Boolean = when (c.code) {
        in 0x0621..0x063A, in 0x0640..0x065F, 0x0670,
        in 0x0671..0x06D3, 0x06D5, in 0x06E5..0x06EF, in 0x06FA..0x06FF -> true
        else -> false
    }

    /**
     * The Arabic letter shapes folded to the Urdu ones the word lists use, the
     * vowel diacritics dropped, and the joiners with them.
     */
    fun normalize(text: String): String = EngineNative.urduNormalize(text)

    /** Whole text: every Urdu run becomes Latin, everything else passes through. */
    fun romanize(text: String): String = EngineNative.urduRomanize(text)

    /** One Urdu word, without spaces. */
    fun romanizeWord(word: String): String = EngineNative.urduRomanizeWord(word)
}
