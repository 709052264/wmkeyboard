package com.wasimaster.wmkeyboard.core.voice.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaHotwordsTest {

    /** A tokens.txt in miniature: `piece id` a line, controls first as the real ones have them. */
    private fun tokens(vararg pieces: String): SherpaHotwords =
        SherpaHotwords.parse(
            (listOf("<blk>", "<sos/eos>", "<unk>") + pieces + listOf("#0"))
                .mapIndexed { id, piece -> "$piece $id" }
                .joinToString("\n"),
        )

    /** LibriSpeech English: capitals only. */
    private val english = tokens(
        "S", "▁THE", "▁A", "T", "▁AND", "E", "▁", "W", "I", "M", "O", "L", "K", "N", "J", "U", "C", "'", "B", "R", "▁W",
    )

    @Test
    fun `a capitals-only model gets the words in capitals, one phrase per entry`() {
        assertEquals(SherpaHotwords.BPE, english.unit)
        assertEquals(listOf("WASI MOLLIK", "O'BRIEN"), english.lines(listOf("Wasi Mollik", "O’Brien", "wasi mollik")))
    }

    @Test
    fun `punctuation splits words and keeps sherpa's line syntax out`() {
        assertEquals("JEAN LUC", english.phrase("Jean-Luc"))
        assertEquals("WASI MOLLIK", english.phrase(":wasi/mollik#"))
    }

    @Test
    fun `a word the pieces cannot spell drops its whole phrase`() {
        // No D, no digits, no accented letters.
        assertNull(english.phrase("Wasi Dhaka"))
        assertNull(english.phrase("Wasi 4"))
        assertNull(english.phrase("Ölm"))
    }

    @Test
    fun `under three letters is never a hotword`() {
        assertNull(english.phrase("Al"))
        assertNull(english.phrase("a/b"))
        assertEquals("ALI", english.phrase("Ali"))
    }

    @Test
    fun `a small-letters model gets small letters, a mixed one the word as typed`() {
        val russian = tokens("е", "▁с", "т", "▁в", "▁", "с", "а", "и", "л", "в")
        assertEquals("василиса", russian.phrase("Василиса"))
        val german = tokens("▁", "t", "e", "▁Das", "M", "ü", "l", "r", "▁M", "u", "D")
        assertEquals("Müller", german.phrase("Müller"))
    }

    @Test
    fun `a precomposed piece replaces the two code points NFC leaves`() {
        val bengali = tokens("▁", "ও", "য়", "া", "স", "ি")
        // য + nukta, as typed: NFC keeps it apart, the model only has য়.
        assertEquals("ওয়াসি", bengali.phrase("ওয়াসি"))
    }

    @Test
    fun `a Chinese model takes characters, and only ASCII words through the pieces`() {
        val chinese = tokens("▁", "▁的", "张", "子", "豪", "的", "我", "W", "A", "S", "I", "▁W", "Б")
        assertEquals(SherpaHotwords.CJK_CHAR_BPE, chinese.unit)
        assertEquals(listOf("张子豪", "WASI"), chinese.lines(listOf("张子豪", "Wasi", "Б Б Б", "张Wasi", "张")))
    }

    @Test
    fun `the vocabulary lists every real piece best first with falling scores`() {
        val lines = english.vocab().trim().lines()
        assertEquals("S -2.3466", lines.first())
        assertEquals(21, lines.size)
        assertTrue(lines.none { it.startsWith("<") || it.startsWith("#") })
        val scores = lines.map { it.substringAfter(' ').toDouble() }
        assertEquals(scores.sortedDescending(), scores)
    }
}
