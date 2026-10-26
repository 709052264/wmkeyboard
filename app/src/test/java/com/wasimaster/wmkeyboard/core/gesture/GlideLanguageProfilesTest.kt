package com.wasimaster.wmkeyboard.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** [GlideLanguageProfiles]: the decoder's weights by language (issue #966). */
class GlideLanguageProfilesTest {

    @Test
    fun `german lowers the frequency weight and raises the gap weight`() {
        val tuned = GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, "de")
        assertEquals(0.5, tuned.freqWeight, 0.0)
        assertEquals(2.0f, tuned.gapWeight, 0f)
        // Nothing else moves.
        assertEquals(GlideBeam.Tuning.DEFAULT, tuned.copy(freqWeight = 1.0, gapWeight = GlideBeam.Tuning.DEFAULT.gapWeight))
    }

    @Test
    fun `english and unknown languages decode with the shipped weights, the same instance`() {
        for (id in listOf("en", "en-US", "fr", "bn", "", null)) {
            assertSame(id, GlideBeam.Tuning.DEFAULT, GlideLanguageProfiles.tune(GlideBeam.Tuning.DEFAULT, id))
            assertSame(id, GlideLanguageProfiles.DEFAULT, GlideLanguageProfiles.forLanguage(id))
        }
    }

    @Test
    fun `regional and script variants of german take the german profile`() {
        for (id in listOf("de-DE", "de-Runr", "de_AT", "DE")) {
            assertSame(id, GlideLanguageProfiles.GERMAN, GlideLanguageProfiles.forLanguage(id))
        }
        assertNotSame(GlideLanguageProfiles.GERMAN, GlideLanguageProfiles.forLanguage("den"))
    }

    @Test
    fun `the profile sits on top of what the user moved`() {
        val mine = GlideBeam.Tuning.DEFAULT.copy(startRadius = 2.2f, endRadius = 2.6f, nearRadius = 1.4f, vocabularyRank = 30000)
        val tuned = GlideLanguageProfiles.tune(mine, "de")
        assertEquals(2.2f, tuned.startRadius, 0f)
        assertEquals(2.6f, tuned.endRadius, 0f)
        assertEquals(1.4f, tuned.nearRadius, 0f)
        assertEquals(30000, tuned.vocabularyRank)
        assertEquals(0.5, tuned.freqWeight, 0.0)
        // Applying it again changes nothing, so a language re-set is free.
        assertSame(tuned, GlideLanguageProfiles.tune(tuned, "de"))
        // And a switch back to English gives the user's own weights back.
        assertEquals(mine, GlideLanguageProfiles.tune(tuned, "en").copy(gapWeight = mine.gapWeight, freqWeight = mine.freqWeight))
    }
}
