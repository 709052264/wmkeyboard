package com.wasimaster.wmkeyboard.core.gesture.eval

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pinning the sampling list keeps an eval's cases fixed across dictionaries (#837). */
class EvalSamplingTest {

    private val words = listOf("the", "of", "and", "something", "together", "different", "people", "between")

    private fun list(freqs: List<Int>): List<Pair<String, Int>> = words.zip(freqs)

    private fun file(entries: List<Pair<String, Int>>): File =
        File.createTempFile("eval-sample", ".txt").apply {
            deleteOnExit()
            writeText(entries.joinToString("\n") { (w, f) -> "$w $f" })
        }

    private fun cases(sampled: List<Pair<String, Int>>): List<String> =
        DuetCorpus(SEED).generate(sampled, SwipeCorpus.Noise.TYPICAL, 40).map { it.intended }

    @Test
    fun `unset, the dictionary under test is the sampling list`() {
        val before = list(listOf(5000, 4000, 3000, 2000, 1000, 500, 200, 100))
        assertEquals(before, EvalSampling.entries(before) { null })
    }

    @Test
    fun `a pinned list draws the same cases whatever the dictionary's frequencies`() {
        val before = list(listOf(5000, 4000, 3000, 2000, 1000, 500, 200, 100))
        val after = list(listOf(100, 200, 500, 1000, 2000, 3000, 4000, 5000))
        // Without a pin the rescale changes the exam itself.
        assertNotEquals(cases(before), cases(after))
        val pinned = file(before).path
        val env: (String) -> String? = { if (it == EvalSampling.ENV) pinned else null }
        assertEquals(cases(EvalSampling.entries(before, env)), cases(EvalSampling.entries(after, env)))
    }

    private companion object {
        const val SEED = 5L
    }
}
