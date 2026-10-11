package com.wasimaster.wmkeyboard.core.input.imported

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.ln

/**
 * Rime's spelling algebra: the rules a schema writes under `speller/algebra`
 * to turn the syllables in its dictionary into what people type for them.
 *
 * Each rule is an operation, a separator character and its fields:
 *
 * - `xform/pattern/replacement/`: the spelling becomes the replacement.
 * - `derive/…/…/`: the replacement becomes a second spelling of the syllable.
 * - `fuzz/…/…/`: as derive, for a spelling that is a guess.
 * - `abbrev/…/…/`: as derive, for a spelling that is only a syllable's start.
 * - `erase/pattern/`: a spelling the whole of which matches is dropped.
 * - `xlit/abc/xyz/`: each character of the first field becomes the one at the
 *   same place in the second.
 *
 * Rules apply in order, each to every spelling the ones before it produced.
 * Patterns are Perl-style regular expressions, which `java.util.regex` reads
 * the same way for everything the published schemas use; a replacement's
 * `$1`, `${1}` and `\U…\E` are expanded here rather than by the library, whose
 * own syntax differs.
 */
class SpellingAlgebra private constructor(private val rules: List<Rule>) {

    enum class Kind { NORMAL, FUZZY, ABBREVIATION }

    /** One way to type a syllable. [penalty] is a log-probability, 0 or below. */
    data class Spelling(val text: String, val kind: Kind, val penalty: Double)

    /** The spellings of [syllable], best first. */
    fun spell(syllable: String): List<Spelling> {
        var set = LinkedHashMap<String, Spelling>()
        set[syllable] = Spelling(syllable, Kind.NORMAL, 0.0)
        for (rule in rules) {
            val next = LinkedHashMap<String, Spelling>()
            for (s in set.values) rule.apply(s) { add(next, it) }
            set = next
            if (set.size > MAX_SPELLINGS) break
        }
        return set.values.sortedWith(compareBy<Spelling> { it.kind.ordinal }.thenByDescending { it.penalty })
    }

    /** Every spelling of every one of [syllables], each with the syllables it can stand for. */
    fun project(syllables: Collection<String>): Map<String, List<Target>> {
        val out = HashMap<String, MutableList<Target>>()
        for (syllable in syllables) {
            for (s in spell(syllable)) {
                if (s.text.isEmpty()) continue
                out.getOrPut(s.text) { ArrayList() } += Target(syllable, s.kind, s.penalty)
            }
        }
        for (list in out.values) list.sortWith(compareBy<Target> { it.kind.ordinal }.thenByDescending { it.penalty })
        return out
    }

    /** A syllable a typed spelling can stand for. */
    data class Target(val syllable: String, val kind: Kind, val penalty: Double)

    private fun add(into: MutableMap<String, Spelling>, s: Spelling) {
        val had = into[s.text]
        if (had == null || s.kind.ordinal < had.kind.ordinal ||
            (s.kind == had.kind && s.penalty > had.penalty)
        ) {
            into[s.text] = s
        }
    }

    private sealed interface Rule {
        fun apply(s: Spelling, emit: (Spelling) -> Unit)
    }

    private class Transform(
        private val pattern: Pattern,
        private val replacement: String,
        private val keep: Boolean,
        private val kind: Kind?,
        private val penalty: Double,
    ) : Rule {
        override fun apply(s: Spelling, emit: (Spelling) -> Unit) {
            val changed = replaceAll(pattern, s.text, replacement)
            if (keep) emit(s)
            if (changed == null) {
                if (!keep) emit(s)
                return
            }
            if (changed.isEmpty()) return
            emit(Spelling(changed, kind ?: s.kind, s.penalty + penalty))
        }
    }

    private class Erase(private val pattern: Pattern) : Rule {
        override fun apply(s: Spelling, emit: (Spelling) -> Unit) {
            if (!pattern.matcher(s.text).matches()) emit(s)
        }
    }

