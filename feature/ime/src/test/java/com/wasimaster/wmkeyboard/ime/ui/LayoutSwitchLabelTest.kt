package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.settings.SpacebarDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name the spacebar and the switcher give a layout once the user has
 * named it themselves: theirs, in every mode, and the automatic one again the
 * moment it is blank.
 */
class LayoutSwitchLabelTest {

    private val qwerty = BuiltInLayouts.QWERTY_ID
    private val enabled = listOf(qwerty, BuiltInLayouts.COMPACT_ID)

    @Test
    fun aGivenNameWinsInEveryMode() {
        for (mode in SpacebarDisplay.entries) {
            assertEquals("Work", layoutSwitchLabel(qwerty, enabled, emptyList(), mode, mapOf(qwerty to "Work")))
        }
    }

    @Test
    fun aBlankNameIsTheAutomaticOne() {
        for (mode in SpacebarDisplay.entries) {
            assertEquals(
                layoutSwitchLabel(qwerty, enabled, emptyList(), mode),
                layoutSwitchLabel(qwerty, enabled, emptyList(), mode, mapOf(qwerty to "  ")),
            )
        }
    }

    @Test
    fun anotherLayoutsNameLeavesThisOneAlone() {
        val names = mapOf(BuiltInLayouts.COMPACT_ID to "Small")
        assertEquals(
            layoutSwitchLabel(qwerty, enabled, emptyList(), SpacebarDisplay.BOTH),
            layoutSwitchLabel(qwerty, enabled, emptyList(), SpacebarDisplay.BOTH, names),
        )
    }
}
