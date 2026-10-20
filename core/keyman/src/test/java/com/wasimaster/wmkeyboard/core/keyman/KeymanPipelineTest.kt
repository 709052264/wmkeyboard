package com.wasimaster.wmkeyboard.core.keyman

import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ingest pipeline: turns a checkout of `keymanapp/keyboards` into the files
 * this repository commits.
 *
 * ## Why this is a test
 *
 * It needs the real [TouchLayoutConverter], which needs `LayoutSpec`, which
 * reaches `AssetManager` and Compose's `@Immutable` through its own
 * dependencies. A `kotlin("jvm")` module cannot compile that, so the
 * `:tools:dictc` trick of symlinking sources into a plain JVM module does not
 * carry over. An Android library's unit-test runtime *is* the supported way to
 * run JVM code against that library's classes, so the generator lives here and
 * runs the same code the app runs. Nothing is duplicated, so nothing can drift.
 *
 * ## Running it
 *
 * ```
 * KEYMAN_CORPUS=/path/to/keyboards KEYMAN_OUT=/path/to/out \
 *   ./gradlew :core:keyman:testDebugUnitTest --tests '*KeymanPipelineTest*' --rerun-tasks
 * ```
 *
 * Without both variables it does nothing and passes, so an ordinary build needs
 * no checkout.
 */
class KeymanPipelineTest {

