package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shape rules for the Tab order each panel declares. The `when` in
 * [panelFocusRegions] is exhaustive, so a new panel cannot be forgotten —
 * these hold the declarations themselves to the contract the controller
 * assumes.
 */
class PanelFocusRegionsTest {

    @Test
    fun `no panel declares a region twice`() {
        for (panel in PanelMode.entries) {
            val regions = panelFocusRegions(panel)
            assertEquals(
                "duplicate regions for $panel",
                regions.size,
                regions.distinct().size,
            )
        }
    }

    @Test
    fun `panels a keyboard user drives are navigable`() {
        val navigable = listOf(
            PanelMode.EMOJI, PanelMode.CLIPBOARD, PanelMode.SYMBOLS,
            PanelMode.TOOLBOX, PanelMode.AI, PanelMode.TRANSLATE,
        )
        for (panel in navigable) {
            assertTrue(
                "$panel lost its hardware navigation",
                panelFocusRegions(panel).isNotEmpty(),
            )
        }
    }

    @Test
    fun `search comes first wherever a panel has one`() {
        for (panel in PanelMode.entries) {
            // Text Repeat's SEARCH is the text being repeated, which sits
            // under the mode tabs it depends on.
            if (panel == PanelMode.TEXT_REPEAT) continue
            val regions = panelFocusRegions(panel)
            if (FocusRegion.SEARCH in regions) {
                assertEquals("search must lead the Tab order for $panel", regions.first(), FocusRegion.SEARCH)
            }
        }
    }
}
