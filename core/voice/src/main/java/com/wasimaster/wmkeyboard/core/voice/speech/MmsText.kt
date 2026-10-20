package com.wasimaster.wmkeyboard.core.voice.speech

import java.util.Locale

/**
 * A voice's alphabet: `tokens.txt` in sherpa-onnx's format, one symbol and its
 * id per line, a line that starts with a space being the space itself. MMS
 * voices read characters, not phonemes, so this is the whole text frontend.
 */
class MmsTokens private constructor(private val ids: Map<Int, Long>) {

    /**
     * [text] as the ids one VITS run takes: lower-cased the way Meta's own
     * tokenizer does, characters the voice has no symbol for dropped, and,
     * when [addBlank], the id-0 blank before, between and after them, as the
     * model was trained. Empty when nothing in [text] is speakable.
     */
    fun encode(text: String, locale: Locale, addBlank: Boolean): LongArray {
        val out = ArrayList<Long>(text.length * 2 + 1)
        if (addBlank) out += BLANK
        var any = false
        text.lowercase(locale).codePoints().forEach { cp ->
            val id = ids[cp] ?: return@forEach
            out += id
            if (addBlank) out += BLANK
            // Spaces alone are not something to say.
            if (!Character.isWhitespace(cp)) any = true
        }
        return if (any) out.toLongArray() else LongArray(0)
    }

    /** Whether the voice has a symbol for [codePoint] (after lower-casing). */
    fun has(codePoint: Int): Boolean = codePoint in ids

    companion object {
        private const val BLANK = 0L

        fun parse(text: String): MmsTokens {
            val ids = HashMap<Int, Long>()
            for (line in text.split('\n')) {
                val clean = line.trimEnd('\r')
                if (clean.isBlank()) continue
                val cut = clean.lastIndexOf(' ')
                if (cut < 0) continue
                val id = clean.substring(cut + 1).toLongOrNull() ?: continue
                // "  7": the symbol is the space before the separator.
                val symbol = if (cut == 0 || clean.startsWith("  ")) " " else clean.substring(0, cut)
                if (symbol.codePointCount(0, symbol.length) != 1) continue
                ids.putIfAbsent(symbol.codePointAt(0), id)
            }
            return MmsTokens(ids)
        }
    }
}

/** Splitting text into the pieces one VITS run reads. */
object MmsText {

    /**
     * Longest piece read in one run. VITS attends over the whole input, so a
     * paragraph in one run costs memory with its square, and the first sound
     * would wait for all of it.
     */
    const val MAX_PIECE = 280

    private val sentenceEnds = setOf(
        '.', '!', '?', ';', '\n',
        '।', '॥', // Devanagari danda and double danda, also Bengali's
        '。', '！', '？', '．', // CJK full stops
        '؟', '۔', // Arabic question mark and full stop (Urdu)
        '።', '፧', // Ethiopic full stop and question mark
        '၊', '။', // Myanmar section marks
        '།', // Tibetan shad
    )
    private val softBreaks = setOf(',', ':', '،', '、', '，', '՝', '፣')

    /**
     * [text] cut after each sentence end, and a sentence longer than
     * [MAX_PIECE] cut again at a comma or a space. Pieces keep their own
     * punctuation (voices drop what they cannot say) and are trimmed; blank
     * ones are left out.
     */
    fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) out += piece
            current.setLength(0)
        }
        for (c in text) {
            current.append(c)
            if (c in sentenceEnds) {
                flush()
            } else if (current.length >= MAX_PIECE) {
                val cut = current.indexOfLast { it in softBreaks }.takeIf { it > MAX_PIECE / 3 }
                    ?: current.lastIndexOf(" ").takeIf { it > MAX_PIECE / 3 }
                if (cut == null) {
                    flush()
                } else {
                    val rest = current.substring(cut + 1)
                    current.setLength(cut + 1)
                    flush()
                    current.append(rest)
                }
            }
        }
        flush()
        return out
    }
}
