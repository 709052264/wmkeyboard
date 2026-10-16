package com.wasimaster.wmkeyboard.core.transliteration

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** One word filed under a fold key: what a phonetic index ranks. */
class PhoneticRow(
    val word: String,
    val frequency: Int,
    /** The fold's detail mask, one ASCII char per key position (and a closing one for Urdu). */
    val detail: String,
    /** Fold-specific bits ([HindiPhoneticIndex], [UrduPhoneticIndex]). */
    val flags: Int,
)

/**
 * What a fold-and-rank phonetic index keeps: every word of the list filed
 * under its fold keys with the detail the fold discarded, and each word's
 * frequency (issue #716).
 *
 * The index classes decide how a word folds and how siblings rank; this is
 * only where the result lives. Kept apart so it can live off the heap: a
 * [PackedPhoneticTable] mapped from a file the data repo built ahead of time
 * costs the keyboard no Java heap at all, where the `HashMap`s it replaced
 * held 16 MB for Urdu's list and 94 MB for Tamil's once built, more while
 * building, and were built again at every service start. Built in memory, the
 * same table is a quarter to a sixth of that.
 */
interface PhoneticTable {

    val isEmpty: Boolean

    /** The largest frequency of any filed word; 0 when empty. */
    val maxFrequency: Int

    /** The smallest frequency of any filed word; 0 when empty. Equal to [maxFrequency] for a flat list. */
    val minFrequency: Int

    /** Frequency of [word], the highest it was filed with; 0 when it is not filed. */
    fun frequencyOf(word: String): Int

    /** Every row filed under [key], commonest first, ties in the order they were filed. */
    fun rows(key: String): List<PhoneticRow>

    /**
     * Every word with a frequency, once each, in [String.compareTo] order and
     * ranked by that frequency: what desktop Avro's search and Khipro's
     * completions walk ([BengaliPhoneticIndex]). The same object every time it
     * is read, so a caller can tell the list has not changed.
     */
    val words: SortedWords

    companion object {
        val EMPTY: PhoneticTable = object : PhoneticTable {
            override val isEmpty: Boolean get() = true
            override val maxFrequency: Int get() = 0
            override val minFrequency: Int get() = 0
            override fun frequencyOf(word: String): Int = 0
            override fun rows(key: String): List<PhoneticRow> = emptyList()
            override val words: SortedWords get() = NO_WORDS
        }

        private val NO_WORDS: SortedWords = object : SortedWords {
            override val size: Int get() = 0
            override fun length(i: Int): Int = throw IndexOutOfBoundsException()
            override fun charAt(i: Int, at: Int): Char = throw IndexOutOfBoundsException()
            override fun word(i: Int): String = throw IndexOutOfBoundsException()
            override fun rank(i: Int): Long = throw IndexOutOfBoundsException()
        }

        /**
         * [parts] read as one table: a prebuilt list's table and the one built
         * on the device over the user's imported lists. A word in both is filed
         * twice and answers with its higher frequency, as it did when both were
         * folded into one index.
         */
        fun combine(parts: List<PhoneticTable>): PhoneticTable {
            val kept = parts.filterNot { it.isEmpty }
            return when (kept.size) {
                0 -> EMPTY
                1 -> kept[0]
                else -> CombinedTable(kept)
            }
        }

        /**
         * [table] with only the words [keep] accepts: a table built over a
         * whole published list, read against the smaller tier the user
         * downloaded of it. Filing is per word, so this answers exactly what a
         * table built over the kept words alone would.
         */
        fun filter(table: PhoneticTable, keep: (String) -> Boolean): PhoneticTable = FilteredTable(table, keep)
    }
}

private class CombinedTable(private val parts: List<PhoneticTable>) : PhoneticTable {
    override val isEmpty: Boolean get() = false
    override val maxFrequency: Int = parts.maxOf { it.maxFrequency }
    override val minFrequency: Int = parts.minOf { it.minFrequency }
    override fun frequencyOf(word: String): Int = parts.maxOf { it.frequencyOf(word) }
    override fun rows(key: String): List<PhoneticRow> {
        val all = ArrayList<PhoneticRow>()
        for (part in parts) all += part.rows(key)
        // Commonest first across the parts too; sortedByDescending is stable,
        // so within one frequency each part keeps its own order.
        return if (all.size > 1) all.sortedByDescending { it.frequency } else all
    }

    /**
     * The parts' word lists merged, a word in more than one part once, with
     * its highest frequency. Worked out the first time it is read, which only
     * desktop Avro's search and Khipro do: two ints a word.
     */
    override val words: SortedWords by lazy { merge() }

