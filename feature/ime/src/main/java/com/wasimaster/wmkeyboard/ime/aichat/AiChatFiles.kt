package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wasimaster.wmkeyboard.core.aichat.AiChatFile
import com.wasimaster.wmkeyboard.core.aichat.AiChatStore
import java.io.File
import java.util.UUID

/**
 * The files sent in the AI chat (#882), as the File button in the "+" menu
 * picks them: the same shape as [AiChatImages], beside it.
 *
 * A text file is read here, once, into the message, so nothing is copied. A
 * recording, a video or a PDF is copied into the chat's own folder, since a
 * picker's read grant does not outlast the screen that asked for it, and only
 * a model that takes such a file whole is ever sent its bytes. Anything else
 * is refused: there is no model it could reach.
 */
object AiChatFiles {

    private const val KEPT = "ai/chat_files"
    private const val PASSING = "ai_chat_files"

    /** A copy picked for a message not sent yet belongs to no conversation; see [AiChatImages]. */
    private const val GRACE_MS = 60L * 60L * 1000L

    /**
     * The largest file copied in. Gemini, the one service sent the bytes,
     * takes about 20 MB inline in one request, base64 and the rest of the
     * conversation included.
     */
    const val MAX_BYTES = 14L * 1024L * 1024L

    /** What a pick came to. */
    sealed interface Imported {
        data class Done(val file: AiChatFile) : Imported

        /** A picture, which goes with the message's pictures instead. */
        data object Picture : Imported

        data object TooBig : Imported

        /** Neither text nor anything a model takes whole, or it could not be read. */
        data object Unreadable : Imported
    }

    private fun folder(context: Context, keep: Boolean): File =
        if (keep) File(context.filesDir, KEPT) else File(context.cacheDir, PASSING)

    /** The copy behind [stored], wherever it was kept; null when it is gone. */
    fun file(context: Context, stored: String): File? =
        listOf(true, false).map { File(folder(context, it), stored) }.firstOrNull { it.isFile }

    /** Whether a file of [mime] is sent whole to a model that takes it, rather than read as text. */
    fun isMedia(mime: String): Boolean =
        mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/pdf"

    /**
     * Reads or copies the file at [uri]. Blocking: it reads the whole file.
     * A picture is handed back as [Imported.Picture] for the caller to send
     * with [AiChatImages.import], so it is scaled like every other picture.
     */
    fun import(context: Context, uri: Uri, keep: Boolean): Imported {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n).orEmpty()
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "file" }
        val mime = (runCatching { resolver.getType(uri) }.getOrNull() ?: "").ifEmpty { mimeFromName(name) }
        return when {
            mime.startsWith("image/") -> Imported.Picture
            isMedia(mime) -> copy(context, uri, name, mime, size, keep)
            isText(mime, name) -> read(context, uri, name, mime, size)
            else -> Imported.Unreadable
        }
    }

    private fun copy(context: Context, uri: Uri, name: String, mime: String, size: Long, keep: Boolean): Imported {
        if (size > MAX_BYTES) return Imported.TooBig
        val extension = name.substringAfterLast('.', "").take(8).filter { it.isLetterOrDigit() }
        val stored = UUID.randomUUID().toString() + if (extension.isEmpty()) "" else ".$extension"
        val target = runCatching { File(folder(context, keep).apply { mkdirs() }, stored) }.getOrNull()
            ?: return Imported.Unreadable
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        // The picker's size can be missing or wrong; the bytes are not.
                        if (total > MAX_BYTES) return@use -1L
                        output.write(buffer, 0, read)
                    }
                    total
                }
            } ?: 0L
        }.getOrDefault(0L)
        if (copied <= 0L) {
            runCatching { target.delete() }
            return if (copied < 0L) Imported.TooBig else Imported.Unreadable
        }
        return Imported.Done(AiChatFile(name = name, mime = mime, size = copied, stored = stored))
    }

    private fun read(context: Context, uri: Uri, name: String, mime: String, size: Long): Imported {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // A little more than the cap allows, to know whether it was cut.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                while (out.size() < TEXT_READ_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return Imported.Unreadable
        // A zero byte is no text file, whatever its name says.
        if (bytes.any { it == 0.toByte() }) return Imported.Unreadable
        val text = String(bytes, Charsets.UTF_8).let {
            if (it.length <= AiChatStore.MAX_TEXT) it else it.substring(0, AiChatStore.MAX_TEXT)
        }
        if (text.isBlank()) return Imported.Unreadable
        return Imported.Done(AiChatFile(name = name, mime = mime.ifEmpty { "text/plain" }, size = size.coerceAtLeast(0L), text = text))
    }

    /**
     * Deletes the copies no conversation refers to any more, sparing one
     * picked in the last hour, as [AiChatImages.prune] does.
     */
    fun prune(context: Context, referenced: Set<String>, now: Long = System.currentTimeMillis()) {
        for (keep in listOf(true, false)) {
            folder(context, keep).listFiles().orEmpty().forEach { file ->
                if (file.name !in referenced && now - file.lastModified() > GRACE_MS) runCatching { file.delete() }
            }
        }
    }

    private fun isText(mime: String, name: String): Boolean =
        mime.startsWith("text/") || mime in TEXT_TYPES ||
            name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun mimeFromName(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty()

    private val TEXT_TYPES = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript",
        "application/x-yaml", "application/yaml", "application/toml", "application/x-sh",
        "application/sql", "application/x-subrip", "application/rtf",
    )

    /** Files that are text whatever a picker calls them: source code, mostly, which often comes as octet-stream. */
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
        "log", "html", "htm", "css", "js", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs",
        "c", "h", "cpp", "hpp", "cs", "swift", "php", "sh", "bash", "zsh", "sql", "lua", "srt", "vtt", "tex",
        "gradle", "properties", "rtf",
    )

    private const val BUFFER = 64 * 1024

    /** Four bytes a character at worst, so the cap in characters is always reached. */
    private const val TEXT_READ_BYTES = AiChatStore.MAX_TEXT * 4
}
