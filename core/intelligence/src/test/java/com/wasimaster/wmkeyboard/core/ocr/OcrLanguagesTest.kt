package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which Tesseract pack reads each keyboard language, and which engine runs. */
class OcrLanguagesTest {

    private fun lang(id: String) = LanguageRegistry.byId(id)

    @Test
    fun everyPackHandedOutHasASize() {
        for (language in LanguageRegistry.all) {
            val pack = OcrLanguages.packFor(language) ?: continue
            assertTrue("${language.id} -> $pack has no size", OcrLanguages.sizeOf(pack) > 0L)
        }
    }

    @Test
    fun languagesWithTheirOwnPack() {
        assertEquals("ben", OcrLanguages.packFor(lang("bn")))
        assertEquals("eng", OcrLanguages.packFor(lang("en")))
        assertEquals("chi_sim", OcrLanguages.packFor(lang("zh")))
        assertEquals("srp_latn", OcrLanguages.packFor(lang("sr-Latn")))
        assertEquals("nor", OcrLanguages.packFor(lang("nn")))
    }

    @Test
    fun aLanguageWithoutAPackBorrowsItsScriptsPack() {
        // Maithili is written in Devanagari and has no pack of its own.
        assertEquals("hin", OcrLanguages.packFor(lang("mai")))
        // Romanised Hindi is Latin text.
        assertEquals("eng", OcrLanguages.packFor(lang("hi_rom")))
    }

    @Test
    fun notationLayoutsGetNoPack() {
        assertNull(OcrLanguages.packFor(lang("ipa")))
        assertNull(OcrLanguages.packFor(lang("morse")))
    }

    @Test
    fun autoLeavesMlKitsScriptsToMlKit() {
        assertNull(OcrLanguages.tesseractPack(OcrEngine.AUTO, lang("en"), true))
        assertEquals("ben", OcrLanguages.tesseractPack(OcrEngine.AUTO, lang("bn"), true))
        // #757: ML Kit has its own recognizers for these, and reads them better.
        for (id in listOf("zh", "yue", "ja", "ko", "hi", "mr", "mai")) {
            assertNull(id, OcrLanguages.tesseractPack(OcrEngine.AUTO, lang(id), true))
        }
    }

    @Test
    fun eachScriptGetsItsMlKitRecognizer() {
        assertEquals(MlKitScript.LATIN, OcrLanguages.mlKitScript(lang("en")))
        assertEquals(MlKitScript.CHINESE, OcrLanguages.mlKitScript(lang("zh")))
        assertEquals(MlKitScript.CHINESE, OcrLanguages.mlKitScript(lang("yue")))
        assertEquals(MlKitScript.JAPANESE, OcrLanguages.mlKitScript(lang("ja")))
        assertEquals(MlKitScript.KOREAN, OcrLanguages.mlKitScript(lang("ko")))
        assertEquals(MlKitScript.DEVANAGARI, OcrLanguages.mlKitScript(lang("hi")))
        assertNull(OcrLanguages.mlKitScript(lang("bn")))
        // Forced onto ML Kit, a script it cannot read falls back to Latin as before.
        assertEquals(MlKitScript.LATIN, OcrLanguages.mlKitRecognizer(lang("bn")))
    }

    @Test
    fun withoutPlayServicesOnlyLatinIsMlKits() {
        // The other four models download through Play services.
        assertEquals(MlKitScript.LATIN, OcrLanguages.mlKitScript(lang("en"), playServices = false))
        assertNull(OcrLanguages.mlKitScript(lang("ja"), playServices = false))
        assertNull(OcrLanguages.mlKitScript(lang("hi"), playServices = false))
    }

    @Test
    fun forcedEnginesAndAMissingLibrary() {
        assertEquals("eng", OcrLanguages.tesseractPack(OcrEngine.TESSERACT, lang("en"), true))
        assertNull(OcrLanguages.tesseractPack(OcrEngine.ML_KIT, lang("bn"), true))
        assertNull(OcrLanguages.tesseractPack(OcrEngine.TESSERACT, lang("bn"), false))
    }

    @Test
    fun chipOffersOneLanguagePerWayOfReading() {
        val enabled = listOf(lang("en"), lang("fr"), lang("bn"), lang("ja"), lang("ipa"))
        // English and French share ML Kit's Latin recognizer under Auto; IPA reads with nothing.
        assertEquals(
            listOf("en", "bn", "ja"),
            OcrLanguages.chipLanguages(OcrEngine.AUTO, enabled, true).map { it.id },
        )
        assertEquals(
            listOf("en", "fr", "bn", "ja"),
            OcrLanguages.chipLanguages(OcrEngine.TESSERACT, enabled, true).map { it.id },
        )
        // ML Kit alone: Bangla falls back to the Latin recognizer, Japanese has its own.
        assertEquals(listOf("en", "ja"), OcrLanguages.chipLanguages(OcrEngine.ML_KIT, enabled, true).map { it.id })
        // Without Tesseract the chip still picks between ML Kit's recognizers.
        assertEquals(listOf("en", "ja"), OcrLanguages.chipLanguages(OcrEngine.AUTO, enabled, false).map { it.id })
        assertTrue(OcrLanguages.chipLanguages(OcrEngine.ONLINE, enabled, true).isEmpty())
    }

    @Test
    fun packsGroupTheLanguagesThatShareThem() {
        val packs = OcrLanguages.packsFor(listOf(lang("nb"), lang("nn"), lang("bn")))
        assertEquals(listOf("nor", "ben"), packs.map { it.first })
        assertEquals(listOf("nb", "nn"), packs.first().second.map { it.id })
    }
}
