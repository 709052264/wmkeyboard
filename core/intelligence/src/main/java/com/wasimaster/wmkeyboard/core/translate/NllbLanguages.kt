package com.wasimaster.wmkeyboard.core.translate

/**
 * What Meta's NLLB-200 model reads and writes, and how the app's language
 * tags map onto it, kept apart from the engine so the settings screens and
 * the unit tests can use it without the native library.
 *
 * NLLB names a language by its FLORES-200 code: an ISO 639-3 language and an
 * ISO 15924 script (`ben_Beng`, `zho_Hant`). One model translates between
 * any two of its [CODES] directly, with no pivot through English, so a pair
 * is either both languages known or not at all.
 *
 * A two-letter tag that names a macrolanguage goes to the variety NLLB was
 * trained on for it: Arabic is Modern Standard (`arb`), Persian is Iranian
 * (`pes`), Malay is Standard Malay (`zsm`), Swahili, Latvian, Estonian,
 * Mongolian, Nepali, Oromo, Pashto, Albanian, Uzbek, Azerbaijani and
 * Malagasy likewise ([TWO_LETTER]).
 */
object NllbLanguages : OfflineLanguageTable {

    const val ENGLISH = "eng_Latn"

    /** Every language tag the model has, as its tokenizer lists them. */
    val CODES: List<String> = (
        "ace_Arab ace_Latn acm_Arab acq_Arab aeb_Arab afr_Latn ajp_Arab aka_Latn amh_Ethi apc_Arab arb_Arab " +
            "ars_Arab ary_Arab arz_Arab asm_Beng ast_Latn awa_Deva ayr_Latn azb_Arab azj_Latn bak_Cyrl bam_Latn " +
            "ban_Latn bel_Cyrl bem_Latn ben_Beng bho_Deva bjn_Arab bjn_Latn bod_Tibt bos_Latn bug_Latn bul_Cyrl " +
            "cat_Latn ceb_Latn ces_Latn cjk_Latn ckb_Arab crh_Latn cym_Latn dan_Latn deu_Latn dik_Latn dyu_Latn " +
            "dzo_Tibt ell_Grek eng_Latn epo_Latn est_Latn eus_Latn ewe_Latn fao_Latn pes_Arab fij_Latn fin_Latn " +
            "fon_Latn fra_Latn fur_Latn fuv_Latn gla_Latn gle_Latn glg_Latn grn_Latn guj_Gujr hat_Latn hau_Latn " +
            "heb_Hebr hin_Deva hne_Deva hrv_Latn hun_Latn hye_Armn ibo_Latn ilo_Latn ind_Latn isl_Latn ita_Latn " +
            "jav_Latn jpn_Jpan kab_Latn kac_Latn kam_Latn kan_Knda kas_Arab kas_Deva kat_Geor knc_Arab knc_Latn " +
            "kaz_Cyrl kbp_Latn kea_Latn khm_Khmr kik_Latn kin_Latn kir_Cyrl kmb_Latn kon_Latn kor_Hang kmr_Latn " +
            "lao_Laoo lvs_Latn lij_Latn lim_Latn lin_Latn lit_Latn lmo_Latn ltg_Latn ltz_Latn lua_Latn lug_Latn " +
            "luo_Latn lus_Latn mag_Deva mai_Deva mal_Mlym mar_Deva min_Latn mkd_Cyrl plt_Latn mlt_Latn mni_Beng " +
            "khk_Cyrl mos_Latn mri_Latn zsm_Latn mya_Mymr nld_Latn nno_Latn nob_Latn npi_Deva nso_Latn nus_Latn " +
            "nya_Latn oci_Latn gaz_Latn ory_Orya pag_Latn pan_Guru pap_Latn pol_Latn por_Latn prs_Arab pbt_Arab " +
            "quy_Latn ron_Latn run_Latn rus_Cyrl sag_Latn san_Deva sat_Beng scn_Latn shn_Mymr sin_Sinh slk_Latn " +
            "slv_Latn smo_Latn sna_Latn snd_Arab som_Latn sot_Latn spa_Latn als_Latn srd_Latn srp_Cyrl ssw_Latn " +
            "sun_Latn swe_Latn swh_Latn szl_Latn tam_Taml tat_Cyrl tel_Telu tgk_Cyrl tgl_Latn tha_Thai tir_Ethi " +
            "taq_Latn taq_Tfng tpi_Latn tsn_Latn tso_Latn tuk_Latn tum_Latn tur_Latn twi_Latn tzm_Tfng uig_Arab " +
            "ukr_Cyrl umb_Latn urd_Arab uzn_Latn vec_Latn vie_Latn war_Latn wol_Latn xho_Latn ydd_Hebr yor_Latn " +
            "yue_Hant zho_Hans zho_Hant zul_Latn"
        ).split(' ')

