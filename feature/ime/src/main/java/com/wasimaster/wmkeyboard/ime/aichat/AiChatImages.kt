package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.wasimaster.wmkeyboard.core.reply.ReplyShots
import java.io.File
import java.util.UUID

/**
 * The pictures sent in the AI chat (#698), kept as files beside the saved
 * conversations and named in each message.
 *
 * A picture is scaled the way the reply tool scales a screenshot before it is
 * kept, so what is stored is what a model is sent: small enough for every
 * provider, legible enough to read text in. A chat whose conversations are
 * not kept puts its pictures in the cache instead, which Android may clear,
 * and which a backup never reaches.
 */
object AiChatImages {

    private const val KEPT = "ai/chat_images"
    private const val PASSING = "ai_chat_images"

    /**
     * A picture picked for a message that is not sent yet is not referred to
     * by any conversation, so the clear-out spares anything this young.
     */
    private const val GRACE_MS = 60L * 60L * 1000L

    private fun folder(context: Context, keep: Boolean): File =
        if (keep) File(context.filesDir, KEPT) else File(context.cacheDir, PASSING)

    /** The file behind [name], wherever it was kept; null when it is gone. */
    fun file(context: Context, name: String): File? =
        listOf(true, false).map { File(folder(context, it), name) }.firstOrNull { it.isFile }

    /**
     * Copies the picture at [uri] in, scaled, and returns the name a message
     * carries, or null when it could not be read. Blocking: decodes a bitmap.
     */
    fun import(context: Context, uri: Uri, keep: Boolean): String? {
        val encoded = ReplyShots.encode(context, uri) ?: return null
        return store(context, encoded, keep)
    }

    /**
     * The same for a file the keyboard already holds: a clipboard image, or a
     * screenshot's own copy. Blocking.
     */
    fun import(context: Context, file: File, keep: Boolean): String? {
        val encoded = ReplyShots.encode(file) ?: return null
        return store(context, encoded, keep)
    }

    private fun store(context: Context, encoded: ReplyShots.Encoded, keep: Boolean): String? {
        val bytes = runCatching { Base64.decode(encoded.base64, Base64.NO_WRAP) }.getOrNull() ?: return null
        val name = UUID.randomUUID().toString() + ".jpg"
        return runCatching {
            val dir = folder(context, keep).apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            name
        }.getOrNull()
    }

    /** The JPEG bytes behind [name], or null when the file is gone. */
    fun jpeg(context: Context, name: String): ByteArray? = file(context, name)?.let { runCatching { it.readBytes() }.getOrNull() }

    /**
     * Deletes the pictures no conversation refers to any more: ones whose
     * message was deleted, edited away, or never kept. A file picked in the
     * last hour is spared, since it may belong to a message still being
     * written.
     */
    fun prune(context: Context, referenced: Set<String>, now: Long = System.currentTimeMillis()) {
        for (keep in listOf(true, false)) {
            folder(context, keep).listFiles().orEmpty().forEach { file ->
                if (file.name !in referenced && now - file.lastModified() > GRACE_MS) runCatching { file.delete() }
            }
        }
    }
}
