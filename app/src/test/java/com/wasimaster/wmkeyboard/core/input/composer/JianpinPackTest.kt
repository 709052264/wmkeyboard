package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Jianpin against the real pinyin pack — the issue's own examples (#405) and
 * the cost of decoding a run of bare initials, which the fixture tests cannot
 * speak to. The pack is host-only, so this runs only where the data repo is
 * checked out beside the app and is skipped everywhere else.
 */
class JianpinPackTest {

    private val packFile = File(System.getProperty("user.home"), "Work/wmkeyboard-data/cjk/pinyin_v2.tsv")
    private val pairsFile = File(System.getProperty("user.home"), "Work/wmkeyboard-data/cjk/pinyin_ngrams.tsv")

    @Before
    fun load() {
        assumeTrue("pinyin pack not on this machine", packFile.isFile)
        val asset = File("src/main/assets/dictionaries/pinyin_syllables.txt")
        assumeTrue(asset.isFile)
        PinyinSyllables.valid = PinyinSyllables.parse(asset.readText().lineSequence())
        Jianpin.index = Jianpin.build(PinyinSyllables.valid)
        CjkDictionaries.pinyin = packFile.bufferedReader().useLines(ConversionDictionary::parse)
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        CjkLearning.store = null
        CjkConfig.jianpin = true
        CjkConfig.fuzzyPinyin = false
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
    }

    @After
    fun reset() {
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        PinyinSyllables.valid = emptySet()
        Jianpin.index = Jianpin.EMPTY
    }

    /**
     * #405's examples. With the word pairs, since the pack's own frequencies
     * are per word and say nothing about which of `bj`'s hundred two-syllable
     * readings people mean.
     */
    @Test
    fun `the issue's examples resolve against the shipped pack`() {
        assumeTrue("pairs pack not on this machine", pairsFile.isFile)
        CjkDictionaries.pinyinNgrams = pairsFile.bufferedReader().useLines(CjkNgrams::parse)
        assertEquals("我们", PinyinComposer.candidates("wm").first())
        assertTrue("北京" in PinyinComposer.candidates("bj").take(3))
        assertTrue("中国" in PinyinComposer.candidates("zg").take(3))
        assertTrue("上海" in PinyinComposer.candidates("sh", 100))
        assertEquals("上海", PinyinComposer.candidates("shangh").first())
        assertEquals("好的", PinyinComposer.candidates("hd").first())
        assertTrue("中国" in PinyinComposer.candidates("zhg").take(3))
        // Two initials mean a phrase: what leads covers both letters, and a
        // lone 好 is further down, among what `h` alone reads.
        val hd = PinyinComposer.candidates("hd")
        assertEquals(hd.toString(), 2, PinyinComposer.consumedFor("hd", hd.first()))
        assertTrue("好" in PinyinComposer.candidates("h"))
        val haod = PinyinComposer.candidates("haod")
        assertEquals(haod.toString(), 4, PinyinComposer.consumedFor("haod", haod.first()))
        assertTrue(haod.toString(), haod.first().startsWith("好"))
        assertTrue("中国" in PinyinComposer.candidates("zguo").take(3))
        assertTrue("北京" in PinyinComposer.candidates("beij").take(3))
    }

    /**
     * #580 with the word pairs loaded: the readings the issue asked for, in the
     * order it asked for them. Skipped where the pairs pack is not checked out.
     */
    @Test
    fun `with the word pairs everyday readings lead`() {
        assumeTrue("pairs pack not on this machine", pairsFile.isFile)
        CjkDictionaries.pinyinNgrams = pairsFile.bufferedReader().useLines(CjkNgrams::parse)
        fun top(b: String, n: Int = 4) = PinyinComposer.candidates(b).take(n)
        assertEquals(top("wbxh").toString(), "我不喜欢", top("wbxh").first())
        assertTrue(top("wbxh", 8).toString(), "我不想" in top("wbxh", 8))
        assertTrue(top("wbxz").toString(), "我不想再" in top("wbxz"))
        assertEquals("你呢", top("nine").first())
        assertTrue(top("wdmm").toString(), "我的妈妈" in top("wdmm"))
        assertEquals("我不喜欢你", top("wbxhni").first())
        assertEquals("我不喜欢你", top("wobuxihuanni").first())
        assertEquals("今天天气很好", top("jintiantianqihenhao").first())
        assertTrue(PinyinComposer.candidates("xian", 12).toString(), "西安" in PinyinComposer.candidates("xian", 12))
        // A mistyped a, next to the q.
        assertEquals(4, PinyinComposer.consumedFor("xiqn", top("xiqn").first()))
        // The candidates run from the whole buffer down to its first letter.
        val wbxh = PinyinComposer.candidates("wbxh", 60)
        val covered = wbxh.map { PinyinComposer.consumedFor("wbxh", it) }
        assertEquals(wbxh.toString(), covered.sortedDescending(), covered)
    }

    /**
     * Without the pairs the decoder only has each word's own frequency, which
     * cannot rank a reading of several words (that is what #580 was). It still
     * has to answer sensibly: whole-buffer readings first, a prefix commit for
     * every shorter one, and nothing lost.
     */
    @Test
    fun `without the word pairs every buffer still converts`() {
        for (b in listOf("wdmm", "wbxhni", "zhg", "nine", "xian")) {
            val cands = PinyinComposer.candidates(b, 40)
            assertTrue(b, cands.isNotEmpty())
            val covered = cands.map { PinyinComposer.consumedFor(b, it) }
            assertEquals(cands.toString(), b.length, covered.first())
            assertEquals(cands.toString(), covered.sortedDescending(), covered)
        }
    }

    @Test
    fun `a run of initials decodes within the frame budget`() {
        val buffers = listOf("hd", "zgrm", "wsygzgr", "nihaoz", "wdmm", "wbxhni")
        for (b in buffers) PinyinComposer.candidates(b) // warm-up
        for (b in buffers) {
            CjkDictionaries.invalidate() // drop the one-entry cache
            val t0 = System.nanoTime()
            val cands = PinyinComposer.candidates(b)
            val ms = (System.nanoTime() - t0) / 1e6
            println("jianpin $b → ${cands.take(4)} in ${"%.1f".format(ms)} ms")
            assertTrue(cands.isNotEmpty())
            // Generous: a JVM run, a cold JIT and a shared CI box all count.
            assertTrue("$b took $ms ms", ms < 200.0)
        }
        // And again with the word pairs, which every path now consults.
        if (!pairsFile.isFile) return
        CjkDictionaries.pinyinNgrams = pairsFile.bufferedReader().useLines(CjkNgrams::parse)
        for (b in buffers) {
            CjkDictionaries.invalidate()
            val t0 = System.nanoTime()
            PinyinComposer.candidates(b)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("$b with pairs took $ms ms", ms < 200.0)
        }
    }
}
