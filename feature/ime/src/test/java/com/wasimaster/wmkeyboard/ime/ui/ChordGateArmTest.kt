package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.input.pointer.PointerId
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * `?123` going out to a flick arm while it could be a chord's trigger (#875):
 * one of the two runs, never both.
 */
@RunWith(RobolectricTestRunner::class)
class ChordGateArmTest {

    private val trigger = PointerId(1)
    private val partner = Key("a")

    private fun gate(ran: MutableList<KeyAlternate>) = ChordGate().apply {
        chords = mapOf("a" to KeyAlternate(KeyAction.LanguageSwitch))
        run = { ran += it }
    }

    @Test
    fun `an arm taken before any chord lets the partner type its own letter`() {
        val ran = mutableListOf<KeyAlternate>()
        val gate = gate(ran)
        gate.triggerDown(trigger)
        ShadowSystemClock.advanceBy(Duration.ofMillis(CHORD_MIN_HOLD_MS + 10))
        gate.showLabels(trigger)
        assertFalse(gate.armTaken(trigger))
        assertNull(gate.labels.value)
        assertFalse(gate.tap(partner))
        assertFalse(gate.triggerUp(trigger))
        assertEquals(emptyList<KeyAlternate>(), ran)
    }

    @Test
    fun `a chord that already ran keeps the press`() {
        val ran = mutableListOf<KeyAlternate>()
        val gate = gate(ran)
        gate.triggerDown(trigger)
        ShadowSystemClock.advanceBy(Duration.ofMillis(CHORD_MIN_HOLD_MS + 10))
        assertTrue(gate.tap(partner))
        assertTrue(gate.armTaken(trigger))
        assertTrue(gate.triggerUp(trigger))
        assertEquals(1, ran.size)
    }
}
