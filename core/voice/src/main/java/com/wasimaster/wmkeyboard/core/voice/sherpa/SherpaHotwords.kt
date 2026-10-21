package com.wasimaster.wmkeyboard.core.voice.sherpa

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

/**
 * The words dictation listens for (#305), put the way one Sherpa model
 * spells: sherpa-onnx boosts a hotword token by token, so a name only helps
 * when it is written in the pieces the model itself writes it in.
 *
 * Everything is read from the model's `tokens.txt`, which every catalog model
 * has; most of them ship no `bpe.model`. sherpa-onnx cuts each word into
 * pieces itself, over a vocabulary of `piece score` lines ([vocab]). The
 * catalog's models are SentencePiece unigram models, whose pieces are listed
 * best first, so a score falling with the rank stands in for the real one:
 * on 400 to 735 words per model it cuts 93 to 100% of them exactly as the
 * real `bpe.model` does (English 98, Russian 93, Bengali 98, Korean 100, the
 * eight-language model 98).
 *
 * How a word is written for a model:
 * - **Unit.** A model whose pieces are mostly single Chinese characters (the
 *   Chinese and the eight-language ones) takes `cjkchar+bpe`: a CJK character
 *   is a token of its own and an ASCII word is cut into pieces. sherpa-onnx
 *   splits every other script into single characters in that mode, which
 *   such a model never writes, so those words are left out there. Every
 *   other model takes `bpe`.
 * - **Case.** A model that writes in capitals only (the LibriSpeech English
 *   and French ones) or small letters only (Russian) gets the word in that
 *   case; one with mixed-case pieces (Kroko German, Spanish) gets it as typed.
 * - **Forms.** A character the model has precomposed (Bengali `য়`) replaces
 *   the two-code-point form NFC leaves it in.
 * - **Leaving out.** A word with a character no single piece spells could
 *   not be matched, and part of it would boost the wrong thing, so the whole
 *   phrase goes. So does one under three letters (a CJK character counts
 *   two): a lone letter would be boosted wherever it starts a word.
 */
