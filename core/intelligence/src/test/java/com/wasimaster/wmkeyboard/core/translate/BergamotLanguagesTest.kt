package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.core.translate.OfflineTranslatePlan.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Bergamot engine can read and write, the route a pair takes, the
 * pins in the generated catalogue, and the guess that stands in for a
 * language identifier on a phone without ML Kit.
 */
class BergamotLanguagesTest {

    // ---- the catalogue ------------------------------------------------------

    @Test
    fun `every pinned file is a sha256 and a positive size, served from Mozilla's CDN`() {
        val hex = Regex("[0-9a-f]{64}")
        for (direction in BergamotCatalog.directions) {
            assertTrue(direction.key, direction.files.any { it.kind == BergamotCatalog.Kind.MODEL })
            val vocab = direction.files.any { it.kind == BergamotCatalog.Kind.VOCAB } ||
                (
                    direction.files.any { it.kind == BergamotCatalog.Kind.SRC_VOCAB } &&
                        direction.files.any { it.kind == BergamotCatalog.Kind.TRG_VOCAB }
                    )
            assertTrue(direction.key, vocab)
            for (file in direction.files) {
                assertTrue(file.name, hex.matches(file.sha256) && hex.matches(file.rawSha256))
                assertTrue(file.name, file.size in 1 until file.rawSize)
                assertTrue(file.url, file.url.startsWith("https://firefox-settings-attachments.cdn.mozilla.net/"))
            }
            assertTrue(direction.key, direction.version.startsWith("3."))
        }
        // One entry per direction.
        assertEquals(BergamotCatalog.directions.size, BergamotCatalog.directions.map { it.key }.toSet().size)
    }

    @Test
    fun `every language Mozilla has goes both ways through English`() {
        for (code in BergamotLanguages.SUPPORTED - BergamotLanguages.PIVOT) {
            assertTrue(code, BergamotLanguages.reads(code))
            assertTrue(code, BergamotLanguages.writes(code))
        }
    }

    // ---- codes ----------------------------------------------------------------

    @Test
    fun `picker codes and keyboard ids map onto Mozilla's codes`() {
        assertEquals("bn", BergamotLanguages.modelCode("bn"))
        assertEquals("en", BergamotLanguages.modelCode("en_US"))
        assertEquals("zh-Hans", BergamotLanguages.modelCode("zh-CN"))
        assertEquals("zh-Hans", BergamotLanguages.modelCode("zh"))
        // Unlike ML Kit, Mozilla has Traditional Chinese.
        assertEquals("zh-Hant", BergamotLanguages.modelCode("zh-TW"))
        assertEquals("zh-Hant", BergamotLanguages.modelCode("zh-Hant"))
        assertEquals("nb", BergamotLanguages.modelCode("no"))
        assertEquals("he", BergamotLanguages.modelCode("iw"))
        assertEquals("tl", BergamotLanguages.modelCode("fil"))
    }

    @Test
    fun `a language with no model, or romanised text, is null`() {
        assertNull(BergamotLanguages.modelCode("ga"))
        assertNull(BergamotLanguages.modelCode(""))
        assertNull(BergamotLanguages.modelCode("hi-Latn"))
        assertTrue(BergamotLanguages.isRomanized("ru-Latn"))
        assertFalse(BergamotLanguages.isRomanized("sr-Latn"))
    }

    // ---- routes ---------------------------------------------------------------

    @Test
    fun `a pair with English is one direction, any other pivots through English`() {
        assertEquals(listOf("fr-en"), BergamotLanguages.route("fr", "en")?.map { it.key })
        assertEquals(listOf("en-de"), BergamotLanguages.route("en", "de")?.map { it.key })
        assertEquals(listOf("fr-en", "en-de"), BergamotLanguages.route("fr", "de")?.map { it.key })
        assertEquals(emptyList<String>(), BergamotLanguages.route("fr", "fr")?.map { it.key })
        assertNull(BergamotLanguages.route("ga", "en"))
        assertNull(BergamotLanguages.route("en", "ga"))
    }

    // ---- the source, without an identifier --------------------------------------

    private fun resolve(text: String, hints: List<String> = emptyList(), target: String = "en"): Source =
        OfflineTranslatePlan.resolveSource(null, ScriptLanguageGuess.candidates(text), hints, target, BergamotLanguages)

    @Test
    fun `a script that belongs to one language settles it`() {
        assertEquals(Source.Known("bn", false), resolve("আমি তোমাকে ভালোবাসি"))
        assertEquals(Source.Known("ko", false), resolve("안녕하세요 반갑습니다"))
        assertEquals(Source.Known("ja", false), resolve("これは日本語です"))
        assertEquals(Source.Known("zh-Hans", false), resolve("我们明天见"))
        assertEquals(Source.Known("el", false), resolve("Καλημέρα σας"))
    }

    @Test
    fun `letters only one language uses tell a shared script apart`() {
        assertEquals(Source.Known("uk", false), resolve("Добрий день, як справи? Їжак"))
        assertEquals(Source.Known("ur", false), resolve("یہ میری کتاب ہے"))
        assertEquals(Source.Known("ru", false), resolve("Это мой дом, ты был здесь"))
    }

    @Test
    fun `a sentence in Latin letters is told by its common words`() {
        assertEquals(Source.Known("de", false), resolve("Ich habe das nicht gewusst und es ist mir egal"))
        assertEquals(Source.Known("fr", false), resolve("Je ne sais pas pourquoi il est parti avec les enfants"))
        assertEquals(Source.Known("es", false), resolve("No sé por qué se fue con los niños, pero está bien"))
        assertEquals(Source.Known("en", false), resolve("I do not know why he left with the children", target = "fr"))
    }

    @Test
    fun `too little to go on falls back to the keyboard's languages, as a guess`() {
        assertEquals(Source.Known("de", true), resolve("Hallo", hints = listOf("de", "en")))
        assertEquals(Source.Unknown, resolve("Hallo"))
    }

    @Test
    fun `devanagari is Hindi or Marathi, and a Marathi keyboard decides`() {
        assertEquals(Source.Known("mr", false), resolve("मी घरी जात आहे", hints = listOf("mr")))
    }
}
