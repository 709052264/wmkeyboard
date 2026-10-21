package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch

/**
 * A glide engine the user picked in place of [GlideBeam]: FUTO Swipe
 * ([NeuralGlide]) or Google's gesture library
 * ([com.wasimaster.wmkeyboard.core.gesture.latinime.LatinImeGlide]).
 * `SuggestionEngine.glide` asks it first and decodes with [GlideBeam]
 * whenever it answers null, so an engine only has to say yes for the
 * strokes it can really read.
 */
interface GlideDecoder {

    /**
     * Up to [limit] words for [path] (pixels, keys [keyWidth] wide on
     * [keys]) out of [sources], best first, scores in nats like
     * [GlideBeam]'s; null when this engine cannot read the stroke, and the
     * caller decodes with [GlideBeam] instead. [deep] is the second look a
     * user asks for by undoing a glide; [vocabularyRank] caps the
     * dictionaries at their commonest words, 0 for no cap.
     *
     * [lookAhead], [shapes] and [tuning] are what [GlideBeam.decode] is
     * given beside the stroke: how many guesses at an unfinished word may
     * join the readings, the user's learned shapes for this grid, and the
     * glide weights the marks in a stroke are read with. An engine uses
     * those it can and ignores the rest.
     */
    @Suppress("LongParameterList")
    fun decode(
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        keyWidth: Float,
        sources: List<FuzzyBeamSearch.WalkSource>,
        limit: Int,
        deep: Boolean = false,
        vocabularyRank: Int = 0,
        lookAhead: Int = 0,
        shapes: GlideShapeSource? = null,
        tuning: GlideBeam.Tuning = GlideBeam.Tuning.DEFAULT,
    ): List<GlideBeam.Candidate>?
}
