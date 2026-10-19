package com.wasimaster.wmkeyboard.app

/**
 * What a file's own bytes say it is, for the formats that carry no tag of
 * ours and often arrive without their name: a chat app's cache, a browser's
 * `msf:` URI. Pure functions over a file's first bytes or its text, so
 * [WMFileTypes.identify] stays readable and every sniff is testable without a
 * `ContentResolver`.
 *
 * Each test is aimed at the format's own signature rather than at "looks
 * plausible": anything that matches here is offered for import, so a false
 * match is a wrong dialog and a missed one is only the editor.
 */
internal object FileSniff {

    /** Bytes [identify][WMFileTypes.identify] reads up front: enough for every header below. */
    const val HEAD_BYTES = 512

    /** A font the platform loads: TrueType, CFF OpenType, Apple's two tags, or a collection. */
    fun isFont(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val tag = ascii(head, 0, 4)
        return int32(head, 0) == 0x00010000 || tag == "OTTO" || tag == "true" || tag == "ttcf"
    }

    /**
     * Whether a font draws colour glyphs, which is what an emoji font is: one
     * of the colour tables named in its table directory. Fonts with colour
     * tables are emoji fonts in practice, and anything else is text.
     * A collection (`ttcf`) has no directory at the front and is never one.
     */
    fun isColourFont(head: ByteArray): Boolean {
        if (head.size < 12 || ascii(head, 0, 4) == "ttcf") return false
        val tables = ((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF)
        for (i in 0 until tables) {
            val at = 12 + i * 16
            if (at + 4 > head.size) break
            if (ascii(head, at, 4) in COLOUR_TABLES) return true
        }
        return false
    }

    /**
     * A key sound `SoundPool` plays: an MP3 (by its ID3 tag, or by a frame
     * sync when the name says `.mp3`, since two bytes alone match too much
     * binary), an Ogg or a WAV.
     */
    fun isSound(head: ByteArray, name: String): Boolean {
        if (head.size < 12) return false
        val tag = ascii(head, 0, 4)
        if (tag.startsWith("ID3") || tag == "OggS") return true
        if (tag == "RIFF" && ascii(head, 8, 4) == "WAVE") return true
        val sync = (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0
        return sync && name.endsWith(".mp3", ignoreCase = true)
    }

    /** An SQLite database, which is what Emotic's older export is. */
    fun isSqlite(head: ByteArray): Boolean = head.size >= 16 && ascii(head, 0, 15) == "SQLite format 3"

    /** A tar archive, by the `ustar` magic every POSIX tar writes at 257. */
    fun isTar(head: ByteArray): Boolean = head.size >= 262 && ascii(head, 257, 5) == "ustar"

    /**
     * An emoji keyword pack in its TSV shape: rows of an emoji, a tab, then
     * its keywords. Most rows have to start that way, and there have to be a
     * few of them, because a stray emoji at the head of a note is not a pack.
     */
    fun looksLikeEmojiKeywords(text: String): Boolean {
        var rows = 0
        var matching = 0
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("# ")) continue
            rows++
            val tab = line.indexOf('\t')
            if (tab in 1..MAX_EMOJI_CHARS && isEmojiCell(line.substring(0, tab).trim())) matching++
            if (rows >= SAMPLE_ROWS) break
        }
        return matching >= MIN_EMOJI_ROWS && matching * 10 >= rows * 9
    }

    /** The data repository's JSON shape of the same pack: rows naming an `emoji` and its `keywords`. */
    fun looksLikeEmojiKeywordJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("[") && trimmed.contains("\"emoji\"") && trimmed.contains("\"keywords\"")
    }

    /**
     * A FlorisBoard layout: JSON naming its key types (`"$": "text_key"`), or
     * the bare `code` and `label` pairs a hand-written one uses. The reader
     * behind it takes any array of arrays, so this is the gate that keeps it
     * from claiming every JSON file.
     */
    fun looksLikeFlorisLayout(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return false
        return FLORIS_KEY_TYPE.containsMatchIn(trimmed) ||
            (FLORIS_CODE.containsMatchIn(trimmed) && trimmed.contains("\"label\""))
    }

    /**
     * An input method table: an m17n `(input-method`, a `.cin` `%chardef`, or
     * a Rime schema or dictionary, which start with the YAML keys they are
     * named after.
     */
    fun looksLikeInputMethod(text: String, name: String): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".mim") || lower.endsWith(".cin")) return true
        if (lower.endsWith(".schema.yaml") || lower.endsWith(".dict.yaml")) return true
        if (text.contains("(input-method")) return true
        if (text.contains("%chardef begin") || text.contains("%chardef  begin")) return true
        return RIME_SCHEMA.containsMatchIn(text) && text.contains("engine:")
    }

    private fun isEmojiCell(cell: String): Boolean {
        if (cell.isEmpty()) return false
        var i = 0
        var symbol = false
        while (i < cell.length) {
            val cp = cell.codePointAt(i)
            when {
                Character.isLetterOrDigit(cp) && cp < 0x2000 -> return false
                cp >= 0x1F000 || cp in 0x2190..0x2BFF || cp in 0x3030..0x303D || cp in 0x3297..0x3299 -> symbol = true
                Character.getType(cp) == Character.OTHER_SYMBOL.toInt() -> symbol = true
            }
            i += Character.charCount(cp)
        }
        return symbol
    }

    private fun ascii(bytes: ByteArray, from: Int, length: Int): String =
        if (from + length > bytes.size) "" else String(bytes, from, length, Charsets.ISO_8859_1)

    private fun int32(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    /** The tables that carry colour glyphs: Google's bitmaps, Microsoft's layers, Apple's images, SVG. */
    private val COLOUR_TABLES = setOf("CBDT", "COLR", "sbix", "SVG ")

    private val FLORIS_KEY_TYPE = Regex(""""\$"\s*:\s*"[a-z_]*key"""")
    private val FLORIS_CODE = Regex(""""code"\s*:\s*-?\d+""")
    private val RIME_SCHEMA = Regex("""(?m)^schema:\s*$""")

    private const val MAX_EMOJI_CHARS = 16
    private const val SAMPLE_ROWS = 200
    private const val MIN_EMOJI_ROWS = 3
}
