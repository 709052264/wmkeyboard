package com.wasimaster.wmkeyboard.core.input.mozc

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import com.wasimaster.wmkeyboard.input.R
import java.io.File

/**
 * Mozc's system dictionary: the `mozc.data` file its own Android build
 * loads, built from Mozc's sources at the release libwmmozc is built from
 * (native/mozc-jni/build.sh --data) and published in the data repository.
 * Kept out of the library so the library stays small and the dictionary
 * downloads only for whoever picks Mozc.
 *
 * An ordinary [CjkDictPack], so it goes through the CJK pack path
 * unchanged: `CjkDictDownloadManager` fetches it resumably, checks the
 * pinned SHA-256 and moves it into `cjk_dicts/mozc/`, and the offline import
 * installs the same file by its published name. The built-in Japanese
 * composer never reads it.
 *
 * The dictionary has to come from the same Mozc release as the library: its
 * format is the library's. A Mozc update therefore publishes a new file
 * under a new name, and the old name moves to `legacyFileNames` only if the
 * old data still loads in the new library.
 */
object MozcData {

    /** The Mozc release the library and this dictionary are built from. */
    const val MOZC_VERSION = "3.34.6239"

    val pack: CjkDictPack = CjkDictPack(
        id = "mozc",
        langId = "ja",
        displayNameRes = R.string.core_input_mozc_pack_title,
        descriptionRes = R.string.core_input_mozc_pack_subtitle,
        path = "cjk/mozc/mozc-$MOZC_VERSION.data",
        sizeBytes = 18_887_704L,
        sha256 = "879a0132f03e4c5ee0ad0ca0f30b486f2e125ec82d7d9b86e0c3177d34b83ef9",
        fileName = "mozc-$MOZC_VERSION.data",
    )

    val packs: List<CjkDictPack> = listOf(pack)

    /** The downloaded dictionary, or null while there is none. */
    fun file(filesDir: File): File? = CjkDictStore.packFile(filesDir, pack).takeIf(File::isFile)
}
