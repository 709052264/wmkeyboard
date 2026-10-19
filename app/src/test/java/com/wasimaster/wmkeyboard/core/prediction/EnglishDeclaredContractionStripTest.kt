package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An ambiguous contraction reaches the strip a user actually sees, on the
 * bundled English list (#384).
 *
 * "I can't seem to get the word I'd in any context": `id` is rarer than
 * *identify*, *idiot* and *ideas*, so the strip's three slots filled with
 * completions, and the offer that sat just behind `id` fell off it along with
 * `id` itself. [EnglishContractionStripTest] could not see this, because its
 * list has no other word starting `id`.
 */
class EnglishDeclaredContractionStripTest {

    private val entries by lazy {
        listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .first { it.isFile }
            .inputStream()
            .use { DictionaryLoader.loadEntries(it) }
    }

    private fun english() = SuggestionEngine(
        PackedTrie.of(entries), BengaliPhoneticIndex(emptyList()), UserLexicon(null),
    ).apply {
        primaryLanguageId = "en"
        englishSources = true
    }

    /** The strip's default width; see `SuggestionStripSettings.slotCount`. */
    private val slots = 3

    @Test fun iDIsOnTheStripForIdInEveryContext() {
        val e = english()
        for (previous in listOf(null, "and", "think", "said", "so", "but", "that")) {
            val strip = e.suggest("id", previousWord = previous).take(slots)
            assertTrue("after $previous: $strip", "I'd" in strip)
            assertTrue("after $previous: $strip", "id" in strip)
            assertTrue("after $previous: $strip", strip.indexOf("id") < strip.indexOf("I'd"))
        }
    }

    @Test fun everyAmbiguousFormKeepsItsContractionOnTheStripBehindIt() {
        // `wed` lost *we'd* to *wednesday* and *weddings* the same way.
        val e = english()
        for ((typed, offered) in listOf(
            "id" to "I'd", "wed" to "we'd", "its" to "it's", "hell" to "he'll", "lets" to "let's",
            "shed" to "she'd", "shell" to "she'll", "well" to "we'll", "were" to "we're",
        )) {
            val strip = e.suggest(typed, previousWord = null).take(slots)
            assertEquals("$typed: $strip", listOf(typed, offered), strip.take(2))
        }
    }

    @Test fun theSpaceBarStillKeepsWhatWasTyped() {
        // `id` is a word: an offer, never a correction.
        val e = english()
        for (typed in listOf("id", "wed")) assertNull(typed, e.elide(typed))
    }

    @Test fun aGlideReadingGetsTheSameOfferTheStripMakes() {
        // A glide asks for the offer by the reading it decoded; see `offerApostrophes`.
        val e = english()
        for ((read, offered) in listOf("id" to "I'd", "Id" to "I'd", "wed" to "we'd", "its" to "it's")) {
            assertEquals(read, offered, e.apostropheOffer(read))
        }
        // A spelling that is no word is the space bar's to rewrite, and one
        // with no contraction has nothing to offer.
        for (read in listOf("dont", "the")) assertNull(read, e.apostropheOffer(read))
        e.apostropheFixes = false
        assertNull(e.apostropheOffer("id"))
    }
}
