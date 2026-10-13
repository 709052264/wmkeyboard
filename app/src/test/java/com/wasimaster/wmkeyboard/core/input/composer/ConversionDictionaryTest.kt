package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reading→candidates table: lookup order, prefixes, and pack-scale loading. */
class ConversionDictionaryTest {

    private fun dict(vararg lines: String) = ConversionDictionary.parse(lines.asSequence())

    @Test
    fun `exact returns a reading's words most frequent first`() {
        val d = dict(
            "# comment",
            "nihao\t你好\t900",
            "ni\t尼\t100",
            "ni\t你\t800",
            "nihao\t泥壕\t5",
        )
        assertEquals(listOf("你", "尼"), d.exact("ni"))
        assertEquals(listOf("你好", "泥壕"), d.exact("nihao"))
        assertEquals(emptyList<String>(), d.exact("wo"))
        assertFalse(d.isEmpty)
    }

    @Test
    fun `candidates fall back to shorter prefixes of the buffer`() {
        val d = dict("ni\t你\t800", "nihao\t你好\t900")
        // The whole reading first, then what its prefixes offer.
        assertEquals(listOf("你好", "你"), d.candidates("nihao"))
        // An unknown tail still surfaces the prefix's candidates.
        assertEquals(listOf("你"), d.candidates("nix"))
        assertEquals(emptyList<String>(), d.candidates(""))
    }

    @Test
    fun `candidates de-duplicate and respect the limit`() {
        val d = dict("a\t啊\t10", "ab\t啊\t20", "ab\t吧\t30", "abc\t阿布\t40")
        assertEquals(listOf("阿布", "吧", "啊"), d.candidates("abc"))
        assertEquals(listOf("阿布"), d.candidates("abc", limit = 1))
    }

    @Test
    fun `malformed and empty input is skipped`() {
        assertSame(ConversionDictionary.EMPTY, ConversionDictionary.parse(emptySequence()))
        assertSame(ConversionDictionary.EMPTY, dict("", "   ", "# only a comment", "notabs", "hao\t"))
        // A row with no frequency column still loads, ranked as zero.
        assertEquals(listOf("好"), dict("hao\t好").exact("hao"))
        // Surrounding whitespace is trimmed off the row, not treated as a missing field.
        assertEquals(listOf("好"), dict("  hao\t好\t3  ").exact("hao"))
        assertTrue(ConversionDictionary.EMPTY.isEmpty)
    }

    @Test
    fun `rows arriving out of order are still found`() {
        val d = dict("zi\t子\t10", "an\t安\t20", "mi\t米\t30", "an\t按\t5")
        assertEquals(listOf("安", "按"), d.exact("an"))
        assertEquals(listOf("子"), d.exact("zi"))
        assertEquals(listOf("米"), d.exact("mi"))
    }

    /**
     * The `ja_kana` pack is 1.08M rows: a `Map<String, List<String>>` of it runs
     * past 160 MB and dies on a phone's 256 MB heap, which the loader then
     * swallows as "no dictionary" — the whole reason Japanese conversion offered
     * only kana on device. Readings here are deliberately unsorted (`row10` sorts
     * before `row2`) so the load path's sort carries the full row count too.
     */
    // System.gc() below is the measurement, not a hint: the assertion is about
    // what the table *retains*, so the garbage from building it has to be gone
    // before the heap is read.
    @Test
    @Suppress("ExplicitGarbageCollectionCall")
    fun `a pack-sized table loads and stays small enough to query`() {
        val rows = 1_081_860
        val lines = Sequence {
            object : Iterator<String> {
                private var i = 0
                override fun hasNext() = i < rows
                override fun next(): String {
                    if (i >= rows) throw NoSuchElementException()
                    i++
                    return "reading$i\t漢字$i\t${i % 1000}"
                }
            }
        }

        // Measured against the heap as it stood before the parse: the test JVM
        // is shared with every other :app test class (no forkEvery), and what
        // they leave in statics is not this table's. Read whole, the heap grew
        // with the suite past the budget while the table stayed ~70 MB.
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val d = ConversionDictionary.parse(lines)
        System.gc()
        val retainedMb = (runtime.totalMemory() - runtime.freeMemory() - before) / (1024 * 1024)

        assertFalse(d.isEmpty)
        assertEquals(listOf("漢字1"), d.exact("reading1"))
        assertEquals(listOf("漢字$rows"), d.exact("reading$rows"))
        assertEquals(listOf("漢字540930"), d.exact("reading540930"))
        assertEquals(emptyList<String>(), d.exact("reading${rows + 1}"))
        // ~47 MB of text and ~22 MB of row arrays; the old layout needed >160 MB.
        assertTrue("retained ${retainedMb}MB for $rows rows", retainedMb < 150)
    }
}
