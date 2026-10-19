package com.wasimaster.wmkeyboard.core.tools.yomichan

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The pieces of a Yomichan / Yomitan dictionary (#765) this app reads, and
 * how each is read. The format is the published one: a zip holding
 * `index.json`, numbered `term_bank_N.json`, `term_meta_bank_N.json` and
 * `tag_bank_N.json` files, each a JSON array of rows. Kanji banks are not
 * read: the look-up card is about words.
 */
internal object YomichanFormat {

    /** What `index.json` says about the dictionary. */
    data class Index(
        val title: String,
        val revision: String,
        val format: Int,
        val author: String,
        val attribution: String,
        val sourceLanguage: String,
        val targetLanguage: String,
    )

    /** One `term_bank` row. [glossary] is already plain text, one line per item. */
    data class Term(
        val term: String,
        val reading: String,
        val definitionTags: String,
        val rules: String,
        val score: Int,
        val glossary: List<String>,
        val sequence: Int,
        val termTags: String,
    )

    /** One `term_meta_bank` row: a frequency, a pitch accent or an IPA line for [term]. */
    data class Meta(val term: String, val mode: String, val data: JsonElement)

    /** One `tag_bank` row: the short name a row carries, and what it stands for. */
    data class Tag(val name: String, val category: String, val notes: String)

    val TERM_BANK = Regex("""term_bank_\d+\.json""")
    val META_BANK = Regex("""term_meta_bank_\d+\.json""")
    val TAG_BANK = Regex("""tag_bank_\d+\.json""")
    const val INDEX = "index.json"

    fun index(root: JsonObject): Index? {
        val title = root.string("title")?.trim().orEmpty()
        if (title.isEmpty()) return null
        val format = root["format"]?.jsonPrimitive?.intOrNull ?: root["version"]?.jsonPrimitive?.intOrNull ?: 3
        return Index(
            title = title,
            revision = root.string("revision").orEmpty(),
            format = format,
            author = root.string("author").orEmpty(),
            attribution = root.string("attribution").orEmpty(),
            sourceLanguage = root.string("sourceLanguage").orEmpty(),
            targetLanguage = root.string("targetLanguage").orEmpty(),
        )
    }

    /**
     * A `term_bank` row, or null for one that is not a term. Format 1 spreads
     * the glossary over the row's remaining items; format 3 keeps it in one
     * array, followed by the sequence number and the term tags.
     */
    fun term(row: JsonElement, format: Int): Term? {
        val items = row as? JsonArray ?: return null
        if (items.size < 6) return null
        val term = items[0].text()?.trim().orEmpty()
        if (term.isEmpty()) return null
        val glossary = if (format == 1) {
            items.drop(5).flatMap { glossaryLines(it) }
        } else {
            (items[5] as? JsonArray).orEmpty().flatMap { glossaryLines(it) }
        }
        return Term(
            term = term,
            reading = items[1].text()?.trim().orEmpty(),
            definitionTags = items[2].text().orEmpty(),
            rules = items[3].text().orEmpty(),
            score = (items[4] as? JsonPrimitive)?.intOrNull ?: 0,
            glossary = glossary,
            sequence = if (format == 1) 0 else (items.getOrNull(6) as? JsonPrimitive)?.intOrNull ?: 0,
            termTags = if (format == 1) "" else items.getOrNull(7)?.text().orEmpty(),
        )
    }

    fun meta(row: JsonElement): Meta? {
        val items = row as? JsonArray ?: return null
        if (items.size < 3) return null
        val term = items[0].text()?.trim().orEmpty()
        val mode = items[1].text().orEmpty()
        if (term.isEmpty() || mode !in META_MODES) return null
        return Meta(term, mode, items[2])
    }

    private val META_MODES = setOf("freq", "pitch", "ipa")

    fun tag(row: JsonElement): Tag? {
        val items = row as? JsonArray ?: return null
        if (items.size < 4) return null
        val name = items[0].text()?.trim().orEmpty()
        if (name.isEmpty()) return null
        return Tag(name, items[1].text().orEmpty(), items[3].text().orEmpty())
    }

    /**
     * One glossary item as lines of text. A plain string is itself; a
     * `text` object is its text; `structured-content` is flattened
     * ([StructuredContent]); an image, and a deinflection pointer
     * (`[base, [rules]]`, which says what the word inflects from rather than
     * what it means), have no text to show.
     */
    fun glossaryLines(item: JsonElement): List<String> = when (item) {
        is JsonPrimitive -> listOfNotNull(item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        is JsonObject -> when (item.string("type")) {
            "text" -> listOfNotNull(item.string("text")?.trim()?.takeIf { it.isNotEmpty() })
            "structured-content" -> StructuredContent.lines(item["content"] ?: JsonNull)
            else -> emptyList()
        }
        else -> emptyList()
    }

    /**
     * A `freq` row's figure, for showing: a number, a string, a
     * `{value, displayValue}` object, or any of those under `frequency` beside
     * the `reading` it is for. Null when it is for another reading than [reading].
     */
    fun frequency(data: JsonElement, reading: String): String? {
        if (data is JsonObject && "frequency" in data) {
            val forReading = data.string("reading")
            if (!forReading.isNullOrEmpty() && reading.isNotEmpty() && forReading != reading) return null
            return frequencyValue(data["frequency"]!!)
        }
        return frequencyValue(data)
    }

    private fun frequencyValue(value: JsonElement): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> value.string("displayValue")?.takeIf { it.isNotBlank() }
            ?: (value["value"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    /** A `pitch` row's downstep positions for [reading] (0 = flat), or empty when it is for another reading. */
    fun pitch(data: JsonElement, reading: String): List<Int> {
        val obj = data as? JsonObject ?: return emptyList()
        val forReading = obj.string("reading").orEmpty()
        if (reading.isNotEmpty() && forReading.isNotEmpty() && forReading != reading) return emptyList()
        return (obj["pitches"] as? JsonArray).orEmpty().mapNotNull { pitch ->
            ((pitch as? JsonObject)?.get("position") as? JsonPrimitive)?.intOrNull
        }.distinct()
    }

    /** An `ipa` row's transcriptions for [reading]. */
    fun ipa(data: JsonElement, reading: String): List<String> {
        val obj = data as? JsonObject ?: return emptyList()
        val forReading = obj.string("reading").orEmpty()
        if (reading.isNotEmpty() && forReading.isNotEmpty() && forReading != reading) return emptyList()
        return (obj["transcriptions"] as? JsonArray).orEmpty().mapNotNull { transcription ->
            (transcription as? JsonObject)?.string("ipa")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
}

/**
 * Yomitan's structured content, as plain lines. It is HTML-like JSON: a node
 * is a string, an array of nodes, or an object with a `tag` and `content`.
 * Block tags (`div`, `li`, `tr`, `details`…) start a line; `li` gets a bullet;
 * table cells are joined with a bar; ruby keeps its reading in brackets after
 * the base; images and their captions have no text.
 */
internal object StructuredContent {

    private val BLOCK = setOf("div", "p", "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "details", "summary", "br")
    private val SKIP = setOf("img", "rp")

    fun lines(node: JsonElement): List<String> {
        val out = StringBuilder()
        append(out, node)
        return out.toString().lines().map { it.trim().replace(SPACES, " ") }.filter { it.isNotEmpty() }
    }

    private val SPACES = Regex("""[ \t]{2,}""")

    private fun append(out: StringBuilder, node: JsonElement) {
        when (node) {
            is JsonPrimitive -> node.contentOrNull?.let(out::append)
            is JsonArray -> node.forEach { append(out, it) }
            is JsonObject -> element(out, node)
        }
    }

    private fun element(out: StringBuilder, node: JsonObject) {
        val tag = (node["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (tag in SKIP) return
        val content = node["content"] ?: JsonNull
        when (tag) {
            "br" -> out.append('\n')
            "ruby" -> ruby(out, content)
            "rt" -> {
                out.append('(')
                append(out, content)
                out.append(')')
            }
            "li" -> {
                newline(out)
                out.append("• ")
                append(out, content)
                newline(out)
            }
            "td", "th" -> {
                if (out.isNotEmpty() && out.last() != '\n') out.append(" | ")
                append(out, content)
            }
            in BLOCK -> {
                newline(out)
                append(out, content)
                newline(out)
            }
            else -> append(out, content)
        }
    }

    /** `漢字(かんじ)`: the base, then the reading, so the line still reads in one pass. */
    private fun ruby(out: StringBuilder, content: JsonElement) {
        val parts = content as? JsonArray ?: JsonArray(listOf(content))
        for (part in parts) append(out, part)
    }

    private fun newline(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }
}
