package com.wasimaster.wmkeyboard.core.grammar

import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderEntry
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.GrammarLintKind
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The LanguageTool provider: answers recorded from the public API on
 * 2026-10-20 (`src/test/resources/languagetool/`), mapped onto the grammar
 * panel's lints, and the cutting and pacing that keep a long text within
 * what the public server takes.
 */
class LanguageToolTest {

    private fun recorded(name: String): String =
        requireNotNull(javaClass.getResource("/languagetool/$name")) { "missing fixture $name" }.readText()

    @After
    fun restore() {
        LanguageToolChecker.transport = LanguageToolClient::check
        LanguageToolChecker.clock = { System.nanoTime() / 1_000_000 }
        LanguageToolChecker.sleep = { kotlinx.coroutines.delay(it) }
        LanguageToolChecker.reset()
    }

    // ---- The response, as the panel's lints.

    private val english = "😀 He go to teh store. Its a nice day"

    @Test
    fun `matches become lints with UTF-16 spans past an emoji`() {
        val result = LanguageToolClient.parse(recorded("check-en-us-emoji.json"), english)
        assertEquals("en-US", result.language)
        assertEquals(listOf("go", "teh", "Its"), result.lints.map { it.original })
        val agreement = result.lints[0]
        // The emoji is two UTF-16 units: "go" starts at 6, not at 5.
        assertEquals(6, agreement.start)
        assertEquals(8, agreement.end)
        assertEquals("go", english.substring(agreement.start, agreement.end))
    }

    @Test
    fun `message, short message, rule and fixes carry across`() {
        val agreement = LanguageToolClient.parse(recorded("check-en-us-emoji.json"), english).lints[0]
        assertTrue(agreement.message.startsWith("The pronoun"))
        assertEquals("Agreement error", agreement.title)
        assertEquals("HE_VERB_AGR", agreement.rule)
        assertEquals(listOf(GrammarFix("replace", "goes"), GrammarFix("replace", "went")), agreement.suggestions)
    }

    @Test
    fun `a speller match is spelling, and its thirty replacements are cut to five`() {
        val typo = LanguageToolClient.parse(recorded("check-en-us-emoji.json"), english).lints[1]
        assertEquals(GrammarLintKind.SPELLING, GrammarLintKind.forKind(typo.kind))
        assertEquals(5, typo.suggestions.size)
        assertEquals("the", typo.suggestions.first().text)
    }

    @Test
    fun `kinds follow the category, so filters and colours treat them like Harper's`() {
        val lints = LanguageToolClient.parse(recorded("check-en-us-emoji.json"), english).lints
        assertEquals(GrammarLintKind.GRAMMAR, GrammarLintKind.forKind(lints[0].kind))
        // IT_IS is in TYPOS but is no speller: a typo.
        assertEquals(GrammarLintKind.TYPO, GrammarLintKind.forKind(lints[2].kind))
    }

    @Test
    fun `an auto check reports the language it settled on`() {
        val text = "Das ist ein Test, das ich mache. Ich habe keine zeit."
        val result = LanguageToolClient.parse(recorded("check-auto-de.json"), text)
        assertEquals("de-DE", result.language)
        val casing = result.lints.single()
        assertEquals("zeit", casing.original)
        assertEquals(GrammarLintKind.CAPITALIZATION, GrammarLintKind.forKind(casing.kind))
        assertEquals(listOf(GrammarFix("replace", "Zeit")), casing.suggestions)
    }

    @Test
    fun `an empty replacement is a removal`() {
        val body = """{"matches":[{"message":"Possible typo: you repeated a word","offset":5,"length":3,
            "replacements":[{"value":""}],"rule":{"id":"ENGLISH_WORD_REPEAT_RULE","issueType":"duplication",
            "category":{"id":"MISC"}}}]}"""
        val lint = LanguageToolClient.parse(body, "This is is it").lints.single()
        assertEquals(listOf(GrammarFix("remove")), lint.suggestions)
        assertEquals(GrammarLintKind.REPETITION, GrammarLintKind.forKind(lint.kind))
    }

    @Test
    fun `a match past the end of the text is dropped, not trusted`() {
        val body = """{"matches":[{"message":"x","offset":10,"length":5,"replacements":[]}]}"""
        assertTrue(LanguageToolClient.parse(body, "short").lints.isEmpty())
    }

