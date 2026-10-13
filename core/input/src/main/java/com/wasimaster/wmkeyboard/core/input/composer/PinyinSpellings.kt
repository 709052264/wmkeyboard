package com.wasimaster.wmkeyboard.core.input.composer

/**
 * The spellings of full pinyin that are not the syllable inventory's own,
 * worked out from that inventory: what [PinyinGraph] reads as units beside
 * the real syllables.
 *
 * Two tables, both derived rather than shipped, so they cannot disagree with
 * the inventory the rest of the composer reads:
 *
 *  - [aliases]: another way to spell a syllable, to the syllable it spells.
 *    Some are spelling conventions the inventory does not use — `lue` and
 *    `nue` for lüe and nüe (it spells ü as `v`), `jv`, `qv`, `xv` and `yv`
 *    for ju, qu, xu and yu, and the textbook long forms `liou`, `guei` and
 *    `duen` that pinyin contracts to liu, gui and dun. The rest are the slips
 *    libpinyin and libime forgive by default: `ng` typed `gn` (`bagn`) and
 *    `ong` typed without its `g` (`zhon`).
 *  - [completions]: the start of a syllable, two letters or more and not a
 *    syllable itself, to the syllables it begins. The graph reads the last
 *    stretch of the buffer through these, so `zhongguoren` is on the strip
 *    by `zhongguore` and `nia` already offers 年 (libime's "partial final").
 *
 * An alias is only ever a string that is not a syllable already: `lu` is lu,
 * never lü, and `on` stays out because no syllable is spelled that way to be
 * mistaken for it.
 */
object PinyinSpellings {

    class Tables(
        /** Alternative spelling → the syllable it stands for. */
        val aliases: Map<String, String>,
        /** Unfinished syllable → the syllables it can finish as, sorted. */
        val completions: Map<String, List<String>>,
    )

    private val EMPTY = Tables(emptyMap(), emptyMap())

    /** Built once per inventory: the composer asks on every keystroke. */
    @Volatile
    private var cache: Pair<Set<String>, Tables>? = null

    /** Letters a syllable's vowel part can contain, `v` standing for ü. */
    private const val VOWELS = "aeiouv"

    /** Longest prefix offered as unfinished: one short of `zhuang`. */
    private const val MAX_PARTIAL = 5

    /** How many syllables one unfinished stretch is read as, at most. */
    private const val MAX_COMPLETIONS = 8

    private val JQXY = setOf('j', 'q', 'x', 'y')

    fun of(valid: Set<String>): Tables {
        if (valid.isEmpty()) return EMPTY
        cache?.let { (forSet, tables) -> if (forSet === valid) return tables }
        val built = build(valid)
        cache = valid to built
        return built
    }

    fun build(valid: Set<String>): Tables {
        val aliases = HashMap<String, String>()
        fun alias(spelling: String, syllable: String) {
            if (spelling != syllable && spelling !in valid && spelling !in aliases) aliases[spelling] = syllable
        }
        for (s in valid.sorted()) {
            val initial = initialOf(s)
            val final = s.substring(initial.length)
            // ü: the inventory's `v` as `u` (lve → lue), and after j/q/x/y,
            // where the u is ü already, as `v` (ju → jv).
            if ("v" in final) alias(initial + final.replace('v', 'u'), s)
            if (initial.length == 1 && initial[0] in JQXY && final.startsWith("u")) alias(initial + "v" + final.substring(1), s)
            // The long forms pinyin contracts.
            if (final.endsWith("iu")) alias(initial + final.dropLast(2) + "iou", s)
            if (final == "ui") alias(initial + "uei", s)
            if (final == "un" && initial.isNotEmpty() && initial[0] !in JQXY) alias(initial + "uen", s)
            // Common slips.
            if (final.endsWith("ng")) alias(s.dropLast(2) + "gn", s)
            if (final.endsWith("ong")) alias(s.dropLast(1), s)
        }

        val completions = HashMap<String, MutableList<String>>()
        for (s in valid.sorted()) {
            for (len in 2..minOf(MAX_PARTIAL, s.length - 1)) {
                val prefix = s.substring(0, len)
                if (prefix in valid || prefix in aliases || prefix.none { it in VOWELS }) continue
                val list = completions.getOrPut(prefix) { ArrayList() }
                if (list.size < MAX_COMPLETIONS) list.add(s)
            }
        }
        return Tables(aliases, completions)
    }

    private fun initialOf(syllable: String): String = when {
        syllable.length >= 2 && syllable.substring(0, 2) in DIGRAPHS -> syllable.substring(0, 2)
        syllable.isNotEmpty() && syllable[0] !in VOWELS -> syllable.substring(0, 1)
        else -> ""
    }

    private val DIGRAPHS = setOf("zh", "ch", "sh")
}
