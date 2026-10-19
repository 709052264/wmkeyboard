package com.wasimaster.wmkeyboard.core.tools.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupScannerTest {

    @Test
    fun `a tap inside a word finds the whole word`() {
        val text = "The quick brown fox"
        val scan = LookupScanner.scan(text, 12)!!
        assertEquals("brown", scan.span.of(text))
        assertTrue(scan.entries.isEmpty())
    }

    @Test
    fun `a tap on a space moves to the nearest letter`() {
        val text = "red fox"
        assertEquals("fox", LookupScanner.scan(text, 3)!!.span.of(text))
    }

    @Test
    fun `nothing but punctuation finds no word`() {
        assertNull(LookupScanner.scan("... !!! ...", 5))
        assertNull(LookupScanner.scan("", 0))
    }

    @Test
    fun `a selection splits into its words`() {
        val text = "Hello, wide world!"
        assertEquals(listOf("Hello", "wide", "world"), LookupScanner.words(text).map { it.of(text) })
    }

    @Test
    fun `the sentence around a word`() {
        val text = "First one. The word is here. Last one."
        val span = LookupScanner.wordAt(text, text.indexOf("word"))!!
        assertEquals("The word is here.", LookupScanner.sentence(text, span))
    }

    @Test
    fun `scripts written without spaces are told apart`() {
        assertTrue(LookupScanner.isSpaceless('食'))
        assertTrue(LookupScanner.isSpaceless('べ'))
        assertTrue(LookupScanner.isSpaceless('カ'))
        assertTrue(LookupScanner.isSpaceless('ไ'))
        assertFalse(LookupScanner.isSpaceless('a'))
        assertFalse(LookupScanner.isSpaceless('ব'))
    }
}