    @Test
    fun `the language list reads the long codes`() {
        val codes = LanguageToolClient.parseLanguages(recorded("languages.json"))
        assertTrue("de-DE" in codes)
        assertTrue("ca-ES-valencia" in codes)
        // The table the keyboard maps against is the same list.
        assertEquals(LanguageToolLanguages.KNOWN, codes.toSet() - setOf("de-DE-x-simple-language", "de-DE-x-simple-language-DE"))
    }

    // ---- The request.

    @Test
    fun `a blank address is the public server, or the Premium one with an account`() {
        assertEquals("https://api.languagetool.org", LanguageToolServer().base)
        assertTrue(LanguageToolServer().publicApi)
        val premium = LanguageToolServer(username = "me@example.com", apiKey = "k")
        assertEquals("https://api.languagetoolplus.com", premium.base)
        assertFalse(premium.publicApi)
    }

    @Test
    fun `a pasted address reduces to its root`() {
        assertEquals("https://lt.example.org", LanguageToolClient.baseUrl("lt.example.org"))
        assertEquals("http://192.168.1.20:8081", LanguageToolClient.baseUrl("http://192.168.1.20:8081/v2/check"))
        assertEquals("https://lt.example.org", LanguageToolClient.baseUrl("https://lt.example.org/v2/"))
        assertFalse(LanguageToolServer(url = "http://192.168.1.20:8081").publicApi)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a scheme other than http is refused`() {
        LanguageToolClient.baseUrl("ftp://lt.example.org")
    }

    @Test
    fun `the form sends an account only whole, and the two options when set`() {
        val plain = LanguageToolClient.form(LanguageToolServer(apiKey = "orphan"), "hi", "en-US")
        assertEquals(mapOf("text" to "hi", "language" to "en-US"), plain)
        val full = LanguageToolClient.form(
            LanguageToolServer(username = "me@example.com", apiKey = "k", picky = true, motherTongue = "de"),
            "hi",
            "",
        )
        assertEquals("auto", full["language"])
        assertEquals("me@example.com", full["username"])
        assertEquals("k", full["apiKey"])
        assertEquals("picky", full["level"])
        assertEquals("de", full["motherTongue"])
    }

    // ---- The keyboard's language as LanguageTool's code.

    @Test
    fun `english follows the grammar dialect`() {
        assertEquals("en-US", LanguageToolLanguages.codeFor("en-US", GrammarDialect.AMERICAN))
        assertEquals("en-GB", LanguageToolLanguages.codeFor("en-US", GrammarDialect.BRITISH))
        assertEquals("en-AU", LanguageToolLanguages.codeFor("en", GrammarDialect.AUSTRALIAN))
    }

    @Test
    fun `a bare code that would skip spelling gets its main variant`() {
        assertEquals("de-DE", LanguageToolLanguages.codeFor("de"))
        assertEquals("pt-PT", LanguageToolLanguages.codeFor("pt"))
        assertEquals("pl-PL", LanguageToolLanguages.codeFor("pl"))
        assertEquals("uk-UA", LanguageToolLanguages.codeFor("uk_UA"))
    }

    @Test
    fun `a region LanguageTool has is kept`() {
        assertEquals("de-CH", LanguageToolLanguages.codeFor("de-CH"))
        assertEquals("pt-BR", LanguageToolLanguages.codeFor("pt-BR"))
        assertEquals("fr-FR", LanguageToolLanguages.codeFor("fr-FR"))
        assertEquals("fr", LanguageToolLanguages.codeFor("fr"))
        assertEquals("fr-CA", LanguageToolLanguages.codeFor("fr-CA"))
    }

    @Test
    fun `a language LanguageTool lacks goes as auto`() {
        assertEquals("auto", LanguageToolLanguages.codeFor("bn-BD"))
        assertEquals("auto", LanguageToolLanguages.codeFor(""))
    }

    // ---- Cutting a long text.

    @Test
    fun `a short text is one piece`() {
        assertEquals(listOf(LanguageToolChunks.Chunk(0, "Hi there.")), LanguageToolChunks.split("Hi there.", 100))
    }

    @Test
    fun `pieces cut after whole paragraphs and cover the text exactly`() {
        val text = "First paragraph here.\nSecond one is here.\nThird."
        val chunks = LanguageToolChunks.split(text, 30)
        assertEquals("First paragraph here.\n", chunks[0].text)
        assertEquals(text, chunks.joinToString("") { it.text })
        chunks.forEach { assertEquals(it.text, text.substring(it.start, it.start + it.text.length)) }
    }

    @Test
    fun `a long paragraph is cut at a sentence end, then at a space`() {
        val text = "One two three. Four five six seven eight nine"
        val chunks = LanguageToolChunks.split(text, 24)
        assertEquals("One two three. ", chunks[0].text)
        assertTrue(chunks.drop(1).all { it.text.toByteArray().size <= 24 })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun `no piece is over the limit in UTF-8 bytes, nor splits an emoji`() {
        val text = "ব".repeat(10) + "😀😀😀" + "é".repeat(9)
        val chunks = LanguageToolChunks.split(text, 8)
        assertTrue(chunks.all { it.text.toByteArray(Charsets.UTF_8).size <= 8 })
        assertTrue(chunks.none { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() })
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test
    fun `the public limits fit in one request`() {
        assertTrue(LanguageToolServer().maxRequestBytes < 20_000)
        assertEquals(LanguageToolClient.OTHER_MAX_BYTES, LanguageToolServer(url = "lt.example.org").maxRequestBytes)
    }

    // ---- Pacing to the public API's minute.

    @Test
    fun `the minute holds twenty requests, then waits for the oldest to age out`() {
        val window = RateWindow(requests = 20, bytes = 75_000)
        repeat(20) { i ->
            assertEquals(0L, window.waitMs(now = i * 100L, size = 100))
            window.record(i * 100L, 100)
        }
        assertEquals(60_000L - 2_000L, window.waitMs(now = 2_000, size = 100))
        assertEquals(0L, window.waitMs(now = 60_000, size = 100))
    }

    @Test
    fun `the minute holds 75 KB, whatever the count`() {
        val window = RateWindow(requests = 20, bytes = 75_000)
        window.record(0, 18_000)
        window.record(1_000, 18_000)
        window.record(2_000, 18_000)
        window.record(3_000, 18_000)
        assertEquals(0L, window.waitMs(now = 4_000, size = 3_000))
        assertEquals(56_000L, window.waitMs(now = 4_000, size = 18_000))
    }

    // ---- The checker: pieces, offsets, the cache and a 429.

    private fun lintAt(start: Int, end: Int, text: String) =
        GrammarLint(start = start, end = end, original = text.substring(start, end), kind = "GRAMMAR")

    @Test
    fun `each piece's lints come back at the piece's offset in the whole text`() = runBlocking {
        val sent = ArrayList<String>()
        LanguageToolChecker.transport = { _, text, language ->
            sent += text
            LanguageToolResult(listOf(lintAt(0, 3, text)), language)
        }
        val server = LanguageToolServer(url = "lt.example.org")
        val paragraph = "abc " + "x".repeat(LanguageToolClient.OTHER_MAX_BYTES - 6) + "\n"
        val text = paragraph + "def more"
        val result = LanguageToolChecker.check(text, "en-US", server)
        assertEquals(2, sent.size)
        assertEquals(listOf(0, paragraph.length), result.lints.map { it.start })
        assertEquals("def", text.substring(result.lints[1].start, result.lints[1].end))
    }

    @Test
    fun `a piece already checked is not sent again`() = runBlocking {
        var calls = 0
        LanguageToolChecker.transport = { _, _, language -> calls++; LanguageToolResult(emptyList(), language) }
        val server = LanguageToolServer(url = "lt.example.org")
        LanguageToolChecker.check("Same text.", "en-US", server)
        LanguageToolChecker.check("Same text.", "en-US", server)
        assertEquals(1, calls)
        // Another language is another question.
        LanguageToolChecker.check("Same text.", "en-GB", server)
        assertEquals(2, calls)
    }

    @Test
    fun `a 429 waits as long as Retry-After says, then asks again`() = runBlocking {
        val waits = ArrayList<Long>()
        LanguageToolChecker.sleep = { waits += it }
        var calls = 0
        LanguageToolChecker.transport = { _, _, language ->
            calls++
            if (calls == 1) {
                throw ToolHttpException(0, 429, headers = mapOf(LanguageToolClient.RETRY_AFTER to "3"))
            }
            LanguageToolResult(emptyList(), language)
        }
        LanguageToolChecker.check("Hello.", "en-US", LanguageToolServer(url = "lt.example.org"))
        assertEquals(2, calls)
        assertEquals(listOf(3_000L), waits)
    }

    @Test
    fun `a 429 that keeps coming is reported, not retried for ever`() = runBlocking {
        LanguageToolChecker.sleep = { }
        var calls = 0
        LanguageToolChecker.transport = { _, _, _ -> calls++; throw ToolHttpException(0, 429) }
        try {
            LanguageToolChecker.check("Hello.", "en-US", LanguageToolServer(url = "lt.example.org"))
            fail("expected the 429 to surface")
        } catch (e: ToolHttpException) {
            assertEquals(429, e.status)
        }
        assertEquals(3, calls)
    }

    @Test
    fun `the public server's minute is kept by waiting, and a long wait is reported instead`() = runBlocking {
        var now = 0L
        val waits = ArrayList<Long>()
        LanguageToolChecker.clock = { now }
        LanguageToolChecker.sleep = { waits += it; now += it }
        LanguageToolChecker.transport = { _, _, language -> LanguageToolResult(emptyList(), language) }
        repeat(20) { i -> LanguageToolChecker.check("Text number $i.", "en-US", LanguageToolServer()) }
        assertTrue(waits.isEmpty())
        // A 21st in the same second would wait a minute: said, not waited out.
        try {
            LanguageToolChecker.check("One more.", "en-US", LanguageToolServer())
            fail("expected the full minute to be reported")
        } catch (e: ToolHttpException) {
            assertEquals(429, e.status)
        }
        // Fifty seconds on, the wait is ten seconds, short enough to sit through.
        now = 50_000
        LanguageToolChecker.check("One more.", "en-US", LanguageToolServer())
        assertEquals(listOf(10_000L), waits)
    }

