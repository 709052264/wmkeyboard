package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #736: under two-thumb typing a swipe may start on a letter with flick
 * arms once the finger leaves the key's cell, as one-finger glide has since
 * #655. Both loops ask the same two questions at the down.
 */
class FlickGlideOwnershipTest {

    private val cell = Rect(0f, 0f, 100f, 120f)

    @Test
    fun `a letter with a text arm waits for its cell, then glides`() {
        val z = Key("z", flick = mapOf(FlickDirection.DOWN to "*"))
        assertEquals(cell, z.glideFlickCell(cell))
        assertFalse(z.ownsDragFromGlide(z.glideFlickCell(cell)))
    }

    @Test
    fun `a letter with an action arm waits for its cell too`() {
        val c = Key(
            "c",
            flickActions = mapOf(FlickDirection.UP to KeyAlternate(KeyAction.Tool(ToolbarTool.COPY))),
        )
        assertEquals(cell, c.glideFlickCell(cell))
        assertFalse(c.ownsDragFromGlide(c.glideFlickCell(cell)))
    }

    /** A held caret arm has moved the caret before the finger wanders off; nothing would take it back. */
    @Test
    fun `a letter whose arm repeats keeps the whole stroke`() {
        val e = Key(
            "e",
            flickActions = mapOf(FlickDirection.LEFT to KeyAlternate(KeyAction.Edit(TextEditAction.LEFT))),
        )
        assertNull(e.glideFlickCell(cell))
        assertTrue(e.ownsDragFromGlide(e.glideFlickCell(cell)))
    }

    @Test
    fun `chords and layer peeks stay owned`() {
        val shift = Key("", action = KeyAction.Shift)
        val ctrl = Key("Ctrl", action = KeyAction.Mod(ModifierKey.CTRL))
        val symbols = Key("?123", action = KeyAction.Symbols)
        for (key in listOf(shift, ctrl, symbols)) {
            assertNull(key.glideFlickCell(cell))
            assertTrue(key.ownsDragFromGlide(key.glideFlickCell(cell)))
        }
    }

    @Test
    fun `a plain letter has no cell to wait for and is not owned`() {
        val a = Key("a")
        assertNull(a.glideFlickCell(cell))
        assertFalse(a.ownsDragFromGlide(null))
        assertFalse(null.ownsDragFromGlide(null))
    }

    /** No cell measured yet: the arms win, as they did before #736. */
    @Test
    fun `a flick letter with no measured cell stays owned`() {
        val z = Key("z", flick = mapOf(FlickDirection.DOWN to "*"))
        assertNull(z.glideFlickCell(null))
        assertTrue(z.ownsDragFromGlide(null))
    }
}
