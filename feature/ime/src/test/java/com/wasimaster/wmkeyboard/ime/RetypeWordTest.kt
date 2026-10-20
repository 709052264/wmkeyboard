package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
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

    /** An editor that says where its caret is, as most do: Retype wants proof before it deletes (#844). */
    private class ExtractingEditor(initial: String) : RecordingEditor(initial = initial) {
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().also {
                it.text = text.toString()
                it.startOffset = 0
                it.selectionStart = text.length
                it.selectionEnd = text.length
            }
    }

    /** A service over [initial], told its caret sits at the end of it. */
    private fun keyboard(initial: String, extracting: Boolean = true): Pair<GlideKeyboard, RecordingEditor> {
        val editor = if (extracting) ExtractingEditor(initial) else RecordingEditor(initial = initial)
        val (service, _) = glideKeyboard(editor = editor)
        val end = initial.length
        service.onUpdateSelection(end, end, end, end, -1, -1)
        return service to editor
    }

    @Test
    fun `an editor that will not say where its caret is keeps its text`() {
        val (service, editor) = keyboard("hello form ", extracting = false)
        service.retype()
        assertEquals("hello form ", editor.text.toString())
    }

    @Test
    fun `a stale caret leaves the field alone`() {
        val (service, editor) = keyboard("hello form ")
        // The editor moved on with no echo: the keyboard still thinks 11.
        editor.text.insert(0, "oh ")
        service.retype()
        assertEquals("oh hello form ", editor.text.toString())
    }

    @Test
    fun `a strip refresh still waiting does not replace the offer`() {
        val (service, _) = keyboard("hello ")
        "form".forEach { service.onText(it.toString()) }
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key(" ", action = com.wasimaster.wmkeyboard.core.layout.KeyAction.Space))
        val end = "hello form ".length
        service.onUpdateSelection(end, end, end, end, -1, -1)
        service.retype()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("form", service.uiState.value.suggestions.first())
    }

    private fun GlideKeyboard.retype() = onTextEdit(TextEditAction.RETYPE_WORD, haptic = false)

    /** Marks the space at the caret as the one a pick or a glide just left. */
    private fun GlideKeyboard.spaceIsOurs() {
        WMKeyboardService::class.java.getDeclaredField("pendingWordSpace")
            .apply { isAccessible = true }
            .setBoolean(this, true)
    }

    @Test
    fun `the word before the caret goes, with the keyboard's space after it, and comes back on the strip`() {
        val (service, editor) = keyboard("hello form ")
        service.spaceIsOurs()
        service.retype()
        assertEquals("hello ", editor.text.toString())
        assertEquals("form", service.uiState.value.suggestions.first())
    }

    @Test
    fun `a space the user typed stays`() {
        // #823: the space key's space is not the word's to take.
        val (service, editor) = keyboard("hello form ")
        service.retype()
        assertEquals("hello  ", editor.text.toString())
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
        service.spaceIsOurs()
        service.retype()
        service.onSuggestionTapped("form")
        assertEquals("hello form ", editor.text.toString())
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
