package com.wasimaster.wmkeyboard.core.text

/**
 * How Select word grows a selection it made when it is pressed again (#679).
 *
 * Each press takes the next ring out that is bigger than the selection, so an
 * address comes apart the way it is built, the parts touching the selection
 * before anything past a space:
 *
 * 1. across `-` and `_`: "wasi-master", "snake_case";
 * 2. across `.`: "github.com";
 * 3. across a single `/`, never a doubled one: "github.com/wasi-master/wmkeyboard";
 * 4. the whole run of characters between two spaces, "https://" included;
 * 5. then a word past the space on each side, and on again.
 *
 * With the caret in `git⌶hub.com` in a link, the presses go "github",
 * "github.com", the path after it, and the whole link last.
 */
object SelectWordGrowth {

    /** The separators each ring may cross, beyond word characters; see the class notes. */
    private val RINGS = listOf("-_", "-_.", "-_./")

    /**
     * The next selection out from [start] until [end] in [text], or null when
     * there is nothing left to take.
     */
    fun next(text: CharSequence, start: Int, end: Int): IntRange? {
        if (start < 0 || end > text.length || start >= end) return null
        for (joiners in RINGS) {
            val ring = expand(text, start, end, joiners)
            if (ring.first < start || ring.last + 1 > end) return ring
        }
        val token = token(text, start, end)
        if (token.first < start || token.last + 1 > end) return token
        // Past the spaces: the next run of characters on each side.
        var from = start
        while (from > 0 && text[from - 1].isWhitespace()) from--
        while (from > 0 && !text[from - 1].isWhitespace()) from--
        var to = end
        while (to < text.length && text[to].isWhitespace()) to++
        while (to < text.length && !text[to].isWhitespace()) to++
        return if (from < start || to > end) from until to else null
    }

    /**
     * What a first press takes when the caret touches no word: the run of
     * characters between two spaces it sits in or against, so `example@⌶`
     * selects "example@" instead of doing nothing. Null between two spaces.
     */
    fun tokenAt(text: CharSequence, at: Int): IntRange? {
        if (at !in 0..text.length) return null
        var from = at
        var to = at
        while (from > 0 && !text[from - 1].isWhitespace()) from--
        while (to < text.length && !text[to].isWhitespace()) to++
        return if (from < to) from until to else null
    }

    private fun token(text: CharSequence, start: Int, end: Int): IntRange {
        var from = start
        var to = end
        while (from > 0 && !text[from - 1].isWhitespace()) from--
        while (to < text.length && !text[to].isWhitespace()) to++
        return from until to
    }

    /**
     * Grows [start] until [end] over word characters and [joiners]. A `/` is
     * crossed only on its own: one with another `/` beside it is the `//` of
     * a scheme, which only the whole-run ring takes.
     */
    private fun expand(text: CharSequence, start: Int, end: Int, joiners: String): IntRange {
        fun joins(i: Int): Boolean {
            val c = text[i]
            if (isWordChar(c)) return true
            if (c !in joiners) return false
            if (c == '/') {
                val doubled = (i > 0 && text[i - 1] == '/') || (i + 1 < text.length && text[i + 1] == '/')
                if (doubled) return false
            }
            return true
        }
        var from = start
        var to = end
        while (from > 0 && joins(from - 1)) from--
        while (to < text.length && joins(to)) to++
        // A joiner at either edge is not part of what it joins: "wasi-" is
        // not a step on the way to "wasi-master".
        while (from < start && !isWordChar(text[from])) from++
        while (to > end && !isWordChar(text[to - 1])) to--
        return from until to
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    } || c == '\''
}
