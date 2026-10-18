package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.text.Graphemes
import java.util.Locale

/**
 * The Text repeat tool's engine: a piece of text written out many times, and
 * the detector behind its chip, which notices when the field already holds
 * the same thing several times over.
 *
 * Pure and synchronous, like [SmartSuggest]: the panel builds its preview
 * from [build] on every change, and the chip asks [findRun] about the text
 * before the cursor on every keystroke, so both stay cheap and allocation-
 * light. Nothing here reads settings or the field; the caller hands those in.
 */
object TextRepeat {

    /**
     * How the copies are laid out. [REPEAT] writes the text [Spec.count] times
     * with the separator between; [STRETCH] lengthens the text's last letter
     * instead ("no" → "nooooo"); [STAIRS] grows a copy at a time ("ha", "haha",
     * "hahaha").
     */
    enum class Mode { REPEAT, STRETCH, STAIRS }

    /** What goes between the copies. [CUSTOM] reads [Spec.customSeparator]. */
    enum class Separator(val text: String) {
        NONE(""), SPACE(" "), NEWLINE("\n"), COMMA(", "), CUSTOM("");

        companion object {
            /** The preset that writes [text], or [CUSTOM] when none does. */
            fun of(text: String): Separator = entries.firstOrNull { it != CUSTOM && it.text == text } ?: CUSTOM
        }
    }

    /**
     * What Insert does with the copies the user had already typed when the
     * chip opened the tool: take them out and write the whole count in their
     * place, or leave them and add the count after.
     */
    enum class ChipInsert { REPLACE_RUN, APPEND }

    data class Spec(
        val text: String,
        val count: Int,
        val mode: Mode = Mode.REPEAT,
        val separator: Separator = Separator.SPACE,
        val customSeparator: String = "",
        /** "1. x", "2. x" … in front of each copy. */
        val numbered: Boolean = false,
        /** Every second copy in capitals: x X x X. */
        val alternateCase: Boolean = false,
        val maxChars: Int = DEFAULT_MAX_CHARS,
    ) {
        val separatorText: String
            get() = if (separator == Separator.CUSTOM) customSeparator else separator.text
    }

    /**
     * The built text. [copies] is how many made it in ([Mode.STRETCH] counts
     * the letters added); [truncated] says the limit cut the run short, always
     * at a whole copy so the result never ends mid-word.
     */
    data class Result(val text: String, val copies: Int, val truncated: Boolean)

    const val MIN_COUNT = 1
    const val MAX_COUNT = 1000
    const val DEFAULT_COUNT = 10

    /** The counts the panel offers as one-tap chips. */
    val quickCounts: List<Int> = listOf(2, 3, 5, 10, 25, 50, 100)

    /** Longest custom separator: a few characters, not a second text. */
    const val MAX_SEPARATOR_LENGTH = 8

    /** Longest text the panel's box takes; the limit on the output is the real cap. */
    const val MAX_TEXT_LENGTH = 2000

    const val MIN_MAX_CHARS = 500
    const val DEFAULT_MAX_CHARS = 5000
    const val MAX_MAX_CHARS = 20000

    /** The default for "chip after N repeats", and its range. */
    const val DEFAULT_REPEAT_AFTER = 3
    const val MIN_REPEAT_AFTER = 2
    const val MAX_REPEAT_AFTER = 6

    private val EMPTY = Result("", 0, truncated = false)

