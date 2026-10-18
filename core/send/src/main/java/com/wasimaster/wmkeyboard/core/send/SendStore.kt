package com.wasimaster.wmkeyboard.core.send

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What this device sent and received, as two JSON files under the directory
 * the host hands over (`filesDir/send`). The uploads carry owner tokens, which
 * is why the host keeps the directory out of backups: a token lets anyone
 * delete the file or change its limits.
 *
 * Everything is read and written under one lock and rewritten whole; the lists
 * are short (expired uploads are dropped on every read).
 */
class SendStore(private val dir: File) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val uploadsFile get() = File(dir, "uploads.json")
    private val receivedFile get() = File(dir, "received.json")

    fun uploads(nowMs: Long = System.currentTimeMillis()): List<SendUpload> = synchronized(lock) {
        val all = read(uploadsFile, SendUpload.serializer())
        val live = all.filterNot { it.expired(nowMs) }
        if (live.size != all.size) write(uploadsFile, live, SendUpload.serializer())
        live.sortedByDescending { it.createdAt }
    }

    fun add(upload: SendUpload) = synchronized(lock) {
        val rest = read(uploadsFile, SendUpload.serializer()).filterNot { it.id == upload.id && it.instance == upload.instance }
        write(uploadsFile, (rest + upload).takeLast(MAX_UPLOADS), SendUpload.serializer())
    }

    fun update(upload: SendUpload) = add(upload)

    fun remove(upload: SendUpload) = synchronized(lock) {
        write(
            uploadsFile,
            read(uploadsFile, SendUpload.serializer()).filterNot { it.id == upload.id && it.instance == upload.instance },
            SendUpload.serializer(),
        )
    }

    fun received(): List<SendReceived> = synchronized(lock) {
        read(receivedFile, SendReceived.serializer()).sortedByDescending { it.at }
    }

    fun addReceived(item: SendReceived) = synchronized(lock) {
        val rest = read(receivedFile, SendReceived.serializer()).filterNot { it.location == item.location }
        write(receivedFile, (rest + item).takeLast(MAX_RECEIVED), SendReceived.serializer())
    }

    fun removeReceived(location: String) = synchronized(lock) {
        write(receivedFile, read(receivedFile, SendReceived.serializer()).filterNot { it.location == location }, SendReceived.serializer())
    }

    fun clearReceived() = synchronized(lock) { write(receivedFile, emptyList(), SendReceived.serializer()) }

    private fun <T> read(file: File, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(serializer), file.readText()) }.getOrDefault(emptyList())
    }

    private fun <T> write(file: File, items: List<T>, serializer: kotlinx.serialization.KSerializer<T>) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(serializer), items))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val DIR = "send"
        const val MAX_UPLOADS = 200
        const val MAX_RECEIVED = 200
    }
}
