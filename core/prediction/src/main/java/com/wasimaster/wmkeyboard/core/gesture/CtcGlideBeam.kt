package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.ChildBuffer
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.TrieWalker
import kotlin.math.ln
import kotlin.math.pow

/**
 * Glide decoding off a neural model's emissions ([SwipeModel]): a beam search
 * over the dictionary tries, time step by time step, that only ever spells
 * what a trie holds.
 *
 * A hypothesis is a trie node and whether its last step was the blank. Each
 * step it may take the blank and stay, take a child's letter and descend, or
 * take its own letter again and stay; the score is the sum of the chosen
 * log-probabilities, kept as the best path into each (node, blank) pair rather
 * than the sum over paths. A doubled letter needs no blank between its two
 * halves: the stroke shows `ll` as one key visited once, and the models were
 * trained to emit it that way.
 *
 * A finished word scores
 *
 *     path / L^lengthNorm + lengthBonus * L + freqWeight * (logWeight + ln(1 + f))
 *
 * for L letters: the path's log-probability flattened by length, so a long
 * word is not punished for having more steps to be wrong on, a bonus per
 * letter against the model's lean toward short readings, and the word's
 * frequency in the same log space [GlideBeam] scores in. The beam is pruned
 * each step on a length-aware key, `path / max(L, 1)^pruneNorm + pruneBonus * L`,
 * which keeps long prefixes alive against short ones that have merely had
 * fewer chances to go wrong. The weights are FUTO's, tuned for their encoder
 * and English decoder (`scoring.json` in the model repository), with the
 * frequency weight converted from AOSP's 0..255 scale to natural log counts.
 *
 * Written for this project from the scoring the model card and technical
 * report describe; FUTO's own decoder library is GPL and none of it is here.
 */
class CtcGlideBeam(private val scoring: Scoring = Scoring()) {

    data class Scoring(
        /** Exponent of the length normalisation on a finished word's path score. */
        val lengthNorm: Float = 0.5949f,
        /** Nats per letter for a finished word. */
        val lengthBonus: Float = 0.7271f,
        /**
         * Weight on a word's log frequency. FUTO's 0.0134 per AOSP probability
         * step, at 8.59 steps to a doubling, is 0.166 per nat of count.
         */
        val freqWeight: Float = 0.166f,
        /** Length normalisation of the pruning key. */
        val pruneNorm: Float = 0.1902f,
        /** Per-letter bonus in the pruning key. */
        val pruneBonus: Float = 1.2727f,
        /** Hypotheses kept per step and source. */
        val width: Int = 100,
    )

    // One search's state, reused across decodes; the caller serialises them.
    private var cap = 0
    private var curNode = IntArray(0)
    private var curParent = IntArray(0)
    private var curLabel = CharArray(0)
    private var curHop = CharArray(0)
    private var curBlank = BooleanArray(0)
    private var curDepth = IntArray(0)
    private var curLast = IntArray(0)
    private var curScore = FloatArray(0)
    private var curPrune = FloatArray(0)
    private var curCount = 0
    private var nextNode = IntArray(0)
    private var nextParent = IntArray(0)
    private var nextLabel = CharArray(0)
    private var nextHop = CharArray(0)
    private var nextBlank = BooleanArray(0)
    private var nextDepth = IntArray(0)
    private var nextLast = IntArray(0)
    private var nextScore = FloatArray(0)
    private var nextPrune = FloatArray(0)
    private var nextCount = 0
    private val slots = SlotMap()
    private val spelled = SpellMap()
    private val children = ChildBuffer()
    private val hopChildren = ChildBuffer()
    private var order = IntArray(0)

