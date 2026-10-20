package com.wasimaster.wmkeyboard.core.prediction

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DivvunChecker]'s own logic over a stand-in for the native library: the
 * bounded wait on the main thread, caching, words it never asks about, and
 * closing after a call in progress. The library itself is checked by the
 * host probe in native/divvunspell-jni.
 */
class DivvunCheckerTest {

    private class FakeBackend(
        val words: Set<String>,
        val spellings: Map<String, List<String>> = emptyMap(),
    ) : DivvunBackend {
        val checked = ArrayList<String>()
        val closed = ArrayList<Long>()
        var checkGate: CountDownLatch? = null
        var suggestGate: CountDownLatch? = null
        var suggestEntered: CountDownLatch? = null

        override fun open(path: String, tempDir: String): Long = 1L

        override fun close(handle: Long) {
            synchronized(closed) { closed += handle }
        }

        override fun isCorrect(handle: Long, word: String): Boolean {
            checkGate?.await(5, TimeUnit.SECONDS)
            synchronized(checked) { checked += word }
            return word in words
        }

        override fun suggest(handle: Long, word: String, limit: Int): Array<String>? {
            suggestEntered?.countDown()
            suggestGate?.await(5, TimeUnit.SECONDS)
            return spellings[word]?.take(limit)?.toTypedArray() ?: emptyArray()
        }

        override fun unpack(source: String, member: String, target: String): String? = "not here"
    }

    @Test
    fun acceptsWhatTheSpellerAcceptsAndRemembersIt() {
        val backend = FakeBackend(setOf("sámegiella", "giella"))
        val checker = DivvunChecker(backend, 1L)
        assertTrue(checker.accepts("sámegiella"))
        assertFalse(checker.accepts("sámegiela"))
        assertTrue(checker.accepts("sámegiella"))
        assertEquals(listOf("sámegiella", "sámegiela"), backend.checked)
        checker.close()
    }

    @Test
    fun wordsWithoutLettersAreNeverAsked() {
        // The speller calls them all correct, which would make "123" known.
        val backend = FakeBackend(emptySet())
        val checker = DivvunChecker(backend, 1L)
        assertFalse(checker.accepts("123"))
        assertFalse(checker.accepts("..."))
        assertFalse(checker.accepts("x".repeat(DivvunChecker.MAX_WORD_LENGTH + 1)))
        assertTrue(backend.checked.isEmpty())
        assertTrue(checker.suggestions("123", 4).isEmpty())
        checker.close()
    }

    @Test
    fun aSlowCheckIsANoThatIsRememberedOnceItLands() {
        val gate = CountDownLatch(1)
        val backend = FakeBackend(setOf("guovdageaidnu")).apply { checkGate = gate }
        val checker = DivvunChecker(backend, 1L)
        val started = System.nanoTime()
        assertFalse(checker.accepts("guovdageaidnu"))
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $waitedMs ms", waitedMs < DivvunChecker.SPELL_WAIT_MS + 200)
        gate.countDown()
        // The walk finishes on its own thread; the next ask finds its answer.
        var answer = false
        repeat(100) {
            if (!answer) {
                answer = checker.accepts("guovdageaidnu")
                if (!answer) Thread.sleep(10)
            }
        }
        assertTrue(answer)
        assertEquals(1, backend.checked.size)
        checker.close()
    }

    @Test
    fun suggestionsDropTheWordItselfAndRepeats() {
        val backend = FakeBackend(
            emptySet(),
            mapOf("boatit" to listOf("boahtit", "boatit", "boađit", "boahtit", " bohtit ")),
        )
        val checker = DivvunChecker(backend, 1L)
        assertEquals(listOf("boahtit", "boađit", "bohtit"), checker.suggestions("boatit", 5))
        assertEquals(listOf("boahtit"), checker.suggestions("boatit", 1))
        checker.close()
    }

    @Test
    fun closeWaitsForASuggestionRunAndAnswersNothingAfter() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val backend = FakeBackend(setOf("dálki"), mapOf("dalki" to listOf("dálki"))).apply {
            suggestGate = gate
            suggestEntered = entered
        }
        val checker = DivvunChecker(backend, 7L)
        var found: List<String> = emptyList()
        val run = Thread { found = checker.suggestions("dalki", 3) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread { checker.close() }.apply { start() }
        Thread.sleep(50)
        assertTrue("closed while a run was inside", backend.closed.isEmpty())
        gate.countDown()
        run.join(5_000)
        closer.join(5_000)
        assertEquals(listOf("dálki"), found)
        assertEquals(listOf(7L), backend.closed)
        assertFalse(checker.accepts("dálki"))
        assertTrue(checker.suggestions("dalki2", 3).isEmpty())
        checker.close()
        assertEquals(listOf(7L), backend.closed)
    }

    @Test
    fun unionAcceptsEitherAndTakesTurns() {
        val a = object : WordChecker {
            override fun accepts(word: String) = word == "Haus"
            override fun suggestions(word: String, limit: Int) = listOf("a1", "a2", "shared").take(limit)
        }
        val b = object : WordChecker {
            override fun accepts(word: String) = word == "giella"
            override fun suggestions(word: String, limit: Int) = listOf("b1", "shared", "b3").take(limit)
        }
        val both = WordChecker.of(listOf(a, b))
        assertTrue(both.accepts("Haus"))
        assertTrue(both.accepts("giella"))
        assertFalse(both.accepts("nope"))
        assertEquals(listOf("a1", "b1", "a2", "shared"), both.suggestions("x", 4))
        assertTrue(WordChecker.of(emptyList()) === WordChecker.NONE)
        assertTrue(WordChecker.of(listOf(a)) === a)
    }
}
