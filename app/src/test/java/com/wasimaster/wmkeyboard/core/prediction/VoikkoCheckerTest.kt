package com.wasimaster.wmkeyboard.core.prediction

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VoikkoChecker]'s own logic over a stand-in for libvoikko: UTF-8 on the
 * way in and out, caching, words it never asks about, hyphenation offsets,
 * the bounded wait on the main thread, and closing after a call in progress.
 * The library itself is checked against the real Finnish dictionary by the
 * desktop run described in native/voikko-jni/README.md.
 */
class VoikkoCheckerTest {

    private class FakeBackend(
        val words: Set<String>,
        val spellings: Map<String, List<String>> = emptyMap(),
        val patterns: Map<String, String> = emptyMap(),
    ) : VoikkoBackend {
        val spelled = ArrayList<String>()
        val closed = ArrayList<Long>()
        var suggestGate: CountDownLatch? = null
        var suggestEntered: CountDownLatch? = null

        override fun open(language: String, dictionaryDir: String): Long = 1L

        override fun close(handle: Long) {
            synchronized(closed) { closed += handle }
        }

        override fun spell(handle: Long, word: ByteArray): Boolean {
            val text = String(word, Charsets.UTF_8)
            synchronized(spelled) { spelled += text }
            return text in words
        }

        override fun suggest(handle: Long, word: ByteArray, limit: Int): Array<ByteArray>? {
            suggestEntered?.countDown()
            suggestGate?.await(5, TimeUnit.SECONDS)
            val text = String(word, Charsets.UTF_8)
            return spellings[text]?.take(limit)?.map { it.toByteArray(Charsets.UTF_8) }?.toTypedArray() ?: emptyArray()
        }

        override fun hyphenate(handle: Long, word: ByteArray): String? = patterns[String(word, Charsets.UTF_8)]
    }

    @Test
    fun acceptsInUtf8AndRemembersTheAnswer() {
        val backend = FakeBackend(setOf("kahvinkeittimessäkin"))
        val checker = VoikkoChecker(backend, 1L)
        assertTrue(checker.accepts("kahvinkeittimessäkin"))
        assertFalse(checker.accepts("kahvinkeitimessäkin"))
        assertTrue(checker.accepts("kahvinkeittimessäkin"))
        assertEquals(listOf("kahvinkeittimessäkin", "kahvinkeitimessäkin"), backend.spelled)
        checker.close()
    }

    @Test
    fun wordsWithoutLettersAreNeverAsked() {
        val backend = FakeBackend(emptySet())
        val checker = VoikkoChecker(backend, 1L)
        assertFalse(checker.accepts("2026"))
        assertFalse(checker.accepts("..."))
        assertFalse(checker.accepts("a".repeat(VoikkoChecker.MAX_WORD_LENGTH + 1)))
        assertTrue(backend.spelled.isEmpty())
        assertTrue(checker.suggestions("2026", 4).isEmpty())
        checker.close()
    }

    @Test
    fun suggestionsDropTheWordItselfAndRepeats() {
        val backend = FakeBackend(
            emptySet(),
            spellings = mapOf("talossa" to listOf("talossa", "talossaan", "talossaan", "taloissa", "talassa")),
        )
        val checker = VoikkoChecker(backend, 1L)
        assertEquals(listOf("talossaan", "taloissa"), checker.suggestions("talossa", 2))
        assertEquals(listOf("talossaan", "taloissa", "talassa"), checker.suggestions("talossa", 5))
        checker.close()
    }

    @Test
    fun hyphenationCountsInUtf16() {
        val backend = FakeBackend(
            emptySet(),
            patterns = mapOf(
                "kahvikuppi" to "   - -  - ",
                // libvoikko's pattern has one place per code point: the
                // break after the supplementary letter is UTF-16 index 5.
                "kis𝔞sa" to "    - ",
                // '=' drops the apostrophe for the hyphen: vaa-an.
                "vaa'an" to "   =  ",
            ),
        )
        val checker = VoikkoChecker(backend, 1L)
        assertEquals(
            listOf(3, 5, 8).map { VoikkoChecker.HyphenPoint(it, false) },
            checker.hyphenate("kahvikuppi"),
        )
        assertEquals(listOf(VoikkoChecker.HyphenPoint(5, false)), checker.hyphenate("kis𝔞sa"))
        assertEquals(listOf(VoikkoChecker.HyphenPoint(3, true)), checker.hyphenate("vaa'an"))
        assertNull(checker.hyphenate("tuntematon"))
        checker.close()
    }

    @Test
    fun aSuggestionRunMakesTheMainThreadSayNoRatherThanWait() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val backend = FakeBackend(setOf("talo"), spellings = mapOf("tlao" to listOf("talo"))).apply {
            suggestGate = gate
            suggestEntered = entered
        }
        val checker = VoikkoChecker(backend, 1L)
        val worker = Thread { checker.suggestions("tlao", 3) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        assertFalse(checker.accepts("talo"))
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $waitedMs ms", waitedMs < 1_000)
        gate.countDown()
        worker.join(5_000)
        // Not remembered as a no: once the lock is free it asks again.
        assertTrue(checker.accepts("talo"))
        checker.close()
    }

    @Test
    fun closeWaitsForARunInProgressAndClosesOnce() {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val backend = FakeBackend(emptySet(), spellings = mapOf("tlao" to listOf("talo"))).apply {
            suggestGate = gate
            suggestEntered = entered
        }
        val checker = VoikkoChecker(backend, 7L)
        var found: List<String>? = null
        val worker = Thread { found = checker.suggestions("tlao", 3) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread { checker.close() }.apply { start() }
        Thread.sleep(50)
        assertTrue(backend.closed.isEmpty())
        gate.countDown()
        worker.join(5_000)
        closer.join(5_000)
        assertEquals(listOf("talo"), found)
        assertEquals(listOf(7L), backend.closed)
        checker.close()
        assertEquals(listOf(7L), backend.closed)
        assertFalse(checker.accepts("talo"))
        assertTrue(checker.suggestions("tloa", 3).isEmpty())
    }
}
