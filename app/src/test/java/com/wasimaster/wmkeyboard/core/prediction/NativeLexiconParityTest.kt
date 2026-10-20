package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.reference.KotlinUserLexicon
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Rust user lexicon behind [UserLexicon] behaves as the Kotlin one did
 * ([KotlinUserLexicon], kept as the reference), and reads and writes the same
 * file (native/RUST_NEON_PLAN.md step 3).
 *
 * Random sequences of every operation the keyboard and the personal
 * dictionary screen perform run on both stores, and after each step every
 * question either can answer is asked of both: counts, capitals, languages,
 * ages, pins, every n-gram store, completions, the whole trie node by node,
 * the mutation counter. The files they save are compared as JSON values,
 * then each store reads the other's file. Old files (both skip-store
 * namings, files from before each additive field, junk the gate came too late
 * for, the old 200-count marker) load into both with the same result.
 */
class NativeLexiconParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private val vocabulary = listOf(
        "the", "The", "THE", "boston", "Boston", "keyboard", "Keyboard", "i", "I", "a", "don't", "Don't",
        "mp3", "b2b", "café", "café", "Café", "ΟΔΟΣ", "οδος", "İstanbul", "istanbul", "আমি", "তুমি",
        "ami", "tumi", "x", "abc!", "!!", "1234", "naïve", "naive", "self-made", "self-", "  padded  ",
        "", " ", "a".repeat(32), "b".repeat(33), "Wmk", "WMK", "wmk", "o'clock", "rock'n'roll", "e-mail",
        "日本", "ß", "SS", "straße", "STRASSE", "ŉ", "ﬀ", "Σ", "σς", "ǅ", "ǆ", "Ǆ", "x‍y", "z‌",
    )

    private fun words(random: Random): String =
        if (random.nextInt(4) == 0) "w${random.nextInt(60)}" else vocabulary[random.nextInt(vocabulary.size)]

    /** Every answer a store gives about [sample], as one comparable string list. */
    private fun answers(
        frequencyOf: (String) -> Int, contains: (String) -> Boolean, isEstablished: (String, Int) -> Boolean,
        languageOf: (String) -> String?, displayOf: (String) -> String?, isAddedByHand: (String) -> Boolean,
        addedGeneration: (String) -> Long?, isCasePinned: (String) -> Boolean,
        nextWords: (String, Int) -> List<String>, nextWordsAfter: (String, String, Int) -> List<String>,
        trigramCount: (String, String, String) -> Int, bigramCount: (String, String) -> Int,
        skip1: (String, String) -> Int, skip2: (String, String) -> Int,
        skip1Followers: (String, Int) -> List<String>, skip2Followers: (String, Int) -> List<String>,
        followerCounts: (String) -> Map<String, Int>, complete: (String, Int) -> List<Suggestion>,
        wordCount: () -> Int, allWords: () -> List<Pair<String, Int>>, mutations: () -> Long,
        walkers: () -> List<TrieWalker>, sample: List<String>,
    ): List<String> {
        val out = ArrayList<String>()
        out += "count=${wordCount()} mutations=${mutations()}"
        out += "all=${allWords()}"
        for (w in sample) {
            out += "$w: f=${frequencyOf(w)} c=${contains(w)} e1=${isEstablished(w, 1)} e3=${isEstablished(w, 3)} " +
                "lang=${languageOf(w)} disp=${displayOf(w)} hand=${isAddedByHand(w)} gen=${addedGeneration(w)} " +
                "pin=${isCasePinned(w)}"
            out += "$w: next=${nextWords(w, 5)} next40=${nextWords(w, 40)} s1=${skip1Followers(w, 8)} s2=${skip2Followers(w, 8)}"
            out += "$w: fc=${followerCounts(w).toSortedMap()} comp=${complete(w.take(1), 10)} comp2=${complete(w.take(2), 3)}"
            for (v in sample.take(6)) {
                out += "$w>$v: b=${bigramCount(w, v)} s1=${skip1(w, v)} s2=${skip2(w, v)} " +
                    "after=${nextWordsAfter(w, v, 6)} t=${trigramCount(w, v, sample[0])}"
            }
        }
        out += "trie=${dump(walkers().single())}"
        return out
    }

    /** The whole trie, depth first in child order: path, frequency, bound. */
    private fun dump(walker: TrieWalker): String {
        val sb = StringBuilder()
        val buffer = ChildBuffer()
        fun visit(node: Int, path: String) {
            sb.append(path).append(':').append(walker.frequency(node)).append('/').append(walker.maxSubtree(node))
                .append(if (walker.isWord(node)) "w" else "").append(' ')
            val count = walker.childrenInto(node, buffer)
            val labels = buffer.labels.copyOf(count)
            val nodes = buffer.nodes.copyOf(count)
            for (i in 0 until count) visit(nodes[i], path + labels[i])
        }
        visit(walker.root, "")
        return sb.toString()
    }

    private fun answersOf(lex: UserLexicon, sample: List<String>) = answers(
        lex::frequencyOf, lex::contains, lex::isEstablished, lex::languageOf, lex::displayOf, lex::isAddedByHand,
        lex::addedGeneration, lex::isCasePinned, lex::nextWords, lex::nextWordsAfter, lex::trigramCount,
        lex::bigramCount, lex::skip1gramCount, lex::skip2gramCount, lex::skip1Followers, lex::skip2Followers,
        lex::followerCounts, lex::complete, lex::wordCount, lex::allWords, lex::mutationCount, lex::walkers, sample,
    )

    private fun answersOf(lex: KotlinUserLexicon, sample: List<String>) = answers(
        lex::frequencyOf, lex::contains, lex::isEstablished, lex::languageOf, lex::displayOf, lex::isAddedByHand,
        lex::addedGeneration, lex::isCasePinned, lex::nextWords, lex::nextWordsAfter, lex::trigramCount,
        lex::bigramCount, lex::skip1gramCount, lex::skip2gramCount, lex::skip1Followers, lex::skip2Followers,
        lex::followerCounts, lex::complete, lex::wordCount, lex::allWords, lex::mutationCount, lex::walkers, sample,
    )

    private fun assertSame(what: String, kotlin: KotlinUserLexicon, rust: UserLexicon, sample: List<String>) {
        val k = answersOf(kotlin, sample)
        val r = answersOf(rust, sample)
        for (i in k.indices) assertEquals("$what: line $i", k[i], r.getOrNull(i))
        assertEquals("$what: answers", k.size, r.size)
    }

    private fun parsed(file: File): JsonElement = json.parseToJsonElement(file.readText())

    /**
     * The two files as JSON values, with the first difference named on
     * failure. The two set-valued fields are compared as sets: Kotlin wrote
     * them in hash order, the Rust store writes them sorted.
     */
    private fun assertSameFile(what: String, kotlin: File, rust: File) {
        fun canonical(e: JsonElement): JsonElement = JsonObject(
            (e as JsonObject).mapValues { (field, value) ->
                if (field in SET_FIELDS && value is JsonArray) JsonArray(value.sortedBy { it.toString() }) else value
            },
        )
        val k = canonical(parsed(kotlin))
        val r = canonical(parsed(rust))
        if (k == r) return
        val ko = k as JsonObject
        val ro = r as JsonObject
        for (field in (ko.keys + ro.keys).toSortedSet()) {
            val kv = ko[field]
            val rv = ro[field]
            if (kv == rv) continue
            if (kv is JsonObject && rv is JsonObject) {
                for (key in (kv.keys + rv.keys).toSortedSet()) {
                    if (kv[key] != rv[key]) {
                        assertEquals("$what: $field[$key]", kv[key].toString(), rv[key].toString())
                    }
                }
            }
            assertEquals("$what: $field", kv.toString().take(400), rv.toString().take(400))
        }
    }

    @Test
    fun randomOperationsAgreeStepByStep() {
        var steps = 0
        for (seed in 1..12) {
            val random = Random(seed)
            val kFile = File(tmp.newFolder("k$seed"), "user_lexicon.json")
            val rFile = File(tmp.newFolder("r$seed"), "user_lexicon.json")
            val kotlin = KotlinUserLexicon(kFile)
            val rust = UserLexicon(rFile)
            repeat(260) { step ->
                val a = words(random)
                val b = words(random)
                val c = words(random)
                when (random.nextInt(20)) {
                    0, 1, 2, 3 -> {
                        val count = random.nextInt(1, 4)
                        val lang = listOf("", "en", "bn_rom", " ").random(random)
                        val evidence = random.nextBoolean()
                        val listed = random.nextInt(5) == 0
                        assertEquals(
                            "learnWord($a)",
                            kotlin.learnWord(a, count, lang, evidence, listed),
                            rust.learnWord(a, count, lang, evidence, listed),
                        )
                    }
                    4 -> {
                        val boost = random.nextInt(0, 3)
                        val evidence = random.nextBoolean()
                        val pin = random.nextBoolean()
                        kotlin.addWord(a, boost, evidence, pin)
                        rust.addWord(a, boost, evidence, pin)
                    }
                    5 -> assertEquals("rename($a, $b)", kotlin.rename(a, b), rust.rename(a, b))
                    6 -> {
                        val n = random.nextInt(-2, 300)
                        assertEquals("setCount($a)", kotlin.setCount(a, n), rust.setCount(a, n))
                    }
                    7 -> {
                        val pin = random.nextBoolean()
                        assertEquals("pinCase($a)", kotlin.pinCase(a, pin), rust.pinCase(a, pin))
                    }
                    8, 9, 10 -> {
                        // Many followers on few heads, so the cap of 32 is hit.
                        val head = vocabulary[random.nextInt(6)]
                        kotlin.learnBigram(head, b)
                        rust.learnBigram(head, b)
                        kotlin.learnBigram(a, b)
                        rust.learnBigram(a, b)
                    }
                    11 -> {
                        kotlin.learnTrigram(a, b, c)
                        rust.learnTrigram(a, b, c)
                    }
                    12 -> {
                        kotlin.learnSkip1gram(a, c)
                        rust.learnSkip1gram(a, c)
                    }
                    13 -> {
                        kotlin.learnSkip2gram(a, c)
                        rust.learnSkip2gram(a, c)
                    }
                    14 -> if (random.nextInt(4) == 0) {
                        kotlin.forget(a)
                        rust.forget(a)
                    }
                    15 -> if (random.nextInt(6) == 0) {
                        val victims = listOf(a, b, c)
                        kotlin.forgetAll(victims)
                        rust.forgetAll(victims)
                    }
                    16, 17 -> {
                        kotlin.save()
                        rust.save()
                        if (kFile.exists() || rFile.exists()) {
                            assertSameFile("seed $seed step $step: the saved files", kFile, rFile)
                        }
                    }
                    18 -> if (random.nextInt(5) == 0) {
                        kotlin.save()
                        rust.save()
                        kotlin.reload()
                        rust.reload()
                    }
                    19 -> if (random.nextInt(40) == 0) {
                        kotlin.clear()
                        rust.clear()
                    }
                }
                steps++
                if (step % 13 == 0) {
                    assertSame("seed $seed step $step", kotlin, rust, listOf(a, b, c) + vocabulary.take(12))
                }
            }
            kotlin.save()
            rust.save()
            assertSame("seed $seed end", kotlin, rust, vocabulary)
            // Each reads the other's file.
            assertSame("seed $seed crossed", KotlinUserLexicon(rFile), UserLexicon(kFile), vocabulary)
        }
        println("random operations: $steps steps on 12 seeds, every answer identical")
    }

    @Test
    fun compactionEvictsTheSameWords() {
        val kFile = File(tmp.newFolder("kc"), "user_lexicon.json")
        val rFile = File(tmp.newFolder("rc"), "user_lexicon.json")
        val kotlin = KotlinUserLexicon(kFile)
        val rust = UserLexicon(rFile)
        val random = Random(99)
        // Past the 10,000-word cap, with ages spread over saves, some words
        // added by hand and many ties, then past the head caps too.
        for (round in 0 until 12) {
            for (i in 0 until 1000) {
                val word = "w${round}x${random.nextInt(3000)}"
                val count = random.nextInt(1, 4)
                kotlin.learnWord(word, count)
                rust.learnWord(word, count)
                if (i % 97 == 0) {
                    kotlin.addWord(word)
                    rust.addWord(word)
                }
                if (i % 2 == 0) {
                    val next = "n${random.nextInt(20)}"
                    kotlin.learnBigram(word, next)
                    rust.learnBigram(word, next)
                    kotlin.learnTrigram(word, next, "z")
                    rust.learnTrigram(word, next, "z")
                    kotlin.learnSkip1gram(word, next)
                    rust.learnSkip1gram(word, next)
                    kotlin.learnSkip2gram(word, next)
                    rust.learnSkip2gram(word, next)
                }
            }
            kotlin.save()
            rust.save()
            assertSameFile("round $round: the saved files", kFile, rFile)
        }
        assertTrue("compaction should have run", kotlin.wordCount() < 12000)
        assertSame("after compaction", kotlin, rust, listOf("w0x1", "w11x5", "n3", "z") + kotlin.allWords().take(20).map { it.first })
        println("compaction: ${kotlin.wordCount()} words kept, files identical")
    }

    /** Old files, each read by both stores. */
    @Test
    fun existingUserFilesMigrateIdentically() {
        val files = mapOf(
            // The first shipped skip naming: "skip2grams" held the 1-skip store.
            "schema 0" to """{"words":{"ami":5,"tumi":3,"bhalo":2},"bigrams":{"ami":{"tumi":4}},"generation":12,
                "skip2grams":{"ami":{"bhalo":2}},"skip3grams":{"tumi":{"ami":1}}}""",
            // Before wordGen, wordLang, case memory, pins, hand-added marks and ages.
            "words only" to """{"words":{"hello":4,"world":1,"Hello":2}}""",
            // Junk the gate (#185) came too late for, and the old 200 marker.
            "junk" to """{"words":{"ok":3,"bad!":2,"kept!":200,"hand!":1,"x":1},"addedByHand":["hand!"],
                "bigrams":{"ok":{"bad!":1,"ok":2},"bad!":{"ok":1}},
                "trigrams":{"ok\u0000bad!":{"ok":1},"ok\u0000ok":{"bad!":1,"ok":3}}}""",
            // Case memory, including a spelling that no longer folds to its key.
            "case" to """{"words":{"boston":3,"wmk":1,"paris":2},"wordCase":{"boston":"Boston","wmk":"WMK","paris":"London"},
                "caseVotes":{"boston":12,"wmk":0,"paris":3},"casePinned":["wmk","gone"],"wordLang":{"boston":"en"},
                "wordGen":{"boston":4},"wordBorn":{"wmk":1},"generation":9,"skipSchema":1}""",
            // Unknown keys, and an unpaired surrogate escape.
            "odd" to """{"words":{"a\ud83d":1,"😀x":2,"fine":1},"future":{"x":[1,2,3]},"skipSchema":1}""",
            // Not a snapshot at all, and a wrong type: both load nothing.
            "broken" to """{"words":[1,2,3]}""",
            "garbage" to "not json",
        )
        for ((name, text) in files) {
            val kFile = File(tmp.newFolder("mk-$name"), "user_lexicon.json").apply { writeText(text) }
            val rFile = File(tmp.newFolder("mr-$name"), "user_lexicon.json").apply { writeText(text) }
            val kotlin = KotlinUserLexicon(kFile)
            val rust = UserLexicon(rFile)
            val sample = listOf("ami", "tumi", "bhalo", "hello", "world", "ok", "bad!", "kept!", "hand!", "x",
                "boston", "wmk", "paris", "a�", "😀x", "fine")
            assertSame("$name: loaded", kotlin, rust, sample)
            // A save after the migration writes the same file.
            kotlin.learnWord("fresh")
            rust.learnWord("fresh")
            kotlin.save()
            rust.save()
            if (kFile.readText() != text || rFile.readText() != text) {
                assertSameFile("$name: rewritten", kFile, rFile)
            }
            assertSame("$name: crossed", KotlinUserLexicon(rFile), UserLexicon(kFile), sample + "fresh")
        }
        println("migration: ${files.size} old files read identically")
    }

    private companion object {
        val SET_FIELDS = setOf("casePinned", "addedByHand")
    }
}
