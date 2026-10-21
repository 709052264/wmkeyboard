package com.wasimaster.wmkeyboard.core.voice.punctuation

import com.wasimaster.wmkeyboard.core.voice.punctuation.PunctuationText.COMMA
import com.wasimaster.wmkeyboard.core.voice.punctuation.PunctuationText.NONE
import com.wasimaster.wmkeyboard.core.voice.punctuation.PunctuationText.PERIOD
import com.wasimaster.wmkeyboard.core.voice.punctuation.PunctuationText.QUESTION
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The model's tokens read back onto a phrase. The real tokenizer and model
 * were checked on the JVM against Hugging Face's on 3,200 FLORES-200 and
 * Tatoeba texts in eight languages (identical ids, identical scores); here
 * each rule is pinned on input small enough to follow by hand.
 */
class PunctuationTextTest {

    /** A toy vocabulary: whole pieces where it has them, bytes for the rest. */
    private val pieces =
        listOf("<pad>", "<eos>", "<bos>", "▁hello", "▁how", "▁are", "▁you", "▁i", "▁am", "▁fine", "▁", "ok") +
            (0 until 256).map { "<0x%02X>".format(it) }
    private val ids = pieces.withIndex().associate { (i, p) -> p to i }

    /** Longest piece first, the way a BPE over this vocabulary ends up; bytes for what is left. */
    private fun encodeWord(word: String): IntArray {
        val spaced = word.replace(' ', '▁')
        val out = ArrayList<Int>()
        var at = 0
        while (at < spaced.length) {
            val whole = (spaced.length downTo at + 1).firstOrNull { ids[spaced.substring(at, it)] != null }
            if (whole != null) {
                out += ids.getValue(spaced.substring(at, whole))
                at = whole
            } else {
                val cp = spaced.codePointAt(at)
                for (byte in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                    out += ids.getValue("<0x%02X>".format(byte.toInt() and 0xFF))
                }
                at += Character.charCount(cp)
            }
        }
        return out.toIntArray()
    }

    private fun encode(text: String) = PunctuationText.encode(text, ::encodeWord) { pieces[it] }

    @Test
    fun `each word's last token ends where the word does`() {
        val e = encode("hello how are you")
        assertArrayEquals(intArrayOf(3, 4, 5, 6), e.ids)
        // "hello" is 0..5, "how" 6..9, "are" 10..13, "you" 14..17.
        assertArrayEquals(intArrayOf(5, 9, 13, 17), e.ends)
    }

    @Test
    fun `byte pieces count as the characters they spell`() {
        // "ok" plus 😀 (four bytes, two chars) and আ (three bytes, one char).
        val e = encode("ok😀 আ")
        val text = "ok😀 আ"
        // ▁ falls back to its piece, "ok" is whole, then 4 + 3 bytes.
        assertEquals(text.length, e.ends.last())
        assertEquals(4, e.ends[5]) // the emoji's last byte closes it at char 4
        assertEquals(2, e.ends[1]) // "ok"
    }

    @Test
    fun `labels come from runs of CHUNK tokens with OVERLAP between them`() {
        val n = PunctuationText.CHUNK + 120
        val input = IntArray(n) { 1000 + it }
        val windows = ArrayList<IntArray>()
        // Each token's label is its own id, so the result shows which run it came from.
        val labels = PunctuationText.labels(input) { w -> windows += w; w }!!
        assertEquals(2, windows.size)
        assertEquals(PunctuationText.BOS, windows[0].first())
        assertEquals(PunctuationText.EOS, windows[0].last())
        assertEquals(PunctuationText.CHUNK + 2, windows[0].size)
        // The second run starts OVERLAP tokens before the first one ended.
        assertEquals(input[PunctuationText.CHUNK - PunctuationText.OVERLAP], windows[1][1])
        assertArrayEquals(input, labels)
    }

    @Test
    fun `a failed run fails the phrase`() {
        assertNull(PunctuationText.labels(intArrayOf(5, 6)) { null })
        assertNull(PunctuationText.labels(intArrayOf(5, 6)) { IntArray(1) })
    }

