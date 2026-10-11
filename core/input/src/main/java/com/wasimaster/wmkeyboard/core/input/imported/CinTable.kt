package com.wasimaster.wmkeyboard.core.input.imported

/**
 * A `.cin` input table: the plain-text format of xcin, gcin, hime and
 * OpenVanilla, which is how Cangjie, Array, Boshiamy, Dayi, Wubi and a few
 * hundred other shape and sound tables are shared.
 *
 * ```
 * %ename array30
 * %cname 行列30
 * %selkey 1234567890
 * %keyname begin
 * a 1-
 * %keyname end
 * %chardef begin
 * cpu 溫
 * %chardef end
 * ```
 *
 * Read from the format's own published description; no table's data is
 * shipped. What the reader keeps: the names, the cap each key is drawn with
 * (`%keyname`), whether case matters (`%keep_key_case`), and the code to text
 * rows in file order, which is the order the table's author ranked them in.
 * `%selkey`, `%endkey` and `%space_style` describe a desktop's candidate window
 * and have no counterpart on the strip; they are read past.
 */
class CinTable private constructor(
    val englishName: String?,
    val localName: String?,
    /** Each key's cap, from `%keyname`: `a` → `日` on a Cangjie table. */
    val keyNames: Map<Char, String>,
    val keepCase: Boolean,
    /** Code and text, in the order the file gives them. */
    val rows: List<Pair<String, String>>,
    /** Rows dropped for running past [MAX_ROWS]. */
    val truncated: Boolean,
) {

    /** The characters a code is spelled with. */
    val alphabet: Set<Char> by lazy {
        val out = HashSet<Char>(keyNames.keys)
        for ((code, _) in rows) for (c in code) out += c
        out
    }

    val name: String get() = localName?.takeIf { it.isNotBlank() } ?: englishName.orEmpty()

    companion object {

        /** Whether [text] reads as a `.cin`: a `%chardef` section. */
        fun looksLikeCin(text: String): Boolean =
            text.lineSequence().take(SNIFF_LINES).any { it.trimStart().startsWith("%chardef") } ||
                text.contains("\n%chardef")

        /** Reads [text], or null when it has no rows at all. */
        fun parse(text: String): CinTable? {
            var ename: String? = null
            var cname: String? = null
            var keepCase = false
            val keyNames = LinkedHashMap<Char, String>()
            val rows = ArrayList<Pair<String, String>>()
            var section: String? = null
            var truncated = false
            for (raw in text.lineSequence()) {
                val line = raw.trim().trimStart('﻿')
                if (line.isEmpty()) continue
                if (line.startsWith('%')) {
                    val word = line.substring(1).substringBefore(' ').substringBefore('\t').trim()
                    val value = line.substring(1 + word.length).trim()
                    when (word) {
                        "keyname", "chardef" -> section = when (value) {
                            "begin" -> word
                            else -> null
                        }
                        "ename" -> ename = value.ifEmpty { null }
                        "cname", "tcname" -> if (cname == null) cname = value.ifEmpty { null }
                        "keep_key_case" -> keepCase = true
                        else -> if (section == null) Unit else {
                            // A row whose code starts with % inside a section
                            // (some symbol tables have one) is still a row.
                            addRow(line, section, keyNames, rows)
                        }
                    }
                    continue
                }
                if (line.startsWith('#')) continue
                when (section) {
                    "keyname" -> {
                        val (key, cap) = split(line) ?: continue
                        if (key.length == 1) keyNames[key[0]] = cap
                    }
                    "chardef" -> {
                        if (rows.size >= MAX_ROWS) {
                            truncated = true
                            continue
                        }
                        val (code, value) = split(line) ?: continue
                        rows += code to value
                    }
                    else -> Unit
                }
            }
            if (rows.isEmpty()) return null
            val folded = if (keepCase) {
                rows
            } else {
                rows.map { (code, value) -> code.lowercase() to value }
            }
            val names = if (keepCase) keyNames else keyNames.mapKeys { it.key.lowercaseChar() }
            return CinTable(ename, cname, names, keepCase, folded, truncated)
        }

        private fun addRow(
            line: String,
            section: String,
            keyNames: MutableMap<Char, String>,
            rows: MutableList<Pair<String, String>>,
        ) {
            val (a, b) = split(line) ?: return
            if (section == "keyname") {
                if (a.length == 1) keyNames[a[0]] = b
            } else if (rows.size < MAX_ROWS) {
                rows += a to b
            }
        }

        /** `code text`, split at the first run of blanks; the text may itself hold spaces. */
        private fun split(line: String): Pair<String, String>? {
            val cut = line.indexOfFirst { it == ' ' || it == '\t' }
            if (cut <= 0) return null
            val code = line.substring(0, cut)
            val value = line.substring(cut).trim()
            if (value.isEmpty()) return null
            return code to value
        }

        private const val SNIFF_LINES = 400

        /** Larger than any real table (the biggest Array and Cangjie tables run near 100k). */
        const val MAX_ROWS = 500_000
    }
}
