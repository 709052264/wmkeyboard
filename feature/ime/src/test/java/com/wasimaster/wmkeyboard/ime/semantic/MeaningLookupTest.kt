package com.wasimaster.wmkeyboard.ime.semantic

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.notes.Note
import com.wasimaster.wmkeyboard.core.semantic.VectorIndex
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.tools.AiMeaningFormat
import com.wasimaster.wmkeyboard.core.tools.AiMeaningHit
import com.wasimaster.wmkeyboard.core.tools.AiMeaningSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search by meaning for search_clipboard, search_notes and search_snippets, and the words the
 * model reads, with a fake embedder: four axes stand for four topics, so which
 * items a query lands on is known in advance, and what is left out is left out
 * by the filters.
 */
class MeaningLookupTest {

    // Axis 0: the dentist. 1: travel. 2: food. 3: everything else.
    private fun v(vararg x: Float) = floatArrayOf(*x)
    private val dentist = v(1f, 0f, 0f, 0f)

    private val clinic = ClipItem(id = 1, text = "Dr Rahman's clinic,\n12 Lake Road", timestamp = 0)
    private val password = ClipItem(id = 2, text = "the dentist portal login", timestamp = 0, sensitive = true)
    private val shapedLikeOne = ClipItem(id = 3, text = "Xk9#mQ2!vLp7", timestamp = 0)
    private val code = ClipItem(id = 4, text = "483920", timestamp = 0)
    private val flight = ClipItem(id = 5, text = "Flight BG 147 to Sylhet", timestamp = 0)

    private val appointment = Note(id = 10, title = "Dentist", body = "Tuesday at 3 pm, bring the X-rays")
    private val locked = Note(id = 11, title = "Teeth", body = "insurance number 0042", locked = true)
    private val trashed = Note(id = 12, body = "old dentist, closed now", deletedAt = 5)

    private val dentistReply = Snippet(id = 20, label = "Dentist reply", text = "Booked for {date:EEEE}, see you then", trigger = "dent")

    private fun indexes(): Map<MeaningSpace, VectorIndex> {
        val clips = VectorIndex(4).apply {
            put("1", 0, v(0.95f, 0f, 0f, 0.31f))
            put("2", 0, dentist)
            put("3", 0, dentist)
            put("4", 0, dentist)
            put("5", 0, v(0f, 1f, 0f, 0f))
            // Deleted from the clipboard since it was indexed.
            put("99", 0, dentist)
        }
        val notes = VectorIndex(4).apply {
            put("10", 0, v(0.9f, 0f, 0f, 0.43f))
            put("11", 0, dentist)
            put("12", 0, dentist)
        }
        val snippets = VectorIndex(4).apply {
            put("20", 0, v(0.92f, 0f, 0f, 0.39f))
            // Deleted from the snippets since it was indexed.
            put("21", 0, dentist)
        }
        return mapOf(MeaningSpace.CLIPS to clips, MeaningSpace.NOTES to notes, MeaningSpace.SNIPPETS to snippets)
    }

    private fun recall(embedder: (String) -> FloatArray? = { dentist }): MeaningLookup {
        val byIndex = indexes()
        return MeaningLookup(embedQuery = { embedder(it) }, indexOf = { byIndex[it] ?: VectorIndex(4) })
    }

    private val sources = MeaningLookup.Sources(
        clips = listOf(clinic, password, shapedLikeOne, code, flight),
        notes = listOf(appointment, locked, trashed),
    )

    @Test
    fun `finds across sources best first, on one line each`() = runBlocking {
        val hits = recall().search("where is my dentist", sources)!!
        assertEquals(2, hits.size)
        assertEquals(AiMeaningSource.CLIPBOARD, hits[0].source)
        assertEquals("Dr Rahman's clinic, 12 Lake Road", hits[0].text)
        assertNull(hits[0].title)
        assertEquals(AiMeaningSource.NOTE, hits[1].source)
        assertEquals("Dentist", hits[1].title)
        assertTrue(hits[0].score > hits[1].score)
        // Each hit names its item, so search_notes can drop what its words found.
        assertEquals("1", hits[0].id)
        assertEquals("10", hits[1].id)
    }

