package com.wasimaster.wmkeyboard.core.voice.punctuation

import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry

/**
 * The text side of dictation punctuation: a phrase as an engine wrote it
 * (words, single spaces, no marks) cut into the punctuation model's tokens,
 * and the model's labels read back onto it as marks and capitals. No model
 * and no native code here, so all of it is tested on the JVM.
 *
 * The model labels a token with the mark that follows it: nothing, a full
 * stop, a comma or a question mark. It also has a colon and a free-standing
 * dash, which it gets right too rarely to type (F1 0.44 and 0.17 on FLORES),
 * so those count as nothing. A word's mark is its last token's; in Chinese
 * and Japanese, which have no spaces, a mark may follow any character.
 */
object PunctuationText {

    // The model's label ids; 4 (a dash) and 5 (a colon) are never typed.
    const val NONE = 0
    const val PERIOD = 1
    const val COMMA = 2
    const val QUESTION = 3

    /** Sequence ids the model was trained with around a text: `<bos>`, `<eos>`. */
    const val BOS = 2
    const val EOS = 1

    /**
     * Tokens per run. The model reads 8,192, but a phrase is never near
     * that, and a long one costs less in overlapping pieces.
     */
    const val CHUNK = 400

    /** Tokens two runs share, so no word is labelled without its context. */
    const val OVERLAP = 50

    /** The phrase with its words one space apart, and nothing either side. */
    fun normalize(text: String): String = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    /** The model's tokens of a phrase, and where each one ends in it (exclusive, in chars). */
    class Encoded(val ids: IntArray, val ends: IntArray)

    /**
     * [text] (normalised) as the model's tokens: each word encoded on its own
     * with the `▁` that stands for the space before it, as the tokenizer's
     * Metaspace pre-tokenizer cuts a text. [encodeWord] tokenises `" word"`;
     * [piece] spells an id. A run of byte pieces (`<0xE0>`) spells one
     * character, or two chars when it is a surrogate pair.
     */
    fun encode(text: String, encodeWord: (String) -> IntArray, piece: (Int) -> String): Encoded {
        val ids = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var start = 0
        while (start < text.length) {
            var stop = text.indexOf(' ', start)
            if (stop < 0) stop = text.length
            // The ▁ stands on the space in front, which the first word has
            // only in the tokenizer's view.
            var end = start - 1
            // Continuation bytes still to come for the character a lead
            // byte began, and whether it is outside the BMP (two chars).
            var pending = 0
            var wide = false
            for (id in encodeWord(" " + text.substring(start, stop))) {
                val p = piece(id)
                val byte = byteOf(p)
                if (byte < 0) {
                    pending = 0
                    end += p.length
                } else if (pending == 0) {
                    val length = utf8Length(byte)
                    pending = length - 1
                    wide = length == 4
                    if (pending == 0) end += 1
                } else {
                    pending--
                    if (pending == 0) end += if (wide) 2 else 1
                }
                ids += id
                ends += end.coerceIn(0, stop)
            }
            start = stop + 1
        }
        return Encoded(ids.toIntArray(), ends.toIntArray())
    }

    /**
     * The model's label for each of [ids], run [CHUNK] tokens at a time with
     * [OVERLAP] between runs, each run between [BOS] and [EOS]; the label
     * that counts is the one from the run that saw the most context after
     * it. [run] returns a label per id it was given, specials included, or
     * null when the model failed; then so does this.
     */
    fun labels(ids: IntArray, run: (IntArray) -> IntArray?): IntArray? {
        val n = ids.size
        val out = IntArray(n)
        var start = 0
        while (start < n) {
            val end = minOf(n, start + CHUNK)
            val window = IntArray(end - start + 2)
            window[0] = BOS
            ids.copyInto(window, 1, start, end)
            window[window.size - 1] = EOS
            val got = run(window) ?: return null
            if (got.size != window.size) return null
            val keepTo = if (end == n) n else end - OVERLAP
            for (k in start until keepTo) out[k] = got[k - start + 1]
            if (end == n) break
            start = keepTo
        }
        return out
    }

    /**
     * Where the marks go: for each character of [text], the label of the
     * mark that follows it, or [NONE]. A label counts at a word's end, or
     * after any character of Chinese or Japanese. Nothing goes after a
     * character that is already a mark.
     */
    fun marks(text: String, ends: IntArray, labels: IntArray): IntArray {
        val out = IntArray(text.length)
        for (k in ends.indices) {
            val label = labels.getOrElse(k) { NONE }
            if (label != PERIOD && label != COMMA && label != QUESTION) continue
            val e = ends[k]
            if (e <= 0 || e > text.length) continue
            val before = text.codePointBefore(e)
            val atWordEnd = e == text.length || text[e] == ' '
            if (!atWordEnd && !isCjk(before) && !(e < text.length && isCjk(text.codePointAt(e)))) continue
            if (isMark(before)) continue
            out[e - 1] = label
        }
        return out
    }

