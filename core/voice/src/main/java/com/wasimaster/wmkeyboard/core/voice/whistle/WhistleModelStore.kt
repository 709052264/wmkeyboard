package com.wasimaster.wmkeyboard.core.voice.whistle

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import java.io.File
import java.security.MessageDigest

/**
 * Where the one Whistle model lives: `filesDir/whistle/whistle.cact`, apart
 * from Whisper's `filesDir/whisper/` so neither engine's cleanup can touch the
 * other's files.
 */
object WhistleModelStore {
    const val MODEL_FILE_NAME = "whistle.cact"
    const val MODEL_BYTES = 16_919_407L
    const val MODEL_SHA256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"

    /** Pinned to a revision, so the file behind the URL cannot change under [MODEL_SHA256]. */
    const val MODEL_URL =
        "https://huggingface.co/Cactus-Compute/whistle/resolve/b358ddadd89b7a713b5aa131f23032d3cca1b251/whistle.cact"

    fun rootDir(filesDir: File): File = File(filesDir, "whistle")
    fun modelFile(filesDir: File): File = File(rootDir(filesDir), MODEL_FILE_NAME)
    fun partialFile(filesDir: File): File = File(rootDir(filesDir), "$MODEL_FILE_NAME.part")

    /** The finished file is in place. Cheap: the hash was checked before it was moved there. */
    fun isDownloaded(filesDir: File): Boolean = modelFile(filesDir).let { it.isFile && it.length() == MODEL_BYTES }

    fun bytesOnDisk(filesDir: File): Long =
        (if (modelFile(filesDir).isFile) modelFile(filesDir).length() else 0L) + FileDownloader.bytesDone(partialFile(filesDir))

    fun hasValidChecksum(file: File): Boolean {
        if (!file.isFile || file.length() != MODEL_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return IntegrityChecks.accept(
            digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256,
            MODEL_FILE_NAME,
        )
    }

    fun delete(filesDir: File) {
        rootDir(filesDir).deleteRecursively()
    }

    /**
     * Puts a `whistle.cact` the user fetched some other way where a download
     * would have put it. False, and nothing touched, when it is not the
     * pinned file.
     */
    fun install(filesDir: File, source: File): Boolean {
        if (!hasValidChecksum(source)) return false
        val target = modelFile(filesDir)
        target.parentFile?.mkdirs()
        val part = partialFile(filesDir)
        return try {
            source.inputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            target.delete()
            part.renameTo(target)
        } finally {
            part.delete()
        }
    }
}
