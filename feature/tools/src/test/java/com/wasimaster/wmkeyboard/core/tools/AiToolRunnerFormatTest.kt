package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a finished tool hands back for the model to read. */
class AiToolRunnerFormatTest {

    private val results = listOf(
        WebResult("High water", "The tide came in.", "https://a.test/tides", "a.test"),
        WebResult("Low water", "Then it went out.", "https://b.test/ebb", "b.test"),
    )

    @Test
    fun `results are numbered, and every address is included`() {
        val text = AiToolRunner.formatSearch(WebSearchPage(results), "nothing")
        assertTrue(text.startsWith("1. High water"))
        // The address is what lets the model follow up with web_fetch, and
        // what lets it cite a source rather than assert one.
        assertTrue("https://a.test/tides" in text)
        assertTrue("https://b.test/ebb" in text)
        assertTrue("2. Low water" in text)
    }

    @Test
    fun `the backend's own summary leads`() {
        val text = AiToolRunner.formatSearch(WebSearchPage(results, answer = "It is tidal."), "nothing")
        assertTrue(text.startsWith("Summary: It is tidal."))
    }

    @Test
    fun `an empty search says so rather than handing back nothing`() {
        assertEquals("nothing", AiToolRunner.formatSearch(WebSearchPage(emptyList()), "nothing"))
        // A summary with no results is still an answer.
        assertTrue(
            AiToolRunner.formatSearch(WebSearchPage(emptyList(), answer = "Yes."), "nothing")
                .startsWith("Summary: Yes."),
        )
    }

    @Test
    fun `a page is labelled with what it is, and says when it was cut`() {
        val page = WebFetchClient.Page("The Tides", "High water at noon.", truncated = true)
        val text = AiToolRunner.formatPage(page, "https://a.test/tides", "[cut]")
        assertEquals(
            "The Tides\nhttps://a.test/tides\n\nHigh water at noon.\n\n[cut]",
            text,
        )
    }

    @Test
    fun `a page with no title still names its address`() {
        val page = WebFetchClient.Page("", "Body.", truncated = false)
        assertEquals("https://a.test\n\nBody.", AiToolRunner.formatPage(page, "https://a.test", "[cut]"))
    }
    @Test
    fun `an image result gives the picture and the page it is on`() {
        val text = AiToolRunner.formatImages(
            listOf(ImageResult("Capybara", "https://t.test/s.jpg", "https://i.test/c.jpg", "image/jpeg", "https://p.test/zoo")),
            "nothing",
        )
        assertTrue(text.startsWith("1. Capybara"))
        assertTrue("Image: https://i.test/c.jpg" in text)
        assertTrue("Page: https://p.test/zoo" in text)
        assertEquals("nothing", AiToolRunner.formatImages(emptyList(), "nothing"))
    }

    @Test
    fun `a definition keeps its part of speech, senses and an example`() {
        val entry = DictEntry(
            word = "tide",
            phonetic = "/taɪd/",
            audioUrl = null,
            meanings = listOf(
                DictMeaning(
                    partOfSpeech = "noun",
                    definitions = listOf(DictDefinition("The rise and fall of the sea.", "The tide is out.", emptyList())),
                    synonyms = listOf("flow"),
                    antonyms = emptyList(),
                ),
            ),
        )
        val text = AiToolRunner.formatDefinitions(listOf(entry))
        assertTrue(text.startsWith("tide /taɪd/"))
        assertTrue("noun:\n1. The rise and fall of the sea. (e.g. \"The tide is out.\")" in text)
        assertTrue("Synonyms: flow" in text)
    }
}