    private val codeSet: Set<String> by lazy { CODES.toSet() }

    /** The codes of one ISO 639-3 language, in [CODES] order (`kas` has two scripts). */
    private val byLanguage: Map<String, List<String>> by lazy { CODES.groupBy { it.substringBefore('_') } }

    /** ISO 639-1 codes, and the old ones still about, to the variety NLLB has. */
    private val TWO_LETTER: Map<String, String> = mapOf(
        "af" to "afr_Latn", "ak" to "aka_Latn", "am" to "amh_Ethi", "ar" to "arb_Arab", "as" to "asm_Beng",
        "ay" to "ayr_Latn", "az" to "azj_Latn", "ba" to "bak_Cyrl", "be" to "bel_Cyrl", "bg" to "bul_Cyrl",
        "bm" to "bam_Latn", "bn" to "ben_Beng", "bo" to "bod_Tibt", "bs" to "bos_Latn", "ca" to "cat_Latn",
        "cs" to "ces_Latn", "cy" to "cym_Latn", "da" to "dan_Latn", "de" to "deu_Latn", "dz" to "dzo_Tibt",
        "ee" to "ewe_Latn", "el" to "ell_Grek", "en" to "eng_Latn", "eo" to "epo_Latn", "es" to "spa_Latn",
        "et" to "est_Latn", "eu" to "eus_Latn", "fa" to "pes_Arab", "ff" to "fuv_Latn", "fi" to "fin_Latn",
        "fj" to "fij_Latn", "fo" to "fao_Latn", "fr" to "fra_Latn", "ga" to "gle_Latn", "gd" to "gla_Latn",
        "gl" to "glg_Latn", "gn" to "grn_Latn", "gu" to "guj_Gujr", "ha" to "hau_Latn", "he" to "heb_Hebr",
        "hi" to "hin_Deva", "hr" to "hrv_Latn", "ht" to "hat_Latn", "hu" to "hun_Latn", "hy" to "hye_Armn",
        "id" to "ind_Latn", "ig" to "ibo_Latn", "is" to "isl_Latn", "it" to "ita_Latn", "ja" to "jpn_Jpan",
        "jv" to "jav_Latn", "ka" to "kat_Geor", "kg" to "kon_Latn", "ki" to "kik_Latn", "kk" to "kaz_Cyrl",
        "km" to "khm_Khmr", "kn" to "kan_Knda", "ko" to "kor_Hang", "kr" to "knc_Latn", "ks" to "kas_Arab",
        "ku" to "kmr_Latn", "ky" to "kir_Cyrl", "lb" to "ltz_Latn", "lg" to "lug_Latn", "li" to "lim_Latn",
        "ln" to "lin_Latn", "lo" to "lao_Laoo", "lt" to "lit_Latn", "lv" to "lvs_Latn", "mg" to "plt_Latn",
        "mi" to "mri_Latn", "mk" to "mkd_Cyrl", "ml" to "mal_Mlym", "mn" to "khk_Cyrl", "mr" to "mar_Deva",
        "ms" to "zsm_Latn", "mt" to "mlt_Latn", "my" to "mya_Mymr", "nb" to "nob_Latn", "ne" to "npi_Deva",
        "nl" to "nld_Latn", "nn" to "nno_Latn", "no" to "nob_Latn", "ny" to "nya_Latn", "oc" to "oci_Latn",
        "om" to "gaz_Latn", "or" to "ory_Orya", "pa" to "pan_Guru", "pl" to "pol_Latn", "ps" to "pbt_Arab",
        "pt" to "por_Latn", "qu" to "quy_Latn", "rn" to "run_Latn", "ro" to "ron_Latn", "ru" to "rus_Cyrl",
        "rw" to "kin_Latn", "sa" to "san_Deva", "sc" to "srd_Latn", "sd" to "snd_Arab", "sg" to "sag_Latn",
        "si" to "sin_Sinh", "sk" to "slk_Latn", "sl" to "slv_Latn", "sm" to "smo_Latn", "sn" to "sna_Latn",
        "so" to "som_Latn", "sq" to "als_Latn", "sr" to "srp_Cyrl", "ss" to "ssw_Latn", "st" to "sot_Latn",
        "su" to "sun_Latn", "sv" to "swe_Latn", "sw" to "swh_Latn", "ta" to "tam_Taml", "te" to "tel_Telu",
        "tg" to "tgk_Cyrl", "th" to "tha_Thai", "ti" to "tir_Ethi", "tk" to "tuk_Latn", "tl" to "tgl_Latn",
        "tn" to "tsn_Latn", "tr" to "tur_Latn", "ts" to "tso_Latn", "tt" to "tat_Cyrl", "tw" to "twi_Latn",
        "ug" to "uig_Arab", "uk" to "ukr_Cyrl", "ur" to "urd_Arab", "uz" to "uzn_Latn", "vi" to "vie_Latn",
        "wo" to "wol_Latn", "xh" to "xho_Latn", "yi" to "ydd_Hebr", "yo" to "yor_Latn", "zh" to "zho_Hans",
        "zu" to "zul_Latn",
        // Withdrawn codes Android and old identifiers still report.
        "iw" to "heb_Hebr", "in" to "ind_Latn", "ji" to "ydd_Hebr",
    )

