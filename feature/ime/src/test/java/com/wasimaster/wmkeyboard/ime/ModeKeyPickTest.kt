package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A mode key toggles against the mode that is on, not the hand pick (#834). */
class ModeKeyPickTest {

    @Test
    fun `a press on the mode a binding chose leaves no hand pick`() {
        assertNull(modeKeyPick("coding", activeModeId = "coding"))
    }

    @Test
    fun `a press on another mode picks it, and a second press goes back`() {
        assertEquals("peck", modeKeyPick("peck", activeModeId = "coding"))
        assertNull(modeKeyPick("peck", activeModeId = "peck"))
    }

    @Test
    fun `with no mode on, presses alternate the mode and automatic`() {
        var active: String? = null
        val seen = (1..4).map { modeKeyPick("peck", active).also { active = it } }
        assertEquals(listOf("peck", null, "peck", null), seen)
    }
}
