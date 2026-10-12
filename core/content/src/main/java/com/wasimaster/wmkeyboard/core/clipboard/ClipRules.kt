package com.wasimaster.wmkeyboard.core.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One rule that tidies a copy before the history keeps it (#612): every match
 * of [pattern], a case-insensitive regular expression, is replaced with
 * [replacement]. An empty replacement removes the match, which is what most
 * rules are for: `https?://(www\.)?` takes the front off every address.
 * [replacement] may name the pattern's groups (`$1`) to add to a match
 * instead of only taking from it.
 */
@Serializable
data class ClipCopyRule(
    val pattern: String,
    val replacement: String = "",
    val enabled: Boolean = true,
)

/** Applies [ClipCopyRule]s to copied text, and stores them as one string. */
object ClipCopyRules {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ClipCopyRule.serializer())

    /** [rule]'s pattern compiled, or null while it does not compile or is empty. */
    fun compile(rule: ClipCopyRule): Regex? =
        rule.pattern.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }

    /**
     * [text] with every enabled rule applied in order, each to what the one
     * before it left. A rule that does not compile, or whose replacement names
     * a group the pattern does not have, is skipped rather than failing the
     * copy. The result is trimmed, so a rule that removes the front of a clip
     * does not leave the space that stood after it.
     */
    fun apply(text: String, rules: List<ClipCopyRule>): String {
        var out = text
        for (rule in rules) {
            if (!rule.enabled) continue
            val regex = compile(rule) ?: continue
            out = runCatching { regex.replace(out, rule.replacement) }.getOrDefault(out)
        }
        return if (out == text) text else out.trim()
    }

    fun encode(rules: List<ClipCopyRule>): String = json.encodeToString(serializer, rules)

    /** The rules [stored] holds; empty for nothing stored or a string that does not parse. */
    fun decode(stored: String?): List<ClipCopyRule> =
        stored?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()
}

/**
 * Several clips joined into one (#612).
 *
 * Plainly, the clips' texts with [joiner] between them. With [append], a
 * regular expression, each clip gives only the first part of it that matches,
 * and a clip with no match is left out; [base] then goes in front of the
 * lot. Joining `https://m.youtube.com/watch?v=Z7pkvfRl5S8` and
 * `https://m.youtube.com/watch?v=A1bcdeFg2H3` with base `youtube.com/watch?v`,
 * append `=[a-zA-Z0-9_]{11}` and joiner `; ` gives
 * `youtube.com/watch?v=Z7pkvfRl5S8; =A1bcdeFg2H3`: the joiner goes between
 * the parts, never before the first.
 */
object ClipCombine {

    /**
     * The joined text, or null when there is nothing to join: no clip had a
     * match, or [append] is not a pattern that compiles. See [ClipCombine].
     */
    fun combine(texts: List<String>, joiner: String, base: String = "", append: String = ""): String? {
        val parts = if (append.isEmpty()) {
            texts
        } else {
            val pattern = appendPattern(append) ?: return null
            texts.mapNotNull { text -> pattern.find(text)?.value }
        }
        val kept = parts.filter { it.isNotEmpty() }
        if (kept.isEmpty()) return null
        return base + kept.joinToString(joiner)
    }

    /** [append] compiled, or null when it does not compile. */
    fun appendPattern(append: String): Regex? =
        runCatching { Regex(append) }.getOrNull()
}
