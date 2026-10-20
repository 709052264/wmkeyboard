package com.wasimaster.wmkeyboard.core.localllm

import java.io.File

/**
 * Recognises a GGUF file, the format llama.cpp runs, by its content: every
 * GGUF starts with the four bytes `GGUF`. The name is no guide on its own,
 * since a file saved from a browser or a chat app can lose its extension.
 */
object GgufFile {

    const val EXTENSION = "gguf"

    private val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** Whether [header] (a file's first bytes) opens a GGUF. */
    fun hasMagic(header: ByteArray): Boolean =
        header.size >= MAGIC.size && (MAGIC.indices).all { header[it] == MAGIC[it] }

    /** Whether [file] is a GGUF, read from its first four bytes. False when it cannot be read. */
    fun isGguf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(MAGIC.size)
            input.read(header) == MAGIC.size && hasMagic(header)
        }
    }.getOrDefault(false)

    /** Whether [file] is run by llama.cpp rather than LiteRT-LM: its extension, else its content. */
    fun runsOnLlamaCpp(file: File): Boolean =
        file.extension.equals(EXTENSION, ignoreCase = true) ||
            (ModelFormat.entries.none { it.extension.equals(file.extension, ignoreCase = true) } && isGguf(file))
}
