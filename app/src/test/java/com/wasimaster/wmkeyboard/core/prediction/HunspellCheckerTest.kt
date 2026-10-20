package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HunspellChecker]'s own logic over a stand-in for the native library:
 * encoding words into each dictionary's charset, caching, the lock the main
 * thread does not wait on, and closing. The library itself is not here.
 */
class HunspellCheckerTest {

    /** One fake dictionary: its encoding, the words it accepts and what it suggests, all as text. */
    private class FakeDictionary(
        val encoding: String,
        val words: Set<String>,
        val suggestions: Map<String, List<String>> = emptyMap(),
    )

    private class FakeBackend(vararg dictionaries: Pair<String, FakeDictionary>) : HunspellBackend {
        private val byPath = dictionaries.toMap()
        private val open = HashMap<Long, FakeDictionary>()
        val spellCalls = ArrayList<ByteArray>()
        val closed = ArrayList<Long>()
        var suggestGate: CountDownLatch? = null
        var suggestEntered: CountDownLatch? = null

        override fun open(aff: String, dic: String): Long {
            val dictionary = byPath[aff] ?: return 0L
            val handle = open.size + 1L
            open[handle] = dictionary
            return handle
        }

        override fun close(handle: Long) {
            closed += handle
        }

        override fun encoding(handle: Long): String = open.getValue(handle).encoding

        override fun spell(handle: Long, word: ByteArray): Boolean {
            spellCalls += word
            val d = open.getValue(handle)
            val charset = HunspellEncoding.charsetFor(d.encoding)!!
            return d.words.any { it.toByteArray(charset).contentEquals(word) }
        }

        override fun suggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>? {
            suggestEntered?.countDown()
            suggestGate?.await(5, TimeUnit.SECONDS)
            val d = open.getValue(handle)
            val charset = HunspellEncoding.charsetFor(d.encoding)!!
            val text = String(word, charset)
            return d.suggestions[text].orEmpty().take(limit).map { it.toByteArray(charset) }.toTypedArray()
        }
    }

    private fun files(name: String) = CustomDictionaries.HunspellFiles(File(name), File("$name.dic"))

    private val latin1 = FakeDictionary(
        "ISO8859-1",
        setOf("Straße", "Donaudampfschiff"),
        mapOf("Donaudampfschif" to listOf("Donaudampfschiff", "Donau dampfschif", "Donaudampfschif")),
    )

    @Test
    fun aWordIsEncodedInTheDictionarysOwnCharset() {
        val backend = FakeBackend("de" to latin1)
        val checker = HunspellChecker.open(backend, listOf(files("de")))!!
        assertTrue(checker.accepts("Straße"))
        // ß is one byte in ISO 8859-1, two in UTF-8: the dictionary got the right one.
        assertArrayEquals(byteArrayOf(0x53, 0x74, 0x72, 0x61, 0xDF.toByte(), 0x65), backend.spellCalls.single())
        assertFalse(checker.accepts("Strasse"))
    }

    @Test
    fun aWordTheCharsetCannotHoldIsNotAskedAbout() {
        val backend = FakeBackend("de" to latin1)
        val checker = HunspellChecker.open(backend, listOf(files("de")))!!
        assertFalse(checker.accepts("привет"))
        assertTrue(backend.spellCalls.isEmpty())
        assertTrue(checker.suggestions("привет", 4).isEmpty())
    }

    @Test
    fun anyDictionaryAcceptingIsEnough() {
        val backend = FakeBackend(
            "de" to latin1,
            "de-extra" to FakeDictionary("UTF-8", setOf("Weißwurstäquator")),
        )
        val checker = HunspellChecker.open(backend, listOf(files("de"), files("de-extra")))!!
        assertTrue(checker.accepts("Straße"))
        assertTrue(checker.accepts("Weißwurstäquator"))
        assertFalse(checker.accepts("Weisswurst"))
    }

    @Test
    fun suggestionsAreDecodedDedupedAndNeverTheWordItself() {
        val backend = FakeBackend("de" to latin1)
        val checker = HunspellChecker.open(backend, listOf(files("de")))!!
        assertEquals(
            listOf("Donaudampfschiff", "Donau dampfschif"),
            checker.suggestions("Donaudampfschif", 4),
        )
        assertEquals(listOf("Donaudampfschiff"), checker.suggestions("Donaudampfschif", 1))
    }

    @Test
    fun answersAreRemembered() {
        val backend = FakeBackend("de" to latin1)
        val checker = HunspellChecker.open(backend, listOf(files("de")))!!
        repeat(3) { assertTrue(checker.accepts("Straße")) }
        assertEquals(1, backend.spellCalls.size)
    }

    @Test
    fun openSkipsWhatDoesNotOpenAndWhatThePlatformCannotDecode() {
        val backend = FakeBackend(
            "odd" to FakeDictionary("no-such-charset-anywhere", setOf("x")),
            "de" to latin1,
        )
        // "missing" does not open at all (handle 0); "odd" opens and is closed again.
        val checker = HunspellChecker.open(backend, listOf(files("missing"), files("odd"), files("de")))
        assertNotNull(checker)
        assertEquals(listOf(1L), backend.closed)
        assertNull(HunspellChecker.open(FakeBackend(), listOf(files("missing"))))
        assertNull(HunspellChecker.open(backend, emptyList()))
    }

    @Test
    fun aClosedCheckerAcceptsNothingAndFreesOnce() {
        val backend = FakeBackend("de" to latin1)
        val checker = HunspellChecker.open(backend, listOf(files("de")))!!
        checker.close()
        checker.close()
        assertEquals(listOf(1L), backend.closed)
        assertFalse(checker.accepts("Straße"))
        assertTrue(checker.suggestions("Donaudampfschif", 4).isEmpty())
    }

    @Test
    fun theMainThreadDoesNotWaitOutASuggestionRun() {
        val backend = FakeBackend("de" to latin1)
        val checker = HunspellChecker.open(backend, listOf(files("de")))!!
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        backend.suggestGate = gate
        backend.suggestEntered = entered
        val worker = Thread { checker.suggestions("Donaudampfschif", 4) }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        // Held by the suggestion run: no answer rather than a wait.
        assertFalse(checker.accepts("Straße"))
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("waited $waitedMs ms", waitedMs < 1_000)
        gate.countDown()
        worker.join(5_000)
        // That no was not remembered: asked again, the word stands.
        assertTrue(checker.accepts("Straße"))
    }

    @Test
    fun hunspellEncodingNamesReadAsJavaCharsets() {
        assertEquals(Charsets.ISO_8859_1, HunspellEncoding.charsetFor("ISO8859-1"))
        assertEquals(Charset.forName("ISO-8859-2"), HunspellEncoding.charsetFor("ISO8859-2"))
        assertEquals(Charset.forName("ISO-8859-15"), HunspellEncoding.charsetFor("iso8859-15"))
        assertEquals(Charsets.UTF_8, HunspellEncoding.charsetFor("UTF-8"))
        assertEquals(Charset.forName("windows-1251"), HunspellEncoding.charsetFor("microsoft-cp1251"))
        assertEquals(Charset.forName("KOI8-R"), HunspellEncoding.charsetFor("KOI8-R"))
        // No SET means Hunspell's default.
        assertEquals(Charsets.ISO_8859_1, HunspellEncoding.charsetFor(""))
        assertNull(HunspellEncoding.charsetFor("no-such-charset-anywhere"))
    }
}
