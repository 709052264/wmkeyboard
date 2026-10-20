package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * No space (#851): the keyboard's own space after a word comes back out, the
 * next glided word joins on, and a space the user typed is never touched.
 */
@RunWith(RobolectricTestRunner::class)
class NoSpaceTest {

    private val space = Key(" ", action = KeyAction.Space)

    private fun keyboard(initial: String = ""): Pair<GlideKeyboard, RecordingEditor> {
        val editor = RecordingEditor(initial = initial)
        val (service, _, _) = glideKeyboard(
            state = glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    gesture = GestureSettings(autoSpaceAfterGlide = true),
                ),
            ),
            editor = editor,
        )
        return service to editor
    }

    private fun GlideKeyboard.glide(word: String) {
        onGesture(straightStroke(), glideKeyGrid(), GLIDE_KEY_WIDTH, GlideVerdict.Word(word))
        // The strip is already full from an earlier glide: wait for this one.
        TwoThumbKit.drain(this)
    }

    private fun GlideKeyboard.noSpace() = onTextEdit(TextEditAction.NO_SPACE, haptic = false)

    @Test
    fun `the glide's space comes back out, and the next glide joins on`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        assertEquals("foot ", editor.text.toString())
        service.noSpace()
        assertEquals("foot", editor.text.toString())
        service.glide("ball")
        assertEquals("football ", editor.text.toString())
    }

    @Test
    fun `a space the user typed stays`() {
        val (service, editor) = keyboard()
        "foo".forEach { service.onText(it.toString()) }
        service.onKey(space)
        val typed = editor.text.toString()
        service.noSpace()
        assertEquals(typed, editor.text.toString())
    }

    @Test
    fun `after it a backspace takes one letter`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.noSpace()
        service.onKey(Key("", action = KeyAction.Delete))
        assertEquals("foo", editor.text.toString())
    }

    @Test
    fun `a full stop after it hugs the word`() {
        val (service, editor) = keyboard()
        service.glide("foot")
        service.noSpace()
        service.onText(".")
        assertEquals("foot.", editor.text.toString())
    }
}