    private fun merge(): SortedWords {
        val lists = parts.map { it.words }
        val total = lists.sumOf { it.size }
        val partOf = ByteArray(total)
        val indexOf = IntArray(total)
        val at = IntArray(lists.size)
        var n = 0
        while (true) {
            // The smallest word at the head of any list; ties go to the list
            // that ranks it best, and the others step past it.
            var best = -1
            for (p in lists.indices) {
                if (at[p] >= lists[p].size) continue
                if (best < 0) {
                    best = p
                    continue
                }
                val cmp = compare(lists[p], at[p], lists[best], at[best])
                if (cmp < 0 || cmp == 0 && lists[p].rank(at[p]) < lists[best].rank(at[best])) best = p
            }
            if (best < 0) break
            val i = at[best]
            for (p in lists.indices) {
                if (p != best && at[p] < lists[p].size && compare(lists[p], at[p], lists[best], i) == 0) at[p]++
            }
            partOf[n] = best.toByte()
            indexOf[n] = i
            n++
            at[best]++
        }
        return object : SortedWords {
            override val size: Int get() = n
            override fun length(i: Int): Int = lists[partOf[i].toInt()].length(indexOf[i])
            override fun charAt(i: Int, at: Int): Char = lists[partOf[i].toInt()].charAt(indexOf[i], at)
            override fun word(i: Int): String = lists[partOf[i].toInt()].word(indexOf[i])
            override fun rank(i: Int): Long = lists[partOf[i].toInt()].rank(indexOf[i])
        }
    }

    private fun compare(a: SortedWords, i: Int, b: SortedWords, j: Int): Int {
        val aLength = a.length(i)
        val bLength = b.length(j)
        for (k in 0 until minOf(aLength, bLength)) {
            val diff = a.charAt(i, k) - b.charAt(j, k)
            if (diff != 0) return diff
        }
        return aLength - bLength
    }
}

private class FilteredTable(private val inner: PhoneticTable, private val keep: (String) -> Boolean) : PhoneticTable {
    override val isEmpty: Boolean get() = inner.isEmpty

    // The commonest word of a frequency-sorted list is in every tier of it,
    // and a flat list is never cut by tier, so neither bound moves.
    override val maxFrequency: Int get() = inner.maxFrequency
    override val minFrequency: Int get() = inner.minFrequency
    override fun frequencyOf(word: String): Int = if (keep(word)) inner.frequencyOf(word) else 0
    override fun rows(key: String): List<PhoneticRow> = inner.rows(key).filter { keep(it.word) }

    /** The kept words' places in [inner]'s list, worked out the first time it is read. */
    override val words: SortedWords by lazy {
        val all = inner.words
        val kept = IntArray(all.size)
        var n = 0
        for (i in 0 until all.size) if (keep(all.word(i))) kept[n++] = i
        object : SortedWords {
            override val size: Int get() = n
            override fun length(i: Int): Int = all.length(kept[i])
            override fun charAt(i: Int, at: Int): Char = all.charAt(kept[i], at)
            override fun word(i: Int): String = all.word(kept[i])
            override fun rank(i: Int): Long = all.rank(kept[i])
        }
    }
}

/**
 * Collects rows and packs them into a [PackedPhoneticTable]: in memory for a
 * table built on the device, or to a file for one the data repo publishes.
 * Both are the same bytes, so the two can never disagree.
 */
class PhoneticTableBuilder(private val fold: Int, private val foldRevision: Int) {

    private val keys = ArrayList<String>()
    private val words = ArrayList<String>()
    private val details = ArrayList<String>()
    private var frequencies = IntArray(1024)
    private var flags = ByteArray(1024)

    val size: Int get() = keys.size

    fun add(key: String, word: String, frequency: Int, detail: String, flags: Int) {
        require(detail.length <= MAX_DETAIL) { "detail of ${detail.length} for $word" }
        val i = keys.size
        if (i == frequencies.size) {
            frequencies = frequencies.copyOf(i * 2)
            this.flags = this.flags.copyOf(i * 2)
        }
        keys += key
        words += word
        details += detail
        frequencies[i] = frequency
        this.flags[i] = flags.toByte()
    }

    /**
     * Gives [word] a frequency without filing it under any key: a word the
     * fold reads but files nowhere (an Urdu word of vowels alone), or another
     * spelling of a word filed under its first one ([IndicPhoneticIndex]).
     */
    fun addWord(word: String, frequency: Int) {
        extraWords += word
        extraFrequencies += frequency
    }

