package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The recent-word bar (#787, #814, #842): the last words written in this
 * field, read back from it by place, never the word still being typed or
 * text the field already held, and nothing at all where the bar is off or
 * the field is private.
 */
@RunWith(RobolectricTestRunner::class)
class RecentWordBarTest {

    private val on = KeyboardSettings(learnFromTyping = false).let {
        it.copy(suggestionStrip = it.suggestionStrip.copy(recentWordBar = true))
    }
    private val space = Key(" ", action = KeyAction.Space)

    /**
     * An editor that says where its caret is, as real ones do: the bar's
     * read waits for the caret echo when the two disagree (#839).
     */
    private class ExtractingEditor(initial: String) : RecordingEditor(initial = initial) {
        /** A range a pick selected to write over, or null with the caret at the end. */
        private var selected: IntRange? = null

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().also {
                it.text = text.toString()
                it.startOffset = 0
                it.selectionStart = text.length
                it.selectionEnd = text.length
            }

        override fun setSelection(start: Int, end: Int): Boolean {
            selected = if (start == end && end == text.length) null else start until end
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val range = selected ?: return super.commitText(text, newCursorPosition)
            commits += text?.toString().orEmpty()
            this.text.replace(range.first, range.last + 1, text?.toString().orEmpty())
            selected = null
            return true
        }
    }

    /** Each keyboard's editor and the caret it last reported. */
    private val editors = HashMap<GlideKeyboard, RecordingEditor>()
    private val carets = HashMap<GlideKeyboard, Int>()

    private fun keyboard(
        settings: KeyboardSettings,
        initial: String,
        secure: Boolean = false,
        kind: FieldKind = FieldKind.TEXT,
        extracting: Boolean = true,
    ): Pair<GlideKeyboard, RecordingEditor> {
        val (service, editor) = glideKeyboard(
            state = glideReadyState(settings = settings, secureField = secure, fieldKind = kind, fieldNoSuggestions = false),
            editor = if (extracting) ExtractingEditor(initial) else RecordingEditor(initial = initial),
        )
        val end = initial.length
        service.onUpdateSelection(end, end, end, end, -1, -1)
        editors[service] = editor
        carets[service] = end
        return service to editor
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The editor's echo of an edit: its caret moved from where it was to the end. */
    private fun GlideKeyboard.echo() {
        val from = carets.getValue(this)
        val end = editors.getValue(this).text.length
        onUpdateSelection(from, from, end, end, -1, -1)
        carets[this] = end
        idle()
    }

    private fun GlideKeyboard.typeWord(word: String) {
        word.forEach { onText(it.toString()) }
        onKey(space)
        idle()
        echo()
    }

    private fun GlideKeyboard.words(): List<String> = uiState.value.recentBar.map { it.word }

    private fun GlideKeyboard.pick(word: String, replacement: String) {
        val entry = uiState.value.recentBar.last { it.word == word }
        WMKeyboardService::class.java
            .getDeclaredMethod("onRecentWordPick", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(this, entry.start, word, replacement)
        idle()
        echo()
    }

    @Test
    fun `the bar holds only words written in this field, the last three`() {
        val (service, _) = keyboard(on, "form here ")
        service.typeWord("now")
        assertEquals(listOf("now"), service.words())
        for (word in listOf("then", "we", "go")) service.typeWord(word)
        assertEquals(listOf("then", "we", "go"), service.words())
    }

    @Test
    fun `text the field already held never joins`() {
        val (service, _) = keyboard(on, "alpha beta gamma ")
        service.onUpdateSelection(17, 17, 11, 11, -1, -1)
        idle()
        assertEquals(emptyList<String>(), service.words())
    }

    @Test
    fun `off, private or incognito, the bar is empty`() {
        val off = KeyboardSettings(learnFromTyping = false)
        for ((service, _) in listOf(
            keyboard(off, ""),
            keyboard(on, "", secure = true),
            keyboard(on.copy(incognito = true), ""),
        )) {
            service.typeWord("now")
            assertEquals(emptyList<String>(), service.words())
        }
    }

    @Test
    fun `a web address field shows the bar`() {
        val (service, _) = keyboard(on, "", kind = FieldKind.URI)
        service.typeWord("cheap")
        service.typeWord("flights")
        assertEquals(listOf("cheap", "flights"), service.words())
    }

    @Test
    fun `a second change can be undone from the same word`() {
        val (service, editor) = keyboard(on, "")
        service.typeWord("here")
        service.typeWord("now")
        service.pick("here", "hear")
        assertEquals("hear now ", editor.text.toString())
        assertTrue(service.uiState.value.recentBar.first().offers.first() == "here")
        service.pick("hear", "here")
        assertEquals("here now ", editor.text.toString())
        assertEquals("hear", service.uiState.value.recentBar.first().offers.first())
    }

    @Test
    fun `a pick changes the copy it was drawn for, never another`() {
        val (service, editor) = keyboard(on, "")
        service.typeWord("that")
        service.typeWord("that")
        val first = service.uiState.value.recentBar.first()
        service.typeWord("now")
        WMKeyboardService::class.java
            .getDeclaredMethod("onRecentWordPick", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(service, first.start, "that", "what")
        idle()
        service.echo()
        assertEquals("what that now ", editor.text.toString())
    }

    /** [typeWord] with the editor's echo first, before the bar's posted read. */
    private fun GlideKeyboard.typeWordEchoFirst(word: String) {
        word.forEach { onText(it.toString()) }
        onKey(space)
        echo()
    }

    @Test
    fun `an editor that will not say where its caret is keeps its words, the echo early or late`() {
        // #868: the bar's read ran before the caret echo, from the caret
        // before the word, and placed every word wrong.
        for (echoFirst in listOf(false, true)) {
            val (service, _) = keyboard(on, "", extracting = false)
            for (word in listOf("cheap", "flights")) {
                if (echoFirst) service.typeWordEchoFirst(word) else service.typeWord(word)
            }
            assertEquals(listOf("cheap", "flights"), service.words())

            val (other, _) = keyboard(on, "form here ", extracting = false)
            for (word in listOf("now", "then", "we", "go")) {
                if (echoFirst) other.typeWordEchoFirst(word) else other.typeWord(word)
            }
            assertEquals(listOf("then", "we", "go"), other.words())
        }
    }

    @Test
    fun `a word retyped where another stood takes its place`() {
        val (service, editor) = keyboard(on, "")
        service.typeWord("hello")
        service.typeWord("form")
        assertEquals(listOf("hello", "form"), service.words())
        // #868: the editor deletes "form " and reports the caret at 6.
        editor.text.setLength(6)
        service.onUpdateSelection(11, 11, 6, 6, -1, -1)
        carets[service] = 6
        idle()
        service.typeWord("from")
        assertEquals("hello from ", editor.text.toString())
        assertEquals(listOf("hello", "from"), service.words())
    }
}
