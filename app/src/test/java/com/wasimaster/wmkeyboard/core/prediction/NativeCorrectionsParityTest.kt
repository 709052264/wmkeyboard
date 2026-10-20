package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.reference.KotlinCorrectionMemory
import com.wasimaster.wmkeyboard.core.prediction.reference.KotlinCorrectionStats
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Rust stores behind [CorrectionMemory] and [CorrectionStats] behave as
 * the Kotlin ones did ([KotlinCorrectionMemory], [KotlinCorrectionStats],
 * kept as the references) and read and write the same files
 * (native/RUST_NEON_PLAN.md step 3).
 *
 * Random runs of every operation, long enough to pass every cap (1,000
 * pairs, 500 penalties), every expiry (180 saves) and every halving window,
 * with every answer compared after each step and the files compared as
 * JSON; then old and damaged files read by both.
 */
class NativeCorrectionsParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private val spellings = listOf(
        "teh", "the", "thw", "thy", "form", "from", "t3st", "test", "amibtomake", "ami tomake",
        "helo", "hello", "Teh", "THE", "wirk", "work", "thhe", "recieve", "receive", "café", "cafe",
    )

    private fun word(random: Random): String =
        if (random.nextInt(3) == 0) "w${random.nextInt(1400)}x${random.nextInt(4)}" else spellings[random.nextInt(spellings.size)]

    private fun habits(h: EditHabits): String =
        listOf('3', 'e', 'h', 'r', 'i', ' ', 'b', 'x', 'w', 'o').joinToString { a ->
            "$a:${h.deletion(a)}/${h.insertion(a)}/${h.spaceSlip(a)}/" +
                listOf('e', 'h', 'r', 'o', 'w').joinToString("|") { b -> "${h.substitution(a, b)}" }
        }

    private fun summary(list: List<CorrectionMemory.Habit>) = list.map { "${it.kind}${it.from}${it.to}${it.count}" }

    private fun memoryAnswers(
        fixFor: (String) -> CorrectionMemory.Taught?,
        pairs: () -> List<Pair<String, CorrectionMemory.Taught>>,
        pairCount: () -> Int,
        habitsFor: (String) -> EditHabits,
        summary: (String?) -> List<CorrectionMemory.Habit>,
    ): List<String> = buildList {
        add("pairs=${pairs().map { "${it.first}>${it.second.fixed}:${it.second.count}" }} count=${pairCount()}")
        for (w in spellings) add("$w: ${fixFor(w)?.let { "${it.fixed}:${it.count}" }}")
        for (layout in listOf("qwerty", "azerty", "")) {
            add("$layout: ${habits(habitsFor(layout))} ${summary(summary(layout))}")
        }
        add("all: ${summary(summary(null))}")
    }

    private fun same(what: String, kotlin: KotlinCorrectionMemory, rust: CorrectionMemory) {
        val k = memoryAnswers(kotlin::fixFor, kotlin::pairs, kotlin::pairCount, kotlin::habitsFor) {
            if (it == null) kotlin.habitSummary() else kotlin.habitSummary(it)
        }
        val r = memoryAnswers(rust::fixFor, rust::pairs, rust::pairCount, rust::habitsFor) {
            if (it == null) rust.habitSummary() else rust.habitSummary(it)
        }
        assertEquals(what, k, r)
        assertEquals("$what: version", kotlin.version, rust.version)
    }

    private fun sameFile(what: String, kotlin: File, rust: File) {
        if (!kotlin.exists() && !rust.exists()) return
        assertEquals(what, json.parseToJsonElement(kotlin.readText()), json.parseToJsonElement(rust.readText()))
    }

    @Test
    fun correctionMemoryAgrees() {
        var steps = 0
        for (seed in 1..6) {
            val random = Random(seed)
            val kFile = File(tmp.newFolder("cmk$seed"), "learned_corrections.json")
            val rFile = File(tmp.newFolder("cmr$seed"), "learned_corrections.json")
            val kotlin = KotlinCorrectionMemory(kFile)
            val rust = CorrectionMemory(rFile)
            repeat(2600) { step ->
                val a = word(random)
                val b = word(random)
                when (random.nextInt(12)) {
                    in 0..5 -> {
                        val kind = if (random.nextInt(3) == 0) CorrectionMemory.Kind.PAIR_ONLY else CorrectionMemory.Kind.PAIR_AND_HABITS
                        val layout = listOf("qwerty", "azerty", "", " ").random(random)
                        kotlin.teach(a, b, layout, kind)
                        rust.teach(a, b, layout, kind)
                    }
                    6 -> {
                        kotlin.unteach(a, b)
                        rust.unteach(a, b)
                    }
                    7 -> if (random.nextInt(5) == 0) {
                        kotlin.forget(a)
                        rust.forget(a)
                    }
                    8, 9 -> {
                        kotlin.save()
                        rust.save()
                        sameFile("seed $seed step $step: file", kFile, rFile)
                    }
                    10 -> if (random.nextInt(10) == 0) {
                        kotlin.save()
                        rust.save()
                        kotlin.reload()
                        rust.reload()
                    }
                    11 -> if (random.nextInt(200) == 0) {
                        kotlin.clear()
                        rust.clear()
                    }
                }
                steps++
                if (step % 37 == 0) same("seed $seed step $step", kotlin, rust)
            }
            // Past the expiry: pairs untouched for 180 saves lose counts.
            repeat(200) {
                kotlin.teach("keep", "kept", "qwerty", CorrectionMemory.Kind.PAIR_ONLY)
                rust.teach("keep", "kept", "qwerty", CorrectionMemory.Kind.PAIR_ONLY)
                kotlin.save()
                rust.save()
            }
            same("seed $seed expired", kotlin, rust)
            sameFile("seed $seed expired file", kFile, rFile)
            // Each reads the other's file.
            same("seed $seed crossed", KotlinCorrectionMemory(rFile), CorrectionMemory(kFile))
        }
        println("correction memory: $steps steps, every answer identical")
    }

    @Test
    fun correctionStatsAgree() {
        var steps = 0
        for (seed in 1..6) {
            val random = Random(seed + 100)
            val kFile = File(tmp.newFolder("csk$seed"), "correction_stats.json")
            val rFile = File(tmp.newFolder("csr$seed"), "correction_stats.json")
            val kotlin = KotlinCorrectionStats(kFile)
            val rust = CorrectionStats(rFile)
            repeat(3000) { step ->
                val typed = word(random)
                val fixed = word(random)
                when (random.nextInt(14)) {
                    in 0..4 -> {
                        val deliberate = random.nextBoolean()
                        kotlin.recordRevert(typed, fixed, deliberate)
                        rust.recordRevert(typed, fixed, deliberate)
                    }
                    in 5..8 -> {
                        kotlin.recordKept(typed, fixed)
                        rust.recordKept(typed, fixed)
                    }
                    9 -> {
                        kotlin.endFieldSession()
                        rust.endFieldSession()
                    }
                    10 -> if (random.nextInt(8) == 0) {
                        val memory = UndoMemory.entries.random(random)
                        kotlin.memory = memory
                        rust.memory = memory
                    }
                    11 -> {
                        kotlin.save()
                        rust.save()
                        sameFile("seed $seed step $step: file", kFile, rFile)
                    }
                    12 -> if (random.nextInt(10) == 0) {
                        kotlin.save()
                        rust.save()
                        kotlin.reload()
                        rust.reload()
                    }
                    13 -> if (random.nextInt(300) == 0) {
                        kotlin.clear()
                        rust.clear()
                    }
                }
                steps++
                assertEquals("seed $seed step $step: multiplier", kotlin.confidenceMultiplier().toRawBits(), rust.confidenceMultiplier().toRawBits())
                assertEquals("seed $seed step $step: penalty $typed>$fixed", kotlin.penalty(typed, fixed), rust.penalty(typed, fixed))
                if (step % 41 == 0) {
                    for (a in spellings) {
                        for (b in spellings.take(6)) {
                            assertEquals("seed $seed step $step: penalty $a>$b", kotlin.penalty(a, b), rust.penalty(a, b))
                        }
                    }
                }
            }
            repeat(200) {
                kotlin.recordRevert("old", "new", false)
                rust.recordRevert("old", "new", false)
                kotlin.save()
                rust.save()
            }
            sameFile("seed $seed expired file", kFile, rFile)
        }
        println("correction stats: $steps steps, every answer identical")
    }

    @Test
    fun oldAndDamagedFilesReadTheSame() {
        val memories = mapOf(
            "plain" to """{"pairs":{"teh":[{"fixed":"the","count":3,"gen":4},{"fixed":"ten","count":1,"gen":2}]},"habits":{"qwerty":{"swap:he":9,"sub:3e":2}},"generation":5}""",
            "unsorted, zero and empty" to """{"pairs":{"teh":[{"fixed":"ten","count":1,"gen":2},{"fixed":"the","count":3,"gen":4},{"fixed":"","count":2,"gen":1},{"fixed":"tha","count":0,"gen":1}]},"habits":{"qwerty":{"del:x":0,"ins:e":8}}}""",
            "missing gen" to """{"pairs":{"teh":[{"fixed":"the","count":3}]}}""",
            "wrong type" to """{"pairs":{"teh":"the"}}""",
            "unknown keys" to """{"pairs":{},"future":1,"generation":3}""",
            "garbage" to "{",
        )
        for ((name, text) in memories) {
            val k = KotlinCorrectionMemory(File(tmp.newFolder("om-k-$name"), "f.json").apply { writeText(text) })
            val r = CorrectionMemory(File(tmp.newFolder("om-r-$name"), "f.json").apply { writeText(text) })
            same("memory $name", k, r)
        }
        val stats = mapOf(
            "plain" to """{"pairs":{"teh\u0000the":{"count":2,"gen":3,"kept":1}},"fired":40,"reverted":6,"generation":9}""",
            "no kept, zero count" to """{"pairs":{"teh\u0000the":{"count":0,"gen":3}},"fired":3}""",
            "missing gen" to """{"pairs":{"teh\u0000the":{"count":2}}}""",
            "garbage" to "nope",
        )
        for ((name, text) in stats) {
            val k = KotlinCorrectionStats(File(tmp.newFolder("os-k-$name"), "f.json").apply { writeText(text) })
            val r = CorrectionStats(File(tmp.newFolder("os-r-$name"), "f.json").apply { writeText(text) })
            assertEquals("stats $name: multiplier", k.confidenceMultiplier(), r.confidenceMultiplier(), 0.0)
            assertEquals("stats $name: penalty", k.penalty("teh", "the"), r.penalty("teh", "the"))
        }
        println("old files: ${memories.size} memory and ${stats.size} stats files read identically")
    }
}
