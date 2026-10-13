package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.abs

/**
 * Places in the field where the user has turned a correction down, and how
 * many times.
 *
 * The loop this breaks: autocorrect rewrites a word, the user deletes the
 * rewrite and types their word again, autocorrect rewrites it again, and round
 * it goes. [CorrectionStats] only partly stops it. Its in-process block is
 * stamped by the backspace undo alone, and deleting the correction letter by
 * letter or with a word-delete is not that undo — the pair simply waits in
 * [CorrectionWatch] for a verdict the user never gets to see.
 *
 * So this remembers the *spot*, not the pair: the word start a correction
 * landed at and the spelling the user typed there. Every time a correction at
 * that spot is undone or deleted counts as one decline, and once a spot has
 * collected enough of them, nothing typed there with that spelling is
 * corrected again. Only that one occurrence: the same typo anywhere else, or
 * in another field, is corrected as usual, and nothing here is persisted.
 *
 * Positions are field offsets. Deletions in front of a spot move it with the
 * text; insertions are not tracked, and [FIND_TOLERANCE] absorbs the small
 * drift a caret echo arriving a beat late leaves behind.
 */
class DeclinedSpots(private val capacity: Int = DEFAULT_CAPACITY) {

    private class Spot(var start: Int, val typed: String) {
        var declines = 0

        /**
         * End of the correction standing at this spot, or [NONE] when none is:
         * a spot only collects a decline while the rewrite it is about is
         * still in the field, and only once per rewrite.
         */
        var liveEnd = NONE
    }

    private val spots = ArrayDeque<Spot>()

    val size: Int get() = spots.size

    /** A correction of [typed] into [corrected] has just landed at [start]. */
    fun fired(start: Int, typed: String, corrected: String) {
        if (start < 0) return
        val spot = find(start, typed) ?: Spot(start, WordKey.of(typed)).also {
            spots.addLast(it)
            if (spots.size > capacity) spots.removeFirst()
        }
        spot.start = start
        spot.liveEnd = start + corrected.length
    }

    /**
     * The newest correction of [typed] still standing was undone outright —
     * by backspace or the undo chip — rather than deleted.
     */
    fun undone(typed: String) {
        val key = WordKey.of(typed)
        spots.lastOrNull { it.typed == key && it.liveEnd != NONE }?.let(::decline)
    }

    /**
     * The field lost `[start, end)`. A correction the range reaches into is
     * declined; spots behind the range move up with the text.
     */
    fun onDeleted(start: Int, end: Int) {
        if (start < 0 || end <= start || spots.isEmpty()) return
        val length = end - start
        for (spot in spots) {
            if (spot.liveEnd != NONE && start < spot.liveEnd && end > spot.start) decline(spot)
            when {
                spot.start >= end -> {
                    spot.start -= length
                    if (spot.liveEnd != NONE) spot.liveEnd -= length
                }
                // The word start itself went: it now begins where the cut did.
                spot.start > start -> spot.start = start
            }
        }
    }

    /**
     * Whether [typed] at [start] has been declined at least [threshold] times,
     * so must go in as typed. A [threshold] of 0 or less turns this off.
     */
    fun blocks(start: Int, typed: String, threshold: Int): Boolean {
        if (threshold <= 0 || start < 0) return false
        val spot = find(start, typed) ?: return false
        return spot.declines >= threshold
    }

    /** A different field, or the text was replaced wholesale. */
    fun clear() {
        spots.clear()
    }

    private fun decline(spot: Spot) {
        spot.declines++
        spot.liveEnd = NONE
    }

    private fun find(start: Int, typed: String): Spot? {
        val key = WordKey.of(typed)
        return spots.lastOrNull { it.typed == key && abs(it.start - start) <= FIND_TOLERANCE }
    }

    companion object {
        private const val NONE = -1

        /** Spots held at once; a run of loops never needs more than a few. */
        const val DEFAULT_CAPACITY = 16

        /** How far a retyped word's start may sit from the spot and still be it. */
        const val FIND_TOLERANCE = 2
    }
}
