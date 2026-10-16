package com.wasimaster.wmkeyboard.core.media.gallery

import android.content.Context
import java.io.File

/**
 * What the gallery tool has sent, most recent first (#708): the "Sent" album,
 * and the small mark on a photo that went out before.
 *
 * Only MediaStore row ids, one per line, in the app's own files: no picture,
 * no name, nothing about where it went. A row that is deleted from the
 * library simply stops matching. Not written from an incognito field.
 */
class GallerySentHistory(private val file: File) {

    private var cache: List<Long>? = null

    /** Ids, most recent send first. */
    @Synchronized
    fun ids(): List<Long> = cache ?: load().also { cache = it }

    @Synchronized
    fun record(sent: List<Long>) {
        if (sent.isEmpty()) return
        val next = merge(ids(), sent)
        cache = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(next.joinToString("\n"))
            if (!tmp.renameTo(file)) {
                file.writeText(next.joinToString("\n"))
                tmp.delete()
            }
        }
    }

    @Synchronized
    fun clear() {
        cache = emptyList()
        runCatching { file.delete() }
    }

    private fun load(): List<Long> = runCatching {
        if (!file.isFile) emptyList() else file.readLines().mapNotNull { it.trim().toLongOrNull() }.distinct().take(MAX)
    }.getOrDefault(emptyList())

    companion object {
        const val FILE_NAME = "gallery_sent.txt"

        @Volatile
        private var shared: GallerySentHistory? = null

        /**
         * The one history of the process: the keyboard and the settings app
         * share a process, and a clear from the settings screen has to reach
         * the keyboard's copy of the list.
         */
        fun get(context: Context): GallerySentHistory = shared ?: synchronized(this) {
            shared ?: GallerySentHistory(File(context.applicationContext.filesDir, FILE_NAME)).also { shared = it }
        }

        /** How many sends are remembered. */
        const val MAX = 200

        /**
         * [sent] in front of [old], in the order they went, without
         * repeats and no longer than [MAX]. The last of a batch to go is the
         * most recent, so it leads.
         */
        fun merge(old: List<Long>, sent: List<Long>): List<Long> {
            val front = sent.asReversed().distinct()
            return (front + old.filterNot { it in front }).take(MAX)
        }
    }
}