    fun build(spec: Spec): Result {
        val text = spec.text
        if (text.isEmpty()) return EMPTY
        val count = spec.count.coerceIn(MIN_COUNT, MAX_COUNT)
        val limit = spec.maxChars.coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)
        return when (spec.mode) {
            Mode.STRETCH -> stretch(text, count, limit)
            Mode.REPEAT, Mode.STAIRS -> copies(spec, count, limit)
        }
    }

    /** "no" → "nooooo": the last user-perceived character, [count] more times. */
    private fun stretch(text: String, count: Int, limit: Int): Result {
        if (text.length > limit) return Result("", 0, truncated = true)
        val tailLength = Graphemes.lastLength(text)
        val tail = text.substring(text.length - tailLength)
        val room = (limit - text.length) / tailLength
        val copies = minOf(count, room)
        val out = StringBuilder(text.length + copies * tailLength).append(text)
        repeat(copies) { out.append(tail) }
        return Result(out.toString(), copies, truncated = copies < count)
    }

    private fun copies(spec: Spec, count: Int, limit: Int): Result {
        val sep = spec.separatorText
        val out = StringBuilder()
        var copies = 0
        for (index in 0 until count) {
            val piece = piece(spec, index)
            val needed = piece.length + if (index > 0) sep.length else 0
            if (out.length + needed > limit) return Result(out.toString(), copies, truncated = true)
            if (index > 0) out.append(sep)
            out.append(piece)
            copies++
        }
        return Result(out.toString(), copies, truncated = false)
    }

    /** Copy number [index] (from 0) with its number and case applied. */
    private fun piece(spec: Spec, index: Int): String {
        var body = if (spec.mode == Mode.STAIRS) spec.text.repeat(index + 1) else spec.text
        if (spec.alternateCase && index % 2 == 1) body = body.uppercase(Locale.ROOT)
        return if (spec.numbered) "${index + 1}. $body" else body
    }

    /**
     * The count the panel opens on when the chip found [typed] copies already
     * in the field: the first quick count that at least doubles them, so the
     * first Insert visibly does something, and never below [preferred].
     */
    fun countAfter(typed: Int, preferred: Int): Int {
        val floor = maxOf(typed * 2, preferred)
        return quickCounts.firstOrNull { it >= floor } ?: floor.coerceAtMost(MAX_COUNT)
    }

    // ---- the chip's detector ----

    /**
     * The same text written several times in a row, ending at the cursor.
     * [unit] is the text, [separator] what sat between the copies ("" when
     * nothing did), [count] how many copies, and [length] how many characters
     * before the cursor the whole run takes, one trailing separator included,
     * so the tool can take it out and write the run properly.
     */
    data class Run(val unit: String, val separator: String, val count: Int, val length: Int)

    /**
     * The separators a run is read with, the most specific first so "no, no,
     * no" is three copies of "no" rather than three of "no," glued together.
     */
    private val runSeparators = listOf("\n", ", ", ",", " ", "")

    /**
     * Finds a run of at least [minCount] copies at the end of [text], or null.
     *
     * The shortest unit that reaches the count wins, so "no no no no" is four
     * of "no" and never two of "no no". A run with nothing between the copies
     * ("hahaha") needs a unit of two characters or more that is not itself a
     * repetition — "!!!!!!" and "😂😂😂" are left alone, since a chip on every
     * stretched letter or doubled emoji would be noise, and the tool's Stretch
     * mode is a tap away. With a separator one character is enough ("a a a").
     * The run has to start at the beginning of the text or after whitespace,
     * so "xhahaha" is not a run; one trailing separator is allowed, since a
     * finished copy usually has one. [truncated] says [text] is a window cut
     * out of something longer, in which case a run reaching its first
     * character may have started mid-word and that first copy is not counted.
     */
    fun findRun(text: String, minCount: Int, truncated: Boolean = false): Run? {
        if (text.isEmpty()) return null
        val min = minCount.coerceAtLeast(2)
        val last = text.last()
        val body = if (last == ' ' || last == '\n') text.dropLast(1) else text
        if (body.isEmpty() || body.last().isWhitespace()) return null
        for (sep in runSeparators) {
            val maxUnit = (body.length - (min - 1) * sep.length) / min
            for (unitLength in 1..maxUnit) {
                val start = body.length - unitLength
                // Never split a surrogate pair.
                if (body[start].isLowSurrogate()) continue
                val unit = body.substring(start)
                if (unit.isBlank()) continue
                if (sep != "\n" && '\n' in unit) continue
                if (sep.isEmpty() && (unit.codePointCount(0, unit.length) < 2 || !isPrimitive(unit))) continue
                val run = walkBack(text, body, unit, sep, truncated) ?: continue
                if (run.count >= min) return run
            }
        }
        return null
    }

    /** Counts copies of [unit] (each preceded by [sep]) back from the end of [body]. */
    private fun walkBack(text: String, body: String, unit: String, sep: String, truncated: Boolean): Run? {
        val step = unit.length + sep.length
        var start = body.length - unit.length
        var count = 1
        while (start - step >= 0 &&
            body.regionMatches(start - step, unit, 0, unit.length) &&
            body.regionMatches(start - sep.length, sep, 0, sep.length)
        ) {
            start -= step
            count++
        }
        if (start == 0) {
            // A window cut mid-text: the first copy may be the tail of a
            // longer word, so it does not count, and the copy after it is the
            // boundary.
            if (truncated) {
                start += step
                count--
            }
        } else if (!body[start - 1].isWhitespace()) {
            return null
        }
        if (count < 1) return null
        return Run(unit, sep, count, text.length - start)
    }

    /** Whether [unit] is not a shorter string written more than once ("haha" is "ha" twice). */
    private fun isPrimitive(unit: String): Boolean {
        for (period in 1..unit.length / 2) {
            if (unit.length % period != 0) continue
            var repeats = true
            for (i in period until unit.length) {
                if (unit[i] != unit[i - period]) {
                    repeats = false
                    break
                }
            }
            if (repeats) return false
        }
        return true
    }
}
