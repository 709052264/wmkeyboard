package com.wasimaster.wmkeyboard.core.imagegen

import java.util.Base64
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTokenizerTest {

    private val tokenizer: ClipTokenizer by lazy {
        val stream = javaClass.getResourceAsStream("/imagegen/clip-merges.txt.gz") ?: error("merges missing")
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).useLines { ClipTokenizer(it) }
    }

    private val fixtures: List<Pair<String, IntArray>> by lazy {
        val stream = javaClass.getResourceAsStream("/imagegen/clip-fixtures.tsv") ?: error("fixtures missing")
        stream.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (text, ids) = line.split('\t')
                String(Base64.getDecoder().decode(text), Charsets.UTF_8) to ids.split(',').map(String::toInt).toIntArray()
            }
    }

    @Test
    fun matchesTransformersTokenizer() {
        for ((text, expected) in fixtures) {
            assertArrayEquals("\"$text\"", expected, tokenizer.encode(text))
        }
    }

    @Test
    fun specialIdsAreTheVocabularyEnd() {
        assertEquals(49406, tokenizer.startId)
        assertEquals(49407, tokenizer.endId)
    }

    @Test
    fun promptWrapsAndPads() {
        val p = tokenizer.prompt("a cute cat wearing sunglasses")
        assertEquals(ClipTokenizer.MAX_TOKENS, p.ids.size)
        assertEquals(49406, p.ids[0])
        assertArrayEquals(tokenizer.encode("a cute cat wearing sunglasses"), p.ids.copyOfRange(1, 1 + p.used))
        assertEquals(49407, p.ids[1 + p.used])
        assertTrue(p.ids.drop(2 + p.used).all { it == 49407 })
        assertEquals(p.used, p.needed)
        assertEquals("", p.ignored)
    }

    @Test
    fun longPromptKeepsSeventyFiveTokensAndNamesTheRest() {
        val text = (1..30).joinToString(" ") { "red balloon$it" }
        val p = tokenizer.prompt(text, pad = 0)
        assertEquals(75, p.used)
        assertTrue(p.needed > 75)
        assertEquals(49407, p.ids[76])
        assertTrue(p.ignored.isNotEmpty())
        assertTrue(text.lowercase().endsWith(p.ignored.substringAfterLast(' ')))
        // What was kept plus what was ignored is the whole prompt.
        val kept = tokenizer.encode(text).take(75)
        assertEquals(kept, p.ids.copyOfRange(1, 76).toList())
    }
}
