package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Retype (#791): the word right behind the caret comes back out of the field
 * and onto the strip, and nothing else is ever touched.
 */
@RunWith(RobolectricTestRunner::class)
class RetypeWordTest {

    /** A service over [initial], told its caret sits at the end of it. */
    private fun keyboard(initial: String): Pair<GlideKeyboard, RecordingEditor> {
        val (service, editor) = glideKeyboard(editor = RecordingEditor(initial = initial))
        val end = initial.length
        service.onUpdateSelection(end, end, end, end, -1, -1)
        return service to editor
    }

    private fun GlideKeyboard.retype() = onTextEdit(TextEditAction.RETYPE_WORD, haptic = false)

    @Test
    fun `the word before the caret goes, with the space after it, and comes back on the strip`() {
        val (service, editor) = keyboard("hello form ")
        service.retype()
        assertEquals("hello ", editor.text.toString())
        assertEquals("form", service.uiState.value.suggestions.first())
    }

    @Test
    fun `a word with no space after it goes the same way`() {
        val (service, editor) = keyboard("hello form")
        service.retype()
        assertEquals("hello ", editor.text.toString())
        assertEquals("form", service.uiState.value.suggestions.first())
    }

    @Test
    fun `only the copy at the caret goes, never an earlier one`() {
        val (service, editor) = keyboard("form and form ")
        service.retype()
        assertEquals("form and ", editor.text.toString())
    }

    @Test
    fun `a mark, a line break or two spaces behind the caret leave the field alone`() {
        for (field in listOf("hello form.", "hello form\n", "hello form  ", "")) {
            val (service, editor) = keyboard(field)
            service.retype()
            assertEquals(field, field, editor.text.toString())
        }
    }

    @Test
    fun `picking the word back puts it where it was`() {
        val (service, editor) = keyboard("hello form ")
        service.retype()
        service.onSuggestionTapped("form")
        assertTrue(editor.text.toString(), editor.text.toString().startsWith("hello form"))
    }

    @Test
    fun `a word still being typed is taken back whole`() {
        val (service, editor) = keyboard("hello ")
        service.onText("f")
        service.onText("o")
        service.retype()
        assertEquals("hello ", editor.text.toString())
        assertEquals("fo", service.uiState.value.suggestions.first())
    }
}
