package com.wasimaster.wmkeyboard.core.gesture

/**
 * The glide decoder's weights by language (issue #966).
 *
 * [GlideBeam.Tuning]'s defaults were fitted on English swipes and ship
 * unchanged for every language, and two of them are a property of the
 * language and its word list rather than of the hand: how much a word's
 * frequency counts against the stroke's shape ([GlideBeam.Tuning.freqWeight]),
 * and how dearly a detour between two keys is charged
 * ([GlideBeam.Tuning.gapWeight]). German's useful words are rare in any list
 * (its compounds sit at a median rank of 20,714 in a 150k list, against 369
 * for the English words people swipe), so a frequency weight fitted on
 * English outvotes the shape for German. Measured on 2,588 real German
 * swipes (FUTO swipe.futo.org, Wikipedia sentences) and 10,908 more
 * (HeliBoard's gesture data, rare words), success per swipe:
 *
 *     shipped                     71.41 %   21.95 %
 *     freqWeight 0.5              73.69 %   30.46 %
 *     freqWeight 0.5, gap 2.0     74.27 %   32.51 %
 *
 * and the same profile on 2,129 English strokes costs 81.49 % -> 80.41 %.
 * No one value wins both, which is what makes it a language's setting and
 * not a new default. The profile sits on top of whatever the user has moved
 * in Advanced tuning: it touches only the two weights no setting reaches,
 * so the radii and the marks stay theirs.
 *
 * Start with German; the compounding languages (Finnish, Dutch, Swedish,
 * Norwegian) are the next candidates, one harness run each
 * (`native/wm-engine/examples/glide_beam_eval`, `COLS` set to the layout's
 * columns). A language with no row here decodes with the defaults, and the
 * English output is unchanged to the bit.
 */
object GlideLanguageProfiles {

    /** The weights a profile moves; everything else stays as the caller tuned it. */
    class Profile(val freqWeight: Double, val gapWeight: Float) {
        /** [tuning] with this profile's weights on it, or [tuning] itself when it already carries them. */
        fun applyTo(tuning: GlideBeam.Tuning): GlideBeam.Tuning =
            if (tuning.freqWeight == freqWeight && tuning.gapWeight == gapWeight) {
                tuning
            } else {
                tuning.copy(freqWeight = freqWeight, gapWeight = gapWeight)
            }
    }

    /** The shipped weights, which every language without a row of its own decodes with. */
    val DEFAULT = Profile(GlideBeam.Tuning.DEFAULT.freqWeight, GlideBeam.Tuning.DEFAULT.gapWeight)

    /** German: shape over frequency, since its targets are rare in every list (see the class note). */
    val GERMAN = Profile(freqWeight = 0.5, gapWeight = 2.0f)

    private val BY_LANGUAGE: Map<String, Profile> = mapOf(
        "de" to GERMAN,
    )

    /**
     * The profile for [languageId], a registry id such as `de` or `de-DE`;
     * [DEFAULT] for a language with none, and for a blank or null id.
     */
    fun forLanguage(languageId: String?): Profile = BY_LANGUAGE[base(languageId)] ?: DEFAULT

    /** [tuning] as the decoder should run it for [languageId]. */
    fun tune(tuning: GlideBeam.Tuning, languageId: String?): GlideBeam.Tuning =
        forLanguage(languageId).applyTo(tuning)

    /** The language subtag of a registry id: `de-DE` and `de-Runr` are German spelled two ways. */
    private fun base(languageId: String?): String =
        languageId.orEmpty().substringBefore('-').substringBefore('_').lowercase()
}