    /**
     * [text] with its marks written in, each in the script of the words in
     * front of it (`।` after Bengali and Hindi, `؟` after Arabic, `。` after
     * Chinese), and a capital on the first letter of every sentence, the
     * first one included: the caret decides later whether the phrase opens
     * one ([com.wasimaster.wmkeyboard.core.voice.VoiceCasing]).
     *
     * [languages] are the dictation's base language codes. English writes the
     * pronoun I as a capital; Spanish opens a question with ¿; Urdu ends a
     * sentence with ۔ and Japanese separates with 、. [names] are words, by
     * index, to spell as given: the capitals another source knows (see
     * [casedWords]).
     */
    fun render(
        text: String,
        marks: IntArray,
        languages: List<String>,
        names: Map<Int, String> = emptyMap(),
    ): String {
        val english = "en" in languages
        val spanish = "es" in languages
        val out = StringBuilder(text.length + 8)
        var capital = true
        // Where the sentence being written began in [out], for a Spanish ¿.
        var sentenceAt = -1
        var index = 0
        var start = 0
        while (start < text.length) {
            var stop = text.indexOf(' ', start)
            if (stop < 0) stop = text.length
            val word = text.substring(start, stop)
            var shown = names[index]?.takeIf { it.length == word.length && it.equals(word, ignoreCase = true) } ?: word
            if (english && isPronounI(shown)) shown = "I" + shown.substring(1)
            if (out.isNotEmpty()) out.append(' ')
            var j = 0
            while (j < shown.length) {
                val cp = shown.codePointAt(j)
                val size = Character.charCount(cp)
                if (capital && Character.isLetterOrDigit(cp)) {
                    sentenceAt = out.length
                    out.appendCodePoint(if (Character.isLetter(cp)) Character.toTitleCase(cp) else cp)
                    capital = false
                } else {
                    out.appendCodePoint(cp)
                }
                val mark = marks.getOrElse(start + j + size - 1) { NONE }
                if (mark != NONE) {
                    if (mark == QUESTION && spanish && sentenceAt >= 0 && isLatin(out.codePointAt(sentenceAt)) &&
                        out.indexOf("¿", sentenceAt) < 0
                    ) {
                        out.insert(sentenceAt, '¿')
                    }
                    out.append(markText(mark, scriptBefore(text, start + j + size), languages))
                    if (mark == PERIOD || mark == QUESTION) {
                        capital = true
                        sentenceAt = -1
                    }
                }
                j += size
            }
            index++
            start = stop + 1
        }
        return out.toString()
    }

    /**
     * The capitals another punctuation pass put on [raw]'s words, by word
     * index, apart from the ones it put there for opening its own sentences:
     * those follow its marks, and these follow the model's. [cased] is
     * [raw] as that pass wrote it, the same words with marks and capitals
     * added. Null when the two do not line up word for word.
     */
    fun casedWords(raw: String, cased: String): Map<Int, String>? {
        val words = raw.split(' ')
        val tokens = cased.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.size != words.size) return null
        val out = HashMap<Int, String>()
        var opens = true
        for ((i, token) in tokens.withIndex()) {
            val core = core(token)
            if (!core.equals(core(words[i]), ignoreCase = true)) return null
            if (!opens && core != words[i] && core.any { it.isUpperCase() }) out[i] = core
            opens = token.isNotEmpty() && token.last() in SENTENCE_ENDS
        }
        return out
    }

    /** [word] without the marks around it, apostrophes kept. */
    private fun core(word: String): String = word.trim { !Character.isLetterOrDigit(it) && it != '\'' && it != '’' }

    /** The mark [label] stands for, after a character of [script]. */
    internal fun markText(label: Int, script: ScriptDef?, languages: List<String>): String {
        val id = script?.id
        return when (id) {
            ScriptId.HAN, ScriptId.JAPANESE -> when (label) {
                PERIOD -> "。"
                QUESTION -> "？"
                else -> if (id == ScriptId.JAPANESE || ("ja" in languages && "zh" !in languages)) "、" else "，"
            }
            ScriptId.ARABIC -> when (label) {
                PERIOD -> if ("ur" in languages) "۔" else "."
                QUESTION -> script.questionMark
                else -> "،"
            }
            ScriptId.ETHIOPIC -> when (label) {
                PERIOD -> script.fullStop
                QUESTION -> "?"
                else -> "፣"
            }
            // Greek asks with what looks like a semicolon.
            ScriptId.GREEK -> when (label) {
                PERIOD -> "."
                QUESTION -> ";"
                else -> ","
            }
            else -> when (label) {
                PERIOD -> script?.fullStop ?: "."
                QUESTION -> script?.questionMark ?: "?"
                else -> ","
            }
        }
    }

    /**
     * The script of the last letter before [end]: digits and symbols have
     * none of their own, so "২৫ টাকা" and "25 টাকা" both end in Bengali's mark.
     */
    private fun scriptBefore(text: String, end: Int): ScriptDef? {
        var i = end
        while (i > 0) {
            val cp = text.codePointBefore(i)
            if (Character.isLetter(cp)) return ScriptRegistry.all.firstOrNull { it.contains(cp) }
            i -= Character.charCount(cp)
        }
        return null
    }

    private fun isCjk(cp: Int): Boolean =
        cp in 0x3040..0x30FF || cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FFFF

    private fun isLatin(cp: Int): Boolean = Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN

    private fun isMark(cp: Int): Boolean = cp < 0x10000 && cp.toChar() in MARKS

    /** "i", or a contraction of it: i'm, i'll, i've, i'd, with either apostrophe. */
    private fun isPronounI(word: String): Boolean =
        word == "i" || (word.length > 1 && word[0] == 'i' && (word[1] == '\'' || word[1] == '’'))

    /** The value of a byte piece (`<0x41>`), or -1 for any other piece. */
    private fun byteOf(piece: String): Int {
        if (piece.length != 6 || !piece.startsWith("<0x") || piece[5] != '>') return -1
        return piece.substring(3, 5).toIntOrNull(16) ?: -1
    }

    /** How many bytes the UTF-8 character led by [byte] takes; 1 for a byte that leads nothing. */
    private fun utf8Length(byte: Int): Int = when {
        byte in 0xC0..0xDF -> 2
        byte in 0xE0..0xEF -> 3
        byte in 0xF0..0xF7 -> 4
        else -> 1
    }

    private val WHITESPACE = Regex("\\s+")
    private val SENTENCE_ENDS = setOf('.', '?', '!', '…')
    private val MARKS = ".,?!;:।॥۔։።፣،؟。，、？！；：".toSet()
}
