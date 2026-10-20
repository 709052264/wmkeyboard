package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.ChildBuffer
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.NativeFuzzySearch
import com.wasimaster.wmkeyboard.core.prediction.PredictionNative

/**
 * [GlideBeam] in Rust (`wm-engine`'s `glide`), when every trie it decodes
 * against has a Rust twin, the same test [NativeFuzzySearch] makes.
 *
 * The user's learned shapes stay in Kotlin, in [GlideShapeSource], so a
 * decode that reads them is two crossings on one thread: the walk, which
 * says which words the shape channel will score, then the finish with those
 * words' shapes and, when seeding is on, the words the store finds near the
 * stroke. Without them it is one.
 */
internal object NativeGlide {

    private val NO_SHAPES = emptyArray<ByteArray>()

    /** [tuning] in the order `glide.rs` reads it. */
    fun tuningArray(tuning: GlideBeam.Tuning): DoubleArray = with(tuning) {
        doubleArrayOf(
            sigma.toDouble(), maxPointCost.toDouble(), gapWeight.toDouble(), gapWindow.toDouble(),
            shapeWeight, repeatCost.toDouble(), unloopedRepeat.toDouble(), dwellFull.toDouble(),
            unclaimedDwell.toDouble(), loopExtent.toDouble(), loopMinArc.toDouble(), loopRadius.toDouble(),
            wiggleExtent.toDouble(), wiggleWeight.toDouble(), loopExclusion.toDouble(), unclaimedLoop.toDouble(),
            learnedShapeGain, if (shapeSeeding) 1.0 else 0.0, shapeChannel, startRadius.toDouble(),
            endRadius.toDouble(), nearRadius.toDouble(), vocabularyRank.toDouble(), lookAheadCost,
            lookAheadBudget.toDouble(), joinerCost.toDouble(),
        )
    }

    private class Path(path: List<GesturePoint>) {
        val xs = FloatArray(path.size) { path[it].x }
        val ys = FloatArray(path.size) { path[it].y }
        val ts = LongArray(path.size) { path[it].t }
    }

    /**
     * [GlideBeam.decode] natively, or null when a source has no twin and the
     * caller should decode in Kotlin.
     */
    @Suppress("LongParameterList", "ReturnCount")
    fun decode(
        tuning: GlideBeam.Tuning,
        tuningArray: DoubleArray,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int,
        shapes: GlideShapeSource?,
        lookAhead: Int,
    ): List<GlideBeam.Candidate>? {
        val handles = LongArray(sources.size)
        val kinds = IntArray(sources.size)
        val weights = DoubleArray(sources.size)
        val tiers = IntArray(sources.size)
        val floors = IntArray(sources.size)
        var count = 0
        val scratch = ChildBuffer()
        for (src in sources) {
            val walker = src.walker
            val handle = NativeFuzzySearch.handleOf(walker, scratch)
            if (handle == NativeFuzzySearch.SKIP) continue
            if (handle == 0L) return null
            handles[count] = handle
            kinds[count] = NativeFuzzySearch.kindOf(walker)
            weights[count] = src.logWeight
            tiers[count] = if (src.tier == FuzzyBeamSearch.Tier.USER) 1 else 0
            floors[count] = walker.frequencyAtRank(tuning.vocabularyRank)
            count++
        }
        val learned = shapes != null && tuning.learnedShapeGain > 0.0 && tuning.shapeChannel > 0.0
        val seeding = shapes != null && tuning.shapeSeeding && tuning.learnedShapeGain > 0.0
        val stroke = Path(path)
        val walked = PredictionNative.glideDecode(
            tuningArray, stroke.xs, stroke.ys, stroke.ts, keys.nativeHandle, keyWidth,
            handles.copyOf(count), kinds.copyOf(count), weights.copyOf(count), tiers.copyOf(count),
            floors.copyOf(count), limit, lookAhead, if (learned || seeding) 1 else 0,
        ) ?: return emptyList()
        if (!learned && !seeding) return candidates(walked)
        @Suppress("UNCHECKED_CAST")
        val words = walked[0] as Array<String>
        val ints = walked[2] as IntArray
        val readCount = ints[ints.size - 1]
        val stored = Array(words.size) { i ->
            if (learned && i < readCount) shapes!!.shapesOf(words[i]).toTypedArray() else NO_SHAPES
        }
        val seeds = if (seeding) {
            shapes!!.wordsNear(walked[3] as ByteArray, radius = SEED_RADIUS, limit = limit).toTypedArray()
        } else {
            null
        }
        return candidates(PredictionNative.glideFinish(words, stored, seeds))
    }

    private fun candidates(result: Array<Any>): List<GlideBeam.Candidate> {
        @Suppress("UNCHECKED_CAST")
        val words = result[0] as Array<String>
        val doubles = result[1] as DoubleArray
        val ints = result[2] as IntArray
        return List(words.size) { i ->
            GlideBeam.Candidate(
                word = words[i],
                score = doubles[2 * i],
                shapeCost = doubles[2 * i + 1],
                tier = if (ints[2 * i] == 1) FuzzyBeamSearch.Tier.USER else FuzzyBeamSearch.Tier.DICTIONARY,
                ahead = ints[2 * i + 1],
            )
        }
    }

    /** [GlideBeam.align] natively. */
    @Suppress("LongParameterList")
    fun align(
        tuningArray: DoubleArray,
        word: String,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
    ): GlideBeam.Alignment? {
        val stroke = Path(path)
        val floats = FloatArray(2 * GlideWorkspace.MAX_IDEAL_POINTS)
        val flat = PredictionNative.glideAlign(
            tuningArray, word, stroke.xs, stroke.ys, stroke.ts, keys.nativeHandle, keyWidth, floats,
        ) ?: return null
        val count = (flat.size - 1) / 3
        return GlideBeam.Alignment(
            keys = flat.copyOfRange(0, count),
            x = floats.copyOfRange(0, count),
            y = floats.copyOfRange(count, 2 * count),
            samples = flat.copyOfRange(count, 2 * count),
            chars = flat.copyOfRange(2 * count, 3 * count),
            sampleCount = flat[3 * count],
        )
    }

    /** [GlideBeam.sampleShape] natively. */
    fun sampleShape(path: List<GesturePoint>, keyWidth: Float): ByteArray? {
        val stroke = Path(path)
        return PredictionNative.glideSampleShape(stroke.xs, stroke.ys, stroke.ts, keyWidth)
    }

    /** The radius [GlideBeam.decode]'s seeding asks the shape store for. */
    const val SEED_RADIUS = 0.35f
}
