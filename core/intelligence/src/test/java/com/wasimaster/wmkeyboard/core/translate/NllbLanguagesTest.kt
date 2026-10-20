package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.core.translate.OfflineTranslatePlan.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the app's language tags land on NLLB-200's FLORES-200 codes, the pins
 * of the model download, and the sentence cut the engine translates by.
 */
class NllbLanguagesTest {

    @Test
    fun `the model has its 202 tags, each a language and a script`() {
        assertEquals(202, NllbLanguages.CODES.size)
        assertEquals(NllbLanguages.CODES.size, NllbLanguages.CODES.toSet().size)
        for (code in NllbLanguages.CODES) assertTrue(code, Regex("[a-z]{3}_[A-Z][a-z]{3}").matches(code))
        assertTrue(NllbLanguages.has(NllbLanguages.ENGLISH))
    }

    @Test
    fun `two letter tags and keyboard ids find their variety`() {
        assertEquals("ben_Beng", NllbLanguages.modelCode("bn"))
        assertEquals("ben_Beng", NllbLanguages.modelCode("bn_BD"))
        assertEquals("eng_Latn", NllbLanguages.modelCode("en-US"))
        assertEquals("arb_Arab", NllbLanguages.modelCode("ar"))
        assertEquals("swh_Latn", NllbLanguages.modelCode("sw"))
        assertEquals("pes_Arab", NllbLanguages.modelCode("fa"))
        assertEquals("zsm_Latn", NllbLanguages.modelCode("ms"))
        assertEquals("nob_Latn", NllbLanguages.modelCode("no"))
        assertEquals("heb_Hebr", NllbLanguages.modelCode("iw"))
        assertEquals("tgl_Latn", NllbLanguages.modelCode("fil"))
        assertEquals("ckb_Arab", NllbLanguages.modelCode("ckb"))
        assertEquals("ilo_Latn", NllbLanguages.modelCode("ilo"))
        assertEquals("fra_Latn", NllbLanguages.modelCode("fra_Latn"))
    }

    @Test
    fun `Chinese and scripts pick the right code`() {
        assertEquals("zho_Hans", NllbLanguages.modelCode("zh-CN"))
        assertEquals("zho_Hans", NllbLanguages.modelCode("zh"))
        assertEquals("zho_Hant", NllbLanguages.modelCode("zh-TW"))
        assertEquals("zho_Hant", NllbLanguages.modelCode("zh-Hant"))
        assertEquals("kas_Deva", NllbLanguages.modelCode("ks-Deva"))
        assertEquals("kas_Arab", NllbLanguages.modelCode("ks"))
    }

    @Test
    fun `a language in letters the model does not write it in is refused`() {
        assertNull(NllbLanguages.modelCode("hi-Latn"))
        assertTrue(NllbLanguages.isRomanized("hi-Latn"))
        assertTrue(NllbLanguages.isRomanized("bn-Latn"))
        assertFalse(NllbLanguages.isRomanized("tr-Latn"))
        assertNull(NllbLanguages.modelCode("und"))
        assertNull(NllbLanguages.modelCode(""))
        assertNull(NllbLanguages.modelCode("xx"))
    }

    @Test
    fun `every picker language that maps lands on a real tag`() {
        for (code in listOf("bn", "hi", "ar", "sw", "fr", "am", "yo", "ha", "zu", "my", "km", "ug", "ur", "ne", "pa")) {
            val mapped = NllbLanguages.modelCode(code)
            assertTrue("$code -> $mapped", mapped != null && NllbLanguages.has(mapped))
        }
    }

    @Test
    fun `scripts only NLLB has are guessed for it`() {
        fun resolve(text: String) =
            OfflineTranslatePlan.resolveSource(null, ScriptLanguageGuess.candidates(text), emptyList(), "en", NllbLanguages)
        assertEquals(Source.Known("khm_Khmr", false), resolve("ខ្ញុំស្រលាញ់អ្នក"))
        assertEquals(Source.Known("ben_Beng", false), resolve("আমি তোমাকে ভালোবাসি"))
    }

    @Test
    fun `the model files are pinned`() {
        assertEquals(4, NllbTranslator.FILES.size)
        for (file in NllbTranslator.FILES) {
            assertTrue(file.name, Regex("[0-9a-f]{64}").matches(file.sha256))
            assertTrue(file.name, file.bytes > 0)
            assertTrue(file.url, file.url.startsWith("https://huggingface.co/") && "/resolve/" in file.url)
        }
        assertEquals(630_016_302L, NllbTranslator.modelBytes)
    }

    @Test
    fun `a line is cut into sentences that keep their spaces`() {
        assertEquals(
            listOf("I will be late. ", "The train was cancelled! ", "Sorry?"),
            NllbTranslator.sentences("I will be late. The train was cancelled! Sorry?"),
        )
        assertEquals(listOf("আমি আসব। ", "তুমি কোথায়?"), NllbTranslator.sentences("আমি আসব। তুমি কোথায়?"))
        assertEquals(listOf("no stop at the end"), NllbTranslator.sentences("no stop at the end"))
    }
}
