package com.wasimaster.wmkeyboard.core.xlit

import java.text.Normalizer

/**
 * IndicXlit's two vocabularies, read from `indicxlit-vocab.txt`: `s<TAB>symbol`
 * lines for the source ids (four specials, the 26 letters and a few more, a
 * `__xx__` token per language), then `t<TAB>symbol` lines for the target ids
 * (the specials, every script's characters, the same language tokens), each
 * in id order. Pure, so it is tested off the device.
 */
class IndicXlitVocab private constructor(private val source: List<String>, private val target: List<String>) {

    private val sourceIds: Map<String, Int> = source.withIndex().associate { (i, s) -> s to i }
    private val masks = HashMap<String, BooleanArray>()

    val targetSize: Int get() = target.size

    /**
     * The source ids for [roman] typed on [languageId]'s layout: the language
     * token, the letters lower-cased, then `</s>`. Null when the language has
     * no token or a character is not one the model was trained on (a digit,
     * an apostrophe), which the caller takes as "no readings".
     */
    fun encode(languageId: String, roman: String): IntArray? {
        val lang = sourceIds["__${languageId}__"] ?: return null
        val out = IntArray(roman.length + 2)
        out[0] = lang
        for ((i, c) in roman.withIndex()) {
            val lower = c.lowercaseChar()
            if (lower !in 'a'..'z') return null
            out[i + 1] = sourceIds[lower.toString()] ?: return null
        }
        out[out.size - 1] = EOS
        return out
    }

    /**
     * Which target ids a reading for [languageId] may write: `</s>`, and every
     * symbol whose characters are all [IndicXlitModels.writes] for it.
     */
    @Synchronized
    fun allowed(languageId: String): BooleanArray = masks.getOrPut(languageId) {
        BooleanArray(target.size) { i ->
            val s = target[i]
            i == EOS || (i >= FIRST_SYMBOL && !s.startsWith("__") && s.isNotEmpty() && s.all { IndicXlitModels.writes(languageId, it) })
        }
    }

    /** The text of target ids [ids], NFC. */
    fun decode(ids: IntArray, from: Int = 0, count: Int = ids.size): String {
        val sb = StringBuilder()
        for (k in from until from + count) {
            val id = ids[k]
            if (id in FIRST_SYMBOL until target.size) sb.append(target[id])
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    companion object {
        const val EOS = 2
        private const val FIRST_SYMBOL = 4

        fun parse(lines: Sequence<String>): IndicXlitVocab {
            val source = ArrayList<String>()
            val target = ArrayList<String>()
            for (line in lines) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                require(tab == 1) { "bad vocabulary line: $line" }
                when (line[0]) {
                    's' -> source += line.substring(2)
                    't' -> target += line.substring(2)
                    else -> error("bad vocabulary line: $line")
                }
            }
            require(source.size > FIRST_SYMBOL && target.size > FIRST_SYMBOL) { "empty vocabulary" }
            return IndicXlitVocab(source, target)
        }

        /**
         * The readings in a packed result of [XlitNative.nativeTransliterate]:
         * per reading its token count, the bits of its float score, then its
         * ids. Best first, as the search left them.
         */
        fun unpack(packed: IntArray, vocab: IndicXlitVocab): List<Pair<String, Float>> {
            val out = ArrayList<Pair<String, Float>>()
            var i = 0
            while (i + 1 < packed.size) {
                val n = packed[i]
                val score = Float.fromBits(packed[i + 1])
                if (n < 0 || i + 2 + n > packed.size) break
                out += vocab.decode(packed, i + 2, n) to score
                i += 2 + n
            }
            return out
        }
    }
}
