package com.wasimaster.wmkeyboard.core.emoji

import android.database.sqlite.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Reads an export of Emotic (F-Droid `futuristicgoo.emotic`), the emoticon
 * and reaction-image app, so its library can move into this keyboard.
 *
 * Emotic writes two shapes:
 * - since 0.1.11, a gzipped tar holding `emoticdb.sqlite` and the reaction
 *   images ("emotipics") under `media/images/`;
 * - before that, the bare `emoticdb.sqlite`.
 *
 * The database keeps faces in `emoticons`, tags in `tags` joined through
 * `emoticon_to_tags`, and the user's order in `emoticons_ordering` and
 * `tags_ordering` (REAL sort keys; missing rows sort last). Emotipics live in
 * `emotipics_images` with their own tag tables. Older files lack the ordering
 * tables or the image tables, so every query checks the table is there.
 *
 * The archive is untrusted input. Only the two known entry shapes are taken,
 * an entry's path is never joined onto a directory (images are written by a
 * cleaned base name), and every size is capped.
 */
object EmoticArchive {

    const val DB_ENTRY = "emoticdb.sqlite"
    private const val IMAGE_PREFIX = "media/images/"

    private const val MAX_DB_BYTES = 64L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_IMAGES = 5000

    /** One reaction image, extracted into the staging folder. */
    data class Image(val file: File, val tags: List<String>)

    data class Contents(
        val faces: List<KaomojiImport>,
        val tags: List<String>,
        val images: List<Image>,
    )

    /**
     * Extracts [input] into [staging] (emptied first) and reads it. Throws
     * [IOException] for a file that is neither shape or holds no database.
     */
    fun read(input: InputStream, staging: File): Contents {
        staging.deleteRecursively()
        val imagesDir = File(staging, "images")
        imagesDir.mkdirs()
        val db = File(staging, DB_ENTRY)
        val buffered = BufferedInputStream(input)
        buffered.mark(HEADER_PEEK)
        val head = ByteArray(HEADER_PEEK)
        val got = readFully(buffered, head)
        buffered.reset()
        when {
            got >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() ->
                extractTar(GZIPInputStream(buffered), db, imagesDir)
            got >= SQLITE_MAGIC.size && head.copyOf(SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC) ->
                copyCapped(buffered, db, MAX_DB_BYTES)
            got >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar" ->
                extractTar(buffered, db, imagesDir)
            else -> throw IOException("not an Emotic export")
        }
        if (!db.isFile) throw IOException("no $DB_ENTRY in the archive")
        return readDatabase(db, imagesDir)
    }

    private fun extractTar(stream: InputStream, db: File, imagesDir: File) {
        var total = 0L
        var images = 0
        TarStream(stream).forEach { name, size, body ->
            total += size
            if (total > MAX_TOTAL_BYTES) throw IOException("archive too large")
            val path = name.trimStart('.', '/')
            when {
                path == DB_ENTRY -> copyCapped(body, db, MAX_DB_BYTES)
                path.startsWith(IMAGE_PREFIX) && images < MAX_IMAGES -> {
                    val base = safeName(path.substringAfterLast('/')) ?: return@forEach
                    copyCapped(body, File(imagesDir, base), MAX_IMAGE_BYTES)
                    images++
                }
            }
        }
    }