    private class Transliterate(private val from: String, private val to: String) : Rule {
        override fun apply(s: Spelling, emit: (Spelling) -> Unit) {
            emit(s.copy(text = transliterate(s.text, from, to)))
        }
    }

    /** A rule this cannot read, and why, for the import screen. */
    data class Problem(val rule: String)

    companion object {

        /** The algebra for [rules]; [problems] collects the ones that were skipped. */
        fun of(rules: List<String>, problems: MutableList<Problem> = ArrayList()): SpellingAlgebra {
            val parsed = ArrayList<Rule>(rules.size)
            for (rule in rules) {
                val r = parse(rule)
                if (r == null) problems += Problem(rule) else parsed += r
            }
            return SpellingAlgebra(parsed)
        }

        /**
         * The display rules of a `preedit_format`: only `xform` and `xlit`
         * mean anything there.
         */
        fun format(text: String, rules: List<String>): String {
            var out = text
            for (rule in rules) {
                val fields = fields(rule) ?: continue
                when (fields.first) {
                    "xform" -> {
                        val pattern = compile(fields.second.getOrNull(0) ?: continue) ?: continue
                        out = replaceAll(pattern, out, fields.second.getOrNull(1).orEmpty()) ?: out
                    }
                    "xlit" -> out = transliterate(out, fields.second.getOrNull(0).orEmpty(), fields.second.getOrNull(1).orEmpty())
                    else -> Unit
                }
            }
            return out
        }

        /**
         * The key caps a schema's formatting rules name: the first `xlit`
         * whose first field is lowercase keys, in `preedit_format` or
         * `comment_format`. A Cangjie schema maps `abc…` to `日月金…` this way.
         */
        fun keyNames(vararg ruleLists: List<String>): Map<Char, String> {
            for (rules in ruleLists) {
                for (rule in rules) {
                    val fields = fields(rule) ?: continue
                    if (fields.first != "xlit") continue
                    val from = fields.second.getOrNull(0).orEmpty()
                    val to = fields.second.getOrNull(1).orEmpty()
                    if (from.none { it.isLowerCase() }) continue
                    val toPoints = to.codePoints().toArray()
                    if (toPoints.size < from.length) continue
                    return from.withIndex().associate { (i, c) -> c to String(Character.toChars(toPoints[i])) }
                }
            }
            return emptyMap()
        }

        private fun parse(rule: String): Rule? {
            val (op, f) = fields(rule) ?: return null
            return when (op) {
                "xform", "derive", "fuzz", "abbrev" -> {
                    val pattern = compile(f.getOrNull(0) ?: return null) ?: return null
                    val replacement = f.getOrNull(1) ?: ""
                    when (op) {
                        "xform" -> Transform(pattern, replacement, keep = false, kind = null, penalty = 0.0)
                        "derive" -> Transform(pattern, replacement, keep = true, kind = null, penalty = 0.0)
                        "fuzz" -> Transform(pattern, replacement, keep = true, kind = Kind.FUZZY, penalty = GUESS_PENALTY)
                        else -> Transform(pattern, replacement, keep = true, kind = Kind.ABBREVIATION, penalty = GUESS_PENALTY)
                    }
                }
                "erase" -> Erase(compile(f.getOrNull(0) ?: return null) ?: return null)
                "xlit" -> {
                    val from = f.getOrNull(0) ?: return null
                    val to = f.getOrNull(1) ?: return null
                    if (from.codePointCount(0, from.length) != to.codePointCount(0, to.length)) return null
                    Transliterate(from, to)
                }
                else -> null
            }
        }

        /** `op` + separator + fields: `xform/a/b/` or `xlit|abc|xyz|` or `xlit abc xyz`. */
        private fun fields(rule: String): Pair<String, List<String>>? {
            val trimmed = rule.trim()
            val opEnd = trimmed.indexOfFirst { !it.isLetter() }
            if (opEnd <= 0 || opEnd >= trimmed.length) return null
            val op = trimmed.substring(0, opEnd)
            val separator = trimmed[opEnd]
            val rest = trimmed.substring(opEnd + 1)
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < rest.length) {
                val c = rest[i]
                when {
                    // A backslash before the separator writes the separator.
                    c == '\\' && i + 1 < rest.length && rest[i + 1] == separator && separator != '\\' -> {
                        current.append(separator)
                        i += 2
                        continue
                    }
                    c == separator -> {
                        parts += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotEmpty()) parts += current.toString()
            return op to parts
        }

        private fun compile(regex: String): Pattern? = try {
            Pattern.compile(regex)
        } catch (_: PatternSyntaxException) {
            null
        }

        /** Every match of [pattern] in [text] replaced, or null when nothing matched. */
        internal fun replaceAll(pattern: Pattern, text: String, replacement: String): String? {
            val m = pattern.matcher(text)
            val out = StringBuilder()
            var found = false
            var last = 0
            var from = 0
            while (from <= text.length && m.find(from)) {
                found = true
                out.append(text, last, m.start())
                out.append(expand(replacement, m))
                last = m.end()
                // An empty match moves on by one character, or it would match
                // in the same place forever; that character is copied with the
                // next stretch of plain text.
                from = if (m.end() == m.start()) m.end() + 1 else m.end()
            }
            if (!found) return null
            out.append(text, last, text.length)
            return out.toString()
        }

        /** Perl's replacement syntax: `$1`, `${1}`, `$&`, `\U` `\L` `\E`, and `\` before anything else. */
        private fun expand(replacement: String, m: Matcher): String {
            val out = StringBuilder()
            var case: Char? = null
            fun put(s: String) {
                out.append(
                    when (case) {
                        'U' -> s.uppercase()
                        'L' -> s.lowercase()
                        else -> s
                    },
                )
            }
            var i = 0
            while (i < replacement.length) {
                val c = replacement[i]
                when {
                    c == '$' && i + 1 < replacement.length && replacement[i + 1] == '{' -> {
                        val close = replacement.indexOf('}', i + 2)
                        val n = if (close > 0) replacement.substring(i + 2, close).toIntOrNull() else null
                        if (n != null && n <= m.groupCount()) {
                            put(m.group(n).orEmpty())
                            i = close + 1
                            continue
                        }
                        put("$")
                    }
                    c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit() -> {
                        // One digit, as Perl reads `$14` with one group: group 1 then "4".
                        var j = i + 1
                        var n = 0
                        while (j < replacement.length && replacement[j].isDigit()) {
                            val next = n * 10 + (replacement[j] - '0')
                            if (next > m.groupCount() && j > i + 1) break
                            n = next
                            j++
                        }
                        if (n <= m.groupCount()) put(m.group(n).orEmpty())
                        i = j
                        continue
                    }
                    c == '$' && i + 1 < replacement.length && replacement[i + 1] == '&' -> {
                        put(m.group())
                        i += 2
                        continue
                    }
                    c == '\\' && i + 1 < replacement.length -> {
                        when (val e = replacement[i + 1]) {
                            'U', 'L' -> case = e
                            'E' -> case = null
                            'n' -> put("\n")
                            't' -> out.append('\t')
                            else -> put(e.toString())
                        }
                        i += 2
                        continue
                    }
                    else -> put(c.toString())
                }
                i++
            }
            return out.toString()
        }

        private fun transliterate(text: String, from: String, to: String): String {
            val f = from.codePoints().toArray()
            val t = to.codePoints().toArray()
            if (f.isEmpty()) return text
            val out = StringBuilder()
            text.codePoints().forEach { cp ->
                val i = f.indexOf(cp)
                out.appendCodePoint(if (i >= 0 && i < t.size) t[i] else cp)
            }
            return out.toString()
        }

        /** What a guessed spelling costs: Rime ranks abbreviations and fuzzy ones behind real ones. */
        private val GUESS_PENALTY = ln(0.5)

        /** A syllable's spellings past this are a rule set that multiplies without end. */
        private const val MAX_SPELLINGS = 512
    }
}
