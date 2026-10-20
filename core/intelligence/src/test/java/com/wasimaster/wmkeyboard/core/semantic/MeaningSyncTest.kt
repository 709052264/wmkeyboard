package com.wasimaster.wmkeyboard.core.semantic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Indexing with a fake embedder: a text's vector is a fixed function of its
 * words, so what lands in the index and what a query finds can be checked
 * without a model.
 */
class MeaningSyncTest {

    /** Words to axes: texts sharing a word point the same way. */
    private class FakeEmbedder(private val failAfter: Int = Int.MAX_VALUE) : Embedder {
        val calls = ArrayList<List<String>>()
        override suspend fun embed(inputs: List<String>): List<FloatArray>? {
            if (calls.size >= failAfter) return null
            calls += inputs
            return inputs.map(::vectorOf)
        }
    }

    private fun item(key: String, text: String) = MeaningSync.Item(key, MeaningSync.stampOf(text), text)

    @Test
    fun firstPassEmbedsEverythingInBatches() = runBlocking {
        val index = VectorIndex(DIMS)
        val embedder = FakeEmbedder()
        val items = (1..5).map { item("k$it", "word$it") }
        val result = MeaningSync.sync(index, items, embedder, batch = 2)
        assertEquals(5, result.embedded)
        assertTrue(result.complete)
        assertEquals(listOf(2, 2, 1), embedder.calls.map { it.size })
        assertEquals(5, index.size)
    }

    @Test
    fun unchangedItemsAreNotEmbeddedAgain() = runBlocking {
        val index = VectorIndex(DIMS)
        val items = listOf(item("a", "cat sofa"), item("b", "bank loan"))
        MeaningSync.sync(index, items, FakeEmbedder())
        val again = FakeEmbedder()
        val result = MeaningSync.sync(index, items, again)
        assertEquals(0, result.embedded)
        assertTrue(again.calls.isEmpty())
    }

    @Test
    fun anEditedItemIsEmbeddedAgainAndADeletedOneDropped() = runBlocking {
        val index = VectorIndex(DIMS)
        MeaningSync.sync(index, listOf(item("a", "cat sofa"), item("b", "bank loan")), FakeEmbedder())
        val embedder = FakeEmbedder()
        val result = MeaningSync.sync(index, listOf(item("a", "cat bed")), embedder)
        assertEquals(1, result.embedded)
        assertEquals(1, result.removed)
        assertEquals(listOf(listOf("cat bed")), embedder.calls)
        assertNull(index.stampOf("b"))
        assertEquals(MeaningSync.stampOf("cat bed"), index.stampOf("a"))
    }

    @Test
    fun theBudgetLeavesTheRestForTheNextPass() = runBlocking {
        val index = VectorIndex(DIMS)
        val items = (1..10).map { item("k$it", "word$it") }
        val first = MeaningSync.sync(index, items, FakeEmbedder(), batch = 3, budget = 4)
        assertEquals(4, first.embedded)
        assertFalse(first.complete)
        // Store order is kept: the first items listed are the first embedded.
        assertNotNull(index.stampOf("k1"))
        assertNull(index.stampOf("k5"))
        val second = MeaningSync.sync(index, items, FakeEmbedder())
        assertEquals(6, second.embedded)
        assertEquals(10, index.size)
    }

    @Test
    fun aFailingEmbedderStopsThePassAndKeepsWhatItHad() = runBlocking {
        val index = VectorIndex(DIMS)
        val items = (1..6).map { item("k$it", "word$it") }
        var saves = 0
        val result = MeaningSync.sync(index, items, FakeEmbedder(failAfter = 1), batch = 2) { saves++ }
        assertEquals(2, result.embedded)
        assertFalse(result.complete)
        assertEquals(1, saves)
        assertEquals(2, index.size)
    }

    @Test
    fun aQueryFindsTheTextThatSharesItsMeaning() = runBlocking {
        val index = VectorIndex(DIMS)
        MeaningSync.sync(
            index,
            listOf(item("cat", "cat sleeps sofa"), item("bank", "bank loan meeting"), item("milk", "buy milk eggs")),
            FakeEmbedder(),
        )
        val hits = index.score(vectorOf("cat"))
        assertEquals("cat", hits.first().key)
        val narrowed = index.score(vectorOf("cat")) { it != "cat" }
        assertFalse(narrowed.any { it.key == "cat" })
    }

    @Test
    fun stampsFollowTheTextAndTheVersion() {
        assertEquals(MeaningSync.stampOf("a b"), MeaningSync.stampOf("a b"))
        assertTrue(MeaningSync.stampOf("a b") != MeaningSync.stampOf("a c"))
        assertTrue(MeaningSync.stampOf("a b", version = 1) != MeaningSync.stampOf("a b", version = 2))
    }

    companion object {
        const val DIMS = 16

        /** Each word lights one axis; a small constant keeps every vector non-zero. */
        fun vectorOf(text: String): FloatArray {
            val v = FloatArray(DIMS) { 0.05f }
            for (word in text.split(' ')) if (word.isNotEmpty()) v[Math.floorMod(word.hashCode(), DIMS)] += 1f
            return v
        }
    }
}