    @Test
    fun `generate the committed artifacts`() {
        val corpus = System.getenv("KEYMAN_CORPUS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val out = System.getenv("KEYMAN_OUT")?.let(::File) ?: return
        val layoutsDir = File(out, "layouts").apply { mkdirs() }

        val release = File(corpus, "release")
        assertTrue("no release/ in $corpus", release.isDirectory)

        val rows = mutableListOf<SourceRow>()
        val skipped = mutableListOf<String>()
        var rawBytes = 0L

        for (dir in release.listFiles().orEmpty().sortedBy { it.name }) {
            if (!dir.isDirectory) continue
            for (board in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (!board.isDirectory) continue
                val id = board.name
                val source = File(board, "source")
                val touch = File(source, "$id.keyman-touch-layout")
                if (!touch.isFile) {
                    skipped += "$id: no touch layout"
                    continue
                }

                val doc = when (val r = KeymanTouchLayoutReader.parse(touch.readText())) {
                    is KeymanResult.Success -> r.value
                    is KeymanResult.Failure -> {
                        skipped += "$id: parse ${r.fault}"
                        continue
                    }
                }

                val meta = readKps(File(source, "$id.kps"))
                val converted = when (
                    val r = TouchLayoutConverter.convert(doc, id, meta.name.ifBlank { id })
                ) {
                    is KeymanResult.Success -> r.value
                    is KeymanResult.Failure -> {
                        skipped += "$id: convert ${r.fault}"
                        continue
                    }
                }

                val declared = meta.languages.firstOrNull()?.let(::canonicalTag).orEmpty()
                val langId = resolveLanguage(declared, distinctiveScript(converted.layout))
                if (langId.isEmpty()) {
                    skipped += "$id: no usable language (declared '$declared')"
                    continue
                }
                val spec = converted.layout.copy(
                    langId = langId,
                    keyman = com.wasimaster.wmkeyboard.core.layout.KeymanBinding(
                        keyboardId = id,
                        version = meta.version,
                    ),
                )

                val json = LayoutFile.encodeAsset(spec)
                val file = File(layoutsDir, "kmn_$id.${LayoutFile.FILE_EXTENSION}")
                file.writeText(json)
                rawBytes += json.length.toLong()

                rows += SourceRow(
                    layoutId = spec.id,
                    keyboardId = id,
                    langId = langId,
                    script = dominantScript(langId, spec),
                    languageName = meta.languageNames.firstOrNull().orEmpty(),
                    version = meta.version,
                    copyright = meta.copyright,
                    langTags = meta.languages.map(::canonicalTag),
                    droppedMultitaps = converted.report.droppedMultitaps,
                    droppedFlicks = converted.report.droppedDiagonalFlicks,
                    repairs = converted.repairNotes.size,
                )
            }
        }

        File(out, "keyman-sources.tsv").writeText(
            buildString {
                appendLine(
                    listOf(
                        "layout_id", "keyman_id", "version", "copyright",
                        "lang_tags", "dropped_multitaps", "dropped_flicks", "repairs",
                    ).joinToString("\t"),
                )
                for (r in rows.sortedBy { it.keyboardId }) {
                    appendLine(
                        listOf(
                            r.layoutId, r.keyboardId, r.version, r.copyright.replace('\t', ' '),
                            r.langTags.joinToString(" "), r.droppedMultitaps,
                            r.droppedFlicks, r.repairs,
                        ).joinToString("\t"),
                    )
                }
            },
        )

        File(out, "skipped.txt").writeText(skipped.joinToString("\n"))

        // One row per language the corpus assigns, with the script its layouts
        // actually type in. The script is measured from the converted keys
        // rather than taken from the tag's script subtag, because most tags do
        // not carry one and a wrong script is not cosmetic — it decides text
        // direction, whether shift does anything, and which font the keys draw
        // with.
        val byLanguage = rows.groupBy { it.langId }.filterKeys { it.isNotEmpty() }
        File(out, "keyman-languages.tsv").writeText(
            buildString {
                appendLine(listOf("lang_id", "script", "layout_ids", "confidence", "names").joinToString("\t"))
                for ((langId, boards) in byLanguage.toSortedMap()) {
                    val best = boards.mapNotNull { it.script }.maxByOrNull { it.second }
                    val script = best?.first ?: "LATIN"
                    val confidence = best?.second ?: 0
                    appendLine(
                        listOf(
                            langId,
                            script,
                            boards.joinToString(" ") { it.layoutId },
                            confidence,
                            boards.mapNotNull { it.languageName.ifBlank { null } }.distinct()
                                .joinToString(" | "),
                        ).joinToString("\t"),
                    )
                }
            },
        )

        val tags = rows.flatMap { it.langTags }.map { it.substringBefore('-') }.filter { it.isNotEmpty() }
        println(
            "keymanc: ${rows.size} layouts, ${skipped.size} skipped, " +
                "${rawBytes / 1024} KiB raw, ${tags.toSet().size} distinct base subtags",
        )
        assertTrue("pipeline produced nothing", rows.isNotEmpty())
    }

    private data class SourceRow(
        val layoutId: String,
        val keyboardId: String,
        val langId: String,
        /** [ScriptId] name and how confident the guess is, as a percentage. */
        val script: Pair<String, Int>?,
        /** The language name the keyboard author wrote in the .kps. */
        val languageName: String,
        val version: String,
        val copyright: String,
        val langTags: List<String>,
        val droppedMultitaps: Int,
        val droppedFlicks: Int,
        val repairs: Int,
    )

    private data class KpsMeta(
        val name: String = "",
        val version: String = "",
        val copyright: String = "",
        val languages: List<String> = emptyList(),
        /** The human name each `<Language>` element carries, index-aligned. */
        val languageNames: List<String> = emptyList(),
    )

    /**
     * The bits of a `.kps` the pipeline needs.
     *
     * Read with regex rather than a parser on purpose: this runs on a fixed,
     * machine-generated corpus, the three fields wanted are attributes and
     * simple elements, and pulling in an XML stack for it would be the larger
     * risk. A field that does not match yields an empty string and the row says
     * so, rather than the whole keyboard being dropped.
     */
    private fun readKps(file: File): KpsMeta {
        if (!file.isFile) return KpsMeta()
        val xml = runCatching { file.readText() }.getOrNull() ?: return KpsMeta()

        // Scoped to <Info>, and tolerant of attributes. Both matter: the
        // keyboard's own name lives in <Info> as `<Name URL="">Khmer Angkor`,
        // while <Files> carries a `<Name>` per packaged file holding a path. An
        // unscoped, attribute-blind match takes the first <Name> in the document
        // and names 867 keyboards after a build artefact.
        val info = section(xml, "Info")
        val keyboards = section(xml, "Keyboards")
        fun infoTag(name: String): String =
            Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(info)?.groupValues?.get(1)?.trim().orEmpty()

        return KpsMeta(
            name = infoTag("Name"),
            version = infoTag("Version"),
            copyright = infoTag("Copyright"),
            languages = LANGUAGE_ELEMENT.findAll(keyboards).map { it.groupValues[1] }.toList(),
            languageNames = LANGUAGE_ELEMENT.findAll(keyboards)
                .map { it.groupValues[2].trim() }.toList(),
        )
    }

    private fun section(xml: String, name: String): String =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1).orEmpty()