    @Test
    fun `the clipboard search reads only clips, the notes search only notes`() = runBlocking {
        val clipsOnly = recall().search("dentist", MeaningLookup.Sources(clips = sources.clips))!!
        assertTrue(clipsOnly.isNotEmpty() && clipsOnly.all { it.source == AiMeaningSource.CLIPBOARD })
        val notesOnly = recall().search("dentist", MeaningLookup.Sources(notes = sources.notes))!!
        assertEquals(listOf(AiMeaningSource.NOTE), notesOnly.map { it.source })
    }

    @Test
    fun `the snippets search reads only live snippets, by id, their template as written`() = runBlocking {
        val hits = recall().search("dentist", MeaningLookup.Sources(snippets = listOf(dentistReply)))!!
        assertEquals(1, hits.size)
        assertEquals(AiMeaningSource.SNIPPET, hits[0].source)
        assertEquals("Dentist reply", hits[0].title)
        assertEquals("Booked for {date:EEEE}, see you then", hits[0].text)
        assertEquals("20", hits[0].id)
    }

    @Test
    fun `passwords, codes, locked, trashed and deleted things never come back`() = runBlocking {
        val texts = recall().search("dentist", sources)!!.map { it.text }
        assertTrue(texts.none { "portal" in it })
        assertTrue(texts.none { "Xk9" in it })
        assertTrue(texts.none { "483920" in it })
        assertTrue(texts.none { "insurance" in it })
        assertTrue(texts.none { "closed now" in it })
    }

    @Test
    fun `a query about nothing the user has finds nothing`() = runBlocking {
        val hits = recall { v(0f, 0f, 0f, 1f) }.search("quantum chromodynamics", sources)!!
        assertEquals(emptyList<AiMeaningHit>(), hits)
    }

    @Test
    fun `no model answer is null, a too-short query is not asked`() = runBlocking {
        var asked = 0
        assertNull(recall { asked++; null }.search("dentist", sources))
        assertEquals(emptyList<AiMeaningHit>(), recall { asked++; dentist }.search(" d ", sources))
        assertEquals(1, asked)
    }

    @Test
    fun `travel lands on the flight`() = runBlocking {
        assertEquals(listOf("Flight BG 147 to Sylhet"), recall { v(0f, 1f, 0f, 0f) }.search("trip", sources)!!.map { it.text })
    }

    @Test
    fun `the model reads numbered, labelled lines`() {
        val text = AiMeaningFormat.format(
            listOf(
                AiMeaningHit(AiMeaningSource.CLIPBOARD, null, "Dr Rahman's clinic, 12 Lake Road", 0.95f),
                AiMeaningHit(AiMeaningSource.NOTE, "Dentist", "Tuesday at 3 pm", 0.9f),
                AiMeaningHit(AiMeaningSource.NOTE, " ", "Lentils", 0.8f),
            ),
        )
        assertEquals(
            "1. [clipboard] Dr Rahman's clinic, 12 Lake Road\n" +
                "2. [note] Dentist: Tuesday at 3 pm\n" +
                "3. [note] Lentils",
            text,
        )
        assertEquals(AiMeaningFormat.NOTHING, AiMeaningFormat.format(emptyList()))
    }

    @Test
    fun `at most eight hits are read, and a long text is cut at a word`() {
        val many = List(12) { AiMeaningHit(AiMeaningSource.NOTE, null, "note $it", 1f - it / 100f) }
        assertEquals(AiMeaningFormat.MAX_HITS, AiMeaningFormat.format(many).lines().size)

        val long = "word ".repeat(100)
        val cut = AiMeaningFormat.excerpt(long, max = 40)
        assertTrue(cut.length <= 40)
        assertTrue(cut.endsWith("word…"))
        assertEquals("a b c", AiMeaningFormat.excerpt("  a\n\tb   c "))
    }
}