    /**
     * Words for [emissions] out of [sources], best first, at most [limit].
     * Scores are in this decoder's own units: comparable with one another,
     * not with [GlideBeam]'s. [GlideBeam.Candidate.shapeCost] is the path's
     * negated, length-normalised log-probability, low when the stroke read
     * cleanly as the word.
     */
    fun decode(
        emissions: SwipeEmissions,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int = 4,
        width: Int = scoring.width,
        /** How many of a dictionary's commonest words may be read; 0 for all. See [GlideBeam.Tuning.vocabularyRank]. */
        vocabularyRank: Int = 0,
    ): List<GlideBeam.Candidate> {
        if (limit <= 0 || sources.isEmpty() || emissions.steps == 0) return emptyList()
        ensure(width)
        val best = HashMap<String, GlideBeam.Candidate>()
        for (src in sources) search(src, emissions, width, vocabularyRank, best)
        return best.values.sortedWith(
            compareByDescending<GlideBeam.Candidate> { it.score }.thenBy { it.word },
        ).take(limit)
    }

    private fun search(
        src: FuzzyBeamSearch.WalkSource,
        emissions: SwipeEmissions,
        width: Int,
        vocabularyRank: Int,
        best: HashMap<String, GlideBeam.Candidate>,
    ) {
        val walker = src.walker
        val minFrequency = walker.frequencyAtRank(vocabularyRank)
        spelled.clear()
        curCount = 0
        put(walker.root, -1, NO_CHAR, NO_CHAR, false, 0, -1, 0f, cur = true)
        val lp = emissions.logProbs
        for (t in 0 until emissions.steps) {
            val row = t * SwipeEmissions.CLASSES
            val blank = lp[row + SwipeEmissions.BLANK]
            nextCount = 0
            slots.clear(width * EXPANSION)
            for (h in 0 until curCount) {
                val node = curNode[h]
                val score = curScore[h]
                val depth = curDepth[h]
                // Blank: stay, now blank-ended.
                offer(node, curParent[h], curLabel[h], curHop[h], true, depth, curLast[h], score + blank)
                // Own letter again: stay, unless a blank came between.
                val last = curLast[h]
                if (!curBlank[h] && last >= 0) {
                    offer(node, curParent[h], curLabel[h], curHop[h], false, depth, last, score + lp[row + last])
                }
                // A child's letter: descend.
                val count = walker.childrenInto(node, children)
                for (i in 0 until count) {
                    val label = children.labels[i]
                    val child = children.nodes[i]
                    if (minFrequency > 0 && walker.maxSubtree(child) < minFrequency) continue
                    val cls = classOf(label)
                    if (cls >= 0) {
                        offer(child, node, label, NO_CHAR, false, depth + 1, cls, score + lp[row + cls])
                    } else if (depth > 0 && GlideJoiners.isJoiner(label.code)) {
                        // A hyphen or apostrophe is spelled but never drawn:
                        // step over it to the letter after, in the same step.
                        val hops = walker.childrenInto(child, hopChildren)
                        for (j in 0 until hops) {
                            val hopLabel = hopChildren.labels[j]
                            val hopCls = classOf(hopLabel)
                            if (hopCls < 0) continue
                            val grandchild = hopChildren.nodes[j]
                            if (minFrequency > 0 && walker.maxSubtree(grandchild) < minFrequency) continue
                            offer(grandchild, node, hopLabel, label, false, depth + 1, hopCls, score + lp[row + hopCls])
                        }
                    }
                }
            }
            prune(width)
            if (curCount == 0) return
        }
        for (h in 0 until curCount) {
            val node = curNode[h]
            if (!walker.isWord(node) || curDepth[h] < MIN_WORD_LENGTH) continue
            val frequency = walker.frequency(node)
            if (frequency < minFrequency) continue
            val word = spelled.word(node) ?: continue
            if (src.tier == FuzzyBeamSearch.Tier.DICTIONARY && GlideJoiners.isNonWord(word)) continue
            val length = curDepth[h].toFloat()
            val path = curScore[h] / length.pow(scoring.lengthNorm)
            val score = path + scoring.lengthBonus * length +
                scoring.freqWeight * (src.logWeight + ln(1.0 + frequency)).toFloat()
            val existing = best[word]
            if (existing == null || existing.score < score) {
                best[word] = GlideBeam.Candidate(word, score.toDouble(), -path.toDouble(), src.tier)
            }
        }
    }

    /** a..z for a letter of either case, -1 for anything the model has no class for. */
    private fun classOf(label: Char): Int {
        val c = if (label in 'A'..'Z') label + ('a' - 'A') else label
        return if (c in 'a'..'z') c - 'a' else -1
    }

