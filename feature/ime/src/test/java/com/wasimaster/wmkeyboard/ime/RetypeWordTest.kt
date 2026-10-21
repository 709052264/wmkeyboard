package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val (service, _) = keyboard("hello form ")
        // A refresh queued from before (#872: one that really is pending, so
        // the test fails if Retype stops calling it off).
        @Suppress("UNCHECKED_CAST")
        val ui = service.field("_uiState") as MutableStateFlow<KeyboardUiState>
        val pending = (service.field("serviceScope") as CoroutineScope).launch {
            yield()
            ui.update { it.copy(suggestions = listOf("STALE")) }
        }
        WMKeyboardService::class.java.getDeclaredField("suggestionJob")
            .apply { isAccessible = true }
            .set(service, pending)
        service.retype()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("form", service.uiState.value.suggestions.first())
    }

    @Test
    fun `an editor that will not say where its caret is is not offered Retype`() {
        // #872: the word menu offered it, and choosing it did nothing.
        val (service, _) = keyboard("hello form ", extracting = false)
        val target = WMKeyboardService::class.java.getDeclaredMethod("retypeTarget")
            .apply { isAccessible = true }
            .invoke(service)
        assertNull(target)
    }

    private fun GlideKeyboard.field(name: String): Any? =
        WMKeyboardService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this)

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
        // The keyboard's space, so it goes too (#823 keeps a typed one).
        service.spaceIsOurs()
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
