package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * One downloadable context model: a `.wmlm` for a language (or a pair of
 * languages that are typed together), hosted in the data repo beside the
 * language's word lists as `data/<repoCode>/<stem>.wmlm`.
 */
class ContextModelEntry(
    /** The language id the model serves; a model for two languages is listed once per language. */
    val languageId: String,
    /** Directory under `data/` in the repo. */
    val repoCode: String,
    /** File stem, e.g. `en_context` -> `data/en/en_context.wmlm`. */
    val stem: String,
    /** The file's size, for the prompt and the storage screen. */
    val approxBytes: Long,
) {
    val fileName: String get() = "$stem.wmlm"

    fun url(): String = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$repoCode/$stem.wmlm")
}

/**
 * Every language with a published context model. Models are trained by
 * `tools/wmlm` (see its README) and published to the data repo; a language
 * that is not here has none to offer and keeps its n-gram stores alone.
 */
object ContextModelCatalog {

    val entries: List<ContextModelEntry> = listOf(
        ContextModelEntry(languageId = "en", repoCode = "en", stem = "en_context", approxBytes = 4_900_000),
        // Banglish and English in one model (tools/wmlm, `--langs=en,bn_rom`):
        // the two are typed in one sentence, so one model reads both.
        ContextModelEntry(languageId = "bn_rom", repoCode = "bn", stem = "bn_rom_context", approxBytes = 5_200_000),
    )

    private val byLanguage = entries.associateBy { it.languageId }

    fun forLanguage(languageId: String): ContextModelEntry? = byLanguage[languageId]
}