    private fun offer(
        node: Int, parent: Int, label: Char, hop: Char, blankEnded: Boolean,
        depth: Int, last: Int, score: Float,
    ) {
        val key = (node.toLong() shl 1) or (if (blankEnded) 1L else 0L)
        val at = slots.get(key)
        if (at >= 0) {
            if (nextScore[at] < score) {
                nextParent[at] = parent; nextLabel[at] = label; nextHop[at] = hop
                nextDepth[at] = depth; nextLast[at] = last
                nextScore[at] = score; nextPrune[at] = pruneKey(score, depth)
            }
            return
        }
        slots.put(key, nextCount)
        put(node, parent, label, hop, blankEnded, depth, last, score, cur = false)
    }

    private fun pruneKey(score: Float, depth: Int): Float =
        score / maxOf(depth, 1).toFloat().pow(scoring.pruneNorm) + scoring.pruneBonus * depth

    @Suppress("LongParameterList")
    private fun put(
        node: Int, parent: Int, label: Char, hop: Char, blankEnded: Boolean,
        depth: Int, last: Int, score: Float, cur: Boolean,
    ) {
        if (cur) {
            val i = curCount++
            curNode[i] = node; curParent[i] = parent; curLabel[i] = label; curHop[i] = hop
            curBlank[i] = blankEnded; curDepth[i] = depth; curLast[i] = last
            curScore[i] = score; curPrune[i] = pruneKey(score, depth)
        } else {
            if (nextCount >= nextNode.size) growNext()
            val i = nextCount++
            nextNode[i] = node; nextParent[i] = parent; nextLabel[i] = label; nextHop[i] = hop
            nextBlank[i] = blankEnded; nextDepth[i] = depth; nextLast[i] = last
            nextScore[i] = score; nextPrune[i] = pruneKey(score, depth)
        }
    }

    /** Keeps the [width] best of the next beam by pruning key and records how each survivor was spelled. */
    private fun prune(width: Int) {
        val n = nextCount
        if (order.size < n) order = IntArray(n * 2)
        for (i in 0 until n) order[i] = i
        val keep = minOf(n, width)
        if (n > width) select(n, width)
        for (k in 0 until keep) {
            val i = order[k]
            curNode[k] = nextNode[i]; curParent[k] = nextParent[i]; curLabel[k] = nextLabel[i]
            curHop[k] = nextHop[i]; curBlank[k] = nextBlank[i]; curDepth[k] = nextDepth[i]
            curLast[k] = nextLast[i]; curScore[k] = nextScore[i]; curPrune[k] = nextPrune[i]
            spelled.record(nextNode[i], nextParent[i], nextLabel[i], nextHop[i])
        }
        curCount = keep
    }

