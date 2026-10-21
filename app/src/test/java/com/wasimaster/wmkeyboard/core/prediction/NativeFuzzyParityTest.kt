package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.eval.QwertyGeometry
import com.wasimaster.wmkeyboard.core.prediction.eval.TypoCorpus
import com.wasimaster.wmkeyboard.core.prediction.reference.KotlinBeamWorkspace
import com.wasimaster.wmkeyboard.core.prediction.reference.KotlinFuzzyBeamSearch
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FuzzyBeamSearch], which runs in Rust, gives the same answer as the Kotlin
 * walk it replaced ([KotlinFuzzyBeamSearch], kept as the reference;
 * native/RUST_NEON_PLAN.md Phase 2 step 2): every candidate, in order, with
 * bit-identical scores, edit costs, edit counts, completion lengths, tiers,
 * accent counts and slip counts.
 *
 * The queries are the prediction eval's own: the typo corpus over the
 * bundled English list (the 2,000 cases `PredictionEvalTest` scores) and its
 * clean words cut to prefixes. Each runs through the plain walk, with touch
 * evidence, with learned habits, with every edit budget, over a mapped list
 * and its in-memory twin together, with a user tier on top; T9 frames,
 * accented and half-space words, and supplementary characters each get
 * their own pass.
 */
class NativeFuzzyParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val beam = FuzzyBeamSearch()
    private val workspace = BeamWorkspace()
    private val reference = KotlinFuzzyBeamSearch()
    private val referenceWorkspace = KotlinBeamWorkspace()

    private fun entries(name: String): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/$name.txt"), File("app/dictionaries-src/$name.txt"))
            .firstOrNull { it.exists() } ?: error("$name.txt not found")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    private fun mapped(trie: PackedTrie, name: String): MappedTrie {
        val file = tmp.newFile("$name.wmdict")
        file.outputStream().use { PackedTrieCodec.write(trie, it) }
        return MappedTrie.open(file) ?: error("$name: rejected")
    }

    private var compared = 0
    private var candidates = 0

    @Suppress("LongParameterList")
    private fun check(
        what: String,
        sources: List<FuzzyBeamSearch.WalkSource>,
        typed: String,
        limit: Int,
        maxEdits: Int = FuzzyBeamSearch.defaultMaxEdits(typed.length),
        touch: FuzzyBeamSearch.TouchScoring? = null,
        habits: EditHabits = EditHabits.NONE,
        keys: KeySets? = null,
        proximity: KeyProximity = KeyProximity.QWERTY,
    ) {
        val kotlin = reference.search(sources, typed, proximity, limit, referenceWorkspace, maxEdits, touch, habits, keys)
        val rust = beam.search(sources, typed, proximity, limit, workspace, maxEdits, touch, habits, keys)
        val label = "$what '$typed' limit=$limit edits=$maxEdits"
        assertEquals("$label: words", kotlin.map { it.word }, rust.map { it.word })
        for (i in kotlin.indices) {
            val k = kotlin[i]
            val r = rust[i]
            val at = "$label #$i ${k.word}"
            assertEquals("$at score", k.score.toRawBits(), r.score.toRawBits())
            assertEquals("$at editCost", k.editCost.toRawBits(), r.editCost.toRawBits())
            assertEquals("$at edits", k.edits, r.edits)
            assertEquals("$at completedChars", k.completedChars, r.completedChars)
            assertEquals("$at tier", k.tier, r.tier)
            assertEquals("$at dictScore", k.dictScore.toRawBits(), r.dictScore.toRawBits())
            assertEquals("$at userScore", k.userScore.toRawBits(), r.userScore.toRawBits())
            assertEquals("$at accents", k.accents, r.accents)
            assertEquals("$at slips", k.slips, r.slips)
        }
        compared++
        candidates += kotlin.size
    }

    private fun dictionary(walker: TrieWalker, weight: Double = 0.0) =
        FuzzyBeamSearch.WalkSource(walker, weight, FuzzyBeamSearch.Tier.DICTIONARY)

    private fun user(walker: TrieWalker, weight: Double) =
        FuzzyBeamSearch.WalkSource(walker, weight, FuzzyBeamSearch.Tier.USER)

    /** A tap near each typed letter's key, some missing, from a fixed seed. */
    private fun taps(typed: String, random: Random): List<TouchPoint?> = typed.map { ch ->
        val key = QwertyGeometry.keyFor(ch)
        if (key == null || random.nextInt(9) == 0) {
            null
        } else {
            TouchPoint(key.x + (random.nextFloat() - 0.5f) * 0.9f, key.y + (random.nextFloat() - 0.5f) * 0.9f)
        }
    }

    private val touchModel = KeyTouchModel(QwertyGeometry.keys.associate { it.char to TouchPoint(it.x, it.y) })
    private val noSlips = KeyTouchModel(
        QwertyGeometry.keys.associate { it.char to TouchPoint(it.x, it.y) },
        slipRadius = 0.0,
    )

    @Test
    fun evalCorpusMatchesOnEveryPath() {
        val en = entries("en")
        val packed = PackedTrie.of(en)
        val mapped = mapped(packed, "en")
        val corpus = TypoCorpus()
        val cases = corpus.generate(en)
        val clean = corpus.cleanWords(en)
        val random = Random(7)
        val memory = CorrectionMemory(null)
        repeat(4) {
            memory.teach("thim", "them", "qwerty", CorrectionMemory.Kind.PAIR_AND_HABITS)
            memory.teach("wirk", "work", "qwerty", CorrectionMemory.Kind.PAIR_AND_HABITS)
            memory.teach("teh", "the", "qwerty", CorrectionMemory.Kind.PAIR_AND_HABITS)
            memory.teach("helo", "hello", "qwerty", CorrectionMemory.Kind.PAIR_AND_HABITS)
            memory.teach("thhe", "the", "qwerty", CorrectionMemory.Kind.PAIR_AND_HABITS)
        }
        val habits = memory.habitsFor("qwerty")
        assertTrue("the habit model should have learned something", habits != EditHabits.NONE)
        val userList = PackedTrie.of(en.take(400).map { it.first + "z" to 3 } + listOf("thim" to 9, "wirk" to 2))

        for ((index, case) in cases.withIndex()) {
            val typed = case.typed.lowercase()
            check("mapped", listOf(dictionary(mapped)), typed, 4)
            check("packed", listOf(dictionary(packed)), typed, 4)
            if (index % 2 == 0) {
                val frame = taps(typed, random)
                check("touch", listOf(dictionary(mapped)), typed, 4, touch = FuzzyBeamSearch.TouchScoring(touchModel, frame))
                check("touch, no slips", listOf(dictionary(mapped)), typed, 4, touch = FuzzyBeamSearch.TouchScoring(noSlips, frame))
                check(
                    "touch, habits", listOf(dictionary(mapped)), typed, 4,
                    touch = FuzzyBeamSearch.TouchScoring(touchModel, frame), habits = habits,
                )
            }
            if (index % 3 == 0) check("habits", listOf(dictionary(mapped)), typed, 4, habits = habits)
            if (index % 5 == 0) {
                val two = listOf(dictionary(mapped, -0.2), dictionary(packed, 0.3), user(userList, 6.2))
                check("three sources", two, typed, 9, maxEdits = 2)
                check("no edits", listOf(dictionary(mapped)), typed, 4, maxEdits = 0)
            }
        }
        for (word in clean) {
            for (cut in listOf(1, 2, 3, word.length)) {
                if (cut > word.length) continue
                check("prefix", listOf(dictionary(mapped)), word.substring(0, cut), 4)
            }
        }
        println("eval corpus: $compared searches, $candidates candidates identical")
    }

    @Test
    fun ambiguousKeysMatch() {
        val en = entries("en")
        val mapped = mapped(PackedTrie.of(en), "en-t9")
        val pad = mapOf(
            'a' to "abc", 'b' to "abc", 'c' to "abc", 'd' to "def", 'e' to "def", 'f' to "def",
            'g' to "ghi", 'h' to "ghi", 'i' to "ghi", 'j' to "jkl", 'k' to "jkl", 'l' to "jkl",
            'm' to "mno", 'n' to "mno", 'o' to "mno", 'p' to "pqrs", 'q' to "pqrs", 'r' to "pqrs",
            's' to "pqrs", 't' to "tuv", 'u' to "tuv", 'v' to "tuv", 'w' to "wxyz", 'x' to "wxyz",
            'y' to "wxyz", 'z' to "wxyz",
        )
        for ((word, _) in en.filter { (w, _) -> w.all { it in pad } }.take(1500)) {
            val frame = KeySets.of(word.map { pad.getValue(it) }) ?: continue
            check("t9", listOf(dictionary(mapped)), word, 4, keys = frame)
            check("t9 prefix", listOf(dictionary(mapped)), word.take(3), 4, keys = KeySets.of(word.take(3).map { pad.getValue(it) }))
        }
        // A key that carries letters a list spells in two units.
        val nukta = PackedTrie.of(listOf("क़लम" to 40, "कलम" to 90, "ड़र" to 30, "डर" to 70))
        val frame = KeySets.of(listOf("कक़", "ल", "म"))
        check("nukta", listOf(dictionary(nukta)), "कलम", 4, keys = frame, proximity = KeyProximity.QWERTY)
        println("ambiguous keys: $compared searches, $candidates candidates identical")
    }

    @Test
    fun accentsHalfSpacesAndAstralLettersMatch() {
        val words = listOf(
            "żółw" to 300, "już" to 900, "zolw" to 3, "łódź" to 200, "café" to 400, "naïve" to 90,
            "über" to 120, "señor" to 80, "می‌کنم" to 500, "میکنم" to 20, "می‌روم" to 300,
            "𐓏𐓘𐓻𐓘𐓻𐓟" to 50, "𐓏𐓘𐓻" to 70, "😀ok" to 20,
        )
        val trie = PackedTrie.of(words)
        val mapped = mapped(trie, "accents")
        for (typed in listOf("zolw", "juz", "lodz", "cafe", "naive", "uber", "senor", "میکنم", "میروم", "𐓏𐓘", "😀o", "zol", "kafe")) {
            check("accents", listOf(dictionary(mapped)), typed, 4)
            check("accents, two edits", listOf(dictionary(trie)), typed, 9, maxEdits = 2)
        }
        println("accents: $compared searches identical")
    }
}
