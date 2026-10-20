package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import java.io.File

/**
 * The word list an eval draws its cases from (#837).
 *
 * The suites that sample words by frequency (DuetBeamEvalTest, the base half
 * of GlideRerankOracleTest, RerankHarmTest's neighbour filter,
 * GlideShiftDetourEvalTest) drew them from
 * the dictionary under test, so a dictionary change changed the cases too and
 * a before/after comparison measured two different exams. Set
 * [ENV] to a word list file and every case is drawn from that file instead,
 * while the decoder still reads the dictionary under test:
 *
 *     git show 13dae189e^:app/dictionaries-src/en.txt > /tmp/en-before.txt
 *     WM_EVAL_SAMPLE_DICT=/tmp/en-before.txt WM_DUET_EVAL=1 ./gradlew :app:testIntlDebugUnitTest \
 *         --tests '*DuetBeamEvalTest' --tests '*GlideRerankOracleTest' --tests '*RerankHarmTest'
 *
 * Run once with the old file in place and once with the new, both with
 * [ENV] naming the old one, and the two runs read the same cases.
 */
internal object EvalSampling {

    const val ENV = "WM_EVAL_SAMPLE_DICT"

    /** [underTest], or the list [ENV] names when it is set. [env] is the environment, for the tests. */
    fun entries(
        underTest: List<Pair<String, Int>>,
        env: (String) -> String? = System::getenv,
    ): List<Pair<String, Int>> {
        val path = env(ENV)?.takeIf { it.isNotBlank() } ?: return underTest
        return File(path).inputStream().use { DictionaryLoader.loadEntries(it) }
    }
}
