package com.wasimaster.wmkeyboard.core.emoji

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The kaomoji library in and out of a file the user picks.
 *
 * Export writes this keyboard's own JSON. Import reads that back, a JSON
 * array of plain strings, or plain text with one face per line, optionally
 * followed by a tab and comma-separated tags: the shape every "kaomoji list"
 * pasted off the web already has. Emotic's archives go through [EmoticArchive].
 */
object KaomojiFiles {

    const val FORMAT = "wmkeyboard-kaomoji"
    const val MIME_JSON = "application/json"
    const val DEFAULT_FILE_NAME = "kaomoji.json"

    @Serializable
    private data class FileItem(
        val text: String,
        val tags: List<String> = emptyList(),
        val kind: KaomojiKind? = null,
        val favourite: Boolean = false,
    )

    @Serializable
    private data class FileBody(
        val format: String = FORMAT,
        val version: Int = 1,
        val tags: List<String> = emptyList(),
        val hiddenTags: List<String> = emptyList(),
        val items: List<FileItem> = emptyList(),
    )

    /** What an import found: the faces, and the tag order the file gave. */
    data class Contents(val items: List<KaomojiImport>, val tags: List<String> = emptyList())

    /**
     * The library as an export file. Usage counts stay behind: they describe
     * one phone's habits, not the library.
     */
    fun encode(library: KaomojiLibrary): String {
        val body = FileBody(
            tags = library.tags,
            hiddenTags = library.hiddenTags.toList(),
            items = library.items.map { FileItem(it.text, it.tags, it.kind, it.favourite) },
        )
        return pretty.encodeToString(FileBody.serializer(), body)
    }

    /** Whatever [text] holds as faces, or null when it holds none in any shape this reads. */
    fun decode(text: String): Contents? {
        val trimmed = text.removePrefix("﻿").trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            val body = runCatching { KaomojiStore.json.decodeFromString(FileBody.serializer(), trimmed) }.getOrNull()
            if (body != null && body.items.isNotEmpty()) {
                return Contents(body.items.map { KaomojiImport(it.text, it.tags, it.kind, it.favourite) }, body.tags)
            }
        }
        if (trimmed.startsWith("[")) {
            val array = runCatching { KaomojiStore.json.parseToJsonElement(trimmed) as? JsonArray }.getOrNull()
            val faces = array?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.jsonPrimitive?.contentOrNull }
            if (!faces.isNullOrEmpty()) return Contents(faces.map { KaomojiImport(it) })
        }
        val lines = trimmed.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val tab = line.indexOf('\t')
            if (tab < 0) {
                KaomojiImport(line)
            } else {
                KaomojiImport(line.substring(0, tab), line.substring(tab + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.let { Contents(it) }
    }

    private val pretty = kotlinx.serialization.json.Json {
        prettyPrint = true
        encodeDefaults = true
    }
}
