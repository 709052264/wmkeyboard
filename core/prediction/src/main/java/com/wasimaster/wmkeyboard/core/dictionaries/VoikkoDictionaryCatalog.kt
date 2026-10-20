package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * One Voikko dictionary the keyboard can fetch: a zip of libvoikko's
 * `5/mor-<variant>/` folder in the data repository, pinned by SHA-256.
 */
data class VoikkoDictionaryEntry(
    /** The keyboard's language id. */
    val languageId: String,
    val version: String,
    /** The zip's path in the data repository. */
    val path: String,
    val sha256: String,
    /** Bytes of the zip as downloaded. */
    val packedBytes: Long,
    /** Bytes once unpacked: what the phone keeps. */
    val unpackedBytes: Long,
    /** SPDX id of the dictionary's licence. */
    val licence: String,
    /** The release the zip is built from, published beside it as the licence asks. */
    val sourcePath: String,
) {
    val url: String get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(path)

    val sourceUrl: String get() = ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(sourcePath)
}

/**
 * The Voikko dictionaries the keyboard offers to download. Finnish is the
 * only language libvoikko has a full dictionary for: voikko-fi, the
 * standard vocabulary (`mor-standard`), built by native/voikko-jni/build-dict.sh
 * from the signed voikko-fi 2.6 release, whose tarball sits beside it in the
 * data repository. Upstream publishes sources only, so the built zip is
 * ours, and it is reproducible from that tarball byte for byte.
 */
object VoikkoDictionaryCatalog {
    private const val DIR = "spell/voikko"

    val entries: List<VoikkoDictionaryEntry> = listOf(
        VoikkoDictionaryEntry(
            languageId = "fi",
            version = "2.6",
            path = "$DIR/voikko-fi-2.6-mor-standard.zip",
            sha256 = "9a7b8ed3f401ac46e81714775743219175c54164a4eaa3b0286c68db4825b439",
            packedBytes = 1_645_402,
            unpackedBytes = 4_010_981,
            licence = "GPL-2.0-or-later",
            sourcePath = "$DIR/voikko-fi-2.6.tar.gz",
        ),
    )

    private val byLanguage = entries.associateBy { it.languageId }

    fun forLanguage(langId: String): VoikkoDictionaryEntry? = byLanguage[langId]
}
