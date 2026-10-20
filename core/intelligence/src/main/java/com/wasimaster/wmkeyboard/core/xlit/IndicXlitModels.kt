package com.wasimaster.wmkeyboard.core.xlit

import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import java.io.File

/**
 * The files behind neural suggestions on the Indic phonetic layouts:
 * AI4Bharat's IndicXlit v1.0 (MIT), English letters to 21 Indic scripts,
 * exported to ONNX and quantised to int8 by `scripts/export_indicxlit.py` in
 * the data repository and published as assets of its `models` release under
 * `<sha256>-<name>`, like [com.wasimaster.wmkeyboard.core.semantic.SemanticModels].
 *
 * One model serves every language, so there is one pack of three files
 * whichever languages have it on. Sizes and digests are the published
 * files'; a download is checked against them before it gets its name, so the
 * address can be any mirror.
 */
object IndicXlitModels {

    /** The data repository's release the files are assets of. */
    const val RELEASE_TAG = "models"

    enum class Part(val fileName: String, val bytes: Long, val sha256: String) {
        ENCODER(
            "indicxlit-encoder.int8.onnx", 6_072_771L,
            "c94cd7a2e048da5d6d7f3f46e00dce13a654cb4db7afdeb03d068d730da48ce8",
        ),
        DECODER(
            "indicxlit-decoder.int8.onnx", 7_070_073L,
            "ba94caddf41ca54202f4a439677d7a3034f8ed9f32c3ae993d87c6cda3c3140d",
        ),
        VOCAB(
            "indicxlit-vocab.txt", 5_243L,
            "313b400545b4b08341ad10c33e2d73baaefb194c18e2d74bcdf00392efcdbea5",
        ),
    }

    /** Every byte of the pack, for the settings row. */
    val BYTES: Long = Part.entries.sumOf { it.bytes }

    /**
     * The keyboard's phonetic languages the model writes, with the Unicode
     * block its readings are kept to. The model's output vocabulary is every
     * script's letters at once; a reading for Bengali may only use Bengali's.
     * Urdu also allows the Arabic presentation forms, which its training text
     * has.
     */
    private val BLOCKS: Map<String, List<IntRange>> = mapOf(
        "bn" to listOf(0x0980..0x09FF),
        "as" to listOf(0x0980..0x09FF),
        "hi" to listOf(0x0900..0x097F),
        "mr" to listOf(0x0900..0x097F),
        "pa" to listOf(0x0A00..0x0A7F),
        "gu" to listOf(0x0A80..0x0AFF),
        "or" to listOf(0x0B00..0x0B7F),
        "ta" to listOf(0x0B80..0x0BFF),
        "te" to listOf(0x0C00..0x0C7F),
        "kn" to listOf(0x0C80..0x0CFF),
        "ml" to listOf(0x0D00..0x0D7F),
        "ur" to listOf(0x0600..0x06FF, 0xFB50..0xFEFF),
    )

    /** The languages a phonetic layout can ask the model about. */
    val LANGUAGES: Set<String> = BLOCKS.keys

    fun supports(languageId: String): Boolean = languageId in BLOCKS

    /**
     * The languages where the model may also choose the space bar's word when
     * the rules' reading is not a known word. Measured with the spelling map on
     * (top-1, Aksharantar test words): Bengali 25.0% to 56.4%, Hindi 40.9% to
     * 48.6%, and chat spellings the map lacks 41.8% to 52.8% and 78.2% to 80.1%.
     * Urdu gained one point (29.1% to 30.1%), not worth what the swap costs.
     */
    val LEADS: Set<String> = setOf("bn", "hi")

    fun canLead(languageId: String): Boolean = languageId in LEADS

    /** Whether [c] may be in a reading for [languageId]: its block, or a joiner. */
    fun writes(languageId: String, c: Char): Boolean =
        c == '‌' || c == '‍' || BLOCKS[languageId].orEmpty().any { c.code in it }

    /** Where the files live. Not backed up: they come back with one download. */
    fun dir(noBackupFilesDir: File): File = File(noBackupFilesDir, "xlit/models")

    fun file(noBackupFilesDir: File, part: Part): File = File(dir(noBackupFilesDir), part.fileName)

    fun partFile(noBackupFilesDir: File, part: Part): File = File(dir(noBackupFilesDir), part.fileName + ".part")

    /** Whole when every file is there at its pinned length; the digest was checked before it got its name. */
    fun isDownloaded(noBackupFilesDir: File): Boolean = Part.entries.all {
        file(noBackupFilesDir, it).let { f -> f.isFile && f.length() == it.bytes }
    }

    /** Every byte of the pack on disk now, for the storage screen. */
    fun bytesOnDisk(noBackupFilesDir: File): Long =
        Part.entries.sumOf { file(noBackupFilesDir, it).takeIf(File::isFile)?.length() ?: 0L }

    /** The release asset for [part], or null when the data repository is not on GitHub. */
    fun url(part: Part): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${part.sha256}-${part.fileName}"
    }
}
