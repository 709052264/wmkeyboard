package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.GlidePreviewSteadiness
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The steadiness gate's rules, and the one property that matters more than any
 * of them: what a lift types is what the screen was showing.
 */
class GlidePreviewGateTest {

    private val light = GlidePreviewSteadiness.LIGHT

    /** A reading: words best first, with scores that fall by [gap] a place. */
    private fun scores(vararg values: Double) = values.toList()

    @Test
    fun theFirstReadingIsPublishedAsItIs() {
        val gate = GlidePreviewGate()
        assertEquals(0, gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L))
        assertEquals("hello", gate.shown)
    }

    @Test
    fun aNarrowChallengerDoesNotTakeTheScreen() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L)
        // "help" now leads, but only by less than the margin, and the hold has
        // long passed — so the screen keeps "hello".
        val at = gate.steady(listOf("help", "hello"), scores(0.0, -0.3), light, 1_000L)
        assertEquals(1, at)
        assertEquals("hello", gate.shown)
    }

    @Test
    fun aClearChallengerTakesTheScreen() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L)
        val at = gate.steady(listOf("help", "hello"), scores(0.0, -4.0), light, 1_000L)
        assertEquals(0, at)
        assertEquals("help", gate.shown)
    }

    @Test
    fun aWordKeepsTheScreenForItsHoldEvenAgainstAClearChallenger() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L)
        val at = gate.steady(listOf("help", "hello"), scores(0.0, -9.0), light, light.holdMs - 1L)
        assertEquals("held a beaten word past its hold", 1, at)
        assertEquals("hello", gate.shown)
    }

    @Test
    fun aWordThatLeavesTheReadingGoesAtOnce() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L)
        // Inside the hold window, and it still goes: it is not a reading of
        // this stroke any more.
        val at = gate.steady(listOf("held", "helm"), scores(0.0, -1.0), light, 1L)
        assertEquals(0, at)
        assertEquals("held", gate.shown)
    }

    @Test
    fun offPublishesEveryReading() {
        val gate = GlidePreviewGate()
        val off = GlidePreviewSteadiness.OFF
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), off, 0L)
        assertEquals(0, gate.steady(listOf("help", "hello"), scores(0.0, -0.1), off, 1L))
        assertEquals("help", gate.shown)
    }

    /**
     * The property the whole feature turns on: a steadier preview must never
     * mean typing a word the user was not shown.
     */
    @Test
    fun aLiftTypesTheWordOnScreen() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L)
        gate.steady(listOf("help", "hello"), scores(0.0, -0.3), light, 1_000L)
        assertEquals("hello", gate.shown)
        assertEquals(
            "the lift typed something other than the word on screen",
            "hello",
            gate.commit(listOf("help", "hello"), scores(0.0, -0.3), light),
        )
    }

    @Test
    fun aLiftAfterTheResetStillTypesTheWordThatWasOnScreen() {
        // #816: the lift resets the gate before the finished stroke decodes,
        // so the word on screen is read first and handed to commit.
        val gate = GlidePreviewGate()
        gate.steady(listOf("her", "here"), scores(0.0, -2.0), light, 0L)
        gate.steady(listOf("here", "her"), scores(0.0, -0.3), light, 1_000L)
        val shownAtLift = gate.shown
        gate.reset()
        assertEquals("her", gate.commit(listOf("here", "herd", "her"), scores(0.0, -0.1, -0.3), light, shownAtLift))
    }

    @Test
    fun aSideWordKeepsItsSlotWhileItHoldsUp() {
        // #849: the slots beside the centre one are held like the leader.
        val gate = GlidePreviewGate()
        gate.steadySides(listOf("some", "same", "sane"), scores(0.0, -1.0, -1.5), light, 0L)
        val next = gate.steadySides(listOf("some", "sane", "same"), scores(0.0, -1.2, -1.3), light, 1_000L)
        assertEquals(listOf("some", "same", "sane"), next)
    }

    @Test
    fun aSideWordGoneFromTheReadingLeavesAtOnce() {
        val gate = GlidePreviewGate()
        gate.steadySides(listOf("some", "same", "sane"), scores(0.0, -1.0, -1.5), light, 0L)
        val next = gate.steadySides(listOf("some", "sale", "sane"), scores(0.0, -1.0, -1.5), light, 10L)
        assertEquals(listOf("some", "sale", "sane"), next)
    }

    @Test
    fun aClearlyBetterChallengerTakesTheSlotOnceTheHoldIsOver() {
        val gate = GlidePreviewGate()
        gate.steadySides(listOf("some", "same", "sane"), scores(0.0, -1.0, -1.5), light, 0L)
        val next = gate.steadySides(listOf("some", "sane", "same"), scores(0.0, -1.0, -9.0), light, 1_000L)
        assertEquals(listOf("some", "sane", "same"), next)
    }

    @Test
    fun aLiftTakesTheLeaderOnceTheShownWordIsClearlyBeaten() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L)
        // Held on screen only because it was inside its hold window...
        gate.steady(listOf("help", "hello"), scores(0.0, -9.0), light, 1L)
        assertEquals("hello", gate.shown)
        // ...but the finished stroke is emphatic, so the lift takes its leader.
        assertEquals(
            "help",
            gate.commit(listOf("help", "hello"), scores(0.0, -9.0), light),
        )
    }

    @Test
    fun aLiftTakesTheLeaderWhenTheShownWordIsGone() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello"), scores(0.0), light, 0L)
        assertEquals("held", gate.commit(listOf("held", "helm"), scores(0.0, -1.0), light))
    }

    /**
     * Issue #121, stated at the gate rather than at the decoder.
     *
     * A word the reading in front of the gate does not hold cannot win the
     * lift, and that rule is right: the stroke has stopped meaning it. What was
     * wrong was handing the lift a reading the shown word could never be in. A
     * guess only ever comes out of a decode that was asked for one, so a lift
     * decoded without the look-ahead met a promised "dictionary" with a list
     * holding "dict" alone, and typed "dict".
     */
    @Test
    fun aGuessSurvivesTheLiftOnlyWhenTheLiftWasAskedForGuessesToo() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("dictionary", "dict"), scores(0.0, -2.0), light, 0L)
        assertEquals("dictionary", gate.shown)
        // The lift as it used to decode: no look-ahead, so no "dictionary".
        assertEquals("dict", gate.commit(listOf("dict"), scores(0.0), light))
        // The lift as it decodes now: the candidates the preview ranked.
        assertEquals(
            "dictionary",
            gate.commit(listOf("dict", "dictionary"), scores(0.0, -0.3), light),
        )
    }

    @Test
    fun aResetStopsTheNextStrokeInheritingThisOne() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello", "help"), scores(0.0, -2.0), light, 0L)
        gate.reset()
        assertEquals(null, gate.shown)
        // A fresh stroke's first reading is published as it is, not judged
        // against the last stroke's word.
        assertEquals(0, gate.steady(listOf("help", "hello"), scores(0.0, -0.1), light, 5L))
        assertEquals("help", gate.shown)
    }

    @Test
    fun anEmptyReadingChangesNothing() {
        val gate = GlidePreviewGate()
        gate.steady(listOf("hello"), scores(0.0), light, 0L)
        assertEquals(0, gate.steady(emptyList(), emptyList(), light, 1_000L))
        assertEquals("hello", gate.shown)
        assertEquals(null, gate.commit(emptyList(), emptyList(), light))
    }

    /** Every tier holds a near-tie and yields to a rout. */
    @Test
    fun everyTierHoldsANarrowChallengerAndYieldsToAClearOne() {
        for (tier in GlidePreviewSteadiness.entries) {
            if (tier == GlidePreviewSteadiness.OFF) continue
            val gate = GlidePreviewGate()
            gate.steady(listOf("hello", "help"), scores(0.0, -2.0), tier, 0L)
            val narrow = tier.margin / 2
            assertEquals(
                "$tier let a challenger $narrow nats ahead take the screen",
                1,
                gate.steady(listOf("help", "hello"), scores(0.0, -narrow), tier, 10_000L),
            )
            assertEquals(
                "$tier held against a challenger ${tier.margin * 4} nats ahead",
                0,
                gate.steady(
                    listOf("help", "hello"), scores(0.0, -tier.margin * 4), tier, 20_000L,
                ),
            )
        }
    }
}
