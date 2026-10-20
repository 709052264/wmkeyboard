package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mode keys draw the mode's name as it is now, and a mode that is gone says so (#821, #835). */
class ModeLabelsTest {

    private val names = mapOf("peck" to "Peck", "code" to "Code")

    @Test
    fun `an unlabelled binding draws the name the mode has now`() {
        assertEquals("Peck", modeBindingLabel("peck", "", names))
        assertEquals("Short", modeBindingLabel("peck", "", mapOf("peck" to "Short")))
    }

    @Test
    fun `a name left by picking another mode follows the mode it now names`() {
        assertEquals("Code", modeBindingLabel("code", "Peck", names))
    }

    @Test
    fun `a label typed by hand stays through a rename and a re-pick`() {
        assertEquals("URL", modeBindingLabel("peck", "URL", names))
        assertEquals("URL", modeBindingLabel("code", "URL", mapOf("peck" to "Short", "code" to "Code")))
    }

    @Test
    fun `a deleted or blank mode draws the missing face, never the stored name`() {
        assertEquals(MissingModeLabel, modeBindingLabel("gone", "Peck", names))
        assertEquals(MissingModeLabel, modeBindingLabel("", "", names))
    }

    @Test
    fun `the key, its arms, its alternates and its hold are all labelled`() {
        val mode = KeyAlternate(KeyAction.Mode("peck"))
        val key = Key(
            "",
            action = KeyAction.Mode("code"),
            flickActions = mapOf(FlickDirection.UP to mode),
            actionAlternates = listOf(mode, KeyAlternate(KeyAction.Mode("gone"))),
            holdAction = mode,
        ).withModeNames(names)
        assertEquals("Code", key.label)
        assertEquals("Peck", key.flickActions.getValue(FlickDirection.UP).label)
        assertEquals(listOf("Peck", MissingModeLabel), key.actionAlternates.map { it.label })
        assertEquals("Peck", key.holdAction!!.label)
    }

    @Test
    fun `a key with no mode binding is left alone`() {
        val key = Key("a")
        assertEquals(key, key.withModeNames(names))
    }
}
