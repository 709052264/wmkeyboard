package com.wasimaster.wmkeyboard.core.modules

import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * The delivered native libraries on disk: `<root>/<sha256>/<name>`, where
 * root is `noBackupFilesDir/natives` (never backed up or restored: a library
 * belongs to the build that pinned it, and the next build may pin another).
 *
 * A library is installed when its file exists at the pinned size. Its hash
 * is checked once, at [install]; after that the file is read-only and in
 * the app's private storage, so reading 20 MB at every process start to hash
 * it again would buy nothing.
 */
class NativeStore(val root: File) {

    fun fileOf(pin: NativePin): File = File(File(root, pin.sha256), pin.name)

    fun installed(pin: NativePin): Boolean = fileOf(pin).length() == pin.size

    /** Where a download of [pin] is staged. */
    fun partOf(pin: NativePin): File = File(root, "${pin.sha256}.part")

    /**
     * Moves [staged] into place as [pin] when its size and SHA-256 match,
     * deleting [staged] either way unless [keepSource]. The library is
     * written into a file that is made read-only before any byte of it is
     * written: Android 14 refuses to load code from a file the app could
     * still change, and setting it afterwards would leave a window.
     */
    fun install(pin: NativePin, staged: File, keepSource: Boolean = false): Boolean {
        try {
            val sizeOk = staged.length() == pin.size
            val matches = sizeOk && sha256(staged) == pin.sha256
            if (!IntegrityChecks.accept(matches, "native ${pin.name} ${pin.sha256}")) return false
            val target = fileOf(pin)
            target.parentFile?.mkdirs()
            if (target.exists()) {
                target.setWritable(true)
                target.delete()
            }
            val temp = File(target.parentFile, ".${pin.name}.tmp")
            temp.delete()
            FileOutputStream(temp).use { out ->
                temp.setReadOnly()
                FileInputStream(staged).use { it.copyTo(out, BUFFER) }
                out.fd.sync()
            }
            return temp.renameTo(target)
        } finally {
            if (!keepSource) staged.delete()
        }
    }

    /** The pin among [pins] whose hash [file] has, or null. For an import. */
    fun pinFor(file: File, pins: List<NativePin>): NativePin? {
        val candidates = pins.filter { it.size == file.length() }
        if (candidates.isEmpty()) return null
        val hash = sha256(file)
        return candidates.firstOrNull { it.sha256 == hash }
    }

    /**
     * Deletes every library directory no pin in [pins] names, and stale
     * staging files. An app update that changed a library leaves the old
     * one behind otherwise.
     */
    fun prune(pins: List<NativePin>) {
        val keep = pins.mapTo(HashSet()) { it.sha256 }
        root.listFiles()?.forEach { child ->
            val stale = when {
                child.isDirectory -> child.name !in keep
                child.name.endsWith(".part") -> child.name.removeSuffix(".part") !in keep
                else -> true
            }
            if (stale) {
                child.walkBottomUp().forEach { it.setWritable(true) }
                child.deleteRecursively()
            }
        }
    }

    /** Bytes the delivered libraries take. */
    fun bytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val BUFFER = 64 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
