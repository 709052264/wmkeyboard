package com.wasimaster.wmkeyboard.core.semantic

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
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
 * Ranking is brute force: a dot product with every vector. Every vector is
 * stored at unit length, so that dot product is the cosine. The vectors sit
 * in one row-major array, so the whole scan is one call into the engine's
 * NEON kernel ([NativeEngine.dotRows]), or the plain loop when
 * [NativeEngine.useNative] is off.
 *
 * Not thread-safe; the owner serialises access.
 */
class VectorIndex(val dimensions: Int = EmbeddingInput.DIMENSIONS) {

    /** Row of each key, in the order the keys came in (the order [keys] and [write] use). */
    private val rows = LinkedHashMap<String, Int>()
    private var keyAt = arrayOfNulls<String>(0)
    private var stamps = LongArray(0)
    private var matrix = FloatArray(0)

    val size: Int get() = rows.size

    val keys: Set<String> get() = rows.keys

    operator fun contains(key: String): Boolean = key in rows

    /** The stamp [key] was embedded under, or null when it is not here. */
    fun stampOf(key: String): Long? = rows[key]?.let { stamps[it] }

    fun put(key: String, stamp: Long, vector: FloatArray) {
        require(vector.size == dimensions) { "vector has ${vector.size} values, not $dimensions" }
        val row = rows.getOrPut(key) { grow(size) }
        keyAt[row] = key
        stamps[row] = stamp
        EmbeddingInput.normalize(vector.copyOf()).copyInto(matrix, row * dimensions)
    }

    fun remove(key: String) {
        val row = rows.remove(key) ?: return
        // The last row moves into the gap, so the rows stay packed.
        val last = rows.size
        if (row != last) {
            val moved = keyAt[last]!!
            keyAt[row] = moved
            stamps[row] = stamps[last]
            matrix.copyInto(matrix, row * dimensions, last * dimensions, (last + 1) * dimensions)
            rows[moved] = row
        }
        keyAt[last] = null
    }

    /** Drops every key not in [keep]. Returns how many went. */
    fun retainAll(keep: Set<String>): Int {
        val gone = rows.keys.filter { it !in keep }
        for (key in gone) remove(key)
        return gone.size
    }

    fun clear() {
        rows.clear()
        keyAt = arrayOfNulls(0)
        stamps = LongArray(0)
        matrix = FloatArray(0)
    }

    /** The vector stored for [key], unit length, or null. */
    fun vectorOf(key: String): FloatArray? {
        val row = rows[key] ?: return null
        return matrix.copyOfRange(row * dimensions, (row + 1) * dimensions)
    }

    /**
     * Every key scored against [query] (unit length), best first. [accept]
     * narrows the candidates, for a search inside one sticker pack. A search
     * that keeps a few wants [search], which does not sort them all.
     */
    fun score(query: FloatArray, accept: (String) -> Boolean = { true }): List<MeaningRank.Hit> {
        val scores = scores(query)
        val hits = ArrayList<MeaningRank.Hit>(size)
        for (row in 0 until size) {
            val key = keyAt[row]!!
            if (accept(key)) hits += MeaningRank.Hit(key, scores[row])
        }
        hits.sortWith(compareByDescending<MeaningRank.Hit> { it.score }.thenBy { it.key })
        return hits
    }

    /**
     * `MeaningRank.select(score(query, accept), tuning)`, the same hits in the
     * same order, without a [MeaningRank.Hit] per item or a sort of them all:
     * the median comes from a selection, and only the hits over the bar are
     * ranked, at most [MeaningRank.Tuning.limit] of them.
     */
    fun search(query: FloatArray, tuning: MeaningRank.Tuning, accept: (String) -> Boolean = { true }): List<MeaningRank.Hit> {
        val scores = scores(query)
        val accepted = IntArray(size)
        var count = 0
        var best = Float.NEGATIVE_INFINITY
        for (row in 0 until size) {
            if (!accept(keyAt[row]!!)) continue
            accepted[count++] = row
            if (java.lang.Float.compare(scores[row], best) > 0) best = scores[row]
        }
        if (count == 0) return emptyList()
        val bar = MeaningRank.bar(best, count, tuning) { medianOf(FloatArray(count) { scores[accepted[it]] }) }
        val top = TopRows(minOf(tuning.limit, count)) { a, b -> better(scores, a, b) }
        for (i in 0 until count) {
            val row = accepted[i]
            if (scores[row] >= bar) top.offer(row)
        }
        return top.sorted().map { MeaningRank.Hit(keyAt[it]!!, scores[it]) }
    }

    /** [query] against every row, indexed by row. */
    private fun scores(query: FloatArray): FloatArray {
        require(query.size == dimensions) { "query has ${query.size} values, not $dimensions" }
        val out = FloatArray(size)
        if (size == 0) return out
        if (NativeEngine.useNative) {
            NativeEngine.dotRows(query, matrix, dimensions, out)
            return out
        }
        for (row in 0 until size) {
            val base = row * dimensions
            var dot = 0f
            for (k in 0 until dimensions) dot += matrix[base + k] * query[k]
            out[row] = dot
        }
        return out
    }

