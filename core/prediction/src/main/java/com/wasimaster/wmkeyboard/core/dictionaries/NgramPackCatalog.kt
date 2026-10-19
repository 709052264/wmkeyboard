package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * One downloadable n-gram pack: corpus bigram/trigram counts for a language,
 * hosted in the data repo next to its wordlists. Files are gzip text, one
 * `word... count` line each, count-descending — the same shape as every
 * other list the repo ships.
 */
class NgramPackEntry(
    /** Language id the pack serves ([com.wasimaster.wmkeyboard.core.language] registry). */
    val languageId: String,
    /** Directory under `data/` in the repo (languages share one, e.g. `bn`). */
    val repoCode: String,
    /** File stem, e.g. `bn_rom_bigrams` -> `data/bn/bn_rom_bigrams.txt.gz`. */
    val bigramStem: String,
    val trigramStem: String,
    /**
     * Roughly what the two files cost to fetch, compressed, for the prompt
     * that asks before downloading them.
     *
     * The *transferred* share, not the published size: the lists are
     * count-descending and the read stops at
     * [NgramPackDownloadManager]'s caps, so English sends about a quarter of
     * its bigram file and an eighth of its trigram one. A pack whose lists are
     * shorter than the caps is simply its full size.
     */
    val approxGzBytes: Long,
) {
    fun bigramUrl(): String = url(bigramStem)
    fun trigramUrl(): String = url(trigramStem)

    /** The two files' names in the data repo, and in a language pack. */
    val bigramFileName: String get() = "$bigramStem.txt.gz"
    val trigramFileName: String get() = "$trigramStem.txt.gz"

    /** The data repository, wherever [ServiceRepo.DATA] points. */
    private fun url(stem: String): String =
        ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$repoCode/$stem.txt.gz")
}

/**
 * Every language with a published n-gram pack: English, Bengali and romanized
 * Bengali, the 48 languages the data repo builds from Leipzig corpora, then
 * the ones it builds from Tatoeba and Wikipedia text.
 * A language id that is not here has no pack to offer.
 */
object NgramPackCatalog {