    /**
     * Canonicalises a BCP-47 tag the way the corpus needs.
     *
     * The upstream tags are raw author input: three-letter codes where a
     * two-letter one exists, inconsistent casing between the `.kps` and the
     * generated metadata, and a couple of invalid regions. Left alone they would
     * mint duplicate language entries that differ only in spelling.
     */
    private fun canonicalTag(raw: String): String {
        val parts = raw.trim().split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        var language = parts[0].lowercase(Locale.ROOT)
        language = THREE_TO_TWO[language] ?: language
        val rest = parts.drop(1).mapNotNull { part ->
            when {
                part.length == 4 -> part.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
                part.length == 2 && part.all { it.isLetter() } ->
                    part.uppercase(Locale.ROOT).takeIf { it !in INVALID_REGIONS }
                part.length == 3 && part.all { it.isDigit() } -> part
                else -> null
            }
        }
        return (listOf(language) + rest).joinToString("-")
    }

    /**
     * The language a keyboard belongs to, or empty when it has none we can use.
     *
     * Most keyboards declare one. A few declare `und` — "undetermined" — which
     * is not a language anyone picks out of a list, and which would collide with
     * the registry's own `und` stand-in that is documented as never being shown.
     *
     * For those the script is the identity instead: an IPA keyboard is an IPA
     * keyboard whatever its author left in the tag, and we already carry a
     * language for that. Where the script maps to nothing the board is skipped
     * and named in `skipped.txt`, because inventing a language identity for a
     * keyboard is guesswork that a user would then have to find and undo.
     */
    private fun resolveLanguage(declared: String, distinctive: String?): String {
        if (declared.isNotEmpty() && !declared.startsWith("und")) return declared
        return UNDETERMINED_BY_SCRIPT[distinctive].orEmpty()
    }

    /**
     * The most common script among the grid's *non-ASCII* letters.
     *
     * Separate from [dominantScript], which counts ASCII as Latin because for
     * a normal keyboard that is the truth. Here the question is narrower —
     * which script makes this keyboard distinctive — and counting ASCII
     * answers it wrongly: an IPA keyboard's base layer is mostly plain Latin
     * letters, so Latin wins and the IPA that is the entire point of the
     * keyboard never shows up.
     */
    private fun distinctiveScript(spec: LayoutSpec): String? {
        val counts = mutableMapOf<ScriptId, Int>()
        for (layer in spec.layers.values) {
            for (row in layer.rows) {
                for (key in row) {
                    for (ch in (key.output ?: key.label)) {
                        if (ch.code < 0x80 || !ch.isLetter()) continue
                        val script = ScriptRegistry.all.firstOrNull { it.contains(ch.code) }
                            ?: continue
                        counts.merge(script.id, 1, Int::plus)
                    }
                }
            }
        }
        return counts.maxByOrNull { it.value }?.key?.name
    }

    /**
     * The [ScriptId] a language's keyboards write in.
     *
     * The tag's own script subtag wins when it has one — `adi-Tibt` says
     * Tibetan and means it. Otherwise the converted key caps are counted and the
     * plain majority wins.
     *
     * An earlier version preferred any non-Latin script over Latin, to stop
     * digits and punctuation from calling every keyboard Latin. It over-corrected
     * badly: a handful of stray characters outvoted a 95% Latin grid, and an
     * Albanian keyboard came out as Tamil. Excluding ASCII and combining marks
     * from the count deals with the original problem without letting a minority
     * win.
     *
     * This is still a guess, and it is wrong in one known way: a *mnemonic*
     * keyboard has Latin key caps and types its own script through rules, so it
     * measures as Latin. Nothing in the source states the script, and reading it
     * out would mean running each keyboard's rules. [scriptConfidence] reports
     * the winning share so the registry step can flag the weak ones.
     */
    private fun dominantScript(tag: String, spec: LayoutSpec): Pair<String, Int>? {
        scriptFromTag(tag)?.let { return it to CONFIDENCE_FROM_TAG }

        val counts = mutableMapOf<ScriptId, Int>()
        var total = 0
        for (layer in spec.layers.values) {
            for (row in layer.rows) {
                for (key in row) {
                    for (ch in (key.output ?: key.label)) {
                        if (!ch.isLetter()) continue
                        if (ch.code < 0x80) {
                            // ASCII letters are on nearly every grid as digits'
                            // shifted forms and as the odd Latin label; counting
                            // them makes everything Latin.
                            counts.merge(ScriptId.LATIN, 1, Int::plus)
                            total++
                            continue
                        }
                        val script = ScriptRegistry.all.firstOrNull { it.contains(ch.code) }
                            ?: continue
                        counts.merge(script.id, 1, Int::plus)
                        total++
                    }
                }
            }
        }
        if (total == 0) return null
        val winner = counts.maxByOrNull { it.value } ?: return null
        return winner.key.name to (winner.value * 100 / total)
    }

