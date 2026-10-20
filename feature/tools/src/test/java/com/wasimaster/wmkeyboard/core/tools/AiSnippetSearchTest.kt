package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.snippets.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What `search_snippets` finds by words, how it adds the meaning half, and what the model reads. */
class AiSnippetSearchTest {

    private val address = Snippet(
        id = 1, label = "Home address", text = "12 Lake Road\nSylhet 3100",
        createdAt = 100, trigger = "addr", aliases = listOf("adr"), tags = listOf("personal"),
    )
    private val bank = Snippet(id = 2, label = "Bank", text = "IBAN GB29 NWBK 6016 1331 9268 19", createdAt = 300, trigger = "iban")
    private val signOff = Snippet(
        id = 3, label = "Sign-off", text = "Kind regards,\nWasi", createdAt = 200,
        trigger = "kr", alternates = listOf("Best,\nWasi", "Cheers"), tags = listOf("work", "email"),
    )
    private val today = Snippet(id = 4, label = "Today", text = "Sent on {date:EEEE d MMMM}, {clip}", createdAt = 50, trigger = "//today")
    private val distance = Snippet(id = 5, label = "Kilometres", text = "$1 kilometres", createdAt = 10, triggerPattern = "(\\d+)km")
    private val all = listOf(address, bank, signOff, today, distance)

    private fun ids(found: List<Snippet>) = found.map { it.id }

    @Test
    fun `words are found in the name, every trigger, the tags and every expansion`() {
        assertEquals(listOf(1L), ids(AiSnippetSearch.byWords(all, "address")))
        assertEquals(listOf(1L), ids(AiSnippetSearch.byWords(all, "ADR")))
        assertEquals(listOf(1L), ids(AiSnippetSearch.byWords(all, "personal")))
        assertEquals(listOf(1L), ids(AiSnippetSearch.byWords(all, "sylhet")))
        // An alternate is text the snippet inserts too.
        assertEquals(listOf(3L), ids(AiSnippetSearch.byWords(all, "cheers")))
    }

    @Test
    fun `every word must be there, though not all in one field`() {
        assertEquals(listOf(3L), ids(AiSnippetSearch.byWords(all, "email regards")))
        assertEquals(emptyList<Long>(), ids(AiSnippetSearch.byWords(all, "email iban")))
    }

    @Test
    fun `a name or a trigger that is the query comes before a text that holds it`() {
        val inText = Snippet(id = 9, label = "Transfer note", text = "Paid by bank transfer", createdAt = 999)
        assertEquals(listOf(2L, 9L), ids(AiSnippetSearch.byWords(listOf(inText, bank), "bank")))
    }

    @Test
    fun `no query lists the newest, at most five`() {
        val many = all + Snippet(id = 6, label = "Old", text = "old", createdAt = 1)
        assertEquals(listOf(2L, 3L, 1L, 4L, 5L), ids(AiSnippetSearch.byWords(many, "  ")))
    }

    @Test
    fun `the meaning half drops what the words found, and what is gone from the store`() {
        val hits = listOf(
            AiMeaningHit(AiMeaningSource.SNIPPET, "Home address", "12 Lake Road", 0.9f, "1"),
            AiMeaningHit(AiMeaningSource.SNIPPET, "Bank", "IBAN", 0.8f, "2"),
            // Deleted since the index was built.
            AiMeaningHit(AiMeaningSource.SNIPPET, "Gone", "gone", 0.7f, "77"),
            AiMeaningHit(AiMeaningSource.SNIPPET, "Bank", "IBAN", 0.6f, "2"),
        )
        val text = AiSnippetSearch.answer(listOf(address), hits, all)
        val (words, meaning) = text.split("Close in meaning:\n")
        assertTrue(words.startsWith("Home address"))
        assertTrue("Home address" !in meaning)
        assertEquals(1, Regex("^Bank", RegexOption.MULTILINE).findAll(meaning).count())
        assertTrue("Gone" !in text)
    }

    @Test
    fun `meaning matches alone still answer, and nothing at all says so`() {
        val hit = AiMeaningHit(AiMeaningSource.SNIPPET, "Bank", "IBAN", 0.8f, "2")
        assertTrue(AiSnippetSearch.answer(emptyList(), listOf(hit), all).startsWith("Close in meaning:\nBank"))
        assertEquals(AiSnippetSearch.NOTHING, AiSnippetSearch.answer(emptyList(), emptyList(), all))
    }

    @Test
    fun `a snippet reads as its name, its triggers and its texts`() {
        assertEquals(
            "Home address (trigger: \"addr\", \"adr\")\n12 Lake Road\nSylhet 3100",
            AiSnippetSearch.describe(address),
        )
        assertEquals(
            "Sign-off (trigger: \"kr\")\nKind regards,\nWasi\nOr: Best,\nWasi\nOr: Cheers",
            AiSnippetSearch.describe(signOff),
        )
        assertEquals("(no name)\nhello", AiSnippetSearch.describe(Snippet(id = 8, label = " ", text = "hello")))
    }

    @Test
    fun `a template or a pattern is shown as written, never run`() {
        val template = AiSnippetSearch.describe(today)
        assertTrue("{date:EEEE d MMMM}, {clip}" in template)
        assertTrue("[a template" in template)

        val pattern = AiSnippetSearch.describe(distance)
        assertTrue(pattern.startsWith("Kilometres (trigger pattern: (\\d+)km)"))
        assertTrue("$1 kilometres" in pattern)
        assertTrue("[a template" in pattern)

        assertFalse("[a template" in AiSnippetSearch.describe(bank))
    }

    @Test
    fun `a long text is cut short`() {
        val long = Snippet(id = 7, label = "Long", text = "x".repeat(2_000))
        val body = AiSnippetSearch.describe(long).lines()[1]
        assertEquals(AiSnippetSearch.TEXT_CHARS, body.length)
        assertTrue(body.endsWith("…"))
    }
}
