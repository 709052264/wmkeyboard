package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import kotlin.math.sqrt

/**
 * A neural glide engine: [model] reads the stroke, [CtcGlideBeam] spells
 * words out of the dictionary tries. The alternative to [GlideBeam] the user
 * can pick for English on QWERTY (the FUTO Swipe models); the caller attaches
 * one only for an English layout, and [decode] answers null for any grid that
 * is not QWERTY, so the built-in decoder takes every case this one cannot.
 *
 * It takes three of [GlideBeam]'s readings of the stroke into the search
 * ([CtcGlideBeam] says how): finishing a long word early, a loop for a
 * doubled letter, and the shapes a user's own kept glides taught it. Pauses
 * and wiggles it leaves to the model, which reads timing for itself.
 */
class NeuralGlide(
    private val model: SwipeModel,
    private val beam: CtcGlideBeam = CtcGlideBeam(),
) : GlideDecoder {
    private var frameKeys: GlideKeyMap? = null
    private var frame: SwipeFrame? = null

    /** Whether [keys] is a grid this engine reads: all 26 letters where QWERTY puts them. */
    @Synchronized
    fun reads(keys: GlideKeyMap): Boolean = frameOf(keys) != null

    /**
     * Up to [limit] words for [path] (pixels, keys [keyWidth] wide) out of
     * [sources], best first; null when this engine cannot read the stroke
     * (not QWERTY, too short, the model failed), and the caller decodes with
     * [GlideBeam] instead. [deep] widens the beam for the second look a user
     * asks for by undoing a glide.
     */
    @Synchronized
    override fun decode(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int,
        deep: Boolean,
        vocabularyRank: Int,
        lookAhead: Int,
        shapes: GlideShapeSource?,
        tuning: GlideBeam.Tuning,
    ): List<GlideBeam.Candidate>? {
        if (sources.isEmpty() || path.size < MIN_POINTS || keyWidth <= 0f) return null
        if (strokeLength(path) / keyWidth < MIN_STROKE_KEYS) return null
        val frame = frameOf(keys) ?: return null
        val features = frame.features(path, keyWidth) ?: return null
        val emissions = model.emissions(features) ?: return null
        return beam.decode(
            emissions,
            sources,
            limit = limit,
            width = if (deep) DEEP_WIDTH else WIDTH,
            vocabularyRank = if (deep) 0 else vocabularyRank,
            stroke = CtcGlideBeam.Stroke(path, keys, keyWidth, tuning, shapes),
            lookAhead = lookAhead,
        )
    }

    private fun frameOf(keys: GlideKeyMap): SwipeFrame? {
        if (keys !== frameKeys) {
            frameKeys = keys
            frame = SwipeFrame.fit(keys)
        }
        return frame
    }

    private fun strokeLength(path: List<GesturePoint>): Float {
        var total = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            total += sqrt(dx * dx + dy * dy)
        }
        return total
    }

    private companion object {
        /** As [GlideBeam]: anything shorter is a tap. */
        const val MIN_POINTS = 3
        const val MIN_STROKE_KEYS = 0.5f

        /** FUTO's tuned width. */
        const val WIDTH = 100

        /** The second look: three times the hypotheses, so rarer spellings survive. */
        const val DEEP_WIDTH = 300
    }
}
