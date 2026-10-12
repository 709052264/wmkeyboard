package com.wasimaster.wmkeyboard.core.voice.asr4all

import java.io.File
import java.security.MessageDigest

/** One file of a model: where it lives in the repo, and what it must be. */
class Asr4AllFile(val path: String, val bytes: Long, val sha256: String) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The three asr4all sizes, English only. Each is a program (the int8 XNNPACK
 * export, which is the one meant for phones) and the metadata beside it,
 * pinned to a revision so neither can change under its hash.
 */
enum class Asr4AllSize(
    val id: String,
    /** Parameters, in millions, for the size chip. */
    val millions: Int,
    val repo: String,
    val revision: String,
    val program: Asr4AllFile,
    val metadata: Asr4AllFile,
) {
    SMALL(
        "s", 28, "futo-org/asr4all-s", "735bdd8ae688094926068990ccf9b26a80cc43c3",
        Asr4AllFile(
            "executorch/xnnpack_int8/asr_encoder.pte", 38_489_024L,
            "a027754d46abdc7ffcdcc621a1430da7896e4504484657228a22d6eccf283381",
        ),
        Asr4AllFile(
            "executorch/xnnpack_int8/metadata.json", 90_364L,
            "025de70620e2e90fd0e3de947fea4783e0e637ec54d877a5d25c8ad3866e6f48",
        ),
    ),
    MEDIUM(
        "m", 55, "futo-org/asr4all-m", "86f67fdc60fecb4f9662ccd1d99948291db4d905",
        Asr4AllFile(
            "executorch/xnnpack_int8/asr_encoder.pte", 72_395_968L,
            "f50318acb5de7bfc75229f73edcc9276b3553efc9c3e34a7f3f1cf66f76067b4",
        ),
        Asr4AllFile(
            "executorch/xnnpack_int8/metadata.json", 90_363L,
            "321501d89e6249a76b8c0f896f9c3e90089f0f9d6135ab82802e5752990cab41",
        ),
    ),
    LARGE(
        "l", 97, "futo-org/asr4all-l", "2da6534551f007201368514ec08b0b9a60ba39dc",
        Asr4AllFile(
            "executorch/xnnpack_int8/asr_encoder.pte", 123_044_416L,
            "50113d5aa74c916cce33828a3ec69c86aa64b58513a437ecf84fecfae098d351",
        ),
        Asr4AllFile(
            "executorch/xnnpack_int8/metadata.json", 90_362L,
            "05c6fd156f1fa99ea44457ab0d0a8d23b954c272fb614a034476123874e6d125",
        ),
    ),
    ;

    val files: List<Asr4AllFile> get() = listOf(metadata, program)
    val bytes: Long get() = program.bytes + metadata.bytes

    fun url(file: Asr4AllFile): String = "https://huggingface.co/$repo/resolve/$revision/${file.path}"

    companion object {
        fun byId(id: String): Asr4AllSize? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where the asr4all models live: `filesDir/asr4all/<size>/`, apart from the
 * other engines' directories so no engine's cleanup can touch another's files.
 * A file is only ever moved into place whole and hash-checked, so a model is
 * downloaded exactly when both of its files are there at their sizes.
 */
object Asr4AllStore {

    /** asr4all transcribes English and nothing else. */
    const val LANGUAGE = "en"

    fun rootDir(filesDir: File): File = File(filesDir, "asr4all")
    fun dir(filesDir: File, size: Asr4AllSize): File = File(rootDir(filesDir), size.id)
    fun file(filesDir: File, size: Asr4AllSize, file: Asr4AllFile): File = File(dir(filesDir, size), file.name)

    fun isDownloaded(filesDir: File, size: Asr4AllSize): Boolean =
        size.files.all { f -> file(filesDir, size, f).let { it.isFile && it.length() == f.bytes } }

    fun downloaded(filesDir: File): List<Asr4AllSize> = Asr4AllSize.entries.filter { isDownloaded(filesDir, it) }

    /** The size dictation uses: the largest one on the device, since that is the one kept on purpose. */
    fun active(filesDir: File): Asr4AllSize? = downloaded(filesDir).maxByOrNull { it.millions }

    fun bytesOnDisk(filesDir: File, size: Asr4AllSize): Long =
        dir(filesDir, size).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun delete(filesDir: File, size: Asr4AllSize) {
        dir(filesDir, size).deleteRecursively()
    }

    /** Whether [language] (a keyboard language id) is one asr4all transcribes. */
    fun supports(language: String): Boolean =
        language.trim().lowercase().substringBefore('-').substringBefore('_') == LANGUAGE

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
