package com.wasimaster.wmkeyboard.core.voice.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsTextTest {

    // The head of mms-tts-hin's tokens.txt, in the conversion's own format:
    // "  7" is the space, and id 0 is the blank VITS reads between characters.
    private val hindi = MmsTokens.parse(
        "फ 0\n  7\nअ 4\nज 57\nआ 55\nम 71\nौ 64\nस 40\nब 41\nह 36\nु 28\nत 69\nच 4\nछ 9\n",
    )

    @Test
    fun `characters map to ids with the blank around each`() {
        val ids = hindi.encode("आज", Locale("hi"), addBlank = true).toList()
        assertEquals(listOf(0L, 55L, 0L, 57L, 0L), ids)
    }

    @Test
    fun `the space line is the space and unknown characters are dropped`() {
        val ids = hindi.encode("आज, मौसम!", Locale("hi"), addBlank = false).toList()
        assertEquals(listOf(55L, 57L, 7L, 71L, 64L, 40L, 71L), ids)
    }

    @Test
    fun `nothing speakable is empty rather than a lone blank`() {
        assertEquals(0, hindi.encode("123 ...", Locale("hi"), addBlank = true).size)
    }

    @Test
    fun `latin voices read upper case as lower case`() {
        val english = MmsTokens.parse("k 0\nK 0\n  19\nh 6\ni 18\n")
        assertEquals(listOf(6L, 18L), english.encode("HI", Locale.ENGLISH, addBlank = false).toList())
    }

    @Test
    fun `pieces end at sentence marks of several scripts`() {
        val pieces = MmsText.pieces("আমি গান গাই। তুমি? Hello there. 你好。")
        assertEquals(listOf("আমি গান গাই।", "তুমি?", "Hello there.", "你好。"), pieces)
    }

    @Test
    fun `a long sentence is cut at a comma or a space`() {
        val long = (1..80).joinToString(" ") { "word$it" } + ", " + (1..20).joinToString(" ") { "tail$it" }
        val pieces = MmsText.pieces(long)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= MmsText.MAX_PIECE })
        assertEquals(long.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }

    @Test
    fun `catalog lines parse and comments are skipped`() {
        val voices = MmsVoices.parse(
            "# header\n# code\tbytes\nben\t114044676\tc670\t480\tabcd\nbad line\n",
        )
        assertEquals(setOf("ben"), voices.keys)
        assertEquals(114_044_676L + 480L, voices.getValue("ben").bytes)
    }

    @Test
    fun `keyboard languages map to Meta's codes`() {
        assertEquals("ben", MmsVoices.codeFor("bn"))
        assertEquals("ben", MmsVoices.codeFor("bn_BD"))
        assertEquals("hin", MmsVoices.codeFor("hi-IN"))
        assertEquals("eng", MmsVoices.codeFor("en_GB"))
        assertEquals("swh", MmsVoices.codeFor("sw"))
        assertEquals("zlm", MmsVoices.codeFor("ms"))
        assertEquals("tgl", MmsVoices.codeFor("fil"))
        // A corpus language is already ISO 639-3.
        assertEquals("swc", MmsVoices.codeFor("swc"))
        assertNull(MmsVoices.codeFor(""))
    }
}