    /**
     * Moves the [k] highest pruning keys among the first [n] of [order] to its
     * front, in no particular order: quickselect, with no allocation.
     */
    private fun select(n: Int, k: Int) {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val pivot = nextPrune[order[(lo + hi) ushr 1]]
            var i = lo
            var j = hi
            while (i <= j) {
                while (nextPrune[order[i]] > pivot) i++
                while (nextPrune[order[j]] < pivot) j--
                if (i <= j) {
                    val tmp = order[i]; order[i] = order[j]; order[j] = tmp
                    i++; j--
                }
            }
            when {
                k - 1 <= j -> hi = j
                k - 1 >= i -> lo = i
                else -> return
            }
        }
    }

    private fun ensure(width: Int) {
        if (cap >= width) return
        cap = width
        curNode = IntArray(width); curParent = IntArray(width); curLabel = CharArray(width)
        curHop = CharArray(width); curBlank = BooleanArray(width); curDepth = IntArray(width)
        curLast = IntArray(width); curScore = FloatArray(width); curPrune = FloatArray(width)
        val next = width * EXPANSION
        nextNode = IntArray(next); nextParent = IntArray(next); nextLabel = CharArray(next)
        nextHop = CharArray(next); nextBlank = BooleanArray(next); nextDepth = IntArray(next)
        nextLast = IntArray(next); nextScore = FloatArray(next); nextPrune = FloatArray(next)
    }

    private fun growNext() {
        val size = nextNode.size * 2
        nextNode = nextNode.copyOf(size); nextParent = nextParent.copyOf(size)
        nextLabel = nextLabel.copyOf(size); nextHop = nextHop.copyOf(size)
        nextBlank = nextBlank.copyOf(size); nextDepth = nextDepth.copyOf(size)
        nextLast = nextLast.copyOf(size); nextScore = nextScore.copyOf(size)
        nextPrune = nextPrune.copyOf(size)
    }

    /** (node << 1 | blank) to its index in the next beam: open addressing, cleared by generation. */
    private class SlotMap {
        private var keys = LongArray(0)
        private var values = IntArray(0)
        private var stamps = IntArray(0)
        private var stamp = 0
        private var mask = 0
        private var load = 0

        fun clear(expected: Int) {
            var size = 64
            while (size < expected * 2) size = size shl 1
            if (size > keys.size) {
                keys = LongArray(size); values = IntArray(size); stamps = IntArray(size)
                mask = size - 1
                stamp = 0
            }
            stamp++
            load = 0
            if (stamp == Int.MAX_VALUE) {
                stamps.fill(0)
                stamp = 1
            }
        }

        fun get(key: Long): Int {
            var i = hash(key) and mask
            while (stamps[i] == stamp) {
                if (keys[i] == key) return values[i]
                i = (i + 1) and mask
            }
            return -1
        }

        fun put(key: Long, value: Int) {
            var i = hash(key) and mask
            while (stamps[i] == stamp) {
                if (keys[i] == key) { values[i] = value; return }
                i = (i + 1) and mask
            }
            if (load++ * 2 > keys.size) grow()
            i = hash(key) and mask
            while (stamps[i] == stamp) i = (i + 1) and mask
            keys[i] = key; values[i] = value; stamps[i] = stamp
        }

        private fun grow() {
            val oldKeys = keys
            val oldValues = values
            val oldStamps = stamps
            val live = stamp
            keys = LongArray(oldKeys.size * 2); values = IntArray(oldKeys.size * 2); stamps = IntArray(oldKeys.size * 2)
            mask = keys.size - 1
            stamp = 1
            load = 0
            for (i in oldKeys.indices) if (oldStamps[i] == live) put(oldKeys[i], oldValues[i])
        }

        private fun hash(key: Long): Int {
            var x = key * -0x61c8864680b583ebL
            x = x xor (x ushr 29)
            return x.toInt()
        }
    }

    /**
     * How each trie node a surviving hypothesis reached was spelled from its
     * parent: one letter, or a joiner and then a letter. A trie has no parent
     * pointers, and building every prefix as a string would allocate on every
     * expansion; this records only the survivors and spells a word once, at
     * the end.
     */
    private class SpellMap {
        private val parent = HashMap<Int, Long>()

        fun clear() = parent.clear()

        fun record(node: Int, from: Int, label: Char, hop: Char) {
            if (from < 0 || parent.containsKey(node)) return
            parent[node] = (from.toLong() shl 32) or (hop.code.toLong() shl 16) or label.code.toLong()
        }

        fun word(node: Int): String? {
            val out = StringBuilder()
            var at = node
            var guard = 0
            while (true) {
                val entry = parent[at] ?: break
                out.append((entry and 0xFFFF).toInt().toChar())
                val hop = ((entry ushr 16) and 0xFFFF).toInt()
                if (hop != 0) out.append(hop.toChar())
                at = (entry ushr 32).toInt()
                if (++guard > MAX_WORD) return null
            }
            return if (out.isEmpty()) null else out.reverse().toString()
        }
    }

    private companion object {
        const val NO_CHAR = '\u0000'
        /** Next-beam slots per kept hypothesis before it grows. */
        const val EXPANSION = 16
        /** As [GlideBeam]: a stroke never reads as a single letter. */
        const val MIN_WORD_LENGTH = 2
        const val MAX_WORD = 64
    }
}
