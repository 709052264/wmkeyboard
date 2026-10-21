package com.wasimaster.wmkeyboard.core.prediction

/**
 * The words inside a run of Thai, Lao, Khmer or Burmese.
 *
 * Those four are written without spaces between words, so what every other
 * part of prediction reads as one word, a run of letters between spaces, is
 * there a phrase or a whole sentence: learned as one "word" it fills the
 * personal dictionary with sentences, and read back as context it matches
 * nothing. This asks ICU4X's word segmenter (its LSTM models, in the Rust
 * engine: `native/wm-engine/src/segment.rs`) where the words are, so the
 * callers can treat each one as the word it is.
 *
 * Only for those four scripts. Chinese and Japanese have no model here and
 * stay one piece, as do Tibetan and Javanese; every other script already
 * puts spaces where its words end.
 */
object WordSegments {

    /** Whether [codePoint] belongs to one of the four scripts split here. */
    fun isSegmentedScript(codePoint: Int): Boolean {
        if (codePoint < 0x0E00) return false
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR,
            -> true
            else -> false
        }
    }

    /** Whether any character of [text] is in one of the four scripts. */
    fun hasSegmentedScript(text: CharSequence): Boolean = text.any { isSegmentedScript(it.code) }

    /**
     * The words of [text] as `[start, end)` offsets, flat: `[s0, e0, s1, e1, ...]`.
     * Spaces and punctuation are left out; outside the four scripts the
     * words are the ones Unicode's word rules give.
     */
    fun ranges(text: String): IntArray =
        if (text.isEmpty()) IntArray(0) else PredictionNative.segmentWords(text)

    /**
     * [word] as the words it holds: itself, alone, unless it is in one of the
     * four scripts and the segmenter finds more than one word in it. The
     * pieces are its own characters, in order, with nothing between them
     * dropped, so joined they give [word] back.
     */
    fun split(word: String): List<String> {
        if (!hasSegmentedScript(word)) return listOf(word)
        val at = ranges(word)
        if (at.size <= 2) return listOf(word)
        // Cut at each word's start, so whatever the segmenter left between
        // two words (a joiner, a mark it does not count) stays with one.
        val cuts = (2 until at.size step 2).map { at[it] }
        val out = ArrayList<String>(cuts.size + 1)
        var start = 0
        for (cut in cuts) {
            out += word.substring(start, cut)
            start = cut
        }
        out += word.substring(start)
        return out
    }
}