    private fun readDatabase(dbFile: File, imagesDir: File): Contents {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = HashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
            val faces = if ("emoticons" in tables) readFaces(db, tables) else emptyList()
            val tagOrder = if ("tags" in tables) {
                orderedNames(db, "tags", "tag_id", "tag_name", "tags_ordering", "tag_user_order", tables)
            } else {
                emptyList()
            }
            val images = readImages(db, tables, imagesDir)
            return Contents(faces, tagOrder, images)
        } finally {
            db.close()
        }
    }

    private fun readFaces(db: SQLiteDatabase, tables: Set<String>): List<KaomojiImport> {
        val tagsById = HashMap<Long, MutableList<String>>()
        if ("emoticon_to_tags" in tables && "tags" in tables) {
            db.rawQuery(
                "SELECT j.id, t.tag_name FROM emoticon_to_tags j JOIN tags t ON j.tag_id = t.tag_id",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val list = tagsById.getOrPut(c.getLong(0)) { ArrayList() }
                    val tag = c.getString(1) ?: continue
                    // The join table has no unique key and holds repeats.
                    if (tag !in list) list.add(tag)
                }
            }
        }
        val sql = if ("emoticons_ordering" in tables) {
            "SELECT e.id, e.text FROM emoticons e LEFT JOIN emoticons_ordering o ON e.id = o.emoticon_id " +
                "ORDER BY o.emoticon_user_order IS NULL, o.emoticon_user_order, e.id"
        } else {
            "SELECT id, text FROM emoticons ORDER BY id"
        }
        val out = ArrayList<KaomojiImport>()
        val seen = HashSet<String>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(1) ?: continue
                if (!seen.add(text)) continue
                out.add(KaomojiImport(text, tagsById[c.getLong(0)].orEmpty()))
            }
        }
        return out
    }

    private fun readImages(db: SQLiteDatabase, tables: Set<String>, imagesDir: File): List<Image> {
        val extracted = imagesDir.listFiles()?.filter { it.isFile }?.associateBy { it.name }.orEmpty()
        if (extracted.isEmpty()) return emptyList()
        if ("emotipics_images" !in tables) return extracted.values.sortedBy { it.name }.map { Image(it, emptyList()) }
        val tagsById = HashMap<Long, MutableList<String>>()
        if ("emotipics_to_tags_join" in tables && "emotipics_tags" in tables) {
            db.rawQuery(
                "SELECT j.emotipic_id, t.tag_name FROM emotipics_to_tags_join j " +
                    "JOIN emotipics_tags t ON j.tag_id = t.tag_id",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val tag = c.getString(1) ?: continue
                    val list = tagsById.getOrPut(c.getLong(0)) { ArrayList() }
                    if (tag !in list) list.add(tag)
                }
            }
        }
        val sql = if ("emotipics_image_ordering" in tables) {
            "SELECT i.emotipic_id, i.emotipic_uri, i.is_excluded FROM emotipics_images i " +
                "LEFT JOIN emotipics_image_ordering o ON i.emotipic_id = o.emotipic_id " +
                "ORDER BY o.user_order IS NULL, o.user_order, i.emotipic_id"
        } else {
            "SELECT emotipic_id, emotipic_uri, is_excluded FROM emotipics_images ORDER BY emotipic_id"
        }
        val out = ArrayList<Image>()
        val used = HashSet<String>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                // "emotic:media/images/<name>" in an export.
                val base = safeName(c.getString(1)?.substringAfterLast('/') ?: continue) ?: continue
                val file = extracted[base] ?: continue
                // Marked as excluded in Emotic: claimed, so the sweep below skips it too.
                if (!used.add(base)) continue
                if (!c.isNull(2) && c.getInt(2) != 0) continue
                out.add(Image(file, tagsById[c.getLong(0)].orEmpty()))
            }
        }
        // Files the table does not list still came in the archive; keep them, untagged.
        extracted.values.filter { it.name !in used }.sortedBy { it.name }.forEach { out.add(Image(it, emptyList())) }
        return out
    }

    /** Names of [table] in the user's order from [orderTable], unordered rows last. */
    private fun orderedNames(
        db: SQLiteDatabase,
        table: String,
        idCol: String,
        nameCol: String,
        orderTable: String,
        orderCol: String,
        tables: Set<String>,
    ): List<String> {
        val sql = if (orderTable in tables) {
            "SELECT t.$nameCol FROM $table t LEFT JOIN $orderTable o ON t.$idCol = o.$idCol " +
                "ORDER BY o.$orderCol IS NULL, o.$orderCol, t.$idCol"
        } else {
            "SELECT $nameCol FROM $table ORDER BY $idCol"
        }
        val out = ArrayList<String>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) c.getString(0)?.let { if (it !in out) out.add(it) }
        }
        return out
    }

    /** A base name safe to create inside the staging folder, or null. */
    internal fun safeName(name: String): String? {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
        if (clean.isEmpty() || clean == "." || clean == ".." || clean.length > 120) return null
        if (clean.any { it.isISOControl() || it == ':' }) return null
        return clean
    }

    private fun copyCapped(input: InputStream, target: File, cap: Long) {
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            val buffer = ByteArray(BUFFER)
            var written = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                if (written > cap) {
                    out.close()
                    target.delete()
                    throw IOException("${target.name} is too large")
                }
                out.write(buffer, 0, n)
            }
        }
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private const val HEADER_PEEK = 512
    private const val BUFFER = 64 * 1024
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}

