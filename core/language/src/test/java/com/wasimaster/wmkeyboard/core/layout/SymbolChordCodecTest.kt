package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored form of the `?123` chords (issue #754), and how a key names one. */
class SymbolChordCodecTest {

    @Test
    fun `chords survive the round trip`() {
        val map = mapOf(
            "p" to KeyAlternate(KeyAction.Tool(ToolbarTool.CLIPBOARD)),
            "t" to KeyAlternate(KeyAction.Text, label = "Thanks!"),
            "1" to KeyAlternate(KeyAction.Edit(TextEditAction.COPY)),
        )
        assertEquals(map, SymbolChordCodec.decode(SymbolChordCodec.encode(map)))
    }

    @Test
    fun `an unbound key falls back to its default, and None clears it`() {
        val none = emptyMap<String, KeyAlternate>()
        assertEquals(DefaultSymbolChords["l"], none.chordOf("l"))
        assertNull(mapOf("l" to KeyAlternate(KeyAction.None)).chordOf("l"))
        assertNull(none.chordOf("q"))
    }

    @Test
    fun `the chords in force merge defaults and leave cleared ones out`() {
        val emoji = KeyAlternate(KeyAction.Emoji)
        assertEquals(
            mapOf("e" to emoji, "l" to DefaultSymbolChords.getValue("l")),
            mapOf("e" to emoji).effectiveChords(),
        )
        assertTrue(mapOf("l" to KeyAlternate(KeyAction.None)).effectiveChords().isEmpty())
    }

    @Test
    fun `a bad entry costs that entry alone`() {
        val json = """{"l":{"action":{"type":"teleport"}},"ab":{"action":{"type":"space"}},""" +
            """"q":{"action":{"type":"space"}},"Q":{"action":{"type":"space"}},"w":"junk"}"""
        assertEquals(mapOf("q" to KeyAlternate(KeyAction.Space)), SymbolChordCodec.decode(json))
    }

    @Test
    fun `garbage decodes to nothing`() {
        assertTrue(SymbolChordCodec.decode("not json").isEmpty())
        assertTrue(SymbolChordCodec.decode(null).isEmpty())
    }

    @Test
    fun `a key names its chord by the one character it types`() {
        assertEquals("l", SymbolChord.tokenOf(Key("L")))
        assertEquals("ß", SymbolChord.tokenOf(Key("ß")))
        assertEquals("1", SymbolChord.tokenOf(Key("1")))
        assertEquals("😀", SymbolChord.tokenOf(Key("😀")))
        assertNull(SymbolChord.tokenOf(Key("?123", action = KeyAction.Symbols)))
        assertNull(SymbolChord.tokenOf(Key(".com")))
        assertNull(SymbolChord.tokenOf(Key(" ", action = KeyAction.Space)))
        assertFalse(SymbolChord.isToken(" "))
        assertFalse(SymbolChord.isToken(""))
    }
}