    private val extraWords = ArrayList<String>()
    private val extraFrequencies = ArrayList<Int>()

    /** Packed in memory. [sourceTag] and [sourceWords] say what list the rows came from. */
    fun build(sourceTag: String = "", sourceWords: Int = 0): PackedPhoneticTable =
        PackedPhoneticTable.of(ByteBuffer.wrap(pack(sourceTag, sourceWords)).order(ByteOrder.BIG_ENDIAN))
            ?: error("packed table failed its own check")

    fun write(out: OutputStream, sourceTag: String, sourceWords: Int) {
        out.write(pack(sourceTag, sourceWords))
    }

    private fun pack(sourceTag: String, sourceWords: Int): ByteArray {
        val n = keys.size
        // By key, then commonest first, then in the order filed: the order the
        // per-key lists of the old indexes were in, so ties break the same way.
        val order = (0 until n).sortedWith(
            compareBy<Int> { keys[it] }.thenByDescending { frequencies[it] }.thenBy { it },
        )
        val wordFrequency = HashMap<String, Int>()
        for (i in 0 until n) wordFrequency.merge(words[i], frequencies[i], ::maxOf)
        for (i in extraWords.indices) wordFrequency.merge(extraWords[i], extraFrequencies[i], ::maxOf)
        val sortedWords = wordFrequency.keys.sorted()
        val wordId = HashMap<String, Int>(sortedWords.size * 2)
        sortedWords.forEachIndexed { id, word -> wordId[word] = id }

        val distinctKeys = ArrayList<String>()
        val bucketStarts = ArrayList<Int>()
        for ((position, i) in order.withIndex()) {
            if (distinctKeys.isEmpty() || distinctKeys.last() != keys[i]) {
                distinctKeys += keys[i]
                bucketStarts += position
            }
        }
        val keyBytes = distinctKeys.sumOf { it.length }
        val detailBytes = details.sumOf { it.length }
        val wordChars = sortedWords.sumOf { it.length }
        val tag = sourceTag.toByteArray(Charsets.US_ASCII).copyOf(minOf(sourceTag.length, MAX_TAG))

        val keyIndexOff = HEADER_BYTES
        val keyBytesOff = keyIndexOff + (distinctKeys.size + 1) * 4
        val bucketOff = pad4(keyBytesOff + keyBytes)
        val entryOff = bucketOff + (distinctKeys.size + 1) * 4
        val detailOff = entryOff + n * ENTRY_BYTES
        val wordIndexOff = pad4(detailOff + detailBytes)
        val wordFreqOff = wordIndexOff + (sortedWords.size + 1) * 4
        val wordCharsOff = wordFreqOff + sortedWords.size * 4
        val total = wordCharsOff + wordChars * 2

        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0, MAGIC)
        buf.putShort(4, VERSION.toShort())
        buf.putShort(6, fold.toShort())
        buf.putInt(8, foldRevision)
        buf.putInt(12, distinctKeys.size)
        buf.putInt(16, n)
        buf.putInt(20, sortedWords.size)
        buf.putInt(24, wordFrequency.values.maxOrNull() ?: 0)
        buf.putInt(28, wordFrequency.values.minOrNull() ?: 0)
        buf.putInt(32, sourceWords)
        val offsets = intArrayOf(keyIndexOff, keyBytesOff, bucketOff, entryOff, detailOff, wordIndexOff, wordFreqOff, wordCharsOff)
        offsets.forEachIndexed { k, off -> buf.putInt(36 + k * 4, off) }
        buf.put(68, tag.size.toByte())
        tag.forEachIndexed { k, b -> buf.put(69 + k, b) }

        var at = 0
        distinctKeys.forEachIndexed { k, key ->
            buf.putInt(keyIndexOff + k * 4, at)
            for (c in key) {
                require(c.code < 0x80) { "fold key $key is not ASCII" }
                buf.put(keyBytesOff + at++, c.code.toByte())
            }
        }
        buf.putInt(keyIndexOff + distinctKeys.size * 4, at)
        bucketStarts.forEachIndexed { k, start -> buf.putInt(bucketOff + k * 4, start) }
        buf.putInt(bucketOff + distinctKeys.size * 4, n)

