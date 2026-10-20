package com.wasimaster.wmkeyboard.core.input.zinnia

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import com.wasimaster.wmkeyboard.input.R
import java.io.File

/**
 * zinnia's handwriting models for Japanese, Simplified Chinese and
 * Traditional Chinese: the Tegaki project's 0.3 releases, published in the
 * data repository byte for byte as Tegaki built them. The recogniser itself
 * (libwmzinnia, `:core:intelligence`) reads one of these per language; the
 * handwriting panel offers the download.
 *
 * Ordinary [CjkDictPack]s, so they go through the CJK pack path unchanged:
 * `CjkDictDownloadManager` fetches each resumably, checks the pinned SHA-256
 * and moves it into `cjk_dicts/<id>/`, and the offline import installs the
 * same file by its published name. They are listed only with the
 * handwriting models, never on a language's dictionary screen.
 *
 * Licence: LGPL 2.1, inherited from the Tomoe stroke data the models are
 * trained on (data repository `cjk/zinnia/COPYING`). Japanese and Simplified
 * Chinese are the files in Debian's `tegaki-zinnia-japanese` and
 * `tegaki-zinnia-simplified-chinese` 0.3 source packages; Traditional
 * Chinese is the one in `tegaki-zinnia-traditional-chinese-0.3.zip`, whose
 * SHA-1 OpenMandriva's and pkgsrc's packaging records agree on.
 */
object ZinniaData {

    /** The Tegaki model release the three packs come from. */
    const val TEGAKI_VERSION = "0.3"

    val japanese: CjkDictPack = CjkDictPack(
        id = "zinnia_ja",
        langId = "ja",
        displayNameRes = R.string.core_input_zinnia_ja_pack_title,
        descriptionRes = R.string.core_input_zinnia_pack_subtitle,
        path = "cjk/zinnia/handwriting-ja-$TEGAKI_VERSION.model",
        sizeBytes = 26_068_668L,
        sha256 = "d58618576d12c1ea38992606f30d19158a0a3beda3ac23bacccf1e48e651f2b2",
        fileName = "handwriting-ja-$TEGAKI_VERSION.model",
    )

    val simplifiedChinese: CjkDictPack = CjkDictPack(
        id = "zinnia_zh_hans",
        langId = "zh",
        displayNameRes = R.string.core_input_zinnia_zh_hans_pack_title,
        descriptionRes = R.string.core_input_zinnia_pack_subtitle,
        path = "cjk/zinnia/handwriting-zh_CN-$TEGAKI_VERSION.model",
        sizeBytes = 26_834_816L,
        sha256 = "e16153d1ff267cd479aea260d6f71a3edda8b4ba06db2d121513adda65a4449e",
        fileName = "handwriting-zh_CN-$TEGAKI_VERSION.model",
    )

    val traditionalChinese: CjkDictPack = CjkDictPack(
        id = "zinnia_zh_hant",
        langId = "zh",
        displayNameRes = R.string.core_input_zinnia_zh_hant_pack_title,
        descriptionRes = R.string.core_input_zinnia_pack_subtitle,
        path = "cjk/zinnia/handwriting-zh_TW-$TEGAKI_VERSION.model",
        sizeBytes = 52_210_768L,
        sha256 = "cd47f16b64e7ecaa4c2813d0f98231dfea7b411f48be1ead5c9b2eeb19330b11",
        fileName = "handwriting-zh_TW-$TEGAKI_VERSION.model",
        alsoLangIds = setOf("yue"),
    )

    val packs: List<CjkDictPack> = listOf(japanese, simplifiedChinese, traditionalChinese)

    /** [pack]'s downloaded model, or null while there is none. */
    fun file(filesDir: File, pack: CjkDictPack): File? = CjkDictStore.packFile(filesDir, pack).takeIf(File::isFile)
}