/**
 * Just enough of a tar reader for an Emotic archive: ustar and old-style
 * headers, GNU long names (`L`) and pax `path` records (`x`). Regular files
 * are handed to the callback as a stream bounded to the entry; anything else
 * is skipped.
 */
internal class TarStream(private val input: InputStream) {

    fun forEach(onFile: (name: String, size: Long, body: InputStream) -> Unit) {
        val header = ByteArray(BLOCK)
        var longName: String? = null
        while (true) {
            if (!readBlock(header)) return
            if (header.all { it == 0.toByte() }) return
            val size = parseSize(header)
            if (size < 0) throw IOException("bad tar header")
            val type = header[156].toInt().toChar()
            val name = longName ?: headerName(header)
            longName = null
            val body = Bounded(input, size)
            when (type) {
                '0', '\u0000', '7' -> onFile(name, size, body)
                'L' -> longName = String(body.readBytes(MAX_META), Charsets.UTF_8).trimEnd('\u0000')
                'x' -> longName = paxPath(String(body.readBytes(MAX_META), Charsets.UTF_8))
                else -> Unit
            }
            body.skipRest()
            val pad = (BLOCK - size % BLOCK) % BLOCK
            skipFully(pad)
        }
    }

    private fun headerName(header: ByteArray): String {
        val name = field(header, 0, 100)
        val ustar = String(header, 257, 5, Charsets.US_ASCII) == "ustar"
        val prefix = if (ustar) field(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    /** Octal size, or base-256 when the top bit of the field is set. */
    private fun parseSize(header: ByteArray): Long {
        if (header[124].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in 125 until 136) value = (value shl 8) or (header[i].toLong() and 0xff)
            return value
        }
        val text = field(header, 124, 12).trim()
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: -1
    }

    private fun paxPath(records: String): String? =
        records.lineSequence().mapNotNull { line ->
            val record = line.substringAfter(' ', "")
            if (record.startsWith("path=")) record.removePrefix("path=") else null
        }.firstOrNull()

    private fun readBlock(into: ByteArray): Boolean {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) {
                // A clean end between entries; anything else is a cut-off file.
                if (off == 0) return false
                throw IOException("truncated tar")
            }
            off += n
        }
        return true
    }

    private fun skipFully(count: Long) {
        var left = count
        val scratch = ByteArray(BLOCK)
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, BLOCK.toLong()).toInt())
            if (n < 0) throw IOException("truncated tar")
            left -= n
        }
    }

    /** The entry's bytes and no more; closing it does not close the archive. */
    private class Bounded(private val source: InputStream, size: Long) : InputStream() {
        private var left = size

        override fun read(): Int {
            if (left <= 0) return -1
            val b = source.read()
            if (b < 0) throw IOException("truncated tar")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("truncated tar")
            left -= n
            return n
        }

        override fun close() = Unit

        fun readBytes(cap: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BLOCK)
            while (out.size() < cap) {
                val n = read(buffer, 0, minOf(buffer.size, cap - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        fun skipRest() {
            val buffer = ByteArray(BLOCK)
            while (read(buffer, 0, buffer.size) >= 0) Unit
        }
    }

    private companion object {
        const val BLOCK = 512
        const val MAX_META = 64 * 1024
    }
}