        var detailAt = 0
        for ((position, i) in order.withIndex()) {
            val e = entryOff + position * ENTRY_BYTES
            buf.putInt(e, wordId.getValue(words[i]))
            buf.putInt(e + 4, frequencies[i])
            buf.putInt(e + 8, detailAt)
            buf.put(e + 12, details[i].length.toByte())
            buf.put(e + 13, flags[i])
            for (c in details[i]) {
                require(c.code < 0x80) { "fold detail of ${words[i]} is not ASCII" }
                buf.put(detailOff + detailAt++, c.code.toByte())
            }
        }

        var charAt = 0
        sortedWords.forEachIndexed { id, word ->
            buf.putInt(wordIndexOff + id * 4, charAt)
            buf.putInt(wordFreqOff + id * 4, wordFrequency.getValue(word))
            for (c in word) buf.putChar(wordCharsOff + 2 * charAt++, c)
        }
        buf.putInt(wordIndexOff + sortedWords.size * 4, charAt)
        return buf.array()
    }

    companion object {
        internal const val MAGIC = 0x574D5049 // "WMPI"

        /** Bumped when the layout below changes; a file of another version reads as absent. */
        const val VERSION = 1

        internal const val HEADER_BYTES = 128
        internal const val ENTRY_BYTES = 16
        internal const val MAX_TAG = 59
        private const val MAX_DETAIL = 255

        internal fun pad4(n: Int): Int = (n + 3) and 3.inv()
    }
}

/**
 * A [PhoneticTable] read straight out of its packed bytes, which are a mapped
 * file ([open]) or an array built on the device ([PhoneticTableBuilder]).
 *
 * Layout, big-endian, every section 4-aligned where it holds ints:
 *
 *  - a 128-byte header: magic `WMPI`, format version, fold id and fold
 *    revision (which fold wrote it, and which version of that fold's code),
 *    key / entry / word counts, the largest and smallest frequency, the
 *    number of words of the source list, the offset of every section, and a
 *    short ASCII tag naming the source list;
 *  - the distinct fold keys, sorted, as ASCII bytes with an offset table;
 *  - per key, where its run of entries starts;
 *  - the entries, 16 bytes each: word id, frequency, detail offset, detail
 *    length and the fold's flags;
 *  - the detail bytes;
 *  - the distinct words, sorted by [String.compareTo], with an offset table,
 *    each word's highest frequency, and their UTF-16 characters.
 *
 * Reads are absolute gets, so one table serves every thread, as a mapped
 * `.wmdict` does.
 */
