package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackDownloadManager
import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Rust readers behind [MappedTrie] and [MappedNgramPack] (`wm-engine`'s
 * `wmdict` and `wmng`, native/RUST_NEON_PLAN.md Phase 2 step 1) answer every
 * query exactly as the Kotlin ones did: the same words, the same
 * frequencies, and the same order, ties included.
 *
 * Dictionaries are checked against [PackedTrie], the heap trie the file was
 * written from, whose completion is the Kotlin `TrieCompleter`; n-gram packs
 * against the counts they were built from. Before the Kotlin readers were
 * deleted this also ran them on the same files: every query agreed, on every
 * list below including the downloaded ones.
 *
 * Always run: the bundled en and bn lists, and a synthetic list the size of a
 * Large download with non-BMP and lone-surrogate words in it. Run when
 * `PARITY_DOWNLOADS` names a folder of the data repo's files
 * (`en_full.txt.gz`, `ko_full.txt.gz`, `th_full.txt.gz`, `en_bigrams.txt.gz`,
 * `en_trigrams.txt.gz`...): those lists at the Large cap, and the real n-gram
 * packs.
 */
class NativeReaderParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val downloads: File? =
        System.getenv("PARITY_DOWNLOADS")?.let(::File)?.takeIf { it.isDirectory }

    private fun source(name: String): File =
        listOf(File("dictionaries-src/$name.txt"), File("app/dictionaries-src/$name.txt"))
            .firstOrNull { it.exists() } ?: error("$name.txt not found (cwd=${File(".").absolutePath})")

    private fun bundled(name: String): List<Pair<String, Int>> =
        source(name).inputStream().use { DictionaryLoader.loadEntries(it) }

    /** A downloaded list read as the phone reads it, at the Large cap. */
    private fun downloaded(code: String): List<Pair<String, Int>>? {
        val file = downloads?.let { File(it, "${code}_full.txt.gz") }?.takeIf { it.isFile } ?: return null
        val out = ArrayList<Pair<String, Int>>()
        GZIPInputStream(file.inputStream()).use { input ->
            DictionaryLoader.forEachEntry(input) { word, frequency ->
                if (out.size < LARGE && frequency >= 2) out.add(word to frequency)
            }
        }
        return out
    }

    /**
     * The en list grown to a Large download's size with derived forms, plus
     * the words a reader gets wrong first: supplementary characters (two
     * edges each), lone surrogates, a symbol table past 256 labels.
     */
    private fun synthetic(): List<Pair<String, Int>> {
        val base = bundled("en")
        val out = ArrayList<Pair<String, Int>>(LARGE)
        out.addAll(base)
        val suffixes = listOf("s", "ed", "ing", "er", "ly", "ness", "ish", "able", "ful", "less", "ment", "ward")
        var i = 0
        while (out.size < LARGE - 2000) {
            val (word, frequency) = base[i % base.size]
            out.add(word + suffixes[(i / base.size) % suffixes.size] + (i / (base.size * suffixes.size)).let { if (it == 0) "" else "$it" } to frequency / 3 + 1)
            i++
        }
        val emoji = listOf("😀", "😂", "🙂", "👍", "🎉")
        for (j in 0 until 600) out.add("${emoji[j % emoji.size]}${base[j].first}" to (j % 50) + 2)
        for (j in 0 until 400) out.add("${base[j].first}\uD800" to 7)
        for (j in 0 until 400) out.add("\uDC00${base[j].first}" to 7)
        // Hangul syllables: a label alphabet past 256, so u16 labels.
        for (j in 0 until 600) out.add("${(0xAC00 + j * 7).toChar()}${(0xAC00 + j * 3).toChar()}" to j + 2)
        return out
    }

    private var written = 0

    private fun write(entries: List<Pair<String, Int>>): Pair<PackedTrie, File> {
        val packed = PackedTrie.of(entries)
        val file = tmp.newFile("dict${written++}.wmdict")
        file.outputStream().use { PackedTrieCodec.write(packed, it) }
        return packed to file
    }

    /** Every 1..3-unit prefix in the list, then longer ones from a sample of words. */
    private fun prefixes(entries: List<Pair<String, Int>>): List<String> {
        val out = LinkedHashSet<String>()
        for ((word, _) in entries) for (n in 1..minOf(3, word.length)) out.add(word.substring(0, n))
        entries.forEachIndexed { index, (word, _) ->
            if (index % 97 == 0) for (n in 4..word.length) out.add(word.substring(0, n))
        }
        out.addAll(listOf("\uD83D", "\uD800", "\uDC00", "zzzzzz", "\u0000"))
        return out.toList()
    }

    private fun checkDictionary(name: String, entries: List<Pair<String, Int>>) {
        val (packed, file) = write(entries)
        val mapped = MappedTrie.open(file) ?: error("$name: MappedTrie rejected the codec's output")

        // Depth first with children in label order is UTF-16 order, which is
        // String's own; the frequencies are PackedTrie's (already rounded).
        val listed = mapped.entries()
        val expected = listed.map { it.first }.sorted().map { it to packed.frequencyOf(it) }
        assertEquals("$name: entries in depth-first order", expected, listed)
        assertEquals("$name: word count", packed.wordCount, listed.size)

        for ((word, _) in listed) {
            val expected = packed.frequencyOf(word)
            assertEquals("$name: frequencyOf($word)", expected, mapped.frequencyOf(word))
        }
        val misses = listed.take(5000).map { it.first.reversed() + "\u0001" } + listOf("", "\uD800", "\uDFFF", "a\uD83D")
        for (word in misses) {
            assertEquals("$name: frequencyOf(miss $word)", packed.frequencyOf(word), mapped.frequencyOf(word))
            assertEquals("$name: contains(miss $word)", packed.contains(word), mapped.contains(word))
        }
        val prefixes = prefixes(listed)
        var compared = 0
        for (prefix in prefixes) {
            for (limit in LIMITS) {
                val expected = packed.complete(prefix, limit)
                assertEquals("$name: complete($prefix, $limit)", expected, mapped.complete(prefix, limit))
                compared++
            }
        }
        assertTrue(mapped.complete("", 5).isEmpty())
        assertTrue(mapped.complete("a", 0).isEmpty())
        println("$name: ${listed.size} words, ${prefixes.size} prefixes, $compared completions identical")
    }

    @Test fun bundledEnglish() = checkDictionary("en (bundled)", bundled("en"))

    @Test fun bundledBengali() = checkDictionary("bn (bundled)", bundled("bn"))

    @Test fun largeSynthetic() = checkDictionary("synthetic large", synthetic())

    @Test fun downloadedLists() {
        for (code in listOf("en", "ko", "th")) {
            val entries = downloaded(code) ?: continue
            checkDictionary("$code (downloaded, Large)", entries)
        }
    }

    // ---- n-gram packs ---------------------------------------------------------

    /**
     * Every count each n-gram was added with. The builder keeps a repeated
     * n-gram as two slots (a downloaded list can repeat one once its words
     * are folded to keys), so a lookup may land on either.
     */
    private class Counts {
        val bigrams = HashMap<String, HashMap<String, MutableList<Int>>>()
        val trigrams = HashMap<Pair<String, String>, HashMap<String, MutableList<Int>>>()
    }

    private fun checkPack(name: String, pack: MappedNgramPack, counts: Counts) {
        // The vocabulary, both ways: every word the counts name, in UTF-8 order.
        val vocab = ArrayList<String>()
        while (true) vocab.add(pack.word(vocab.size) ?: break)
        val named = HashSet<String>()
        for ((first, followers) in counts.bigrams) { named.add(first); named.addAll(followers.keys) }
        for ((context, followers) in counts.trigrams) { named.add(context.first); named.add(context.second); named.addAll(followers.keys) }
        assertEquals("$name: vocabulary", named.sortedWith(Comparator(::compareUtf8)), vocab)
        vocab.forEachIndexed { id, word -> assertEquals("$name: idOf($word)", id, pack.idOf(word)) }
        assertEquals(-1, pack.idOf("\u0001nowhere"))
        assertEquals(null, pack.word(-1))
        assertEquals(null, pack.word(vocab.size))

        var repeated = 0
        for ((first, followers) in counts.bigrams) {
            for ((second, added) in followers) {
                val got = pack.bigramCount(first, second)
                assertTrue("$name: bigram $first $second = $got, added $added", got in added.map(FrequencyCodec::round))
            }
            assertEquals(0, pack.bigramCount(first, "\u0001nowhere"))
            for (limit in NEXT_LIMITS) {
                val got = pack.nextWords(first, limit)
                if (followers.values.any { it.size > 1 }) {
                    // A repeated follower is listed once per slot; which slot
                    // a lookup lands on is the binary search's business.
                    repeated++
                    assertEquals("$name: nextWords($first, $limit) size", minOf(limit, followers.values.sumOf { it.size }), got.size)
                } else {
                    assertEquals("$name: nextWords($first, $limit)", best(followers, limit), got)
                }
            }
        }
        for ((context, followers) in counts.trigrams) {
            for ((third, added) in followers) {
                val got = pack.trigramCount(context.first, context.second, third)
                assertTrue("$name: trigram $context $third = $got, added $added", got in added.map(FrequencyCodec::round))
            }
            for (limit in NEXT_LIMITS) {
                val got = pack.nextWordsAfter(context.first, context.second, limit)
                if (followers.values.any { it.size > 1 }) {
                    repeated++
                    assertEquals("$name: nextWordsAfter($context, $limit) size", minOf(limit, followers.values.sumOf { it.size }), got.size)
                } else {
                    assertEquals("$name: nextWordsAfter($context, $limit)", best(followers, limit), got)
                }
            }
        }
        println("$name: ${vocab.size} words, ${counts.bigrams.size} heads, ${counts.trigrams.size} contexts identical ($repeated with a repeated n-gram)")
    }

    /** Best first by stored (rounded) count, ties in UTF-8 byte order: the pack's promise. */
    private fun best(followers: Map<String, List<Int>>, limit: Int): List<String> =
        followers.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Int>>> { FrequencyCodec.encode(it.value.single()) }
                    .thenComparator { a, b -> compareUtf8(a.key, b.key) },
            )
            .take(limit)
            .map { it.key }

    private fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        for (i in 0 until minOf(x.size, y.size)) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private fun keep(map: HashMap<String, MutableList<Int>>, word: String, count: Int) {
        map.getOrPut(word) { ArrayList(1) }.add(count)
    }

    @Test fun syntheticPack() {
        val builder = NgramPackBuilder()
        val counts = Counts()
        val seeds = listOf(File("src/main/assets/dictionaries/en_bigrams.txt"), File("app/src/main/assets/dictionaries/en_bigrams.txt"))
            .first { it.exists() }
        val words = bundled("en").map { it.first }.take(6000)
        seeds.readLines().forEach { line ->
            val parts = line.trim().split(' ')
            val fresh = counts.bigrams[parts[0]]?.containsKey(parts.getOrNull(1)) != true
            if (!line.startsWith("#") && parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty() && fresh) {
                val count = parts.getOrNull(2)?.toIntOrNull() ?: 5
                builder.addBigram(parts[0], parts[1], count)
                keep(counts.bigrams.getOrPut(parts[0]) { HashMap() }, parts[1], count)
            }
        }
        val seen = HashSet<String>()
        for (i in 0 until 40_000) {
            val a = words[(i * 31) % words.size]
            val b = words[(i * 17 + 3) % words.size]
            val c = words[(i * 7 + 11) % words.size]
            if (!seen.add("$a $b") || counts.bigrams[a]?.containsKey(b) == true) continue
            // Many equal counts, so ties are exercised; some past 2048, where
            // the minifloat rounds.
            val count = if (i % 5 == 0) 3 else (i * 131) % 9000 + 1
            builder.addBigram(a, b, count)
            keep(counts.bigrams.getOrPut(a) { HashMap() }, b, count)
            builder.addTrigram(a, b, c, count)
            keep(counts.trigrams.getOrPut(a to b) { HashMap() }, c, count)
        }
        builder.addBigram("শব্দ", "😀", 9)
        keep(counts.bigrams.getOrPut("শব্দ") { HashMap() }, "😀", 9)
        val file = tmp.newFile("synthetic.wmng")
        file.outputStream().use { NgramPackCodec.write(builder.build(), it) }
        checkPack("synthetic pack", MappedNgramPack.open(file) ?: error("pack rejected"), counts)
    }

    @Test fun downloadedPacks() {
        val dir = downloads ?: return
        for (code in listOf("en", "bn")) {
            val bigrams = File(dir, "${code}_bigrams.txt.gz").takeIf { it.isFile } ?: continue
            val trigrams = File(dir, "${code}_trigrams.txt.gz").takeIf { it.isFile }
            val filesDir = tmp.newFolder("files-$code")
            val entry = NgramPackCatalog.entries.first { it.languageId == code }
            NgramPackDownloadManager.install(filesDir, entry, bigrams, trigrams)
            val pack = MappedNgramPack.open(NgramPackDownloadManager.packFile(filesDir, code)) ?: error("$code pack")
            // The counts the pack was built from, read the way the manager reads them.
            val counts = Counts()
            readList(bigrams, 2, BIGRAM_CAP) { w, c -> keep(counts.bigrams.getOrPut(w[0]) { HashMap() }, w[1], c) }
            if (trigrams != null) {
                readList(trigrams, 3, TRIGRAM_CAP) { w, c -> keep(counts.trigrams.getOrPut(w[0] to w[1]) { HashMap() }, w[2], c) }
            }
            checkPack("$code pack (downloaded)", pack, counts)
        }
    }

    private fun readList(file: File, parts: Int, cap: Int, accept: (List<String>, Int) -> Unit) {
        var taken = 0
        GZIPInputStream(file.inputStream()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (taken >= cap) break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val separator = trimmed.lastIndexOf(' ')
                if (separator <= 0) continue
                val count = trimmed.substring(separator + 1).toIntOrNull() ?: continue
                val words = trimmed.substring(0, separator).trim().split(' ')
                if (words.size != parts) continue
                if (words.any { it.isEmpty() || it.length > MAX_WORD }) continue
                accept(words.map { WordKey.of(it) }, count)
                taken++
            }
        }
    }

    private companion object {
        const val LARGE = 300_000
        val LIMITS = listOf(1, 3, 8, 18, 50)
        val NEXT_LIMITS = listOf(1, 5, 12, 40)
        // NgramPackDownloadManager's caps and word length limit.
        const val BIGRAM_CAP = 150_000
        const val TRIGRAM_CAP = 75_000
        const val MAX_WORD = 48
    }
}