    /** ISO 639-3 macrolanguage and alternative codes to the one NLLB has. */
    private val THREE_LETTER: Map<String, String> = mapOf(
        "ara" to "arb_Arab", "fas" to "pes_Arab", "per" to "pes_Arab", "msa" to "zsm_Latn", "may" to "zsm_Latn",
        "swa" to "swh_Latn", "lav" to "lvs_Latn", "ekk" to "est_Latn", "mon" to "khk_Cyrl", "nep" to "npi_Deva",
        "orm" to "gaz_Latn", "pus" to "pbt_Arab", "pbu" to "pbt_Arab", "sqi" to "als_Latn", "alb" to "als_Latn",
        "uzb" to "uzn_Latn", "aze" to "azj_Latn", "mlg" to "plt_Latn", "zho" to "zho_Hans", "chi" to "zho_Hans",
        "cmn" to "zho_Hans", "nor" to "nob_Latn", "kur" to "kmr_Latn", "yid" to "ydd_Hebr", "que" to "quy_Latn",
        "grn" to "grn_Latn", "kau" to "knc_Latn", "ful" to "fuv_Latn", "fil" to "tgl_Latn", "ori" to "ory_Orya",
        "aym" to "ayr_Latn", "hbs" to "hrv_Latn", "lah" to "pan_Guru",
    )

    private val TRADITIONAL_CHINESE_SUBTAGS = setOf("tw", "hk", "mo", "hant")

    /**
     * NLLB's code for a picker code (`zh-TW`), a keyboard language id
     * (`bn_BD`), a tag from a language identifier, or a FLORES-200 code
     * itself; null when the model has none. A script subtag picks among a
     * language's scripts (`ks-Deva`); one the model does not write that
     * language in, such as `hi-Latn`, is refused, as the other tables do.
     */
    override fun modelCode(tag: String): String? {
        val trimmed = tag.trim()
        if (trimmed in codeSet) return trimmed
        val parts = trimmed.replace('_', '-').split('-').filter { it.isNotEmpty() }
        val primary = parts.firstOrNull()?.lowercase() ?: return null
        val rest = parts.drop(1).map { it.lowercase() }
        val script = rest.firstOrNull { it.length == 4 && it.all(Char::isLetter) }
        if (primary == "zh") {
            if (script == "latn") return null
            return if (rest.any { it in TRADITIONAL_CHINESE_SUBTAGS }) "zho_Hant" else "zho_Hans"
        }
        val default = TWO_LETTER[primary] ?: THREE_LETTER[primary] ?: byLanguage[primary]?.firstOrNull() ?: return null
        if (script == null) return default
        val language = default.substringBefore('_')
        return byLanguage[language]?.firstOrNull { it.substringAfter('_').equals(script, ignoreCase = true) }
    }

    /** A tag in Latin letters for a language the model only has in another script (`hi-Latn`, `bn-Latn`). */
    override fun isRomanized(tag: String): Boolean {
        val parts = tag.trim().replace('_', '-').lowercase().split('-')
        if (parts.size < 2 || parts[1] != "latn") return false
        val language = modelCode(parts[0])?.substringBefore('_') ?: return false
        return byLanguage[language]?.none { it.endsWith("_Latn") } == true
    }

    /** Whether the model reads and writes [code] (a FLORES-200 code). */
    fun has(code: String): Boolean = code in codeSet
}