class PackedPhoneticTable private constructor(
    private val buf: ByteBuffer,
    val fold: Int,
    val foldRevision: Int,
    private val keyCount: Int,
    private val entryCount: Int,
    private val wordCount: Int,
    override val maxFrequency: Int,
    override val minFrequency: Int,
    /** How many words the list the table was built over held. */
    val sourceWords: Int,
    /** Which list the table was built over: the catalog entry id. */
    val sourceTag: String,
    private val keyIndexOff: Int,
    private val keyBytesOff: Int,
    private val bucketOff: Int,
    private val entryOff: Int,
    private val detailOff: Int,
    private val wordIndexOff: Int,
    private val wordFreqOff: Int,
    private val wordCharsOff: Int,
) : PhoneticTable {

    override val isEmpty: Boolean get() = entryCount == 0

    override fun rows(key: String): List<PhoneticRow> {
        val k = keyOf(key)
        if (k < 0) return emptyList()
        val from = buf.getInt(bucketOff + k * 4)
        val until = buf.getInt(bucketOff + (k + 1) * 4)
        val out = ArrayList<PhoneticRow>(until - from)
        for (position in from until until) {
            val e = entryOff + position * PhoneticTableBuilder.ENTRY_BYTES
            val detailAt = buf.getInt(e + 8)
            val detailLength = buf.get(e + 12).toInt() and 0xFF
            val detail = CharArray(detailLength) { (buf.get(detailOff + detailAt + it).toInt() and 0xFF).toChar() }
            out += PhoneticRow(
                word = wordAt(buf.getInt(e)),
                frequency = buf.getInt(e + 4),
                detail = String(detail),
                flags = buf.get(e + 13).toInt() and 0xFF,
            )
        }
        return out
    }

    /** The word section as it is: sorted, each word once with its highest frequency. */
    override val words: SortedWords = object : SortedWords {
        override val size: Int get() = wordCount

        override fun length(i: Int): Int = buf.getInt(wordIndexOff + (i + 1) * 4) - buf.getInt(wordIndexOff + i * 4)

        override fun charAt(i: Int, at: Int): Char = buf.getChar(wordCharsOff + 2 * (buf.getInt(wordIndexOff + i * 4) + at))

        override fun word(i: Int): String = wordAt(i)

        override fun rank(i: Int): Long = -buf.getInt(wordFreqOff + i * 4).toLong()
    }

    override fun frequencyOf(word: String): Int {
        var low = 0
        var high = wordCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareWord(mid, word)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return buf.getInt(wordFreqOff + mid * 4)
            }
        }
        return 0
    }

    private fun wordAt(id: Int): String {
        val start = buf.getInt(wordIndexOff + id * 4)
        val end = buf.getInt(wordIndexOff + (id + 1) * 4)
        return String(CharArray(end - start) { buf.getChar(wordCharsOff + 2 * (start + it)) })
    }

    private fun compareWord(id: Int, word: String): Int {
        val start = buf.getInt(wordIndexOff + id * 4)
        val length = buf.getInt(wordIndexOff + (id + 1) * 4) - start
        for (i in 0 until minOf(length, word.length)) {
            val diff = buf.getChar(wordCharsOff + 2 * (start + i)) - word[i]
            if (diff != 0) return diff
        }
        return length - word.length
    }

    /** The index of [key] among the sorted keys, or -1. */
    private fun keyOf(key: String): Int {
        var low = 0
        var high = keyCount - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = buf.getInt(keyIndexOff + mid * 4)
            val length = buf.getInt(keyIndexOff + (mid + 1) * 4) - start
            var cmp = 0
            for (i in 0 until minOf(length, key.length)) {
                cmp = buf.get(keyBytesOff + start + i).toInt() - key[i].code
                if (cmp != 0) break
            }
            if (cmp == 0) cmp = length - key.length
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {

        /**
         * Maps [file] read-only. Null when it is missing, cut short, or not a
         * table this build reads — the caller then builds one on the device.
         */
        fun open(file: File): PackedPhoneticTable? {
            if (!file.isFile) return null
            return try {
                val buf = RandomAccessFile(file, "r").use { raf ->
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                }
                of(buf.order(ByteOrder.BIG_ENDIAN))
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        internal fun of(buf: ByteBuffer): PackedPhoneticTable? {
            val length = buf.capacity()
            if (length < PhoneticTableBuilder.HEADER_BYTES) return null
            if (buf.getInt(0) != PhoneticTableBuilder.MAGIC) return null
            if (buf.getShort(4).toInt() != PhoneticTableBuilder.VERSION) return null
            val keyCount = buf.getInt(12)
            val entryCount = buf.getInt(16)
            val wordCount = buf.getInt(20)
            if (keyCount < 0 || entryCount < 0 || wordCount < 0) return null
            val offsets = IntArray(8) { buf.getInt(36 + it * 4) }
            if (offsets.any { it < PhoneticTableBuilder.HEADER_BYTES || it > length }) return null
            if (offsets.toList() != offsets.sorted()) return null
            // Every fixed-size section has to be there in full; a cut-short
            // file would otherwise read past its end on the first lookup.
            if (offsets[0] + (keyCount + 1) * 4L > offsets[1]) return null
            if (offsets[2] + (keyCount + 1) * 4L > offsets[3]) return null
            if (offsets[3] + entryCount.toLong() * PhoneticTableBuilder.ENTRY_BYTES > offsets[4]) return null
            if (offsets[5] + (wordCount + 1) * 4L > offsets[6]) return null
            if (offsets[6] + wordCount * 4L > offsets[7]) return null
            val wordChars = buf.getInt(offsets[5] + wordCount * 4)
            if (offsets[7] + wordChars * 2L > length) return null
            val tagLength = buf.get(68).toInt() and 0xFF
            if (tagLength > PhoneticTableBuilder.MAX_TAG) return null
            val tag = String(ByteArray(tagLength) { buf.get(69 + it) }, Charsets.US_ASCII)
            return PackedPhoneticTable(
                buf = buf,
                fold = buf.getShort(6).toInt(),
                foldRevision = buf.getInt(8),
                keyCount = keyCount,
                entryCount = entryCount,
                wordCount = wordCount,
                maxFrequency = buf.getInt(24),
                minFrequency = buf.getInt(28),
                sourceWords = buf.getInt(32),
                sourceTag = tag,
                keyIndexOff = offsets[0],
                keyBytesOff = offsets[1],
                bucketOff = offsets[2],
                entryOff = offsets[3],
                detailOff = offsets[4],
                wordIndexOff = offsets[5],
                wordFreqOff = offsets[6],
                wordCharsOff = offsets[7],
            )
        }
    }
}