    @Test
    fun `marks go at word ends and never after a mark already there`() {
        val text = "hello, how are"
        // A label inside "hello," (on its comma), after it, and the dash and colon labels.
        val ends = intArrayOf(5, 6, 10, 14)
        val marks = PunctuationText.marks(text, ends, intArrayOf(PERIOD, COMMA, 4, 5))
        assertEquals(NONE, marks[4]) // mid-word: "hello" ends at the comma, not a space
        assertEquals(NONE, marks[5]) // after the spoken comma
        assertEquals(NONE, marks[9]) // a dash is never typed
        assertEquals(NONE, marks[13]) // nor a colon
    }

    @Test
    fun `chinese takes a mark after any character`() {
        val text = "你好吗我很好"
        val marks = PunctuationText.marks(text, intArrayOf(2, 3, 6), intArrayOf(NONE, QUESTION, PERIOD))
        assertEquals("你好吗？我很好。", PunctuationText.render(text, marks, listOf("zh")))
    }

    private fun punctuate(
        text: String,
        labels: Map<String, Int>,
        languages: List<String>,
        names: Map<Int, String> = emptyMap(),
    ): String {
        // Labels by word, the word's end being its last token's.
        val words = text.split(' ')
        val ends = IntArray(words.size)
        var at = 0
        for ((i, w) in words.withIndex()) {
            at += w.length
            ends[i] = at
            at++
        }
        val marks = PunctuationText.marks(text, ends, IntArray(words.size) { labels[words[it]] ?: NONE })
        return PunctuationText.render(text, marks, languages, names)
    }

    @Test
    fun `english gets sentence capitals and the pronoun i`() {
        assertEquals(
            "Hello, how are you? I am fine.",
            punctuate("hello how are you i am fine", mapOf("hello" to COMMA, "you" to QUESTION, "fine" to PERIOD), listOf("en")),
        )
        assertEquals(
            "I'm here.",
            punctuate("i'm here", mapOf("here" to PERIOD), listOf("en")),
        )
    }

    @Test
    fun `each script ends a sentence its own way`() {
        assertEquals(
            "আমি ভালো আছি। তুমি?",
            punctuate("আমি ভালো আছি তুমি", mapOf("আছি" to PERIOD, "তুমি" to QUESTION), listOf("bn")),
        )
        // A number has no script: the words before it decide.
        assertEquals("दाम 25।", punctuate("दाम 25", mapOf("25" to PERIOD), listOf("hi")))
        assertEquals(
            "مرحبا، كيف حالك؟",
            punctuate("مرحبا كيف حالك", mapOf("مرحبا" to COMMA, "حالك" to QUESTION), listOf("ar")),
        )
        assertEquals("میں ٹھیک ہوں۔", punctuate("میں ٹھیک ہوں", mapOf("ہوں" to PERIOD), listOf("ur")))
        assertEquals("Τι κάνεις;", punctuate("τι κάνεις", mapOf("κάνεις" to QUESTION), listOf("el")))
    }

    @Test
    fun `japanese separates with its own comma`() {
        val text = "今日は雨ですが明日は晴れです"
        val marks = PunctuationText.marks(text, intArrayOf(7, 14), intArrayOf(COMMA, PERIOD))
        assertEquals("今日は雨ですが、明日は晴れです。", PunctuationText.render(text, marks, listOf("ja")))
    }

    @Test
    fun `a spanish question opens with an inverted mark`() {
        assertEquals(
            "Hola. ¿Cómo estás?",
            punctuate("hola cómo estás", mapOf("hola" to PERIOD, "estás" to QUESTION), listOf("es")),
        )
    }

    @Test
    fun `names are spelled as given, and only the same word`() {
        assertEquals(
            "I met John in London.",
            punctuate(
                "i met john in london",
                mapOf("london" to PERIOD),
                listOf("en"),
                names = mapOf(2 to "John", 4 to "London", 3 to "Inn"),
            ),
        )
    }

    @Test
    fun `another pass's capitals count only where they are not its sentence starts`() {
        val raw = "i met john yesterday then we went to paris"
        val cased = "I met John yesterday. Then we went to Paris."
        // "I" and "Then" open that pass's sentences: not names.
        assertEquals(mapOf(2 to "John", 8 to "Paris"), PunctuationText.casedWords(raw, cased))
        // Words that do not line up give nothing.
        assertNull(PunctuationText.casedWords(raw, "I met John."))
        assertNull(PunctuationText.casedWords("a b", "A c"))
    }

    @Test
    fun `normalize leaves single spaces and nothing at the ends`() {
        assertEquals("a b c", PunctuationText.normalize("  a \t b  c "))
    }
}
