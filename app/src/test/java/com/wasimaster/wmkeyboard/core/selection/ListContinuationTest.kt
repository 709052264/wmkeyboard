package com.wasimaster.wmkeyboard.core.selection

import com.wasimaster.wmkeyboard.core.selection.ListContinuation.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListContinuationTest {

    @Test
    fun `bullets carry on as they are`() {
        assertEquals(Step.Continue("- "), ListContinuation.onEnter("- milk", ""))
        assertEquals(Step.Continue("* "), ListContinuation.onEnter("* milk", ""))
        assertEquals(Step.Continue("+ "), ListContinuation.onEnter("+ milk", ""))
        assertEquals(Step.Continue("• "), ListContinuation.onEnter("• milk", ""))
    }

    @Test
    fun `numbers count on with their own delimiter`() {
        assertEquals(Step.Continue("2. "), ListContinuation.onEnter("1. milk", ""))
        assertEquals(Step.Continue("10) "), ListContinuation.onEnter("9) milk", ""))
    }

    @Test
    fun `indentation and the gap are kept`() {
        assertEquals(Step.Continue("  - "), ListContinuation.onEnter("  - milk", ""))
        assertEquals(Step.Continue("3.  "), ListContinuation.onEnter("2.  milk", ""))
    }

    @Test
    fun `a task carries on as an empty box`() {
        assertEquals(Step.Continue("- [ ] "), ListContinuation.onEnter("- [x] milk", ""))
        assertEquals(Step.Continue("☐ "), ListContinuation.onEnter("☑ milk", ""))
    }

    @Test
    fun `an empty item ends the list`() {
        assertEquals(Step.End(2), ListContinuation.onEnter("- ", ""))
        assertEquals(Step.End(5), ListContinuation.onEnter("  3. ", ""))
        assertEquals(Step.End(6), ListContinuation.onEnter("- [ ] ", ""))
    }

    @Test
    fun `an item split mid-line carries on`() {
        assertEquals(Step.Continue("2. "), ListContinuation.onEnter("1. mi", "lk"))
        assertEquals(Step.Continue("- "), ListContinuation.onEnter("- ", "milk"))
    }

    @Test
    fun `plain lines are left alone`() {
        assertNull(ListContinuation.onEnter("hello", ""))
        assertNull(ListContinuation.onEnter("", ""))
        assertNull(ListContinuation.onEnter("-milk", ""))
        assertNull(ListContinuation.onEnter("**bold**", ""))
        assertNull(ListContinuation.onEnter("2024. was long", ""))
        assertNull(ListContinuation.onEnter("1.5 litres", ""))
    }

    @Test
    fun `a caret inside the marker is a plain break`() {
        assertNull(ListContinuation.onEnter("-", " milk"))
        assertNull(ListContinuation.onEnter("1.", " milk"))
    }
}
