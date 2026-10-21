package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Which Tesseract pack reads each keyboard language, and which engine runs. */
class OcrLanguagesTest {

    private fun lang(id: String) = LanguageRegistry.byId(id)

    // A Play build with Play services, unless a test says otherwise.
    @Before
    fun playBuild() {
        OcrLanguages.mlKitOverride = true
        OcrLanguages.extraScriptsOverride = true
        OcrLanguages.paddleOverride = true
    }

    @After
    fun reset() {
        OcrLanguages.mlKitOverride = null
        OcrLanguages.extraScriptsOverride = null
        OcrLanguages.paddleOverride = null
    }

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
        assertNull(OcrLanguages.offlinePack(OcrEngine.AUTO, lang("en"), true))
        assertEquals("ben", OcrLanguages.offlinePack(OcrEngine.AUTO, lang("bn"), true))
        // #757: ML Kit has its own recognizers for these, and reads them better.
        for (id in listOf("zh", "yue", "ja", "ko", "hi", "mr", "mai")) {
            assertNull(id, OcrLanguages.offlinePack(OcrEngine.AUTO, lang(id), true))
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
        // No Play services to fetch the models: Chinese and Japanese go to
        // Paddle (#757), Korean and Devanagari to Tesseract.
        OcrLanguages.extraScriptsOverride = false
        assertEquals(MlKitScript.LATIN, OcrLanguages.mlKitScript(lang("en")))
        assertNull(OcrLanguages.mlKitScript(lang("ja")))
        val paddle = PaddleOcrModel.PACK
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.AUTO, lang("ja"), true))
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.AUTO, lang("zh"), true))
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.AUTO, lang("yue"), true))
        // Paddle needs no Tesseract library.
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.AUTO, lang("ja"), false))
        assertEquals("kor", OcrLanguages.offlinePack(OcrEngine.AUTO, lang("ko"), true))
        assertEquals("hin", OcrLanguages.offlinePack(OcrEngine.AUTO, lang("hi"), true))
        // Tesseract when asked for by name.
        assertEquals("jpn", OcrLanguages.offlinePack(OcrEngine.TESSERACT, lang("ja"), true))
    }

    @Test
    fun anOlderTesseractPackKeepsReadingUntilPaddleIsThere() {
        OcrLanguages.extraScriptsOverride = false
        val paddle = PaddleOcrModel.PACK
        val onlyJpn: (String) -> Boolean = { it == "jpn" }
        assertEquals("jpn", OcrLanguages.offlinePack(OcrEngine.AUTO, lang("ja"), true, onlyJpn))
        // Not without the library to read it with.
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.AUTO, lang("ja"), false, onlyJpn))
        val both: (String) -> Boolean = { it == "jpn" || it == paddle }
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.AUTO, lang("ja"), true, both))
    }

    @Test
    fun withoutPaddleChineseAndJapaneseStayWithTesseract() {
        // A build that could not run Paddle (every build can now: LiteRT, or MNN).
        OcrLanguages.extraScriptsOverride = false
        OcrLanguages.paddleOverride = false
        assertEquals("jpn", OcrLanguages.offlinePack(OcrEngine.AUTO, lang("ja"), true))
        assertEquals("chi_sim", OcrLanguages.offlinePack(OcrEngine.AUTO, lang("zh"), true))
        assertEquals(listOf("jpn"), OcrLanguages.packsFor(listOf(lang("ja"))).map { it.first })
    }

    @Test
    fun paddleIsOnePackForChineseAndJapanese() {
        OcrLanguages.extraScriptsOverride = false
        val packs = OcrLanguages.packsFor(listOf(lang("zh"), lang("ja"), lang("bn")))
        assertEquals(listOf(PaddleOcrModel.PACK, "ben"), packs.map { it.first })
        assertEquals(listOf("zh", "ja"), packs.first().second.map { it.id })
        assertEquals(PaddleOcrModel.totalBytes, OcrLanguages.sizeOf(PaddleOcrModel.PACK))
        // Tesseract lists its own packs, and ML Kit's languages list Tesseract's as before.
        assertEquals(
            listOf("chi_sim", "jpn", "ben"),
            OcrLanguages.packsFor(listOf(lang("zh"), lang("ja"), lang("bn")), OcrEngine.TESSERACT).map { it.first },
        )
        OcrLanguages.extraScriptsOverride = true
        assertEquals(listOf("chi_sim", "jpn"), OcrLanguages.packsFor(listOf(lang("zh"), lang("ja"))).map { it.first })
        // The chip offers Chinese and Japanese once between them: both read with Paddle.
        OcrLanguages.extraScriptsOverride = false
        assertEquals(
            listOf("en", "zh"),
            OcrLanguages.chipLanguages(OcrEngine.AUTO, listOf(lang("en"), lang("zh"), lang("ja")), true).map { it.id },
        )
    }

    @Test
    fun forcedEnginesAndAMissingLibrary() {
        assertEquals("eng", OcrLanguages.offlinePack(OcrEngine.TESSERACT, lang("en"), true))
        assertNull(OcrLanguages.offlinePack(OcrEngine.ML_KIT, lang("bn"), true))
        assertNull(OcrLanguages.offlinePack(OcrEngine.TESSERACT, lang("bn"), false))
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

    @Test
    fun fdroidReadsWithPaddleWhereNothingElseIs() {
        // F-Droid's build: no ML Kit, no Tesseract library; Paddle runs on MNN.
        OcrLanguages.mlKitOverride = false
        OcrLanguages.extraScriptsOverride = false
        val paddle = PaddleOcrModel.PACK
        assertNull(OcrLanguages.mlKitScript(lang("en")))
        for (id in listOf("zh", "yue", "ja", "en", "fr", "hi_rom")) {
            assertEquals(id, paddle, OcrLanguages.offlinePack(OcrEngine.AUTO, lang(id), false))
        }
        // Scripts Paddle does not know read online only.
        assertNull(OcrLanguages.offlinePack(OcrEngine.AUTO, lang("bn"), false))
        assertFalse(OcrLanguages.readable(OcrEngine.AUTO, lang("bn"), false))
        assertTrue(OcrLanguages.readable(OcrEngine.ONLINE, lang("bn"), false))
        assertTrue(OcrLanguages.readable(OcrEngine.AUTO, lang("en"), false))
        // A choice restored from another build's backup reads as Auto.
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.ML_KIT, lang("ja"), false))
        assertEquals(paddle, OcrLanguages.offlinePack(OcrEngine.TESSERACT, lang("en"), false))
        assertEquals(listOf(OcrEngine.AUTO, OcrEngine.ONLINE), OcrLanguages.engines(false))
        // One pack to offer, and one chip: everything readable reads with it.
        assertEquals(
            listOf(paddle),
            OcrLanguages.packsFor(listOf(lang("en"), lang("ja"), lang("bn")), OcrEngine.AUTO, false).map { it.first },
        )
        assertEquals(
            listOf("en"),
            OcrLanguages.chipLanguages(OcrEngine.AUTO, listOf(lang("en"), lang("ja"), lang("bn")), false).map { it.id },
        )
        // With Tesseract's library, Latin goes back to it.
        assertEquals("fra", OcrLanguages.offlinePack(OcrEngine.AUTO, lang("fr"), true))
        assertEquals(listOf(OcrEngine.AUTO, OcrEngine.TESSERACT, OcrEngine.ONLINE), OcrLanguages.engines(true))
    }
}
