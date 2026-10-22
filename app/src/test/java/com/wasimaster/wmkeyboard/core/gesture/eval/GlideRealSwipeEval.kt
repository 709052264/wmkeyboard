// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measurement, not a gate: [GlideBeam] at its shipped tuning on real
 * swipe.futo.org strokes, which the synthetic [SwipeCorpus] only imitates.
 *
 * Skipped unless `WM_FUTO_DIR` names a folder holding the dataset's
 * `qwerty.json` and any of `test.jsonl`, `double.jsonl` and `stray.jsonl`
 * (the last two from `native/bench/futo-swipe/loopify.py`); see that folder's
 * README for the download. The first few thousand lines of `test.jsonl` are
 * plenty. Prints top-1 over the strokes whose word the bundled list holds,
 * and over the short words with a doubled letter, which is where #886 was:
 *
 * ```
 * WM_FUTO_DIR=/path/to/futo ./gradlew :app:testIntlDebugUnitTest --tests "*GlideRealSwipeEval"
 * ```
 */
class GlideRealSwipeEval {

    @Test
    fun measure() {
        val dir = System.getenv("WM_FUTO_DIR")?.let(::File)
        assumeTrue("set WM_FUTO_DIR to run this measurement", dir != null && dir.isDirectory)
        val layout = Json.parseToJsonElement(File(dir, "qwerty.json").readText()).jsonObject.getValue("keys").jsonArray
        val list = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt")).first { it.exists() }
        val entries = list.inputStream().use { DictionaryLoader.loadEntries(it) }
        val vocabulary = entries.map { it.first.lowercase() }.toHashSet()
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
        val beam = GlideBeam()
        val workspace = GlideWorkspace()
        for (name in listOf("test.jsonl", "double.jsonl", "stray.jsonl")) {
            val file = File(dir, name).takeIf { it.exists() } ?: continue
            var hits = 0
            var total = 0
            var shortHits = 0
            var shortTotal = 0
            file.forEachLine { line ->
                val record = Json.parseToJsonElement(line).jsonObject
                val word = record.text("word").lowercase()
                if (!word.matches(WORD) || word !in vocabulary) return@forEachLine
                val width = record.number("canvas_width")
                val height = record.number("canvas_height")
                val keyWidth = width * KEY_WIDTH_SHARE
                val keys = GlideKeyMap.of(
                    layout.map {
                        val key = it.jsonObject
                        KeyCenter(key.text("letter")[0].code, key.number("cx") * width, key.number("cy") * height)
                    },
                    keyWidth,
                )
                val path = record.getValue("data").jsonArray.map {
                    val point = it.jsonObject
                    GesturePoint(
                        point.number("x") * width,
                        point.number("y") * height,
                        point.getValue("t").jsonPrimitive.long,
                    )
                }
                val hit = beam.decode(path, keys, keyWidth, sources, workspace, LIMIT).firstOrNull()?.word == word
                total++
                if (hit) hits++
                if (word.length <= SHORT && (1 until word.length).any { word[it] == word[it - 1] }) {
                    shortTotal++
                    if (hit) shortHits++
                }
            }
            println(
                String.format(
                    Locale.ROOT, "%-13s top1 %.4f (n=%d)  short doubled %.4f (n=%d)",
                    name, hits / maxOf(1, total).toDouble(), total, shortHits / maxOf(1, shortTotal).toDouble(), shortTotal,
                ),
            )
        }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.number(key: String): Float = getValue(key).jsonPrimitive.double.toFloat()

    private companion object {
        val WORD = Regex("[a-z]{2,}")

        /** The dataset's keys are a tenth of the canvas wide. */
        const val KEY_WIDTH_SHARE = 0.1f
        const val LIMIT = 4
        const val SHORT = 4
    }
}
