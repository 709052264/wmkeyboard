package com.wasimaster.wmkeyboard.core.semantic

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Vectors for one kind of thing (clips, notes, snippets, emoji, stickers),
 * each under the key its store knows it by, with a stamp saying which
 * version of it was embedded, so an edit is noticed and an unchanged item is
 * never embedded twice.
 *
 * Ranking is brute force: a dot product with every vector, which for a few
 * thousand 768-float vectors is a few milliseconds. Every vector is stored at
 * unit length, so that dot product is the cosine.
 *
 * Not thread-safe; the owner serialises access.
 */
class VectorIndex(val dimensions: Int = EmbeddingInput.DIMENSIONS) {

    private class Entry(val stamp: Long, val vector: FloatArray)

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = entries.size

    val keys: Set<String> get() = entries.keys

    operator fun contains(key: String): Boolean = key in entries

    /** The stamp [key] was embedded under, or null when it is not here. */
    fun stampOf(key: String): Long? = entries[key]?.stamp

    fun put(key: String, stamp: Long, vector: FloatArray) {
        require(vector.size == dimensions) { "vector has ${vector.size} values, not $dimensions" }
        entries[key] = Entry(stamp, EmbeddingInput.normalize(vector.copyOf()))
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    /** Drops every key not in [keep]. Returns how many went. */
    fun retainAll(keep: Set<String>): Int {
        val before = entries.size
        entries.keys.retainAll(keep)
        return before - entries.size
    }

    fun clear() = entries.clear()

    /** The vector stored for [key], unit length, or null. */
    fun vectorOf(key: String): FloatArray? = entries[key]?.vector

    /**
     * Every key scored against [query] (unit length), best first. [accept]
     * narrows the candidates, for a search inside one sticker pack.
     */
    fun score(query: FloatArray, accept: (String) -> Boolean = { true }): List<MeaningRank.Hit> {
        require(query.size == dimensions) { "query has ${query.size} values, not $dimensions" }
        val hits = ArrayList<MeaningRank.Hit>(entries.size)
        for ((key, entry) in entries) {
            if (!accept(key)) continue
            val v = entry.vector
            var dot = 0f
            for (k in 0 until dimensions) dot += v[k] * query[k]
            hits += MeaningRank.Hit(key, dot)
        }
        hits.sortWith(compareByDescending<MeaningRank.Hit> { it.score }.thenBy { it.key })
        return hits
    }

    /** Writes the index to [file] through a temporary sibling, so a crash never leaves half a file. */
    fun write(file: File) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(dimensions)
            out.writeInt(entries.size)
            for ((key, entry) in entries) {
                out.writeUTF(key)
                out.writeLong(entry.stamp)
                for (value in entry.vector) out.writeFloat(value)
            }
        }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("could not move the index into place")
        }
    }

    companion object {
        private const val MAGIC = 0x574D5649 // "WMVI"
        private const val VERSION = 1

        /**
         * Reads [file], or an empty index when it is missing, unreadable, or
         * of another width: an index is a cache, and rebuilding it is always
         * possible.
         */
        fun read(file: File, dimensions: Int = EmbeddingInput.DIMENSIONS): VectorIndex {
            val index = VectorIndex(dimensions)
            if (!file.isFile) return index
            try {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readInt() != dimensions) return index
                    val count = input.readInt()
                    repeat(count) {
                        val key = input.readUTF()
                        val stamp = input.readLong()
                        val vector = FloatArray(dimensions) { input.readFloat() }
                        index.entries[key] = Entry(stamp, vector)
                    }
                }
            } catch (_: IOException) {
                index.clear()
            }
            return index
        }
    }
}
