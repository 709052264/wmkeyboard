package com.wasimaster.wmkeyboard.core.tools

/**
 * What search by meaning hands the AI tools: clips for `search_clipboard`,
 * notes and snippets for the meaning halves of `search_notes` and
 * `search_snippets`. The search itself is the keyboard module's (search by
 * meaning lives there), lent through [AiToolHost.searchClipboard],
 * [AiToolHost.notesByMeaning] and [AiToolHost.snippetsByMeaning]; the words
 * the model reads are made here.
 */
enum class AiMeaningSource(val label: String) {
    CLIPBOARD("clipboard"),
    NOTE("note"),
    SNIPPET("snippet"),
}

/**
 * One thing found. [title] is a note's title or a snippet's name, null for a
 * clip; [text] is
 * already cut short and on one line; [score] is the cosine the match was
 * ranked by, best near 1; [id] is the item's id in its store, so a caller can
 * tell a meaning match from one it already found by words.
 */
data class AiMeaningHit(
    val source: AiMeaningSource,
    val title: String?,
    val text: String,
    val score: Float,
    val id: String? = null,
)

object AiMeaningFormat {

    /** The most hits one answer lists: enough to choose from, short enough for a small model. */
    const val MAX_HITS = 8

    /** How much of one item the model is shown. */
    const val EXCERPT_CHARS = 280

    const val NOTHING = "Nothing on the clipboard matches that."

    /**
     * [hits] as the model reads them, best first: a numbered line each, its
     * source in brackets, a title when it has one, and the excerpt.
     */
    fun format(hits: List<AiMeaningHit>): String {
        if (hits.isEmpty()) return NOTHING
        return hits.take(MAX_HITS).mapIndexed { k, hit ->
            val title = hit.title?.takeIf { it.isNotBlank() }?.let { "${oneLine(it, TITLE_CHARS)}: " }.orEmpty()
            "${k + 1}. [${hit.source.label}] $title${hit.text}"
        }.joinToString("\n")
    }

    /**
     * [text] on one line and at most [max] characters, cut at a word where
     * there is one near the end, with an ellipsis when anything went.
     */
    fun excerpt(text: String, max: Int = EXCERPT_CHARS): String = oneLine(text, max)

    private fun oneLine(text: String, max: Int): String {
        val flat = text.replace(WHITESPACE, " ").trim()
        if (flat.length <= max) return flat
        val cut = flat.take(max - 1)
        val space = cut.lastIndexOf(' ')
        val head = if (space >= max * 2 / 3) cut.substring(0, space) else cut
        return head.trimEnd() + "…"
    }

    private const val TITLE_CHARS = 80
    private val WHITESPACE = Regex("\\s+")
}
