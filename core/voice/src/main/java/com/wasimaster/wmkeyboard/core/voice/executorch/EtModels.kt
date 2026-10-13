package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.File
import java.security.MessageDigest

/**
 * The dictation engines that run on [EtNative]. [id] is the value the
 * Recognition engine setting stores; [dirName] is the folder under `filesDir`.
 * [languages] are the base language codes the engine transcribes.
 */
enum class EtEngine(val id: String, val dirName: String, val languages: Set<String>) {
    ASR4ALL("asr4all", "asr4all", setOf("en")),

    /** Parakeet TDT v3: 25 European languages, which it tells apart itself. */
    PARAKEET(
        "parakeet", "parakeet",
        setOf(
            "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
            "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
        ),
    ),
    MOONSHINE("moonshine", "moonshine", setOf("en")),
    ;

    /** Whether [languageId] (a keyboard language id such as `en_GB` or `de`) is one this engine transcribes. */
    fun supports(languageId: String): Boolean =
        languageId.trim().lowercase().substringBefore('-').substringBefore('_') in languages

    companion object {
        fun byId(id: String): EtEngine? = entries.firstOrNull { it.id == id }
    }
}

/** One file of a model: where it lives in the repo, and what it must be. */
class EtFile(val path: String, val bytes: Long, val sha256: String) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * One downloadable model, pinned to a repo revision so no file can change
 * under its hash. [label] is the model's own name, never translated.
 */
class EtModel(
    val id: String,
    val engine: EtEngine,
    /** Folder under the engine's folder. Asr4All's were "s", "m" and "l" before there was this class. */
    val dirName: String,
    val label: String,
    /** Parameters, in millions; the largest one downloaded is the one used. */
    val millions: Int,
    val repo: String,
    val revision: String,
    val files: List<EtFile>,
) {
    val bytes: Long get() = files.sumOf { it.bytes }

    fun url(file: EtFile): String = "https://huggingface.co/$repo/resolve/$revision/${file.path}"

    fun file(name: String): EtFile = files.first { it.name == name }
}

/** Every model the ExecuTorch engines can download. */
object EtCatalog {

    private const val ASR4ALL_PTE = "executorch/xnnpack_int8/asr_encoder.pte"
    private const val ASR4ALL_META = "executorch/xnnpack_int8/metadata.json"

