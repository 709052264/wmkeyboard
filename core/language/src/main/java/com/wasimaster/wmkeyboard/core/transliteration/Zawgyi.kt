package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Burmese in Zawgyi, the font encoding much Burmese text is still written in:
 * the same code points as Unicode, given different glyphs and stored in visual
 * order (ေ before its consonant), so a string reads in one encoding and is
 * garbage in the other.
 *
 * A port of google/myanmar-tools (Apache-2.0) living in the Rust engine
 * (`native/wm-engine/src/zawgyi.rs`): its Markov-model detector and the
 * Zawgyi ↔ Unicode rules its Java client compiles from CLDR, held to that
 * client's output on the upstream corpus. This is the call into it.
 */
object Zawgyi {

    /**
     * Whether [text] holds a code point of the Myanmar block. Answered here, so
     * the many callers that only need to know whether to ask anything at all
     * never cross into the engine.
     */
    fun hasMyanmar(text: CharSequence): Boolean = text.any(::isMyanmar)

    /** Whether [c] is in the Myanmar block (U+1000–U+109F). */
    fun isMyanmar(c: Char): Boolean = c in 'က'..'႟'

    /**
     * The probability that [text] is Zawgyi rather than Unicode, from 0 to 1;
     * [Double.NEGATIVE_INFINITY] when it holds no Myanmar letter at all.
     */
    fun probability(text: String): Double =
        if (hasMyanmar(text)) EngineNative.zawgyiProbability(text) else Double.NEGATIVE_INFINITY

    /**
     * Whether [text] is Zawgyi beyond reasonable doubt: enough Myanmar letters
     * to judge, a probability past the engine's bar (0.99999, above the
     * Unicode sentences upstream lists as easily mistaken), and something for
     * the conversion to change. What the keyboard asks before offering to
     * convert text nobody asked it about.
     */
    fun isConfidentlyZawgyi(text: String): Boolean = hasMyanmar(text) && EngineNative.zawgyiConfident(text)

    /** Zawgyi [text] as Unicode; what is not Myanmar passes through. */
    fun toUnicode(text: String): String = if (hasMyanmar(text)) EngineNative.zawgyiToUnicode(text) else text

    /** Unicode Burmese [text] as Zawgyi. */
    fun toZawgyi(text: String): String = if (hasMyanmar(text)) EngineNative.zawgyiFromUnicode(text) else text

    /**
     * [text] the other way round: to Unicode when the detector leans Zawgyi,
     * to Zawgyi otherwise. For a conversion the user asked for on text they
     * can see, where a lean is enough.
     */
    fun convertEitherWay(text: String): String =
        if (hasMyanmar(text)) EngineNative.zawgyiConvertEitherWay(text) else text
}