class SherpaHotwords private constructor(
    /** The model's pieces in order, controls and byte pieces left out. */
    private val pieces: List<String>,
    /** sherpa-onnx's `modeling_unit` for this model: `bpe` or `cjkchar+bpe`. */
    val unit: String,
    private val case: Case,
    /** Code points a piece spells on its own, with or without its `▁`. */
    private val singles: Set<Int>,
    /** Code points a piece opens a word with (`▁x`). */
    private val openers: Set<Int>,
    /** Single pieces, as strings, for `cjkchar+bpe`'s character lookups. */
    private val pieceSet: Set<String>,
    private val bareSpace: Boolean,
    /** (decomposed form, the precomposed piece) for each precomposed piece. */
    private val compose: List<Pair<String, String>>,
) {

    private enum class Case { KEEP, UPPER, LOWER }

    /** The vocabulary sherpa-onnx cuts words with: `piece score` a line. */
    fun vocab(): String = buildString {
        pieces.forEachIndexed { rank, piece ->
            append(piece).append(' ').append(String.format(Locale.ROOT, "%.4f", score(rank))).append('\n')
        }
    }

    /**
     * [words] (a phrase each, most important first) as sherpa-onnx hotword
     * lines, each phrase once; the ones this model cannot spell are left out.
     */
    fun lines(words: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(words.size)
        for (word in words) {
            val line = phrase(word) ?: continue
            if (seen.add(line)) out += line
        }
        return out
    }

    /** [raw] written for this model, or null when it cannot be. */
    fun phrase(raw: String): String? {
        // Anything but letters, marks, digits and apostrophes splits words:
        // "Jean-Luc" is said as two. That also keeps out the characters
        // sherpa-onnx reads as syntax in a hotword line (`/`, `:`, `#`, `@`).
        val clean = StringBuilder(raw.length)
        raw.replace('’', '\'').forEachCodePoint { cp ->
            if (cp == '\''.code || Character.isLetterOrDigit(cp) || isMark(cp)) clean.appendCodePoint(cp) else clean.append(' ')
        }
        val words = clean.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val out = ArrayList<String>(words.size)
        for (word in words) {
            val formed = form(word)
            val cased = when (case) {
                Case.UPPER -> formed.uppercase(Locale.ROOT)
                Case.LOWER -> formed.lowercase(Locale.ROOT)
                Case.KEEP -> formed
            }
            out += listOf(cased, formed).firstOrNull(::spells) ?: return null
        }
        var size = 0
        for (word in out) word.forEachCodePoint { size += if (it >= 0x2E80) 2 else 1 }
        return if (size >= MIN_SIZE) out.joinToString(" ") else null
    }

    private fun form(word: String): String {
        var out = Normalizer.normalize(word, Normalizer.Form.NFC)
        for ((decomposed, piece) in compose) {
            if (out.contains(decomposed)) out = out.replace(decomposed, piece)
        }
        return out
    }

    private fun spells(word: String): Boolean {
        if (unit == BPE) return spellsInPieces(word)
        // cjkchar+bpe: an ASCII word goes through the pieces; anything else
        // is looked up a character at a time. A mix would give the ASCII part
        // a word start of its own, which the model never writes mid-word.
        if (word.all { it.code < 0x80 }) {
            return word[0].isLetter() && word.all { it.isLetter() || it == '\'' } && spellsInPieces(word)
        }
        var ok = true
        word.forEachCodePoint { cp -> if (cp < 0x800 || String(Character.toChars(cp)) !in pieceSet) ok = false }
        return ok
    }

    private fun spellsInPieces(word: String): Boolean {
        if (word.isEmpty()) return false
        if (!bareSpace && word.codePointAt(0) !in openers) return false
        var ok = true
        word.forEachCodePoint { if (it !in singles) ok = false }
        return ok
    }

    companion object {
        const val BPE = "bpe"
        const val CJK_CHAR_BPE = "cjkchar+bpe"

        /** The fewest letters a phrase may have (a CJK character counts two). */
        private const val MIN_SIZE = 3

        private const val SPACE = '▁'

        /**
         * A piece's score from its rank: a fixed cost per piece, so fewer
         * pieces win, and a little more the rarer it is. The pair is the one
         * that matched the real models' cuts best (see the class comment).
         */
        private fun score(rank: Int): Double = -2.0 - 0.5 * ln(rank + 2.0)

        /** [tokens], a model's `tokens.txt` (`piece id` a line), read. */
        fun parse(tokens: String): SherpaHotwords {
            val pieces = tokens.lineSequence()
                .map { it.substringBeforeLast(' ') }
                // sherpa-onnx's reader splits a vocabulary line at whitespace.
                .filter { it.isNotEmpty() && it.none { c -> c <= ' ' } && !isControl(it) }
                .toList()
            var cjk = 0
            var mixed = 0
            var upper = 0
            var lower = 0
            val singles = HashSet<Int>()
            val openers = HashSet<Int>()
            val compose = ArrayList<Pair<String, String>>()
            for (piece in pieces) {
                val body = piece.removePrefix(SPACE.toString())
                if (body.codePointCount(0, body.length) == 1) {
                    val cp = body.codePointAt(0)
                    singles += cp
                    if (piece[0] == SPACE) openers += cp
                    if (piece[0] != SPACE && isCjk(cp)) cjk++
                    val decomposed = Normalizer.normalize(body, Normalizer.Form.NFD)
                    if (decomposed != body) compose += decomposed to body
                }
                val up = piece.any { it.isUpperCase() }
                val low = piece.any { it.isLowerCase() }
                when {
                    up && low -> mixed++
                    up -> upper++
                    low -> lower++
                }
            }
            val case = when {
                mixed > 0 || upper + lower == 0 -> Case.KEEP
                upper >= lower -> Case.UPPER
                else -> Case.LOWER
            }
            return SherpaHotwords(
                pieces = pieces,
                unit = if (cjk * 4 >= pieces.size) CJK_CHAR_BPE else BPE,
                case = case,
                singles = singles,
                openers = openers,
                pieceSet = pieces.toHashSet(),
                bareSpace = SPACE.toString() in pieces,
                compose = compose,
            )
        }

        /** `<blk>`, `<unk>`, `<0x41>`, `#0`, `__DUMMY__`: never a spelling. */
        private fun isControl(piece: String): Boolean =
            (piece.length > 2 && piece.startsWith('<') && piece.endsWith('>')) ||
                (piece.length > 1 && piece[0] == '#' && piece.drop(1).all { it.isDigit() }) ||
                (piece.length > 4 && piece.startsWith("__") && piece.endsWith("__"))

        private fun isCjk(cp: Int): Boolean =
            cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FFFF

        private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }

        private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
            var i = 0
            while (i < length) {
                val cp = codePointAt(i)
                action(cp)
                i += Character.charCount(cp)
            }
        }
    }
}
