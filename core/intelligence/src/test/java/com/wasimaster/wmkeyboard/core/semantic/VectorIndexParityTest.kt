package com.wasimaster.wmkeyboard.core.semantic

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import kotlin.math.abs
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's kernel against the Kotlin loop, and [VectorIndex.search]
 * against `MeaningRank.select(score(...))`, on vectors shaped like the
 * model's: a shared direction plus noise, so cosines sit in the 0.5-0.9 band
 * the tunings were read off, with exact duplicates for the ties.
 */
class VectorIndexParityTest {

    private val before = NativeEngine.forceKotlin

    @After
    fun restore() {
        NativeEngine.forceKotlin = before
    }

    private val tunings = listOf(
        MeaningRank.TEXT, MeaningRank.PICTURE, MeaningRank.PHOTO, MeaningRank.EMOJI, MeaningRank.TAG,
        MeaningRank.TEXT.copy(floor = 0.55f, smallSetBar = 0.6f, limit = 5),
        MeaningRank.TEXT.copy(floor = 0f, fromBest = 1f, aboveMedian = -1f, limit = 1000),
    )

    private fun index(r: Random, count: Int, dim: Int): VectorIndex {
        val common = FloatArray(dim) { r.nextFloat() * 2f - 1f }
        val index = VectorIndex(dim)
        var last = FloatArray(dim)
        for (i in 0 until count) {
            val v = if (i % 17 == 5) last else {
                val weight = 0.5f + r.nextFloat() * 1.5f
                FloatArray(dim) { common[it] * weight + r.nextFloat() * 2f - 1f }
            }
            index.put("item/${r.nextInt(count * 4)}", i.toLong(), v)
            last = v
        }
        return index
    }

    private fun query(r: Random, index: VectorIndex): FloatArray {
        val pick = index.keys.elementAt(r.nextInt(index.size))
        val near = index.vectorOf(pick)!!
        return EmbeddingInput.normalize(FloatArray(near.size) { near[it] + (r.nextFloat() * 2f - 1f) * 0.02f })
    }

    @Test
    fun theKernelRanksAsTheKotlinLoopDoes() {
        assertTrue(NativeEngine.loaded)
        val r = Random(21)
        for ((count, dim) in listOf(1 to 768, 7 to 768, 300 to 768, 2_000 to 768, 500 to 37, 64 to 3)) {
            val index = index(r, count, dim)
            repeat(5) {
                val q = query(r, index)
                NativeEngine.forceKotlin = true
                val kotlin = index.score(q)
                NativeEngine.forceKotlin = false
                val native = index.score(q)
                assertSameRanking("$count x $dim", kotlin, native)
                for (tuning in tunings) {
                    NativeEngine.forceKotlin = true
                    val kept = index.search(q, tuning)
                    NativeEngine.forceKotlin = false
                    assertSameRanking("$count x $dim $tuning", kept, index.search(q, tuning))
                }
            }
        }
    }

    /**
     * The same hits in the same order, each score within dot.rs's tolerance
     * (1e-5 of the sum of the absolute products, at most 1 for unit vectors).
     * Two neighbours closer than the two sums can disagree by may trade places.
     */
    private fun assertSameRanking(what: String, kotlin: List<MeaningRank.Hit>, native: List<MeaningRank.Hit>) {
        assertEquals(what, kotlin.size, native.size)
        val byKey = native.associate { it.key to it.score }
        for (hit in kotlin) assertEquals(what, hit.score, byKey[hit.key] ?: Float.NaN, 1e-5f)
        for ((k, n) in kotlin.zip(native)) {
            if (k.key != n.key) assertEquals("$what: ${k.key} / ${n.key}", k.score, n.score, 2e-5f)
        }
    }

    @Test
    fun searchKeepsWhatSelectKeepsInItsOrder() {
        val r = Random(5)
        for (native in listOf(false, true)) {
            NativeEngine.forceKotlin = !native
            for (count in listOf(0, 1, 6, 7, 8, 9, 40, 1_500)) {
                val index = index(r, count, 64)
                repeat(6) { round ->
                    val q = if (count == 0) EmbeddingInput.normalize(FloatArray(64) { 1f }) else query(r, index)
                    val accept: (String) -> Boolean = if (round % 2 == 0) { _ -> true } else { key -> key.hashCode() % 3 != 0 }
                    for (tuning in tunings) {
                        val expected = MeaningRank.select(index.score(q, accept), tuning)
                        assertEquals("native=$native $count $tuning", expected, index.search(q, tuning, accept))
                    }
                }
            }
        }
    }

    @Test
    fun theMedianIsTheSortedOne() {
        val r = Random(9)
        for (n in 1..60) {
            repeat(20) {
                val values = FloatArray(n) { (r.nextInt(12) / 10f) + if (r.nextBoolean()) 0f else r.nextFloat() }
                val sorted = values.sorted()
                val expected = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
                assertEquals(expected, medianOf(values.copyOf()), 0f)
            }
        }
    }

    @Test
    fun removingKeepsEveryOtherVectorWhereItWas() {
        val r = Random(3)
        val index = index(r, 200, 16)
        val vectors = index.keys.associateWith { index.vectorOf(it)!! }
        val gone = index.keys.filter { r.nextInt(3) == 0 }.toSet()
        for (key in gone) index.remove(key)
        index.retainAll(index.keys.filter { r.nextInt(5) != 0 }.toSet())
        assertTrue(index.keys.none { it in gone })
        for (key in index.keys) assertTrue(key, vectors.getValue(key).contentEquals(index.vectorOf(key)!!))
        val q = query(r, index)
        val scored = index.score(q)
        assertEquals(index.size, scored.size)
        for (hit in scored) {
            val v = vectors.getValue(hit.key)
            assertTrue(abs(v.indices.sumOf { (v[it] * q[it]).toDouble() }.toFloat() - hit.score) < 1e-5f)
        }
    }
}
