package com.wasimaster.wmkeyboard.core.tools.lookup

import com.wasimaster.wmkeyboard.core.tools.DictEntry
import com.wasimaster.wmkeyboard.core.tools.yomichan.YomichanDictionaries
import java.text.BreakIterator

/**
 * Which word a point in a text is on (#765): the word under a tapped finger,
 * or the first word of a selection.
 *
 * Text written without spaces (Japanese, Chinese, Thai) has no word edges to
 * read, so there it does what Yomichan does: tries the longest stretch from
 * the point that an imported dictionary knows, inflected or not, down to one
 * character. Everywhere else, and where no imported dictionary knows the
 * stretch, the platform's word breaker draws the edges.
 */
object LookupScanner {

    /** [start] until [end] of the text. */
    data class Span(val start: Int, val end: Int) {
        fun of(text: String): String = text.substring(start, end)
    }

    /**
     * The word at a point: its [span], and the entries an imported dictionary
     * already found for it while scanning. Empty [entries] means the word is
     * still to be looked up through the dictionary sources.
     */
    data class Scan(val span: Span, val entries: List<DictEntry> = emptyList())

    /** How far a point that is on a space or punctuation may move to the nearest letter. */
    private const val NUDGE = 2

    /** Most word chips a long selection offers. */
    const val MAX_WORDS = 40

    fun scan(text: String, offset: Int): Scan? {
        if (text.isEmpty()) return null
        val at = nearestLetter(text, offset.coerceIn(0, text.length - 1)) ?: return null
        if (isSpaceless(text[at])) {
            YomichanDictionaries.longestMatch(text, at)?.let { return Scan(Span(it.start, it.end), it.entries) }
        }
        return wordAt(text, at)?.let { Scan(it) }
    }

    /** The word the platform's breaker puts [offset] in, or null on a space or a mark. */
    fun wordAt(text: String, offset: Int): Span? {
        if (offset !in text.indices) return null
        val breaker = BreakIterator.getWordInstance()
        breaker.setText(text)
        val end = breaker.following(offset)
        val start = breaker.previous()
        if (start == BreakIterator.DONE || end == BreakIterator.DONE || start >= end) return null
        val span = Span(start, end)
        return span.takeIf { hasLetter(span.of(text)) }
    }

    /** Every word in [text], in order, for a selection longer than one word. */
    fun words(text: String, limit: Int = MAX_WORDS): List<Span> {
        val out = ArrayList<Span>()
        val breaker = BreakIterator.getWordInstance()
        breaker.setText(text)
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE && out.size < limit) {
            val span = Span(start, end)
            if (hasLetter(span.of(text))) out += span
            start = end
            end = breaker.next()
        }
        return out
    }

    /** The sentence [span] sits in, trimmed, for a flashcard's example. */
    fun sentence(text: String, span: Span): String {
        if (text.isEmpty()) return ""
        val breaker = BreakIterator.getSentenceInstance()
        breaker.setText(text)
        val at = span.start.coerceIn(0, text.length - 1)
        val end = breaker.following(at).takeIf { it != BreakIterator.DONE } ?: text.length
        val start = breaker.previous().takeIf { it != BreakIterator.DONE } ?: 0
        return text.substring(start, maxOf(end, span.end.coerceAtMost(text.length))).trim()
    }

    private fun nearestLetter(text: String, offset: Int): Int? {
        if (isWordChar(text[offset])) return offset
        for (step in 1..NUDGE) {
            val after = offset + step
            if (after < text.length && isWordChar(text[after])) return after
            val before = offset - step
            if (before >= 0 && isWordChar(text[before])) return before
        }
        return null
    }

    private fun isWordChar(char: Char): Boolean = Character.isLetterOrDigit(char)

    private fun hasLetter(text: String): Boolean = text.any { Character.isLetter(it) }

    /** Scripts written without spaces between words. */
    fun isSpaceless(char: Char): Boolean = when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI,
        Character.UnicodeScript.LAO,
        Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.TIBETAN,
        -> true
        else -> char == 'ー'
    }
}
