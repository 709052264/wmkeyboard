package com.wasimaster.wmkeyboard.core.tools.anki

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Under Robolectric only because the object's provider URIs are `android.net.Uri`. */
@RunWith(RobolectricTestRunner::class)
class AnkiDroidTest {

    @Test
    fun `the first field's checksum is the first eight hex digits of its SHA-1`() {
        // sha1("hello") = aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d
        assertEquals(0xaaf4c61dL, AnkiDroid.fieldChecksum("hello"))
    }

    @Test
    fun `the note's fields are in the note type's order`() {
        val note = AnkiDroid.Note("食べる", "たべる", "たべꜜる [2]", "to eat", "パンを食べました。", "Jitendex")
        assertEquals(AnkiDroid.FIELDS.size, note.fields.size)
        assertEquals(listOf("食べる", "たべる", "たべꜜる [2]", "to eat", "パンを食べました。", "Jitendex"), note.fields)
    }

    @Test
    fun `the editor gets the back as plain lines`() {
        val note = AnkiDroid.Note(
            word = "house",
            meaning = "<i>noun</i> a building<br>a family",
            sentence = "The house is red.",
        )
        assertEquals("noun a building\na family\nThe house is red.", AnkiDroid.editorBack(note))
    }
}