    @Test
    fun `retry-after reads seconds`() {
        assertEquals(5_000L, LanguageToolChecker.retryAfterMs(" 5 "))
        assertNull(LanguageToolChecker.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    // ---- The plan: who checks.

    private fun row(typeId: String, extra: Map<String, String> = emptyMap(), url: String = ""): ResolvedProvider {
        val type = ProviderTypes.of(typeId)
        val credential = ProviderCredential(typeId, typeId, url = url, extra = extra)
        return ResolvedProvider(ProviderEntry(typeId), type, credential, apiKey = "", url = url, model = "", problem = null)
    }

    private val harper = row(ProviderTypes.HARPER.id)
    private val lt = row(ProviderTypes.LANGUAGETOOL.id)

    @Test
    fun `english goes to the first row, other languages skip Harper`() {
        val english = GrammarProviders.plan(listOf(harper, lt), "en-US", privateField = false, dataSaverAllows = true)
        assertEquals(GrammarStep.Harper, english.steps.first())
        assertEquals(2, english.steps.size)
        val german = GrammarProviders.plan(listOf(harper, lt), "de-DE", privateField = false, dataSaverAllows = true)
        assertTrue(german.steps.single() is GrammarStep.LanguageTool)
        assertTrue(german.online)
    }

    @Test
    fun `with nothing that knows the language, Harper checks as it always did`() {
        val plan = GrammarProviders.plan(listOf(harper), "bn-BD", privateField = false, dataSaverAllows = true)
        assertEquals(listOf<GrammarStep>(GrammarStep.Harper), plan.steps)
    }

    @Test
    fun `a password or incognito field is never sent`() {
        val withHarper = GrammarProviders.plan(listOf(lt, harper), "en-US", privateField = true, dataSaverAllows = true)
        assertEquals(listOf<GrammarStep>(GrammarStep.Harper), withHarper.steps)
        assertNull(withHarper.held)
        val alone = GrammarProviders.plan(listOf(lt), "en-US", privateField = true, dataSaverAllows = true)
        assertTrue(alone.steps.isEmpty())
        assertEquals(GrammarHeld.PRIVATE, alone.held)
    }

    @Test
    fun `data saver holds the server and says so`() {
        val plan = GrammarProviders.plan(listOf(lt), "de-DE", privateField = false, dataSaverAllows = false)
        assertTrue(plan.steps.isEmpty())
        assertEquals(GrammarHeld.DATA_SAVER, plan.held)
        assertEquals(GrammarHeld.NOTHING_ON, GrammarProviders.plan(emptyList(), "en", false, true).held)
    }

    @Test
    fun `a row's account and options become the server`() {
        val row = row(
            ProviderTypes.LANGUAGETOOL.id,
            extra = mapOf(
                CredentialSpec.USERNAME to " me@example.com ",
                LanguageToolServer.PICKY to "true",
                LanguageToolServer.MOTHER_TONGUE to "de",
            ),
            url = "lt.example.org",
        )
        val server = GrammarProviders.serverOf(row)
        assertEquals("me@example.com", server.username)
        assertTrue(server.picky)
        assertEquals("de", server.motherTongue)
        assertEquals("https://lt.example.org", server.base)
    }
}
