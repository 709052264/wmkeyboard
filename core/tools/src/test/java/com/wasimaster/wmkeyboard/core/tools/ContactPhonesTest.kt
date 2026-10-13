package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPhonesTest {

    private val book = ContactPhones.from(
        listOf(
            ContactPhones.Entry("Jane Doe", "+1 555 0100", contactId = 1),
            ContactPhones.Entry("Jane Doe", "+1 555 0199", primary = true, contactId = 1),
            ContactPhones.Entry("Mom", "01711-000000", contactId = 2),
            ContactPhones.Entry("Mark Twain", "555 0200", contactId = 3),
            ContactPhones.Entry("Mark Ruffalo", "555 0300", contactId = 4),
            ContactPhones.Entry("Will Smith", "555 0400", contactId = 5),
            ContactPhones.Entry("No Number", "n/a", contactId = 6),
        ),
    )

    private fun detect(text: String) =
        SmartSuggest.detect(
            text,
            SmartSuggest.Context(
                calcEnabled = false, currencyEnabled = false, unitsEnabled = false,
                keywordsEnabled = false, dateChips = false, weatherChips = false,
                lookupChips = false, intentChips = false, gifChips = false,
                vocabChips = false, numberChips = false,
                contacts = book,
            ),
        )

    @Test
    fun wholeNameMatchesInAnyCase() {
        assertEquals("Jane Doe", book.match("jane doe")?.name)
        assertEquals("Mom", book.match("mom")?.name)
    }

    @Test
    fun primaryNumberWinsForOnePerson() {
        assertEquals("+1 555 0199", book.match("Jane Doe")?.number)
    }

    @Test
    fun firstNameNeedsItsCapitalAndToBeUnique() {
        assertEquals("Jane Doe", book.match("Jane")?.name)
        assertNull(book.match("jane"))
        // Two Marks saved: ringing one would be a guess.
        assertNull(book.match("Mark"))
        // "will" typed lowercase is a word.
        assertNull(book.match("will"))
        assertEquals("Will Smith", book.match("Will")?.name)
    }

    @Test
    fun aContactWithNoDigitsIsLeftOut() {
        assertNull(book.match("No Number"))
    }

    @Test
    fun chipOffersTheLongestNameAtTheCursor() {
        val hit = detect("tell Jane Doe")
        assertEquals(SmartSuggest.Kind.CONTACT, hit?.kind)
        assertEquals("Jane Doe", hit?.query)
        assertEquals(ToolPrefill.Contact("Jane Doe", "+1 555 0199"), hit?.prefill)
        // The typed name is never replaced.
        assertNull(hit?.insert)
        assertEquals(0, hit?.replaceSpan)
    }

    @Test
    fun chipSurvivesOneTrailingSpaceOnly() {
        assertEquals("Mom", detect("call Mom ")?.query)
        assertNull(detect("call Mom  "))
        assertNull(detect("call Mom and"))
    }

    @Test
    fun punctuationEndsTheName() {
        assertNull(detect("Mom,"))
        assertNull(detect("Mom?"))
    }

    @Test
    fun aCutOffFirstWordIsNotReadAsAName() {
        // Exactly the lookbehind long, so its first word is whatever the
        // window starts on.
        val name = "Om Abcdefghijklmn Abcdefghijklmn Abcdefghijklmno"
        assertEquals(SmartSuggest.LOOKBEHIND, name.length)
        val long = ContactPhones.from(listOf(ContactPhones.Entry(name, "555 0500")))
        fun detectIn(text: String) = SmartSuggest.detect(
            text,
            SmartSuggest.Context(
                calcEnabled = false, currencyEnabled = false, unitsEnabled = false,
                keywordsEnabled = false, dateChips = false, weatherChips = false,
                lookupChips = false, intentChips = false, gifChips = false,
                vocabChips = false, numberChips = false,
                contacts = long,
            ),
        )
        assertEquals(name, detectIn(name)?.query)
        // "Bl" + name: the window starts mid-word, on "Om" of "BlOm".
        assertNull(detectIn("Bl$name"))
    }

    @Test
    fun emptyBookOffersNothing() {
        assertTrue(ContactPhones.EMPTY.isEmpty)
        assertNull(ContactPhones.EMPTY.match("Jane"))
    }
}