    /** Whether row [a] ranks before row [b]: [score]'s order, best first, then by key. */
    private fun better(scores: FloatArray, a: Int, b: Int): Boolean {
        val c = java.lang.Float.compare(scores[a], scores[b])
        return c > 0 || (c == 0 && keyAt[a]!! < keyAt[b]!!)
    }

    /**
     * Room for row [row], the next one; returns it. An eighth more at a time:
     * an index grows a batch of embeddings at a time, each far slower than
     * the copy, and stays in memory at the size it grew to.
     */
    private fun grow(row: Int): Int {
        if (row == keyAt.size) reserve(row + maxOf(16, row / 8))
        return row
    }

    private fun reserve(capacity: Int) {
        if (capacity <= keyAt.size) return
        keyAt = keyAt.copyOf(capacity)
        stamps = stamps.copyOf(capacity)
        matrix = matrix.copyOf(capacity * dimensions)
    }

    /** Writes the index to [file] through a temporary sibling, so a crash never leaves half a file. */
    fun write(file: File) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(dimensions)
            out.writeInt(size)
            for ((key, row) in rows) {
                out.writeUTF(key)
                out.writeLong(stamps[row])
                for (k in row * dimensions until (row + 1) * dimensions) out.writeFloat(matrix[k])
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
                    if (count < 0 || count > file.length() / (dimensions * 4L)) return index
                    index.reserve(count)
                    repeat(count) {
                        val key = input.readUTF()
                        val stamp = input.readLong()
                        val row = index.rows.getOrPut(key) { index.grow(index.size) }
                        index.keyAt[row] = key
                        index.stamps[row] = stamp
                        for (k in row * dimensions until (row + 1) * dimensions) index.matrix[k] = input.readFloat()
                    }
                }
            } catch (_: IOException) {
                index.clear()
            }
            return index
        }
    }
}

/**
 * The median of [values] as [MeaningRank]'s sorted one has it (the middle
 * value, or the mean of the middle two), by selection rather than a sort.
 * Reorders [values].
 */
internal fun medianOf(values: FloatArray): Float {
    val mid = values.size / 2
    val upper = nth(values, mid)
    if (values.size % 2 == 1) return upper
    // Everything before mid is no greater than it: the largest is the other middle value.
    var lower = values[0]
    for (i in 1 until mid) if (values[i] > lower) lower = values[i]
    return (lower + upper) / 2f
}

/** Puts in `a[k]` what a sort would, with nothing greater before it; returns it. */
private fun nth(a: FloatArray, k: Int): Float {
    var lo = 0
    var hi = a.size - 1
    while (hi > lo) {
        val pivot = middleOf(a[lo], a[(lo + hi) ushr 1], a[hi])
        var i = lo
        var j = hi
        while (i <= j) {
            while (a[i] < pivot) i++
            while (a[j] > pivot) j--
            if (i <= j) {
                val t = a[i]
                a[i] = a[j]
                a[j] = t
                i++
                j--
            }
        }
        // lo..j are no greater than the pivot, i..hi no less, and between them equal to it.
        when {
            k <= j -> hi = j
            k >= i -> lo = i
            else -> return a[k]
        }
    }
    return a[k]
}

private fun middleOf(a: Float, b: Float, c: Float): Float =
    if (a < b) (if (b < c) b else if (a < c) c else a) else (if (a < c) a else if (b < c) c else b)

/** The best [capacity] rows offered, by [before]: a heap with the worst kept on top. */
private class TopRows(private val capacity: Int, private val before: (Int, Int) -> Boolean) {
    private val heap = IntArray(capacity)
    private var count = 0

    fun offer(row: Int) {
        if (count < capacity) {
            heap[count] = row
            var at = count++
            while (at > 0) {
                val parent = (at - 1) / 2
                if (!before(heap[parent], heap[at])) break
                swap(at, parent)
                at = parent
            }
        } else if (capacity > 0 && before(row, heap[0])) {
            heap[0] = row
            var at = 0
            while (true) {
                var worst = at
                for (child in intArrayOf(2 * at + 1, 2 * at + 2)) {
                    if (child < count && before(heap[worst], heap[child])) worst = child
                }
                if (worst == at) break
                swap(at, worst)
                at = worst
            }
        }
    }

    /** Best first. */
    fun sorted(): List<Int> =
        heap.copyOf(count).sortedWith { a, b -> if (before(a, b)) -1 else if (before(b, a)) 1 else 0 }

    private fun swap(a: Int, b: Int) {
        val t = heap[a]
        heap[a] = heap[b]
        heap[b] = t
    }
}
