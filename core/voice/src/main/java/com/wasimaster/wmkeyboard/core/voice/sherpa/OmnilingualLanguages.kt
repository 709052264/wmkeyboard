package com.wasimaster.wmkeyboard.core.voice.sherpa

/**
 * The keyboard languages Meta's Omnilingual ASR (300M CTC) transcribes, as
 * the base codes [com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine]
 * compares: every language id in LanguageRegistry, KeymanLanguages and
 * CorpusLanguages whose ISO 639-3 code (or, for a macrolanguage, the
 * individual language Omnilingual lists: `ar` is `arb`, `fa` is `fas`, `sw`
 * is `swh`) is one of the 1,668 in omnilingual-asr's `lang_ids.py` at
 * 81f51e2.
 *
 * Left out although listed there: the languages Omnilingual only writes in
 * another script than the keyboard's (Crimean Tatar in Cyrillic for a Latin
 * keyboard, Manipuri in Bengali for a Meetei Mayek one, Tulu in Malayalam,
 * Uzbek in Latin for the Cyrillic one, and so on), since the model has no
 * way to be told which script to use.
 */
internal object OmnilingualLanguages {

    val codes: Set<String> = setOf(
        "aae", "aal", "ab", "ace", "acm", "ady", "aeb", "af", "afb", "agx", "ahk", "ak", "aln", "alt", "am",
        "amf", "ami", "an", "ann", "anp", "aoz", "apc", "ar", "arq", "ary", "arz", "as", "ast", "av", "awa",
        "ay", "ayl", "az", "azb", "azj", "ba", "bal", "ban", "bax", "bba", "bbc", "bbo", "bcl", "bda", "be",
        "bem", "bew", "bfa", "bft", "bfz", "bg", "bho", "bi", "bjn", "blt", "bm", "bn", "bo", "bom", "bqj",
        "br", "brh", "bru", "brx", "bs", "bsc", "bsk", "bsq", "btm", "bug", "ca", "ce", "ceb", "chm", "cjp",
        "ckb", "ckt", "cmo", "cou", "crk", "crs", "cs", "csk", "ctd", "cv", "cy", "da", "dag", "dar", "de",
        "dga", "dgr", "din", "diq", "dml", "doi", "dsb", "dtp", "dty", "dv", "dyo", "dz", "dzg", "ee", "el",
        "emp", "en", "eo", "es", "et", "eu", "evn", "fa", "fat", "ff", "fi", "fj", "fo", "fon", "fr", "fub",
        "fuc", "fuv", "fy", "ga", "gag", "gbm", "gil", "gl", "glk", "gn", "gor", "grc", "gsl", "gu", "guc",
        "gur", "gv", "gwc", "gwi", "gwt", "ha", "hak", "haw", "he", "hi", "hif", "hil", "hr", "hsb", "ht",
        "hu", "hy", "hyw", "ia", "iba", "id", "idu", "ig", "igl", "ik", "ilo", "is", "it", "ja", "jam", "jv",
        "ka", "kaa", "kab", "kac", "kai", "kaj", "kam", "kbd", "kbp", "kcg", "kdh", "kek", "keu", "kha",
        "khk", "khw", "ki", "kjh", "kk", "km", "kmr", "kn", "knc", "knf", "ko", "kok", "krc", "krl", "krx",
        "ks", "ku", "kum", "kus", "kv", "kvx", "kw", "kxp", "ky", "kyu", "la", "lb", "lg", "lhu", "lij",
        "lis", "ln", "lo", "lt", "ltg", "lv", "lzz", "mad", "mah", "mai", "mak", "max", "men", "mfe", "mg",
        "mhi", "mi", "min", "mk", "mki", "ml", "mn", "mnw", "mos", "mr", "mrj", "ms", "mt", "mve", "mvy",
        "mwq", "my", "myv", "nan", "nap", "nb", "nch", "ndv", "ne", "new", "ngu", "nia", "nko", "nl", "nlv",
        "nnb", "nog", "nso", "nst", "nup", "nus", "ny", "nzi", "oc", "odk", "ojb", "om", "ood", "or", "orc",
        "oru", "os", "pa", "pag", "pam", "pap", "pau", "pcm", "pes", "phl", "phr", "pl", "pms", "pnb", "prs",
        "ps", "pt", "pwn", "qu", "quc", "raj", "rap", "raw", "rel", "rhg", "rif", "rmy", "rn", "ro", "ru",
        "rw", "sah", "saj", "sc", "scl", "scn", "sco", "sd", "sg", "sgw", "sh", "shi", "shn", "si", "sk",
        "skr", "sl", "sm", "sn", "so", "sq", "sr", "srn", "srr", "stp", "su", "sv", "sva", "sw", "szy", "ta",
        "tay", "te", "tg", "tgo", "th", "thv", "ti", "tig", "tk", "tl", "tli", "tly", "tn", "tob", "tok",
        "tpi", "tr", "trq", "trv", "trw", "ts", "tt", "ttq", "tw", "udm", "ug", "uk", "umb", "ur", "urh",
        "ush", "uz", "vai", "vi", "vro", "war", "way", "wbl", "wlx", "wo", "wsg", "xal", "xh", "xmf", "ydg",
        "yi", "yo", "yua", "yue", "zh", "zu",
    )
}
