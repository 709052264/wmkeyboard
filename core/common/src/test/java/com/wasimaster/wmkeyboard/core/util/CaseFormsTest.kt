package com.wasimaster.wmkeyboard.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Re-casing keeps a word's spelling: ß is not SS (#715). */
class CaseFormsTest {

    @Test
    fun `sharp s goes to the capital sharp s and back`() {
        for (word in listOf("Spaß", "Scheiße", "Großbuchstaben")) {
            val upper = word.uppercaseKeepingLength()
            assertEquals(word.length, upper.length)
            assertEquals(word.lowercase(), upper.lowercaseKeepingLength())
        }
        assertEquals("SPAẞ", "Spaß".uppercaseKeepingLength())
        assertEquals("spaß", "SPAẞ".lowercaseKeepingLength())
    }

    @Test
    fun `ordinary text matches the full mapping`() {
        assertEquals("HELLO WORLD", "Hello world".uppercaseKeepingLength())
        assertEquals("hello world", "Hello WORLD".lowercaseKeepingLength())
        assertEquals("ÄÖÜ", "äöü".uppercaseKeepingLength())
    }

    @Test
    fun `greek keeps its final sigma`() {
        assertEquals("οδός", "ΟΔΌΣ".lowercaseKeepingLength())
    }

    @Test
    fun `letters with no single capital stay as they are`() {
        assertEquals("ŉ", "ŉ".uppercaseKeepingLength())
        assertEquals("ﬀ", "ﬀ".uppercaseKeepingLength())
        assertEquals("ABCﬀ", "abcﬀ".uppercaseKeepingLength())
    }

    @Test
    fun `dotted capital i lowers to one letter`() {
        assertEquals(1, "İ".lowercaseKeepingLength().length)
    }
}
