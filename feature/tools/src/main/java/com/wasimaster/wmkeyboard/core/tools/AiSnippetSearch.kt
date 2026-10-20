package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.snippets.Snippet

/**
 * What the `search_snippets` tool reads back: the snippets a query finds by
 * their words, then the ones search by meaning finds that the words did not
 * ([AiToolHost.snippetsByMeaning]), each shown the same way: its name, its
 * trigger and its text.
 *
 * A snippet's text is a template. `{date}`, `{clip}`, `{random:…}` or a
 * pattern's `$1` are filled in only when the keyboard expands it, so the
 * model reads them as written. Expanding one here would hand the model the
 * clipboard, or today's date on a text nobody is typing.
 */
object AiSnippetSearch {

    /** The most snippets one half of an answer lists. */
    const val MAX_SNIPPETS = 5

    /** How much of one expansion the model is shown. */
    const val TEXT_CHARS = 600

    /** How many of a snippet's other expansions are shown. */
    const val MAX_ALTERNATES = 3

    const val NOTHING = "No snippets found."

    /**
     * The snippets with every word of [query] somewhere in their name,
     * triggers, tags or texts, those whose name or trigger is the query
     * first; for a blank query, the newest. At most [MAX_SNIPPETS].
     */
    fun byWords(snippets: List<Snippet>, query: String): List<Snippet> {
        val words = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return snippets.sortedWith(compareByDescending<Snippet> { it.createdAt }.thenByDescending { it.id })
                .take(MAX_SNIPPETS)
        }
        val phrase = words.joinToString(" ")
        return snippets.mapNotNull { snippet ->
            val names = listOf(snippet.label) + snippet.spellings() + snippet.tags
            val all = names + snippet.expansions() + listOfNotNull(snippet.triggerPattern)
            if (!words.all { word -> all.any { it.contains(word, ignoreCase = true) } }) return@mapNotNull null
            val rank = when {
                names.any { it.trim().equals(phrase, ignoreCase = true) } -> 0
                names.any { it.contains(phrase, ignoreCase = true) } -> 1
                else -> 2
            }
            rank to snippet
        }.sortedBy { it.first }.map { it.second }.take(MAX_SNIPPETS)
    }

    /**
     * The answer: [found] by words, then under "Close in meaning" the
     * [byMeaning] hits the words did not find, best first. A hit is read from
     * [snippets] as they are now, so one deleted since it was indexed is gone.
     */
    fun answer(found: List<Snippet>, byMeaning: List<AiMeaningHit>, snippets: List<Snippet>): String {
        val byWords = found.mapTo(HashSet()) { it.id.toString() }
        val live = snippets.associateBy { it.id.toString() }
        val close = byMeaning.mapNotNull { hit -> hit.id?.takeIf { it !in byWords }?.let(live::get) }
            .distinctBy { it.id }
            .take(MAX_SNIPPETS)
        if (found.isEmpty() && close.isEmpty()) return NOTHING
        val words = found.joinToString("\n\n", transform = ::describe)
        val meaning = close.takeIf { it.isNotEmpty() }?.joinToString("\n\n", prefix = "Close in meaning:\n", transform = ::describe)
        return listOf(words, meaning.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    /**
     * One snippet as the model reads it: its name and how it is typed on the
     * first line, then its text as written, cut short, and its other
     * expansions. A template says so, so the model does not take `{date}`
     * for the text.
     */
    fun describe(snippet: Snippet): String {
        val spellings = snippet.spellings()
        val trigger = when {
            snippet.triggerPattern != null -> "trigger pattern: ${snippet.triggerPattern}"
            spellings.isNotEmpty() -> "trigger: " + spellings.joinToString(", ") { "\"$it\"" }
            else -> null
        }
        val name = snippet.label.trim().ifEmpty { "(no name)" }
        val head = name + trigger?.let { " ($it)" }.orEmpty()
        val expansions = snippet.expansions()
        val template = snippet.triggerPattern != null || expansions.any { TEMPLATE.containsMatchIn(it) }
        return buildString {
            append(head)
            if (template) append("\n[a template: its {…} and \$n parts are filled in when it is typed]")
            append('\n').append(cut(snippet.text.trim()))
            val others = expansions.drop(1)
            for (other in others.take(MAX_ALTERNATES)) append("\nOr: ").append(cut(other.trim()))
            if (others.size > MAX_ALTERNATES) append("\n(and ${others.size - MAX_ALTERNATES} more)")
        }
    }

    private fun cut(text: String): String =
        if (text.length <= TEXT_CHARS) text else text.take(TEXT_CHARS - 1).trimEnd() + "…"

    private val WHITESPACE = Regex("\\s+")

    /** A `{variable}` (with an argument or an offset, as in `{date+86400:dd/MM}`) or a capture reference. */
    private val TEMPLATE = Regex("""\{[a-z][a-z0-9_]*(?:[+:-][^{}]*)?\}|\$\{?\d""")
}