    val entries: List<NgramPackEntry> = listOf(
        NgramPackEntry(
            languageId = "en",
            repoCode = "en",
            bigramStem = "en_bigrams",
            trigramStem = "en_trigrams",
            approxGzBytes = 1_100_000,
        ),
        // Bengali script, for all three of its layouts. The words are spelled
        // the way `bn_full` spells them, which is NFC — and NFC leaves a nukta
        // decomposed (য + U+09BC), because U+09DF and its two siblings are
        // Unicode composition exclusions. Probhat and Jatiya type it that way
        // too. Avro does not: it commits the precomposed U+09DF, so a word it
        // transliterated will miss these pairs until the context word is
        // normalized on the way in.
        NgramPackEntry(
            languageId = "bn",
            repoCode = "bn",
            bigramStem = "bn_bigrams",
            trigramStem = "bn_trigrams",
            approxGzBytes = 1_820_000,
        ),
        // Banglish: bn_rom is its own first-class language (Latin script,
        // plain QWERTY, transliterated wordlist), so its romanized pairs
        // match that language's typing directly — the ordinary pipeline,
        // nothing Avro-specific. The files live under the repo's bn/ dir.
        NgramPackEntry(
            languageId = "bn_rom",
            repoCode = "bn",
            bigramStem = "bn_rom_bigrams",
            trigramStem = "bn_rom_trigrams",
            approxGzBytes = 315_000,
        ),
        // Everything below is built the same way from the Leipzig Corpora
        // Collection (data/README.md, "N-gram Lists") and already stops at
        // the read caps, so the published size is the transferred size.
        NgramPackEntry(
            languageId = "hi",
            repoCode = "hi",
            bigramStem = "hi_bigrams",
            trigramStem = "hi_trigrams",
            approxGzBytes = 1_522_473,
        ),
        NgramPackEntry(
            languageId = "ur",
            repoCode = "ur",
            bigramStem = "ur_bigrams",
            trigramStem = "ur_trigrams",
            approxGzBytes = 1_400_797,
        ),
        NgramPackEntry(
            languageId = "mr",
            repoCode = "mr",
            bigramStem = "mr_bigrams",
            trigramStem = "mr_trigrams",
            approxGzBytes = 1_703_233,
        ),
        NgramPackEntry(
            languageId = "gu",
            repoCode = "gu",
            bigramStem = "gu_bigrams",
            trigramStem = "gu_trigrams",
            approxGzBytes = 1_468_283,
        ),
        NgramPackEntry(
            languageId = "pa",
            repoCode = "pa",
            bigramStem = "pa_bigrams",
            trigramStem = "pa_trigrams",
            approxGzBytes = 1_398_798,
        ),
        NgramPackEntry(
            languageId = "or",
            repoCode = "or",
            bigramStem = "or_bigrams",
            trigramStem = "or_trigrams",
            approxGzBytes = 835_130,
        ),
        NgramPackEntry(
            languageId = "as",
            repoCode = "as",
            bigramStem = "as_bigrams",
            trigramStem = "as_trigrams",
            approxGzBytes = 1_054_959,
        ),
        NgramPackEntry(
            languageId = "ta",
            repoCode = "ta",
            bigramStem = "ta_bigrams",
            trigramStem = "ta_trigrams",
            approxGzBytes = 1_959_859,
        ),
        NgramPackEntry(
            languageId = "te",
            repoCode = "te",
            bigramStem = "te_bigrams",
            trigramStem = "te_trigrams",
            approxGzBytes = 1_811_653,
        ),
        NgramPackEntry(
            languageId = "kn",
            repoCode = "kn",
            bigramStem = "kn_bigrams",
            trigramStem = "kn_trigrams",
            approxGzBytes = 1_857_900,
        ),
        NgramPackEntry(
            languageId = "ml",
            repoCode = "ml",
            bigramStem = "ml_bigrams",
            trigramStem = "ml_trigrams",
            approxGzBytes = 1_757_180,
        ),
        NgramPackEntry(
            languageId = "fa",
            repoCode = "fa",
            bigramStem = "fa_bigrams",
            trigramStem = "fa_trigrams",
            approxGzBytes = 1_395_329,
        ),
        NgramPackEntry(
            languageId = "ar",
            repoCode = "ar",
            bigramStem = "ar_bigrams",
            trigramStem = "ar_trigrams",
            approxGzBytes = 1_545_166,
        ),
        NgramPackEntry(
            languageId = "es",
            repoCode = "es",
            bigramStem = "es_bigrams",
            trigramStem = "es_trigrams",
            approxGzBytes = 1_265_849,
        ),
        NgramPackEntry(
            languageId = "fr",
            repoCode = "fr",
            bigramStem = "fr_bigrams",
            trigramStem = "fr_trigrams",
            approxGzBytes = 1_327_303,
        ),
        NgramPackEntry(
            languageId = "de",
            repoCode = "de",
            bigramStem = "de_bigrams",
            trigramStem = "de_trigrams",
            approxGzBytes = 1_298_189,
        ),
        NgramPackEntry(
            languageId = "pt",
            repoCode = "pt",
            bigramStem = "pt_bigrams",
            trigramStem = "pt_trigrams",
            approxGzBytes = 1_320_108,
        ),
        NgramPackEntry(
            languageId = "ru",
            repoCode = "ru",
            bigramStem = "ru_bigrams",
            trigramStem = "ru_trigrams",
            approxGzBytes = 1_537_818,
        ),
        NgramPackEntry(
            languageId = "it",
            repoCode = "it",
            bigramStem = "it_bigrams",
            trigramStem = "it_trigrams",
            approxGzBytes = 1_286_750,
        ),
        NgramPackEntry(
            languageId = "tr",
            repoCode = "tr",
            bigramStem = "tr_bigrams",
            trigramStem = "tr_trigrams",
            approxGzBytes = 1_490_458,
        ),
        NgramPackEntry(
            languageId = "id",
            repoCode = "id",
            bigramStem = "id_bigrams",
            trigramStem = "id_trigrams",
            approxGzBytes = 1_351_056,
        ),
        NgramPackEntry(
            languageId = "vi",
            repoCode = "vi",
            bigramStem = "vi_bigrams",
            trigramStem = "vi_trigrams",
            approxGzBytes = 1_263_341,
        ),
        NgramPackEntry(
            languageId = "pl",
            repoCode = "pl",
            bigramStem = "pl_bigrams",
            trigramStem = "pl_trigrams",
            approxGzBytes = 1_451_563,
        ),
        NgramPackEntry(
            languageId = "nl",
            repoCode = "nl",
            bigramStem = "nl_bigrams",
            trigramStem = "nl_trigrams",
            approxGzBytes = 1_200_623,
        ),
        NgramPackEntry(
            languageId = "uk",
            repoCode = "uk",
            bigramStem = "uk_bigrams",
            trigramStem = "uk_trigrams",
            approxGzBytes = 1_728_943,
        ),
        NgramPackEntry(
            languageId = "ko",
            repoCode = "ko",
            bigramStem = "ko_bigrams",
            trigramStem = "ko_trigrams",
            approxGzBytes = 1_529_913,
        ),
        NgramPackEntry(
            languageId = "ro",
            repoCode = "ro",
            bigramStem = "ro_bigrams",
            trigramStem = "ro_trigrams",
            approxGzBytes = 1_309_077,
        ),
        NgramPackEntry(
            languageId = "el",
            repoCode = "el",
            bigramStem = "el_bigrams",
            trigramStem = "el_trigrams",
            approxGzBytes = 1_639_033,
        ),
        NgramPackEntry(
            languageId = "cs",
            repoCode = "cs",
            bigramStem = "cs_bigrams",
            trigramStem = "cs_trigrams",
            approxGzBytes = 1_382_871,
        ),
        NgramPackEntry(
            languageId = "hu",
            repoCode = "hu",
            bigramStem = "hu_bigrams",
            trigramStem = "hu_trigrams",
            approxGzBytes = 1_370_515,
        ),
        NgramPackEntry(
            languageId = "sv",
            repoCode = "sv",
            bigramStem = "sv_bigrams",
            trigramStem = "sv_trigrams",
            approxGzBytes = 1_236_131,
        ),
        NgramPackEntry(
            languageId = "he",
            repoCode = "he",
            bigramStem = "he_bigrams",
            trigramStem = "he_trigrams",
            approxGzBytes = 1_360_510,
        ),
        NgramPackEntry(
            languageId = "ms",
            repoCode = "ms",
            bigramStem = "ms_bigrams",
            trigramStem = "ms_trigrams",
            approxGzBytes = 1_388_479,
        ),
        NgramPackEntry(
            languageId = "sw",
            repoCode = "sw",
            bigramStem = "sw_bigrams",
            trigramStem = "sw_trigrams",
            approxGzBytes = 1_105_398,
        ),
        NgramPackEntry(
            languageId = "tl",
            repoCode = "tl",
            bigramStem = "tl_bigrams",
            trigramStem = "tl_trigrams",
            approxGzBytes = 1_134_474,
        ),
        NgramPackEntry(
            languageId = "ne",
            repoCode = "ne",
            bigramStem = "ne_bigrams",
            trigramStem = "ne_trigrams",
            approxGzBytes = 1_711_668,
        ),
        NgramPackEntry(
            languageId = "si",
            repoCode = "si",
            bigramStem = "si_bigrams",
            trigramStem = "si_trigrams",
            approxGzBytes = 1_603_499,
        ),
        NgramPackEntry(
            languageId = "az",
            repoCode = "az",
            bigramStem = "az_bigrams",
            trigramStem = "az_trigrams",
            approxGzBytes = 1_440_413,
        ),
        NgramPackEntry(
            languageId = "uz",
            repoCode = "uz",
            bigramStem = "uz_bigrams",
            trigramStem = "uz_trigrams",
            approxGzBytes = 1_302_778,
        ),
        NgramPackEntry(
            languageId = "kk",
            repoCode = "kk",
            bigramStem = "kk_bigrams",
            trigramStem = "kk_trigrams",
            approxGzBytes = 1_455_744,
        ),
        NgramPackEntry(
            languageId = "da",
            repoCode = "da",
            bigramStem = "da_bigrams",
            trigramStem = "da_trigrams",
            approxGzBytes = 1_237_543,
        ),
        NgramPackEntry(
            languageId = "fi",
            repoCode = "fi",
            bigramStem = "fi_bigrams",
            trigramStem = "fi_trigrams",
            approxGzBytes = 1_389_457,
        ),
        NgramPackEntry(
            languageId = "nb",
            repoCode = "nb",
            bigramStem = "nb_bigrams",
            trigramStem = "nb_trigrams",
            approxGzBytes = 1_226_063,
        ),
        NgramPackEntry(
            languageId = "sk",
            repoCode = "sk",
            bigramStem = "sk_bigrams",
            trigramStem = "sk_trigrams",
            approxGzBytes = 1_410_500,
        ),
        NgramPackEntry(
            languageId = "bg",
            repoCode = "bg",
            bigramStem = "bg_bigrams",
            trigramStem = "bg_trigrams",
            approxGzBytes = 1_482_369,
        ),
        NgramPackEntry(
            languageId = "sr",
            repoCode = "sr",
            bigramStem = "sr_bigrams",
            trigramStem = "sr_trigrams",
            approxGzBytes = 1_481_462,
        ),
        NgramPackEntry(
            languageId = "hr",
            repoCode = "hr",
            bigramStem = "hr_bigrams",
            trigramStem = "hr_trigrams",
            approxGzBytes = 1_344_582,
        ),
        NgramPackEntry(
            languageId = "ca",
            repoCode = "ca",
            bigramStem = "ca_bigrams",
            trigramStem = "ca_trigrams",
            approxGzBytes = 1_217_419,
        ),
        // Everything else the data repo publishes a pack for, mostly built from
        // Tatoeba's sentences (some from Wikipedia text), wherever the language
        // has a list in DictionaryCatalog for the words to come from. Packs of
        // under 500 word pairs are left out: too thin to change a ranking.
        NgramPackEntry(
            languageId = "af",
            repoCode = "af",
            bigramStem = "af_bigrams",
            trigramStem = "af_trigrams",
            approxGzBytes = 19_000,
        ),
        NgramPackEntry(
            languageId = "avk",
            repoCode = "avk",
            bigramStem = "avk_bigrams",
            trigramStem = "avk_trigrams",
            approxGzBytes = 6_081,
        ),
        NgramPackEntry(
            languageId = "be",
            repoCode = "be",
            bigramStem = "be_bigrams",
            trigramStem = "be_trigrams",
            approxGzBytes = 46_000,
        ),
        NgramPackEntry(
            languageId = "ber",
            repoCode = "ber",
            bigramStem = "ber_bigrams",
            trigramStem = "ber_trigrams",
            approxGzBytes = 1_097_000,
        ),
        NgramPackEntry(
            languageId = "br",
            repoCode = "br",
            bigramStem = "br_bigrams",
            trigramStem = "br_trigrams",
            approxGzBytes = 32_000,
        ),
        NgramPackEntry(
            languageId = "bs",
            repoCode = "bs",
            bigramStem = "bs_bigrams",
            trigramStem = "bs_trigrams",
            approxGzBytes = 3_667,
        ),
        NgramPackEntry(
            languageId = "bzt",
            repoCode = "bzt",
            bigramStem = "bzt_bigrams",
            trigramStem = "bzt_trigrams",
            approxGzBytes = 5_036,
        ),
        NgramPackEntry(
            languageId = "cbk",
            repoCode = "cbk",
            bigramStem = "cbk_bigrams",
            trigramStem = "cbk_trigrams",
            approxGzBytes = 12_000,
        ),
        NgramPackEntry(
            languageId = "ceb",
            repoCode = "ceb",
            bigramStem = "ceb_bigrams",
            trigramStem = "ceb_trigrams",
            approxGzBytes = 5_159,
        ),
        NgramPackEntry(
            languageId = "ckb",
            repoCode = "ckb",
            bigramStem = "ckb_bigrams",
            trigramStem = "ckb_trigrams",
            approxGzBytes = 38_000,
        ),
        NgramPackEntry(
            languageId = "cy",
            repoCode = "cy",
            bigramStem = "cy_bigrams",
            trigramStem = "cy_trigrams",
            approxGzBytes = 7_292,
        ),
        NgramPackEntry(
            languageId = "dtp",
            repoCode = "dtp",
            bigramStem = "dtp_bigrams",
            trigramStem = "dtp_trigrams",
            approxGzBytes = 9_392,
        ),
        NgramPackEntry(
            languageId = "enm",
            repoCode = "enm",
            bigramStem = "enm_bigrams",
            trigramStem = "enm_trigrams",
            approxGzBytes = 5_257,
        ),
        NgramPackEntry(
            languageId = "eo",
            repoCode = "eo",
            bigramStem = "eo_bigrams",
            trigramStem = "eo_trigrams",
            approxGzBytes = 1_048_000,
        ),
        NgramPackEntry(
            languageId = "et",
            repoCode = "et",
            bigramStem = "et_bigrams",
            trigramStem = "et_trigrams",
            approxGzBytes = 15_000,
        ),
        NgramPackEntry(
            languageId = "eu",
            repoCode = "eu",
            bigramStem = "eu_bigrams",
            trigramStem = "eu_trigrams",
            approxGzBytes = 17_000,
        ),
        NgramPackEntry(
            languageId = "frr",
            repoCode = "frr",
            bigramStem = "frr_bigrams",
            trigramStem = "frr_trigrams",
            approxGzBytes = 11_000,
        ),
        NgramPackEntry(
            languageId = "ga",
            repoCode = "ga",
            bigramStem = "ga_bigrams",
            trigramStem = "ga_trigrams",
            approxGzBytes = 13_000,
        ),
        NgramPackEntry(
            languageId = "gcf",
            repoCode = "gcf",
            bigramStem = "gcf_bigrams",
            trigramStem = "gcf_trigrams",
            approxGzBytes = 7_507,
        ),
        NgramPackEntry(
            languageId = "gd",
            repoCode = "gd",
            bigramStem = "gd_bigrams",
            trigramStem = "gd_trigrams",
            approxGzBytes = 6_144,
        ),
        NgramPackEntry(
            languageId = "gl",
            repoCode = "gl",
            bigramStem = "gl_bigrams",
            trigramStem = "gl_trigrams",
            approxGzBytes = 39_000,
        ),
        NgramPackEntry(
            languageId = "gn",
            repoCode = "gn",
            bigramStem = "gn_bigrams",
            trigramStem = "gn_trigrams",
            approxGzBytes = 4_780,
        ),
        NgramPackEntry(
            languageId = "gos",
            repoCode = "gos",
            bigramStem = "gos_bigrams",
            trigramStem = "gos_trigrams",
            approxGzBytes = 41_000,
        ),
        NgramPackEntry(
            languageId = "grc",
            repoCode = "grc",
            bigramStem = "grc_bigrams",
            trigramStem = "grc_trigrams",
            approxGzBytes = 6_786,
        ),
        NgramPackEntry(
            languageId = "ha",
            repoCode = "ha",
            bigramStem = "ha_bigrams",
            trigramStem = "ha_trigrams",
            approxGzBytes = 109_000,
        ),
        NgramPackEntry(
            languageId = "hoc-Latn",
            repoCode = "hoc",
            bigramStem = "hoc_latn_bigrams",
            trigramStem = "hoc_latn_trigrams",
            approxGzBytes = 5_057,
        ),
        NgramPackEntry(
            languageId = "hrx",
            repoCode = "hrx",
            bigramStem = "hrx_bigrams",
            trigramStem = "hrx_trigrams",
            approxGzBytes = 9_413,
        ),
        NgramPackEntry(
            languageId = "hy",
            repoCode = "hy",
            bigramStem = "hy_bigrams",
            trigramStem = "hy_trigrams",
            approxGzBytes = 177_000,
        ),
        NgramPackEntry(
            languageId = "ia",
            repoCode = "ia",
            bigramStem = "ia_bigrams",
            trigramStem = "ia_trigrams",
            approxGzBytes = 216_000,
        ),
        NgramPackEntry(
            languageId = "ie",
            repoCode = "ie",
            bigramStem = "ie_bigrams",
            trigramStem = "ie_trigrams",
            approxGzBytes = 37_000,
        ),
        NgramPackEntry(
            languageId = "ilo",
            repoCode = "ilo",
            bigramStem = "ilo_bigrams",
            trigramStem = "ilo_trigrams",
            approxGzBytes = 8_529,
        ),
        NgramPackEntry(
            languageId = "io",
            repoCode = "io",
            bigramStem = "io_bigrams",
            trigramStem = "io_trigrams",
            approxGzBytes = 44_000,
        ),
        NgramPackEntry(
            languageId = "is",
            repoCode = "is",
            bigramStem = "is_bigrams",
            trigramStem = "is_trigrams",
            approxGzBytes = 68_000,
        ),
        NgramPackEntry(
            languageId = "isv_latn",
            repoCode = "isv",
            bigramStem = "isv_latn_bigrams",
            trigramStem = "isv_latn_trigrams",
            approxGzBytes = 3_857,
        ),
        NgramPackEntry(
            languageId = "jbo",
            repoCode = "jbo",
            bigramStem = "jbo_bigrams",
            trigramStem = "jbo_trigrams",
            approxGzBytes = 84_000,
        ),
        NgramPackEntry(
            languageId = "ka",
            repoCode = "ka",
            bigramStem = "ka_bigrams",
            trigramStem = "ka_trigrams",
            approxGzBytes = 15_000,
        ),
        NgramPackEntry(
            languageId = "kab",
            repoCode = "kab",
            bigramStem = "kab_bigrams",
            trigramStem = "kab_trigrams",
            approxGzBytes = 599_000,
        ),
        NgramPackEntry(
            languageId = "kha",
            repoCode = "kha",
            bigramStem = "kha_bigrams",
            trigramStem = "kha_trigrams",
            approxGzBytes = 13_000,
        ),
        NgramPackEntry(
            languageId = "knc",
            repoCode = "knc",
            bigramStem = "knc_bigrams",
            trigramStem = "knc_trigrams",
            approxGzBytes = 7_718,
        ),
        NgramPackEntry(
            languageId = "ku",
            repoCode = "ku",
            bigramStem = "ku_bigrams",
            trigramStem = "ku_trigrams",
            approxGzBytes = 30_000,
        ),
        NgramPackEntry(
            languageId = "kw",
            repoCode = "kw",
            bigramStem = "kw_bigrams",
            trigramStem = "kw_trigrams",
            approxGzBytes = 12_000,
        ),
        NgramPackEntry(
            languageId = "kzj",
            repoCode = "kzj",
            bigramStem = "kzj_bigrams",
            trigramStem = "kzj_trigrams",
            approxGzBytes = 25_000,
        ),
        NgramPackEntry(
            languageId = "la",
            repoCode = "la",
            bigramStem = "la_bigrams",
            trigramStem = "la_trigrams",
            approxGzBytes = 158_000,
        ),
        NgramPackEntry(
            languageId = "lad",
            repoCode = "lad",
            bigramStem = "lad_bigrams",
            trigramStem = "lad_trigrams",
            approxGzBytes = 5_460,
        ),
        NgramPackEntry(
            languageId = "lb",
            repoCode = "lb",
            bigramStem = "lb_bigrams",
            trigramStem = "lb_trigrams",
            approxGzBytes = 4_083,
        ),
        NgramPackEntry(
            languageId = "lfn",
            repoCode = "lfn",
            bigramStem = "lfn_bigrams",
            trigramStem = "lfn_trigrams",
            approxGzBytes = 135_000,
        ),
        NgramPackEntry(
            languageId = "lij",
            repoCode = "lij",
            bigramStem = "lij_bigrams",
            trigramStem = "lij_trigrams",
            approxGzBytes = 6_002,
        ),
        NgramPackEntry(
            languageId = "ln",
            repoCode = "ln",
            bigramStem = "ln_bigrams",
            trigramStem = "ln_trigrams",
            approxGzBytes = 31_000,
        ),
        NgramPackEntry(
            languageId = "lt",
            repoCode = "lt",
            bigramStem = "lt_bigrams",
            trigramStem = "lt_trigrams",
            approxGzBytes = 593_000,
        ),
        NgramPackEntry(
            languageId = "lv",
            repoCode = "lv",
            bigramStem = "lv_bigrams",
            trigramStem = "lv_trigrams",
            approxGzBytes = 59_000,
        ),
        NgramPackEntry(
            languageId = "chm",
            repoCode = "mhr",
            bigramStem = "mhr_bigrams",
            trigramStem = "mhr_trigrams",
            approxGzBytes = 8_709,
        ),
        NgramPackEntry(
            languageId = "mk",
            repoCode = "mk",
            bigramStem = "mk_bigrams",
            trigramStem = "mk_trigrams",
            approxGzBytes = 286_000,
        ),
        NgramPackEntry(
            languageId = "mn",
            repoCode = "mn",
            bigramStem = "mn_bigrams",
            trigramStem = "mn_trigrams",
            approxGzBytes = 9_395,
        ),
        NgramPackEntry(
            languageId = "nds",
            repoCode = "nds",
            bigramStem = "nds_bigrams",
            trigramStem = "nds_trigrams",
            approxGzBytes = 77_000,
        ),
        NgramPackEntry(
            languageId = "nds_nl",
            repoCode = "nds_nl",
            bigramStem = "nds_nl_bigrams",
            trigramStem = "nds_nl_trigrams",
            approxGzBytes = 49_000,
        ),
        NgramPackEntry(
            languageId = "nn",
            repoCode = "nn",
            bigramStem = "nn_bigrams",
            trigramStem = "nn_trigrams",
            approxGzBytes = 49_000,
        ),
        NgramPackEntry(
            languageId = "nnb",
            repoCode = "nnb",
            bigramStem = "nnb_bigrams",
            trigramStem = "nnb_trigrams",
            approxGzBytes = 18_000,
        ),
        NgramPackEntry(
            languageId = "nus-Latn",
            repoCode = "nus",
            bigramStem = "nus_bigrams",
            trigramStem = "nus_trigrams",
            approxGzBytes = 8_791,
        ),
        NgramPackEntry(
            languageId = "oc",
            repoCode = "oc",
            bigramStem = "oc_bigrams",
            trigramStem = "oc_trigrams",
            approxGzBytes = 87_000,
        ),
        NgramPackEntry(
            languageId = "os",
            repoCode = "os",
            bigramStem = "os_bigrams",
            trigramStem = "os_trigrams",
            approxGzBytes = 4_909,
        ),
        NgramPackEntry(
            languageId = "ota_latn",
            repoCode = "ota",
            bigramStem = "ota_latn_bigrams",
            trigramStem = "ota_latn_trigrams",
            approxGzBytes = 5_108,
        ),
        NgramPackEntry(
            languageId = "pau",
            repoCode = "pau",
            bigramStem = "pau_bigrams",
            trigramStem = "pau_trigrams",
            approxGzBytes = 4_632,
        ),
        NgramPackEntry(
            languageId = "prg",
            repoCode = "prg",
            bigramStem = "prg_bigrams",
            trigramStem = "prg_trigrams",
            approxGzBytes = 17_000,
        ),
        NgramPackEntry(
            languageId = "rif",
            repoCode = "rif",
            bigramStem = "rif_bigrams",
            trigramStem = "rif_trigrams",
            approxGzBytes = 13_000,
        ),
        NgramPackEntry(
            languageId = "rn",
            repoCode = "rn",
            bigramStem = "rn_bigrams",
            trigramStem = "rn_trigrams",
            approxGzBytes = 8_838,
        ),
        NgramPackEntry(
            languageId = "rom",
            repoCode = "rom",
            bigramStem = "rom_bigrams",
            trigramStem = "rom_trigrams",
            approxGzBytes = 5_077,
        ),
        NgramPackEntry(
            languageId = "sat",
            repoCode = "sat",
            bigramStem = "sat_bigrams",
            trigramStem = "sat_trigrams",
            approxGzBytes = 24_000,
        ),
        NgramPackEntry(
            languageId = "sl",
            repoCode = "sl",
            bigramStem = "sl_bigrams",
            trigramStem = "sl_trigrams",
            approxGzBytes = 10_000,
        ),
        NgramPackEntry(
            languageId = "sq",
            repoCode = "sq",
            bigramStem = "sq_bigrams",
            trigramStem = "sq_trigrams",
            approxGzBytes = 10_000,
        ),
        NgramPackEntry(
            languageId = "swc",
            repoCode = "swc",
            bigramStem = "swc_bigrams",
            trigramStem = "swc_trigrams",
            approxGzBytes = 106_000,
        ),
        NgramPackEntry(
            languageId = "swg",
            repoCode = "swg",
            bigramStem = "swg_bigrams",
            trigramStem = "swg_trigrams",
            approxGzBytes = 6_735,
        ),
        NgramPackEntry(
            languageId = "ti",
            repoCode = "ti",
            bigramStem = "ti_bigrams",
            trigramStem = "ti_trigrams",
            approxGzBytes = 5_477,
        ),
        NgramPackEntry(
            languageId = "tig",
            repoCode = "tig",
            bigramStem = "tig_bigrams",
            trigramStem = "tig_trigrams",
            approxGzBytes = 106_000,
        ),
        NgramPackEntry(
            languageId = "tk",
            repoCode = "tk",
            bigramStem = "tk_bigrams",
            trigramStem = "tk_trigrams",
            approxGzBytes = 15_000,
        ),
        NgramPackEntry(
            languageId = "tlh",
            repoCode = "tlh",
            bigramStem = "tlh_bigrams",
            trigramStem = "tlh_trigrams",
            approxGzBytes = 44_000,
        ),
        NgramPackEntry(
            languageId = "tok",
            repoCode = "tok",
            bigramStem = "tok_bigrams",
            trigramStem = "tok_trigrams",
            approxGzBytes = 167_000,
        ),
        NgramPackEntry(
            languageId = "tt",
            repoCode = "tt",
            bigramStem = "tt_bigrams",
            trigramStem = "tt_trigrams",
            approxGzBytes = 208_000,
        ),
        NgramPackEntry(
            languageId = "udm",
            repoCode = "udm",
            bigramStem = "udm_bigrams",
            trigramStem = "udm_trigrams",
            approxGzBytes = 7_734,
        ),
        NgramPackEntry(
            languageId = "ug",
            repoCode = "ug",
            bigramStem = "ug_bigrams",
            trigramStem = "ug_trigrams",
            approxGzBytes = 24_000,
        ),
        NgramPackEntry(
            languageId = "vo",
            repoCode = "vo",
            bigramStem = "vo_bigrams",
            trigramStem = "vo_trigrams",
            approxGzBytes = 5_028,
        ),
        NgramPackEntry(
            languageId = "yi",
            repoCode = "yi",
            bigramStem = "yi_bigrams",
            trigramStem = "yi_trigrams",
            approxGzBytes = 90_000,
        ),
        NgramPackEntry(
            languageId = "zgh",
            repoCode = "zgh",
            bigramStem = "zgh_bigrams",
            trigramStem = "zgh_trigrams",
            approxGzBytes = 59_000,
        ),
    )

    fun forLanguage(languageId: String): NgramPackEntry? =
        entries.firstOrNull { it.languageId == languageId }

    /** The pack [name] (gzip or not) belongs to, and whether it is the trigram half. */
    fun byFileName(name: String): Pair<NgramPackEntry, Boolean>? {
        val gz = if (name.endsWith(".txt")) "$name.gz" else name
        for (entry in entries) {
            if (entry.bigramFileName == gz) return entry to false
            if (entry.trigramFileName == gz) return entry to true
        }
        return null
    }
}