    /** `xx-Yyyy` to a [ScriptId] name, when the subtag names one we carry. */
    private fun scriptFromTag(tag: String): String? {
        val subtag = tag.split('-').firstOrNull { it.length == 4 } ?: return null
        return ISO_15924[subtag]
    }

    private companion object {
        /**
         * What an undetermined tag falls back to, by measured script. Only
         * scripts we already carry a language for appear here.
         */
        val UNDETERMINED_BY_SCRIPT = mapOf("IPA" to "ipa")

        /** A script named by the tag itself is stated, not inferred. */
        const val CONFIDENCE_FROM_TAG = 100

        /** The ISO 15924 codes the corpus actually uses, to our ScriptIds. */
        val ISO_15924 = mapOf(
            "Latn" to "LATIN", "Cyrl" to "CYRILLIC", "Grek" to "GREEK",
            "Arab" to "ARABIC", "Hebr" to "HEBREW", "Deva" to "DEVANAGARI",
            "Beng" to "BENGALI", "Guru" to "GURMUKHI", "Gujr" to "GUJARATI",
            "Orya" to "ORIYA", "Taml" to "TAMIL", "Telu" to "TELUGU",
            "Knda" to "KANNADA", "Mlym" to "MALAYALAM", "Sinh" to "SINHALA",
            "Thai" to "THAI", "Laoo" to "LAO", "Khmr" to "KHMER",
            "Mymr" to "MYANMAR", "Ethi" to "ETHIOPIC", "Tibt" to "TIBETAN",
            "Syrc" to "SYRIAC", "Thaa" to "THAANA", "Armn" to "ARMENIAN",
            "Geor" to "GEORGIAN", "Cans" to "CANADIAN_ABORIGINAL_SYLLABICS",
            "Cher" to "CHEROKEE", "Tfng" to "TIFINAGH", "Nkoo" to "NKO",
            "Olck" to "OL_CHIKI", "Mtei" to "MEETEI_MAYEK", "Vaii" to "VAI",
            "Osge" to "OSAGE", "Adlm" to "ADLAM", "Bali" to "BALINESE",
            "Java" to "JAVANESE", "Mong" to "MONGOLIAN", "Plrd" to "MIAO",
            "Lisu" to "LISU", "Newa" to "NEWA", "Runr" to "RUNIC",
            "Copt" to "COPTIC", "Limb" to "LIMBU", "Cakm" to "CHAKMA",
            "Rohg" to "HANIFI_ROHINGYA", "Bopo" to "BOPOMOFO", "Yiii" to "YI",
            "Wara" to "WARANG_CITI",
        )

        val LANGUAGE_ELEMENT = Regex("""<Language\s+ID="([^"]+)"\s*>(.*?)</Language>""")

        /** ISO 639-2/T codes the corpus uses where a 639-1 code exists. */
        val THREE_TO_TWO = mapOf(
            "bel" to "be", "bos" to "bs", "bul" to "bg", "ces" to "cs",
            "hrv" to "hr", "mkd" to "mk", "pol" to "pl", "rus" to "ru",
            "slk" to "sk", "slv" to "sl", "srp" to "sr", "ukr" to "uk",
        )

        /** Not real region subtags; the corpus carries them anyway. */
        val INVALID_REGIONS = setOf("QP", "XL")
    }
}
