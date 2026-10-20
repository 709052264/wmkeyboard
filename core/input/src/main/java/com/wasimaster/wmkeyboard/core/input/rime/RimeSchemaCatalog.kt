package com.wasimaster.wmkeyboard.core.input.rime

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.input.R

/**
 * The typing methods a RIME schema can stand in for: one per built-in
 * Chinese conversion composer whose keys a RIME schema reads. The layout
 * decides the method, as it does for the built-in composers, and the user
 * picks which installed schema serves it.
 */
enum class RimeMethod {
    PINYIN,
    T9_PINYIN,
    ZHUYIN,
    CANGJIE,
    STROKE,
    JYUTPING,
}

/**
 * One downloadable RIME schema pack: a zip of schema and dictionary YAML
 * from the data repository, unpacked into RIME's shared data directory and
 * compiled on the phone ([RimeRuntime.deploy]).
 *
 * The download itself is an ordinary [CjkDictPack], so it goes through the
 * CJK pack path unchanged: `CjkDictDownloadManager` fetches it resumably,
 * checks the pinned SHA-256 and moves it into `cjk_dicts/<id>/`, and the
 * offline import installs the same file by its published name.
 */
data class RimeSchemaPack(
    val pack: CjkDictPack,
    /** The schema ids it brings that are worth picking, best first. */
    val schemas: List<String>,
    /** The schema it offers for each method it serves. */
    val methods: Map<RimeMethod, String>,
)

/**
 * The curated RIME packs, built by `scripts/build_rime_packs.py` in the data
 * repository from upstream at pinned commits, each carrying its upstream
 * licence. [BASE] is what every schema stands on and comes with the first
 * schema fetched.
 *
 * A published pack is never rebuilt in place: new bytes go out under a new
 * path (`rime_<id>_v2.zip`), with the old name in `legacyFileNames`.
 */
object RimeSchemaCatalog {

    val BASE: CjkDictPack = CjkDictPack(
        id = "rime_base",
        langId = "zh",
        displayNameRes = R.string.core_input_rime_pack_base_title,
        descriptionRes = R.string.core_input_rime_pack_base_subtitle,
        path = "cjk/rime/rime_base.zip",
        sizeBytes = 3_065_494L,
        sha256 = "b56bb89eddf895981ea82a71c1aad95c1539be3d6a8e5772435d1a44137c981a",
        fileName = "rime_base.zip",
        alsoLangIds = setOf("yue"),
    )

    val schemaPacks: List<RimeSchemaPack> = listOf(
        RimeSchemaPack(
            pack = CjkDictPack(
                id = "rime_luna_pinyin",
                langId = "zh",
                displayNameRes = R.string.core_input_rime_pack_luna_pinyin_title,
                descriptionRes = R.string.core_input_rime_pack_luna_pinyin_subtitle,
                path = "cjk/rime/rime_luna_pinyin.zip",
                sizeBytes = 1_462_611L,
                sha256 = "7c68e0d1d96d53e23aff73467f73d517053cb63ac5770c3332aa8111c6cb499c",
                fileName = "rime_luna_pinyin.zip",
            ),
            schemas = listOf("luna_pinyin", "luna_pinyin_simp", "luna_pinyin_tw", "luna_pinyin_fluency", "luna_quanpin", "stroke"),
            methods = mapOf(RimeMethod.PINYIN to "luna_pinyin", RimeMethod.STROKE to "stroke"),
        ),
        RimeSchemaPack(
            pack = CjkDictPack(
                id = "rime_ice",
                langId = "zh",
                displayNameRes = R.string.core_input_rime_pack_ice_title,
                descriptionRes = R.string.core_input_rime_pack_ice_subtitle,
                path = "cjk/rime/rime_ice.zip",
                sizeBytes = 11_115_500L,
                sha256 = "a408fdbf539872b462021ea0fe8a8eb51f8d1ccced682d29b2a81a0981dc2a2f",
                fileName = "rime_ice.zip",
            ),
            schemas = listOf(
                "rime_ice", "t9", "double_pinyin", "double_pinyin_mspy", "double_pinyin_sogou",
                "double_pinyin_flypy", "double_pinyin_ziguang", "double_pinyin_abc", "double_pinyin_jiajia",
            ),
            methods = mapOf(RimeMethod.PINYIN to "rime_ice", RimeMethod.T9_PINYIN to "t9"),
        ),
        RimeSchemaPack(
            pack = CjkDictPack(
                id = "rime_bopomofo",
                langId = "zh",
                displayNameRes = R.string.core_input_rime_pack_bopomofo_title,
                descriptionRes = R.string.core_input_rime_pack_bopomofo_subtitle,
                path = "cjk/rime/rime_bopomofo.zip",
                sizeBytes = 1_807_669L,
                sha256 = "fd28e37b1918772dbd83695862788d8a972232d8169e1906c63edf1fc3c5d9dc",
                fileName = "rime_bopomofo.zip",
            ),
            schemas = listOf("bopomofo", "bopomofo_tw", "bopomofo_express", "terra_pinyin"),
            methods = mapOf(RimeMethod.ZHUYIN to "bopomofo"),
        ),
        RimeSchemaPack(
            pack = CjkDictPack(
                id = "rime_cangjie5",
                langId = "zh",
                displayNameRes = R.string.core_input_rime_pack_cangjie5_title,
                descriptionRes = R.string.core_input_rime_pack_cangjie5_subtitle,
                path = "cjk/rime/rime_cangjie5.zip",
                sizeBytes = 398_131L,
                sha256 = "b42523e51018cfd1fdbd44479463effab0491ad4176e739d0a2a2052a615d758",
                fileName = "rime_cangjie5.zip",
            ),
            schemas = listOf("cangjie5", "cangjie5_express"),
            methods = mapOf(RimeMethod.CANGJIE to "cangjie5"),
        ),
        RimeSchemaPack(
            pack = CjkDictPack(
                id = "rime_jyutping",
                langId = "yue",
                displayNameRes = R.string.core_input_rime_pack_jyutping_title,
                descriptionRes = R.string.core_input_rime_pack_jyutping_subtitle,
                path = "cjk/rime/rime_jyutping.zip",
                sizeBytes = 4_486_185L,
                sha256 = "b4d86e396736da4920bb1d467e2f60a6c955f4616e23f583c450f4461d25d031",
                fileName = "rime_jyutping.zip",
            ),
            schemas = listOf("jyut6ping3"),
            methods = mapOf(RimeMethod.JYUTPING to "jyut6ping3"),
        ),
    )

    /** Every pack, [BASE] first. */
    val packs: List<CjkDictPack> = listOf(BASE) + schemaPacks.map { it.pack }

    init {
        check(packs.map { it.id }.toSet().size == packs.size) { "pack ids must be unique" }
    }

    fun byId(id: String): CjkDictPack? = packs.firstOrNull { it.id == id }

    fun schemaPack(id: String): RimeSchemaPack? = schemaPacks.firstOrNull { it.pack.id == id }

    /** The schema packs offered on [langId]'s screen. */
    fun forLang(langId: String): List<RimeSchemaPack> =
        schemaPacks.filter { it.pack.langId == langId || langId in it.pack.alsoLangIds }

    /** [pack] with [BASE], which has to be there for any schema to compile. */
    fun withBase(pack: CjkDictPack): List<CjkDictPack> = if (pack.id == BASE.id) listOf(pack) else listOf(BASE, pack)
}
