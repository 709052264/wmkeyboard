package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The translation front ends (#726): the address a person pastes, the answers
 * each one gives, and the language codes each engine spells its own way. The
 * response bodies are copied from live instances (2026-10-19).
 */
class TranslateFrontEndsTest {

    // ---- Addresses -----------------------------------------------------------

    @Test
    fun `an instance address is read from every reasonable paste`() {
        val expected = "https://mozhi.example.org"
        assertEquals(expected, FrontEndUrls.base("mozhi.example.org"))
        assertEquals(expected, FrontEndUrls.base("https://mozhi.example.org/"))
        // Mozhi's own page ends in /#, the others carry the text in a query.
        assertEquals(expected, FrontEndUrls.base("https://mozhi.example.org/#"))
        assertEquals(expected, FrontEndUrls.base("https://mozhi.example.org/?engine=google&text=hi"))
        assertEquals(expected, FrontEndUrls.base("https://mozhi.example.org/api/translate"))
        assertEquals(expected, FrontEndUrls.base("https://mozhi.example.org/api"))
        assertEquals("https://example.org/lingva", FrontEndUrls.base("https://example.org/lingva/api/v1/auto/es/hi"))
        assertEquals("http://192.168.0.10:3000", FrontEndUrls.base(" http://192.168.0.10:3000 "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an address with another scheme is refused`() {
        FrontEndUrls.base("ftp://mozhi.example.org")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty address is refused`() {
        FrontEndUrls.base("  ")
    }

    // ---- Answers -------------------------------------------------------------

    @Test
    fun `mozhi reads one engine's answer`() {
        val body = """{"engine":"google","detected":"en","translated-text":"Hola Mundo","source_language":"auto",""" +
            """"target_language":"es","word_choices":null}"""
        val result = MozhiClient.parse(body)
        assertEquals("Hola Mundo", result.text)
        assertEquals("en", result.detectedSource)
    }

    @Test
    fun `mozhi reads the first answer with text out of an array`() {
        val body = """[{"engine":"yandex","detected":"","translated-text":""},""" +
            """{"engine":"duckduckgo","detected":"en","translated-text":"Hola"}]"""
        assertEquals("Hola", MozhiClient.parse(body).text)
    }

    @Test
    fun `mozhi reads an engine's language list`() {
        val body = """[{"Name":"Chinese (Traditional)","Id":"zh-hant"},{"Name":"English","Id":"en"}]"""
        assertEquals(setOf("zh-hant", "en"), MozhiClient.parseLanguages(body))
    }

    @Test
    fun `simplytranslate reads its answer and its language map`() {
        val result = SimplyTranslateClient.parse(
            """{"source_language":"en","definitions":null,"translations":null,"translated_text":"Hola","pronunciation":""}""",
        )
        assertEquals("Hola", result.text)
        assertEquals("en", result.detectedSource)
        assertEquals(setOf("auto", "en"), SimplyTranslateClient.parseLanguages("""{"auto":"Autodetect","en":"English"}"""))
    }

    @Test(expected = FrontEndEmptyException::class)
    fun `an empty answer is a failure so the next provider is asked`() {
        // What simplytranslate.org answers while Google blocks it: 200 and no text.
        val result = SimplyTranslateClient.parse(
            """{"source_language":"","definitions":null,"translations":null,"translated_text":"","pronunciation":""}""",
        )
        FrontEndResult.checked(result, TranslateClient.AUTO)
    }

    @Test
    fun `lingva reads its answer, with and without info`() {
        val full = LingvaClient.parse("""{"translation":"Hola mundo","info":{"detectedSource":"en","pronunciation":{}}}""")
        assertEquals("Hola mundo", full.text)
        assertEquals("en", full.detectedSource)
        assertEquals("", LingvaClient.parse("""{"translation":"Hola"}""").detectedSource)
        assertEquals(
            setOf("zh", "zh_HANT"),
            LingvaClient.parseLanguages("""{"languages":[{"code":"zh","name":"Chinese"},{"code":"zh_HANT","name":"Chinese (Traditional)"}]}"""),
        )
    }

    @Test
    fun `lingva puts the text in the path with spaces as percent-20`() {
        assertEquals(
            "https://lingva.example/api/v1/auto/es/hello%20world%2F%3F",
            LingvaClient.translateUrl("https://lingva.example", "auto", "es", "hello world/?"),
        )
    }

    @Test
    fun `translite reads both language lists from one answer`() {
        val (sources, targets) = TransliteClient.parseLanguages(
            """{"sl":{"auto":"Autodetect","en":"English"},"tl":{"en":"English","es":"Spanish"}}""",
        )
        assertEquals(setOf("auto", "en"), sources)
        assertEquals(setOf("en", "es"), targets)
    }

    @Test
    fun `a source the user named stands in for a detection that is missing`() {
        val result = FrontEndResult.checked(Translation("Hola", ""), "en")
        assertEquals("en", result.detectedSource)
        assertEquals("", FrontEndResult.checked(Translation("Hola", ""), TranslateClient.AUTO).detectedSource)
    }

    // ---- Language codes ------------------------------------------------------

    @Test
    fun `codes are matched to each engine's spelling`() {
        val google = listOf("auto", "en", "es", "iw", "no", "tl", "zh-CN", "zh-TW", "pt")
        val deepl = listOf("en-us", "en-gb", "nb", "pt-br", "pt-pt", "zh", "zh-hans", "zh-hant")
        val lingva = listOf("en", "iw", "zh", "zh_HANT")
        val yandex = listOf("en", "he", "no", "zh", "pt-BR", "pt")

        assertEquals("iw", FrontEndLanguages.match("he", google))
        assertEquals("he", FrontEndLanguages.match("iw", yandex))
        assertEquals("nb", FrontEndLanguages.match("no", deepl))
        assertEquals("tl", FrontEndLanguages.match("fil", google))
        assertEquals("zh-hans", FrontEndLanguages.match("zh-CN", deepl))
        assertEquals("zh-hant", FrontEndLanguages.match("zh-TW", deepl))
        assertEquals("zh_HANT", FrontEndLanguages.match("zh-TW", lingva))
        assertEquals("zh", FrontEndLanguages.match("zh-CN", lingva))
        assertEquals("en-us", FrontEndLanguages.match("en", deepl))
        assertEquals("pt", FrontEndLanguages.match("pt", yandex))
    }

    @Test
    fun `traditional chinese is never sent as simplified`() {
        assertNull(FrontEndLanguages.match("zh-TW", listOf("en", "zh")))
    }

    @Test
    fun `a missing language is null and an unknown list passes the code through`() {
        assertNull(FrontEndLanguages.match("bn", listOf("en", "de")))
        assertEquals("bn", FrontEndLanguages.match("bn", emptyList()))
    }

    @Test
    fun `auto goes only to an engine that detects`() {
        assertEquals("auto", FrontEndLanguages.source(TranslateClient.AUTO, listOf("auto", "en")))
        assertEquals("auto", FrontEndLanguages.source("", emptyList()))
        assertEquals("en", FrontEndLanguages.source("en", listOf("en", "de")))
    }

    @Test(expected = FrontEndLanguageException::class)
    fun `auto on an engine that cannot detect hands the text on`() {
        // DeepL and Reverso behind Mozhi.
        FrontEndLanguages.source(TranslateClient.AUTO, listOf("en", "de"))
    }
}
