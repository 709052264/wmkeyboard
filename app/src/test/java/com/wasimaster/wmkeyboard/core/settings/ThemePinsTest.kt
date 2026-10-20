package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the keyboard keeps a theme of its own (issue #806): the places the
 * themes screen lists, so the theme picked there and the keyboard can no
 * longer disagree with nothing saying why.
 */
class ThemePinsTest {

    private val mine = ThemeSpec(id = "custom_mine", name = "Mine")

    /** The reporter's set-up: their theme picked, QWERTY's Numpad tab on the built-in default. */
    private fun withNumpadTab(themeId: String?, settings: KeyboardSettings = base()): KeyboardSettings {
        val qwerty = BuiltInLayouts.default
        val tab = LayerSpec(rows = BuiltInPanelLayouts.default(PanelKind.NUMPAD).grid.rows, themeId = themeId)
        val edited = qwerty.copy(layers = qwerty.layers + (PanelKind.NUMPAD.layerKey to tab))
        return settings.copy(customLayouts = listOf(edited))
    }

    private fun base() = KeyboardSettings(
        keyboardThemeId = mine.id,
        customThemes = listOf(mine),
        enabledLayoutIds = listOf(BuiltInLayouts.DEFAULT_ID),
    )

    @Test
    fun `a panel tab on the default theme is a pin`() {
        val pins = withNumpadTab(DEFAULT_THEME_ID).themePins(emptyList())
        assertEquals(
            listOf(ThemePin(BuiltInLayouts.DEFAULT_ID, BuiltInLayouts.default.name, "panel_numpad", DEFAULT_THEME_ID)),
            pins,
        )
    }

    @Test
    fun `no theme, the picked theme, or a missing theme is not a pin`() {
        assertTrue(withNumpadTab(null).themePins(emptyList()).isEmpty())
        assertTrue(withNumpadTab(mine.id).themePins(emptyList()).isEmpty())
        assertTrue(withNumpadTab("custom_gone").themePins(emptyList()).isEmpty())
    }

    @Test
    fun `the picked theme pins too while the automatic pair is on`() {
        val auto = base().copy(autoTheme = AutoThemeSettings(enabled = true))
        assertEquals(1, withNumpadTab(mine.id, auto).themePins(emptyList()).size)
    }

    @Test
    fun `a shared panel layout with a theme is a pin with no layout`() {
        val shared = BuiltInPanelLayouts.default(PanelKind.NUMPAD).let {
            it.copy(grid = it.grid.copy(themeId = DEFAULT_THEME_ID))
        }
        assertEquals(
            listOf(ThemePin(null, null, "panel_numpad", DEFAULT_THEME_ID)),
            base().themePins(listOf(shared)),
        )
    }

    @Test
    fun `a layout the globe key does not reach is left out`() {
        val pins = withNumpadTab(DEFAULT_THEME_ID, base().copy(enabledLayoutIds = listOf("builtin_azerty")))
            .themePins(emptyList())
        assertTrue(pins.isEmpty())
    }
}
