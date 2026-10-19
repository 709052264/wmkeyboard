package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The recent-word bar (#787): the last words written before the caret, read
 * from the field, never the word still being typed, and nothing at all where
 * the bar is off or the field is private.
 */
@RunWith(RobolectricTestRunner::class)
class RecentWordBarTest {

    private val on = KeyboardSettings(learnFromTyping = false).let {
        it.copy(suggestionStrip = it.suggestionStrip.copy(recentWordBar = true))
    }
    private val space = Key(" ", action = KeyAction.Space)

    private fun keyboard(settings: KeyboardSettings, initial: String, secure: Boolean = false): GlideKeyboard {
        val (service, _) = glideKeyboard(
            state = glideReadyState(settings = settings, secureField = secure, fieldNoSuggestions = false),
            editor = RecordingEditor(initial = initial),
        )
        val end = initial.length
        service.onUpdateSelection(end, end, end, end, -1, -1)
        return service
    }

    private fun GlideKeyboard.typeWord(word: String) {
        word.forEach { onText(it.toString()) }
        onKey(space)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the bar holds the last two words written`() {
        val service = keyboard(on, "form here ")
        service.typeWord("now")
        assertEquals(listOf("here", "now"), service.uiState.value.recentBar)
    }

    @Test
    fun `off, private or incognito, the bar is empty`() {
        val off = KeyboardSettings(learnFromTyping = false)
        for (service in listOf(
            keyboard(off, "form here "),
            keyboard(on, "form here ", secure = true),
            keyboard(on.copy(incognito = true), "form here "),
        )) {
            service.typeWord("now")
            assertEquals(emptyList<String>(), service.uiState.value.recentBar)
        }
    }
}