    val models: List<EtModel> = listOf(
        // Small files first in each list: a bad connection fails before the big one.
        EtModel(
            "asr4all-s", EtEngine.ASR4ALL, "s", "Asr4All Small", 28,
            "futo-org/asr4all-s", "735bdd8ae688094926068990ccf9b26a80cc43c3",
            listOf(
                EtFile(ASR4ALL_META, 90_364L, "025de70620e2e90fd0e3de947fea4783e0e637ec54d877a5d25c8ad3866e6f48"),
                EtFile(ASR4ALL_PTE, 38_489_024L, "a027754d46abdc7ffcdcc621a1430da7896e4504484657228a22d6eccf283381"),
            ),
        ),
        EtModel(
            "asr4all-m", EtEngine.ASR4ALL, "m", "Asr4All Medium", 55,
            "futo-org/asr4all-m", "86f67fdc60fecb4f9662ccd1d99948291db4d905",
            listOf(
                EtFile(ASR4ALL_META, 90_363L, "321501d89e6249a76b8c0f896f9c3e90089f0f9d6135ab82802e5752990cab41"),
                EtFile(ASR4ALL_PTE, 72_395_968L, "f50318acb5de7bfc75229f73edcc9276b3553efc9c3e34a7f3f1cf66f76067b4"),
            ),
        ),
        EtModel(
            "asr4all-l", EtEngine.ASR4ALL, "l", "Asr4All Large", 97,
            "futo-org/asr4all-l", "2da6534551f007201368514ec08b0b9a60ba39dc",
            listOf(
                EtFile(ASR4ALL_META, 90_362L, "05c6fd156f1fa99ea44457ab0d0a8d23b954c272fb614a034476123874e6d125"),
                EtFile(ASR4ALL_PTE, 123_044_416L, "50113d5aa74c916cce33828a3ec69c86aa64b58513a437ecf84fecfae098d351"),
            ),
        ),
        // NVIDIA's parakeet-tdt-0.6b-v3 (CC-BY-4.0), exported by an ExecuTorch
        // maintainer with 8-bit activations and 4-bit weights for XNNPACK.
        EtModel(
            "parakeet-v3", EtEngine.PARAKEET, "tdt-0.6b-v3", "Parakeet TDT 0.6B v3", 600,
            "larryliu0820/parakeet-tdt-0.6b-v3-executorch", "17c1a8fd222fcab46c4053fc230363d20d6ee572",
            listOf(
                EtFile(
                    "xnnpack/int4/tokenizer.model", 360_916L,
                    "eacec2b0a77f336d4a2ca4a25a7047575d3c2b74de47e997f4c205126ed3135e",
                ),
                EtFile(
                    "xnnpack/int4/model.pte", 719_223_704L,
                    "cd9c75c84b49c4bf2b20a383742e413fe49cd1eb9c01c3fd84c5ac7fd496453e",
                ),
            ),
        ),
        // Useful Sensors' Moonshine Tiny (MIT), exported by Software Mansion
        // for react-native-executorch.
        EtModel(
            "moonshine-tiny", EtEngine.MOONSHINE, "tiny", "Moonshine Tiny", 27,
            "software-mansion/react-native-executorch-moonshine-tiny", "6af58e7ab467f655d034eedc7acc756c1ff9b2c2",
            listOf(
                EtFile(
                    "moonshine_tiny_tokenizer.json", 1_985_530L,
                    "6579793438bc4fbafffacf699169ff53e3769c5a0a0f5e71cdee8853e8130deb",
                ),
                EtFile(
                    "xnnpack/moonshine_tiny_xnnpack_encoder.pte", 30_870_016L,
                    "737f46a5c683ba0edaa0aaf638e02ea2e14a799906213b6c6dfd3ed8ea4d5f24",
                ),
                EtFile(
                    "xnnpack/moonshine_tiny_xnnpack_decoder.pte", 118_008_224L,
                    "89481cfa1391e9962d8ff710684a80d0900d8d39cbf34764e5f28ed8c0f9b9b0",
                ),
            ),
        ),
    )

    fun byId(id: String): EtModel? = models.firstOrNull { it.id == id }

    fun forEngine(engine: EtEngine): List<EtModel> = models.filter { it.engine == engine }
}

/**
 * Where the ExecuTorch models live: `filesDir/<engine>/<model>/`, apart from
 * the other engines' folders so no engine's cleanup can touch another's. A
 * file is only ever moved into place whole and hash-checked, so a model is
 * downloaded exactly when all of its files are there at their sizes.
 */
object EtStore {

    fun rootDir(filesDir: File, engine: EtEngine): File = File(filesDir, engine.dirName)
    fun dir(filesDir: File, model: EtModel): File = File(rootDir(filesDir, model.engine), model.dirName)
    fun file(filesDir: File, model: EtModel, file: EtFile): File = File(dir(filesDir, model), file.name)
    fun file(filesDir: File, model: EtModel, name: String): File = file(filesDir, model, model.file(name))

    fun isDownloaded(filesDir: File, model: EtModel): Boolean =
        model.files.all { f -> file(filesDir, model, f).let { it.isFile && it.length() == f.bytes } }

    fun downloaded(filesDir: File, engine: EtEngine): List<EtModel> =
        EtCatalog.forEngine(engine).filter { isDownloaded(filesDir, it) }

    /** The model an engine dictates with: the largest one on the device, since that is the one kept on purpose. */
    fun active(filesDir: File, engine: EtEngine): EtModel? = downloaded(filesDir, engine).maxByOrNull { it.millions }

    fun bytesOnDisk(filesDir: File, model: EtModel): Long =
        dir(filesDir, model).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun delete(filesDir: File, model: EtModel) {
        dir(filesDir, model).deleteRecursively()
    }

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
