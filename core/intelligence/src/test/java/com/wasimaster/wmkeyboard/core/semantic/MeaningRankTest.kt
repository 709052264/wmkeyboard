package com.wasimaster.wmkeyboard.core.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers are the model's own, from the checks in the data repository's models/README.md. */
class MeaningRankTest {

    private fun hits(vararg scores: Float) =
        scores.mapIndexed { k, s -> MeaningRank.Hit("k$k", s) }.sortedByDescending { it.score }

    @Test
    fun aSmallSetUsesTheFixedBar() {
        // "kitten" against five notes: the two cat notes, and nothing else.
        val found = MeaningRank.select(hits(0.73f, 0.56f, 0.64f, 0.72f, 0.59f), MeaningRank.TEXT)
        assertEquals(setOf("k0", "k3"), found.map { it.key }.toSet())
    }

    @Test
    fun aSmallSetWithNothingRelatedFindsNothing() {
        // "birthday cake" against seven photos, none of a cake.
        val found = MeaningRank.select(hits(0.57f, 0.56f, 0.5f, 0.56f, 0.55f, 0.53f, 0.55f), MeaningRank.PICTURE)
        assertTrue(found.isEmpty())
    }

    @Test
    fun aLargeSetUsesTheMedian() {
        // A crowd around 0.65 and two that stand out.
        val crowd = FloatArray(20) { 0.6f + it * 0.005f }
        val found = MeaningRank.select(hits(0.84f, 0.81f, *crowd), MeaningRank.EMOJI)
        assertEquals(listOf("k0", "k1"), found.map { it.key })
    }

    @Test
    fun hitsFarBelowTheBestAreDropped() {
        val found = MeaningRank.select(hits(0.9f, 0.8f, 0.75f), MeaningRank.TEXT)
        assertEquals(listOf("k0"), found.map { it.key })
    }

    @Test
    fun theLimitHolds() {
        val found = MeaningRank.select(hits(*FloatArray(5) { 0.9f }, *FloatArray(10) { 0.6f }), MeaningRank.TAG)
        assertEquals(3, found.size)
    }

    @Test
    fun aSetWhereNothingStandsOutFindsNothing() {
        assertTrue(MeaningRank.select(hits(*FloatArray(10) { 0.9f }), MeaningRank.TAG).isEmpty())
    }

    @Test
    fun mergeKeepsKeywordsFirstAndListsEachOnce() {
        val merged = MeaningRank.merge(listOf("a", "b"), listOf("c", "a", "d", "gone")) { it != "gone" }
        assertEquals(listOf("a", "b", "c", "d"), merged)
    }

    @Test
    fun nothingScoredFindsNothing() {
        assertTrue(MeaningRank.select(emptyList(), MeaningRank.TEXT).isEmpty())
    }
}
