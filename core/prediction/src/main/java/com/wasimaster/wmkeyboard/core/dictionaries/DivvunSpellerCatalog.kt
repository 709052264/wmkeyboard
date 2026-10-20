package com.wasimaster.wmkeyboard.core.dictionaries

/**
 * One GiellaLT speller the keyboard can fetch: the `noarch-mobile` package of
 * a versioned `speller-<code>/v<version>` release on the language's own
 * repository (github.com/giellalt/lang-<code>), pinned by URL and SHA-256.
 * The package is a zstd tar holding [member], the speller archive, and a
 * `-x-predict` archive the keyboard does not use.
 *
 * Only versioned releases are pinned. GiellaLT's `dev-latest` releases are
 * rebuilt on every commit and drop the previous file, so a pin on one stops
 * working within days; their spellers can still be imported by hand.
 */
data class DivvunSpellerEntry(
    /** The keyboard's language id. */
    val languageId: String,
    /** GiellaLT's ISO 639-3 code, the `lang-<code>` repository. */
    val giellaCode: String,
    val version: String,
    val url: String,
    val sha256: String,
    /** Bytes of the package as downloaded. */
    val packedBytes: Long,
    /** The archive inside it, and its size once unpacked: what the phone keeps. */
    val member: String,
    val unpackedBytes: Long,
    /** SPDX id of the language repository's licence, which the speller is built from. */
    val licence: String,
) {
    /** The repository the speller is built from, for the licence and the source. */
    val sourceUrl: String get() = "https://github.com/giellalt/lang-$giellaCode"
}

/**
 * The GiellaLT spellers the keyboard offers to download, one per language,
 * for the languages it types that GiellaLT has made a versioned release for
 * (checked 2026-10-20). Irish (`speller-gle/v0.2.0`) is left out: its
 * repository states no licence yet.
 *
 * [published] lists the languages GiellaLT builds a speller for at all,
 * versioned or not, so a language's screen can offer the import of one
 * fetched by hand.
 */
object DivvunSpellerCatalog {
    private const val BASE = "https://github.com/giellalt"

    private fun entry(
        languageId: String,
        code: String,
        version: String,
        build: Int,
        sha256: String,
        packedBytes: Long,
        member: String,
        unpackedBytes: Long,
        licence: String,
    ) = DivvunSpellerEntry(
        languageId = languageId,
        giellaCode = code,
        version = version,
        url = "$BASE/lang-$code/releases/download/speller-$code/v$version/" +
            "speller-${code}_$version%2Bbuild.${build}_noarch-mobile.pkt.tar.zst",
        sha256 = sha256,
        packedBytes = packedBytes,
        member = member,
        unpackedBytes = unpackedBytes,
        licence = licence,
    )

    val entries: List<DivvunSpellerEntry> = listOf(
        entry(
            "se", "sme", "4.5.2", 614,
            "6a8140fda14aa13d8dd96f302ce8ff1b0453b40b7a2591b5801f4ebf60a3dae1",
            10_616_265, "se.bhfst", 79_173_472, "GPL-3.0-only",
        ),
        entry(
            "sma", "sma", "4.7.0", 468,
            "a3580ed7c405e81598ee753580acec86d7cac1e5ac0710c956576ceba50b9054",
            2_806_097, "sma.bhfst", 24_940_176, "GPL-3.0-only",
        ),
        entry(
            "smj", "smj", "4.5.1", 40,
            "e3ee6722451f41fdf90478952002c1152f65aa32704b96b932d36916e89a386e",
            7_948_015, "smj.bhfst", 86_522_320, "GPL-3.0-only",
        ),
        entry(
            "smn", "smn", "1.2.1", 60,
            "f5ac30db173f66129f9cb3b308483aa39afe46044f5759a84cbe5502f0856255",
            6_644_857, "smn.bhfst", 62_318_384, "GPL-3.0-only",
        ),
        entry(
            "sms", "sms", "1.0.2", 93,
            "162597920c6f4f3cf177eb11270e7ca186ddbb494db424ea66dbf59dbbecac24",
            14_625_055, "sms.bhfst", 107_304_664, "LGPL-3.0-only",
        ),
        entry(
            "kl", "kal", "1.0.3", 398,
            "d61ef2900038ac96ddf6216c77e1286a3993dac02da383dbbbec735a1a5809dd",
            11_782_914, "kl.bhfst", 94_303_600, "GPL-3.0-only",
        ),
        entry(
            "fo", "fao", "1.3.1", 44,
            "cf8e791777768ad634da575546d125fc08666c839cd89cc69a9e60e73cb443bf",
            4_403_735, "fo.bhfst", 34_140_232, "GPL-3.0-only",
        ),
        // The next three are GiellaLT pre-releases: versioned and pinnable,
        // with spellers its own testing calls early.
        entry(
            "olo", "olo", "0.1.4", 76,
            "40f786bf04e2d956898cb5e29458c6b74ee53d791ac953dce02a0052a70e13b2",
            1_088_896, "olo.bhfst", 9_165_632, "LGPL-3.0-only",
        ),
        entry(
            "fkv", "fkv", "0.2.1", 198,
            "84abf9e3dbe33f9d726d56b873bf1e745724600fae688ba496168dccbc6210d2",
            2_584_429, "fkv.bhfst", 28_061_168, "LGPL-3.0-only",
        ),
        entry(
            "vep", "vep", "0.1.2", 22,
            "0482afbbe0382a517f7105b342ee21e3137278828a7e3cbbe49c0ad8e47c2835",
            239_656, "vep.bhfst", 2_755_400, "LGPL-3.0-only",
        ),
    )

    private val byLanguage = entries.associateBy { it.languageId }

    fun forLanguage(langId: String): DivvunSpellerEntry? = byLanguage[langId]

    /**
     * The keyboard's languages GiellaLT publishes a speller for (a
     * `speller-<code>` release on its lang repository, 2026-10-20), by the
     * keyboard's id, in the script GiellaLT spells it in. English is left
     * out (GiellaLT's English is a test lexicon), and so are Gothic, Yupik
     * and Kurmanji, whose keyboard layouts write another script than
     * GiellaLT's speller reads.
     */
    val published: Set<String> = setOf(
        "ak", "am", "ar", "ay", "ba", "bla-Latn", "bg", "ceb", "cs", "cho", "chp", "chr", "ckt", "kw", "crk",
        "dag", "de", "dgr", "dsb", "eo", "eu", "evn", "fo", "fi", "fkv", "fro", "ga", "gn", "gur", "hdn",
        "hil", "hi", "hu", "iu", "ik", "izh", "kl", "kek", "khk-Cyrl", "kjh", "koi", "kv", "krl", "lv", "lt",
        "liv", "lut-Latn", "mdf", "mns", "moh-Latn", "mrj", "my", "myv", "nds", "nio", "nn", "nb", "non",
        "nso", "oj", "olo", "qya", "rmy", "ro", "rue", "rup", "ru", "sje", "sju", "sma", "se", "smj", "smn",
        "sms", "so", "sq", "srs-Latn", "sto-Latn", "sv", "tt", "tau-Latn", "te", "tl", "th", "ti", "tkl",
        "tlh", "tyv", "udm", "vep", "vot", "vro", "xal", "yrk",
    )

    /** Whether [langId]'s screen should offer a GiellaLT speller, to download or to import. */
    fun offers(langId: String): Boolean = langId in byLanguage || langId in published
}
