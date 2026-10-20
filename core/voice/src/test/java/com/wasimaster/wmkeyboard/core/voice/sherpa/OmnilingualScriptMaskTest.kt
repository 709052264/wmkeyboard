package com.wasimaster.wmkeyboard.core.voice.sherpa

import com.wasimaster.wmkeyboard.core.script.ScriptId
import java.lang.Character.UnicodeScript
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmnilingualScriptMaskTest {

    /** A tokens.txt in miniature: the four specials, then single characters as the real one has them. */
    private val tokens = listOf(
        "<s>", "<pad>", "</s>", "<unk>", " ",
        "a", "न", "ب", "ा", "7", "-", "ʻ", "●",
        "́", // combining acute
        "َ", // fatha
        "॑", // Vedic udatta
        "‍", // zero-width joiner
        "ि", "̇",
    )

    private fun allowed(scripts: Set<UnicodeScript>): List<String> {
        val mask = OmnilingualScriptMask.build(tokens, scripts)!!
        return tokens.filterIndexed { id, _ -> mask[id] }
    }

    @Test
    fun `Devanagari keeps its own letters, the blank, digits, spaces and dashes`() {
        assertEquals(
            listOf("<s>", " ", "न", "ा", "7", "-", "॑", "‍", "ि"),
            allowed(setOf(UnicodeScript.DEVANAGARI)),
        )
    }

    @Test
    fun `Latin keeps its diacritics and modifier letters but not the joiner`() {
        assertEquals(
            listOf("<s>", " ", "a", "7", "-", "ʻ", "́", "̇"),
            allowed(setOf(UnicodeScript.LATIN)),
        )
    }

    @Test
    fun `Arabic keeps its harakat`() {
        assertEquals(
            listOf("<s>", " ", "ب", "7", "-", "َ", "‍"),
            allowed(setOf(UnicodeScript.ARABIC)),
        )
    }

    @Test
    fun `the specials other than the blank and symbols never pass`() {
        val mask = OmnilingualScriptMask.build(tokens, setOf(UnicodeScript.LATIN, UnicodeScript.DEVANAGARI))!!
        assertArrayEquals(booleanArrayOf(true, false, false, false), mask.copyOf(4))
        assertEquals(false, mask[tokens.indexOf("●")])
    }

    @Test
    fun `a script with no letters among the tokens gives no mask`() {
        assertNull(OmnilingualScriptMask.build(tokens, setOf(UnicodeScript.SYRIAC)))
    }

    @Test
    fun `several languages allow all of their scripts`() {
        assertEquals(
            setOf(UnicodeScript.DEVANAGARI, UnicodeScript.LATIN),
            OmnilingualScriptMask.unicodeScripts(listOf(ScriptId.DEVANAGARI, ScriptId.LATIN)),
        )
        assertEquals(
            setOf(UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN),
            OmnilingualScriptMask.unicodeScripts(listOf(ScriptId.JAPANESE)),
        )
    }

    @Test
    fun `a notation among the languages leaves the model unheld`() {
        assertNull(OmnilingualScriptMask.unicodeScripts(listOf(ScriptId.LATIN, ScriptId.BRAILLE)))
        assertNull(OmnilingualScriptMask.unicodeScripts(emptyList()))
    }

    @Test
    fun `tokens are read by id, a space symbol included`() {
        val file = java.io.File.createTempFile("tokens", ".txt")
        try {
            file.writeText("<s> 0\n<pad> 1\n  2\nक 4\n")
            assertEquals(listOf("<s>", "<pad>", " ", "", "क"), OmnilingualScriptMask.readTokens(file))
        } finally {
            file.delete()
        }
    }
}
