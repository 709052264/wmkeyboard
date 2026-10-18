package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #754: what a tap runs while `?123` is held. */
class SymbolChordResolveTest {

    private val nextLanguage = KeyAlternate(KeyAction.LanguageSwitch)
    private val chords = mapOf("l" to nextLanguage)
    private val held = CHORD_MIN_HOLD_MS

    @Test
    fun `a bound key tapped while ?123 is held runs its chord`() {
        assertEquals(nextLanguage, resolveSymbolChord(true, held, Key("l"), chords))
    }

    @Test
    fun `a capital on the face is the same key`() {
        assertEquals(nextLanguage, resolveSymbolChord(true, held, Key("L"), chords))
    }

    @Test
    fun `an unbound key types as it always did`() {
        assertNull(resolveSymbolChord(true, held, Key("k"), chords))
    }

    @Test
    fun `no chord without ?123 down`() {
        assertNull(resolveSymbolChord(false, held, Key("l"), chords))
    }

    @Test
    fun `a roll off ?123 onto the next key is too quick to be a chord`() {
        assertNull(resolveSymbolChord(true, CHORD_MIN_HOLD_MS - 1, Key("l"), chords))
    }

    @Test
    fun `a key that types no single character has no chord`() {
        assertNull(resolveSymbolChord(true, held, Key("⌫", action = KeyAction.Delete), chords))
        assertNull(resolveSymbolChord(true, held, Key("lol"), mapOf("lol" to nextLanguage)))
    }

    @Test
    fun `chords off is an empty map`() {
        assertNull(resolveSymbolChord(true, held, Key("l"), emptyMap()))
    }
}
