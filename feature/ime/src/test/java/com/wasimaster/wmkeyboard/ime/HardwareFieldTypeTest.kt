package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.prediction.LearningBuffer
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.ime.ui.LiveWpmReadout
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * A physical keyboard judges the field that has focus, not the last one the
 * on-screen keys were shown in (#845, test coverage #878).
 *
 * With a hardware keyboard attached the keys stay hidden, so Android calls
 * `onStartInput` for each field and never `onStartInputView`. Each test plants
 * the state `startInputView` would have left for the field that was last
 * shown, moves focus with `onStartInput` alone, and then types
 * `hello sunshine teh` and Enter as real `KeyEvent`s through `onKeyDown` and
 * `onKeyUp`.
 */
@RunWith(RobolectricTestRunner::class)
class HardwareFieldTypeTest {

    private class FieldKeyboard(private val editor: InputConnection) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        var field: EditorInfo = field(InputType.TYPE_CLASS_TEXT)
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = field

        /** Focus moves with the keys hidden: `onStartInput`, and no `onStartInputView`. */
        fun focus(next: EditorInfo) {
            field = next
            onStartInput(next, false)
        }
    }

    private class Typed(val downs: List<Boolean>, val ups: List<Boolean>)

    @Before
    @After
    fun clearReadout() {
        LiveWpmReadout.wpm.intValue = 0
    }

    private val learning = KeyboardSettings(learnFromTyping = true, typingStatsEnabled = true)

    /**
     * The service with an engine that knows the three words, as if a field of
     * [shown] had last had the keys up.
     */
    private fun keyboard(
        shown: EditorInfo?,
        settings: KeyboardSettings = learning,
    ): Pair<FieldKeyboard, RecordingEditor> {
        val editor = RecordingEditor()
        val service = FieldKeyboard(editor)
        plantPersonalStores(service)
        set(service, "suggestionEngine", engine())
        val base = glideReadyState(settings = settings, fieldNoSuggestions = false)
        seedState(
            service,
            if (shown == null) {
                // Never shown: what a fresh service holds before any field.
                base.copy(fieldNoSuggestions = KeyboardUiState().fieldNoSuggestions)
            } else {
                service.field = shown
                base.copy(
                    secureField = shown.inputType.isPassword(),
                    fieldKind = if (shown.inputType.isEmail()) FieldKind.EMAIL else FieldKind.TEXT,
                )
            },
        )
        return service to editor
    }

    private fun engine() = SuggestionEngine(
        Trie().apply {
            insert("the", 9000)
            insert("hello", 5000)
            insert("sunshine", 3000)
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    )

    private fun set(service: WMKeyboardService, name: String, value: Any) {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    private fun <T> get(service: WMKeyboardService, name: String): T {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(service) as T
    }

    private fun pass(ms: Long = KEY_MS) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** `hello sunshine teh` and Enter, each key down and up, a key every [KEY_MS]. */
    private fun FieldKeyboard.type(text: String = "hello sunshine teh"): Typed {
        val downs = mutableListOf<Boolean>()
        val ups = mutableListOf<Boolean>()
        val codes = text.map { c ->
            if (c == ' ') KeyEvent.KEYCODE_SPACE else KeyEvent.KEYCODE_A + (c - 'a')
        } + KeyEvent.KEYCODE_ENTER
        for (code in codes) {
            val now = SystemClock.uptimeMillis()
            downs += onKeyDown(code, KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            ups += onKeyUp(code, KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0))
            pass()
        }
        return Typed(downs, ups)
    }

    private fun FieldKeyboard.counters(): List<Any> =
        get<TypingStats>(this, "typingStats").let { listOf(it.dayEntries(), it.lifetime()) }

    private fun FieldKeyboard.learnedCount(): Long = get<UserLexicon>(this, "userLexicon").mutationCount()

    private fun FieldKeyboard.waiting(): Int = get<LearningBuffer>(this, "learningBuffer").size

    /** Everything a password field must be spared, checked after [type]. */
    private fun FieldKeyboard.assertUntouched(typed: Typed, editor: RecordingEditor, before: List<Any>, lexicon: Long) {
        assertEquals("every key goes back to the app", List(typed.downs.size) { false }, typed.downs)
        assertEquals("and so does every release", List(typed.ups.size) { false }, typed.ups)
        assertEquals("the keyboard wrote nothing, composing or committed", "", editor.text.toString())
        assertEquals(emptyList<String>(), editor.commits)
        assertEquals("nothing reached the lexicon", lexicon, learnedCount())
        assertEquals("nothing waits to be learned", 0, waiting())
        assertEquals("typing stats did not move", before, counters())
        assertTrue(uiState.value.secureField)
    }

    private fun checkPassword(variation: Int, shown: EditorInfo?) {
        val (service, editor) = keyboard(shown)
        service.focus(field(InputType.TYPE_CLASS_TEXT or variation))
        val before = service.counters()
        val lexicon = service.learnedCount()
        service.assertUntouched(service.type(), editor, before, lexicon)
    }

    @Test
    fun `fixture - a plain field shown takes the keys, corrects, learns and counts`() {
        val (service, editor) = keyboard(field(InputType.TYPE_CLASS_TEXT))
        val before = service.counters()
        val typed = service.type()
        assertTrue("the keyboard takes the letters", typed.downs.dropLast(1).all { it })
        assertTrue("teh is corrected: ${editor.text}", editor.text.startsWith("hello sunshine the"))
        assertTrue("the words are taken in", service.waiting() > 0 || service.learnedCount() > 0)
        assertFalse("and counted", before == service.counters())
    }

    @Test
    fun `a password field reached from a shown plain field goes to the app untouched`() {
        checkPassword(InputType.TYPE_TEXT_VARIATION_PASSWORD, shown = field(InputType.TYPE_CLASS_TEXT))
    }

    @Test
    fun `a web password field reached from a shown plain field goes to the app untouched`() {
        checkPassword(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, shown = field(InputType.TYPE_CLASS_TEXT))
    }

    @Test
    fun `a password field with the keys never shown goes to the app untouched`() {
        checkPassword(InputType.TYPE_TEXT_VARIATION_PASSWORD, shown = null)
        checkPassword(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, shown = null)
    }

    @Test
    fun `a password field keeps the recent-word bar empty and shows no speed`() {
        val settings = learning.copy(
            spacebarLiveWpm = true,
            suggestionStrip = SuggestionStripSettings(recentWordBar = true),
        )
        val (service, _) = keyboard(field(InputType.TYPE_CLASS_TEXT), settings)
        service.focus(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        service.type("hello sunshine teh hello sunshine")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Any>(), service.uiState.value.recentBar)
        assertEquals(emptyList<Any>(), get<List<*>>(service, "recentPending"))
        assertEquals("no speed on the space bar", 0, LiveWpmReadout.wpm.intValue)
    }

    @Test
    fun `a plain field reached from a shown password field gets its typing help back`() {
        val (service, editor) = keyboard(field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        service.focus(field(InputType.TYPE_CLASS_TEXT))
        assertFalse(service.uiState.value.secureField)
        val before = service.counters()
        val typed = service.type()
        assertTrue("the keyboard takes the letters", typed.downs.dropLast(1).all { it })
        assertTrue("teh is corrected: ${editor.text}", editor.text.startsWith("hello sunshine the"))
        assertTrue("the words are taken in", service.waiting() > 0 || service.learnedCount() > 0)
        assertFalse("and counted", before == service.counters())
    }

    /** What the focused field does with `hello sunshine teh` and Enter. */
    private fun treatment(service: FieldKeyboard, editor: RecordingEditor): List<Any> {
        val typed = service.type()
        return listOf(typed.downs, editor.text.toString(), service.uiState.value.fieldKind)
    }

    @Test
    fun `the field kind follows the focused field, email to plain and back`() {
        val email = field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val plain = field(InputType.TYPE_CLASS_TEXT)
        for ((from, to) in listOf(email to plain, plain to email)) {
            // The reference: [to] as startInputView would have left it.
            val expected = keyboard(to).let { (service, editor) -> treatment(service, editor) }
            val actual = keyboard(from).let { (service, editor) ->
                service.focus(to)
                treatment(service, editor)
            }
            assertEquals("0x${Integer.toHexString(from.inputType)} -> 0x${Integer.toHexString(to.inputType)}", expected, actual)
        }
        // And the two really are treated apart, or the comparison shows nothing.
        val emailOwn = keyboard(email).let { (service, editor) -> treatment(service, editor) }
        val plainOwn = keyboard(plain).let { (service, editor) -> treatment(service, editor) }
        assertFalse("email and plain fields typed alike", emailOwn == plainOwn)
    }

    private companion object {
        const val KEY_MS = 150L

        fun field(type: Int) = EditorInfo().also { it.inputType = type }

        fun Int.isPassword(): Boolean {
            val variation = this and InputType.TYPE_MASK_VARIATION
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }

        fun Int.isEmail(): Boolean = this and InputType.TYPE_MASK_VARIATION ==
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }
}
