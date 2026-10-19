package com.wasimaster.wmkeyboard.core.tools.yomichan

/**
 * Turns an inflected Japanese word back into the forms a dictionary lists
 * (#765): 食べました → 食べる, 行かなかった → 行く, 高くない → 高い.
 *
 * Each rule swaps an ending for another and says what kind of word it leaves
 * behind ([Kind]): a verb class, an i-adjective, or one of the in-between
 * forms that inflect again (the ます stem, the て form). Rules chain: 食べなかった
 * is かった → い (the negative is an adjective), then ない → る (an ichidan
 * verb). A rule only applies to a word of the kind it inflects, so the chain
 * cannot wander through combinations Japanese does not have.
 *
 * A candidate is only a guess: the dictionary confirms it by its own part of
 * speech ([matchesRules]), so 見る from 見た is accepted only where the entry
 * says it is an ichidan verb. Written from the grammar for this project; it
 * shares no code or tables with Yomichan or Yomitan.
 */
object JapaneseDeinflector {

    /** What a word is, as far as inflection goes. */
    enum class Kind {
        /** Ichidan verb (食べる, 見る). */
        V1,

        /** Godan verb (書く, 話す, 買う). */
        V5,

        /** 来る. */
        VK,

        /** する, and a noun that takes する (勉強). */
        VS,

        /** い-adjective (高い), and the forms that inflect like one: ない, たい. */
        ADJ_I,

        /** A polite ます form, which inflects on: ました, ません, ましょう. */
        MASU,

        /** A て form, which takes いる, しまう, おく and the rest. */
        TE,

        /** A form that inflects no further: past, conditional, volitional, imperative. */
        END,
    }

    /** Kinds a dictionary lists words under; the others are never a headword. */
    private val DICTIONARY_KINDS = setOf(Kind.V1, Kind.V5, Kind.VK, Kind.VS, Kind.ADJ_I)

    /**
     * One step back: a word ending in [from], of a kind in [inflects], comes
     * from the word ending in [to], which is of kind [leaves].
     */
    private class Rule(val from: String, val to: String, val inflects: Set<Kind>, val leaves: Kind)

    /**
     * A form the word could have come from. [kinds] is what it must be for the
     * chain to hold; null for the text exactly as it was found, which any
     * entry may match. [steps] is how many rules made it.
     */
    data class Candidate(val text: String, val kinds: Set<Kind>?, val steps: Int)

    private const val MAX_STEPS = 6
    private const val MAX_CANDIDATES = 200

    private val rules: List<Rule> by lazy { buildRules() }

