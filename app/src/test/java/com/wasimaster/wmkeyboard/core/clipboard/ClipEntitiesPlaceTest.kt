package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Places inside clips get a chip of their own (#350). */
class ClipEntitiesPlaceTest {

    private fun of(text: String, kind: ClipEntityKind) =
        ClipEntities.extract(text).filter { it.kind == kind }.map { it.value }

    @Test
    fun `coordinates, plus codes and geo links are places`() {
        assertEquals(listOf("23.780600, 90.419300"), of("I'm at 23.780600, 90.419300 now", ClipEntityKind.PLACE))
        assertEquals(listOf("7MMGQCJ9+6P"), of("the code is 7MMGQCJ9+6P", ClipEntityKind.PLACE))
        assertEquals(listOf("geo:0,0?q=23.78,90.41(Home)"), of("open geo:0,0?q=23.78,90.41(Home).", ClipEntityKind.PLACE))
    }

    @Test
    fun `a plus code next to the word code is not a one time code`() {
        assertEquals(emptyList<String>(), of("the code is 7MMGQCJ9+6P", ClipEntityKind.OTP))
    }

    @Test
    fun `a map link stays a link`() {
        val text = "here https://www.google.com/maps/search/?api=1&query=23.780600%2C90.419300 see you"
        assertEquals(1, of(text, ClipEntityKind.URL).size)
        assertEquals(emptyList<String>(), of(text, ClipEntityKind.PLACE))
    }
}
