package com.wasimaster.wmkeyboard.core.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabLanguagesTest {

    @Test
    fun `keyboard ids map to sidecar codes`() {
        assertEquals(listOf("bn"), VocabLanguages.codesFor("bn_rom"))
        assertEquals(listOf("cmn"), VocabLanguages.codesFor("zh"))
        assertEquals(listOf("hr", "sh"), VocabLanguages.codesFor("hr"))
        assertEquals(listOf("nb", "no"), VocabLanguages.codesFor("nb"))
        assertEquals(listOf("hi"), VocabLanguages.codesFor("hi"))
    }

    @Test
    fun `wanted codes follow the keyboard languages unless chosen`() {
        assertEquals(listOf("bn", "cmn", "hr", "sh"), VocabLanguages.wantedCodes(emptyList(), listOf("en", "bn_rom", "bn", "zh", "hr")))
        assertEquals(listOf("de", "fr"), VocabLanguages.wantedCodes(listOf("de", " fr", "en"), listOf("bn")))
        // A pick that is a keyboard id rather than a code still finds its sidecar.
        assertEquals(listOf("bn"), VocabLanguages.wantedCodes(listOf("bn_rom"), listOf("en")))
        assertEquals(listOf("cmn"), VocabLanguages.wantedCodes(listOf("zh"), listOf("en")))
    }

    @Test
    fun `a record shows its wanted languages, else whatever it has`() {
        assertEquals(listOf("bn"), VocabLanguages.codesToShow(listOf("bn", "hi"), listOf("de", "bn", "fr")))
        assertEquals(listOf("bn", "hi"), VocabLanguages.codesToShow(listOf("bn", "hi"), listOf("hi", "bn")))
        assertEquals(listOf("fr", "de"), VocabLanguages.codesToShow(listOf("bn"), listOf("de", "fr")))
        assertTrue(VocabLanguages.codesToShow(listOf("bn"), emptyList()).isEmpty())
    }

    @Test
    fun `fallbacks fetch only when the language itself is missing`() {
        val offered = listOf("bn", "sh", "no", "nb", "cmn")
        assertEquals(listOf("bn", "sh"), VocabLanguages.codesToFetch(listOf("bn", "hr", "sh"), offered))
        assertEquals(listOf("nb"), VocabLanguages.codesToFetch(listOf("nb", "no"), offered))
        assertEquals(listOf("cmn"), VocabLanguages.codesToFetch(listOf("cmn"), offered))
        assertTrue(VocabLanguages.codesToFetch(listOf("de"), offered).isEmpty())
    }

    @Test
    fun `names come from the registry then the table then the platform`() {
        assertEquals("Bangla", VocabLanguages.displayName("bn") { if (it == "bn") "Bangla" else null })
        assertEquals("Mandarin Chinese", VocabLanguages.displayName("cmn"))
        assertEquals("Ottoman Turkish", VocabLanguages.displayName("ota"))
        assertEquals("Serbo-Croatian", VocabLanguages.displayName("sh"))
        assertEquals("Norwegian", VocabLanguages.displayName("no"))
        assertEquals("German", VocabLanguages.displayName("de"))
        assertEquals("zzq", VocabLanguages.displayName("zzq"))
    }

    @Test
    fun `romanised layouts want the romanisation first`() {
        assertTrue(VocabLanguages.prefersRomanized("bn", listOf("en", "bn_rom")))
        assertFalse(VocabLanguages.prefersRomanized("bn", listOf("en", "bn")))
    }

    private val abhor = VocabWord(
        word = "abhor",
        translations = mapOf(
            "bn" to VocabTranslation(w = listOf("ঘৃণা করা", "বিতৃষ্ণা"), r = listOf("ghrina kora", "bitrishna")),
            "de" to VocabTranslation(w = listOf("verabscheuen")),
        ),
    )

    @Test
    fun `the one-line meaning takes the chosen language over the keyboard's`() {
        val wanted = VocabLanguages.wantedCodes(listOf("de"), listOf("en", "bn"))
        val meaning = VocabLanguages.primaryMeaning(abhor, wanted, listOf("en", "bn"))
        assertEquals(VocabMeaning("de", "verabscheuen", null), meaning)
    }

    @Test
    fun `a Banglish keyboard reads the Bangla gloss, romanisation first`() {
        val ids = listOf("en", "bn_rom")
        val wanted = VocabLanguages.wantedCodes(emptyList(), ids)
        assertEquals(
            VocabMeaning("bn", "ghrina kora, bitrishna", "ঘৃণা করা, বিতৃষ্ণা"),
            VocabLanguages.primaryMeaning(abhor, wanted, ids),
        )
        // On the Bangla layout itself the script leads.
        assertEquals(
            VocabMeaning("bn", "ঘৃণা করা, বিতৃষ্ণা", "ghrina kora, bitrishna"),
            VocabLanguages.primaryMeaning(abhor, wanted, listOf("en", "bn")),
        )
    }

    @Test
    fun `no translation means no meaning line`() {
        val plain = VocabWord(word = "abhor")
        assertEquals(null, VocabLanguages.primaryMeaning(plain, listOf("bn"), listOf("en", "bn")))
        // An English-only keyboard asks for nothing, and the pack's own language is never a translation.
        assertTrue(VocabLanguages.wantedCodes(emptyList(), listOf("en")).isEmpty())
        val empty = VocabWord(word = "abhor", translations = mapOf("bn" to VocabTranslation()))
        assertEquals(null, VocabLanguages.primaryMeaning(empty, listOf("bn"), listOf("bn")))
    }

    @Test
    fun `the line keeps to a few glosses`() {
        val many = VocabWord(word = "x", translations = mapOf("de" to VocabTranslation(w = listOf("a", "b", "c", "d"))))
        assertEquals("a, b", VocabLanguages.primaryMeaning(many, listOf("de"), emptyList(), max = 2)?.text)
    }
}