    /**
     * Every form [word] could have come from, itself first, then by fewest
     * steps. Only a candidate whose [Candidate.kinds] the entry matches
     * ([matchesRules]) is a real one.
     */
    fun candidates(word: String): List<Candidate> {
        if (word.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>()
        val seen = HashSet<Pair<String, Set<Kind>?>>()
        out += Candidate(word, null, 0)
        seen += word to null
        var frontier = listOf(Candidate(word, null, 0))
        var step = 0
        while (frontier.isNotEmpty() && step < MAX_STEPS && out.size < MAX_CANDIDATES) {
            step++
            val next = ArrayList<Candidate>()
            for (candidate in frontier) {
                for (rule in rules) {
                    if (!candidate.text.endsWith(rule.from)) continue
                    val kinds = candidate.kinds
                    if (kinds != null && kinds.none { it in rule.inflects }) continue
                    val text = candidate.text.dropLast(rule.from.length) + rule.to
                    if (text.isEmpty()) continue
                    val made = Candidate(text, setOf(rule.leaves), step)
                    if (seen.add(made.text to made.kinds)) {
                        next += made
                        out += made
                    }
                }
            }
            frontier = next
        }
        return out
    }

    /**
     * Whether an entry whose part-of-speech rules are [entryRules] (Yomichan's
     * `rules` field, space-separated: `v1`, `v5r`, `vs`, `adj-i`…) can be the
     * word [candidate] asks for.
     */
    fun matchesRules(candidate: Candidate, entryRules: String): Boolean {
        val kinds = candidate.kinds ?: return true
        val wanted = kinds.filter { it in DICTIONARY_KINDS }
        if (wanted.isEmpty()) return false
        return entryRules.split(' ').any { token -> kindOf(token)?.let { it in wanted } == true }
    }

    /** The [Kind] a Yomichan rule token stands for, or null for one inflection does not care about. */
    internal fun kindOf(token: String): Kind? = when {
        token.startsWith("v1") -> Kind.V1
        token.startsWith("v5") -> Kind.V5
        token == "vk" -> Kind.VK
        token.startsWith("vs") || token == "vz" -> Kind.VS
        token == "adj-i" -> Kind.ADJ_I
        else -> null
    }

    /** A godan verb's row: its dictionary ending and the stems inflection builds on. */
    private class GodanRow(
        val base: String,
        val i: String,
        val a: String,
        val e: String,
        val o: String,
        val te: String,
        val ta: String,
    )

    private val GODAN = listOf(
        GodanRow("う", "い", "わ", "え", "お", "って", "った"),
        GodanRow("く", "き", "か", "け", "こ", "いて", "いた"),
        GodanRow("ぐ", "ぎ", "が", "げ", "ご", "いで", "いだ"),
        GodanRow("す", "し", "さ", "せ", "そ", "して", "した"),
        GodanRow("つ", "ち", "た", "て", "と", "って", "った"),
        GodanRow("ぬ", "に", "な", "ね", "の", "んで", "んだ"),
        GodanRow("ぶ", "び", "ば", "べ", "ぼ", "んで", "んだ"),
        GodanRow("む", "み", "ま", "め", "も", "んで", "んだ"),
        GodanRow("る", "り", "ら", "れ", "ろ", "って", "った"),
    )

    private fun buildRules(): List<Rule> {
        val out = ArrayList<Rule>()
        fun add(from: String, to: String, inflects: Kind, leaves: Kind) {
            out += Rule(from, to, setOf(inflects), leaves)
        }

        // Ichidan: the stem is the word without る.
        val v1 = Kind.V1
        add("ます", "る", Kind.MASU, v1)
        add("ない", "る", Kind.ADJ_I, v1)
        add("たい", "る", Kind.ADJ_I, v1)
        add("た", "る", Kind.END, v1)
        add("て", "る", Kind.TE, v1)
        add("れば", "る", Kind.END, v1)
        add("よう", "る", Kind.END, v1)
        add("ろ", "る", Kind.END, v1)
        add("よ", "る", Kind.END, v1)
        add("ず", "る", Kind.END, v1)
        add("たら", "る", Kind.END, v1)
        add("たり", "る", Kind.END, v1)
        add("なさい", "る", Kind.END, v1)
        add("そう", "る", Kind.END, v1)
        // Passive and potential, causative, the colloquial potential: each an ichidan verb itself.
        add("られる", "る", Kind.V1, v1)
        add("れる", "る", Kind.V1, v1)
        add("させる", "る", Kind.V1, v1)
        add("すぎる", "る", Kind.V1, v1)
        add("やすい", "る", Kind.ADJ_I, v1)
        add("にくい", "る", Kind.ADJ_I, v1)
        // てしまう's contractions are godan verbs.
        add("ちゃう", "る", Kind.V5, v1)

        // Godan, row by row.
        val v5 = Kind.V5
        for (row in GODAN) {
            val b = row.base
            add(row.i + "ます", b, Kind.MASU, v5)
            add(row.a + "ない", b, Kind.ADJ_I, v5)
            add(row.i + "たい", b, Kind.ADJ_I, v5)
            add(row.ta, b, Kind.END, v5)
            add(row.te, b, Kind.TE, v5)
            add(row.e + "ば", b, Kind.END, v5)
            add(row.o + "う", b, Kind.END, v5)
            add(row.e, b, Kind.END, v5)
            add(row.a + "ず", b, Kind.END, v5)
            add(row.ta + "ら", b, Kind.END, v5)
            add(row.ta + "り", b, Kind.END, v5)
            add(row.i + "なさい", b, Kind.END, v5)
            add(row.i + "そう", b, Kind.END, v5)
            add(row.a + "れる", b, Kind.V1, v5)
            add(row.e + "る", b, Kind.V1, v5)
            add(row.a + "せる", b, Kind.V1, v5)
            add(row.i + "すぎる", b, Kind.V1, v5)
            add(row.i + "やすい", b, Kind.ADJ_I, v5)
            add(row.i + "にくい", b, Kind.ADJ_I, v5)
            // て → ちゃう, で → じゃう.
            val contracted = if (row.te.endsWith("で")) row.te.dropLast(1) + "じゃう" else row.te.dropLast(1) + "ちゃう"
            add(contracted, b, Kind.V5, v5)
        }
        // 行く is the one く verb whose て and た forms are って and った.
        for (stem in listOf("行", "い")) {
            add(stem + "って", stem + "く", Kind.TE, v5)
            add(stem + "った", stem + "く", Kind.END, v5)
            add(stem + "ったら", stem + "く", Kind.END, v5)
        }

        // 来る, in kanji and in kana.
        val vk = Kind.VK
        for ((k, c, ki, ko) in listOf(listOf("来", "来", "来", "来"), listOf("く", "く", "き", "こ"))) {
            val base = c + "る"
            add(ki + "ます", base, Kind.MASU, vk)
            add(ko + "ない", base, Kind.ADJ_I, vk)
            add(ki + "たい", base, Kind.ADJ_I, vk)
            add(ki + "た", base, Kind.END, vk)
            add(ki + "て", base, Kind.TE, vk)
            add(k + "れば", base, Kind.END, vk)
            add(ko + "よう", base, Kind.END, vk)
            add(ko + "い", base, Kind.END, vk)
            add(ki + "たら", base, Kind.END, vk)
            add(ko + "られる", base, Kind.V1, vk)
            add(ko + "れる", base, Kind.V1, vk)
            add(ko + "させる", base, Kind.V1, vk)
        }

        // する.
        val vs = Kind.VS
        add("します", "する", Kind.MASU, vs)
        add("しない", "する", Kind.ADJ_I, vs)
        add("したい", "する", Kind.ADJ_I, vs)
        add("した", "する", Kind.END, vs)
        add("して", "する", Kind.TE, vs)
        add("すれば", "する", Kind.END, vs)
        add("しよう", "する", Kind.END, vs)
        add("しろ", "する", Kind.END, vs)
        add("せよ", "する", Kind.END, vs)
        add("せず", "する", Kind.END, vs)
        add("したら", "する", Kind.END, vs)
        add("される", "する", Kind.V1, vs)
        add("させる", "する", Kind.V1, vs)
        add("できる", "する", Kind.V1, vs)
        // 勉強する: the noun is what the dictionary lists, marked vs.
        add("する", "", Kind.VS, vs)

        // い-adjectives, and ない / たい, which inflect the same way.
        val adj = Kind.ADJ_I
        add("くない", "い", Kind.ADJ_I, adj)
        add("かった", "い", Kind.END, adj)
        add("かったら", "い", Kind.END, adj)
        add("くて", "い", Kind.END, adj)
        add("ければ", "い", Kind.END, adj)
        add("く", "い", Kind.END, adj)
        add("さ", "い", Kind.END, adj)
        add("そう", "い", Kind.END, adj)
        add("すぎる", "い", Kind.V1, adj)
        add("くなる", "い", Kind.V5, adj)
        add("がる", "い", Kind.V5, adj)

        // The polite forms go back to ます.
        add("ました", "ます", Kind.END, Kind.MASU)
        add("ません", "ます", Kind.END, Kind.MASU)
        add("ませんでした", "ます", Kind.END, Kind.MASU)
        add("ましょう", "ます", Kind.END, Kind.MASU)
        add("まして", "ます", Kind.END, Kind.MASU)

        // て form + auxiliary; each auxiliary inflects as its own verb.
        for (te in listOf("て", "で")) {
            add(te + "いる", te, Kind.V1, Kind.TE)
            add(te + "る", te, Kind.V1, Kind.TE)
            add(te + "しまう", te, Kind.V5, Kind.TE)
            add(te + "おく", te, Kind.V5, Kind.TE)
            add(te + "ある", te, Kind.V5, Kind.TE)
            add(te + "いく", te, Kind.V5, Kind.TE)
            add(te + "くる", te, Kind.VK, Kind.TE)
            add(te + "あげる", te, Kind.V1, Kind.TE)
            add(te + "くれる", te, Kind.V1, Kind.TE)
            add(te + "もらう", te, Kind.V5, Kind.TE)
            add(te + "みる", te, Kind.V1, Kind.TE)
            add(te + "ください", te, Kind.END, Kind.TE)
        }
        add("とく", "て", Kind.V5, Kind.TE)
        add("どく", "で", Kind.V5, Kind.TE)
        return out
    }
}
