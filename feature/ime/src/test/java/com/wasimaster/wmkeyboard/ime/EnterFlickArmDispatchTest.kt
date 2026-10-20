package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.flickKey
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A text arm on Enter (#789, #838): the flick up types its `?` and nothing
 * else, no line break and no Send, and a plain tap is still Enter.
 */
@RunWith(RobolectricTestRunner::class)
class EnterFlickArmDispatchTest {

    private class Field(private val editor: InputConnection, private val info: EditorInfo) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    private val messageBox = EditorInfo().also {
        it.inputType = InputType.TYPE_CLASS_TEXT
        it.imeOptions = EditorInfo.IME_ACTION_SEND
    }

    private val enter = Key("⏎", action = KeyAction.Enter, flick = mapOf(FlickDirection.UP to "?"))

    private fun field(editor: RecordingEditor): Field {
        val service = Field(editor, messageBox)
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
                fieldNoSuggestions = false,
            ),
        )
        return service
    }

    @Test
    fun `the flick up types one question mark and sends nothing`() {
        val editor = RecordingEditor(initial = "why")
        val service = field(editor)
        service.onKey(enter.flickKey(FlickDirection.UP)!!)
        assertEquals("why?", editor.text.toString())
        assertEquals(emptyList<Int>(), editor.editorActions)
    }

    @Test
    fun `a tap is still Enter`() {
        val editor = RecordingEditor(initial = "why")
        val service = field(editor)
        service.onKey(enter)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), editor.editorActions)
    }
}
