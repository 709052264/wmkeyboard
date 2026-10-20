package com.wasimaster.wmkeyboard.ime

import android.os.Looper
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

    private fun keyboard(
        settings: KeyboardSettings,
        initial: String,
        secure: Boolean = false,
        kind: FieldKind = FieldKind.TEXT,
    ): Pair<GlideKeyboard, RecordingEditor> {
        val (service, editor) = glideKeyboard(
            state = glideReadyState(settings = settings, secureField = secure, fieldKind = kind, fieldNoSuggestions = false),
            editor = RecordingEditor(initial = initial),
        )
        val end = initial.length
        service.onUpdateSelection(end, end, end, end, -1, -1)
        return service to editor
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun GlideKeyboard.typeWord(word: String) {
        word.forEach { onText(it.toString()) }
        onKey(space)
        idle()
    }

    private fun GlideKeyboard.words(): List<String> = uiState.value.recentBar.map { it.word }

    private fun GlideKeyboard.pick(word: String, replacement: String) {
        val entry = uiState.value.recentBar.last { it.word == word }
        WMKeyboardService::class.java
            .getDeclaredMethod("onRecentWordPick", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(this, entry.start, word, replacement)
        idle()
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
        assertEquals("what that now ", editor.text.toString())
    }
}
