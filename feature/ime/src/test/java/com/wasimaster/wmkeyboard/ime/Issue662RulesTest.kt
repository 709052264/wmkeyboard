package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text rules behind #662: which quotes the auto-space after punctuation
 * follows, when an address bar is being typed into as a search, and when a
 * sentence-ending mark ends only the quotation it sits in.
 */
class Issue662RulesTest {

    // ---- closingQuoteTakesSpace ----

    @Test
    fun `a straight quote that closes a quotation is spaced`() {
        assertTrue(closingQuoteTakesSpace('"') { "he said \"hello" })
    }

    @Test
    fun `a straight quote that opens one is not`() {
        assertFalse(closingQuoteTakesSpace('"') { "he said " })
        // Counted over the line only: the quote above is closed.
        assertFalse(closingQuoteTakesSpace('"') { "a \"b\"\nc " })
    }

    @Test
    fun `the curly close quote is always spaced and other marks never`() {
        assertTrue(closingQuoteTakesSpace('”') { error("no read for a curly quote") })
        assertFalse(closingQuoteTakesSpace('\'') { "it" })
        assertFalse(mayCloseQuote('\''))
        assertTrue(mayCloseQuote('"'))
    }

    // ---- typedAsSearch ----

    @Test
    fun `an address bar holding a space is a search`() {
        assertTrue(typedAsSearch("best \"titanium"))
        assertFalse(typedAsSearch("example.com/a(b"))
        assertFalse(typedAsSearch(""))
    }

    // ---- sentenceEndInsidePair ----

    @Test
    fun `a mark inside a quotation opened mid-sentence ends only the quotation`() {
        assertTrue(sentenceEndInsidePair("An \"exclamation!\" "))
        assertTrue(sentenceEndInsidePair("He said, “Stop!” "))
        assertTrue(sentenceEndInsidePair("It worked (mostly.) "))
    }

    @Test
    fun `a quotation that is the whole sentence still ends it`() {
        assertFalse(sentenceEndInsidePair("\"Stop!\" "))
        assertFalse(sentenceEndInsidePair("He left. \"Stop!\" "))
        assertFalse(sentenceEndInsidePair("first\n(Really?) "))
    }

    @Test
    fun `no closer after the mark is an ordinary sentence end`() {
        assertFalse(sentenceEndInsidePair("Hello. "))
        assertFalse(sentenceEndInsidePair("Hello "))
        assertFalse(sentenceEndInsidePair(""))
    }

    @Test
    fun `an opener nowhere on the line leaves the caps alone`() {
        assertFalse(sentenceEndInsidePair("one line\nexclamation!\" "))
    }
}
